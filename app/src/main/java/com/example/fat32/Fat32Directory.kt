package com.example.fat32

import com.example.block.BlockDevice
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Directory entry data model.
 */
data class Fat32DirectoryEntry(
    val name: String,
    val shortName: String,
    val attributes: Int,
    var firstCluster: Long,
    var fileSizeBytes: Long
) {
    val isDirectory: Boolean get() = (attributes and ATTR_DIRECTORY) != 0
    val isVolumeId: Boolean get() = (attributes and ATTR_VOLUME_ID) != 0
    val isReadOnly: Boolean get() = (attributes and ATTR_READ_ONLY) != 0

    companion object {
        const val ATTR_READ_ONLY = 0x01
        const val ATTR_HIDDEN = 0x02
        const val ATTR_SYSTEM = 0x04
        const val ATTR_VOLUME_ID = 0x08
        const val ATTR_DIRECTORY = 0x10
        const val ATTR_ARCHIVE = 0x20
        const val ATTR_LONG_NAME = 0x0F
    }
}

/**
 * Exact on-disk location of a directory entry for fast in-place metadata updates.
 */
data class DirectoryEntryLocation(
    val lba: Long,
    val offsetInSector: Int,
    val cluster: Long
)

/**
 * FAT32 Directory Manager.
 *
 * Implements standard 8.3 and Long File Name (LFN) directory parsing, entry allocation,
 * path traversal, cluster-chain auto-expansion, and '.' / '..' subdirectory initialization.
 */
