package com.ashishsinghbora.flashcore

import com.ashishsinghbora.flashcore.usb.BulkTransferEndpoint
import com.ashishsinghbora.flashcore.usb.UsbBulkTransferHandler
import com.ashishsinghbora.flashcore.usb.UsbShortTransferException
import com.ashishsinghbora.flashcore.usb.UsbTransferDirection
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Unit test suite verifying USB transfer length validation per GitHub Issue #6.
 *
 * Acceptance Criteria & Test Cases covered:
 * 1. Full transfer (IN and OUT)
 * 2. Partial transfer (IN and OUT, short transfer exception thrown)
 * 3. Zero-byte transfer (IN and OUT, consecutive retry thresholding)
 * 4. Failed transfer (Negative return code & exception propagation)
 * 5. Multiple partial transfers (Multi-chunk reassembly up to expected length)
 * 6. Intermittent zero-byte transfer with continuation
 * 7. Boundary condition validation (offset, zero-length, negative bounds)
 */
class UsbBulkTransferHandlerTest {

    private val handler = UsbBulkTransferHandler(
        maxZeroByteRetries = 3,
        zeroByteSleepMs = 0L // fast testing without sleeping
    )

    // ========================================================================
    // 1. Full Transfer Tests
    // ========================================================================

    @Test
    fun testFullTransferIn() {
        val expectedSize = 1024
        val sourceData = ByteArray(expectedSize) { (it % 256).toByte() }
        val destBuffer = ByteArray(expectedSize)

        val endpoint = BulkTransferEndpoint { buffer, offset, length, _ ->
            System.arraycopy(sourceData, offset, buffer, offset, length)
            length
        }

        val result = handler.transferIn(endpoint, destBuffer, 0, expectedSize, 1000)

        assertEquals(UsbTransferDirection.IN, result.direction)
        assertEquals(expectedSize, result.expectedBytes)
        assertEquals(expectedSize, result.actualBytes)
        assertTrue(result.isComplete)
        assertFalse(result.isPartial)
        assertFalse(result.isZeroByte)
        assertFalse(result.isFailed)
        assertEquals(1, result.chunksTransferred)
        assertArrayEquals(sourceData, destBuffer)
    }

    @Test
    fun testFullTransferOut() {
        val expectedSize = 512
        val srcBuffer = ByteArray(expectedSize) { (it * 3).toByte() }
        val capturedData = ByteArray(expectedSize)

        val endpoint = BulkTransferEndpoint { buffer, offset, length, _ ->
            System.arraycopy(buffer, offset, capturedData, offset, length)
            length
        }

        val result = handler.transferOut(endpoint, srcBuffer, 0, expectedSize, 1000)

        assertEquals(UsbTransferDirection.OUT, result.direction)
        assertEquals(expectedSize, result.expectedBytes)
        assertEquals(expectedSize, result.actualBytes)
        assertTrue(result.isComplete)
        assertFalse(result.isPartial)
        assertFalse(result.isZeroByte)
        assertFalse(result.isFailed)
        assertEquals(1, result.chunksTransferred)
        assertArrayEquals(srcBuffer, capturedData)
    }

    // ========================================================================
    // 2. Partial Transfer Tests
    // ========================================================================

    @Test
    fun testPartialTransferInDetectsIncompleteData() {
        val expectedSize = 1000
        val destBuffer = ByteArray(expectedSize)
        var callCount = 0

        // Transfers 400 bytes on first chunk, then returns 0 thereafter (device stalls)
        val endpoint = BulkTransferEndpoint { _, _, _, _ ->
            callCount++
            if (callCount == 1) 400 else 0
        }

        val result = handler.transferIn(endpoint, destBuffer, 0, expectedSize, 1000)

        assertEquals(UsbTransferDirection.IN, result.direction)
        assertEquals(expectedSize, result.expectedBytes)
        assertEquals(400, result.actualBytes)
        assertFalse(result.isComplete)
        assertTrue(result.isPartial)
        assertFalse(result.isZeroByte)
        assertTrue(result.isFailed)
        assertEquals(1, result.chunksTransferred)
        assertNotNull(result.errorMessage)
    }

    @Test
    fun testPartialTransferInOrThrowThrowsUsbShortTransferException() {
        val expectedSize = 4096
        val destBuffer = ByteArray(expectedSize)
        var callCount = 0

        val endpoint = BulkTransferEndpoint { _, _, _, _ ->
            callCount++
            if (callCount == 1) 2048 else 0
        }

        val ex = assertThrows(UsbShortTransferException::class.java) {
            handler.transferInOrThrow(endpoint, destBuffer, 0, expectedSize, 1000)
        }

        assertEquals(UsbTransferDirection.IN, ex.direction)
        assertEquals(expectedSize, ex.expectedBytes)
        assertEquals(2048, ex.actualBytes)
    }

