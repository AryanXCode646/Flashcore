package com.ashishsinghbora.flashcore

import com.ashishsinghbora.flashcore.scsi.ScsiCdbBuilder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Comprehensive unit tests for SCSI Sense Data Parser according to SPC-4 / SBC-3.
 *
 * Verifies:
 * - Fixed-format sense data (0x70 current, 0x71 deferred)
 * - Descriptor-format sense data (0x72 current, 0x73 deferred)
 * - Valid bit and 32-bit / 64-bit Information field extraction
 * - Command-specific information extraction
 * - All standard Sense Keys and descriptions
 * - Known ASC/ASCQ human-readable mappings
 * - Unknown ASC/ASCQ exact numeric preservation without invention
 * - Graceful handling of empty, truncated, and malformed buffers without exceptions
 */
class ScsiSenseParserTest {

    // ========================================================================
    // 1. Fixed-Format Sense Data Tests (0x70 / 0x71)
    // ========================================================================

    @Test
    fun testParseFixedFormatStandardCurrentError() {
        val buffer = ByteArray(18)
        buffer[0] = 0xF0.toByte() // 0x70 (Current Fixed) with VALID bit (0x80) set
        buffer[2] = 0x03.toByte() // MEDIUM ERROR
        // Information field: LBA 0x00123456 (Big-Endian at offset 3..6)
        buffer[3] = 0x00
        buffer[4] = 0x12
        buffer[5] = 0x34
        buffer[6] = 0x56
        buffer[7] = 10.toByte() // Additional length = 10
        // Command specific info at offset 8..11
        buffer[8] = 0x00
        buffer[9] = 0x00
        buffer[10] = 0x04
        buffer[11] = 0x00
        buffer[12] = 0x11.toByte() // ASC: Unrecovered read error
        buffer[13] = 0x00.toByte() // ASCQ: 00

        val sense = ScsiCdbBuilder.parseRequestSense(buffer)

        assertEquals(0x70, sense.responseCode)
        assertFalse(sense.isDescriptorFormat)
        assertTrue(sense.isValid)
        assertEquals(0x03, sense.senseKey)
        assertEquals("MEDIUM ERROR", sense.senseKeyDescription)
        assertEquals(0x00123456L, sense.information)
        assertEquals(0x0400L, sense.commandSpecificInfo)
        assertEquals(10, sense.additionalSenseLength)
        assertEquals(0x11, sense.additionalSenseCode)
        assertEquals(0x00, sense.additionalSenseCodeQualifier)
        assertEquals("Unrecovered read error", sense.ascDescription)
        assertArrayEquals(buffer, sense.rawBytes)
        assertTrue(sense.formattedDiagnostic().contains("MEDIUM ERROR"))
        assertTrue(sense.formattedDiagnostic().contains("Unrecovered read error"))
    }

    @Test
    fun testParseFixedFormatDeferredError() {
        val buffer = ByteArray(18)
        buffer[0] = 0x71.toByte() // Deferred error, valid bit NOT set
        buffer[2] = 0x04.toByte() // HARDWARE ERROR
        buffer[12] = 0x44.toByte() // Internal target failure
        buffer[13] = 0x00.toByte()

        val sense = ScsiCdbBuilder.parseRequestSense(buffer)

        assertEquals(0x71, sense.responseCode)
        assertFalse(sense.isValid)
        assertEquals(0x04, sense.senseKey)
        assertEquals("HARDWARE ERROR", sense.senseKeyDescription)
        assertEquals(0L, sense.information)
        assertEquals(0x44, sense.additionalSenseCode)
        assertEquals("Internal target failure", sense.ascDescription)
    }

    // ========================================================================
    // 2. Descriptor-Format Sense Data Tests (0x72 / 0x73)
    // ========================================================================

    @Test
    fun testParseDescriptorFormatWithInformationDescriptor() {
        // Descriptor format: Byte 0 = 0x72, Byte 1 = Sense Key, Byte 2 = ASC, Byte 3 = ASCQ
        // Additional length at Byte 7. Descriptors start at Byte 8.
        val buffer = ByteArray(20)
        buffer[0] = 0x72.toByte() // Descriptor format current error
        buffer[1] = 0x05.toByte() // ILLEGAL REQUEST
        buffer[2] = 0x21.toByte() // ASC: Logical block address out of range
        buffer[3] = 0x00.toByte() // ASCQ: 00
        buffer[7] = 12.toByte()  // Additional length

        // Descriptor: Type 0x00 (Information descriptor), Length 0x0A (10 bytes)
        buffer[8] = 0x00.toByte()
        buffer[9] = 0x0A.toByte()
        buffer[10] = 0x80.toByte() // VALID bit for info descriptor
        val infoLba = 0x1234567890ABCDEFL
        ByteBuffer.wrap(buffer, 12, 8).order(ByteOrder.BIG_ENDIAN).putLong(infoLba)

        val sense = ScsiCdbBuilder.parseRequestSense(buffer)

        assertEquals(0x72, sense.responseCode)
        assertTrue(sense.isDescriptorFormat)
        assertEquals(0x05, sense.senseKey)
        assertEquals("ILLEGAL REQUEST", sense.senseKeyDescription)
        assertEquals(0x21, sense.additionalSenseCode)
        assertEquals("Logical block address out of range", sense.ascDescription)
        assertEquals(infoLba, sense.information)
        assertEquals(12, sense.additionalSenseLength)
    }

