package com.ashishsinghbora.flashcore.usb

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface

/**
 * Abstraction over low-level USB device connection operations.
 * Enables deterministic testing of SCSI Bulk-Only Transport (BOT) transactions,
 * clear-halt stall recovery, and partial transfer loops in pure JVM environments.
 */
interface UsbConnectionAdapter {
    fun bulkTransfer(endpoint: UsbEndpoint?, buffer: ByteArray, offset: Int, length: Int, timeout: Int): Int
    fun bulkTransfer(endpoint: UsbEndpoint?, buffer: ByteArray, length: Int, timeout: Int): Int =
        bulkTransfer(endpoint, buffer, 0, length, timeout)
    fun controlTransfer(requestType: Int, request: Int, value: Int, index: Int, buffer: ByteArray?, length: Int, timeout: Int): Int
    fun claimInterface(intf: UsbInterface?, force: Boolean): Boolean
    fun releaseInterface(intf: UsbInterface?): Boolean
    fun close()
}

/**
 * Production implementation delegating directly to Android's [UsbDeviceConnection].
 */
class AndroidUsbConnectionAdapter(
    val connection: UsbDeviceConnection
) : UsbConnectionAdapter {
    override fun bulkTransfer(endpoint: UsbEndpoint?, buffer: ByteArray, offset: Int, length: Int, timeout: Int): Int {
        return connection.bulkTransfer(endpoint, buffer, offset, length, timeout)
    }

    override fun bulkTransfer(endpoint: UsbEndpoint?, buffer: ByteArray, length: Int, timeout: Int): Int {
        return connection.bulkTransfer(endpoint, buffer, length, timeout)
    }

    override fun controlTransfer(requestType: Int, request: Int, value: Int, index: Int, buffer: ByteArray?, length: Int, timeout: Int): Int {
        return connection.controlTransfer(requestType, request, value, index, buffer, length, timeout)
    }

    override fun claimInterface(intf: UsbInterface?, force: Boolean): Boolean {
        return intf?.let { connection.claimInterface(it, force) } ?: false
    }

    override fun releaseInterface(intf: UsbInterface?): Boolean {
        return intf?.let { connection.releaseInterface(it) } ?: false
    }

    override fun close() {
        connection.close()
    }
}
