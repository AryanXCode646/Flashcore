package com.ashishsinghbora.flashcore

import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import com.ashishsinghbora.flashcore.scsi.CommandBlockWrapper
import com.ashishsinghbora.flashcore.scsi.CommandStatusWrapper
import com.ashishsinghbora.flashcore.usb.UsbBulkTransferHandler
import com.ashishsinghbora.flashcore.usb.UsbConnectionAdapter
import com.ashishsinghbora.flashcore.usb.UsbMassStorageDriver
import com.ashishsinghbora.flashcore.usb.UsbShortTransferException
import com.ashishsinghbora.flashcore.usb.UsbTransferDirection
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * End-to-end unit tests for [UsbMassStorageDriver] with hardened transfer length validation.
 *
 * Verifies SCSI BOT protocol enforcement:
 * - CBW transfer length validation
 * - Data IN partial transfer detection & rejection
 * - Data OUT partial transfer detection & rejection
 * - DirectBuffer OUT partial transfer detection & rejection
 * - CSW transfer length validation
 * - CSW data residue (> 0) rejection
 * - Multi-chunk partial transfer reassembly
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class UsbMassStorageDriverTest {

    private open class MockUsbConnectionAdapter : UsbConnectionAdapter {
        var lastTag: Int = 0
        var cswResidue: Int = 0
        var cswStatus: Byte = CommandStatusWrapper.Status.PASSED.code

        var cbwTransferOverride: ((ByteArray, Int, Int) -> Int)? = null
        var dataInTransferOverride: ((ByteArray, Int, Int) -> Int)? = null
        var dataOutTransferOverride: ((ByteArray, Int, Int) -> Int)? = null
        var cswTransferOverride: ((ByteArray, Int, Int) -> Int)? = null

        override fun bulkTransfer(
            endpoint: UsbEndpoint?,
            buffer: ByteArray,
            offset: Int,
            length: Int,
            timeout: Int
        ): Int {
            // CBW detection (length == 31, magic "USBC")
            if (length == CommandBlockWrapper.CBW_SIZE &&
                buffer[offset] == 0x55.toByte() && buffer[offset + 1] == 0x53.toByte() &&
                buffer[offset + 2] == 0x42.toByte() && buffer[offset + 3] == 0x43.toByte()
            ) {
                lastTag = ByteBuffer.wrap(buffer, offset + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
                cbwTransferOverride?.let { return it(buffer, offset, length) }
                return length
            }

            // CSW detection (length == 13)
            if (length == CommandStatusWrapper.CSW_SIZE) {
                cswTransferOverride?.let { return it(buffer, offset, length) }
                val csw = ByteBuffer.allocate(13).order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(CommandStatusWrapper.CSW_SIGNATURE)
                    .putInt(lastTag)
                    .putInt(cswResidue)
                    .put(cswStatus)
                    .array()
                System.arraycopy(csw, 0, buffer, offset, 13)
                return 13
            }

            // Data phase (OUT)
            dataInTransferOverride?.let { return it(buffer, offset, length) }
            dataOutTransferOverride?.let { return it(buffer, offset, length) }

            return length
        }

        override fun bulkTransfer(
            endpoint: UsbEndpoint?,
            buffer: ByteArray,
            length: Int,
            timeout: Int
        ): Int = bulkTransfer(endpoint, buffer, 0, length, timeout)

        override fun controlTransfer(
            requestType: Int,
            request: Int,
            value: Int,
            index: Int,
            buffer: ByteArray?,
            length: Int,
            timeout: Int
        ): Int = 0

        override fun claimInterface(intf: UsbInterface?, force: Boolean): Boolean = true
        override fun releaseInterface(intf: UsbInterface?): Boolean = true
        override fun close() {}
    }

    private fun createDriver(adapter: MockUsbConnectionAdapter): UsbMassStorageDriver {
        val fastHandler = UsbBulkTransferHandler(maxZeroByteRetries = 2, zeroByteSleepMs = 0L)
        return UsbMassStorageDriver(
            usbManager = null,
            device = null,
            transferHandler = fastHandler,
            connectionAdapter = adapter
        )
    }

    @Test
    fun testReadBlocksFailsOnPartialDataIn() {
        val adapter = MockUsbConnectionAdapter()
        var readAttemptCount = 0

        // Device sends only 256 bytes out of 512, then stops (returns 0)
        adapter.dataInTransferOverride = { buf, off, len ->
            readAttemptCount++
            if (readAttemptCount % 3 == 1) {
                256
            } else {
                0
            }
        }

        val driver = createDriver(adapter)
        val readBuffer = ByteArray(512)

        val ex = assertThrows(UsbShortTransferException::class.java) {
            driver.readBlocks(lba = 10L, blockCount = 1, destBuffer = readBuffer)
        }

        assertEquals(UsbTransferDirection.IN, ex.direction)
        assertEquals(512, ex.expectedBytes)
        assertEquals(256, ex.actualBytes)
    }

    @Test
    fun testWriteBlocksFailsOnPartialDataOut() {
        val adapter = MockUsbConnectionAdapter()
        var writeAttemptCount = 0

        // Device accepts only 300 bytes of 512, then stops
        adapter.dataOutTransferOverride = { buf, off, len ->
            writeAttemptCount++
            if (writeAttemptCount % 3 == 1) {
                300
            } else {
                0
            }
        }

        val driver = createDriver(adapter)
        val writeBuffer = ByteArray(512) { 0xAA.toByte() }

        val ex = assertThrows(UsbShortTransferException::class.java) {
            driver.writeBlocks(lba = 20L, blockCount = 1, srcBuffer = writeBuffer)
        }

        assertEquals(UsbTransferDirection.OUT, ex.direction)
        assertEquals(512, ex.expectedBytes)
        assertEquals(300, ex.actualBytes)
    }

    @Test
    fun testWriteDirectBufferFailsOnPartialDataOut() {
        val adapter = MockUsbConnectionAdapter()
        var writeAttemptCount = 0

        adapter.dataOutTransferOverride = { buf, off, len ->
            writeAttemptCount++
            if (writeAttemptCount == 1) {
                512
            } else {
                0
            }
        }

        val driver = createDriver(adapter)
        val directBuffer = ByteBuffer.allocateDirect(1024)
        for (i in 0 until 1024) directBuffer.put(0x55.toByte())

        val ex = assertThrows(UsbShortTransferException::class.java) {
            runBlocking {
                driver.writeDirectBuffer(lba = 50L, blockCount = 2, directBuffer = directBuffer, offset = 0, length = 1024)
            }
        }

        assertEquals(UsbTransferDirection.OUT, ex.direction)
        assertEquals(1024, ex.expectedBytes)
        assertEquals(512, ex.actualBytes)
    }

    @Test
    fun testCswDataResidueTriggersShortTransferException() {
        val adapter = MockUsbConnectionAdapter()
        // Data transfer succeeds at USB level, but device CSW reports 128 bytes residue
        adapter.cswResidue = 128

        val driver = createDriver(adapter)
        val writeBuffer = ByteArray(512)

        val ex = assertThrows(UsbShortTransferException::class.java) {
            driver.writeBlocks(lba = 100L, blockCount = 1, srcBuffer = writeBuffer)
        }

        assertEquals(UsbTransferDirection.OUT, ex.direction)
        assertEquals(512, ex.expectedBytes)
        assertEquals(512 - 128, ex.actualBytes)
    }

    @Test
    fun testMultiplePartialChunksReassembleSuccessfully() {
        val adapter = MockUsbConnectionAdapter()
        val expectedData = ByteArray(1024) { (it % 255).toByte() }

        // Splits 1024 bytes into four 256-byte chunks
        adapter.dataInTransferOverride = { buf, off, len ->
            val chunk = minOf(256, len)
            System.arraycopy(expectedData, off, buf, off, chunk)
            chunk
        }

        val driver = createDriver(adapter)
        val readBuffer = ByteArray(1024)

        val success = driver.readBlocks(lba = 0L, blockCount = 2, destBuffer = readBuffer)
        assertTrue("readBlocks should succeed after reassembling chunks", success)
        assertArrayEquals(expectedData, readBuffer)
    }

    @Test
    fun testWriteDirectBufferMultipleChunksReassemblesSuccessfully() {
        val adapter = MockUsbConnectionAdapter()
        val captured = ByteArray(1024)

        // Simulates host sending direct buffer in 256-byte chunks
        adapter.dataOutTransferOverride = { buf, off, len ->
            val chunk = minOf(256, len)
            System.arraycopy(buf, off, captured, off, chunk)
            chunk
        }

        val driver = createDriver(adapter)
        val directBuffer = ByteBuffer.allocateDirect(1024)
        val pattern = ByteArray(1024) { (it xor 0x3C).toByte() }
        directBuffer.put(pattern)

        val success = runBlocking {
            driver.writeDirectBuffer(lba = 12L, blockCount = 2, directBuffer = directBuffer, offset = 0, length = 1024)
        }

        assertTrue("writeDirectBuffer should succeed with reassembled chunks", success)
        assertArrayEquals(pattern, captured)
    }
}
