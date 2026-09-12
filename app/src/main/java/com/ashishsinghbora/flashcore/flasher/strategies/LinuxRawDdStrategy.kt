package com.ashishsinghbora.flashcore.flasher.strategies

import android.content.Context
import android.net.Uri
import com.ashishsinghbora.flashcore.block.BlockDevice
import com.ashishsinghbora.flashcore.dsa.DirectRingBuffer
import com.ashishsinghbora.flashcore.dsa.IsoTrieParser
import com.ashishsinghbora.flashcore.dsa.RollingChecksumEngine
import com.ashishsinghbora.flashcore.flasher.FlashEngineStrategy
import com.ashishsinghbora.flashcore.flasher.FlashEngineStrategy.FlashConfig
import com.ashishsinghbora.flashcore.flasher.FlashEngineStrategy.ProgressCallback
import com.ashishsinghbora.flashcore.flasher.FlashEngineStrategy.StrategyResult
import com.ashishsinghbora.flashcore.flasher.checksum.ChecksumValidator
import com.ashishsinghbora.flashcore.flasher.safety.FlashSafetyValidator
import com.ashishsinghbora.flashcore.flasher.verification.FlashVerifier
import com.ashishsinghbora.flashcore.usb.UsbDiskInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.security.MessageDigest

/**
 * Production-Quality Linux Hybrid DD Raw Image Flashing Strategy.
 *
 * Implements the complete end-to-end flashing pipeline:
 * ISO -> Validate -> Optional Checksum -> USB Capacity Check -> Safety Warnings ->
 * Raw Write -> Flush -> Genuine Verification (Source Block vs USB Read) -> Success.
 */
class LinuxRawDdStrategy : FlashEngineStrategy {

    override val id: String = "LINUX_RAW_DD"
    override val displayName: String = "Linux Hybrid (Raw DD)"
    override val description: String = "Byte-for-byte direct sector-0 streaming for Ubuntu, Debian, Arch, Fedora, Tails, Proxmox, and hybrid ISOs."

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
        callback.onLogMessage("STARTING LINUX RAW DD FLASH PIPELINE")
        callback.onLogMessage("==================================================")

        // ---------------------------------------------------------------------
        // STEP 1: ISO VALIDATION
        // ---------------------------------------------------------------------
        callback.onPartitionProgress("Validating Source ISO Image...", 0.02f)
        val totalBytes = isoAnalysis.totalSizeBytes
        if (totalBytes <= 0L) {
            val err = "Invalid source image: report size is 0 bytes"
            callback.onLogMessage("ERROR: $err")
            return@withContext failureResult(err, startTime)
        }

        // Validate stream can be opened
        val streamCheck = try {
            context.contentResolver.openInputStream(sourceUri)?.use { it.read() }
        } catch (e: Exception) {
            val err = "Cannot read source ISO URI: ${e.message}"
            callback.onLogMessage("ERROR: $err")
            return@withContext failureResult(err, startTime)
        }

        if (streamCheck == null || streamCheck == -1) {
            val err = "Source image stream is empty or inaccessible"
            callback.onLogMessage("ERROR: $err")
            return@withContext failureResult(err, startTime)
        }

        if (config.requireIsohybrid && !isoAnalysis.isIsohybrid) {
            val err = "Selected ISO is not an isohybrid image (no MBR boot code at Sector 0). Direct raw DD writing may produce non-bootable media."
            callback.onLogMessage("ERROR: $err")
            return@withContext failureResult(err, startTime)
        }

        callback.onLogMessage("ISO Validation OK: ${isoAnalysis.volumeLabel} (${FlashSafetyValidator.formatBytes(totalBytes)}), Isohybrid=${isoAnalysis.isIsohybrid}")

