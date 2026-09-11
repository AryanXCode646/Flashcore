package com.ashishsinghbora.flashcore.partition

import com.ashishsinghbora.flashcore.block.BlockDevice
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.zip.CRC32

/**
 * Unified Partition Engine for MBR and GPT Partition Tables.
 *
 * Implements building, parsing, serialization, and cryptographic/structural validation
 * conforming to UEFI Specification v2.10 and standard IBM PC BIOS MBR conventions.
 */
object PartitionEngine {

    const val MBR_BOOT_SIGNATURE: Short = 0xAA55.toShort()
    const val GPT_SIGNATURE: String = "EFI PART"
    const val GPT_REVISION: Int = 0x00010000 // 1.0
    const val GPT_DEFAULT_HEADER_SIZE: Int = 92
    const val GPT_DEFAULT_ENTRY_SIZE: Int = 128
    const val GPT_DEFAULT_ENTRY_COUNT: Int = 128
    const val GPT_TABLE_SECTOR_COUNT: Int = 32 // (128 * 128) / 512

    // --- MBR Operations ---

    /**
     * Serializes an [MbrPartitionTable] into a standard 512-byte MBR sector.
     */
    fun buildMbrBytes(table: MbrPartitionTable): ByteArray {
        val mbr = ByteArray(512)
        val buf = ByteBuffer.wrap(mbr).order(ByteOrder.LITTLE_ENDIAN)

        // 1. Bootstrap code (up to 440 bytes)
        if (table.bootstrapCode != null) {
            val len = minOf(table.bootstrapCode.size, 440)
            System.arraycopy(table.bootstrapCode, 0, mbr, 0, len)
        }

        // 2. Disk Signature at offset 440
        buf.position(440)
        buf.putInt(table.diskSignature)
        buf.putShort(0.toShort()) // Reserved

        // 3. Four 16-byte Partition Entries at offset 446
        for (i in 0 until 4) {
            buf.position(446 + (i * 16))
            if (i < table.partitions.size) {
                val p = table.partitions[i]
                buf.put(if (p.bootable) 0x80.toByte() else 0x00.toByte())

                // Starting CHS (0x00, 0x02, 0x00 dummy for LBA)
                buf.put(0x00.toByte())
                buf.put(0x02.toByte())
                buf.put(0x00.toByte())

                buf.put(p.mbrType ?: 0x83.toByte())

                // Ending CHS (0xFF, 0xFF, 0xFF dummy for LBA)
                buf.put(0xFF.toByte())
                buf.put(0xFF.toByte())
                buf.put(0xFF.toByte())

                buf.putInt((p.firstLba and 0xFFFFFFFFL).toInt())
                buf.putInt((p.sectorCount and 0xFFFFFFFFL).toInt())
            } else {
                for (j in 0 until 16) buf.put(0.toByte())
            }
        }

        // 4. Boot Signature 0x55, 0xAA at offset 510
        buf.position(510)
        buf.put(0x55.toByte())
        buf.put(0xAA.toByte())

        return mbr
    }

    /**
     * Builds a single 0xEE Protective MBR spanning the disk.
     */
    fun buildProtectiveMbr(totalDiskSectors: Long): ByteArray {
        val count = minOf(totalDiskSectors - 1L, 0xFFFFFFFFL)
        val protectivePartition = Partition(
            index = 1,
            firstLba = 1L,
            lastLba = count,
            name = "Protective MBR",
            mbrType = MbrBuilder.TYPE_GPT_PROTECTIVE,
            bootable = false
        )
        return buildMbrBytes(
            MbrPartitionTable(
                partitions = listOf(protectivePartition),
                totalDiskSectors = totalDiskSectors,
                isProtective = true
            )
        )
    }

