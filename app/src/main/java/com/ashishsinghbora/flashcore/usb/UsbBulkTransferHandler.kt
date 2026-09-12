package com.ashishsinghbora.flashcore.usb

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.util.Log

/**
 * Functional interface abstracting a raw USB bulk transfer invocation.
 * Allows decoupling low-level endpoint I/O from Android hardware for pure JVM testing.
 */
fun interface BulkTransferEndpoint {
    /**
     * Executes a single low-level bulk transfer.
     *
     * @param buffer Destination or source byte array.
     * @param offset Byte offset in [buffer].
     * @param length Maximum number of bytes to transfer.
     * @param timeoutMs Timeout in milliseconds for this attempt.
     * @return Number of bytes actually transferred, 0 if zero bytes were transferred, or negative error code on failure.
     */
    fun transfer(buffer: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int
}

/**
 * Hardened USB Bulk Transfer Auditor & Continuation Handler.
 *
 * Implements strict transfer length verification for USB Bulk-Only Transport (BOT)
 * data, CBW, and CSW phases according to GitHub Issue #6:
 *
 * 1. Determines expected byte count before initiating transfer.
 * 2. Loops and accumulates partial packet transfers until the expected length is fulfilled.
 * 3. Records actual transferred bytes and chunk counts.
 * 4. Detects partial, zero-byte, and negative failure responses.
 * 5. Fails safely without exposing partial buffers or silently returning success.
 *
 * @param maxZeroByteRetries Maximum consecutive 0-byte responses before aborting with safe failure.
 * @param zeroByteSleepMs Milliseconds to sleep between consecutive 0-byte retries.
 */
open class UsbBulkTransferHandler(
    val maxZeroByteRetries: Int = DEFAULT_MAX_ZERO_BYTE_RETRIES,
    val zeroByteSleepMs: Long = DEFAULT_ZERO_BYTE_SLEEP_MS
) {
    companion object {
        private const val TAG = "UsbBulkTransferHandler"
        const val DEFAULT_MAX_ZERO_BYTE_RETRIES = 3
        const val DEFAULT_ZERO_BYTE_SLEEP_MS = 1L
    }

    /**
     * Executes an audited USB Bulk IN (Device-to-Host) transfer.
     */
    fun transferIn(
        endpoint: BulkTransferEndpoint,
        buffer: ByteArray,
        offset: Int,
        expectedBytes: Int,
        timeoutMs: Int
    ): UsbTransferResult {
        return executeTransfer(UsbTransferDirection.IN, endpoint, buffer, offset, expectedBytes, timeoutMs)
    }

    /**
     * Executes an audited USB Bulk OUT (Host-to-Device) transfer.
     */
    fun transferOut(
        endpoint: BulkTransferEndpoint,
        buffer: ByteArray,
        offset: Int,
        expectedBytes: Int,
        timeoutMs: Int
    ): UsbTransferResult {
        return executeTransfer(UsbTransferDirection.OUT, endpoint, buffer, offset, expectedBytes, timeoutMs)
    }

    /**
     * Android UsbDeviceConnection convenience overload for Bulk IN transfers.
     */
    fun transferIn(
        conn: UsbDeviceConnection,
        ep: UsbEndpoint,
        buffer: ByteArray,
        offset: Int,
        expectedBytes: Int,
        timeoutMs: Int
    ): UsbTransferResult {
        return transferIn(
            endpoint = { buf, off, len, to -> conn.bulkTransfer(ep, buf, off, len, to) },
            buffer = buffer,
            offset = offset,
            expectedBytes = expectedBytes,
            timeoutMs = timeoutMs
        )
    }

    /**
     * Android UsbDeviceConnection convenience overload for Bulk OUT transfers.
     */
    fun transferOut(
        conn: UsbDeviceConnection,
        ep: UsbEndpoint,
        buffer: ByteArray,
        offset: Int,
        expectedBytes: Int,
        timeoutMs: Int
    ): UsbTransferResult {
        return transferOut(
            endpoint = { buf, off, len, to -> conn.bulkTransfer(ep, buf, off, len, to) },
            buffer = buffer,
            offset = offset,
            expectedBytes = expectedBytes,
            timeoutMs = timeoutMs
        )
    }

    /**
     * Executes a Bulk IN transfer and throws [UsbShortTransferException] if the transfer cannot be completed in full.
     */
    @Throws(UsbShortTransferException::class)
    fun transferInOrThrow(
        endpoint: BulkTransferEndpoint,
        buffer: ByteArray,
        offset: Int,
        expectedBytes: Int,
        timeoutMs: Int
    ): UsbTransferResult {
        val result = transferIn(endpoint, buffer, offset, expectedBytes, timeoutMs)
        if (!result.isComplete) {
            throw UsbShortTransferException(
                direction = UsbTransferDirection.IN,
                expectedBytes = expectedBytes,
                actualBytes = result.actualBytes,
                message = "USB Bulk IN transfer incomplete: expected $expectedBytes bytes, received ${result.actualBytes} bytes (${result.errorMessage ?: "code ${result.errorCode}"})"
            )
        }
        return result
    }

    /**
     * Executes a Bulk OUT transfer and throws [UsbShortTransferException] if the transfer cannot be completed in full.
     */
    @Throws(UsbShortTransferException::class)
    fun transferOutOrThrow(
        endpoint: BulkTransferEndpoint,
        buffer: ByteArray,
        offset: Int,
        expectedBytes: Int,
        timeoutMs: Int
    ): UsbTransferResult {
        val result = transferOut(endpoint, buffer, offset, expectedBytes, timeoutMs)
        if (!result.isComplete) {
            throw UsbShortTransferException(
                direction = UsbTransferDirection.OUT,
                expectedBytes = expectedBytes,
                actualBytes = result.actualBytes,
                message = "USB Bulk OUT transfer incomplete: expected $expectedBytes bytes, sent ${result.actualBytes} bytes (${result.errorMessage ?: "code ${result.errorCode}"})"
            )
        }
        return result
    }

    private fun executeTransfer(
        direction: UsbTransferDirection,
        endpoint: BulkTransferEndpoint,
        buffer: ByteArray,
        offset: Int,
        expectedBytes: Int,
        timeoutMs: Int
    ): UsbTransferResult {
        require(expectedBytes >= 0) { "expectedBytes must be non-negative: $expectedBytes" }
        if (expectedBytes == 0) {
            return UsbTransferResult(
                direction = direction,
                expectedBytes = 0,
                actualBytes = 0,
                isComplete = true,
                isPartial = false,
                isZeroByte = true,
                isFailed = false,
                chunksTransferred = 0
            )
        }

        if (offset < 0 || (offset.toLong() + expectedBytes.toLong()) > buffer.size.toLong()) {
            throw IndexOutOfBoundsException(
                "Buffer bounds violation: buffer size ${buffer.size}, offset $offset, expectedBytes $expectedBytes"
            )
        }

        var actualTransferred = 0
        var chunks = 0
        var consecutiveZeroCount = 0
        var errorCode = 0
        var errorMessage: String? = null
        val startTime = System.currentTimeMillis()

        while (actualTransferred < expectedBytes) {
            val remaining = expectedBytes - actualTransferred
            val elapsed = (System.currentTimeMillis() - startTime).toInt()
            if (elapsed >= timeoutMs) {
                errorMessage = "USB ${direction.name} transfer timed out after ${elapsed}ms (transferred $actualTransferred/$expectedBytes bytes)"
                break
            }

            val remainingTimeout = (timeoutMs - elapsed).coerceAtLeast(1)
            val res = try {
                endpoint.transfer(buffer, offset + actualTransferred, remaining, remainingTimeout)
            } catch (e: Exception) {
                errorCode = -1
                errorMessage = "USB ${direction.name} transfer threw exception: ${e.message}"
                break
            }

            if (res < 0) {
                errorCode = res
                errorMessage = "USB ${direction.name} transfer failed with error code $res (transferred $actualTransferred/$expectedBytes bytes)"
                break
            }

            if (res == 0) {
                consecutiveZeroCount++
                if (consecutiveZeroCount >= maxZeroByteRetries) {
                    errorMessage = "USB ${direction.name} transfer stalled: received $consecutiveZeroCount consecutive 0-byte transfers (transferred $actualTransferred/$expectedBytes bytes)"
                    break
                }
                if (zeroByteSleepMs > 0) {
                    try {
                        Thread.sleep(zeroByteSleepMs)
                    } catch (ie: InterruptedException) {
                        Thread.currentThread().interrupt()
                        errorMessage = "USB ${direction.name} transfer interrupted during zero-byte backoff"
                        break
                    }
                }
            } else {
                consecutiveZeroCount = 0
                actualTransferred += res
                chunks++
            }
        }

        val isComplete = (actualTransferred == expectedBytes)
        val isPartial = (actualTransferred in 1 until expectedBytes)
        val isZeroByte = (actualTransferred == 0)
        val isFailed = !isComplete

        return UsbTransferResult(
            direction = direction,
            expectedBytes = expectedBytes,
            actualBytes = actualTransferred,
            isComplete = isComplete,
            isPartial = isPartial,
            isZeroByte = isZeroByte,
            isFailed = isFailed,
            errorCode = errorCode,
            chunksTransferred = chunks,
            errorMessage = errorMessage
        )
    }
}
