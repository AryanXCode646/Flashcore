package com.example.flasher.strategies

import android.content.Context
import android.net.Uri
import com.example.block.BlockDevice
import com.example.dsa.IsoTrieParser
import com.example.flasher.FlashEngineStrategy
import com.example.flasher.FlashEngineStrategy.FlashConfig
import com.example.flasher.FlashEngineStrategy.ProgressCallback
import com.example.flasher.FlashEngineStrategy.StrategyResult
import com.example.flasher.safety.FlashSafetyValidator
import com.example.flasher.ventoy.DefaultVentoyAssetProvider
import com.example.flasher.ventoy.VentoyAssetProvider
import com.example.flasher.ventoy.VentoyDetector
import com.example.flasher.ventoy.VentoyInstallMode
import com.example.flasher.ventoy.VentoyInstaller
import com.example.flasher.ventoy.VentoyLicenseNotice
import com.example.flasher.ventoy.VentoyPartitionStyle
import com.example.usb.UsbDiskInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream

/**
 * Ventoy Multi-Boot Environment Strategy.
 *
 * Distinct from raw DD or Windows filesystem extraction:
 * Installs the Ventoy multi-boot runtime environment (dual-partition MBR/GPT layout with 32 MB VTOYEFI),
 * formats the data volume as FAT32, and writes bootable OS images as regular files in the filesystem
 * rather than destroying the disk with raw sector writes.
 *
 * Complies with GPL-3.0 third-party licensing requirements (upstream author: longpanda).
 */
class VentoyStrategy(
    private val assetProvider: VentoyAssetProvider = DefaultVentoyAssetProvider()
) : FlashEngineStrategy {

    override val id: String = "VENTOY_MULTIBOOT"
    override val displayName: String = "Ventoy Multi-Boot Engine"
    override val description: String = "Installs Ventoy multi-boot environment (dual-partition MBR/GPT, 32 MB VTOYEFI, FAT32 data volume) and stores ISOs as filesystem files."

    override suspend fun execute(
        context: Context,
        device: BlockDevice,
        targetDrive: UsbDiskInfo,
        sourceUri: Uri,
        isoAnalysis: IsoTrieParser.AnalysisResult,
        config: FlashConfig,
        callback: ProgressCallback,
        isCancelled: () -> Boolean
    ): StrategyResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        callback.onLogMessage("==================================================")
        callback.onLogMessage("STARTING VENTOY MULTI-BOOT PIPELINE")
        callback.onLogMessage("==================================================")

        // 1. Safety & Pre-flight Validation
        val safetyResult = FlashSafetyValidator.validate(
            device = device,
            targetDrive = targetDrive,
            isoSizeBytes = 100L * 1024L * 1024L, // Min 100 MB for Ventoy
            confirmedByUser = config.confirmedByUser
        )

        if (!safetyResult.isSafeToFlash) {
            callback.onLogMessage("SAFETY PRE-CHECK REJECTED:")
            for (err in safetyResult.errors) {
                callback.onLogMessage(" - ${err.message}")
            }
            val firstError = safetyResult.errors.firstOrNull()?.message
                ?: "Safety check rejected target USB device"
            return@withContext failureResult(firstError, startTime)
        }

        for (warning in safetyResult.warnings) {
            callback.onLogMessage("SAFETY WARNING: ${warning.message}")
        }

        // 2. Inspect Existing Drive for Ventoy
        val existingInfo = VentoyDetector.detect(device)
        val mode = if (existingInfo.isVentoyInstalled) {
            callback.onLogMessage("Existing Ventoy media detected on drive (Version: ${existingInfo.installedVersion ?: "Unknown"}).")
            callback.onLogMessage("Existing stored ISOs: ${existingInfo.storedIsoFiles.size} file(s).")
            // Default to non-destructive update if already Ventoy to protect user files
            VentoyInstallMode.NON_DESTRUCTIVE_UPDATE
        } else {
            callback.onLogMessage("Target drive is unpartitioned or non-Ventoy. Performing fresh installation.")
            VentoyInstallMode.FRESH_INSTALL
        }

        // 3. Resolve Initial ISO Stream (if user selected an ISO to include)
        var initialStream: InputStream? = null
        var initialName: String? = null
        var initialSize = 0L

        if (isoAnalysis.totalSizeBytes > 0) {
            try {
                initialStream = context.contentResolver.openInputStream(sourceUri)
                val uriName = sourceUri.lastPathSegment?.substringAfterLast('/')?.ifBlank { null }
                initialName = uriName ?: (isoAnalysis.volumeLabel.ifBlank { "bootable_os" } + ".iso")
                initialSize = isoAnalysis.totalSizeBytes
                callback.onLogMessage("Initial ISO payload selected: $initialName (${FlashSafetyValidator.formatBytes(initialSize)})")
            } catch (e: Exception) {
                callback.onLogMessage("Warning: Could not open source ISO stream (${e.message}). Proceeding with pure Ventoy installation.")
            }
        }

        val installer = VentoyInstaller(assetProvider)

        try {
            val installResult = installer.install(
                device = device,
                targetDrive = targetDrive,
                mode = mode,
                partitionStyle = VentoyPartitionStyle.MBR,
                initialIsoStream = initialStream,
                initialIsoName = initialName,
                initialIsoSize = initialSize,
                callback = callback,
                isCancelled = isCancelled
            )

            if (!installResult.success) {
                return@withContext failureResult(
                    installResult.errorMessage ?: "Ventoy installation failed",
                    startTime,
                    installResult.totalBytesWritten
                )
            }

            val durationMs = installResult.durationMs
            val avgSpeed = (installResult.totalBytesWritten.toDouble() / (1024.0 * 1024.0)) /
                    (durationMs / 1000.0).coerceAtLeast(0.001)

            callback.onLogMessage("==================================================")
            callback.onLogMessage("VENTOY INSTALLATION COMPLETED SUCCESSFULLY")
            callback.onLogMessage("Mode:       ${installResult.mode.displayName}")
            callback.onLogMessage("Version:    ${installResult.ventoyVersion}")
            callback.onLogMessage("Duration:   ${durationMs / 1000}s (Avg Speed: ${"%.2f".format(avgSpeed)} MB/s)")
            callback.onLogMessage("Stored ISO: ${installResult.isoFilesStored.joinToString().ifEmpty { "None (Ready for drag-and-drop)" }}")
            callback.onLogMessage("==================================================")

            StrategyResult(
                success = true,
                totalBytesWritten = installResult.totalBytesWritten,
                durationMs = durationMs,
                averageSpeedMBps = avgSpeed,
                sha256 = "VENTOY_VTOYEFI_VERIFIED"
            )
        } catch (e: Exception) {
            callback.onLogMessage("Ventoy Engine Error: ${e.message}")
            failureResult(e.message ?: "Ventoy execution error", startTime)
        } finally {
            try { initialStream?.close() } catch (_: Exception) {}
        }
    }

    private fun failureResult(message: String, startTime: Long, totalWritten: Long = 0L): StrategyResult {
        return StrategyResult(
            success = false,
            totalBytesWritten = totalWritten,
            durationMs = System.currentTimeMillis() - startTime,
            averageSpeedMBps = 0.0,
            sha256 = "",
            errorMessage = message
        )
    }
}
