package com.ashishsinghbora.flashcore.flasher.fsm

import com.ashishsinghbora.flashcore.dsa.IsoTrieParser
import com.ashishsinghbora.flashcore.usb.UsbDiskInfo

/**
 * Deterministic Finite State Machine (FSM) States for FlashCore sessions.
 * Strictly non-skippable transition pipeline.
 */
sealed interface FlasherState {
    object Idle : FlasherState

    data class DeviceEnumerated(
        val devices: List<UsbDiskInfo>,
        val selectedDevice: UsbDiskInfo? = null
    ) : FlasherState

    data class PermissionGranted(
        val device: UsbDiskInfo
    ) : FlasherState

    data class DiskLocked(
        val device: UsbDiskInfo
    ) : FlasherState

    data class AnalyzingIso(
        val device: UsbDiskInfo,
        val isoUri: String,
        val progress: Float
    ) : FlasherState

    data class ReadyToFlash(
        val device: UsbDiskInfo,
        val isoAnalysis: IsoTrieParser.AnalysisResult,
        val isoUri: String
    ) : FlasherState

    data class Partitioning(
        val device: UsbDiskInfo,
        val stageName: String,
        val progress: Float
    ) : FlasherState

    data class Streaming(
        val device: UsbDiskInfo,
        val writtenBytes: Long,
        val totalBytes: Long,
        val progress: Float,
        val speedMBps: Double,
        val etaSeconds: Long,
        val bufferSaturation: Float,
        val currentLba: Long,
        val currentChunk: Int,
        val totalChunks: Int
    ) : FlasherState

    data class Verifying(
        val device: UsbDiskInfo,
        val verifiedBytes: Long,
        val totalBytes: Long,
        val progress: Float,
        val isMatching: Boolean
    ) : FlasherState

    data class Completed(
        val device: UsbDiskInfo,
        val totalBytesWritten: Long,
        val durationMs: Long,
        val averageSpeedMBps: Double,
        val sha256Checksum: String
    ) : FlasherState

    data class ErrorRecovery(
        val device: UsbDiskInfo?,
        val errorMessage: String,
        val canRetry: Boolean,
        val lastFailedLba: Long,
        val previousState: String
    ) : FlasherState
}
