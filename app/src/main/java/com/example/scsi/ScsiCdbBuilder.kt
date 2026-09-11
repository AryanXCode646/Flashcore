package com.example.scsi

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * SCSI-2 / SPC-4 / SBC-3 Command Descriptor Block (CDB) Builder and Response Parser.
 *
 * All SCSI CDBs use Network Byte Order (Big-Endian) for multi-byte LBA and Transfer Length fields.
 */
object ScsiCdbBuilder {

    // SCSI Command OpCodes
    const val OP_TEST_UNIT_READY: Byte = 0x00
    const val OP_REQUEST_SENSE: Byte = 0x03
    const val OP_INQUIRY: Byte = 0x12
    const val OP_MODE_SENSE_6: Byte = 0x1A
    const val OP_PREVENT_ALLOW_MEDIUM_REMOVAL: Byte = 0x1E
    const val OP_READ_CAPACITY_10: Byte = 0x25
    const val OP_READ_10: Byte = 0x28
    const val OP_WRITE_10: Byte = 0x2A
    const val OP_SYNCHRONIZE_CACHE_10: Byte = 0x35
    const val OP_READ_16: Byte = 0x88.toByte()
    const val OP_WRITE_16: Byte = 0x8A.toByte()
    const val OP_READ_CAPACITY_16: Byte = 0x9E.toByte()

    /**
     * Builds TEST_UNIT_READY CDB (6 bytes).
     * Tests if the logical unit is ready for data transfer operations.
     */
    fun testUnitReady(lun: Byte = 0): ByteArray {
        val cdb = ByteArray(6)
        cdb[0] = OP_TEST_UNIT_READY
        cdb[1] = ((lun.toInt() and 0x07) shl 5).toByte()
        return cdb
    }

    /**
     * Builds REQUEST_SENSE CDB (6 bytes).
     * Queries sense data (error reason) after a command returns a CHECK CONDITION status.
     * Standard sense response is 18 bytes.
     */
    fun requestSense(allocationLength: Int = 18, lun: Byte = 0): ByteArray {
        val cdb = ByteArray(6)
        cdb[0] = OP_REQUEST_SENSE
        cdb[1] = ((lun.toInt() and 0x07) shl 5).toByte()
        cdb[4] = allocationLength.toByte()
        return cdb
    }

    /**
     * Builds INQUIRY CDB (6 bytes).
     * Requests device parameters (Vendor, Product ID, Revision, Removable flag).
     * Standard response is 36 bytes.
     */
    fun inquiry(allocationLength: Int = 36, lun: Byte = 0): ByteArray {
        val cdb = ByteArray(6)
        cdb[0] = OP_INQUIRY
        cdb[1] = ((lun.toInt() and 0x07) shl 5).toByte()
        cdb[4] = allocationLength.toByte()
        return cdb
    }

    /**
     * Builds READ_CAPACITY_10 CDB (10 bytes).
     * Queries the total number of blocks (Max LBA) and sector block size in bytes (e.g. 512 or 4096).
     * Response is 8 bytes: [0..3: Last LBA], [4..7: Block Length in Bytes].
     */
    fun readCapacity10(lun: Byte = 0): ByteArray {
        val cdb = ByteArray(10)
        cdb[0] = OP_READ_CAPACITY_10
        cdb[1] = ((lun.toInt() and 0x07) shl 5).toByte()
        return cdb
    }

    /**
     * Builds READ_CAPACITY_16 CDB (16 bytes).
     * Queries drives > 2 TB using 64-bit LBA and 32-bit Service Action In (0x10).
     * Response is 32 bytes.
     */
    fun readCapacity16(allocationLength: Int = 32, lun: Byte = 0): ByteArray {
        val cdb = ByteArray(16)
        cdb[0] = OP_READ_CAPACITY_16
        cdb[1] = (0x10 or ((lun.toInt() and 0x07) shl 5)).toByte() // Service Action 0x10
        val buf = ByteBuffer.wrap(cdb).order(ByteOrder.BIG_ENDIAN)
        buf.position(10)
        buf.putInt(allocationLength)
        return cdb
    }