    /**
     * Parses an MBR sector (512 bytes) into an [MbrPartitionTable].
     */
    fun parseMbr(bytes: ByteArray, totalDiskSectors: Long): MbrPartitionTable {
        require(bytes.size >= 512) { "MBR buffer must be at least 512 bytes (${bytes.size})" }
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        // Verify Boot Signature
        buf.position(510)
        val sig = buf.short
        if (sig != MBR_BOOT_SIGNATURE) {
            throw IllegalArgumentException("Invalid MBR boot signature: 0x${Integer.toHexString(sig.toInt() and 0xFFFF)} (expected 0xAA55)")
        }

        buf.position(440)
        val diskSig = buf.int

        val partitions = mutableListOf<Partition>()
        var isProtective = false

        for (i in 0 until 4) {
            buf.position(446 + (i * 16))
            val status = buf.get()
            val startChs = ByteArray(3).also { buf.get(it) }
            val partitionType = buf.get()
            val endChs = ByteArray(3).also { buf.get(it) }
            val startLba = buf.int.toLong() and 0xFFFFFFFFL
            val sectorCount = buf.int.toLong() and 0xFFFFFFFFL

            if (partitionType != 0x00.toByte() && sectorCount > 0L) {
                if (partitionType == MbrBuilder.TYPE_GPT_PROTECTIVE) {
                    isProtective = true
                }
                partitions.add(
                    Partition(
                        index = i + 1,
                        firstLba = startLba,
                        lastLba = startLba + sectorCount - 1L,
                        name = "Partition ${i + 1}",
                        mbrType = partitionType,
                        bootable = (status.toInt() and 0x80) != 0
                    )
                )
            }
        }

        return MbrPartitionTable(
            partitions = partitions,
            totalDiskSectors = totalDiskSectors,
            diskSignature = diskSig,
            isProtective = isProtective
        )
    }

    // --- GPT Operations ---

    data class GptSerializedLayout(
        val protectiveMbr: ByteArray,
        val primaryHeader: ByteArray,
        val primaryPartitionTable: ByteArray,
        val backupPartitionTable: ByteArray,
        val backupHeader: ByteArray
    )

    /**
     * Serializes a [GptPartitionTable] into complete UEFI structures (Protective MBR, Primary GPT, Backup GPT).
     */
    fun buildGptLayout(table: GptPartitionTable): GptSerializedLayout {
        val totalSectors = table.totalDiskSectors
        val tableSectors = (table.partitionEntryCount * table.partitionEntrySizeBytes + table.sectorSizeBytes - 1) / table.sectorSizeBytes
        val firstUsable = table.firstUsableLba
        val lastUsable = table.lastUsableLba

        // 1. Build Partition Entry Array (16,384 bytes for 128 entries x 128 bytes)
        val tableBytes = ByteArray(table.partitionEntryCount * table.partitionEntrySizeBytes)
        val tBuf = ByteBuffer.wrap(tableBytes).order(ByteOrder.LITTLE_ENDIAN)

        for (i in 0 until table.partitionEntryCount) {
            tBuf.position(i * table.partitionEntrySizeBytes)
            if (i < table.partitions.size) {
                val p = table.partitions[i]
                GptGuidHelper.writeMixedEndian(tBuf, p.typeGuid ?: GptGuidHelper.GUID_LINUX_FILESYSTEM)
                GptGuidHelper.writeMixedEndian(tBuf, p.uniqueGuid ?: UUID.randomUUID())
                tBuf.putLong(p.firstLba)
                tBuf.putLong(p.lastLba)
                tBuf.putLong(p.attributes)

                // Name: 36 UTF-16LE characters (72 bytes)
                val chars = p.name.toCharArray()
                for (c in 0 until 36) {
                    if (c < chars.size) tBuf.putChar(chars[c]) else tBuf.putChar('\u0000')
                }
            }
        }

        // 2. Compute Partition Table Array CRC32
        val tableCrc = computeCrc32(tableBytes)

        // 3. Build Primary Header (LBA 1)
        val primaryHeader = buildHeaderSector(
            myLba = 1L,
            alternateLba = totalSectors - 1L,
            firstUsableLba = firstUsable,
            lastUsableLba = lastUsable,
            diskGuid = table.diskGuid,
            partitionEntriesLba = 2L,
            entryCount = table.partitionEntryCount,
            entrySize = table.partitionEntrySizeBytes,
            partitionTableCrc = tableCrc,
            sectorSize = table.sectorSizeBytes
        )

        // 4. Build Backup Header (LBA Last)
        val backupEntriesLba = totalSectors - 1L - tableSectors
        val backupHeader = buildHeaderSector(
            myLba = totalSectors - 1L,
            alternateLba = 1L,
            firstUsableLba = firstUsable,
            lastUsableLba = lastUsable,
            diskGuid = table.diskGuid,
            partitionEntriesLba = backupEntriesLba,
            entryCount = table.partitionEntryCount,
            entrySize = table.partitionEntrySizeBytes,
            partitionTableCrc = tableCrc,
            sectorSize = table.sectorSizeBytes
        )

        // 5. Build Protective MBR
        val protectiveMbr = buildProtectiveMbr(totalSectors)

        return GptSerializedLayout(
            protectiveMbr = protectiveMbr,
            primaryHeader = primaryHeader,
            primaryPartitionTable = tableBytes,
            backupPartitionTable = tableBytes.clone(),
            backupHeader = backupHeader
        )
    }

