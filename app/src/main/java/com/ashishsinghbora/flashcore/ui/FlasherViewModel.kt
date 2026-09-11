package com.ashishsinghbora.flashcore.ui

import android.app.Application
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.ashishsinghbora.flashcore.block.DeviceDisconnectedException
import com.ashishsinghbora.flashcore.dsa.IsoTrieParser
import com.ashishsinghbora.flashcore.flasher.FlashEngineStrategy
import com.ashishsinghbora.flashcore.flasher.fsm.FlasherEvent
import com.ashishsinghbora.flashcore.flasher.fsm.FlasherState
import com.ashishsinghbora.flashcore.flasher.fsm.FlasherStateMachine
import com.ashishsinghbora.flashcore.flasher.strategies.LinuxRawDdStrategy
import com.ashishsinghbora.flashcore.flasher.strategies.VentoyStrategy
import com.ashishsinghbora.flashcore.flasher.strategies.WindowsUefiStrategy
import com.ashishsinghbora.flashcore.service.FlashForegroundService
import com.ashishsinghbora.flashcore.usb.UsbDiskInfo
import com.ashishsinghbora.flashcore.usb.UsbMassStorageDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class FlasherUiState(
    val fsmState: FlasherState = FlasherState.Idle,
    val connectedDevices: List<UsbDiskInfo> = emptyList(),
    val selectedDevice: UsbDiskInfo? = null,
    val selectedIsoUri: Uri? = null,
    val selectedIsoName: String? = null,
    val isoAnalysis: IsoTrieParser.AnalysisResult? = null,
    val isAnalyzingIso: Boolean = false,
    val selectedStrategyId: String = "LINUX_RAW_DD",
    val config: FlashEngineStrategy.FlashConfig = FlashEngineStrategy.FlashConfig(),
    val speedHistory: List<Double> = emptyList(), // For live throughput sparkline
    val logs: List<String> = emptyList(),
    val isFlashing: Boolean = false,
    val showSafetyDialog: Boolean = false,
    val activeBlocks: List<Int> = List(64) { 0 } // 0=pending, 1=writing, 2=done
)

