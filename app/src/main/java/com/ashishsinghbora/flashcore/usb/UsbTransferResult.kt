package com.ashishsinghbora.flashcore.usb

import java.io.IOException

/**
 * Direction of a USB Bulk Transfer.
 */
enum class UsbTransferDirection {
    IN,
    OUT
}

/**
 * Comprehensive audit result for a USB Bulk Transfer operation.
 *
 * Tracks expected vs actual transferred byte count, chunk count, error states,
 * and classifies the transfer as complete, partial, zero-byte, or failed.
 *
 * @property direction The transfer direction (IN for device-to-host, OUT for host-to-device).
 * @property expectedBytes The expected number of bytes for the transfer.
 * @property actualBytes The actual number of bytes successfully transferred.
 * @property isComplete True if exactly [expectedBytes] were transferred.
 * @property isPartial True if some bytes were transferred but fewer than [expectedBytes] ([actualBytes] in 1 until [expectedBytes]).
 * @property isZeroByte True if 0 bytes were transferred ([actualBytes] == 0).
 * @property isFailed True if the transfer did not complete fully ([actualBytes] < [expectedBytes]).
 * @property errorCode Low-level error code returned by USB subsystem, if any.
 * @property chunksTransferred Number of individual transfer chunks required to accumulate [actualBytes].
 * @property errorMessage Diagnostic explanation if the transfer failed or terminated early.
 */
data class UsbTransferResult(
    val direction: UsbTransferDirection,
    val expectedBytes: Int,
    val actualBytes: Int,
    val isComplete: Boolean,
    val isPartial: Boolean,
    val isZeroByte: Boolean,
    val isFailed: Boolean,
    val errorCode: Int = 0,
    val chunksTransferred: Int = 0,
    val errorMessage: String? = null
)

/**
 * Thrown when a USB transfer completes with fewer bytes than requested,
 * preventing silent acceptance of partial data or truncated writes.
 */
class UsbShortTransferException(
    val direction: UsbTransferDirection,
    val expectedBytes: Int,
    val actualBytes: Int,
    message: String = "USB ${direction.name} transfer incomplete: expected $expectedBytes bytes, but transferred $actualBytes bytes"
) : IOException(message)