    private fun buildHeaderSector(
        myLba: Long,
        alternateLba: Long,
        firstUsableLba: Long,
        lastUsableLba: Long,
        diskGuid: UUID,
        partitionEntriesLba: Long,
        entryCount: Int,
        entrySize: Int,
        partitionTableCrc: Long,
        sectorSize: Int
    ): ByteArray {
        val sector = ByteArray(sectorSize)
        val buf = ByteBuffer.wrap(sector).order(ByteOrder.LITTLE_ENDIAN)

        buf.put(GPT_SIGNATURE.toByteArray(Charsets.US_ASCII))
        buf.putInt(GPT_REVISION)
        buf.putInt(GPT_DEFAULT_HEADER_SIZE)
        buf.putInt(0) // CRC32 placeholder
        buf.putInt(0) // Reserved

        buf.putLong(myLba)
        buf.putLong(alternateLba)
        buf.putLong(firstUsableLba)
        buf.putLong(lastUsableLba)

        GptGuidHelper.writeMixedEndian(buf, diskGuid)

        buf.putLong(partitionEntriesLba)
        buf.putInt(entryCount)
        buf.putInt(entrySize)
        buf.putInt((partitionTableCrc and 0xFFFFFFFFL).toInt())

        // Calculate Header CRC32 across 92 bytes with CRC field zeroed
        val headerCrc = computeHeaderCrc32(sector, GPT_DEFAULT_HEADER_SIZE)
        buf.position(16)
        buf.putInt((headerCrc and 0xFFFFFFFFL).toInt())

        return sector
    }

    /**
     * Parses Primary Header, Partition Entries, and optional Backup Header into a [GptPartitionTable].
     */
    fun parseGpt(
        primaryHeader: ByteArray,
        partitionTableBytes: ByteArray,
        backupHeader: ByteArray? = null,
        totalDiskSectors: Long
    ): GptPartitionTable {
        val validation = validateGptStructures(primaryHeader, partitionTableBytes, backupHeader, totalDiskSectors)
        if (!validation.isValid) {
            throw IllegalArgumentException("Invalid GPT structures: ${validation.errors.joinToString()}")
        }

        val buf = ByteBuffer.wrap(primaryHeader).order(ByteOrder.LITTLE_ENDIAN)
        buf.position(40)
        val firstUsable = buf.long
        val lastUsable = buf.long
        val diskGuid = GptGuidHelper.readMixedEndian(buf)
        buf.position(80)
        val entryCount = buf.int
        val entrySize = buf.int

        val partitions = mutableListOf<Partition>()
        val tBuf = ByteBuffer.wrap(partitionTableBytes).order(ByteOrder.LITTLE_ENDIAN)

        for (i in 0 until entryCount) {
            val offset = i * entrySize
            if (offset + entrySize > partitionTableBytes.size) break

            tBuf.position(offset)
            val typeGuid = GptGuidHelper.readMixedEndian(tBuf)
            val uniqueGuid = GptGuidHelper.readMixedEndian(tBuf)
            val firstLba = tBuf.long
            val lastLba = tBuf.long
            val attributes = tBuf.long

            // Partition name: 36 UTF-16LE chars
            val chars = CharArray(36)
            for (c in 0 until 36) {
                chars[c] = tBuf.char
            }
            val name = String(chars).trimEnd('\u0000')

            // Non-empty partition entry
            if (typeGuid != GptGuidHelper.GUID_UNUSED_ENTRY && firstLba > 0L && lastLba >= firstLba) {
                partitions.add(
                    Partition(
                        index = i + 1,
                        firstLba = firstLba,
                        lastLba = lastLba,
                        name = name,
                        typeGuid = typeGuid,
                        uniqueGuid = uniqueGuid,
                        attributes = attributes
                    )
                )
            }
        }

        return GptPartitionTable(
            partitions = partitions,
            totalDiskSectors = totalDiskSectors,
            diskGuid = diskGuid,
            firstUsableLba = firstUsable,
            lastUsableLba = lastUsable,
            partitionEntryCount = entryCount,
            partitionEntrySizeBytes = entrySize
        )
    }