    @Test
    fun testPartialTransferOutOrThrowThrowsUsbShortTransferException() {
        val expectedSize = 2048
        val srcBuffer = ByteArray(expectedSize)
        var callCount = 0

        val endpoint = BulkTransferEndpoint { _, _, _, _ ->
            callCount++
            if (callCount == 1) 512 else 0
        }

        val ex = assertThrows(UsbShortTransferException::class.java) {
            handler.transferOutOrThrow(endpoint, srcBuffer, 0, expectedSize, 1000)
        }

        assertEquals(UsbTransferDirection.OUT, ex.direction)
        assertEquals(expectedSize, ex.expectedBytes)
        assertEquals(512, ex.actualBytes)
    }

    // ========================================================================
    // 3. Zero-Byte Transfer Tests
    // ========================================================================

    @Test
    fun testZeroByteTransferInFailsSafely() {
        val expectedSize = 512
        val destBuffer = ByteArray(expectedSize)
        var callCount = 0

        val endpoint = BulkTransferEndpoint { _, _, _, _ ->
            callCount++
            0 // Always returns 0 bytes transferred
        }

        val result = handler.transferIn(endpoint, destBuffer, 0, expectedSize, 1000)

        assertEquals(UsbTransferDirection.IN, result.direction)
        assertEquals(expectedSize, result.expectedBytes)
        assertEquals(0, result.actualBytes)
        assertFalse(result.isComplete)
        assertFalse(result.isPartial)
        assertTrue(result.isZeroByte)
        assertTrue(result.isFailed)
        assertEquals(0, result.chunksTransferred)
        // Handled within retry threshold
        assertEquals(handler.maxZeroByteRetries, callCount)
    }

    @Test
    fun testZeroByteTransferOutFailsSafely() {
        val expectedSize = 512
        val srcBuffer = ByteArray(expectedSize)

        val endpoint = BulkTransferEndpoint { _, _, _, _ -> 0 }

        val result = handler.transferOut(endpoint, srcBuffer, 0, expectedSize, 1000)

        assertEquals(0, result.actualBytes)
        assertTrue(result.isZeroByte)
        assertTrue(result.isFailed)
    }

    @Test
    fun testZeroExpectedBytesCompletesImmediately() {
        val buffer = ByteArray(16)
        var endpointCalled = false

        val endpoint = BulkTransferEndpoint { _, _, _, _ ->
            endpointCalled = true
            0
        }

        val result = handler.transferIn(endpoint, buffer, 0, 0, 1000)

        assertTrue(result.isComplete)
        assertFalse(result.isPartial)
        assertTrue(result.isZeroByte)
        assertFalse(result.isFailed)
        assertEquals(0, result.actualBytes)
        assertEquals(0, result.chunksTransferred)
        assertFalse("Endpoint should not be invoked when expectedBytes == 0", endpointCalled)
    }

    // ========================================================================
    // 4. Failed Transfer Tests (Negative Return Code & Exceptions)
    // ========================================================================

    @Test
    fun testFailedTransferReturnsNegativeErrorCode() {
        val expectedSize = 512
        val buffer = ByteArray(expectedSize)

        val endpoint = BulkTransferEndpoint { _, _, _, _ -> -1 }

        val result = handler.transferIn(endpoint, buffer, 0, expectedSize, 1000)

        assertFalse(result.isComplete)
        assertTrue(result.isFailed)
        assertEquals(-1, result.errorCode)
        assertEquals(0, result.actualBytes)
        assertTrue(result.isZeroByte)
    }

    @Test
    fun testFailedTransferCatchesEndpointException() {
        val expectedSize = 512
        val buffer = ByteArray(expectedSize)

        val endpoint = BulkTransferEndpoint { _, _, _, _ ->
            throw IOException("USB connection reset by peer")
        }

        val result = handler.transferOut(endpoint, buffer, 0, expectedSize, 1000)

        assertFalse(result.isComplete)
        assertTrue(result.isFailed)
        assertEquals(-1, result.errorCode)
        assertEquals(0, result.actualBytes)
        assertTrue(result.errorMessage?.contains("USB connection reset by peer") == true)
    }

    // ========================================================================
    // 5. Multiple Partial Transfers (Continuation / Multi-Chunk Reassembly)
    // ========================================================================

    @Test
    fun testMultiplePartialTransfersReassemblyIn() {
        val totalExpected = 4096
        val chunkSize = 512
        val fullSource = ByteArray(totalExpected) { (it % 127).toByte() }
        val destBuffer = ByteArray(totalExpected)

        // Simulates USB endpoint that yields data in 512-byte fragments
        val endpoint = BulkTransferEndpoint { buffer, offset, length, _ ->
            val toTransfer = minOf(chunkSize, length)
            System.arraycopy(fullSource, offset, buffer, offset, toTransfer)
            toTransfer
        }

        val result = handler.transferIn(endpoint, destBuffer, 0, totalExpected, 2000)

        assertTrue(result.isComplete)
        assertFalse(result.isPartial)
        assertFalse(result.isFailed)
        assertEquals(totalExpected, result.actualBytes)
        assertEquals(8, result.chunksTransferred) // 4096 / 512 = 8 chunks
        assertArrayEquals(fullSource, destBuffer)
    }

