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
    private val usbManager: UsbManager? = null,
    val device: UsbDevice? = null,
    val transferHandler: UsbBulkTransferHandler = UsbBulkTransferHandler(),
    internal var connectionAdapter: UsbConnectionAdapter? = null
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

    private var connection: UsbConnectionAdapter? = connectionAdapter
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
            return connection != null && (dev == null || connectionAdapter != null || usbManager?.deviceList?.containsKey(dev.deviceName) == true)
        }

    override val sectorSizeBytes: Int
        get() = diskInfo?.sectorSizeBytes ?: 512

    /**
     * Checks if physical USB device is still registered with Android USB subsystem.
     */
    private fun checkDeviceConnected() {
        val dev = device ?: return
        if (connectionAdapter == null && usbManager?.deviceList?.containsKey(dev.deviceName) != true) {
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
            val dev = device
            if (connectionAdapter == null) {
                if (dev == null) throw IOException("Cannot open USB driver: No UsbDevice provided")

                if (usbManager?.hasPermission(dev) != true) {
                    throw SecurityException("USB Permission not granted for ${dev.deviceName}")
                }

                if (usbInterface == null) {
                    if (!initDriver()) {
                        throw IOException("No Bulk-Only USB Mass Storage interface found on ${dev.deviceName}")
                    }
                }

                val conn = usbManager?.openDevice(dev)
                    ?: throw IOException("Failed to open UsbDeviceConnection for ${dev.deviceName}")
                val adapter = AndroidUsbConnectionAdapter(conn)
                connection = adapter

                if (!adapter.claimInterface(usbInterface, true)) {
                    adapter.close()
                    connection = null
                    throw IOException("Failed to claim USB Mass Storage Interface")
                }
            } else {
                connection = connectionAdapter
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
                vendorId = dev?.vendorId ?: 0,
                productId = dev?.productId ?: 0,
                manufacturerName = dev?.manufacturerName ?: inquiry.vendorId,
                productName = dev?.productName ?: inquiry.productId,
                vendorString = inquiry.vendorId,
                productString = inquiry.productId,
                revision = inquiry.productRevision,
                serialNumber = dev?.serialNumber ?: "GENERIC_${dev?.deviceId ?: "TEST"}",
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
     * Loops over bulkTransfer IN using [transferHandler] to ensure partial packets are completely read.
     */
    private fun bulkTransferInAll(
        conn: UsbConnectionAdapter,
        ep: UsbEndpoint?,
        buffer: ByteArray,
        offset: Int,
        length: Int,
        timeoutMs: Int
    ): UsbTransferResult {
        checkDeviceConnected()
        return transferHandler.transferIn(
            endpoint = { buf, off, len, to -> conn.bulkTransfer(ep, buf, off, len, to) },
            buffer = buffer,
            offset = offset,
            expectedBytes = length,
            timeoutMs = timeoutMs
        )
    }

    /**
     * Loops over bulkTransfer OUT using [transferHandler] to ensure partial packets are completely written.
     */
    private fun bulkTransferOutAll(
        conn: UsbConnectionAdapter,
        ep: UsbEndpoint?,
        buffer: ByteArray,
        offset: Int,
        length: Int,
        timeoutMs: Int
    ): UsbTransferResult {
        checkDeviceConnected()
        return transferHandler.transferOut(
            endpoint = { buf, off, len, to -> conn.bulkTransfer(ep, buf, off, len, to) },
            buffer = buffer,
            offset = offset,
            expectedBytes = length,
            timeoutMs = timeoutMs
        )
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
        val outEp = outEndpoint
        val inEp = inEndpoint

        var attempt = 0
        while (attempt < MAX_RETRIES) {
            attempt++
            try {
                checkDeviceConnected()

                // 1. Send Command Block Wrapper (CBW - 31 bytes)
                val cbwBytes = cbw.toByteArray()
                val cbwResult = bulkTransferOutAll(conn, outEp, cbwBytes, 0, CommandBlockWrapper.CBW_SIZE, timeoutMs)
                if (!cbwResult.isComplete) {
                    clearHalt(outEp)
                    throw UsbShortTransferException(
                        direction = UsbTransferDirection.OUT,
                        expectedBytes = CommandBlockWrapper.CBW_SIZE,
                        actualBytes = cbwResult.actualBytes,
                        message = "Failed to transfer 31-byte CBW (sent ${cbwResult.actualBytes}/${CommandBlockWrapper.CBW_SIZE} bytes): ${cbwResult.errorMessage}"
                    )
                }

                // 2. Data Phase (IN or OUT if data length > 0)
                if (dataLength > 0 && dataBuffer != null) {
                    if (cbw.direction == CommandBlockWrapper.Direction.DATA_IN) {
                        val inResult = bulkTransferInAll(conn, inEp, dataBuffer, dataOffset, dataLength, timeoutMs)
                        if (!inResult.isComplete) {
                            clearHalt(inEp)
                            throw UsbShortTransferException(
                                direction = UsbTransferDirection.IN,
                                expectedBytes = dataLength,
                                actualBytes = inResult.actualBytes,
                                message = "Data IN transfer incomplete: expected $dataLength bytes, received ${inResult.actualBytes} bytes (${inResult.errorMessage})"
                            )
                        }
                    } else if (cbw.direction == CommandBlockWrapper.Direction.DATA_OUT) {
                        val outResult = bulkTransferOutAll(conn, outEp, dataBuffer, dataOffset, dataLength, timeoutMs)
                        if (!outResult.isComplete) {
                            clearHalt(outEp)
                            throw UsbShortTransferException(
                                direction = UsbTransferDirection.OUT,
                                expectedBytes = dataLength,
                                actualBytes = outResult.actualBytes,
                                message = "Data OUT transfer incomplete: expected $dataLength bytes, sent ${outResult.actualBytes} bytes (${outResult.errorMessage})"
                            )
                        }
                    }
                }

                // 3. Status Phase (CSW - 13 bytes)
                var cswResult = bulkTransferInAll(conn, inEp, cswBuffer, 0, CommandStatusWrapper.CSW_SIZE, timeoutMs)
                if (!cswResult.isComplete) {
                    // Endpoint might be stalled, clear halt and re-read CSW
                    clearHalt(inEp)
                    cswResult = bulkTransferInAll(conn, inEp, cswBuffer, 0, CommandStatusWrapper.CSW_SIZE, timeoutMs)
                }

                if (!cswResult.isComplete) {
                    clearHalt(inEp)
                    throw UsbShortTransferException(
                        direction = UsbTransferDirection.IN,
                        expectedBytes = CommandStatusWrapper.CSW_SIZE,
                        actualBytes = cswResult.actualBytes,
                        message = "Failed to read 13-byte CSW (received ${cswResult.actualBytes}/${CommandStatusWrapper.CSW_SIZE} bytes): ${cswResult.errorMessage}"
                    )
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

                if (csw.isSuccess && dataLength > 0 && csw.dataResidue > 0) {
                    throw UsbShortTransferException(
                        direction = if (cbw.direction == CommandBlockWrapper.Direction.DATA_IN) UsbTransferDirection.IN else UsbTransferDirection.OUT,
                        expectedBytes = dataLength,
                        actualBytes = dataLength - csw.dataResidue,
                        message = "SCSI device reported residual untransferred data: expected $dataLength bytes, residue ${csw.dataResidue} bytes"
                    )
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
            val outEp = outEndpoint
            val inEp = inEndpoint

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
            val cbwResult = bulkTransferOutAll(conn, outEp, cbwBytes, 0, CommandBlockWrapper.CBW_SIZE, timeoutMs)
            if (!cbwResult.isComplete) {
                clearHalt(outEp)
                throw UsbShortTransferException(
                    direction = UsbTransferDirection.OUT,
                    expectedBytes = CommandBlockWrapper.CBW_SIZE,
                    actualBytes = cbwResult.actualBytes,
                    message = "Failed to write CBW for LBA $lba: expected ${CommandBlockWrapper.CBW_SIZE} bytes, sent ${cbwResult.actualBytes} bytes"
                )
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

            val sentDataResult = bulkTransferOutAll(conn, outEp, tempArray, 0, length, timeoutMs)
            if (!sentDataResult.isComplete) {
                clearHalt(outEp)
                throw UsbShortTransferException(
                    direction = UsbTransferDirection.OUT,
                    expectedBytes = length,
                    actualBytes = sentDataResult.actualBytes,
                    message = "Failed to write complete data at LBA $lba: expected $length bytes, sent ${sentDataResult.actualBytes} bytes (${sentDataResult.errorMessage})"
                )
            }

            // Status Phase
            var cswResult = bulkTransferInAll(conn, inEp, cswBuffer, 0, CommandStatusWrapper.CSW_SIZE, timeoutMs)
            if (!cswResult.isComplete) {
                clearHalt(inEp)
                cswResult = bulkTransferInAll(conn, inEp, cswBuffer, 0, CommandStatusWrapper.CSW_SIZE, timeoutMs)
            }

            if (!cswResult.isComplete) {
                clearHalt(inEp)
                throw UsbShortTransferException(
                    direction = UsbTransferDirection.IN,
                    expectedBytes = CommandStatusWrapper.CSW_SIZE,
                    actualBytes = cswResult.actualBytes,
                    message = "Failed to read CSW after writing LBA $lba: expected ${CommandStatusWrapper.CSW_SIZE} bytes, received ${cswResult.actualBytes} bytes"
                )
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
            if (csw.isSuccess && csw.dataResidue > 0) {
                throw UsbShortTransferException(
                    direction = UsbTransferDirection.OUT,
                    expectedBytes = length,
                    actualBytes = length - csw.dataResidue,
                    message = "SCSI device reported residual unwritten data at LBA $lba: expected $length bytes, residue ${csw.dataResidue} bytes"
                )
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
    private fun clearHalt(endpoint: UsbEndpoint?) {
        val conn = connection ?: return
        val epAddr = endpoint?.address ?: return
        try {
            // Standard USB Clear Feature (ENDPOINT_HALT = 0)
            conn.controlTransfer(
                0x02, // Endpoint Recipient
                0x01, // CLEAR_FEATURE
                0x00, // ENDPOINT_HALT
                epAddr,
                null,
                0,
                1000
            )
        } catch (e: Exception) {
            Log.e(TAG, "clearHalt failed on endpoint $epAddr: ${e.message}")
        }
    }

    /**
     * Performs USB Mass Storage Bulk-Only Mass Storage Reset (BOMSR).
     */
    private fun resetRecovery() {
        val conn = connection ?: return
        val ifaceId = usbInterface?.id ?: 0
        try {
            // Bulk-Only Mass Storage Reset Request (0xFF, Class/Interface)
            conn.controlTransfer(
                0x21, // Class Request to Interface
                0xFF, // Bulk-Only Mass Storage Reset
                0,
                ifaceId,
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
            connectionAdapter = null
            usbInterface = null
            inEndpoint = null
            outEndpoint = null
        }
    }
}