        // ---------------------------------------------------------------------
        // STEP 2: OPTIONAL SOURCE CHECKSUM VERIFICATION
        // ---------------------------------------------------------------------
        if (!config.expectedChecksum.isNullOrBlank()) {
            callback.onPartitionProgress("Verifying Source Image Checksum...", 0.05f)
            callback.onLogMessage("Calculating source ${config.checksumAlgorithm} digest before writing...")

            val (actualDigest, bytesHashed) = try {
                context.contentResolver.openInputStream(sourceUri)?.use { stream ->
                    ChecksumValidator.computeDigest(
                        stream = stream,
                        algorithm = config.checksumAlgorithm,
                        onProgress = { read ->
                            val pct = ((read.toDouble() / totalBytes.toDouble()) * 100.0).coerceIn(0.0, 100.0)
                            if (read % (32 * 1024 * 1024) == 0L) {
                                callback.onLogMessage("Hashing source image: %.1f%% (%s / %s)".format(
                                    pct, FlashSafetyValidator.formatBytes(read), FlashSafetyValidator.formatBytes(totalBytes)
                                ))
                            }
                        },
                        isCancelled = isCancelled
                    )
                } ?: Pair("", 0L)
            } catch (e: Exception) {
                val err = "Source checksum calculation failed: ${e.message}"
                callback.onLogMessage("ERROR: $err")
                return@withContext failureResult(err, startTime)
            }

            if (isCancelled()) {
                callback.onLogMessage("Operation cancelled during source checksum verification.")
                return@withContext failureResult("Operation cancelled", startTime)
            }

            callback.onLogMessage("Source Calculated ${config.checksumAlgorithm}: $actualDigest")
            callback.onLogMessage("Expected Checksum:                     ${config.expectedChecksum}")

            val matches = ChecksumValidator.verifyChecksum(actualDigest, config.expectedChecksum)
            if (!matches) {
                val err = "SOURCE CHECKSUM MISMATCH! Expected ${config.expectedChecksum} but calculated $actualDigest. Aborting flash to protect target media."
                callback.onLogMessage("ERROR: $err")
                return@withContext failureResult(err, startTime)
            }
            callback.onLogMessage("Source checksum matches expected digest.")
        }

        // ---------------------------------------------------------------------
        // STEP 3: USB CAPACITY CHECK & SAFETY VALIDATION
        // ---------------------------------------------------------------------
        callback.onPartitionProgress("Verifying Target Drive Safety & Geometry...", 0.08f)
        val safetyResult = FlashSafetyValidator.validate(
            device = device,
            targetDrive = targetDrive,
            isoSizeBytes = totalBytes,
            confirmedByUser = config.confirmedByUser
        )

        callback.onLogMessage("Device Identity: ${safetyResult.identity.vendorString} ${safetyResult.identity.productString} (S/N: ${safetyResult.identity.serialNumber})")
        callback.onLogMessage("Capacity: ${FlashSafetyValidator.formatBytes(safetyResult.identity.totalCapacityBytes)} (${safetyResult.identity.totalSectors} sectors @ ${safetyResult.identity.sectorSizeBytes} B/sector)")

        // Check for fatal errors (e.g., insufficient capacity, write-protected, unconfirmed)
        if (safetyResult.errors.isNotEmpty()) {
            for (err in safetyResult.errors) {
                callback.onLogMessage("[SAFETY ERROR] ${err.title}: ${err.message}")
            }
            val primaryError = safetyResult.errors.first().message
            return@withContext failureResult(primaryError, startTime)
        }

        // Log warnings (e.g. existing partitions will be wiped)
        if (safetyResult.warnings.isNotEmpty()) {
            for (warn in safetyResult.warnings) {
                callback.onLogMessage("[SAFETY WARNING] ${warn.title}: ${warn.message}")
            }
        }

        // ---------------------------------------------------------------------
        // STEP 4: RAW STREAMING WRITE (Sector 0 Direct DD)
        // ---------------------------------------------------------------------
        callback.onPartitionProgress("Streaming Bootloader & Partitions to Sector 0...", 0.10f)
        val blockSize = config.blockSizeBytes.coerceIn(512 * 1024, 4 * 1024 * 1024)
        val sectorSize = targetDrive.sectorSizeBytes.coerceAtLeast(512)
        val totalChunks = ((totalBytes + blockSize - 1) / blockSize).toInt().coerceAtLeast(1)

        val ringBuffer = DirectRingBuffer(chunkCapacity = 16, chunkSizeBytes = blockSize)
        val md = MessageDigest.getInstance("SHA-256")

        var totalWritten = 0L
        var lastLogTime = System.currentTimeMillis()
        var currentLba = 0L
        var chunkIndex = 0

