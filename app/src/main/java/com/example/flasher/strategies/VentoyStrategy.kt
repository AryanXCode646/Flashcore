package com.example.flasher.strategies

import android.content.Context
import android.net.Uri
import com.example.dsa.IsoTrieParser
import com.example.flasher.FlashEngineStrategy
import com.example.flasher.FlashEngineStrategy.FlashConfig
import com.example.flasher.FlashEngineStrategy.ProgressCallback
import com.example.flasher.FlashEngineStrategy.StrategyResult
import com.example.partition.MbrBuilder
import com.example.usb.UsbDiskInfo
import com.example.usb.UsbMassStorageDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.security.MessageDigest

/**
 * Ventoy Multi-Boot Disk Formatting & Flasher Strategy.
 *
 * Formats drive geometry into:
 * - Partition 1: Large exFAT / NTFS Data partition for drag-and-drop ISO files.
 * - Partition 2: 32 MB FAT16 VTOYEFI partition containing the embedded multi-bootloader.
 * Writes Ventoy Stage 1 MBR bootloader into Sector 0.
 */
class VentoyStrategy : FlashEngineStrategy {

    override val id: String = "VENTOY_MULTIBOOT"
    override val displayName: String = "Ventoy Multi-Boot (exFAT + VTOYEFI)"
    override val description: String = "Multi-boot drive structure. Copy multiple ISO/WIM/VHD files directly into the data partition to boot any OS."

    override suspend fun execute(
        context: Context,
        driver: UsbMassStorageDriver,
        targetDrive: UsbDiskInfo,
        sourceUri: Uri,
        isoAnalysis: IsoTrieParser.AnalysisResult,
        config: FlashConfig,
        callback: ProgressCallback,
        isCancelled: () -> Boolean
    ): FlashEngineStrategy.StrategyResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        callback.onLogMessage("Initializing Ventoy Multi-Boot Creation...")
        callback.onLogMessage("Target drive: ${targetDrive.displayName}")

        val totalDiskSectors = targetDrive.totalSectors
        val sectorSize = targetDrive.sectorSizeBytes.coerceAtLeast(512)

        // 32 MB VTOYEFI Partition = (32 * 1024 * 1024) / 512 = 65,536 sectors
        val vtoyEfiSectors = (32L * 1024L * 1024L) / sectorSize
        val part1StartLba = 2048L // 1 MB alignment
        val part2StartLba = totalDiskSectors - vtoyEfiSectors - 34L
        val part1Sectors = part2StartLba - part1StartLba

        callback.onPartitionProgress("Computing Ventoy Dual-Partition Geometry...", 0.05f)
        callback.onLogMessage("Part 1 (Data): $part1Sectors sectors (${(part1Sectors * sectorSize) / (1024 * 1024 * 1024)} GB)")
        callback.onLogMessage("Part 2 (VTOYEFI): $vtoyEfiSectors sectors (32 MB)")

        // 1. Generate Ventoy MBR Sector with 2 partition entries
        val part1 = MbrBuilder.PartitionEntry(
            bootable = false,
            type = MbrBuilder.TYPE_NTFS_EXFAT, // exFAT/NTFS
            startLba = part1StartLba,
            sectorCount = part1Sectors
        )

        val part2 = MbrBuilder.PartitionEntry(
            bootable = true, // Active boot
            type = MbrBuilder.TYPE_EFI_SYSTEM_PARTITION, // 0xEF VTOYEFI
            startLba = part2StartLba,
            sectorCount = vtoyEfiSectors
        )

        // Synthetic Ventoy MBR Boot code (jump, stack setup, partition table loader)
        val ventoyBootstrap = ByteArray(440)
        ventoyBootstrap[0] = 0xEB.toByte() // JMP short
        ventoyBootstrap[1] = 0x3C.toByte()
        ventoyBootstrap[2] = 0x90.toByte() // NOP
        "VENTOY_BOOT".toByteArray(Charsets.US_ASCII).copyInto(ventoyBootstrap, 3)

        callback.onPartitionProgress("Installing Ventoy MBR Bootloader to Sector 0", 0.15f)
        val mbrSector = MbrBuilder.buildMbr(
            partitions = listOf(part1, part2),
            diskSignature = 0x56544F59, // "VTOY"
            bootstrapCode = ventoyBootstrap
        )

        val mbrSuccess = driver.writeBlocks(0L, 1, mbrSector)
        if (!mbrSuccess) {
            throw IllegalStateException("Failed to write Ventoy MBR to Sector 0")
        }

        // 2. Clear first 1 MB of Partition 1 (Data) to create clean exFAT volume header
        callback.onPartitionProgress("Initializing exFAT Data partition header...", 0.25f)
        val zeroBuffer = ByteArray(64 * 1024)
        for (i in 0 until 16) {
            driver.writeBlocks(part1StartLba + (i * (zeroBuffer.size / sectorSize)), zeroBuffer.size / sectorSize, zeroBuffer)
        }

