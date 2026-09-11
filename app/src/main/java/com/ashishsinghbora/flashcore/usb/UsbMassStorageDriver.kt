package com.ashishsinghbora.flashcore.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.util.Log
import com.ashishsinghbora.flashcore.block.BlockDevice
import com.ashishsinghbora.flashcore.block.DeviceCapacity
import com.ashishsinghbora.flashcore.block.DeviceDisconnectedException
import com.ashishsinghbora.flashcore.scsi.CommandBlockWrapper
import com.ashishsinghbora.flashcore.scsi.CommandStatusWrapper
import com.ashishsinghbora.flashcore.scsi.ScsiCdbBuilder
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Low-Level Non-Root USB Mass Storage SCSI Bulk-Only Transport (BOT) Driver.
 *
 * Communicates directly with USB Flash drives via Android's UsbManager without root privileges.
 * Manages raw SCSI Command Block Wrappers (CBW), data phases, Command Status Wrappers (CSW),
 * automated clear-halt stall recovery, BOMSR resets, partial transfer loops, and 16-byte CDBs.
 *
 * Implements [BlockDevice] to allow strategy engines to access USB storage via a uniform abstraction.
 */
class UsbMassStorageDriver(
    private val usbManager: UsbManager,
    val device: UsbDevice?
) : BlockDevice {

    companion object {
        private const val TAG = "UsbMassStorageDriver"
        const val USB_CLASS_MASS_STORAGE = 8
        const val USB_SUBCLASS_SCSI = 6
        const val USB_PROTOCOL_BOT = 0x50 // Bulk-Only Transport

        const val DEFAULT_TIMEOUT_MS = 5000
        const val WRITE_TIMEOUT_MS = 15000
        const val MAX_RETRIES = 3
    }

    private var connection: UsbDeviceConnection? = null
    private var usbInterface: UsbInterface? = null
    private var inEndpoint: UsbEndpoint? = null
    private var outEndpoint: UsbEndpoint? = null

    private val ioLock = ReentrantLock()
    private val cswBuffer = ByteArray(CommandStatusWrapper.CSW_SIZE)

    var diskInfo: UsbDiskInfo? = null
        private set

    override val isConnected: Boolean
        get() {
            val dev = device
            return connection != null && (dev == null || usbManager.deviceList.containsKey(dev.deviceName))
        }

    override val sectorSizeBytes: Int
        get() = diskInfo?.sectorSizeBytes ?: 512

    /**
     * Checks if physical USB device is still registered with Android USB subsystem.
     */
    private fun checkDeviceConnected() {
        val dev = device ?: return
        if (!usbManager.deviceList.containsKey(dev.deviceName)) {
            throw DeviceDisconnectedException("USB device ${dev.deviceName} has been disconnected")
        }
    }

    /**
     * Finds the Mass Storage Interface and endpoints on the device.
     */
    fun initDriver(): Boolean {
        val dev = device ?: return false
        for (i in 0 until dev.interfaceCount) {
            val iface = dev.getInterface(i)
            if (iface.interfaceClass == USB_CLASS_MASS_STORAGE) {
                var inEp: UsbEndpoint? = null
                var outEp: UsbEndpoint? = null

                for (e in 0 until iface.endpointCount) {
                    val ep = iface.getEndpoint(e)
                    if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                        if (ep.direction == UsbConstants.USB_DIR_IN) {
                            inEp = ep
                        } else if (ep.direction == UsbConstants.USB_DIR_OUT) {
                            outEp = ep
                        }
                    }
                }

                if (inEp != null && outEp != null) {
                    usbInterface = iface
                    inEndpoint = inEp
                    outEndpoint = outEp
                    return true
                }
            }
        }
        return false
    }

    /**
     * Opens connection, claims the USB interface, and reads drive geometry.
     */
    @Throws(IOException::class)
    fun open(): UsbDiskInfo {
        ioLock.withLock {
            val dev = device ?: throw IOException("Cannot open USB driver: No UsbDevice provided")

            if (!usbManager.hasPermission(dev)) {
                throw SecurityException("USB Permission not granted for ${dev.deviceName}")
            }

            if (usbInterface == null) {
                if (!initDriver()) {
                    throw IOException("No Bulk-Only USB Mass Storage interface found on ${dev.deviceName}")
                }
            }

            val conn = usbManager.openDevice(dev)
                ?: throw IOException("Failed to open UsbDeviceConnection for ${dev.deviceName}")
            connection = conn

            if (!conn.claimInterface(usbInterface, true)) {
                conn.close()
                connection = null
                throw IOException("Failed to claim USB Mass Storage Interface")
            }

            // Test if unit is ready
            var ready = false
            for (i in 0 until 5) {
                try {
                    if (testUnitReady()) {
                        ready = true
                        break
                    }
                } catch (e: Exception) {
                    Thread.sleep(100)
                }
            }

            val inquiry = inquiry()
            val capacity = readCapacity()
            val modeSense = modeSense()

            val info = UsbDiskInfo(
                device = dev,
                vendorId = dev.vendorId,
                productId = dev.productId,
                manufacturerName = dev.manufacturerName ?: inquiry.vendorId,
                productName = dev.productName ?: inquiry.productId,
                vendorString = inquiry.vendorId,
                productString = inquiry.productId,
                revision = inquiry.productRevision,
                serialNumber = dev.serialNumber ?: "GENERIC_${dev.deviceId}",
                totalCapacityBytes = capacity.totalCapacityBytes,
                totalSectors = capacity.maxLba + 1L,
                sectorSizeBytes = capacity.blockSizeBytes,
                isRemovable = inquiry.isRemovable,
                isWriteProtected = modeSense.isWriteProtected,
                hasPermission = true
            )
            diskInfo = info
            return info
        }
    }

    /**
     * Loops over bulkTransfer IN to ensure partial packets are completely read.
     */
    private fun bulkTransferInAll(
        conn: UsbDeviceConnection,
        ep: UsbEndpoint,
        buffer: ByteArray,
        offset: Int,
        length: Int,
        timeoutMs: Int
    ): Int {
        checkDeviceConnected()
        var transferred = 0
        val startTime = System.currentTimeMillis()
        while (transferred < length) {
            val remaining = length - transferred
            val elapsed = (System.currentTimeMillis() - startTime).toInt()
            val remainingTimeout = (timeoutMs - elapsed).coerceAtLeast(1)
            if (elapsed >= timeoutMs) {
                Log.w(TAG, "bulkTransferIn timed out after ${elapsed}ms (transferred $transferred/$length bytes)")
                return if (transferred > 0) transferred else -1
            }
            val res = conn.bulkTransfer(ep, buffer, offset + transferred, remaining, remainingTimeout)
            if (res < 0) {
                checkDeviceConnected()
                return if (transferred > 0) transferred else res
            }
            if (res == 0) {
                Thread.sleep(1)
            } else {
                transferred += res
            }
        }
        return transferred
    }

    /**
     * Loops over bulkTransfer OUT to ensure partial packets are completely written.
     */
    private fun bulkTransferOutAll(
        conn: UsbDeviceConnection,
        ep: UsbEndpoint,
        buffer: ByteArray,
        offset: Int,
        length: Int,
        timeoutMs: Int
    ): Int {
        checkDeviceConnected()
        var transferred = 0
        val startTime = System.currentTimeMillis()
        while (transferred < length) {
            val remaining = length - transferred
            val elapsed = (System.currentTimeMillis() - startTime).toInt()
            val remainingTimeout = (timeoutMs - elapsed).coerceAtLeast(1)
            if (elapsed >= timeoutMs) {
                Log.w(TAG, "bulkTransferOut timed out after ${elapsed}ms (transferred $transferred/$length bytes)")
                return if (transferred > 0) transferred else -1
            }
            val res = conn.bulkTransfer(ep, buffer, offset + transferred, remaining, remainingTimeout)
            if (res < 0) {
                checkDeviceConnected()
                return if (transferred > 0) transferred else res
            }
            if (res == 0) {
                Thread.sleep(1)
            } else {
                transferred += res
            }
        }
        return transferred
    }

    /**
     * Executes a full SCSI BOT transaction (CBW -> Data Phase -> CSW).
     */
    @Throws(IOException::class)
    private fun executeBotTransaction(
        cbw: CommandBlockWrapper,
        dataBuffer: ByteArray?,
        dataOffset: Int = 0,
        dataLength: Int = 0,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS,
        autoRequestSense: Boolean = true
    ): CommandStatusWrapper {
        checkDeviceConnected()
        val conn = connection ?: throw DeviceDisconnectedException("USB Driver is not connected")
        val outEp = outEndpoint ?: throw IOException("OUT Endpoint unavailable")
        val inEp = inEndpoint ?: throw IOException("IN Endpoint unavailable")

        var attempt = 0
        while (attempt < MAX_RETRIES) {
            attempt++
            try {
                checkDeviceConnected()

                // 1. Send Command Block Wrapper (CBW - 31 bytes)
                val cbwBytes = cbw.toByteArray()
                val sentCbw = conn.bulkTransfer(outEp, cbwBytes, cbwBytes.size, timeoutMs)
                if (sentCbw != CommandBlockWrapper.CBW_SIZE) {
                    clearHalt(outEp)
                    throw IOException("Failed to transfer 31-byte CBW (transferred $sentCbw bytes)")
                }

                // 2. Data Phase (IN or OUT if data length > 0)
                if (dataLength > 0 && dataBuffer != null) {
                    if (cbw.direction == CommandBlockWrapper.Direction.DATA_IN) {
                        val received = bulkTransferInAll(conn, inEp, dataBuffer, dataOffset, dataLength, timeoutMs)
                        if (received < 0) {
                            clearHalt(inEp)
                            throw IOException("Data IN transfer failed (code: $received)")
                        }
                    } else if (cbw.direction == CommandBlockWrapper.Direction.DATA_OUT) {
                        val sent = bulkTransferOutAll(conn, outEp, dataBuffer, dataOffset, dataLength, timeoutMs)
                        if (sent < 0) {
                            clearHalt(outEp)
                            throw IOException("Data OUT transfer failed (code: $sent)")
                        }
                    }
                }

                // 3. Status Phase (CSW - 13 bytes)
                var receivedCsw = conn.bulkTransfer(inEp, cswBuffer, cswBuffer.size, timeoutMs)
                if (receivedCsw < 0) {
                    // Endpoint might be stalled, clear halt and re-read CSW
                    clearHalt(inEp)
                    receivedCsw = conn.bulkTransfer(inEp, cswBuffer, cswBuffer.size, timeoutMs)
                }

                if (receivedCsw != CommandStatusWrapper.CSW_SIZE) {
                    clearHalt(inEp)
                    throw IOException("Failed to read 13-byte CSW (received: $receivedCsw bytes)")
                }

                val csw = CommandStatusWrapper.parse(cswBuffer)
                if (csw.tag != cbw.tag) {
                    resetRecovery()
                    throw IOException("CSW Tag mismatch: expected ${cbw.tag}, got ${csw.tag}")
                }

                if (csw.isPhaseError) {
                    resetRecovery()
                    throw IOException("SCSI Phase Error reported by device CSW")
                }

                if (csw.isFailed && autoRequestSense) {
                    try {
                        val sense = requestSenseInternal()
                        Log.w(TAG, "SCSI Command Failed: SenseKey=${sense.senseKeyDescription} (0x${Integer.toHexString(sense.senseKey)}), ASC=${sense.ascDescription}")
                    } catch (e: Exception) {
                        Log.w(TAG, "Auto REQUEST SENSE query failed: ${e.message}")
                    }
                }

                return csw
            } catch (e: Exception) {
                if (e is DeviceDisconnectedException) throw e
                Log.w(TAG, "BOT Transaction failed on attempt $attempt: ${e.message}")
                if (attempt >= MAX_RETRIES) {
                    throw if (e is IOException) e else IOException("BOT Transaction exhausted retries", e)
                }
                Thread.sleep((100L * attempt))
            }
        }

        throw IOException("BOT Transaction failed after $MAX_RETRIES attempts")
    }

    /**
     * Internal implementation of REQUEST SENSE without auto-recovery recursion.
     */
    private fun requestSenseInternal(): ScsiCdbBuilder.SenseDataResponse {
        val cdb = ScsiCdbBuilder.requestSense(18)
        val buffer = ByteArray(18)
        val cbw = CommandBlockWrapper.create(18, CommandBlockWrapper.Direction.DATA_IN, cdb)
        val csw = executeBotTransaction(cbw, buffer, 0, 18, autoRequestSense = false)
        return if (csw.isSuccess) {
            ScsiCdbBuilder.parseRequestSense(buffer)
        } else {
            ScsiCdbBuilder.parseRequestSense(ByteArray(0))
        }
    }

    /**
     * Issues SCSI REQUEST SENSE (0x03) to retrieve sense key and ASC/ASCQ details.
     */
    fun requestSense(): ScsiCdbBuilder.SenseDataResponse {
        return ioLock.withLock {
            requestSenseInternal()
        }
    }

    /**
     * Issues SCSI MODE_SENSE_6 (0x1A) to check write-protect and device parameters.
     */
    fun modeSense(): ScsiCdbBuilder.ModeSenseResponse {
        return ioLock.withLock {
            try {
                val cdb = ScsiCdbBuilder.modeSense6(0x3F, 192)
                val buffer = ByteArray(192)
                val cbw = CommandBlockWrapper.create(192, CommandBlockWrapper.Direction.DATA_IN, cdb)
                val csw = executeBotTransaction(cbw, buffer, 0, 192, autoRequestSense = false)
                if (csw.isSuccess) {
                    ScsiCdbBuilder.parseModeSense6(buffer)
                } else {
                    ScsiCdbBuilder.parseModeSense6(ByteArray(0))
                }
            } catch (e: Exception) {
                Log.w(TAG, "MODE SENSE failed: ${e.message}")
                ScsiCdbBuilder.parseModeSense6(ByteArray(0))
            }
        }
    }

    /**
     * Direct transfer overload using direct ByteBuffer to eliminate JVM garbage collection pauses.
     */
    @Throws(IOException::class)
    fun writeDirectBuffer(
        lba: Long,
        blockCount: Int,
        directBuffer: ByteBuffer,
        offset: Int,
        length: Int,
        timeoutMs: Int = WRITE_TIMEOUT_MS
    ): Boolean {
        return ioLock.withLock {
            checkDeviceConnected()
            val conn = connection ?: throw DeviceDisconnectedException("USB Driver is not connected")
            val outEp = outEndpoint ?: throw IOException("OUT Endpoint unavailable")
            val inEp = inEndpoint ?: throw IOException("IN Endpoint unavailable")

            val cdb = if (lba > 0xFFFFFFFFL || blockCount > 0xFFFF) {
                ScsiCdbBuilder.write16(lba, blockCount.toLong())
            } else {
                ScsiCdbBuilder.write10(lba, blockCount)
            }
            val cbw = CommandBlockWrapper.create(
                dataTransferLength = length,
                direction = CommandBlockWrapper.Direction.DATA_OUT,
                cdb = cdb
            )

            val cbwBytes = cbw.toByteArray()
            val sentCbw = conn.bulkTransfer(outEp, cbwBytes, cbwBytes.size, timeoutMs)
            if (sentCbw != CommandBlockWrapper.CBW_SIZE) {
                clearHalt(outEp)
                throw IOException("Failed to write CBW for LBA $lba")
            }

            // Transfer data directly from byte array / buffer slice
            val tempArray = ByteArray(length)
            val pos = directBuffer.position()
            try {
                directBuffer.position(offset)
                directBuffer.get(tempArray, 0, length)
            } finally {
                directBuffer.position(pos)
            }

            val sentData = bulkTransferOutAll(conn, outEp, tempArray, 0, length, timeoutMs)
            if (sentData < 0) {
                clearHalt(outEp)
                throw IOException("Failed to write data at LBA $lba (sent: $sentData)")
            }

            // Status Phase
            var receivedCsw = conn.bulkTransfer(inEp, cswBuffer, cswBuffer.size, timeoutMs)
            if (receivedCsw < 0) {
                clearHalt(inEp)
                receivedCsw = conn.bulkTransfer(inEp, cswBuffer, cswBuffer.size, timeoutMs)
            }

            if (receivedCsw != CommandStatusWrapper.CSW_SIZE) {
                clearHalt(inEp)
                throw IOException("Failed to read CSW after writing LBA $lba")
            }

            val csw = CommandStatusWrapper.parse(cswBuffer)
            if (csw.tag != cbw.tag) {
                resetRecovery()
                throw IOException("CSW Tag mismatch after writing LBA $lba")
            }
            if (csw.isPhaseError) {
                resetRecovery()
                throw IOException("SCSI Phase Error reported after writing LBA $lba")
            }
            if (csw.isFailed) {
                try {
                    val sense = requestSenseInternal()
                    Log.w(TAG, "Write failed at LBA $lba: SenseKey=${sense.senseKeyDescription}, ASC=${sense.ascDescription}")
                } catch (e: Exception) {
                    Log.w(TAG, "Sense query after write failure failed: ${e.message}")
                }
            }
            csw.isSuccess
        }
    }

    /**
     * Executes TEST_UNIT_READY (0x00).
     */
    fun testUnitReady(): Boolean {
        return ioLock.withLock {
            val cdb = ScsiCdbBuilder.testUnitReady()
            val cbw = CommandBlockWrapper.create(0, CommandBlockWrapper.Direction.NONE, cdb)
            val csw = executeBotTransaction(cbw, null)
            csw.isSuccess
        }
    }

    /**
     * Executes INQUIRY (0x12) to get device name and capabilities.
     */
    fun inquiry(): ScsiCdbBuilder.InquiryResponse {
        return ioLock.withLock {
            val cdb = ScsiCdbBuilder.inquiry(36)
            val buffer = ByteArray(36)
            val cbw = CommandBlockWrapper.create(36, CommandBlockWrapper.Direction.DATA_IN, cdb)
            val csw = executeBotTransaction(cbw, buffer, 0, 36)
            if (!csw.isSuccess) {
                throw IOException("SCSI INQUIRY failed with CSW status: ${csw.status}")
            }
            ScsiCdbBuilder.parseInquiry(buffer)
        }
    }

    /**
     * Executes READ_CAPACITY_10 or READ_CAPACITY_16.
     */
    fun readCapacity(): ScsiCdbBuilder.ReadCapacityResponse {
        return ioLock.withLock {
            val cdb = ScsiCdbBuilder.readCapacity10()
            val buffer = ByteArray(8)
            val cbw = CommandBlockWrapper.create(8, CommandBlockWrapper.Direction.DATA_IN, cdb)
            val csw = executeBotTransaction(cbw, buffer, 0, 8)
            if (!csw.isSuccess) {
                throw IOException("SCSI READ_CAPACITY_10 failed with CSW status: ${csw.status}")
            }
            val cap10 = ScsiCdbBuilder.parseReadCapacity10(buffer)
            // Drives > 2 TB report 0xFFFFFFFF for READ_CAPACITY_10 and require READ_CAPACITY_16
            if (cap10.maxLba == 0xFFFFFFFFL) {
                try {
                    val cdb16 = ScsiCdbBuilder.readCapacity16(32)
                    val buffer16 = ByteArray(32)
                    val cbw16 = CommandBlockWrapper.create(32, CommandBlockWrapper.Direction.DATA_IN, cdb16)
                    val csw16 = executeBotTransaction(cbw16, buffer16, 0, 32)
                    if (csw16.isSuccess) {
                        return@withLock ScsiCdbBuilder.parseReadCapacity16(buffer16)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "READ_CAPACITY_16 fallback failed: ${e.message}, using READ_CAPACITY_10 result")
                }
            }
            cap10
        }
    }

    /**
     * Reads sector blocks starting at [lba].
     * Dispatches automatically between READ_10 and READ_16.
     */
    fun readBlocks(lba: Long, blockCount: Int, destBuffer: ByteArray, offset: Int = 0): Boolean {
        return ioLock.withLock {
            val sectorSize = diskInfo?.sectorSizeBytes ?: 512
            val totalBytes = blockCount * sectorSize
            val cdb = if (lba > 0xFFFFFFFFL || blockCount > 0xFFFF) {
                ScsiCdbBuilder.read16(lba, blockCount.toLong())
            } else {
                ScsiCdbBuilder.read10(lba, blockCount)
            }
            val cbw = CommandBlockWrapper.create(totalBytes, CommandBlockWrapper.Direction.DATA_IN, cdb)
            val csw = executeBotTransaction(cbw, destBuffer, offset, totalBytes)
            csw.isSuccess
        }
    }

    /**
     * Writes sector blocks starting at [lba].
     * Dispatches automatically between WRITE_10 and WRITE_16.
     */
    fun writeBlocks(lba: Long, blockCount: Int, srcBuffer: ByteArray, offset: Int = 0): Boolean {
        return ioLock.withLock {
            val sectorSize = diskInfo?.sectorSizeBytes ?: 512
            val totalBytes = blockCount * sectorSize
            val cdb = if (lba > 0xFFFFFFFFL || blockCount > 0xFFFF) {
                ScsiCdbBuilder.write16(lba, blockCount.toLong())
            } else {
                ScsiCdbBuilder.write10(lba, blockCount)
            }
            val cbw = CommandBlockWrapper.create(totalBytes, CommandBlockWrapper.Direction.DATA_OUT, cdb)
            val csw = executeBotTransaction(cbw, srcBuffer, offset, totalBytes, WRITE_TIMEOUT_MS)
            csw.isSuccess
        }
    }

    /**
     * Flushes physical write cache via SYNCHRONIZE_CACHE_10 (0x35).
     */
    fun synchronizeCache(): Boolean {
        return ioLock.withLock {
            try {
                val cdb = ScsiCdbBuilder.synchronizeCache10()
                val cbw = CommandBlockWrapper.create(0, CommandBlockWrapper.Direction.NONE, cdb)
                val csw = executeBotTransaction(cbw, null)
                csw.isSuccess
            } catch (e: Exception) {
                Log.w(TAG, "SYNCHRONIZE_CACHE failed: ${e.message}")
                false
            }
        }
    }

    // --- BlockDevice Interface Implementation ---

    override suspend fun capacity(): DeviceCapacity {
        currentCoroutineContext().ensureActive()
        val cap = readCapacity()
        return DeviceCapacity(cap.maxLba + 1L, cap.blockSizeBytes)
    }

    override suspend fun read(lba: Long, blockCount: Int, dest: ByteArray, offset: Int): Boolean {
        currentCoroutineContext().ensureActive()
        return readBlocks(lba, blockCount, dest, offset)
    }

    override suspend fun write(lba: Long, blockCount: Int, src: ByteArray, offset: Int): Boolean {
        currentCoroutineContext().ensureActive()
        return writeBlocks(lba, blockCount, src, offset)
    }

    override suspend fun writeDirectBuffer(
        lba: Long,
        blockCount: Int,
        directBuffer: ByteBuffer,
        offset: Int,
        length: Int
    ): Boolean {
        currentCoroutineContext().ensureActive()
        return writeDirectBuffer(lba, blockCount, directBuffer, offset, length, WRITE_TIMEOUT_MS)
    }

    override suspend fun flush(): Boolean {
        currentCoroutineContext().ensureActive()
        return synchronizeCache()
    }

    /**
     * Clears endpoint stall (USB_ENDPOINT_HALT).
     */
    private fun clearHalt(endpoint: UsbEndpoint) {
        val conn = connection ?: return
        try {
            // Standard USB Clear Feature (ENDPOINT_HALT = 0)
            conn.controlTransfer(
                0x02, // Endpoint Recipient
                0x01, // CLEAR_FEATURE
                0x00, // ENDPOINT_HALT
                endpoint.address,
                null,
                0,
                1000
            )
        } catch (e: Exception) {
            Log.e(TAG, "clearHalt failed on endpoint ${endpoint.address}: ${e.message}")
        }
    }

    /**
     * Performs USB Mass Storage Bulk-Only Mass Storage Reset (BOMSR).
     */
    private fun resetRecovery() {
        val conn = connection ?: return
        val iface = usbInterface ?: return
        try {
            // Bulk-Only Mass Storage Reset Request (0xFF, Class/Interface)
            conn.controlTransfer(
                0x21, // Class Request to Interface
                0xFF, // Bulk-Only Mass Storage Reset
                0,
                iface.id,
                null,
                0,
                1000
            )
            inEndpoint?.let { clearHalt(it) }
            outEndpoint?.let { clearHalt(it) }
        } catch (e: Exception) {
            Log.e(TAG, "resetRecovery failed: ${e.message}")
        }
    }

    override fun close() {
        ioLock.withLock {
            try {
                synchronizeCache()
            } catch (e: Exception) {
                // Ignore
            }
            try {
                usbInterface?.let { connection?.releaseInterface(it) }
            } catch (e: Exception) {
                // Ignore
            }
            try {
                connection?.close()
            } catch (e: Exception) {
                // Ignore
            }
            connection = null
            usbInterface = null
            inEndpoint = null
            outEndpoint = null
        }
    }
}
