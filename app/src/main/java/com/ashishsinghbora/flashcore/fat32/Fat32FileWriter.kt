package com.ashishsinghbora.flashcore.fat32

import com.ashishsinghbora.flashcore.block.BlockDevice
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import java.io.InputStream

/**
 * High-performance FAT32 File Reader and Writer.
 *
 * Handles file creation, block writing, multi-cluster allocation, append operations,
 * streaming reads/writes, and directory entry synchronization.
 */
class Fat32FileWriter(
    private val device: BlockDevice,
    private val bootSector: Fat32BootSector,
    private val fatTable: Fat32Table,
    private val clusterAllocator: Fat32ClusterAllocator,
    private val directory: Fat32Directory
) {
    private val sectorSize = bootSector.sectorSizeBytes
    private val clusterSize = bootSector.clusterSizeBytes
    private val sectorsPerCluster = bootSector.sectorsPerCluster

    /**
     * Writes byte array data to a file in [dirCluster]. Replaces any existing file contents.
     */
    suspend fun writeFile(
        dirCluster: Long,
        name: String,
        data: ByteArray,
        attributes: Int = Fat32DirectoryEntry.ATTR_ARCHIVE
    ): Fat32DirectoryEntry {
        val existing = directory.findEntry(dirCluster, name)
        if (existing != null && existing.first.isDirectory) {
            throw IllegalArgumentException("Cannot overwrite directory '$name' as a file")
        }

        // Free old clusters if updating an existing file
        if (existing != null && existing.first.firstCluster >= 2L) {
            clusterAllocator.freeClusterChain(existing.first.firstCluster)
        }

        val totalBytes = data.size.toLong()
        val firstCluster: Long

        if (totalBytes == 0L) {
            firstCluster = 0L
        } else {
            val clustersNeeded = ((totalBytes + clusterSize - 1L) / clusterSize).toInt()
            val chain = clusterAllocator.allocateClusterChain(clustersNeeded, zeroOut = false)
            firstCluster = chain.first()

            var bytesWritten = 0L
            for (cluster in chain) {
                val startLba = bootSector.clusterToLba(cluster)
                val sectorBuffer = ByteArray(sectorSize)

                for (sec in 0 until sectorsPerCluster) {
                    if (bytesWritten >= totalBytes) {
                        // Pad remainder of cluster with zeros
                        sectorBuffer.fill(0)
                        device.write(startLba + sec, 1, sectorBuffer)
                    } else {
                        val toWrite = minOf(sectorSize.toLong(), totalBytes - bytesWritten).toInt()
                        System.arraycopy(data, bytesWritten.toInt(), sectorBuffer, 0, toWrite)
                        if (toWrite < sectorSize) {
                            sectorBuffer.fill(0, toWrite, sectorSize)
                        }
                        device.write(startLba + sec, 1, sectorBuffer)
                        bytesWritten += toWrite
                    }
                }
            }
        }

        val updatedEntry: Fat32DirectoryEntry
        if (existing != null) {
            updatedEntry = existing.first.copy(
                firstCluster = firstCluster,
                fileSizeBytes = totalBytes
            )
            directory.updateEntry(existing.second, updatedEntry)
        } else {
            directory.addEntry(
                dirCluster = dirCluster,
                entryName = name,
                attributes = attributes,
                firstCluster = firstCluster,
                fileSizeBytes = totalBytes
            )
            updatedEntry = Fat32DirectoryEntry(
                name = name,
                shortName = name.uppercase(),
                attributes = attributes,
                firstCluster = firstCluster,
                fileSizeBytes = totalBytes
            )
        }

        fatTable.flush()
        device.flush()
        return updatedEntry
    }

    /**
     * Streams data from an [InputStream] into a FAT32 file without loading the entire payload into RAM.
     */
    suspend fun writeFileStream(
        dirCluster: Long,
        name: String,
        inputStream: InputStream,
        totalBytes: Long,
        attributes: Int = Fat32DirectoryEntry.ATTR_ARCHIVE,
        onProgress: ((bytesWritten: Long, total: Long) -> Unit)? = null
    ): Fat32DirectoryEntry {
        val existing = directory.findEntry(dirCluster, name)
        if (existing != null && existing.first.isDirectory) {
            throw IllegalArgumentException("Cannot overwrite directory '$name' as a file")
        }

        if (existing != null && existing.first.firstCluster >= 2L) {
            clusterAllocator.freeClusterChain(existing.first.firstCluster)
        }

        val firstCluster: Long
        if (totalBytes == 0L) {
            firstCluster = 0L
        } else {
            val clustersNeeded = ((totalBytes + clusterSize - 1L) / clusterSize).toInt()
            val chain = clusterAllocator.allocateClusterChain(clustersNeeded, zeroOut = false)
            firstCluster = chain.first()

            val sectorBuffer = ByteArray(sectorSize)
            var totalWritten = 0L

            for (cluster in chain) {
                val startLba = bootSector.clusterToLba(cluster)

                for (sec in 0 until sectorsPerCluster) {
                    if (totalWritten >= totalBytes) {
                        sectorBuffer.fill(0)
                        device.write(startLba + sec, 1, sectorBuffer)
                    } else {
                        val toRead = minOf(sectorSize.toLong(), totalBytes - totalWritten).toInt()
                        var bytesRead = 0
                        while (bytesRead < toRead) {
                            val r = inputStream.read(sectorBuffer, bytesRead, toRead - bytesRead)
                            if (r <= 0) break
                            bytesRead += r
                        }
                        if (bytesRead < sectorSize) {
                            sectorBuffer.fill(0, bytesRead, sectorSize)
                        }
                        device.write(startLba + sec, 1, sectorBuffer)
                        totalWritten += bytesRead
                        onProgress?.invoke(totalWritten, totalBytes)
                    }
                }
            }
        }

        val updatedEntry: Fat32DirectoryEntry
        if (existing != null) {
            updatedEntry = existing.first.copy(
                firstCluster = firstCluster,
                fileSizeBytes = totalBytes
            )
            directory.updateEntry(existing.second, updatedEntry)
        } else {
            directory.addEntry(
                dirCluster = dirCluster,
                entryName = name,
                attributes = attributes,
                firstCluster = firstCluster,
                fileSizeBytes = totalBytes
            )
            updatedEntry = Fat32DirectoryEntry(
                name = name,
                shortName = name.uppercase(),
                attributes = attributes,
                firstCluster = firstCluster,
                fileSizeBytes = totalBytes
            )
        }

        fatTable.flush()
        device.flush()
        return updatedEntry
    }

    /**
     * Appends data to an existing file or creates a new file if it does not exist.
     */
    suspend fun appendFile(
        dirCluster: Long,
        name: String,
        appendData: ByteArray
    ): Fat32DirectoryEntry {
        if (appendData.isEmpty()) {
            val existing = directory.findEntry(dirCluster, name)
            if (existing != null) return existing.first
            return writeFile(dirCluster, name, ByteArray(0))
        }

        val existing = directory.findEntry(dirCluster, name)
        if (existing == null || existing.first.fileSizeBytes == 0L || existing.first.firstCluster < 2L) {
            return writeFile(dirCluster, name, appendData)
        }

        val entry = existing.first
        val oldSize = entry.fileSizeBytes
        val newSize = oldSize + appendData.size

        val chain = fatTable.getClusterChain(entry.firstCluster).toMutableList()
        val lastCluster = chain.last()

        val offsetInLastCluster = (oldSize % clusterSize).toInt()
        val spaceInLastCluster = if (offsetInLastCluster == 0) 0 else (clusterSize - offsetInLastCluster).toInt()
        val writeToLast = minOf(appendData.size, spaceInLastCluster)

        var appendOffset = 0

        // 1. Fill available space in the last existing cluster
        if (writeToLast > 0) {
            val lastClusterStartLba = bootSector.clusterToLba(lastCluster)
            val sectorBuffer = ByteArray(sectorSize)

            val startSec = offsetInLastCluster / sectorSize
            val startSecOffset = offsetInLastCluster % sectorSize
            var bytesWrittenToLast = 0

            for (sec in startSec until sectorsPerCluster) {
                if (bytesWrittenToLast >= writeToLast) break
                val lba = lastClusterStartLba + sec
                device.read(lba, 1, sectorBuffer)

                val secOffset = if (sec == startSec) startSecOffset else 0
                val canWriteSec = minOf(sectorSize - secOffset, writeToLast - bytesWrittenToLast)

                System.arraycopy(appendData, appendOffset, sectorBuffer, secOffset, canWriteSec)
                device.write(lba, 1, sectorBuffer)

                bytesWrittenToLast += canWriteSec
                appendOffset += canWriteSec
            }
        }

        // 2. Allocate extra clusters if needed for the rest of appendData
        val remainingBytes = appendData.size - appendOffset
        if (remainingBytes > 0) {
            val extraClustersNeeded = ((remainingBytes + clusterSize - 1L) / clusterSize).toInt()
            val extraChain = clusterAllocator.allocateClusterChain(extraClustersNeeded, zeroOut = false)

            // Link lastCluster to the new chain in FAT
            fatTable.setEntry(lastCluster, extraChain.first())

            val sectorBuffer = ByteArray(sectorSize)
            var bytesWrittenExtra = 0

            for (cluster in extraChain) {
                val startLba = bootSector.clusterToLba(cluster)
                for (sec in 0 until sectorsPerCluster) {
                    if (bytesWrittenExtra >= remainingBytes) {
                        sectorBuffer.fill(0)
                        device.write(startLba + sec, 1, sectorBuffer)
                    } else {
                        val toWrite = minOf(sectorSize, remainingBytes - bytesWrittenExtra)
                        System.arraycopy(appendData, appendOffset + bytesWrittenExtra, sectorBuffer, 0, toWrite)
                        if (toWrite < sectorSize) {
                            sectorBuffer.fill(0, toWrite, sectorSize)
                        }
                        device.write(startLba + sec, 1, sectorBuffer)
                        bytesWrittenExtra += toWrite
                    }
                }
            }
        }

        // 3. Update directory entry with new size
        val updatedEntry = entry.copy(fileSizeBytes = newSize)
        directory.updateEntry(existing.second, updatedEntry)

        fatTable.flush()
        device.flush()
        return updatedEntry
    }

    /**
     * Reads the entire contents of a file into a [ByteArray].
     */
    suspend fun readFile(dirCluster: Long, name: String): ByteArray {
        val entry = directory.findEntry(dirCluster, name)?.first
            ?: throw FileNotFoundException("File not found in FAT32 directory: $name")

        if (entry.isDirectory) {
            throw IllegalArgumentException("Cannot read directory '$name' as a file")
        }

        val totalBytes = entry.fileSizeBytes
        if (totalBytes == 0L || entry.firstCluster < 2L) {
            return ByteArray(0)
        }

        val chain = fatTable.getClusterChain(entry.firstCluster)
        val result = ByteArray(totalBytes.toInt())
        val sectorBuffer = ByteArray(sectorSize)
        var totalRead = 0L

        for (cluster in chain) {
            val startLba = bootSector.clusterToLba(cluster)
            for (sec in 0 until sectorsPerCluster) {
                if (totalRead >= totalBytes) break
                val lba = startLba + sec
                device.read(lba, 1, sectorBuffer)

                val toCopy = minOf(sectorSize.toLong(), totalBytes - totalRead).toInt()
                System.arraycopy(sectorBuffer, 0, result, totalRead.toInt(), toCopy)
                totalRead += toCopy
            }
            if (totalRead >= totalBytes) break
        }

        return result
    }

    /**
     * Opens an [InputStream] over the file's cluster chain.
     */
    suspend fun readFileStream(dirCluster: Long, name: String): InputStream {
        val bytes = readFile(dirCluster, name)
        return ByteArrayInputStream(bytes)
    }
}