    /**
     * Builds READ_10 CDB (10 bytes).
     * Reads [blockCount] sectors starting at 32-bit [lba].
     */
    fun read10(lba: Long, blockCount: Int, lun: Byte = 0): ByteArray {
        require(lba in 0..0xFFFFFFFFL) { "LBA out of 32-bit range for READ_10: $lba" }
        require(blockCount in 1..0xFFFF) { "Block count out of 16-bit range: $blockCount" }

        val cdb = ByteArray(10)
        cdb[0] = OP_READ_10
        cdb[1] = ((lun.toInt() and 0x07) shl 5).toByte()

        // 32-bit LBA in Big-Endian
        cdb[2] = ((lba shr 24) and 0xFF).toByte()
        cdb[3] = ((lba shr 16) and 0xFF).toByte()
        cdb[4] = ((lba shr 8) and 0xFF).toByte()
        cdb[5] = (lba and 0xFF).toByte()

        // 16-bit Transfer Block Count in Big-Endian
        cdb[7] = ((blockCount shr 8) and 0xFF).toByte()
        cdb[8] = (blockCount and 0xFF).toByte()

        return cdb
    }

    /**
     * Builds WRITE_10 CDB (10 bytes).
     * Writes [blockCount] sectors starting at 32-bit [lba].
     */
    fun write10(lba: Long, blockCount: Int, lun: Byte = 0): ByteArray {
        require(lba in 0..0xFFFFFFFFL) { "LBA out of 32-bit range for WRITE_10: $lba" }
        require(blockCount in 1..0xFFFF) { "Block count out of 16-bit range: $blockCount" }

        val cdb = ByteArray(10)
        cdb[0] = OP_WRITE_10
        cdb[1] = ((lun.toInt() and 0x07) shl 5).toByte()

        // 32-bit LBA in Big-Endian
        cdb[2] = ((lba shr 24) and 0xFF).toByte()
        cdb[3] = ((lba shr 16) and 0xFF).toByte()
        cdb[4] = ((lba shr 8) and 0xFF).toByte()
        cdb[5] = (lba and 0xFF).toByte()

        // 16-bit Transfer Block Count in Big-Endian
        cdb[7] = ((blockCount shr 8) and 0xFF).toByte()
        cdb[8] = (blockCount and 0xFF).toByte()

        return cdb
    }

    /**
     * Builds READ_16 CDB (16 bytes).
     * Reads [blockCount] sectors starting at 64-bit [lba].
     */
    fun read16(lba: Long, blockCount: Long, lun: Byte = 0): ByteArray {
        require(lba >= 0) { "LBA must be non-negative for READ_16: $lba" }
        require(blockCount in 1..0xFFFFFFFFL) { "Block count out of 32-bit range: $blockCount" }

        val cdb = ByteArray(16)
        cdb[0] = OP_READ_16
        cdb[1] = ((lun.toInt() and 0x07) shl 5).toByte()

        // 64-bit LBA in Big-Endian
        for (i in 0..7) {
            cdb[2 + i] = ((lba ushr ((7 - i) * 8)) and 0xFF).toByte()
        }

        // 32-bit Transfer Length in Big-Endian
        for (i in 0..3) {
            cdb[10 + i] = ((blockCount ushr ((3 - i) * 8)) and 0xFF).toByte()
        }

        return cdb
    }

