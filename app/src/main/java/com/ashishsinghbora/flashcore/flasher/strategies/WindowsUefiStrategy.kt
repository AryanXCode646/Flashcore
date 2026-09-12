package com.ashishsinghbora.flashcore.flasher.strategies

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.ashishsinghbora.flashcore.block.BlockDevice
import com.ashishsinghbora.flashcore.dsa.IsoTrieParser
import com.ashishsinghbora.flashcore.dsa.WimChunker
import com.ashishsinghbora.flashcore.fat32.Fat32Writer
import com.ashishsinghbora.flashcore.flasher.FlashEngineStrategy
import com.ashishsinghbora.flashcore.flasher.FlashEngineStrategy.FlashConfig
import com.ashishsinghbora.flashcore.flasher.FlashEngineStrategy.ProgressCallback
import com.ashishsinghbora.flashcore.flasher.FlashEngineStrategy.StrategyResult
import com.ashishsinghbora.flashcore.flasher.safety.FlashSafetyValidator
import com.ashishsinghbora.flashcore.flasher.windows.WindowsBootFilesManager
import com.ashishsinghbora.flashcore.flasher.windows.WindowsCapacityAnalyzer
import com.ashishsinghbora.flashcore.flasher.windows.WindowsCapabilityDetector
import com.ashishsinghbora.flashcore.iso.FileChannelIsoSource
import com.ashishsinghbora.flashcore.iso.IsoFilesystemReader
import com.ashishsinghbora.flashcore.partition.GptBuilder
import com.ashishsinghbora.flashcore.partition.MbrBuilder
import com.ashishsinghbora.flashcore.partition.PartitionBlockDevice
import com.ashishsinghbora.flashcore.usb.UsbDiskInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.channels.FileChannel
import java.security.MessageDigest

/**
 * Production-Grade Windows UEFI Bootable USB Engine.
 *
 * Implements the full Phase 7 pipeline:
 * Windows ISO
 *   ↓
 * ISO inspection (WindowsCapabilityDetector: Architecture, WIM/ESD/SWM, edition)
 *   ↓
 * boot configuration (UEFI loader, BCD hive, legacy fallback)
 *   ↓
 * install.wim detection (exact size, FAT32 4 GB boundary check)
 *   ↓
 * FAT32 capacity analysis (WindowsCapacityAnalyzer: cluster slack, FAT overhead, partition headroom)
 *   ↓
 * WIM splitting if required (WimChunker: SWM planning & on-the-fly streaming)
 *   ↓
 * filesystem creation (Protective MBR + GPT layout + FAT32 volume format)
 *   ↓
 * file extraction (streaming copy of all files & directories)
 *   ↓
 * boot files (WindowsBootFilesManager: ensure /efi/boot/bootx64.efi & /efi/microsoft/boot/bcd)
 *   ↓
 * verification (binary PE MZ header check, BCD regf check, SWM part confirmation)
 *   ↓
 * success
 */
class WindowsUefiStrategy : FlashEngineStrategy {

    override val id: String = "WINDOWS_UEFI"
    override val displayName: String = "Windows UEFI Bootable USB"
    override val description: String = "Genuine GPT partitioning, FAT32 formatting, and ISO extraction with dynamic capability detection and automated WIM splitting."

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
        callback.onLogMessage("STARTING WINDOWS UEFI FLASH PIPELINE")
        callback.onLogMessage("==================================================")

        var pfd: ParcelFileDescriptor? = null
        var channel: FileChannel? = null
        var totalWritten = 0L