    /**
     * Validates GPT Primary Header, Backup Header, CRC32, and Partition Arrays.
     */
    fun validateGptStructures(
        primaryHeader: ByteArray,
        partitionTableBytes: ByteArray,
        backupHeader: ByteArray? = null,
        totalDiskSectors: Long
    ): PartitionValidationResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        if (primaryHeader.size < GPT_DEFAULT_HEADER_SIZE) {
            return PartitionValidationResult.invalid(listOf("Primary GPT Header buffer too small (${primaryHeader.size} bytes)"))
        }

        // 1. Signature Check
        val sig = String(primaryHeader, 0, 8, Charsets.US_ASCII)
        if (sig != GPT_SIGNATURE) {
            errors.add("Invalid Primary GPT Header signature: '$sig' (expected '$GPT_SIGNATURE')")
        }

        val buf = ByteBuffer.wrap(primaryHeader).order(ByteOrder.LITTLE_ENDIAN)
        val revision = buf.getInt(8)
        val headerSize = buf.getInt(12)
        val recordedHeaderCrc = buf.getInt(16).toLong() and 0xFFFFFFFFL

        if (headerSize < GPT_DEFAULT_HEADER_SIZE || headerSize > primaryHeader.size) {
            errors.add("Invalid GPT header size: $headerSize")
        }

        // 2. Primary Header CRC32 Check
        val computedHeaderCrc = computeHeaderCrc32(primaryHeader, headerSize)
        if (recordedHeaderCrc != computedHeaderCrc) {
            errors.add("Primary GPT Header CRC32 mismatch: recorded 0x${recordedHeaderCrc.toString(16)}, computed 0x${computedHeaderCrc.toString(16)}")
        }

        val myLba = buf.getLong(24)
        val alternateLba = buf.getLong(32)
        val firstUsable = buf.getLong(40)
        val lastUsable = buf.getLong(48)
        val entryCount = buf.getInt(80)
        val entrySize = buf.getInt(84)
        val recordedTableCrc = buf.getInt(88).toLong() and 0xFFFFFFFFL

        if (myLba != 1L) {
            errors.add("Primary GPT Header MyLBA must be 1 (found $myLba)")
        }

        // 3. Partition Table CRC32 Check
        val expectedTableBytes = entryCount * entrySize
        if (partitionTableBytes.size < expectedTableBytes) {
            errors.add("Partition table array too short: ${partitionTableBytes.size} < $expectedTableBytes")
        } else {
            val computedTableCrc = computeCrc32(partitionTableBytes, 0, expectedTableBytes)
            if (recordedTableCrc != computedTableCrc) {
                errors.add("GPT Partition Array CRC32 mismatch: recorded 0x${recordedTableCrc.toString(16)}, computed 0x${computedTableCrc.toString(16)}")
            }
        }

        // 4. Usable LBA range
        if (firstUsable >= lastUsable) {
            errors.add("Invalid usable LBA range: firstUsable ($firstUsable) >= lastUsable ($lastUsable)")
        }

        if (totalDiskSectors > 0 && lastUsable >= totalDiskSectors) {
            errors.add("Last usable LBA ($lastUsable) exceeds total disk sectors ($totalDiskSectors)")
        }

        // 5. Backup Header Check (if provided)
        if (backupHeader != null && backupHeader.size >= GPT_DEFAULT_HEADER_SIZE) {
            val bSig = String(backupHeader, 0, 8, Charsets.US_ASCII)
            if (bSig != GPT_SIGNATURE) {
                errors.add("Invalid Backup GPT Header signature: '$bSig'")
            }

            val bBuf = ByteBuffer.wrap(backupHeader).order(ByteOrder.LITTLE_ENDIAN)
            val bHeaderSize = bBuf.getInt(12)
            val recordedBackupHeaderCrc = bBuf.getInt(16).toLong() and 0xFFFFFFFFL
            val computedBackupHeaderCrc = computeHeaderCrc32(backupHeader, bHeaderSize)

            if (recordedBackupHeaderCrc != computedBackupHeaderCrc) {
                errors.add("Backup GPT Header CRC32 mismatch: recorded 0x${recordedBackupHeaderCrc.toString(16)}, computed 0x${computedBackupHeaderCrc.toString(16)}")
            }

            val bMyLba = bBuf.getLong(24)
            val bAlternateLba = bBuf.getLong(32)

            if (bAlternateLba != 1L) {
                errors.add("Backup GPT Header AlternateLBA must point to Primary (1), found $bAlternateLba")
            }

            if (totalDiskSectors > 0 && bMyLba != (totalDiskSectors - 1L)) {
                warnings.add("Backup GPT MyLBA ($bMyLba) does not match disk end (${totalDiskSectors - 1L})")
            }
        }

