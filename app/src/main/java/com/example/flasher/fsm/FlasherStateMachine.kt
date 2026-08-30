package com.example.flasher.fsm

import com.example.dsa.IsoTrieParser
import com.example.usb.UsbDiskInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Deterministic Events driving FlasherState transitions.
 */
sealed interface FlasherEvent {
    data class DevicesUpdated(val devices: List<UsbDiskInfo>, val selected: UsbDiskInfo?) : FlasherEvent
    data class DeviceSelected(val device: UsbDiskInfo) : FlasherEvent
    data class PermissionGranted(val device: UsbDiskInfo) : FlasherEvent
    data class DiskLockAcquired(val device: UsbDiskInfo) : FlasherEvent
    data class IsoAnalysisStarted(val device: UsbDiskInfo, val uri: String) : FlasherEvent
    data class IsoAnalysisFinished(val device: UsbDiskInfo, val analysis: IsoTrieParser.AnalysisResult, val uri: String) : FlasherEvent
    data class PartitionProgressUpdate(val device: UsbDiskInfo, val stage: String, val progress: Float) : FlasherEvent
    data class StreamProgressUpdate(
        val device: UsbDiskInfo,
        val writtenBytes: Long,
        val totalBytes: Long,
        val speedMBps: Double,
        val etaSeconds: Long,
        val bufferSaturation: Float,
        val currentLba: Long,
        val currentChunk: Int,
        val totalChunks: Int
    ) : FlasherEvent
    data class VerificationProgressUpdate(
        val device: UsbDiskInfo,
        val verifiedBytes: Long,
        val totalBytes: Long,
        val isMatching: Boolean
    ) : FlasherEvent
    data class FlashFinished(
        val device: UsbDiskInfo,
        val totalBytes: Long,
        val durationMs: Long,
        val avgSpeedMBps: Double,
        val checksum: String
    ) : FlasherEvent
    data class FlashErrorOccurred(
        val device: UsbDiskInfo?,
        val errorMessage: String,
        val canRetry: Boolean,
        val failedLba: Long
    ) : FlasherEvent
    object ResetToIdle : FlasherEvent
}

/**
 * Thread-safe Finite State Machine controller for flash execution lifecycle.
 */
class FlasherStateMachine {

    private val lock = ReentrantLock()
    private val _state = MutableStateFlow<FlasherState>(FlasherState.Idle)
    val state: StateFlow<FlasherState> = _state.asStateFlow()

    fun transition(event: FlasherEvent): FlasherState {
        return lock.withLock {
            val current = _state.value
            val next = when (event) {
                is FlasherEvent.DevicesUpdated -> {
                    if (event.devices.isEmpty()) {
                        FlasherState.Idle
                    } else {
                        FlasherState.DeviceEnumerated(event.devices, event.selected)
                    }
                }
                is FlasherEvent.DeviceSelected -> {
                    if (event.device.hasPermission) {
                        FlasherState.PermissionGranted(event.device)
                    } else {
                        FlasherState.DeviceEnumerated(listOf(event.device), event.device)
                    }
                }
                is FlasherEvent.PermissionGranted -> {
                    FlasherState.PermissionGranted(event.device)
                }
                is FlasherEvent.DiskLockAcquired -> {
                    FlasherState.DiskLocked(event.device)
                }
                is FlasherEvent.IsoAnalysisStarted -> {
                    FlasherState.AnalyzingIso(event.device, event.uri, 0.1f)
                }
                is FlasherEvent.IsoAnalysisFinished -> {
                    FlasherState.ReadyToFlash(event.device, event.analysis, event.uri)
                }
                is FlasherEvent.PartitionProgressUpdate -> {
                    FlasherState.Partitioning(event.device, event.stage, event.progress)
                }
                is FlasherEvent.StreamProgressUpdate -> {
                    val progress = if (event.totalBytes > 0) (event.writtenBytes.toFloat() / event.totalBytes.toFloat()).coerceIn(0f, 1f) else 0f
                    FlasherState.Streaming(
                        device = event.device,
                        writtenBytes = event.writtenBytes,
                        totalBytes = event.totalBytes,
                        progress = progress,
                        speedMBps = event.speedMBps,
                        etaSeconds = event.etaSeconds,
                        bufferSaturation = event.bufferSaturation,
                        currentLba = event.currentLba,
                        currentChunk = event.currentChunk,
                        totalChunks = event.totalChunks
                    )
                }
                is FlasherEvent.VerificationProgressUpdate -> {
                    val progress = if (event.totalBytes > 0) (event.verifiedBytes.toFloat() / event.totalBytes.toFloat()).coerceIn(0f, 1f) else 0f
                    FlasherState.Verifying(
                        device = event.device,
                        verifiedBytes = event.verifiedBytes,
                        totalBytes = event.totalBytes,
                        progress = progress,
                        isMatching = event.isMatching
                    )
                }
                is FlasherEvent.FlashFinished -> {
                    FlasherState.Completed(
                        device = event.device,
                        totalBytesWritten = event.totalBytes,
                        durationMs = event.durationMs,
                        averageSpeedMBps = event.avgSpeedMBps,
                        sha256Checksum = event.checksum
                    )
                }
                is FlasherEvent.FlashErrorOccurred -> {
                    FlasherState.ErrorRecovery(
                        device = event.device,
                        errorMessage = event.errorMessage,
                        canRetry = event.canRetry,
                        lastFailedLba = event.failedLba,
                        previousState = current.javaClass.simpleName
                    )
                }
                is FlasherEvent.ResetToIdle -> {
                    FlasherState.Idle
                }
            }
            _state.value = next
            next
        }
    }
}
