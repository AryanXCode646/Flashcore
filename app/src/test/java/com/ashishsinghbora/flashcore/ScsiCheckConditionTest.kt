package com.ashishsinghbora.flashcore

import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import com.ashishsinghbora.flashcore.scsi.CommandBlockWrapper
import com.ashishsinghbora.flashcore.scsi.CommandStatusWrapper
import com.ashishsinghbora.flashcore.scsi.ScsiCdbBuilder
import com.ashishsinghbora.flashcore.scsi.ScsiCheckConditionException
import com.ashishsinghbora.flashcore.usb.UsbBulkTransferHandler
import com.ashishsinghbora.flashcore.usb.UsbConnectionAdapter
import com.ashishsinghbora.flashcore.usb.UsbMassStorageDriver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
 * Unit tests verifying SCSI CHECK CONDITION and REQUEST SENSE handling per GitHub Issue #7.
 *
 * Test cases:
 * A. Explicit CHECK CONDITION detection
 * B. Automatic REQUEST SENSE invocation (opcode 0x03, allocation length 18, same LUN)
 * C. Structured sense data attachment and typed exceptions (ScsiCheckConditionException)
 * D. Throwing variants for readBlocksOrThrow, writeBlocksOrThrow, writeDirectBufferOrThrow
 * E. REQUEST SENSE failure (transport error) handling without crashing
 * F. REQUEST SENSE returning CHECK CONDITION without nested recovery
 * G. Infinite-loop regression prevention (every command returns CHECK CONDITION -> bounded to 2 commands)
 * H. Successful commands unaffected (0 REQUEST SENSE issued)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ScsiCheckConditionTest {

    private class BotSimulatingAdapter : UsbConnectionAdapter {
        var lastTag: Int = 0
        var commandsExecuted = mutableListOf<Byte>()
        var requestSenseCallCount = 0
        var totalCbwCount = 0

        // Configuration for responses
        var failNextCommandWithCheckCondition: Boolean = false
        var failAllWithCheckCondition: Boolean = false
        var failRequestSenseWithTransportError: Boolean = false
        var failRequestSenseWithCheckCondition: Boolean = false

        var injectedSenseData: ByteArray? = null

        override fun bulkTransfer(
            endpoint: UsbEndpoint?,
            buffer: ByteArray,
            offset: Int,
            length: Int,
            timeout: Int
        ): Int {
            // CBW Phase
            if (length == CommandBlockWrapper.CBW_SIZE &&
                buffer[offset] == 0x55.toByte() && buffer[offset + 1] == 0x53.toByte() &&
                buffer[offset + 2] == 0x42.toByte() && buffer[offset + 3] == 0x43.toByte()
            ) {
                totalCbwCount++
                lastTag = ByteBuffer.wrap(buffer, offset + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
                val opcode = buffer[offset + 15]
                commandsExecuted.add(opcode)
                if (opcode == ScsiCdbBuilder.OP_REQUEST_SENSE) {
                    requestSenseCallCount++
                }
                return length
            }

            // CSW Phase
            if (length == CommandStatusWrapper.CSW_SIZE) {
                val currentOpcode = commandsExecuted.lastOrNull() ?: 0
                val status: Byte = when {
                    failAllWithCheckCondition -> CommandStatusWrapper.Status.FAILED.code
                    currentOpcode == ScsiCdbBuilder.OP_REQUEST_SENSE && failRequestSenseWithCheckCondition -> {
                        CommandStatusWrapper.Status.FAILED.code
                    }
                    currentOpcode != ScsiCdbBuilder.OP_REQUEST_SENSE && failNextCommandWithCheckCondition -> {
                        failNextCommandWithCheckCondition = false // trigger once
                        CommandStatusWrapper.Status.FAILED.code
                    }
                    else -> CommandStatusWrapper.Status.PASSED.code
                }

                val csw = ByteBuffer.allocate(13).order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(CommandStatusWrapper.CSW_SIGNATURE)
                    .putInt(lastTag)
                    .putInt(0) // Data residue = 0
                    .put(status)
                    .array()
                System.arraycopy(csw, 0, buffer, offset, 13)
                return 13
            }

            // Data Phase
            val currentOpcode = commandsExecuted.lastOrNull() ?: 0
            if (currentOpcode == ScsiCdbBuilder.OP_REQUEST_SENSE) {
                if (failRequestSenseWithTransportError) {
                    return -1 // USB transport error
                }
                val sense = injectedSenseData ?: ByteArray(length)
                val toCopy = minOf(sense.size, length)
                System.arraycopy(sense, 0, buffer, offset, toCopy)
                return toCopy
            }

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

    private fun createDriver(adapter: BotSimulatingAdapter): UsbMassStorageDriver {
        val fastHandler = UsbBulkTransferHandler(maxZeroByteRetries = 2, zeroByteSleepMs = 0L)
        return UsbMassStorageDriver(
            usbManager = null,
            device = null,
            transferHandler = fastHandler,
            connectionAdapter = adapter
        )
    }

    private fun buildStandardSenseData(senseKey: Int, asc: Int, ascq: Int, lba: Long = 0L): ByteArray {
        val buf = ByteArray(18)
        buf[0] = 0xF0.toByte() // Fixed format, valid bit set
        buf[2] = (senseKey and 0x0F).toByte()
        buf[3] = ((lba shr 24) and 0xFF).toByte()
        buf[4] = ((lba shr 16) and 0xFF).toByte()
        buf[5] = ((lba shr 8) and 0xFF).toByte()
        buf[6] = (lba and 0xFF).toByte()
        buf[7] = 10.toByte() // Additional length
        buf[12] = (asc and 0xFF).toByte()
        buf[13] = (ascq and 0xFF).toByte()
        return buf
    }

    // ========================================================================
    // 1. CHECK CONDITION Detection & Auto REQUEST SENSE
    // ========================================================================

    @Test
    fun testCheckConditionDetectedAndAutoRequestSenseIssued() {
        val adapter = BotSimulatingAdapter()
        adapter.failNextCommandWithCheckCondition = true
        // Injected sense data: MEDIUM ERROR (0x03), Unrecovered read error (0x11, 0x00)
        adapter.injectedSenseData = buildStandardSenseData(0x03, 0x11, 0x00, lba = 1024L)

        val driver = createDriver(adapter)
        val readBuffer = ByteArray(512)

        // Read command fails with CHECK CONDITION
        val success = driver.readBlocks(lba = 1024L, blockCount = 1, destBuffer = readBuffer)
        assertFalse("Command should fail on CHECK CONDITION", success)

        // Verify exactly 1 REQUEST_SENSE command was issued
        assertEquals(1, adapter.requestSenseCallCount)
        assertEquals(listOf(ScsiCdbBuilder.OP_READ_10, ScsiCdbBuilder.OP_REQUEST_SENSE), adapter.commandsExecuted)

        // Verify structured sense data is attached to lastCommandResult and lastSenseData
        val lastResult = driver.lastCommandResult
        assertNotNull(lastResult)
        assertTrue(lastResult!!.isCheckCondition)
        assertFalse(lastResult.isSuccess)
        assertFalse(lastResult.requestSenseFailed)

        val sense = driver.lastSenseData
        assertNotNull(sense)
        assertEquals(0x03, sense!!.senseKey)
        assertEquals("MEDIUM ERROR", sense.senseKeyDescription)
        assertEquals(0x11, sense.additionalSenseCode)
        assertEquals("Unrecovered read error", sense.ascDescription)
        assertEquals(1024L, sense.information)
        assertTrue(sense.isValid)
    }

    // ========================================================================
    // 2. Throwing Variants (ScsiCheckConditionException)
    // ========================================================================

    @Test
    fun testWriteBlocksOrThrowThrowsScsiCheckConditionException() {
        val adapter = BotSimulatingAdapter()
        adapter.failNextCommandWithCheckCondition = true
        // DATA PROTECT (0x07), Write protected (0x27, 0x00)
        adapter.injectedSenseData = buildStandardSenseData(0x07, 0x27, 0x00)

        val driver = createDriver(adapter)
        val writeBuffer = ByteArray(512)

        val ex = assertThrows(ScsiCheckConditionException::class.java) {
            driver.writeBlocksOrThrow(lba = 200L, blockCount = 1, srcBuffer = writeBuffer)
        }

        assertEquals(ScsiCdbBuilder.OP_WRITE_10, ex.opcode)
        assertEquals("WRITE_10", ex.commandName)
        assertTrue(ex.csw.isCheckCondition)
        assertNotNull(ex.senseData)
        assertEquals(0x07, ex.senseData!!.senseKey)
        assertEquals("DATA PROTECT", ex.senseData!!.senseKeyDescription)
        assertEquals("Write protected", ex.senseData!!.ascDescription)
        assertTrue(ex.message!!.contains("WRITE_10"))
        assertTrue(ex.message!!.contains("DATA PROTECT"))
        assertTrue(ex.message!!.contains("Write protected"))
    }

    @Test
    fun testReadBlocksOrThrowThrowsScsiCheckConditionException() {
        val adapter = BotSimulatingAdapter()
        adapter.failNextCommandWithCheckCondition = true
        // ILLEGAL REQUEST (0x05), LBA out of range (0x21, 0x00)
        adapter.injectedSenseData = buildStandardSenseData(0x05, 0x21, 0x00)

        val driver = createDriver(adapter)
        val readBuffer = ByteArray(512)

        val ex = assertThrows(ScsiCheckConditionException::class.java) {
            driver.readBlocksOrThrow(lba = 99999999L, blockCount = 1, destBuffer = readBuffer)
        }

        assertEquals(ScsiCdbBuilder.OP_READ_10, ex.opcode)
        assertEquals("READ_10", ex.commandName)
        assertEquals(0x05, ex.senseData!!.senseKey)
        assertEquals("Logical block address out of range", ex.senseData!!.ascDescription)
    }

    @Test
    fun testWriteDirectBufferOrThrowThrowsScsiCheckConditionException() {
        val adapter = BotSimulatingAdapter()
        adapter.failNextCommandWithCheckCondition = true
        // HARDWARE ERROR (0x04), Internal target failure (0x44, 0x00)
        adapter.injectedSenseData = buildStandardSenseData(0x04, 0x44, 0x00)

        val driver = createDriver(adapter)
        val directBuffer = ByteBuffer.allocateDirect(1024)

        val ex = assertThrows(ScsiCheckConditionException::class.java) {
            driver.writeDirectBufferOrThrow(lba = 50L, blockCount = 2, directBuffer = directBuffer, offset = 0, length = 1024)
        }

        assertEquals(ScsiCdbBuilder.OP_WRITE_10, ex.opcode)
        assertEquals(0x04, ex.senseData!!.senseKey)
        assertEquals("HARDWARE ERROR", ex.senseData!!.senseKeyDescription)
        assertEquals("Internal target failure", ex.senseData!!.ascDescription)
    }

    // ========================================================================
    // 3. Inquiry & ReadCapacity with CHECK CONDITION
    // ========================================================================

    @Test
    fun testInquiryThrowsScsiCheckConditionExceptionOnCheckCondition() {
        val adapter = BotSimulatingAdapter()
        adapter.failNextCommandWithCheckCondition = true
        // NOT READY (0x02), Medium not present (0x3A, 0x00)
        adapter.injectedSenseData = buildStandardSenseData(0x02, 0x3A, 0x00)

        val driver = createDriver(adapter)

        val ex = assertThrows(ScsiCheckConditionException::class.java) {
            driver.inquiry()
        }

        assertEquals(ScsiCdbBuilder.OP_INQUIRY, ex.opcode)
        assertEquals("INQUIRY", ex.commandName)
        assertEquals(0x02, ex.senseData!!.senseKey)
        assertEquals("NOT READY", ex.senseData!!.senseKeyDescription)
        assertEquals("Medium not present", ex.senseData!!.ascDescription)
    }

    @Test
    fun testReadCapacityThrowsScsiCheckConditionExceptionOnCheckCondition() {
        val adapter = BotSimulatingAdapter()
        adapter.failNextCommandWithCheckCondition = true
        // UNIT ATTENTION (0x06), Power on or bus reset (0x29, 0x00)
        adapter.injectedSenseData = buildStandardSenseData(0x06, 0x29, 0x00)

        val driver = createDriver(adapter)

        val ex = assertThrows(ScsiCheckConditionException::class.java) {
            driver.readCapacity()
        }

        assertEquals(ScsiCdbBuilder.OP_READ_CAPACITY_10, ex.opcode)
        assertEquals("READ_CAPACITY_10", ex.commandName)
        assertEquals(0x06, ex.senseData!!.senseKey)
        assertEquals("UNIT ATTENTION", ex.senseData!!.senseKeyDescription)
        assertEquals("Power on, reset, or bus device reset occurred", ex.senseData!!.ascDescription)
    }

    // ========================================================================
    // 4. REQUEST SENSE Failures & Non-Recursion Protection
    // ========================================================================

    @Test
    fun testRequestSenseTransportFailurePreservesOriginalFailure() {
        val adapter = BotSimulatingAdapter()
        adapter.failNextCommandWithCheckCondition = true
        adapter.failRequestSenseWithTransportError = true // simulate USB endpoint failure during sense retrieval

        val driver = createDriver(adapter)
        val readBuffer = ByteArray(512)

        // Should not throw or crash; original failure preserved
        val success = driver.readBlocks(lba = 0L, blockCount = 1, destBuffer = readBuffer)
        assertFalse(success)

        val result = driver.lastCommandResult
        assertNotNull(result)
        assertTrue(result!!.isCheckCondition)
        assertTrue(result.requestSenseFailed)
        assertNotNull(result.requestSenseError)
        assertNull(result.senseData)
        assertEquals(1, adapter.requestSenseCallCount)
    }

    @Test
    fun testRequestSenseReturningCheckConditionDoesNotRecurse() {
        val adapter = BotSimulatingAdapter()
        adapter.failNextCommandWithCheckCondition = true
        adapter.failRequestSenseWithCheckCondition = true // REQUEST SENSE itself returns CHECK CONDITION

        val driver = createDriver(adapter)
        val readBuffer = ByteArray(512)

        val success = driver.readBlocks(lba = 10L, blockCount = 1, destBuffer = readBuffer)
        assertFalse(success)

        // Must issue EXACTLY 1 REQUEST SENSE, never a recursive second one
        assertEquals(1, adapter.requestSenseCallCount)
        assertEquals(2, adapter.totalCbwCount) // 1 READ_10 + 1 REQUEST_SENSE
        val result = driver.lastCommandResult
        assertNotNull(result)
        assertTrue(result!!.isCheckCondition)
        assertTrue(result.requestSenseFailed)
    }

    // ========================================================================
    // 5. Infinite-Loop Regression Test
    // ========================================================================

    @Test
    fun testInfiniteLoopRegressionWhenEveryCommandReturnsCheckCondition() {
        val adapter = BotSimulatingAdapter()
        adapter.failAllWithCheckCondition = true // ALL commands return CHECK CONDITION

        val driver = createDriver(adapter)
        val cdb = ScsiCdbBuilder.testUnitReady()
        val cbw = CommandBlockWrapper.create(0, CommandBlockWrapper.Direction.NONE, cdb)

        // Execute command
        val result = driver.executeScsiCommand(cbw, autoRequestSense = true)

        // Execution MUST terminate cleanly
        assertTrue(result.isCheckCondition)
        assertTrue(result.requestSenseFailed)
        // Total commands MUST be bounded to exactly 2 (1 original command + 1 non-recursive REQUEST SENSE)
        assertEquals(2, adapter.totalCbwCount)
        assertEquals(1, adapter.requestSenseCallCount)
    }

    // ========================================================================
    // 6. Successful Commands Unaffected
    // ========================================================================

    @Test
    fun testSuccessfulCommandsDoNotIssueRequestSense() {
        val adapter = BotSimulatingAdapter()
        val driver = createDriver(adapter)

        val success = driver.testUnitReady()
        assertTrue(success)

        // Zero REQUEST_SENSE commands issued for successful command
        assertEquals(0, adapter.requestSenseCallCount)
        assertEquals(1, adapter.totalCbwCount)
        assertNull(driver.lastSenseData)
        assertNotNull(driver.lastCommandResult)
        assertTrue(driver.lastCommandResult!!.isSuccess)
    }
}