        return if (errors.isEmpty()) PartitionValidationResult.valid(warnings) else PartitionValidationResult.invalid(errors, warnings)
    }

    // --- BlockDevice I/O Engine ---

    /**
     * Reads and parses partition table from a [BlockDevice].
     */
    suspend fun readFromDevice(device: BlockDevice): PartitionTable? {
        val capacity = device.capacity()
        val totalSectors = capacity.totalSectors
        val sectorSize = capacity.sectorSizeBytes

        // Read Sector 0
        val sector0 = ByteArray(sectorSize)
        if (!device.read(0L, 1, sector0)) return null

        // Check MBR signature
        val buf = ByteBuffer.wrap(sector0).order(ByteOrder.LITTLE_ENDIAN)
        buf.position(510)
        if (buf.short != MBR_BOOT_SIGNATURE) {
            return null // Raw unpartitioned media
        }

        val mbr = parseMbr(sector0, totalSectors)
        if (!mbr.isProtective) {
            return mbr
        }

        // Protective MBR found -> Read GPT Primary Header (LBA 1)
        val primaryHeader = ByteArray(sectorSize)
        if (!device.read(1L, 1, primaryHeader)) return mbr

        val hBuf = ByteBuffer.wrap(primaryHeader).order(ByteOrder.LITTLE_ENDIAN)
        val sig = String(primaryHeader, 0, 8, Charsets.US_ASCII)
        if (sig != GPT_SIGNATURE) {
            return mbr
        }

        val entryLba = hBuf.getLong(72)
        val entryCount = hBuf.getInt(80)
        val entrySize = hBuf.getInt(84)
        val tableBytesTotal = entryCount * entrySize
        val tableSectors = (tableBytesTotal + sectorSize - 1) / sectorSize

        val tableBytes = ByteArray(tableSectors * sectorSize)
        if (!device.read(entryLba, tableSectors, tableBytes)) return mbr

        // Attempt reading backup header
        val backupHeader = ByteArray(sectorSize)
        val hasBackup = try {
            device.read(totalSectors - 1L, 1, backupHeader)
        } catch (_: Exception) {
            false
        }

        return try {
            parseGpt(primaryHeader, tableBytes, if (hasBackup) backupHeader else null, totalSectors)
        } catch (e: Exception) {
            mbr // Fallback to protective MBR representation if GPT parsing fails
        }
    }

    /**
     * Writes full GPT partition structures to the target [BlockDevice].
     */
    suspend fun writeGptToDevice(table: GptPartitionTable, device: BlockDevice): Boolean {
        val layout = buildGptLayout(table)
        val sectorSize = table.sectorSizeBytes
        val tableSectors = (table.partitionEntryCount * table.partitionEntrySizeBytes + sectorSize - 1) / sectorSize
        val totalSectors = table.totalDiskSectors

        // 1. Write Protective MBR at LBA 0
        if (!device.write(0L, 1, layout.protectiveMbr)) return false

        // 2. Write Primary Header at LBA 1
        if (!device.write(1L, 1, layout.primaryHeader)) return false

        // 3. Write Primary Partition Table at LBA 2..33
        if (!device.write(2L, tableSectors, layout.primaryPartitionTable)) return false

        // 4. Write Backup Partition Table at totalSectors - 1 - tableSectors
        val backupTableLba = totalSectors - 1L - tableSectors
        if (!device.write(backupTableLba, tableSectors, layout.backupPartitionTable)) return false

        // 5. Write Backup Header at totalSectors - 1
        val backupHeaderLba = totalSectors - 1L
        if (!device.write(backupHeaderLba, 1, layout.backupHeader)) return false

        // 6. Flush cache
        return device.flush()
    }

    // --- CRC32 Helpers ---

    fun computeCrc32(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size): Long {
        val crc = CRC32()
        crc.update(bytes, offset, length)
        return crc.value
    }

    fun computeHeaderCrc32(headerBytes: ByteArray, headerSize: Int = GPT_DEFAULT_HEADER_SIZE): Long {
        val copy = headerBytes.copyOf(headerSize)
        copy[16] = 0
        copy[17] = 0
        copy[18] = 0
        copy[19] = 0
        val crc = CRC32()
        crc.update(copy, 0, headerSize)
        return crc.value
    }
}
