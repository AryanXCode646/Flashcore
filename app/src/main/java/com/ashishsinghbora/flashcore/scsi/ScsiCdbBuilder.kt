package com.ashishsinghbora.flashcore.scsi

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

        require(blockSize > 0) { "Read Capacity 10 block size must be > 0: $blockSize" }
        require(lastLba < Long.MAX_VALUE) { "Read Capacity 10 maximum LBA overflows 64-bit capacity math: $lastLba" }
        val totalBlocks = lastLba + 1L
        require(totalBlocks <= Long.MAX_VALUE / blockSize.toLong()) {
            "Read Capacity 10 total bytes overflow: maxLba=$lastLba, blockSize=$blockSize"
        }

        val totalCapacity = totalBlocks * blockSize.toLong()
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

        require(blockSize > 0) { "Read Capacity 16 block size must be > 0: $blockSize" }
        require(lastLba >= 0L && lastLba < Long.MAX_VALUE) {
            "Read Capacity 16 maximum LBA is outside the supported signed 64-bit range: $lastLba"
        }
        val totalBlocks = lastLba + 1L
        require(totalBlocks <= Long.MAX_VALUE / blockSize.toLong()) {
            "Read Capacity 16 total bytes overflow: maxLba=$lastLba, blockSize=$blockSize"
        }

        val totalCapacity = totalBlocks * blockSize.toLong()
        return ReadCapacityResponse(
            maxLba = lastLba,
            blockSizeBytes = blockSize,
            totalCapacityBytes = totalCapacity
        )
    }

    /**
     * Returns the human-readable standard name of a SCSI opcode.
     */
    fun getOpcodeName(opcode: Byte): String = when (opcode) {
        OP_TEST_UNIT_READY -> "TEST_UNIT_READY"
        OP_REQUEST_SENSE -> "REQUEST_SENSE"
        OP_INQUIRY -> "INQUIRY"
        OP_MODE_SENSE_6 -> "MODE_SENSE_6"
        OP_PREVENT_ALLOW_MEDIUM_REMOVAL -> "PREVENT_ALLOW_MEDIUM_REMOVAL"
        OP_READ_CAPACITY_10 -> "READ_CAPACITY_10"
        OP_READ_10 -> "READ_10"
        OP_WRITE_10 -> "WRITE_10"
        OP_SYNCHRONIZE_CACHE_10 -> "SYNCHRONIZE_CACHE_10"
        OP_READ_16 -> "READ_16"
        OP_WRITE_16 -> "WRITE_16"
        OP_READ_CAPACITY_16 -> "READ_CAPACITY_16"
        else -> "OPCODE_0x" + Integer.toHexString(opcode.toInt() and 0xFF).padStart(2, '0').uppercase()
    }

    data class SenseDataResponse(
        val responseCode: Int,
        val senseKey: Int,
        val senseKeyDescription: String,
        val additionalSenseCode: Int,
        val additionalSenseCodeQualifier: Int,
        val ascDescription: String,
        val isValid: Boolean = false,
        val information: Long = 0L,
        val commandSpecificInfo: Long = 0L,
        val additionalSenseLength: Int = 0,
        val isDescriptorFormat: Boolean = false,
        val rawBytes: ByteArray = ByteArray(0)
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is SenseDataResponse) return false
            if (responseCode != other.responseCode) return false
            if (senseKey != other.senseKey) return false
            if (senseKeyDescription != other.senseKeyDescription) return false
            if (additionalSenseCode != other.additionalSenseCode) return false
            if (additionalSenseCodeQualifier != other.additionalSenseCodeQualifier) return false
            if (ascDescription != other.ascDescription) return false
            if (isValid != other.isValid) return false
            if (information != other.information) return false
            if (commandSpecificInfo != other.commandSpecificInfo) return false
            if (additionalSenseLength != other.additionalSenseLength) return false
            if (isDescriptorFormat != other.isDescriptorFormat) return false
            if (!rawBytes.contentEquals(other.rawBytes)) return false
            return true
        }

        override fun hashCode(): Int {
            var result = responseCode
            result = 31 * result + senseKey
            result = 31 * result + senseKeyDescription.hashCode()
            result = 31 * result + additionalSenseCode
            result = 31 * result + additionalSenseCodeQualifier
            result = 31 * result + ascDescription.hashCode()
            result = 31 * result + isValid.hashCode()
            result = 31 * result + information.hashCode()
            result = 31 * result + commandSpecificInfo.hashCode()
            result = 31 * result + additionalSenseLength
            result = 31 * result + isDescriptorFormat.hashCode()
            result = 31 * result + rawBytes.contentHashCode()
            return result
        }

        fun formattedDiagnostic(): String {
            val formatStr = if (isDescriptorFormat) "Descriptor" else "Fixed"
            val validStr = if (isValid) " [Valid Info: 0x${java.lang.Long.toHexString(information)}]" else ""
            return "Sense: $senseKeyDescription ($formatStr, Key=0x${Integer.toHexString(senseKey)}), $ascDescription (ASC=0x${Integer.toHexString(additionalSenseCode).padStart(2, '0')}, ASCQ=0x${Integer.toHexString(additionalSenseCodeQualifier).padStart(2, '0')})$validStr"
        }
    }

    /**
     * Parses SCSI fixed-format (0x70, 0x71) or descriptor-format (0x72, 0x73) sense data.
     * Safely handles truncated data, malformed lengths, and unknown codes without throwing exceptions.
     */
    fun parseRequestSense(data: ByteArray): SenseDataResponse {
        if (data.isEmpty()) {
            return SenseDataResponse(
                responseCode = 0,
                senseKey = 0,
                senseKeyDescription = "NO SENSE",
                additionalSenseCode = 0,
                additionalSenseCodeQualifier = 0,
                ascDescription = "No Sense Data",
                rawBytes = data
            )
        }

        val rawResponseCode = data[0].toInt() and 0xFF
        val responseCode = rawResponseCode and 0x7F
        val isValid = (rawResponseCode and 0x80) != 0
        val isDescriptorFormat = (responseCode == 0x72 || responseCode == 0x73)

        val senseKey: Int
        var asc = 0
        var ascq = 0
        var information = 0L
        var commandSpecificInfo = 0L
        val additionalSenseLength = if (data.size > 7) data[7].toInt() and 0xFF else 0

        if (isDescriptorFormat) {
            // Descriptor format: Byte 1 is Sense Key, Byte 2 is ASC, Byte 3 is ASCQ
            senseKey = if (data.size > 1) data[1].toInt() and 0x0F else 0
            asc = if (data.size > 2) data[2].toInt() and 0xFF else 0
            ascq = if (data.size > 3) data[3].toInt() and 0xFF else 0

            // Parse optional sense data descriptors starting at offset 8
            var offset = 8
            val endOffset = minOf(data.size, 8 + additionalSenseLength)
            while (offset + 2 <= endOffset) {
                val descType = data[offset].toInt() and 0xFF
                val descLen = data[offset + 1].toInt() and 0xFF
                if (descType == 0x00 && descLen >= 10 && offset + 12 <= endOffset) {
                    // Information descriptor: 8-byte information
                    val buf = ByteBuffer.wrap(data, offset + 4, 8).order(ByteOrder.BIG_ENDIAN)
                    information = buf.long
                } else if (descType == 0x01 && descLen >= 6 && offset + 8 <= endOffset) {
                    // Command-specific information descriptor: 4-byte
                    val buf = ByteBuffer.wrap(data, offset + 4, 4).order(ByteOrder.BIG_ENDIAN)
                    commandSpecificInfo = buf.int.toLong() and 0xFFFFFFFFL
                }
                offset += 2 + descLen
            }
        } else {
            // Fixed format: Byte 2 is Sense Key, Bytes 3..6 is Info, Bytes 8..11 is CmdSpecific, Byte 12 is ASC, Byte 13 is ASCQ
            senseKey = if (data.size > 2) data[2].toInt() and 0x0F else 0
            if (data.size >= 7 && isValid) {
                val buf = ByteBuffer.wrap(data, 3, 4).order(ByteOrder.BIG_ENDIAN)
                information = buf.int.toLong() and 0xFFFFFFFFL
            }
            if (data.size >= 12) {
                val buf = ByteBuffer.wrap(data, 8, 4).order(ByteOrder.BIG_ENDIAN)
                commandSpecificInfo = buf.int.toLong() and 0xFFFFFFFFL
            }
            if (data.size > 12) {
                asc = data[12].toInt() and 0xFF
            }
            if (data.size > 13) {
                ascq = data[13].toInt() and 0xFF
            }
        }

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
            0x0C -> "VOLUME OVERFLOW"
            0x0D -> "MISCOMPARE"
            0x0E -> "MISCOMPARE"
            else -> "UNKNOWN SENSE (0x${Integer.toHexString(senseKey)})"
        }

        val ascDesc = when (asc) {
            0x00 -> when (ascq) {
                0x00 -> "No additional sense information"
                0x01 -> "Filemark detected"
                0x02 -> "End-of-partition/medium detected"
                0x06 -> "I/O process terminated"
                else -> "ASC: 0x00, ASCQ: 0x${Integer.toHexString(ascq).padStart(2, '0')}"
            }
            0x04 -> when (ascq) {
                0x00 -> "Logical unit not ready, cause not reportable"
                0x01 -> "Logical unit is in process of becoming ready"
                0x02 -> "Logical unit not ready, initializing command required"
                0x03 -> "Logical unit not ready, manual intervention required"
                0x04 -> "Logical unit not ready, format in progress"
                else -> "Logical unit not ready"
            }
            0x11 -> when (ascq) {
                0x00 -> "Unrecovered read error"
                0x01 -> "Read retries exhausted"
                0x02 -> "Error too long to correct"
                else -> "Unrecovered read error"
            }
            0x15 -> when (ascq) {
                0x01 -> "Mechanical positioning error"
                0x02 -> "Positioning error detected by read of medium"
                else -> "Random positioning error"
            }
            0x17 -> "Recovered data with no error correction applied"
            0x18 -> "Recovered data with error correction applied"
            0x20 -> "Invalid command operation code"
            0x21 -> when (ascq) {
                0x00 -> "Logical block address out of range"
                0x01 -> "Invalid element address"
                else -> "Logical block address out of range"
            }
            0x24 -> "Invalid field in CDB"
            0x25 -> "Logical unit not supported"
            0x26 -> "Invalid field in parameter list"
            0x27 -> "Write protected"
            0x28 -> "Not ready to ready change, medium may have changed"
            0x29 -> when (ascq) {
                0x01 -> "Power on occurred"
                0x02 -> "SCSI bus reset occurred"
                0x03 -> "Bus device reset function occurred"
                0x04 -> "Device internal reset"
                else -> "Power on, reset, or bus device reset occurred"
            }
            0x2A -> when (ascq) {
                0x01 -> "Mode parameters changed"
                else -> "Parameters changed"
            }
            0x3A -> when (ascq) {
                0x01 -> "Medium not present, tray closed"
                0x02 -> "Medium not present, tray open"
                else -> "Medium not present"
            }
            0x3F -> when (ascq) {
                0x01 -> "Microcode has been changed"
                else -> "Target operating conditions have changed"
            }
            0x44 -> "Internal target failure"
            0x47 -> "SCSI parity error"
            0x4E -> "Overlapped commands attempted"
            else -> "ASC: 0x${Integer.toHexString(asc).padStart(2, '0')}, ASCQ: 0x${Integer.toHexString(ascq).padStart(2, '0')}"
        }

        return SenseDataResponse(
            responseCode = responseCode,
            senseKey = senseKey,
            senseKeyDescription = keyDesc,
            additionalSenseCode = asc,
            additionalSenseCodeQualifier = ascq,
            ascDescription = ascDesc,
            isValid = isValid,
            information = information,
            commandSpecificInfo = commandSpecificInfo,
            additionalSenseLength = additionalSenseLength,
            isDescriptorFormat = isDescriptorFormat,
            rawBytes = data.copyOf()
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
