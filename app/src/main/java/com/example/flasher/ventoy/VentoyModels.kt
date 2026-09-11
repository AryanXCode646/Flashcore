package com.example.flasher.ventoy

import java.util.UUID

/**
 * Supported partition table styles for Ventoy media.
 */
enum class VentoyPartitionStyle(val displayName: String) {
    MBR("Master Boot Record (BIOS/Legacy & UEFI)"),
    GPT("GUID Partition Table (Modern UEFI Native)")
}

/**
 * Ventoy installation operation mode.
 */
enum class VentoyInstallMode(val displayName: String) {
    FRESH_INSTALL("Fresh Installation (Destructive - Full Disk Format)"),
    NON_DESTRUCTIVE_UPDATE("Non-Destructive Update (Preserves Data Partition & Stored ISOs)")
}

/**
 * Calculated physical disk partition layout for Ventoy media.
 */
data class VentoyPartitionLayout(
    val diskTotalSectors: Long,
    val partitionStyle: VentoyPartitionStyle,
    val sectorSizeBytes: Int,
    val part1StartLba: Long,
    val part1SectorCount: Long,
    val part2StartLba: Long,
    val part2SectorCount: Long,
    val backupGptLba: Long? = null
) {
    val part1SizeBytes: Long get() = part1SectorCount * sectorSizeBytes
    val part2SizeBytes: Long get() = part2SectorCount * sectorSizeBytes
    val part1EndLba: Long get() = part1StartLba + part1SectorCount - 1L
    val part2EndLba: Long get() = part2StartLba + part2SectorCount - 1L
}

/**
 * Represents a bootable OS image file stored inside the Ventoy data partition filesystem.
 */
data class VentoyIsoFile(
    val fileName: String,
    val relativePath: String,
    val sizeBytes: Long
) {
    val formattedSize: String
        get() {
            val mb = sizeBytes.toDouble() / (1024.0 * 1024.0)
            return if (mb >= 1024.0) {
                String.format(java.util.Locale.US, "%.2f GB", mb / 1024.0)
            } else {
                String.format(java.util.Locale.US, "%.1f MB", mb)
            }
        }
}

/**
 * Hardware and inspection metadata for an existing or candidate Ventoy drive.
 */
data class VentoyDiskInfo(
    val isVentoyInstalled: Boolean,
    val installedVersion: String? = null,
    val partitionStyle: VentoyPartitionStyle? = null,
    val layout: VentoyPartitionLayout? = null,
    val dataPartitionSizeBytes: Long = 0L,
    val vtoyEfiSizeBytes: Long = 0L,
    val storedIsoFiles: List<VentoyIsoFile> = emptyList(),
    val canNonDestructiveUpdate: Boolean = false
) {
    companion object {
        fun notInstalled(): VentoyDiskInfo = VentoyDiskInfo(
            isVentoyInstalled = false,
            canNonDestructiveUpdate = false
        )
    }
}

/**
 * Execution result of a Ventoy installation or update operation.
 */
data class VentoyInstallResult(
    val success: Boolean,
    val mode: VentoyInstallMode,
    val layout: VentoyPartitionLayout,
    val ventoyVersion: String,
    val totalBytesWritten: Long,
    val durationMs: Long,
    val isoFilesStored: List<String> = emptyList(),
    val errorMessage: String? = null
)
