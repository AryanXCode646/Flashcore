package com.ashishsinghbora.flashcore.scsi

/**
 * Structured result of an executed SCSI transaction over USB Bulk-Only Transport (BOT).
 *
 * Provides typed access to the CSW status, CHECK CONDITION detection, and parsed
 * sense information retrieved via automatic REQUEST SENSE.
 *
 * @property csw The Command Status Wrapper returned by the target device.
 * @property isSuccess True if the command completed with PASSED (0x00) status.
 * @property isCheckCondition True if the device returned CHECK CONDITION / FAILED (0x01).
 * @property isPhaseError True if the device returned PHASE_ERROR (0x02).
 * @property senseData Parsed sense data retrieved via automatic REQUEST SENSE, if available.
 * @property requestSenseFailed True if automatic REQUEST SENSE was attempted but failed.
 * @property requestSenseError Error message from REQUEST SENSE attempt, if failed.
 */
data class ScsiCommandResult(
    val csw: CommandStatusWrapper,
    val isSuccess: Boolean = csw.isSuccess,
    val isCheckCondition: Boolean = csw.isCheckCondition,
    val isPhaseError: Boolean = csw.isPhaseError,
    val senseData: ScsiCdbBuilder.SenseDataResponse? = csw.senseData,
    val requestSenseFailed: Boolean = csw.requestSenseFailed,
    val requestSenseError: String? = csw.requestSenseError
)
