package com.example.flasher

import android.content.Context
import android.net.Uri
import com.example.dsa.IsoTrieParser
import com.example.usb.UsbDiskInfo
import com.example.usb.UsbMassStorageDriver

/**
 * Strategy Pattern Interface for Bootable USB Flashing Engines.
 */
interface FlashEngineStrategy {

    val id: String
    val displayName: String
    val description: String

    data class FlashConfig(
        val blockSizeBytes: Int = 1024 * 1024, // 1 MB default
        val verifyAfterWrite: Boolean = true,
        val autoSplitWim: Boolean = true,
        val targetFileSystem: TargetFs = TargetFs.FAT32_UEFI
    )

    enum class TargetFs(val label: String) {
        RAW_DIRECT("Raw DD (Direct Sector 0)"),
        FAT32_UEFI("FAT32 (UEFI Standard)"),
        EXFAT_VENTOY("exFAT Data + FAT16 VTOYEFI")
    }

    interface ProgressCallback {
        fun onPartitionProgress(stage: String, progress: Float)
        fun onStreamProgress(
            writtenBytes: Long,
            totalBytes: Long,
            speedMBps: Double,
            etaSeconds: Long,
            bufferSaturation: Float,
            currentLba: Long,
            chunkIndex: Int,
            totalChunks: Int
        )
        fun onVerificationProgress(verifiedBytes: Long, totalBytes: Long, isMatching: Boolean)
        fun onLogMessage(message: String)
    }

    suspend fun execute(
        context: Context,
        driver: UsbMassStorageDriver,
        targetDrive: UsbDiskInfo,
        sourceUri: Uri,
        isoAnalysis: IsoTrieParser.AnalysisResult,
        config: FlashConfig,
        callback: ProgressCallback,
        isCancelled: () -> Boolean
    ): StrategyResult

    data class StrategyResult(
        val success: Boolean,
        val totalBytesWritten: Long,
        val durationMs: Long,
        val averageSpeedMBps: Double,
        val sha256: String,
        val errorMessage: String? = null
    )
}
