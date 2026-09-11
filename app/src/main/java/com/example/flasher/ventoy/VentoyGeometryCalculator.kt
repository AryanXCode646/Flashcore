package com.example.flasher.ventoy

/**
 * Calculates physical disk geometry and partition boundaries for Ventoy multi-boot media.
 *
 * Implements strict compliance with the Ventoy disk specification:
 * - Partition 1 (Data): 1 MiB alignment (LBA 2048), formatted as exFAT/FAT32 with label "Ventoy".
 * - Partition 2 (VTOYEFI): Exactly 32 MiB (65,536 sectors @ 512 bytes), formatted as FAT16/FAT32 with label "VTOYEFI".
 * - GPT mode reserves 34 sectors at the end of the disk for the secondary GPT header and table.
 */
object VentoyGeometryCalculator {

    /** 32 MiB partition size in standard 512-byte sectors (32 * 1024 * 1024 / 512) */
    const val VTOYEFI_SECTOR_COUNT = 65536L

    /** 1 MiB partition alignment offset in standard 512-byte sectors */
    const val PART1_START_LBA = 2048L

    /** Number of sectors reserved at the physical end of disk for GPT backup table and header */
    const val GPT_BACKUP_SECTOR_COUNT = 34L

    /** Minimum supported disk capacity for Ventoy installation (~100 MiB) */
    const val MINIMUM_DISK_SECTORS = PART1_START_LBA + VTOYEFI_SECTOR_COUNT + (64L * 1024L * 1024L / 512L)

    /**
     * Calculates the dual-partition layout for [totalDiskSectors] using [style].
     *
     * @throws IllegalArgumentException if the target drive capacity is below the minimum required.
     */
    fun computeLayout(
        totalDiskSectors: Long,
        style: VentoyPartitionStyle = VentoyPartitionStyle.MBR,
        sectorSizeBytes: Int = 512
    ): VentoyPartitionLayout {
        val safeSectorSize = sectorSizeBytes.coerceAtLeast(512)
        val minSectors = (MINIMUM_DISK_SECTORS * 512) / safeSectorSize

        if (totalDiskSectors < minSectors) {
            val diskMb = (totalDiskSectors * safeSectorSize) / (1024 * 1024)
            val minMb = (minSectors * safeSectorSize) / (1024 * 1024)
            throw IllegalArgumentException(
                "Disk capacity too small for Ventoy multi-boot layout: $diskMb MB ($totalDiskSectors sectors). " +
                "Minimum required is $minMb MB."
            )
        }

        // Adjust sector counts if device uses 4096-byte native sectors (4Kn)
        val vtoySectors = (32L * 1024L * 1024L) / safeSectorSize
        val part1Start = (1024L * 1024L) / safeSectorSize // 1 MiB alignment

        val (part2StartLba, backupGptLba) = when (style) {
            VentoyPartitionStyle.MBR -> {
                val p2Start = totalDiskSectors - vtoySectors
                Pair(p2Start, null)
            }
            VentoyPartitionStyle.GPT -> {
                val gptBackupSectors = (GPT_BACKUP_SECTOR_COUNT * 512 + safeSectorSize - 1) / safeSectorSize
                val p2Start = totalDiskSectors - vtoySectors - gptBackupSectors
                Pair(p2Start, totalDiskSectors - 1L)
            }
        }

        val part1Sectors = part2StartLba - part1Start

        return VentoyPartitionLayout(
            diskTotalSectors = totalDiskSectors,
            partitionStyle = style,
            sectorSizeBytes = safeSectorSize,
            part1StartLba = part1Start,
            part1SectorCount = part1Sectors,
            part2StartLba = part2StartLba,
            part2SectorCount = vtoySectors,
            backupGptLba = backupGptLba
        )
    }

    /**
     * Checks whether a drive has sufficient capacity to host Ventoy.
     */
    fun isCapacitySufficient(totalDiskSectors: Long, sectorSizeBytes: Int = 512): Boolean {
        val safeSectorSize = sectorSizeBytes.coerceAtLeast(512)
        val minSectors = (MINIMUM_DISK_SECTORS * 512) / safeSectorSize
        return totalDiskSectors >= minSectors
    }
}