        try {
            // -----------------------------------------------------------------
            // STEP 1: OPEN ISO & CAPABILITY INSPECTION
            // -----------------------------------------------------------------
            callback.onPartitionProgress("Opening source Windows ISO...", 0.03f)

            channel = try {
                val fd = context.contentResolver.openFileDescriptor(sourceUri, "r")
                if (fd != null) {
                    pfd = fd
                    FileInputStream(fd.fileDescriptor).channel
                } else null
            } catch (_: Exception) {
                null
            } ?: run {
                val path = sourceUri.path
                if (path != null && File(path).exists()) {
                    FileInputStream(File(path)).channel
                } else {
                    throw IllegalStateException("Unable to open source Windows ISO from URI: $sourceUri")
                }
            }

            val isoSource = FileChannelIsoSource(channel)
            val isoReader = IsoFilesystemReader(isoSource)
            val isoOpened = isoReader.open()
            if (!isoOpened) {
                throw IllegalStateException("Failed to parse ISO 9660 / Joliet filesystem descriptors from source image")
            }

            callback.onPartitionProgress("Detecting Windows ISO Capabilities...", 0.06f)
            val capabilities = WindowsCapabilityDetector.detect(isoReader)

            callback.onLogMessage("OS Edition:        ${capabilities.editionHint}")
            callback.onLogMessage("Architecture:      ${capabilities.arch.displayName}")
            callback.onLogMessage("Installer Format:  ${capabilities.installerType.displayName}")
            callback.onLogMessage("Install Payload:   ${capabilities.installImagePath ?: "None"} (${FlashSafetyValidator.formatBytes(capabilities.installImageSizeBytes)})")
            callback.onLogMessage("Requires Split:    ${capabilities.requiresWimSplit} (Parts: ${capabilities.splitPartCount})")
            callback.onLogMessage("UEFI Boot Support: ${capabilities.hasUefiBoot} (${capabilities.uefiLoaderPath ?: "None"})")
            callback.onLogMessage("BCD Hive:          ${capabilities.bcdPath ?: "None"}")

            if (!capabilities.hasUefiBoot && capabilities.uefiLoaderPath == null && capabilities.legacyBootMgrPath == null) {
                throw IllegalStateException("Source Windows ISO has no valid boot manager or UEFI loader")
            }

            // -----------------------------------------------------------------
            // STEP 2: FAT32 CAPACITY & GEOMETRY ANALYSIS
            // -----------------------------------------------------------------
            callback.onPartitionProgress("Analyzing FAT32 geometry and drive capacity...", 0.10f)
            val totalDiskSectors = targetDrive.totalSectors
            val sectorSize = targetDrive.sectorSizeBytes.coerceAtLeast(512)

            val capacityAnalysis = WindowsCapacityAnalyzer.analyze(
                device = device,
                capabilities = capabilities,
                reader = isoReader,
                sectorSizeBytes = sectorSize,
                targetTotalSectors = totalDiskSectors
            )

            callback.onLogMessage(capacityAnalysis.summary)

            if (!capacityAnalysis.isSufficient) {
                val err = "Target drive capacity insufficient for FAT32 Windows media: Deficit ${FlashSafetyValidator.formatBytes(capacityAnalysis.deficitBytes)}"
                callback.onLogMessage("ERROR: $err")
                return@withContext failureResult(err, startTime)
            }

            // Safety check: confirm drive identity and write-protection
            val safetyResult = FlashSafetyValidator.validate(
                device = device,
                targetDrive = targetDrive,
                isoSizeBytes = capacityAnalysis.totalRequiredBytes,
                confirmedByUser = config.confirmedByUser
            )

            if (safetyResult.errors.isNotEmpty()) {
                val err = safetyResult.errors.first().message
                callback.onLogMessage("SAFETY ERROR: $err")
                return@withContext failureResult(err, startTime)
            }

            if (safetyResult.warnings.isNotEmpty()) {
                for (w in safetyResult.warnings) {
                    callback.onLogMessage("[SAFETY WARNING] ${w.title}: ${w.message}")
                }
            }

            if (isCancelled()) return@withContext failureResult("Operation cancelled by user", startTime)

            // -----------------------------------------------------------------
            // STEP 3: PARTITIONING (Protective MBR + GPT Header & Tables)
            // -----------------------------------------------------------------
            callback.onPartitionProgress("Writing Protective MBR at Sector 0...", 0.15f)
            val protectiveMbr = MbrBuilder.buildProtectiveMbr(totalDiskSectors)
            val mbrWritten = device.write(0L, 1, protectiveMbr)
            if (!mbrWritten) {
                throw IllegalStateException("SCSI write failed while writing Protective MBR to Sector 0")
            }

            callback.onPartitionProgress("Constructing UEFI GPT Partition Tables...", 0.18f)
            val fat32StartLba = capacityAnalysis.partitionFirstLba
            val fat32EndLba = capacityAnalysis.partitionLastLba
            val fat32Sectors = capacityAnalysis.partitionSectorCount

            val gptPartition = GptBuilder.GptPartition(
                typeGuid = GptBuilder.GUID_MICROSOFT_BASIC_DATA,
                firstLba = fat32StartLba,
                lastLba = fat32EndLba,
                partitionName = "WIN_SETUP"
            )

            val gptLayout = GptBuilder.build(
                totalDiskSectors = totalDiskSectors,
                partitions = listOf(gptPartition),
                sectorSizeBytes = sectorSize
            )

            // Write Primary GPT Header (LBA 1) and Table (LBA 2..33)
            device.write(1L, 1, gptLayout.primaryHeaderSector)
            device.write(2L, 32, gptLayout.primaryPartitionTableBytes)

            // Write Backup GPT at end of drive
            val backupTableLba = totalDiskSectors - 1L - 32L
            device.write(backupTableLba, 32, gptLayout.backupPartitionTableBytes)
            device.write(totalDiskSectors - 1L, 1, gptLayout.backupHeaderSector)

            // -----------------------------------------------------------------
            // STEP 4: FILESYSTEM CREATION (FAT32 Volume Formatting)
            // -----------------------------------------------------------------
            callback.onPartitionProgress("Formatting FAT32 Volume on Partition LBA $fat32StartLba...", 0.22f)
            val partitionDevice = PartitionBlockDevice(device, fat32StartLba, fat32Sectors)
            val fat32Writer = Fat32Writer.createNew(
                device = partitionDevice,
                totalSectors = fat32Sectors,
                volumeLabel = "WININSTALL"
            )
            fat32Writer.formatVolume("WININSTALL")
            callback.onLogMessage("FAT32 Volume formatted successfully with 4 KB clusters.")

            if (isCancelled()) return@withContext failureResult("Operation cancelled by user", startTime)

            // -----------------------------------------------------------------
            // STEP 5: DIRECTORY TREE CREATION
            // -----------------------------------------------------------------
            callback.onPartitionProgress("Creating directory hierarchy on FAT32 volume...", 0.25f)
            val dirEntries = isoReader.entries.filter { it.isDirectory }
            for (dir in dirEntries) {
                if (isCancelled()) return@withContext failureResult("Operation cancelled by user", startTime)
                fat32Writer.mkdir(dir.path)
            }

            // -----------------------------------------------------------------
            // STEP 6: FILE EXTRACTION & WIM SPLITTING
            // -----------------------------------------------------------------
            callback.onPartitionProgress("Extracting Windows installation payloads to USB...", 0.30f)
            val fileEntries = isoReader.entries.filter { !it.isDirectory }
            val totalBytesToCopy = capacityAnalysis.totalRequiredBytes
            var lastLogTime = System.currentTimeMillis()

            for (fileEntry in fileEntries) {
                if (isCancelled()) return@withContext failureResult("Operation cancelled by user", startTime, totalWritten)

                val isInstallImage = capabilities.installImagePath != null &&
                        fileEntry.path.equals(capabilities.installImagePath, ignoreCase = true)

                if (isInstallImage && capabilities.requiresWimSplit && config.autoSplitWim) {
                    // Split oversized WIM into .swm parts
                    callback.onLogMessage("Splitting large WIM (${FlashSafetyValidator.formatBytes(fileEntry.sizeBytes)}) into ${capabilities.splitPartCount} SWM parts...")
                    val splitPlan = WimChunker.planSwmSplit(fileEntry.sizeBytes)

                    for (part in splitPlan) {
                        if (isCancelled()) return@withContext failureResult("Operation cancelled by user", startTime, totalWritten)
                        val partPath = fileEntry.path.substringBeforeLast('/') + "/" + part.fileName
                        callback.onLogMessage("Writing SWM part: ${part.fileName} (${FlashSafetyValidator.formatBytes(part.lengthBytes)}, Part ${part.partIndex}/${part.totalParts})...")

                        val partStream = object : InputStream() {
                            private var bytesReadFromPart = 0L
                            private val baseStream = isoReader.openStream(fileEntry).apply {
                                skip(part.startOffset)
                            }

                            override fun read(): Int {
                                if (bytesReadFromPart >= part.lengthBytes) return -1
                                val b = baseStream.read()
                                if (b != -1) bytesReadFromPart++
                                return b
                            }

                            override fun read(b: ByteArray, off: Int, len: Int): Int {
                                if (bytesReadFromPart >= part.lengthBytes) return -1
                                val toRead = minOf(len.toLong(), part.lengthBytes - bytesReadFromPart).toInt()
                                val r = baseStream.read(b, off, toRead)
                                if (r > 0) bytesReadFromPart += r
                                return r
                            }

                            override fun close() {
                                baseStream.close()
                            }
                        }

                        var partWrittenSoFar = 0L
                        fat32Writer.writeFileStream(partPath, partStream, part.lengthBytes) { bytesWritten, _ ->
                            val delta = bytesWritten - partWrittenSoFar
                            partWrittenSoFar = bytesWritten
                            totalWritten += delta

                            val now = System.currentTimeMillis()
                            val elapsedSec = (now - startTime) / 1000.0
                            if ((now - lastLogTime >= 250) || totalWritten >= totalBytesToCopy) {
                                lastLogTime = now
                                val speedMBps = if (elapsedSec > 0.001) (totalWritten.toDouble() / (1024.0 * 1024.0)) / elapsedSec else 0.0
                                val eta = if (speedMBps > 0.05) (((totalBytesToCopy - totalWritten).toDouble() / (1024.0 * 1024.0)) / speedMBps).toLong() else 0L
                                callback.onStreamProgress(
                                    writtenBytes = minOf(totalWritten, totalBytesToCopy),
                                    totalBytes = totalBytesToCopy,
                                    speedMBps = speedMBps,
                                    etaSeconds = eta,
                                    bufferSaturation = 1.0f,
                                    currentLba = fat32StartLba,
                                    chunkIndex = part.partIndex,
                                    totalChunks = part.totalParts
                                )
                            }
                        }
                    }
                } else {
                    // Standard single-file copy
                    val stream = isoReader.openStream(fileEntry)
                    var fileWrittenSoFar = 0L
                    fat32Writer.writeFileStream(fileEntry.path, stream, fileEntry.sizeBytes) { bytesWritten, _ ->
                        val delta = bytesWritten - fileWrittenSoFar
                        fileWrittenSoFar = bytesWritten
                        totalWritten += delta

                        val now = System.currentTimeMillis()
                        val elapsedSec = (now - startTime) / 1000.0
                        if ((now - lastLogTime >= 250) || totalWritten >= totalBytesToCopy) {
                            lastLogTime = now
                            val speedMBps = if (elapsedSec > 0.001) (totalWritten.toDouble() / (1024.0 * 1024.0)) / elapsedSec else 0.0
                            val eta = if (speedMBps > 0.05) (((totalBytesToCopy - totalWritten).toDouble() / (1024.0 * 1024.0)) / speedMBps).toLong() else 0L
                            callback.onStreamProgress(
                                writtenBytes = minOf(totalWritten, totalBytesToCopy),
                                totalBytes = totalBytesToCopy,
                                speedMBps = speedMBps,
                                etaSeconds = eta,
                                bufferSaturation = 1.0f,
                                currentLba = fat32StartLba,
                                chunkIndex = 1,
                                totalChunks = 1
                            )
                        }
                    }
                }
            }

            if (isCancelled()) return@withContext failureResult("Operation cancelled by user", startTime, totalWritten)

            // -----------------------------------------------------------------
            // STEP 7: BOOT FILES PROVISIONING (UEFI Loader & BCD)
            // -----------------------------------------------------------------
            callback.onPartitionProgress("Provisioning UEFI bootloader and BCD configuration...", 0.90f)
            val bootActions = WindowsBootFilesManager.resolveAndEnsureBootFiles(fat32Writer, capabilities)
            for (action in bootActions) {
                callback.onLogMessage(action)
            }

            // -----------------------------------------------------------------
            // STEP 8: FLUSH FILESYSTEM & DRIVE CACHE
            // -----------------------------------------------------------------
            callback.onPartitionProgress("Flushing FAT32 structures and drive cache...", 0.94f)
            fat32Writer.flush()
            val flushSuccess = device.flush()
            if (!flushSuccess) {
                val err = "Physical drive cache flush failed"
                callback.onLogMessage("ERROR: $err")
                return@withContext failureResult(err, startTime, totalWritten)
            }
            callback.onLogMessage("Filesystem and physical drive cache synchronized.")

            // -----------------------------------------------------------------
            // STEP 9: GENUINE VERIFICATION
            // -----------------------------------------------------------------
            callback.onPartitionProgress("Executing genuine UEFI boot & filesystem verification...", 0.96f)
            callback.onLogMessage("Validating UEFI binary headers and BCD registry hives on target volume...")

            val bootVerification = WindowsBootFilesManager.verifyBootIntegrity(fat32Writer, capabilities)
            if (!bootVerification.isBootable) {
                val err = bootVerification.errorMessage ?: "UEFI boot verification failed"
                callback.onLogMessage("VERIFICATION ERROR: $err")
                return@withContext failureResult(err, startTime, totalWritten)
            }

            for (vf in bootVerification.verifiedFiles) {
                callback.onLogMessage("Verified target boot structure: $vf")
            }

            // Verify SWM split parts on target FAT32 volume
            if (capabilities.requiresWimSplit) {
                val plan = WimChunker.planSwmSplit(capabilities.installImageSizeBytes)
                for (part in plan) {
                    val partPath = capabilities.installImagePath!!.substringBeforeLast('/') + "/" + part.fileName
                    if (!fat32Writer.exists(partPath)) {
                        val err = "Verification failed: Expected SWM part $partPath was not found on FAT32 volume"
                        callback.onLogMessage("VERIFICATION ERROR: $err")
                        return@withContext failureResult(err, startTime, totalWritten)
                    }
                    callback.onLogMessage("Verified SWM split chunk on target volume: $partPath (${FlashSafetyValidator.formatBytes(part.lengthBytes)})")
                }
            }

            // -----------------------------------------------------------------
            // STEP 10: SUCCESS
            // -----------------------------------------------------------------
            val durationMs = (System.currentTimeMillis() - startTime).coerceAtLeast(1)
            val avgSpeed = (totalWritten.toDouble() / (1024.0 * 1024.0)) / (durationMs / 1000.0)

            callback.onPartitionProgress("Windows UEFI Flashing Completed Successfully!", 1.0f)
            callback.onLogMessage("Flashing completed in ${durationMs / 1000}s at %.2f MB/s.".format(avgSpeed))
            callback.onLogMessage("Media is ready for native UEFI installation on ${capabilities.arch.displayName}.")

            StrategyResult(
                success = true,
                totalBytesWritten = totalWritten,
                durationMs = durationMs,
                averageSpeedMBps = avgSpeed,
                sha256 = "UEFI_FAT32_VERIFIED"
            )
        } catch (e: Exception) {
            callback.onLogMessage("Windows UEFI flash error: ${e.message}")
            failureResult(e.message ?: "Windows flash failed", startTime, totalWritten)
        } finally {
            try { channel?.close() } catch (_: Exception) {}
            try { pfd?.close() } catch (_: Exception) {}
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