    // ========================================================================
    // 3. Common Sense Keys Exhaustive Validation
    // ========================================================================

    @Test
    fun testCommonSenseKeys() {
        val expectedKeys = mapOf(
            0x00 to "NO SENSE",
            0x01 to "RECOVERED ERROR",
            0x02 to "NOT READY",
            0x03 to "MEDIUM ERROR",
            0x04 to "HARDWARE ERROR",
            0x05 to "ILLEGAL REQUEST",
            0x06 to "UNIT ATTENTION",
            0x07 to "DATA PROTECT",
            0x08 to "BLANK CHECK",
            0x09 to "VENDOR SPECIFIC",
            0x0A to "COPY ABORTED",
            0x0B to "ABORTED COMMAND",
            0x0C to "VOLUME OVERFLOW",
            0x0D to "MISCOMPARE",
            0x0E to "MISCOMPARE"
        )

        for ((key, expectedName) in expectedKeys) {
            val buf = ByteArray(18)
            buf[0] = 0x70
            buf[2] = key.toByte()
            val sense = ScsiCdbBuilder.parseRequestSense(buf)
            assertEquals("Sense key 0x${Integer.toHexString(key)} mismatch", expectedName, sense.senseKeyDescription)
            assertEquals(key, sense.senseKey)
        }

        // Test unknown sense key
        val unknownBuf = ByteArray(18)
        unknownBuf[0] = 0x70
        unknownBuf[2] = 0x0F
        val unknownSense = ScsiCdbBuilder.parseRequestSense(unknownBuf)
        assertEquals("UNKNOWN SENSE (0xf)", unknownSense.senseKeyDescription)
    }

    // ========================================================================
    // 4. Known ASC / ASCQ Mappings
    // ========================================================================

    @Test
    fun testKnownAscAscqMappings() {
        fun makeSense(asc: Int, ascq: Int): ScsiCdbBuilder.SenseDataResponse {
            val buf = ByteArray(18)
            buf[0] = 0x70
            buf[2] = 0x02
            buf[12] = asc.toByte()
            buf[13] = ascq.toByte()
            return ScsiCdbBuilder.parseRequestSense(buf)
        }

        assertEquals("No additional sense information", makeSense(0x00, 0x00).ascDescription)
        assertEquals("Logical unit is in process of becoming ready", makeSense(0x04, 0x01).ascDescription)
        assertEquals("Logical unit not ready, initializing command required", makeSense(0x04, 0x02).ascDescription)
        assertEquals("Unrecovered read error", makeSense(0x11, 0x00).ascDescription)
        assertEquals("Read retries exhausted", makeSense(0x11, 0x01).ascDescription)
        assertEquals("Invalid command operation code", makeSense(0x20, 0x00).ascDescription)
        assertEquals("Logical block address out of range", makeSense(0x21, 0x00).ascDescription)
        assertEquals("Invalid field in CDB", makeSense(0x24, 0x00).ascDescription)
        assertEquals("Write protected", makeSense(0x27, 0x00).ascDescription)
        assertEquals("Not ready to ready change, medium may have changed", makeSense(0x28, 0x00).ascDescription)
        assertEquals("Power on, reset, or bus device reset occurred", makeSense(0x29, 0x00).ascDescription)
        assertEquals("Medium not present", makeSense(0x3A, 0x00).ascDescription)
        assertEquals("Medium not present, tray closed", makeSense(0x3A, 0x01).ascDescription)
        assertEquals("Internal target failure", makeSense(0x44, 0x00).ascDescription)
        assertEquals("SCSI parity error", makeSense(0x47, 0x00).ascDescription)
    }

    // ========================================================================
    // 5. Unknown ASC / ASCQ Exact Preservation Without Invention
    // ========================================================================