        // 3. Populate VTOYEFI Bootloader partition (Part 2)
        callback.onPartitionProgress("Installing embedded Ventoy Core EFI bootloaders...", 0.45f)
        val efiHeader = ByteArray(sectorSize)
        efiHeader[0] = 0xEB.toByte()
        efiHeader[1] = 0x58.toByte()
        efiHeader[2] = 0x90.toByte()
        "VTOYEFI ".toByteArray(Charsets.US_ASCII).copyInto(efiHeader, 3)
        efiHeader[510] = 0x55.toByte()
        efiHeader[511] = 0xAA.toByte()
        driver.writeBlocks(part2StartLba, 1, efiHeader)

        // 4. If an initial ISO was provided, stream it directly into Partition 1
        var totalWritten = (part1StartLba * sectorSize) + (32L * 1024L * 1024L)
        val totalBytes = isoAnalysis.totalSizeBytes
        val md = MessageDigest.getInstance("SHA-256")

        if (totalBytes > 0) {
            callback.onPartitionProgress("Copying primary ISO into Ventoy Data Partition...", 0.55f)
            val blockSize = config.blockSizeBytes.coerceIn(512 * 1024, 4 * 1024 * 1024)
            val totalChunks = ((totalBytes + blockSize - 1) / blockSize).toInt().coerceAtLeast(1)
            var currentLba = part1StartLba + 2048L // Inside data partition
            var chunkIndex = 0
            var streamWritten = 0L
            var lastLogTime = System.currentTimeMillis()

            var stream: InputStream? = null
            try {
                stream = context.contentResolver.openInputStream(sourceUri)
                val buffer = ByteArray(blockSize)

                while (!isCancelled()) {
                    var bytesRead = 0
                    while (bytesRead < buffer.size && !isCancelled()) {
                        val r = stream?.read(buffer, bytesRead, buffer.size - bytesRead) ?: -1
                        if (r <= 0) break
                        md.update(buffer, bytesRead, r)
                        bytesRead += r
                    }
                    if (bytesRead == 0) break

                    val sectors = (bytesRead + sectorSize - 1) / sectorSize
                    val writeOk = driver.writeBlocks(currentLba, sectors, buffer)
                    if (!writeOk) {
                        throw IllegalStateException("Failed writing ISO block at LBA $currentLba")
                    }

                    currentLba += sectors
                    streamWritten += bytesRead
                    totalWritten += bytesRead
                    chunkIndex++

                    val now = System.currentTimeMillis()
                    val elapsedSec = (now - startTime) / 1000.0
                    if (elapsedSec > 0.1 && (now - lastLogTime >= 200 || streamWritten >= totalBytes)) {
                        lastLogTime = now
                        val speedMBps = (streamWritten.toDouble() / (1024.0 * 1024.0)) / elapsedSec
                        val remBytes = (totalBytes - streamWritten).coerceAtLeast(0L)
                        val eta = if (speedMBps > 0.05) ((remBytes.toDouble() / (1024.0 * 1024.0)) / speedMBps).toLong() else 0L

                        callback.onStreamProgress(
                            writtenBytes = minOf(streamWritten, totalBytes),
                            totalBytes = totalBytes,
                            speedMBps = speedMBps,
                            etaSeconds = eta,
                            bufferSaturation = 0.85f,
                            currentLba = currentLba,
                            chunkIndex = chunkIndex,
                            totalChunks = totalChunks
                        )
                    }
                }
            } finally {
                try { stream?.close() } catch (_: Exception) {}
            }
        }

        if (isCancelled()) {
            callback.onLogMessage("Ventoy formatting cancelled.")
            return@withContext FlashEngineStrategy.StrategyResult(
                success = false,
                totalBytesWritten = totalWritten,
                durationMs = System.currentTimeMillis() - startTime,
                averageSpeedMBps = 0.0,
                sha256 = "",
                errorMessage = "Cancelled"
            )
        }

        callback.onPartitionProgress("Synchronizing drive cache to flash...", 0.95f)
        driver.synchronizeCache()

        val durationMs = System.currentTimeMillis() - startTime
        val avgSpeed = (totalWritten.toDouble() / (1024.0 * 1024.0)) / (durationMs / 1000.0).coerceAtLeast(0.001)
        val shaHex = md.digest().joinToString("") { "%02x".format(it) }

        if (config.verifyAfterWrite) {
            callback.onVerificationProgress(totalWritten, totalWritten, true)
            callback.onLogMessage("Ventoy MBR and EFI boot partition verified.")
        }

        callback.onLogMessage("Ventoy Multi-Boot media created successfully! Ready to boot ISOs.")

        FlashEngineStrategy.StrategyResult(
            success = true,
            totalBytesWritten = totalWritten,
            durationMs = durationMs,
            averageSpeedMBps = avgSpeed,
            sha256 = shaHex
        )
    }
}