        // Producer Thread: Streams from source URI into DirectRingBuffer
        val producerThread = Thread {
            var stream: InputStream? = null
            try {
                stream = context.contentResolver.openInputStream(sourceUri)
                    ?: throw IllegalStateException("Unable to open source ISO stream")

                val tempArray = ByteArray(64 * 1024)
                var bytesRemaining = totalBytes

                while (bytesRemaining > 0 && !isCancelled()) {
                    val slot = ringBuffer.acquireWriteSlot()
                    val toReadThisChunk = minOf(blockSize.toLong(), bytesRemaining).toInt()
                    var bytesInSlot = 0

                    while (bytesInSlot < toReadThisChunk && !isCancelled()) {
                        val maxRead = minOf(tempArray.size, toReadThisChunk - bytesInSlot)
                        val r = stream.read(tempArray, 0, maxRead)
                        if (r <= 0) break
                        slot.buffer.put(tempArray, 0, r)
                        md.update(tempArray, 0, r)
                        bytesInSlot += r
                    }

                    if (bytesInSlot == 0) break

                    // Pad the last chunk to a sector boundary if needed
                    val remainder = bytesInSlot % sectorSize
                    if (remainder != 0) {
                        val padding = sectorSize - remainder
                        for (p in 0 until padding) {
                            slot.buffer.put(0.toByte())
                        }
                        bytesInSlot += padding
                    }

                    val slotCrc = RollingChecksumEngine.computeCrc32(slot.buffer, 0, bytesInSlot)
                    ringBuffer.commitWrite(slot.slotIndex, bytesInSlot, currentLba, slotCrc)
                    bytesRemaining -= toReadThisChunk
                }
            } catch (e: Exception) {
                callback.onLogMessage("Producer error: ${e.message}")
            } finally {
                try { stream?.close() } catch (_: Exception) {}
                ringBuffer.close()
            }
        }
        producerThread.start()