class Fat32Directory(
    private val device: BlockDevice,
    private val bootSector: Fat32BootSector,
    private val fatTable: Fat32Table,
    private val clusterAllocator: Fat32ClusterAllocator
) {
    private val sectorSize = bootSector.sectorSizeBytes
    private val clusterSize = bootSector.clusterSizeBytes
    private val sectorsPerCluster = bootSector.sectorsPerCluster

    /**
     * Lists all active directory entries in the specified cluster chain.
     */
    suspend fun listEntries(dirCluster: Long): List<Pair<Fat32DirectoryEntry, DirectoryEntryLocation>> {
        val clusters = fatTable.getClusterChain(dirCluster)
        if (clusters.isEmpty()) return emptyList()

        val results = mutableListOf<Pair<Fat32DirectoryEntry, DirectoryEntryLocation>>()
        val sectorBytes = ByteArray(sectorSize)

        val lfnParts = mutableMapOf<Int, String>()
        var expectedChecksum: Byte? = null

        for (cluster in clusters) {
            val startLba = bootSector.clusterToLba(cluster)
            for (sec in 0 until sectorsPerCluster) {
                val currentLba = startLba + sec
                device.read(currentLba, 1, sectorBytes)

                for (offset in 0 until sectorSize step 32) {
                    val firstByte = sectorBytes[offset].toInt() and 0xFF
                    if (firstByte == 0x00) {
                        // End of directory entries
                        return results
                    }
                    if (firstByte == 0xE5) {
                        // Deleted entry - discard any pending LFN
                        lfnParts.clear()
                        expectedChecksum = null
                        continue
                    }

                    val attr = sectorBytes[offset + 11].toInt() and 0xFF
                    if (attr == Fat32DirectoryEntry.ATTR_LONG_NAME) {
                        // Long File Name sub-component
                        val seq = firstByte and 0x1F
                        val chk = sectorBytes[offset + 13]
                        if (expectedChecksum == null || expectedChecksum == chk) {
                            expectedChecksum = chk
                            val lfnChars = readLfnChars(sectorBytes, offset)
                            lfnParts[seq] = lfnChars
                        } else {
                            // Checksum mismatch, reset
                            lfnParts.clear()
                            expectedChecksum = chk
                            val lfnChars = readLfnChars(sectorBytes, offset)
                            lfnParts[seq] = lfnChars
                        }
                    } else {
                        // Standard 8.3 Directory Entry
                        val shortNameBytes = sectorBytes.copyOfRange(offset, offset + 11)
                        val shortNameStr = parseShortName(shortNameBytes)

                        val computedChecksum = computeLfnChecksum(shortNameBytes)
                        val fullFileName = if (expectedChecksum != null && expectedChecksum == computedChecksum && lfnParts.isNotEmpty()) {
                            // Reassemble LFN parts in sequence order 1..N
                            val maxSeq = lfnParts.keys.maxOrNull() ?: 1
                            val sb = java.lang.StringBuilder()
                            for (s in 1..maxSeq) {
                                lfnParts[s]?.let { sb.append(it) }
                            }
                            sb.toString()
                        } else {
                            shortNameStr
                        }

                        // Clear LFN state
                        lfnParts.clear()
                        expectedChecksum = null

                        // Skip Volume ID entries from file listings unless explicitly needed
                        if ((attr and Fat32DirectoryEntry.ATTR_VOLUME_ID) != 0 && (attr and Fat32DirectoryEntry.ATTR_DIRECTORY) == 0) {
                            continue
                        }

                        val buf = ByteBuffer.wrap(sectorBytes).order(ByteOrder.LITTLE_ENDIAN)
                        val clusterHigh = buf.getShort(offset + 20).toLong() and 0xFFFFL
                        val clusterLow = buf.getShort(offset + 26).toLong() and 0xFFFFL
                        val entryCluster = (clusterHigh shl 16) or clusterLow
                        val fileSize = buf.getInt(offset + 28).toLong() and 0xFFFFFFFFL

                        val entry = Fat32DirectoryEntry(
                            name = fullFileName,
                            shortName = shortNameStr,
                            attributes = attr,
                            firstCluster = entryCluster,
                            fileSizeBytes = fileSize
                        )
                        val loc = DirectoryEntryLocation(
                            lba = currentLba,
                            offsetInSector = offset,
                            cluster = cluster
                        )
                        results.add(Pair(entry, loc))
                    }
                }
            }
        }

        return results
    }

    /**
     * Finds an entry by name (case-insensitive) in the given directory cluster.
     */
    suspend fun findEntry(dirCluster: Long, name: String): Pair<Fat32DirectoryEntry, DirectoryEntryLocation>? {
        val entries = listEntries(dirCluster)
        return entries.firstOrNull { it.first.name.equals(name, ignoreCase = true) || it.first.shortName.equals(name, ignoreCase = true) }
    }

    /**
     * Adds a file or directory entry to the specified directory cluster.
     * Automatically allocates LFN entries and expands the directory cluster chain if needed.
     */
    suspend fun addEntry(
        dirCluster: Long,
        entryName: String,
        attributes: Int,
        firstCluster: Long,
        fileSizeBytes: Long
    ): DirectoryEntryLocation {
        val shortName11 = generateShortName(entryName, dirCluster)
        val shortNameBytes = shortName11.toByteArray(Charsets.US_ASCII)
        val checksum = computeLfnChecksum(shortNameBytes)

        val needsLfn = requiresLfn(entryName, shortName11)
        val lfnEntries = if (needsLfn) generateLfnEntries(entryName, checksum) else emptyList()
        val totalSlotsNeeded = lfnEntries.size + 1

        val (slotLba, slotOffset, targetCluster) = findFreeSlots(dirCluster, totalSlotsNeeded)

        val sectorBytes = ByteArray(sectorSize)
        var currentLba = slotLba
        var currentOffset = slotOffset
        var activeCluster = targetCluster

        // Write LFN entries (in reverse order as generated)
        for (lfnSlot in lfnEntries) {
            writeSlotAt(currentLba, currentOffset, lfnSlot, sectorBytes)
            currentOffset += 32
            if (currentOffset >= sectorSize) {
                currentOffset = 0
                currentLba++
            }
        }

        // Write 8.3 Short Entry
        val shortEntryBytes = ByteArray(32)
        val sBuf = ByteBuffer.wrap(shortEntryBytes).order(ByteOrder.LITTLE_ENDIAN)
        sBuf.put(shortNameBytes)
        sBuf.put(attributes.toByte())
        sBuf.put(0.toByte()) // NTRes
        sBuf.put(0.toByte()) // Creation time tenths
        sBuf.putShort(0.toShort()) // Creation time
        sBuf.putShort(0x5241.toShort()) // Creation date (2021-02-01)
        sBuf.putShort(0x5241.toShort()) // Last access date
        sBuf.putShort((firstCluster shr 16).toShort()) // First cluster high
        sBuf.putShort(0.toShort()) // Last write time
        sBuf.putShort(0x5241.toShort()) // Last write date
        sBuf.putShort((firstCluster and 0xFFFFL).toShort()) // First cluster low
        sBuf.putInt((fileSizeBytes and 0xFFFFFFFFL).toInt()) // File size

        writeSlotAt(currentLba, currentOffset, shortEntryBytes, sectorBytes)

        return DirectoryEntryLocation(
            lba = currentLba,
            offsetInSector = currentOffset,
            cluster = activeCluster
        )
    }

    /**
     * Updates an existing 32-byte short entry on disk (e.g. updating file size or cluster pointer).
     */
    suspend fun updateEntry(location: DirectoryEntryLocation, entry: Fat32DirectoryEntry) {
        val sectorBytes = ByteArray(sectorSize)
        device.read(location.lba, 1, sectorBytes)

        val off = location.offsetInSector
        val buf = ByteBuffer.wrap(sectorBytes).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(off + 11, entry.attributes.toByte())
        buf.putShort(off + 20, (entry.firstCluster shr 16).toShort())
        buf.putShort(off + 26, (entry.firstCluster and 0xFFFFL).toShort())
        buf.putInt(off + 28, (entry.fileSizeBytes and 0xFFFFFFFFL).toInt())

        device.write(location.lba, 1, sectorBytes)
    }

    /**
     * Initializes a newly allocated cluster as a subdirectory with '.' and '..' entries.
     */
    suspend fun initializeSubdirectoryCluster(subCluster: Long, parentCluster: Long) {
        val clusterLba = bootSector.clusterToLba(subCluster)
        val sectorBytes = ByteArray(sectorSize) // zeros

        // 1. '.' entry pointing to subCluster
        val dotEntry = createDotEntry(".          ", subCluster)
        System.arraycopy(dotEntry, 0, sectorBytes, 0, 32)

        // 2. '..' entry pointing to parentCluster
        // For FAT32, if parent is root directory (cluster 2), cluster pointer in '..' is typically 0
        val parentClusterPointer = if (parentCluster == bootSector.rootCluster) 0L else parentCluster
        val dotDotEntry = createDotEntry("..         ", parentClusterPointer)
        System.arraycopy(dotDotEntry, 0, sectorBytes, 32, 32)

        // Write first sector with . and .. entries
        device.write(clusterLba, 1, sectorBytes)

        // Zero out remaining sectors of this cluster
        val zeroBuffer = ByteArray(sectorSize)
        for (sec in 1 until sectorsPerCluster) {
            device.write(clusterLba + sec, 1, zeroBuffer)
        }
    }

    private fun createDotEntry(name11: String, targetCluster: Long): ByteArray {
        val entry = ByteArray(32)
        val buf = ByteBuffer.wrap(entry).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(name11.toByteArray(Charsets.US_ASCII))
        buf.put(Fat32DirectoryEntry.ATTR_DIRECTORY.toByte())
        buf.position(20)
        buf.putShort((targetCluster shr 16).toShort())
        buf.position(26)
        buf.putShort((targetCluster and 0xFFFFL).toShort())
        buf.putInt(0) // Directory size is 0
        return entry
    }

    private suspend fun writeSlotAt(lba: Long, offset: Int, slotBytes: ByteArray, scratch: ByteArray) {
        device.read(lba, 1, scratch)
        System.arraycopy(slotBytes, 0, scratch, offset, 32)
        device.write(lba, 1, scratch)
    }

    /**
     * Finds [slotsNeeded] consecutive free 32-byte slots in the directory's cluster chain.
     * Expands the cluster chain if necessary.
     */
    private suspend fun findFreeSlots(dirCluster: Long, slotsNeeded: Int): Triple<Long, Int, Long> {
        val clusters = fatTable.getClusterChain(dirCluster)
        val sectorBytes = ByteArray(sectorSize)

        for (cluster in clusters) {
            val startLba = bootSector.clusterToLba(cluster)
            for (sec in 0 until sectorsPerCluster) {
                val currentLba = startLba + sec
                device.read(currentLba, 1, sectorBytes)

                var freeCount = 0
                var startOffset = -1

                for (offset in 0 until sectorSize step 32) {
                    val b = sectorBytes[offset].toInt() and 0xFF
                    if (b == 0x00 || b == 0xE5) {
                        if (freeCount == 0) startOffset = offset
                        freeCount++
                        if (freeCount == slotsNeeded) {
                            return Triple(currentLba, startOffset, cluster)
                        }
                    } else {
                        freeCount = 0
                        startOffset = -1
                    }
                }
            }
        }

        // Directory is full: allocate a new cluster and link to directory chain
        val newCluster = clusterAllocator.allocateCluster(zeroOut = true)
        val lastCluster = clusters.last()
        fatTable.setEntry(lastCluster, newCluster)
        fatTable.setEntry(newCluster, Fat32Table.CLUSTER_EOC)

        val newLba = bootSector.clusterToLba(newCluster)
        return Triple(newLba, 0, newCluster)
    }

    private fun requiresLfn(name: String, shortName11: String): Boolean {
        if (name == "." || name == "..") return false
        val cleanShort = parseShortName(shortName11.toByteArray(Charsets.US_ASCII))
        return !name.equals(cleanShort, ignoreCase = false)
    }

    private fun generateShortName(name: String, dirCluster: Long): String {
        if (name == ".") return ".          "
        if (name == "..") return "..         "

        val dotIdx = name.lastIndexOf('.')
        val (base, ext) = if (dotIdx > 0) {
            Pair(name.substring(0, dotIdx), name.substring(dotIdx + 1))
        } else {
            Pair(name, "")
        }

        val cleanBase = base.uppercase().filter { it in 'A'..'Z' || it in '0'..'9' || it == '_' || it == '-' }
        val cleanExt = ext.uppercase().filter { it in 'A'..'Z' || it in '0'..'9' || it == '_' || it == '-' }

        val paddedBase = if (cleanBase.length <= 8) {
            cleanBase.padEnd(8, ' ')
        } else {
            cleanBase.take(6) + "~1"
        }

        val paddedExt = cleanExt.take(3).padEnd(3, ' ')
        return (paddedBase + paddedExt).take(11)
    }

    private fun parseShortName(bytes11: ByteArray): String {
        val base = String(bytes11, 0, 8, Charsets.US_ASCII).trimEnd()
        val ext = String(bytes11, 8, 3, Charsets.US_ASCII).trimEnd()
        return if (ext.isNotEmpty()) "$base.$ext" else base
    }

    fun computeLfnChecksum(shortName11: ByteArray): Byte {
        var sum = 0
        for (i in 0 until 11) {
            sum = (((sum and 1) shl 7) or ((sum and 0xFE) shr 1)) + (shortName11[i].toInt() and 0xFF)
        }
        return sum.toByte()
    }

    private fun generateLfnEntries(longName: String, checksum: Byte): List<ByteArray> {
        val charsNeeded = longName.length
        val numEntries = (charsNeeded + 12) / 13
        val result = mutableListOf<ByteArray>()

        for (seq in 1..numEntries) {
            val entry = ByteArray(32)
            val isLast = (seq == numEntries)
            entry[0] = (if (isLast) (seq or 0x40) else seq).toByte()
            entry[11] = Fat32DirectoryEntry.ATTR_LONG_NAME.toByte()
            entry[12] = 0.toByte() // Type
            entry[13] = checksum
            entry[26] = 0.toByte() // Cluster low = 0
            entry[27] = 0.toByte()

            val charOffset = (seq - 1) * 13
            val slice = longName.drop(charOffset).take(13)

            val buf = ByteBuffer.wrap(entry).order(ByteOrder.LITTLE_ENDIAN)

            fun putLfnChar(charIdx: Int, byteOffset: Int) {
                if (charIdx < slice.length) {
                    buf.putShort(byteOffset, slice[charIdx].code.toShort())
                } else if (charIdx == slice.length) {
                    buf.putShort(byteOffset, 0.toShort()) // null terminator
                } else {
                    buf.putShort(byteOffset, 0xFFFF.toShort()) // padding
                }
            }

            // Chars 1..5 -> offset 1..10
            for (c in 0 until 5) putLfnChar(c, 1 + c * 2)
            // Chars 6..11 -> offset 14..25
            for (c in 0 until 6) putLfnChar(5 + c, 14 + c * 2)
            // Chars 12..13 -> offset 28..31
            for (c in 0 until 2) putLfnChar(11 + c, 28 + c * 2)

            result.add(0, entry) // Store in reverse order so last seq is first on disk
        }

        return result
    }

    private fun readLfnChars(sectorBytes: ByteArray, offset: Int): String {
        val buf = ByteBuffer.wrap(sectorBytes).order(ByteOrder.LITTLE_ENDIAN)
        val sb = java.lang.StringBuilder()

        fun readChar(byteOffset: Int) {
            val code = buf.getShort(offset + byteOffset).toInt() and 0xFFFF
            if (code != 0x0000 && code != 0xFFFF) {
                sb.append(code.toChar())
            }
        }

        for (c in 0 until 5) readChar(1 + c * 2)
        for (c in 0 until 6) readChar(14 + c * 2)
        for (c in 0 until 2) readChar(28 + c * 2)

        return sb.toString()
    }
}
