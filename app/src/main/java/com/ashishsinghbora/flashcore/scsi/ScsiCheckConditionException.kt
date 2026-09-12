package com.ashishsinghbora.flashcore.scsi

import java.io.IOException

/**
 * Thrown when a SCSI command terminates with CHECK CONDITION (bCSWStatus = 0x01).
 *
 * Encapsulates the target command opcode, CSW details, and parsed sense data (Sense Key, ASC, ASCQ),
 * or records whether the subsequent automatic REQUEST SENSE query failed.
 *
 * @property opcode The SCSI command opcode that failed.
 * @property commandName Human-readable name of the failed SCSI command.
 * @property csw The Command Status Wrapper returned by the target device.
 * @property senseData The parsed sense data retrieved via automatic REQUEST SENSE, if successful.
 * @property requestSenseFailed True if automatic REQUEST SENSE was attempted but failed.
 * @property requestSenseError Error message from the REQUEST SENSE attempt, if failed.
 */
class ScsiCheckConditionException(
    val opcode: Byte,
    val commandName: String = ScsiCdbBuilder.getOpcodeName(opcode),
    val csw: CommandStatusWrapper,
    val senseData: ScsiCdbBuilder.SenseDataResponse? = csw.senseData,
    val requestSenseFailed: Boolean = csw.requestSenseFailed,
    val requestSenseError: String? = csw.requestSenseError,
    message: String = buildErrorMessage(commandName, opcode, csw, senseData, requestSenseFailed, requestSenseError)
) : IOException(message) {

    companion object {
        fun buildErrorMessage(
            commandName: String,
            opcode: Byte,
            csw: CommandStatusWrapper,
            senseData: ScsiCdbBuilder.SenseDataResponse?,
            requestSenseFailed: Boolean,
            requestSenseError: String?
        ): String {
            val opHex = "0x" + Integer.toHexString(opcode.toInt() and 0xFF).padStart(2, '0').uppercase()
            val base = "SCSI command $commandName ($opHex) failed with CHECK CONDITION (CSW tag 0x${Integer.toHexString(csw.tag)})"
            return when {
                senseData != null && senseData.responseCode != 0 -> {
                    "$base: [${senseData.senseKeyDescription}] ${senseData.ascDescription} (Key=0x${Integer.toHexString(senseData.senseKey)}, ASC=0x${Integer.toHexString(senseData.additionalSenseCode).padStart(2, '0')}, ASCQ=0x${Integer.toHexString(senseData.additionalSenseCodeQualifier).padStart(2, '0')})"
                }
                requestSenseFailed -> {
                    "$base: Automatic REQUEST SENSE failed: ${requestSenseError ?: "unknown error"}"
                }
                else -> {
                    "$base: No sense data available"
                }
            }
        }
    }
}