class FlasherViewModel(
    application: Application,
    private val savedStateHandle: SavedStateHandle = SavedStateHandle()
) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "FlasherViewModel"
        const val ACTION_USB_PERMISSION = "com.ashishsinghbora.flashcore.USB_PERMISSION"
        private const val KEY_SAVED_STRATEGY = "saved_strategy_id"
        private const val KEY_SAVED_ISO_URI = "saved_iso_uri"
        private const val KEY_SAVED_ISO_NAME = "saved_iso_name"
    }

    private val usbManager = application.getSystemService(Context.USB_SERVICE) as UsbManager
    private val fsm = FlasherStateMachine()

    private val _uiState = MutableStateFlow(
        FlasherUiState(
            connectedDevices = emptyList(),
            selectedDevice = null,
            fsmState = FlasherState.Idle,
            logs = listOf("[INIT] FlashCore Subsystem Initialized. Zero-Server, Direct BOT SCSI Engine Ready.")
        )
    )
    val uiState: StateFlow<FlasherUiState> = _uiState.asStateFlow()

    private val strategies = listOf(
        LinuxRawDdStrategy(),
        WindowsUefiStrategy(),
        VentoyStrategy()
    )

    private var activeFlashJob: Job? = null
    private var isCancelledFlag = false

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    addLog("USB Hardware Event: OTG Flash Drive Attached")
                    refreshDevices()
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    addLog("USB Hardware Event: OTG Flash Drive Detached")
                    val detachedDevice: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }

                    val currentDevice = _uiState.value.selectedDevice?.device
                    val isCurrentDeviceDetached = (detachedDevice != null && currentDevice != null &&
                            detachedDevice.deviceName == currentDevice.deviceName) ||
                            (currentDevice != null && !usbManager.deviceList.containsKey(currentDevice.deviceName))

                    if (_uiState.value.isFlashing && isCurrentDeviceDetached) {
                        handleDeviceDetachedDuringFlash()
                    } else {
                        if (isCurrentDeviceDetached) {
                            _uiState.update { it.copy(selectedDevice = null) }
                        }
                        refreshDevices()
                    }
                }
                ACTION_USB_PERMISSION -> {
                    synchronized(this) {
                        val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                        }
                        val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                        if (device != null) {
                            if (granted) {
                                addLog("USB Permission Granted for ${device.productName ?: device.deviceName}")
                                refreshDevices(autoSelectDevice = device)
                            } else {
                                addLog("USB Permission Denied for ${device.productName ?: device.deviceName}")
                            }
                        }
                    }
                }
            }
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(ACTION_USB_PERMISSION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            application.registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            application.registerReceiver(usbReceiver, filter)
        }

        // Restore state across process death / configuration changes
        val savedStrategy = savedStateHandle.get<String>(KEY_SAVED_STRATEGY)
        val savedUriStr = savedStateHandle.get<String>(KEY_SAVED_ISO_URI)
        val savedName = savedStateHandle.get<String>(KEY_SAVED_ISO_NAME)
        if (savedStrategy != null || savedUriStr != null) {
            _uiState.update {
                it.copy(
                    selectedStrategyId = savedStrategy ?: it.selectedStrategyId,
                    selectedIsoUri = savedUriStr?.let { s -> Uri.parse(s) },
                    selectedIsoName = savedName ?: it.selectedIsoName
                )
            }
        }

        // Register foreground service cancellation hook
        FlashForegroundService.onCancelActionRequested = {
            cancelFlashing()
        }

        addLog("FlashCore Subsystem Initialized. Scanning for connected USB OTG storage drives...")
        refreshDevices()
    }

    fun getStrategies(): List<FlashEngineStrategy> = strategies

    fun refreshDevices(autoSelectDevice: UsbDevice? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            val deviceList = usbManager.deviceList.values.toList()
            val diskList = mutableListOf<UsbDiskInfo>()

            for (device in deviceList) {
                val driver = UsbMassStorageDriver(usbManager, device)
                if (driver.initDriver()) {
                    val hasPerm = usbManager.hasPermission(device)
                    if (hasPerm) {
                        try {
                            val info = driver.open()
                            diskList.add(info)
                            driver.close()
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed reading USB device ${device.deviceName}: ${e.message}")
                            diskList.add(createFallbackDiskInfo(device, hasPerm = true))
                        }
                    } else {
                        diskList.add(createFallbackDiskInfo(device, hasPerm = false))
                    }
                }
            }

            val currentSelected = _uiState.value.selectedDevice
            val selected = when {
                autoSelectDevice != null -> diskList.find { it.device == autoSelectDevice } ?: diskList.firstOrNull()
                currentSelected != null -> diskList.find { it.serialNumber == currentSelected.serialNumber } ?: diskList.firstOrNull()
                else -> diskList.firstOrNull()
            }

            if (diskList.isNotEmpty() && selected != null) {
                fsm.transition(FlasherEvent.DevicesUpdated(diskList, selected))
            } else {
                fsm.transition(FlasherEvent.ResetToIdle)
            }

            _uiState.update {
                it.copy(
                    connectedDevices = diskList,
                    selectedDevice = selected,
                    fsmState = fsm.state.value
                )
            }

            if (diskList.isEmpty()) {
                addLog("No USB Mass Storage devices attached. Please connect an OTG flash drive.")
            } else {
                addLog("Enumerated ${diskList.size} USB Storage Device(s): ${diskList.joinToString { it.displayName }}")
            }
        }
    }

    fun requestDevicePermission(device: UsbDiskInfo) {
        val dev = device.device
        if (dev == null || usbManager.hasPermission(dev)) {
            selectDevice(device)
            return
        }
        val context = getApplication<Application>()
        val permIntent = Intent(ACTION_USB_PERMISSION).apply {
            setPackage(context.packageName)
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val permissionIntent = PendingIntent.getBroadcast(
            context,
            0,
            permIntent,
            flags
        )
        addLog("Requesting USB OS Permission for ${device.displayName}...")
        usbManager.requestPermission(dev, permissionIntent)
    }

    fun selectDevice(device: UsbDiskInfo) {
        fsm.transition(FlasherEvent.DeviceSelected(device))
        _uiState.update {
            it.copy(
                selectedDevice = device,
                fsmState = fsm.state.value
            )
        }
        addLog("Selected target: ${device.displayName}")
    }

    fun selectStrategy(strategyId: String) {
        savedStateHandle[KEY_SAVED_STRATEGY] = strategyId
        _uiState.update { it.copy(selectedStrategyId = strategyId) }
        addLog("Switched Flashing Engine to: $strategyId")
    }

    fun updateConfig(config: FlashEngineStrategy.FlashConfig) {
        _uiState.update { it.copy(config = config) }
    }

    fun analyzeIsoUri(uri: Uri, displayName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isAnalyzingIso = true, selectedIsoUri = uri, selectedIsoName = displayName) }
            addLog("Analyzing ISO image: $displayName...")

            try {
                val context = getApplication<Application>()
                try {
                    val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                    context.contentResolver.takePersistableUriPermission(uri, takeFlags)
                    addLog("Persistable SAF URI permission acquired for $displayName")
                } catch (_: Exception) {}

                savedStateHandle[KEY_SAVED_ISO_URI] = uri.toString()
                savedStateHandle[KEY_SAVED_ISO_NAME] = displayName

                var totalSize = 0L
                context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    totalSize = pfd.statSize
                }

                val stream = context.contentResolver.openInputStream(uri)
                    ?: throw IllegalStateException("Cannot open input stream for $uri")

                val analysis = IsoTrieParser.parse(stream, totalSize, fileName = displayName)
                stream.close()

                // Auto-suggest strategy based on image classification
                val recommendedStrategy = when (analysis.imageType) {
                    IsoTrieParser.ImageType.WINDOWS_INSTALLER -> "WINDOWS_UEFI"
                    IsoTrieParser.ImageType.VENTOY_BOOTABLE -> "VENTOY_MULTIBOOT"
                    else -> "LINUX_RAW_DD"
                }

                _uiState.update {
                    it.copy(
                        isoAnalysis = analysis,
                        isAnalyzingIso = false,
                        selectedStrategyId = recommendedStrategy
                    )
                }

                addLog("OS Detected: ${analysis.osName} (${analysis.osRelease}) [${analysis.distroBadge}]")
                addLog("ISO Metadata: Type=${analysis.imageType.displayName}, Arch=${analysis.architecture}, Size=${analysis.formattedSize}")
                if (analysis.requiresWimSplit) {
                    addLog("Notice: install.wim > 4GB (${analysis.installWimSize / (1024 * 1024)} MB). Automatic SWM split enabled for FAT32 compatibility.")
                }
            } catch (e: Exception) {
                addLog("ISO Analysis Error: ${e.message}")
                _uiState.update { it.copy(isAnalyzingIso = false) }
            }
        }
    }

    /**
     * Loads a built-in virtual image (e.g. Ubuntu 24.04 Live or Windows 11 UEFI) for testing.
     */
    fun loadSampleImage(type: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isAnalyzingIso = true) }
            val context = getApplication<Application>()
            val sampleFile = File(context.cacheDir, if (type == "windows") "windows_11_uefi_sample.iso" else "ubuntu_24_04_live_sample.iso")

            if (!sampleFile.exists()) {
                sampleFile.outputStream().use { fos ->
                    // Generate a valid 32 MB synthetic ISO with El Torito / EFI headers
                    val header = ByteArray(64 * 1024)
                    "CD001".toByteArray(Charsets.US_ASCII).copyInto(header, 16 * 2048 + 1)
                    fos.write(header)
                    val dummyChunk = ByteArray(1024 * 1024)
                    for (i in 0 until 31) {
                        fos.write(dummyChunk)
                    }
                }
            }

            val uri = Uri.fromFile(sampleFile)
            analyzeIsoUri(uri, sampleFile.name)
        }
    }

    fun openSafetyConfirmation() {
        if (_uiState.value.selectedDevice == null) {
            addLog("Please select a target USB drive first.")
            return
        }
        if (_uiState.value.selectedIsoUri == null) {
            addLog("Please select a source ISO image first.")
            return
        }
        _uiState.update { it.copy(showSafetyDialog = true) }
    }

    fun dismissSafetyConfirmation() {
        _uiState.update { it.copy(showSafetyDialog = false) }
    }

    fun startFlashing() {
        dismissSafetyConfirmation()
        val target = _uiState.value.selectedDevice ?: return
        val sourceUri = _uiState.value.selectedIsoUri ?: return
        val analysis = _uiState.value.isoAnalysis ?: return
        val strategy = strategies.find { it.id == _uiState.value.selectedStrategyId } ?: strategies.first()

        isCancelledFlag = false
        _uiState.update {
            it.copy(
                isFlashing = true,
                speedHistory = emptyList(),
                activeBlocks = List(64) { 0 }
            )
        }

        val context = getApplication<Application>()
        FlashForegroundService.startService(context)

        activeFlashJob = viewModelScope.launch(Dispatchers.IO) {
            addLog("==========================================")
            addLog("LAUNCHING FLASH ENGINE: ${strategy.displayName}")
            addLog("Target: ${target.displayName}")
            addLog("Source: ${analysis.volumeLabel} (${analysis.totalSizeBytes / (1024 * 1024)} MB)")
            addLog("==========================================")

            val driver = UsbMassStorageDriver(usbManager, target.device)
            try {
                driver.open()
            } catch (e: Exception) {
                val errorMsg = "Failed to initialize USB driver: ${e.message}"
                addLog("ERROR: $errorMsg")
                fsm.transition(
                    FlasherEvent.FlashErrorOccurred(
                        device = target,
                        errorMessage = errorMsg,
                        canRetry = true,
                        failedLba = 0L
                    )
                )
                _uiState.update { it.copy(isFlashing = false, fsmState = fsm.state.value) }
                FlashForegroundService.stopService(context)
                return@launch
            }

            val result = strategy.execute(
                context = context,
                device = driver,
                targetDrive = target,
                sourceUri = sourceUri,
                isoAnalysis = analysis,
                config = _uiState.value.config,
                callback = object : FlashEngineStrategy.ProgressCallback {
                    override fun onPartitionProgress(stage: String, progress: Float) {
                        fsm.transition(FlasherEvent.PartitionProgressUpdate(target, stage, progress))
                        _uiState.update { it.copy(fsmState = fsm.state.value) }
                        addLog("[Partition] $stage (%.0f%%)".format(progress * 100))
                        FlashForegroundService.update(stage, (progress * 100).toInt(), 0.0, 0L)
                    }

                    override fun onStreamProgress(
                        writtenBytes: Long,
                        totalBytes: Long,
                        speedMBps: Double,
                        etaSeconds: Long,
                        bufferSaturation: Float,
                        currentLba: Long,
                        chunkIndex: Int,
                        totalChunks: Int
                    ) {
                        fsm.transition(
                            FlasherEvent.StreamProgressUpdate(
                                device = target,
                                writtenBytes = writtenBytes,
                                totalBytes = totalBytes,
                                speedMBps = speedMBps,
                                etaSeconds = etaSeconds,
                                bufferSaturation = bufferSaturation,
                                currentLba = currentLba,
                                currentChunk = chunkIndex,
                                totalChunks = totalChunks
                            )
                        )

                        // Update active block visualizer (64 blocks)
                        val progressFraction = (writtenBytes.toDouble() / totalBytes.toDouble()).coerceIn(0.0, 1.0)
                        val completedBlocksCount = (progressFraction * 64).toInt()
                        val updatedBlocks = List(64) { idx ->
                            when {
                                idx < completedBlocksCount -> 2 // Done
                                idx == completedBlocksCount -> 1 // Active writing
                                else -> 0 // Pending
                            }
                        }

                        val pct = (progressFraction * 100).toInt()
                        FlashForegroundService.update("Writing OS payload...", pct, speedMBps, etaSeconds)

                        _uiState.update {
                            val newHistory = (it.speedHistory + speedMBps).takeLast(30)
                            it.copy(
                                fsmState = fsm.state.value,
                                speedHistory = newHistory,
                                activeBlocks = updatedBlocks
                            )
                        }
                    }

                    override fun onVerificationProgress(verifiedBytes: Long, totalBytes: Long, isMatching: Boolean) {
                        fsm.transition(
                            FlasherEvent.VerificationProgressUpdate(target, verifiedBytes, totalBytes, isMatching)
                        )
                        _uiState.update { it.copy(fsmState = fsm.state.value) }
                        val vPct = if (totalBytes > 0) ((verifiedBytes.toDouble() / totalBytes.toDouble()) * 100).toInt() else 0
                        FlashForegroundService.update("Verifying sectors...", vPct, 0.0, 0L)
                    }

                    override fun onLogMessage(message: String) {
                        addLog(message)
                    }
                },
                isCancelled = { isCancelledFlag }
            )

            try { driver.close() } catch (_: Exception) {}

            if (result.success) {
                fsm.transition(
                    FlasherEvent.FlashFinished(
                        device = target,
                        totalBytes = result.totalBytesWritten,
                        durationMs = result.durationMs,
                        avgSpeedMBps = result.averageSpeedMBps,
                        checksum = result.sha256
                    )
                )
                _uiState.update {
                    it.copy(
                        isFlashing = false,
                        fsmState = fsm.state.value,
                        activeBlocks = List(64) { 2 }
                    )
                }
                addLog("FLASH COMPLETED SUCCESSFULLY: ${result.totalBytesWritten / (1024 * 1024)} MB in ${result.durationMs / 1000}s (SHA256: ${result.sha256.take(12)}...)")
                FlashForegroundService.complete("Flash Succeeded", "Bootable USB created and verified successfully!", true)
            } else {
                fsm.transition(
                    FlasherEvent.FlashErrorOccurred(
                        device = target,
                        errorMessage = result.errorMessage ?: "Unknown error",
                        canRetry = true,
                        failedLba = 0L
                    )
                )
                _uiState.update {
                    it.copy(
                        isFlashing = false,
                        fsmState = fsm.state.value
                    )
                }
                addLog("FLASH FAILED: ${result.errorMessage}")
                FlashForegroundService.complete("Flash Failed", result.errorMessage ?: "Flash failed", false)
            }
        }
    }

    private fun handleDeviceDetachedDuringFlash() {
        isCancelledFlag = true
        activeFlashJob?.cancel()
        val target = _uiState.value.selectedDevice
        val errorMsg = "USB drive disconnected or suffered OTG power loss during active write operation"
        addLog("FATAL: $errorMsg")
        fsm.transition(
            FlasherEvent.FlashErrorOccurred(
                device = target ?: UsbDiskInfo(
                    device = null,
                    vendorId = 0,
                    productId = 0,
                    manufacturerName = "",
                    productName = "Disconnected Drive",
                    vendorString = "",
                    productString = "",
                    revision = "",
                    serialNumber = "",
                    totalCapacityBytes = 0L,
                    totalSectors = 0L,
                    sectorSizeBytes = 512,
                    isRemovable = true,
                    isWriteProtected = false,
                    hasPermission = false
                ),
                errorMessage = errorMsg,
                canRetry = false,
                failedLba = 0L
            )
        )
        _uiState.update {
            it.copy(
                isFlashing = false,
                selectedDevice = null,
                fsmState = fsm.state.value
            )
        }
        FlashForegroundService.complete("Flash Aborted", "USB drive was disconnected during flashing", false)
        refreshDevices()
    }

    fun cancelFlashing() {
        if (!_uiState.value.isFlashing && !isCancelledFlag) return
        isCancelledFlag = true
        activeFlashJob?.cancel()
        addLog("Flash cancellation requested by user...")
        val currentDev = _uiState.value.selectedDevice
        if (currentDev != null) {
            fsm.transition(
                FlasherEvent.FlashErrorOccurred(
                    device = currentDev,
                    errorMessage = "Operation cancelled by user",
                    canRetry = true,
                    failedLba = 0L
                )
            )
        }
        _uiState.update {
            it.copy(
                isFlashing = false,
                fsmState = FlasherState.ErrorRecovery(
                    device = currentDev,
                    errorMessage = "Operation cancelled by user",
                    canRetry = true,
                    lastFailedLba = 0L,
                    previousState = "Streaming"
                )
            )
        }
        FlashForegroundService.complete("Flash Cancelled", "Operation cancelled by user", false)
    }

    override fun onCleared() {
        super.onCleared()
        try {
            getApplication<Application>().unregisterReceiver(usbReceiver)
        } catch (_: Exception) {}
        FlashForegroundService.onCancelActionRequested = null
    }

    fun resetSession() {
        fsm.transition(FlasherEvent.ResetToIdle)
        _uiState.update {
            it.copy(
                fsmState = FlasherState.Idle,
                isFlashing = false,
                speedHistory = emptyList(),
                activeBlocks = List(64) { 0 }
            )
        }
        refreshDevices()
    }

    private fun addLog(message: String) {
        val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        val formatted = "[$time] $message"
        _uiState.update {
            it.copy(logs = (it.logs + formatted).takeLast(100))
        }
    }

    private fun createFallbackDiskInfo(device: UsbDevice, hasPerm: Boolean): UsbDiskInfo {
        return UsbDiskInfo(
            device = device,
            vendorId = device.vendorId,
            productId = device.productId,
            manufacturerName = device.manufacturerName ?: "USB Storage",
            productName = device.productName ?: "Mass Storage Drive",
            vendorString = "USB",
            productString = device.productName ?: "OTG Flash Drive",
            revision = "1.0",
            serialNumber = device.serialNumber ?: "DEV_${device.deviceId}",
            totalCapacityBytes = 0L, // Capacity unknown until queried via SCSI
            totalSectors = 0L,
            sectorSizeBytes = 512,
            isRemovable = true,
            isWriteProtected = false,
            hasPermission = hasPerm
        )
    }
}
