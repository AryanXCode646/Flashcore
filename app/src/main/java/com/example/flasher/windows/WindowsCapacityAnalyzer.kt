package com.example.flasher.windows

import com.example.block.BlockDevice
import com.example.dsa.WimChunker
import com.example.iso.IsoFilesystemReader

/**
 * Result of FAT32 storage capacity and geometry analysis for Windows installation media.
 */
data class WindowsCapacityAnalysis(
    val isSufficient: Boolean,
    val totalRequiredBytes: Long,
    val availablePartitionBytes: Long,
    val deficitBytes: Long,
    val recommendedClusterSizeBytes: Int,
    val partitionFirstLba: Long,
    val partitionLastLba: Long,
    val partitionSectorCount: Long,
    val nonWimBytes: Long,
    val wimBytes: Long,
    val filesystemOverheadBytes: Long,
    val summary: String
)

/**
 * Precise capacity and geometry analyzer for FAT32 Windows installation volumes.
 *
 * Accounts for:
 * - GPT primary and backup header reservations (LBA 0..2047 and LBA (End-2047)..End)
 * - 1 MiB partition alignment
 * - FAT32 reserved sectors (VBR Sector 0, FSInfo Sector 1, backup sectors)
 * - FAT1 and FAT2 dual table mirroring overhead
 * - Cluster slack (average cluster slack across thousands of small files)
 * - SWM part splitting overhead
 */
object WindowsCapacityAnalyzer {

    const val GPT_RESERVED_START_SECTORS = 2048L // 1 MiB alignment for LBA 0..2047
    const val GPT_RESERVED_END_SECTORS = 2048L   // End-of-disk buffer for Backup GPT
    const val FAT32_DEFAULT_CLUSTER_SIZE = 4096  // Standard 4 KB clusters for UEFI FAT32

    fun analyze(
        device: BlockDevice,
        capabilities: WindowsCapabilities,
        reader: IsoFilesystemReader,
        sectorSizeBytes: Int = device.sectorSizeBytes.coerceAtLeast(512),
        targetTotalSectors: Long
    ): WindowsCapacityAnalysis {
        // 1. Calculate partition boundaries with 1 MiB alignment
        val startLba = GPT_RESERVED_START_SECTORS
        val endLba = (targetTotalSectors - GPT_RESERVED_END_SECTORS).coerceAtLeast(startLba)
        val partitionSectors = if (endLba >= startLba) endLba - startLba + 1L else 0L
        val availablePartitionBytes = partitionSectors * sectorSizeBytes

        // 2. Calculate raw file payloads
        val installPath = capabilities.installImagePath?.lowercase()
        val nonWimFiles = reader.entries.filter { !it.isDirectory && it.path.lowercase() != installPath }
        val nonWimBytes = nonWimFiles.sumOf { it.sizeBytes }

        val wimBytes = if (capabilities.requiresWimSplit) {
            val parts = WimChunker.planSwmSplit(capabilities.installImageSizeBytes)
            // Each SWM part adds a WIM header of 208 bytes plus sector alignment padding
            parts.sumOf { it.lengthBytes + 208L }
        } else {
            capabilities.installImageSizeBytes
        }

        val rawPayloadBytes = nonWimBytes + wimBytes

        // 3. Compute FAT32 filesystem structures overhead
        val clusterSize = FAT32_DEFAULT_CLUSTER_SIZE
        val estimatedClusters = (rawPayloadBytes + clusterSize - 1) / clusterSize

        // Dual FAT table overhead (4 bytes per cluster, 2 FAT tables)
        val fatTableSizeBytes = (estimatedClusters * 4L * 2L)
        val fatSectors = (fatTableSizeBytes + sectorSizeBytes - 1) / sectorSizeBytes

        // Cluster slack (average half a cluster per file)
        val totalFileCount = nonWimFiles.size + if (capabilities.requiresWimSplit) capabilities.splitPartCount else 1
        val estimatedClusterSlack = totalFileCount.toLong() * (clusterSize / 2L)

        // Reserved sectors (32 sectors) + Directory records overhead
        val reservedSectorsBytes = 32L * sectorSizeBytes
        val directoryOverheadBytes = (capabilities.dirCount.toLong() + totalFileCount.toLong()) * 32L

        val filesystemOverheadBytes = (fatSectors * sectorSizeBytes) + estimatedClusterSlack + reservedSectorsBytes + directoryOverheadBytes
        val totalRequiredBytes = rawPayloadBytes + filesystemOverheadBytes

        val isSufficient = availablePartitionBytes >= totalRequiredBytes
        val deficit = if (isSufficient) 0L else totalRequiredBytes - availablePartitionBytes

        val summary = if (isSufficient) {
            "Storage capacity verified: Required ${formatBytes(totalRequiredBytes)} vs Available ${formatBytes(availablePartitionBytes)} (${formatBytes(availablePartitionBytes - totalRequiredBytes)} free headroom)."
        } else {
            "Insufficient storage capacity: Required ${formatBytes(totalRequiredBytes)} exceeds partition capacity ${formatBytes(availablePartitionBytes)}. Deficit: ${formatBytes(deficit)}."
        }

        return WindowsCapacityAnalysis(
            isSufficient = isSufficient,
            totalRequiredBytes = totalRequiredBytes,
            availablePartitionBytes = availablePartitionBytes,
            deficitBytes = deficit,
            recommendedClusterSizeBytes = clusterSize,
            partitionFirstLba = startLba,
            partitionLastLba = endLba,
            partitionSectorCount = partitionSectors,
            nonWimBytes = nonWimBytes,
            wimBytes = wimBytes,
            filesystemOverheadBytes = filesystemOverheadBytes,
            summary = summary
        )
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return "0 B"
        val gb = bytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
        if (gb >= 1.0) return "%.2f GB".format(gb)
        val mb = bytes.toDouble() / (1024.0 * 1024.0)
        if (mb >= 1.0) return "%.1f MB".format(mb)
        val kb = bytes.toDouble() / 1024.0
        return "%.1f KB".format(kb)
    }
}