    /**
     * Builds WRITE_16 CDB (16 bytes).
     * Writes [blockCount] sectors starting at 64-bit [lba].
     */
    fun write16(lba: Long, blockCount: Long, lun: Byte = 0): ByteArray {
        require(lba >= 0) { "LBA must be non-negative for WRITE_16: $lba" }
        require(blockCount in 1..0xFFFFFFFFL) { "Block count out of 32-bit range: $blockCount" }

        val cdb = ByteArray(16)
        cdb[0] = OP_WRITE_16
        cdb[1] = ((lun.toInt() and 0x07) shl 5).toByte()

        // 64-bit LBA in Big-Endian
        for (i in 0..7) {
            cdb[2 + i] = ((lba ushr ((7 - i) * 8)) and 0xFF).toByte()
        }

        // 32-bit Transfer Length in Big-Endian
        for (i in 0..3) {
            cdb[10 + i] = ((blockCount ushr ((3 - i) * 8)) and 0xFF).toByte()
        }

        return cdb
    }

    /**
     * Builds SYNCHRONIZE_CACHE_10 CDB (10 bytes).
     * Flushes device write cache to physical NAND / media.
     */
    fun synchronizeCache10(lun: Byte = 0): ByteArray {
        val cdb = ByteArray(10)
        cdb[0] = OP_SYNCHRONIZE_CACHE_10
        cdb[1] = ((lun.toInt() and 0x07) shl 5).toByte()
        return cdb
    }

    /**
     * Builds MODE_SENSE_6 CDB (6 bytes) for Page 0x3F (All pages) or 0x01.
     * Used to detect Write-Protect status.
     */
    fun modeSense6(pageCode: Byte = 0x3F, allocationLength: Int = 192, lun: Byte = 0): ByteArray {
        val cdb = ByteArray(6)
        cdb[0] = OP_MODE_SENSE_6
        cdb[1] = ((lun.toInt() and 0x07) shl 5).toByte()
        cdb[2] = pageCode
        cdb[4] = allocationLength.toByte()
        return cdb
    }

    /**
     * Builds PREVENT_ALLOW_MEDIUM_REMOVAL CDB (6 bytes).
     * Locks or unlocks the drive eject mechanism.
     */
    fun preventAllowMediumRemoval(prevent: Boolean, lun: Byte = 0): ByteArray {
        val cdb = ByteArray(6)
        cdb[0] = OP_PREVENT_ALLOW_MEDIUM_REMOVAL
        cdb[1] = ((lun.toInt() and 0x07) shl 5).toByte()
        cdb[4] = if (prevent) 0x01 else 0x00
        return cdb
    }

    // --- Response Parsers ---

    data class InquiryResponse(
        val peripheralDeviceType: Int,
        val isRemovable: Boolean,
        val vendorId: String,
        val productId: String,
        val productRevision: String
    )

    fun parseInquiry(data: ByteArray): InquiryResponse {
        require(data.size >= 36) { "Inquiry data too short (${data.size} bytes, expected >= 36)" }
        val peripheralType = data[0].toInt() and 0x1F
        val isRemovable = (data[1].toInt() and 0x80) != 0

        val vendor = String(data, 8, 8, Charsets.US_ASCII).trim()
        val product = String(data, 16, 16, Charsets.US_ASCII).trim()
        val rev = String(data, 32, 4, Charsets.US_ASCII).trim()

        return InquiryResponse(
            peripheralDeviceType = peripheralType,
            isRemovable = isRemovable,
            vendorId = vendor,
            productId = product,
            productRevision = rev
        )
    }

    data class ReadCapacityResponse(
        val maxLba: Long,
        val blockSizeBytes: Int,
        val totalCapacityBytes: Long
    )

    fun parseReadCapacity10(data: ByteArray): ReadCapacityResponse {
        require(data.size >= 8) { "Read Capacity 10 data too short (${data.size} bytes)" }
        val buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
        val lastLba = buf.int.toLong() and 0xFFFFFFFFL
        val blockSize = buf.int

        val totalCapacity = (lastLba + 1L) * blockSize
        return ReadCapacityResponse(
            maxLba = lastLba,
            blockSizeBytes = blockSize,
            totalCapacityBytes = totalCapacity
        )
    }

