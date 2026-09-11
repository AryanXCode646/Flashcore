package com.ashishsinghbora.flashcore.partition

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Master Boot Record (MBR) Builder.
 *
 * Constructs valid standard MBRs, Hybrid MBRs, and Protective MBRs for UEFI/BIOS booting.
 */
object MbrBuilder {

    const val MBR_SIZE = 512
    const val BOOT_SIGNATURE = 0xAA55 // Little-Endian for 0x55, 0xAA

    // Partition Type Bytes
    const val TYPE_EMPTY: Byte = 0x00
    const val TYPE_FAT12: Byte = 0x01
    const val TYPE_FAT16: Byte = 0x06
    const val TYPE_NTFS_EXFAT: Byte = 0x07
    const val TYPE_FAT32_LBA: Byte = 0x0C
    const val TYPE_LINUX_NATIVE: Byte = 0x83.toByte()
    const val TYPE_EFI_SYSTEM_PARTITION: Byte = 0xEF.toByte()
    const val TYPE_GPT_PROTECTIVE: Byte = 0xEE.toByte()

    data class PartitionEntry(
        val bootable: Boolean,
        val type: Byte,
        val startLba: Long,
        val sectorCount: Long
    )

    /**
     * Builds a standard MBR sector (512 bytes) with up to 4 primary partitions.
     */
    fun buildMbr(
        partitions: List<PartitionEntry>,
        diskSignature: Int = 0x5A4C3E21,
        bootstrapCode: ByteArray? = null
    ): ByteArray {
        val mbr = ByteArray(MBR_SIZE)
        val buf = ByteBuffer.wrap(mbr).order(ByteOrder.LITTLE_ENDIAN)

        // Write Bootstrap code (up to 440 bytes)
        if (bootstrapCode != null) {
            val len = minOf(bootstrapCode.size, 440)
            System.arraycopy(bootstrapCode, 0, mbr, 0, len)
        }

        // Write 32-bit Disk Signature at offset 440
        buf.position(440)
        buf.putInt(diskSignature)
        buf.putShort(0.toShort()) // Reserved 2 bytes

        // Write Partition Table (4 entries x 16 bytes = 64 bytes at offset 446)
        for (i in 0 until 4) {
            val offset = 446 + (i * 16)
            buf.position(offset)
            if (i < partitions.size) {
                val p = partitions[i]
                buf.put(if (p.bootable) 0x80.toByte() else 0x00.toByte()) // Status (0x80 = Active/Bootable)

                // Starting CHS (Dummy 0x00 0x02 0x00 for LBA)
                buf.put(0x00.toByte())
                buf.put(0x02.toByte())
                buf.put(0x00.toByte())

                buf.put(p.type) // Partition Type

                // Ending CHS (Dummy 0xFF 0xFF 0xFF for LBA)
                buf.put(0xFF.toByte())
                buf.put(0xFF.toByte())
                buf.put(0xFF.toByte())

                // Starting LBA (32-bit)
                buf.putInt((p.startLba and 0xFFFFFFFFL).toInt())

                // Sector Count (32-bit)
                buf.putInt((p.sectorCount and 0xFFFFFFFFL).toInt())
            } else {
                // Empty 16-byte partition entry
                for (j in 0 until 16) buf.put(0.toByte())
            }
        }

        // Write MBR Boot Signature (0x55, 0xAA) at offset 510
        buf.position(510)
        buf.put(0x55.toByte())
        buf.put(0xAA.toByte())

        return mbr
    }

    /**
     * Builds a Protective MBR for GPT partitioned drives.
     * Contains single partition of type 0xEE spanning the whole disk.
     */
    fun buildProtectiveMbr(totalDiskSectors: Long): ByteArray {
        val count = minOf(totalDiskSectors - 1L, 0xFFFFFFFFL)
        val protectiveEntry = PartitionEntry(
            bootable = false,
            type = TYPE_GPT_PROTECTIVE,
            startLba = 1L,
            sectorCount = count
        )
        return buildMbr(listOf(protectiveEntry))
    }
}