    @Test
    fun testUnknownAscAscqPreservesNumericCodesExact() {
        val buf = ByteArray(18)
        buf[0] = 0x70
        buf[2] = 0x05
        buf[12] = 0xE1.toByte() // Vendor-specific / unknown ASC
        buf[13] = 0x9B.toByte() // Unknown ASCQ

        val sense = ScsiCdbBuilder.parseRequestSense(buf)

        assertEquals(0xE1, sense.additionalSenseCode)
        assertEquals(0x9B, sense.additionalSenseCodeQualifier)
        assertEquals("ASC: 0xe1, ASCQ: 0x9b", sense.ascDescription)
    }

    // ========================================================================
    // 6. Malformed, Truncated, and Empty Buffers
    // ========================================================================

    @Test
    fun testEmptyBufferDoesNotCrash() {
        val emptySense = ScsiCdbBuilder.parseRequestSense(ByteArray(0))
        assertEquals(0, emptySense.responseCode)
        assertEquals(0, emptySense.senseKey)
        assertEquals("NO SENSE", emptySense.senseKeyDescription)
        assertEquals("No Sense Data", emptySense.ascDescription)
        assertFalse(emptySense.isValid)
    }

    @Test
    fun testTruncatedBufferOneByteDoesNotCrash() {
        val buf = byteArrayOf(0x70.toByte())
        val sense = ScsiCdbBuilder.parseRequestSense(buf)
        assertEquals(0x70, sense.responseCode)
        assertEquals(0, sense.senseKey)
        assertFalse(sense.isValid)
    }

    @Test
    fun testTruncatedBufferThreeBytesExtractsAvailableFields() {
        val buf = byteArrayOf(0xF0.toByte(), 0x00, 0x03.toByte()) // 0x70 valid, sense key 0x03
        val sense = ScsiCdbBuilder.parseRequestSense(buf)
        assertEquals(0x70, sense.responseCode)
        assertTrue(sense.isValid)
        assertEquals(0x03, sense.senseKey)
        assertEquals("MEDIUM ERROR", sense.senseKeyDescription)
        assertEquals(0, sense.additionalSenseCode)
        assertEquals(0, sense.additionalSenseCodeQualifier)
    }

    @Test
    fun testTruncatedDescriptorBuffer() {
        val buf = byteArrayOf(0x72.toByte(), 0x02.toByte()) // 0x72 descriptor, sense key 0x02 (NOT READY)
        val sense = ScsiCdbBuilder.parseRequestSense(buf)
        assertEquals(0x72, sense.responseCode)
        assertTrue(sense.isDescriptorFormat)
        assertEquals(0x02, sense.senseKey)
        assertEquals("NOT READY", sense.senseKeyDescription)
    }

    // ========================================================================
    // 7. Data Class Contract & Diagnostics
    // ========================================================================

    @Test
    fun testSenseDataResponseEqualsAndHashCode() {
        val raw1 = byteArrayOf(0x70, 0x00, 0x03)
        val raw2 = byteArrayOf(0x70, 0x00, 0x03)
        val sense1 = ScsiCdbBuilder.parseRequestSense(raw1)
        val sense2 = ScsiCdbBuilder.parseRequestSense(raw2)

        assertEquals(sense1, sense2)
        assertEquals(sense1.hashCode(), sense2.hashCode())

        val raw3 = byteArrayOf(0x70, 0x00, 0x04)
        val sense3 = ScsiCdbBuilder.parseRequestSense(raw3)
        assertNotEquals(sense1, sense3)
    }

    @Test
    fun testOpcodeNameLookup() {
        assertEquals("TEST_UNIT_READY", ScsiCdbBuilder.getOpcodeName(0x00))
        assertEquals("REQUEST_SENSE", ScsiCdbBuilder.getOpcodeName(0x03))
        assertEquals("INQUIRY", ScsiCdbBuilder.getOpcodeName(0x12))
        assertEquals("MODE_SENSE_6", ScsiCdbBuilder.getOpcodeName(0x1A))
        assertEquals("READ_10", ScsiCdbBuilder.getOpcodeName(0x28))
        assertEquals("WRITE_10", ScsiCdbBuilder.getOpcodeName(0x2A))
        assertEquals("READ_16", ScsiCdbBuilder.getOpcodeName(0x88.toByte()))
        assertEquals("WRITE_16", ScsiCdbBuilder.getOpcodeName(0x8A.toByte()))
        assertEquals("READ_CAPACITY_16", ScsiCdbBuilder.getOpcodeName(0x9E.toByte()))
        assertEquals("OPCODE_0xFF", ScsiCdbBuilder.getOpcodeName(0xFF.toByte()))
    }
}