    fun parseReadCapacity16(data: ByteArray): ReadCapacityResponse {
        require(data.size >= 32) { "Read Capacity 16 data too short (${data.size} bytes)" }
        val buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
        val lastLba = buf.long
        val blockSize = buf.int

        val totalCapacity = (lastLba + 1L) * blockSize
        return ReadCapacityResponse(
            maxLba = lastLba,
            blockSizeBytes = blockSize,
            totalCapacityBytes = totalCapacity
        )
    }

    data class SenseDataResponse(
        val responseCode: Int,
        val senseKey: Int,
        val senseKeyDescription: String,
        val additionalSenseCode: Int,
        val additionalSenseCodeQualifier: Int,
        val ascDescription: String
    )

    fun parseRequestSense(data: ByteArray): SenseDataResponse {
        if (data.size < 14) {
            return SenseDataResponse(
                responseCode = 0,
                senseKey = 0,
                senseKeyDescription = "No Sense Data",
                additionalSenseCode = 0,
                additionalSenseCodeQualifier = 0,
                ascDescription = "Unknown"
            )
        }
        val responseCode = data[0].toInt() and 0x7F
        val senseKey = data[2].toInt() and 0x0F
        val asc = data[12].toInt() and 0xFF
        val ascq = data[13].toInt() and 0xFF

        val keyDesc = when (senseKey) {
            0x00 -> "NO SENSE"
            0x01 -> "RECOVERED ERROR"
            0x02 -> "NOT READY"
            0x03 -> "MEDIUM ERROR"
            0x04 -> "HARDWARE ERROR"
            0x05 -> "ILLEGAL REQUEST"
            0x06 -> "UNIT ATTENTION"
            0x07 -> "DATA PROTECT"
            0x08 -> "BLANK CHECK"
            0x09 -> "VENDOR SPECIFIC"
            0x0A -> "COPY ABORTED"
            0x0B -> "ABORTED COMMAND"
            0x0E -> "MISCOMPARE"
            else -> "UNKNOWN SENSE (0x${Integer.toHexString(senseKey)})"
        }

        val ascDesc = when (asc) {
            0x04 -> when (ascq) {
                0x01 -> "Logical unit is in process of becoming ready"
                0x02 -> "Logical unit not ready, initializing command required"
                else -> "Logical unit not ready"
            }
            0x28 -> "Not ready to ready change, medium may have changed"
            0x27 -> "Write protected"
            0x29 -> "Power on, reset, or bus device reset occurred"
            0x3A -> "Medium not present"
            else -> "ASC: 0x${Integer.toHexString(asc).padStart(2, '0')}, ASCQ: 0x${Integer.toHexString(ascq).padStart(2, '0')}"
        }

        return SenseDataResponse(
            responseCode = responseCode,
            senseKey = senseKey,
            senseKeyDescription = keyDesc,
            additionalSenseCode = asc,
            additionalSenseCodeQualifier = ascq,
            ascDescription = ascDesc
        )
    }

    data class ModeSenseResponse(
        val modeDataLength: Int,
        val mediumType: Int,
        val isWriteProtected: Boolean,
        val blockDescriptorLength: Int
    )

    fun parseModeSense6(data: ByteArray): ModeSenseResponse {
        if (data.size < 4) {
            return ModeSenseResponse(
                modeDataLength = 0,
                mediumType = 0,
                isWriteProtected = false,
                blockDescriptorLength = 0
            )
        }
        val length = data[0].toInt() and 0xFF
        val mediumType = data[1].toInt() and 0xFF
        val deviceSpecific = data[2].toInt() and 0xFF
        val isWriteProtected = (deviceSpecific and 0x80) != 0
        val blockDescLen = data[3].toInt() and 0xFF

        return ModeSenseResponse(
            modeDataLength = length,
            mediumType = mediumType,
            isWriteProtected = isWriteProtected,
            blockDescriptorLength = blockDescLen
        )
    }
}
