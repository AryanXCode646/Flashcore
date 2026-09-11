package com.ashishsinghbora.flashcore.partition

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.zip.CRC32

/**
 * GUID Partition Table (GPT) Builder conforming to UEFI Specification v2.10.
 *
 * Generates Primary GPT Header (LBA 1), Partition Table Entries (LBA 2..33),
 * and Backup GPT at the end of the physical disk.
 */
object GptBuilder {

    const val GPT_HEADER_SIGNATURE = "EFI PART"
    const val GPT_HEADER_REVISION = 0x00010000 // 1.0
    const val GPT_HEADER_SIZE = 92
    const val PARTITION_ENTRY_SIZE = 128
    const val PARTITION_ENTRY_COUNT = 128
    const val PARTITION_TABLE_SECTOR_COUNT = 32 // (128 * 128) / 512 = 32 sectors

    // Well-known GUIDs (RFC 4122)
    val GUID_EFI_SYSTEM = UUID.fromString("C12A7328-F81F-11D2-BA4B-00A0C93EC93B")
    val GUID_MICROSOFT_BASIC_DATA = UUID.fromString("EBD0A0A2-B9E5-4433-87C0-68B6B72699C7")
    val GUID_LINUX_FILESYSTEM = UUID.fromString("0FC63DAF-8483-4772-8E79-3D69D8477DE4")
    val GUID_MICROSOFT_RESERVED = UUID.fromString("E3C9E316-0B5C-4DB8-817D-F92DF00215AE")

    data class GptPartition(
        val typeGuid: UUID,
        val uniqueGuid: UUID = UUID.randomUUID(),
        val firstLba: Long,
        val lastLba: Long,
        val attributes: Long = 0L,
        val partitionName: String
    )

    data class GptLayout(
        val primaryHeaderSector: ByteArray, // LBA 1
        val primaryPartitionTableBytes: ByteArray, // LBA 2..33 (16,384 bytes)
        val backupHeaderSector: ByteArray, // LBA Last
        val backupPartitionTableBytes: ByteArray // LBA Last - 32
    )

    /**
     * Builds complete GPT layout including primary & backup structures.
     */
    fun build(
        totalDiskSectors: Long,
        partitions: List<GptPartition>,
        diskGuid: UUID = UUID.randomUUID(),
        sectorSizeBytes: Int = 512
    ): GptLayout {
        val firstUsableLba = 2L + PARTITION_TABLE_SECTOR_COUNT
        val lastUsableLba = totalDiskSectors - 2L - PARTITION_TABLE_SECTOR_COUNT

        // 1. Build Partition Table Entries (16 KB)
        val partitionTableBytes = ByteArray(PARTITION_ENTRY_COUNT * PARTITION_ENTRY_SIZE)
        val pBuf = ByteBuffer.wrap(partitionTableBytes).order(ByteOrder.LITTLE_ENDIAN)

        for (i in 0 until PARTITION_ENTRY_COUNT) {
            if (i < partitions.size) {
                val p = partitions[i]
                pBuf.position(i * PARTITION_ENTRY_SIZE)
                putUuidMixedEndian(pBuf, p.typeGuid)
                putUuidMixedEndian(pBuf, p.uniqueGuid)
                pBuf.putLong(p.firstLba)
                pBuf.putLong(p.lastLba)
                pBuf.putLong(p.attributes)

                // Partition Name (36 UTF-16LE characters = 72 bytes)
                val nameChars = p.partitionName.toCharArray()
                for (c in 0 until 36) {
                    if (c < nameChars.size) {
                        pBuf.putChar(nameChars[c])
                    } else {
                        pBuf.putChar('\u0000')
                    }
                }
            }
        }

        // Calculate CRC32 of Partition Table
        val crc32 = CRC32()
        crc32.update(partitionTableBytes)
        val partitionTableCrc = crc32.value

        // 2. Build Primary GPT Header (LBA 1)
        val primaryHeader = buildHeader(
            myLba = 1L,
            alternateLba = totalDiskSectors - 1L,
            firstUsableLba = firstUsableLba,
            lastUsableLba = lastUsableLba,
            diskGuid = diskGuid,
            partitionEntriesLba = 2L,
            partitionTableCrc = partitionTableCrc,
            sectorSizeBytes = sectorSizeBytes
        )

        // 3. Build Backup GPT Header (LBA Last)
        val backupHeader = buildHeader(
            myLba = totalDiskSectors - 1L,
            alternateLba = 1L,
            firstUsableLba = firstUsableLba,
            lastUsableLba = lastUsableLba,
            diskGuid = diskGuid,
            partitionEntriesLba = totalDiskSectors - 1L - PARTITION_TABLE_SECTOR_COUNT,
            partitionTableCrc = partitionTableCrc,
            sectorSizeBytes = sectorSizeBytes
        )

        return GptLayout(
            primaryHeaderSector = primaryHeader,
            primaryPartitionTableBytes = partitionTableBytes,
            backupHeaderSector = backupHeader,
            backupPartitionTableBytes = partitionTableBytes.clone()
        )
    }

    private fun buildHeader(
        myLba: Long,
        alternateLba: Long,
        firstUsableLba: Long,
        lastUsableLba: Long,
        diskGuid: UUID,
        partitionEntriesLba: Long,
        partitionTableCrc: Long,
        sectorSizeBytes: Int
    ): ByteArray {
        val sector = ByteArray(sectorSizeBytes)
        val buf = ByteBuffer.wrap(sector).order(ByteOrder.LITTLE_ENDIAN)

        buf.put(GPT_HEADER_SIGNATURE.toByteArray(Charsets.US_ASCII))
        buf.putInt(GPT_HEADER_REVISION)
        buf.putInt(GPT_HEADER_SIZE)
        buf.putInt(0) // CRC32 placeholder (calculated below)
        buf.putInt(0) // Reserved

        buf.putLong(myLba)
        buf.putLong(alternateLba)
        buf.putLong(firstUsableLba)
        buf.putLong(lastUsableLba)

        putUuidMixedEndian(buf, diskGuid)

        buf.putLong(partitionEntriesLba)
        buf.putInt(PARTITION_ENTRY_COUNT)
        buf.putInt(PARTITION_ENTRY_SIZE)
        buf.putInt(partitionTableCrc.toInt())

        // Calculate CRC32 of the 92-byte Header with CRC field zeroed
        val crc = CRC32()
        crc.update(sector, 0, GPT_HEADER_SIZE)
        val headerCrc = crc.value.toInt()

        // Insert computed CRC32 back at offset 16
        buf.position(16)
        buf.putInt(headerCrc)

        return sector
    }

    /**
     * Converts standard UUID into UEFI Mixed-Endian binary format.
     */
    private fun putUuidMixedEndian(buf: ByteBuffer, uuid: UUID) {
        val msb = uuid.mostSignificantBits
        val lsb = uuid.leastSignificantBits

        val timeLow = ((msb ushr 32) and 0xFFFFFFFFL).toInt()
        val timeMid = ((msb ushr 16) and 0xFFFFL).toShort()
        val timeHiAndVersion = (msb and 0xFFFFL).toShort()

        buf.putInt(timeLow)
        buf.putShort(timeMid)
        buf.putShort(timeHiAndVersion)

        // Clock seq and Node are written big-endian (network order)
        for (i in 7 downTo 0) {
            buf.put(((lsb ushr (i * 8)) and 0xFF).toByte())
        }
    }
}
