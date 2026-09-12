package com.ashishsinghbora.flashcore.flasher.verification

import com.ashishsinghbora.flashcore.block.BlockDevice
import java.io.InputStream
import java.security.MessageDigest

/**
 * Robust Bit-for-Bit Target Media Verification Subsystem.
 *
 * Performs real read-back from the target BlockDevice:
 * source block -> USB READ -> compare byte-for-byte -> update cryptographic digests.
 *
 * Guarantees that any corrupt NAND block, USB drop, or silent controller error is
 * immediately identified with exact LBA and byte-offset coordinates.
 */
object FlashVerifier {

    data class VerificationResult(
        val success: Boolean,
        val verifiedBytes: Long,
        val durationMs: Long,
        val averageSpeedMBps: Double,
        val sourceSha256: String,
        val targetSha256: String,
        val mismatchLba: Long? = null,
        val mismatchOffset: Long? = null,
        val errorMessage: String? = null
    )

    /**
     * Verifies written media by reading sectors back from [device] and comparing
     * byte-for-byte with the [sourceStreamProvider].
     */
    suspend fun verify(
        device: BlockDevice,
        sourceStreamProvider: () -> InputStream,
        totalBytesToVerify: Long,
        sectorSizeBytes: Int = device.sectorSizeBytes.coerceAtLeast(512),
        chunkSizeBytes: Int = 1024 * 1024,
        startLba: Long = 0L,
        onProgress: (verifiedBytes: Long, totalBytes: Long, speedMBps: Double) -> Unit = { _, _, _ -> },
        isCancelled: () -> Boolean = { false }
    ): VerificationResult {
        val startTime = System.currentTimeMillis()
        if (totalBytesToVerify <= 0L) {
            return VerificationResult(
                success = true,
                verifiedBytes = 0L,
                durationMs = 0L,
                averageSpeedMBps = 0.0,
                sourceSha256 = "",
                targetSha256 = ""
            )
        }

        val alignedChunkSize = ((chunkSizeBytes / sectorSizeBytes).coerceAtLeast(1)) * sectorSizeBytes
        val sourceBuffer = ByteArray(alignedChunkSize)
        val deviceBuffer = ByteArray(alignedChunkSize)

        val sourceMd = MessageDigest.getInstance("SHA-256")
        val targetMd = MessageDigest.getInstance("SHA-256")

        var verifiedBytes = 0L
        var currentLba = startLba
        var lastLogTime = System.currentTimeMillis()

        var sourceStream: InputStream? = null
        try {
            sourceStream = sourceStreamProvider()

            while (verifiedBytes < totalBytesToVerify && !isCancelled()) {
                val bytesToReadThisChunk = minOf(alignedChunkSize.toLong(), totalBytesToVerify - verifiedBytes).toInt()
                val sectorsToRead = (bytesToReadThisChunk + sectorSizeBytes - 1) / sectorSizeBytes

                // 1. Read block from source stream
                var sourceBytesRead = 0
                while (sourceBytesRead < bytesToReadThisChunk && !isCancelled()) {
                    val r = sourceStream.read(sourceBuffer, sourceBytesRead, bytesToReadThisChunk - sourceBytesRead)
                    if (r <= 0) break
                    sourceBytesRead += r
                }

                if (sourceBytesRead < bytesToReadThisChunk) {
                    return VerificationResult(
                        success = false,
                        verifiedBytes = verifiedBytes,
                        durationMs = System.currentTimeMillis() - startTime,
                        averageSpeedMBps = 0.0,
                        sourceSha256 = "",
                        targetSha256 = "",
                        errorMessage = "Premature end of source stream during verification at offset ${verifiedBytes + sourceBytesRead}"
                    )
                }

                // 2. Read sectors back from USB target device
                val readSuccess = try {
                    device.read(
                        lba = currentLba,
                        blockCount = sectorsToRead,
                        dest = deviceBuffer,
                        offset = 0
                    )
                } catch (e: Exception) {
                    return VerificationResult(
                        success = false,
                        verifiedBytes = verifiedBytes,
                        durationMs = System.currentTimeMillis() - startTime,
                        averageSpeedMBps = 0.0,
                        sourceSha256 = "",
                        targetSha256 = "",
                        mismatchLba = currentLba,
                        mismatchOffset = verifiedBytes,
                        errorMessage = "SCSI READ failed at LBA $currentLba during verification: ${e.message}"
                    )
                }

                if (!readSuccess) {
                    return VerificationResult(
                        success = false,
                        verifiedBytes = verifiedBytes,
                        durationMs = System.currentTimeMillis() - startTime,
                        averageSpeedMBps = 0.0,
                        sourceSha256 = "",
                        targetSha256 = "",
                        mismatchLba = currentLba,
                        mismatchOffset = verifiedBytes,
                        errorMessage = "SCSI READ failed at LBA $currentLba during verification"
                    )
                }

                // 3. Compare byte-for-byte
                for (i in 0 until bytesToReadThisChunk) {
                    val expected = sourceBuffer[i]
                    val actual = deviceBuffer[i]
                    if (expected != actual) {
                        val mismatchLba = currentLba + (i / sectorSizeBytes)
                        val mismatchSectorOffset = i % sectorSizeBytes
                        val absoluteOffset = verifiedBytes + i

                        return VerificationResult(
                            success = false,
                            verifiedBytes = verifiedBytes + i,
                            durationMs = System.currentTimeMillis() - startTime,
                            averageSpeedMBps = 0.0,
                            sourceSha256 = "",
                            targetSha256 = "",
                            mismatchLba = mismatchLba,
                            mismatchOffset = absoluteOffset,
                            errorMessage = "Media verification failed at LBA $mismatchLba (byte offset $absoluteOffset, sector offset $mismatchSectorOffset): " +
                                    "expected 0x%02X but read 0x%02X from device".format(expected, actual)
                        )
                    }
                }

                // 4. Update cryptographic digests
                sourceMd.update(sourceBuffer, 0, bytesToReadThisChunk)
                targetMd.update(deviceBuffer, 0, bytesToReadThisChunk)

                verifiedBytes += bytesToReadThisChunk
                currentLba += sectorsToRead

                val now = System.currentTimeMillis()
                val elapsedSec = (now - startTime) / 1000.0
                if ((now - lastLogTime >= 250) || verifiedBytes >= totalBytesToVerify) {
                    lastLogTime = now
                    val speed = if (elapsedSec > 0.001) (verifiedBytes.toDouble() / (1024.0 * 1024.0)) / elapsedSec else 0.0
                    onProgress(verifiedBytes, totalBytesToVerify, speed)
                }
            }

            if (isCancelled()) {
                return VerificationResult(
                    success = false,
                    verifiedBytes = verifiedBytes,
                    durationMs = System.currentTimeMillis() - startTime,
                    averageSpeedMBps = 0.0,
                    sourceSha256 = "",
                    targetSha256 = "",
                    errorMessage = "Verification cancelled by user"
                )
            }

            val sourceSha = sourceMd.digest().joinToString("") { "%02x".format(it) }
            val targetSha = targetMd.digest().joinToString("") { "%02x".format(it) }

            if (sourceSha != targetSha) {
                return VerificationResult(
                    success = false,
                    verifiedBytes = verifiedBytes,
                    durationMs = System.currentTimeMillis() - startTime,
                    averageSpeedMBps = 0.0,
                    sourceSha256 = sourceSha,
                    targetSha256 = targetSha,
                    errorMessage = "SHA-256 digest mismatch after bit comparison: source=$sourceSha vs target=$targetSha"
                )
            }

            val durationMs = (System.currentTimeMillis() - startTime).coerceAtLeast(1)
            val avgSpeed = (verifiedBytes.toDouble() / (1024.0 * 1024.0)) / (durationMs / 1000.0)

            return VerificationResult(
                success = true,
                verifiedBytes = verifiedBytes,
                durationMs = durationMs,
                averageSpeedMBps = avgSpeed,
                sourceSha256 = sourceSha,
                targetSha256 = targetSha
            )
        } finally {
            try { sourceStream?.close() } catch (_: Exception) {}
        }
    }
}
