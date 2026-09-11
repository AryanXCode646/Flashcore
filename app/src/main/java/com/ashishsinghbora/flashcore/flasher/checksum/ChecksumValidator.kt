package com.ashishsinghbora.flashcore.flasher.checksum

import java.io.InputStream
import java.security.MessageDigest

/**
 * Streaming Checksum Engine for ISO image verification.
 * Supports pre-flash validation against vendor-provided digests (SHA-256, SHA-1, MD5).
 */
object ChecksumValidator {

    enum class ChecksumType(val algorithm: String) {
        SHA256("SHA-256"),
        SHA1("SHA-1"),
        MD5("MD5")
    }

    data class ChecksumResult(
        val matches: Boolean,
        val calculatedChecksum: String,
        val expectedChecksum: String?,
        val algorithm: String,
        val totalBytes: Long,
        val durationMs: Long
    )

    /**
     * Computes the cryptographic digest of an [InputStream] in streaming chunks.
     */
    fun computeDigest(
        stream: InputStream,
        algorithm: String = "SHA-256",
        bufferSize: Int = 128 * 1024,
        onProgress: ((bytesRead: Long) -> Unit)? = null,
        isCancelled: () -> Boolean = { false }
    ): Pair<String, Long> {
        val md = MessageDigest.getInstance(algorithm)
        val buffer = ByteArray(bufferSize)
        var totalRead = 0L

        while (!isCancelled()) {
            val read = stream.read(buffer, 0, buffer.size)
            if (read <= 0) break
            md.update(buffer, 0, read)
            totalRead += read
            onProgress?.invoke(totalRead)
        }

        if (isCancelled()) {
            return Pair("", totalRead)
        }

        val digestBytes = md.digest()
        val hex = digestBytes.joinToString("") { "%02x".format(it) }
        return Pair(hex, totalRead)
    }

    /**
     * Compares an actual hex checksum against an expected hex checksum.
     * Trims whitespace and ignores case.
     */
    fun verifyChecksum(actualHex: String, expectedHex: String): Boolean {
        if (expectedHex.isBlank()) return true
        val cleanActual = actualHex.trim().lowercase()
        val cleanExpected = expectedHex.trim().lowercase()
        return cleanActual == cleanExpected
    }
}
