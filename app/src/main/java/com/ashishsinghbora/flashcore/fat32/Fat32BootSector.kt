package com.ashishsinghbora.flashcore.fat32

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * FAT32 Volume Boot Record (VBR) and BIOS Parameter Block (BPB) Manager.
 *
 * Handles standard FAT32 geometry calculation, BPB serialization, and cluster-to-LBA mapping
 * compliant with Microsoft FAT32 and UEFI Specifications.
 */
data class Fat32BootSector(
    val sectorSizeBytes: Int = 512,
    val sectorsPerCluster: Int = 8,
    val reservedSectors: Int = 32,
    val numFats: Int = 2,
    val totalSectors: Long,
    val sectorsPerFat: Long,
    val rootCluster: Long = 2L,
    val fsInfoSector: Int = 1,
    val backupBootSector: Int = 6,
    val volumeLabel: String = "BOOT_MEDIA",
    val volumeSerial: Int = 0x4C5A8821
) {
    val fat1StartLba: Long get() = reservedSectors.toLong()
    val fat2StartLba: Long get() = reservedSectors.toLong() + sectorsPerFat
    val dataStartLba: Long get() = reservedSectors.toLong() + (numFats * sectorsPerFat)
    val totalDataSectors: Long get() = totalSectors - dataStartLba
    val totalDataClusters: Long get() = (totalDataSectors / sectorsPerCluster).coerceAtLeast(0L)
    val clusterSizeBytes: Long get() = sectorsPerCluster.toLong() * sectorSizeBytes

    /**
     * Translates a FAT32 cluster index (starting at 2) to its physical partition LBA.
     */
    fun clusterToLba(cluster: Long): Long {
        require(cluster >= 2L) { "Cluster index must be >= 2, got $cluster" }
        return dataStartLba + ((cluster - 2L) * sectorsPerCluster)
    }

    /**
     * Translates a physical partition LBA to its containing cluster index.
     */
    fun lbaToCluster(lba: Long): Long {
        require(lba >= dataStartLba) { "LBA $lba is before data region (starts at $dataStartLba)" }
        return 2L + ((lba - dataStartLba) / sectorsPerCluster)
    }

    /**
     * Serializes this boot sector into a standard 512-byte FAT32 VBR sector.
     */
    fun serialize(): ByteArray {
        val bytes = ByteArray(sectorSizeBytes)
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        // Jump instruction & OEM ID (MSWIN4.1)
        buf.put(0xEB.toByte())
        buf.put(0x58.toByte())
        buf.put(0x90.toByte())
        val oem = "MSWIN4.1".toByteArray(Charsets.US_ASCII)
        buf.put(oem, 0, minOf(8, oem.size))

        // Standard BIOS Parameter Block (BPB)
        buf.putShort(sectorSizeBytes.toShort()) // Bytes per Sector
        buf.put(sectorsPerCluster.toByte()) // Sectors per Cluster
        buf.putShort(reservedSectors.toShort()) // Reserved Sectors
        buf.put(numFats.toByte()) // Number of FATs
        buf.putShort(0.toShort()) // Root entries (0 for FAT32)
        buf.putShort(0.toShort()) // Total sectors 16 (0 for FAT32)
        buf.put(0xF8.toByte()) // Media descriptor (Fixed disk)
        buf.putShort(0.toShort()) // Sectors per FAT 16 (0 for FAT32)
        buf.putShort(63.toShort()) // Sectors per track
        buf.putShort(255.toShort()) // Number of heads
        buf.putInt(0) // Hidden sectors
        buf.putInt((totalSectors and 0xFFFFFFFFL).toInt()) // Total Sectors 32

        // Extended FAT32 BPB
        buf.putInt((sectorsPerFat and 0xFFFFFFFFL).toInt()) // Sectors per FAT 32
        buf.putShort(0.toShort()) // Extended Flags (FAT mirroring active)
        buf.putShort(0.toShort()) // FS Version (0:0)
        buf.putInt((rootCluster and 0xFFFFFFFFL).toInt()) // Root Directory Cluster (2)
        buf.putShort(fsInfoSector.toShort()) // FSInfo Sector (1)
        buf.putShort(backupBootSector.toShort()) // Backup Boot Sector (6)

        // 12 bytes Reserved
        for (i in 0 until 12) buf.put(0.toByte())

        buf.put(0x80.toByte()) // Drive number
        buf.put(0.toByte()) // Reserved
        buf.put(0x29.toByte()) // Extended Boot Signature
        buf.putInt(volumeSerial) // Volume Serial Number

        // Volume Label (11 characters space-padded uppercase ASCII)
        val cleanLabel = volumeLabel.padEnd(11, ' ').take(11).uppercase()
        buf.put(cleanLabel.toByteArray(Charsets.US_ASCII))
        buf.put("FAT32   ".toByteArray(Charsets.US_ASCII))

        // Boot signature at offset 510
        buf.position(510)
        buf.put(0x55.toByte())
        buf.put(0xAA.toByte())

        return bytes
    }

    companion object {
        const val SECTOR_SIZE = 512
        const val DEFAULT_RESERVED_SECTORS = 32
        const val DEFAULT_NUM_FATS = 2
        const val DEFAULT_ROOT_CLUSTER = 2L
        const val DEFAULT_FSINFO_SECTOR = 1
        const val DEFAULT_BACKUP_BOOT_SECTOR = 6

        /**
         * Computes optimal sectors per cluster and calculates FAT size for a partition.
         */
        fun create(
            totalSectors: Long,
            volumeLabel: String = "BOOT_MEDIA",
            sectorSize: Int = SECTOR_SIZE
        ): Fat32BootSector {
            require(totalSectors > DEFAULT_RESERVED_SECTORS + 100) {
                "Partition size too small for FAT32: $totalSectors sectors"
            }

            // Power of 2 sectors per cluster: standard FAT32 sizes
            val sectorsPerCluster = when {
                totalSectors < 16777216L -> 8  // 4 KB cluster (< 8 GB, standard for UEFI)
                totalSectors < 33554432L -> 16 // 8 KB cluster (< 16 GB)
                totalSectors < 67108864L -> 32 // 16 KB cluster (< 32 GB)
                else -> 64 // 32 KB cluster (>= 32 GB)
            }

            // Iterative calculation for FAT size:
            // totalClusters = (totalSectors - reserved - 2*sectorsPerFat) / sectorsPerCluster
            // sectorsPerFat = (totalClusters * 4 + sectorSize - 1) / sectorSize
            val availableSectors = totalSectors - DEFAULT_RESERVED_SECTORS
            val clustersGuess = availableSectors / sectorsPerCluster
            val fatBytesGuess = (clustersGuess + 2) * 4
            val sectorsPerFat = (fatBytesGuess + sectorSize - 1) / sectorSize

            return Fat32BootSector(
                sectorSizeBytes = sectorSize,
                sectorsPerCluster = sectorsPerCluster,
                reservedSectors = DEFAULT_RESERVED_SECTORS,
                numFats = DEFAULT_NUM_FATS,
                totalSectors = totalSectors,
                sectorsPerFat = sectorsPerFat,
                rootCluster = DEFAULT_ROOT_CLUSTER,
                fsInfoSector = DEFAULT_FSINFO_SECTOR,
                backupBootSector = DEFAULT_BACKUP_BOOT_SECTOR,
                volumeLabel = volumeLabel
            )
        }

        /**
         * Parses a 512-byte sector as a FAT32 VBR.
         */
        fun parse(sectorBytes: ByteArray): Fat32BootSector {
            require(sectorBytes.size >= 512) { "Sector bytes too short: ${sectorBytes.size}" }
            val buf = ByteBuffer.wrap(sectorBytes).order(ByteOrder.LITTLE_ENDIAN)

            val sig = buf.getShort(510).toInt() and 0xFFFF
            require(sig == 0xAA55) { "Invalid boot signature: 0x${"%04X".format(sig)}" }

            val sectorSize = buf.getShort(11).toInt() and 0xFFFF
            val sectorsPerCluster = buf.get(13).toInt() and 0xFF
            val reservedSectors = buf.getShort(14).toInt() and 0xFFFF
            val numFats = buf.get(16).toInt() and 0xFF
            val totalSectors16 = buf.getShort(19).toInt() and 0xFFFF
            val totalSectors32 = buf.getInt(32).toLong() and 0xFFFFFFFFL
            val totalSectors = if (totalSectors32 != 0L) totalSectors32 else totalSectors16.toLong()

            val sectorsPerFat = buf.getInt(36).toLong() and 0xFFFFFFFFL
            val rootCluster = buf.getInt(44).toLong() and 0xFFFFFFFFL
            val fsInfoSector = buf.getShort(48).toInt() and 0xFFFF
            val backupBoot = buf.getShort(50).toInt() and 0xFFFF

            val serial = buf.getInt(67)
            val labelBytes = ByteArray(11)
            buf.position(71)
            buf.get(labelBytes)
            val label = String(labelBytes, Charsets.US_ASCII).trim()

            return Fat32BootSector(
                sectorSizeBytes = sectorSize,
                sectorsPerCluster = sectorsPerCluster,
                reservedSectors = reservedSectors,
                numFats = numFats,
                totalSectors = totalSectors,
                sectorsPerFat = sectorsPerFat,
                rootCluster = rootCluster,
                fsInfoSector = fsInfoSector,
                backupBootSector = backupBoot,
                volumeLabel = label,
                volumeSerial = serial
            )
        }
    }
}