        // Consumer Loop: Writes direct buffers to BlockDevice
        try {
            while (!isCancelled()) {
                val readSlot = ringBuffer.acquireReadSlot() ?: break // Streaming finished

                val bytesToWrite = readSlot.validBytes
                val sectorsThisChunk = bytesToWrite / sectorSize

                val writeSuccess = device.writeDirectBuffer(
                    lba = currentLba,
                    blockCount = sectorsThisChunk,
                    directBuffer = readSlot.buffer,
                    offset = 0,
                    length = bytesToWrite
                )

                if (!writeSuccess) {
                    throw IllegalStateException("SCSI write failed at LBA $currentLba (Chunk $chunkIndex)")
                }

                ringBuffer.commitRead(readSlot.slotIndex)

                totalWritten += bytesToWrite
                currentLba += sectorsThisChunk
                chunkIndex++

                val now = System.currentTimeMillis()
                val elapsedSec = (now - startTime) / 1000.0
                if ((now - lastLogTime >= 200) || totalWritten >= totalBytes) {
                    lastLogTime = now
                    val speedMBps = if (elapsedSec > 0.001) (totalWritten.toDouble() / (1024.0 * 1024.0)) / elapsedSec else 0.0
                    val remainingBytes = (totalBytes - totalWritten).coerceAtLeast(0L)
                    val etaSeconds = if (speedMBps > 0.05) ((remainingBytes.toDouble() / (1024.0 * 1024.0)) / speedMBps).toLong() else 0L
                    val saturation = ringBuffer.getSaturation()

                    callback.onStreamProgress(
                        writtenBytes = minOf(totalWritten, totalBytes),
                        totalBytes = totalBytes,
                        speedMBps = speedMBps,
                        etaSeconds = etaSeconds,
                        bufferSaturation = saturation,
                        currentLba = currentLba,
                        chunkIndex = chunkIndex,
                        totalChunks = totalChunks
                    )
                }

                if (isCancelled()) {
                    break
                }
            }

            if (isCancelled()) {
                callback.onLogMessage("Flashing cancelled by user during write.")
                return@withContext failureResult("Operation cancelled by user", startTime, totalWritten)
            }

            // -----------------------------------------------------------------
            // STEP 5: FLUSH (Synchronize Cache)
            // -----------------------------------------------------------------
            callback.onPartitionProgress("Synchronizing drive write cache to NAND...", 0.85f)
            callback.onLogMessage("Executing cache flush (SCSI SYNCHRONIZE CACHE)...")
            val flushSuccess = device.flush()
            if (!flushSuccess) {
                val err = "SCSI cache synchronize failed on target drive"
                callback.onLogMessage("ERROR: $err")
                return@withContext failureResult(err, startTime, totalWritten)
            }
            callback.onLogMessage("Cache synchronized.")

            val durationMs = System.currentTimeMillis() - startTime
            val avgSpeed = (totalWritten.toDouble() / (1024.0 * 1024.0)) / (durationMs / 1000.0).coerceAtLeast(0.001)
            val writtenShaHex = md.digest().joinToString("") { "%02x".format(it) }

            // -----------------------------------------------------------------
            // STEP 6: GENUINE VERIFICATION (Source Block vs USB Read)
            // -----------------------------------------------------------------
            if (config.verifyAfterWrite) {
                callback.onPartitionProgress("Verifying written media (Source block vs USB read)...", 0.88f)
                callback.onLogMessage("Starting bit-for-bit media verification...")

                val verificationResult = FlashVerifier.verify(
                    device = device,
                    sourceStreamProvider = {
                        context.contentResolver.openInputStream(sourceUri)
                            ?: throw IllegalStateException("Cannot reopen source ISO stream for verification")
                    },
                    totalBytesToVerify = totalBytes,
                    sectorSizeBytes = sectorSize,
                    chunkSizeBytes = blockSize,
                    startLba = 0L,
                    onProgress = { verified, total, speed ->
                        callback.onVerificationProgress(verified, total, true)
                        val pct = ((verified.toDouble() / total.toDouble()) * 100.0).coerceIn(0.0, 100.0)
                        val vProgress = 0.88f + ((verified.toFloat() / total.toFloat()) * 0.11f)
                        callback.onPartitionProgress("Verifying written sectors: %.0f%% (%.1f MB/s)".format(pct, speed), vProgress)
                    },
                    isCancelled = isCancelled
                )

                if (isCancelled()) {
                    callback.onLogMessage("Operation cancelled by user during verification.")
                    return@withContext failureResult("Operation cancelled by user", startTime, totalWritten)
                }

                if (!verificationResult.success) {
                    val err = verificationResult.errorMessage ?: "Target media verification failed"
                    callback.onLogMessage("[VERIFICATION FAILED] $err")
                    callback.onVerificationProgress(verificationResult.verifiedBytes, totalBytes, false)
                    return@withContext StrategyResult(
                        success = false,
                        totalBytesWritten = totalWritten,
                        durationMs = System.currentTimeMillis() - startTime,
                        averageSpeedMBps = avgSpeed,
                        sha256 = writtenShaHex,
                        errorMessage = err
                    )
                }

                callback.onLogMessage("Target media verification PASSED: 100% of ${FlashSafetyValidator.formatBytes(verificationResult.verifiedBytes)} verified bit-for-bit with 0 sector mismatches.")
                callback.onLogMessage("Source SHA-256: ${verificationResult.sourceSha256}")
                callback.onLogMessage("Target SHA-256: ${verificationResult.targetSha256}")
            } else {
                callback.onLogMessage("Verification skipped by user configuration.")
            }

            // -----------------------------------------------------------------
            // STEP 7: SUCCESS
            // -----------------------------------------------------------------
            callback.onPartitionProgress("Flashing Completed Successfully!", 1.0f)
            callback.onLogMessage("Flashing completed successfully in ${durationMs / 1000}s at %.2f MB/s".format(avgSpeed))

            StrategyResult(
                success = true,
                totalBytesWritten = totalWritten,
                durationMs = durationMs,
                averageSpeedMBps = avgSpeed,
                sha256 = writtenShaHex
            )
        } catch (e: Exception) {
            callback.onLogMessage("Flash failed: ${e.message}")
            failureResult(e.message ?: "Unknown I/O error", startTime, totalWritten)
        } finally {
            ringBuffer.close()
            try { producerThread.join(2000) } catch (_: Exception) {}
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
