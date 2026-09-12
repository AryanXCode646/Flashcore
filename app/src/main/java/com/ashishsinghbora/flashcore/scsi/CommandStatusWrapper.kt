package com.ashishsinghbora.flashcore.scsi

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * SCSI Command Status Wrapper (CSW) according to USB Mass Storage Class Bulk-Only Transport (BOT) Spec v1.0.
 * Total Length: Exactly 13 bytes.
 *
 * Structure:
 * 0..3   : dCSWSignature (0x53425355 - "USBS" in Little Endian)
 * 4..7   : dCSWTag (Must match dCBWTag of the associated command)
 * 8..11  : dCSWDataResidue (Difference between expected and actual data transferred)
 * 12     : bCSWStatus (0x00 = Command Passed, 0x01 = Command Failed, 0x02 = Phase Error)
 */
data class CommandStatusWrapper(
    val signature: Int,
    val tag: Int,
    val dataResidue: Int,
    val status: Status,
    val senseData: ScsiCdbBuilder.SenseDataResponse? = null,
    val requestSenseFailed: Boolean = false,
    val requestSenseError: String? = null
) {
    enum class Status(val code: Byte) {
        PASSED(0x00.toByte()),
        FAILED(0x01.toByte()),
        PHASE_ERROR(0x02.toByte()),
        UNKNOWN(0xFF.toByte());

        companion object {
            fun fromCode(code: Byte): Status = entries.find { it.code == code } ?: UNKNOWN
        }
    }

    val isSuccess: Boolean get() = status == Status.PASSED
    val isPhaseError: Boolean get() = status == Status.PHASE_ERROR
    val isFailed: Boolean get() = status == Status.FAILED
    val isCheckCondition: Boolean get() = status == Status.FAILED

    companion object {
        const val CSW_SIGNATURE = 0x53425355 // "USBS" in Little-Endian
        const val CSW_SIZE = 13

        /**
         * Parses a 13-byte buffer into a CommandStatusWrapper.
         */
        fun parse(data: ByteArray, offset: Int = 0): CommandStatusWrapper {
            require(data.size - offset >= CSW_SIZE) { "Data buffer too small for CSW: ${data.size - offset} bytes" }
            val buffer = ByteBuffer.wrap(data, offset, CSW_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            val sig = buffer.int
            val tag = buffer.int
            val residue = buffer.int
            val statusCode = buffer.get()

            require(sig == CSW_SIGNATURE) {
                "Invalid CSW signature: 0x${Integer.toHexString(sig)} (expected 0x${Integer.toHexString(CSW_SIGNATURE)})"
            }

            return CommandStatusWrapper(
                signature = sig,
                tag = tag,
                dataResidue = residue,
                status = Status.fromCode(statusCode)
            )
        }

        fun parse(buffer: ByteBuffer): CommandStatusWrapper {
            val originalOrder = buffer.order()
            buffer.order(ByteOrder.LITTLE_ENDIAN)
            val sig = buffer.int
            val tag = buffer.int
            val residue = buffer.int
            val statusCode = buffer.get()
            buffer.order(originalOrder)

            require(sig == CSW_SIGNATURE) {
                "Invalid CSW signature: 0x${Integer.toHexString(sig)} (expected 0x${Integer.toHexString(CSW_SIGNATURE)})"
            }

            return CommandStatusWrapper(
                signature = sig,
                tag = tag,
                dataResidue = residue,
                status = Status.fromCode(statusCode)
            )
        }
    }
}