    @Test
    fun testMultiplePartialTransfersReassemblyOut() {
        val totalExpected = 2048
        val chunkSize = 256
        val srcBuffer = ByteArray(totalExpected) { (it xor 0x5A).toByte() }
        val capturedBuffer = ByteArray(totalExpected)

        val endpoint = BulkTransferEndpoint { buffer, offset, length, _ ->
            val toTransfer = minOf(chunkSize, length)
            System.arraycopy(buffer, offset, capturedBuffer, offset, toTransfer)
            toTransfer
        }

        val result = handler.transferOut(endpoint, srcBuffer, 0, totalExpected, 2000)

        assertTrue(result.isComplete)
        assertFalse(result.isPartial)
        assertFalse(result.isFailed)
        assertEquals(totalExpected, result.actualBytes)
        assertEquals(8, result.chunksTransferred) // 2048 / 256 = 8 chunks
        assertArrayEquals(srcBuffer, capturedBuffer)
    }

    @Test
    fun testMultiplePartialTransfersFollowedByError() {
        val totalExpected = 4096
        val destBuffer = ByteArray(totalExpected)
        var callCount = 0

        // Delivers 1024 bytes on call 1, 1024 on call 2, then fails with -1 on call 3
        val endpoint = BulkTransferEndpoint { _, _, _, _ ->
            callCount++
            when (callCount) {
                1 -> 1024
                2 -> 1024
                else -> -1
            }
        }

        val result = handler.transferIn(endpoint, destBuffer, 0, totalExpected, 2000)

        assertFalse(result.isComplete)
        assertTrue(result.isPartial)
        assertTrue(result.isFailed)
        assertEquals(2048, result.actualBytes)
        assertEquals(2, result.chunksTransferred)
        assertEquals(-1, result.errorCode)
    }

    // ========================================================================
    // 6. Intermittent Zero-Byte Responses with Recovery
    // ========================================================================

    @Test
    fun testIntermittentZeroBytesWithContinuationSuccess() {
        val totalExpected = 1024
        val destBuffer = ByteArray(totalExpected)
        var callCount = 0

        // Call 1: 512 bytes
        // Call 2: 0 bytes (intermittent USB pause)
        // Call 3: 512 bytes (transfer completes)
        val endpoint = BulkTransferEndpoint { _, _, _, _ ->
            callCount++
            when (callCount) {
                1 -> 512
                2 -> 0
                3 -> 512
                else -> 0
            }
        }

        val result = handler.transferIn(endpoint, destBuffer, 0, totalExpected, 2000)

        assertTrue(result.isComplete)
        assertFalse(result.isPartial)
        assertFalse(result.isFailed)
        assertEquals(totalExpected, result.actualBytes)
        assertEquals(2, result.chunksTransferred)
    }

    // ========================================================================
    // 7. Boundary and Buffer Validation
    // ========================================================================

    @Test
    fun testOffsetPlacementIntegrity() {
        val totalBufferSize = 1000
        val offset = 200
        val transferLength = 300
        val destBuffer = ByteArray(totalBufferSize) { 0xFF.toByte() }
        val testData = ByteArray(transferLength) { (it + 1).toByte() }

        val endpoint = BulkTransferEndpoint { buffer, off, len, _ ->
            System.arraycopy(testData, off - offset, buffer, off, len)
            len
        }

        val result = handler.transferIn(endpoint, destBuffer, offset, transferLength, 1000)

        assertTrue(result.isComplete)
        assertEquals(transferLength, result.actualBytes)

        // Verify pre-offset bytes untouched
        for (i in 0 until offset) {
            assertEquals(0xFF.toByte(), destBuffer[i])
        }
        // Verify transferred range
        val transferredSlice = destBuffer.copyOfRange(offset, offset + transferLength)
        assertArrayEquals(testData, transferredSlice)
        // Verify post-transfer bytes untouched
        for (i in (offset + transferLength) until totalBufferSize) {
            assertEquals(0xFF.toByte(), destBuffer[i])
        }
    }

    @Test
    fun testBufferOverflowThrowsIndexOutOfBounds() {
        val buffer = ByteArray(100)
        val endpoint = BulkTransferEndpoint { _, _, len, _ -> len }

        // offset + length > buffer.size
        assertThrows(IndexOutOfBoundsException::class.java) {
            handler.transferIn(endpoint, buffer, 50, 60, 1000)
        }

        // negative offset
        assertThrows(IndexOutOfBoundsException::class.java) {
            handler.transferIn(endpoint, buffer, -1, 10, 1000)
        }
    }

    @Test
    fun testNegativeExpectedBytesThrowsIllegalArgument() {
        val buffer = ByteArray(100)
        val endpoint = BulkTransferEndpoint { _, _, len, _ -> len }

        assertThrows(IllegalArgumentException::class.java) {
            handler.transferIn(endpoint, buffer, 0, -10, 1000)
        }
    }
}
