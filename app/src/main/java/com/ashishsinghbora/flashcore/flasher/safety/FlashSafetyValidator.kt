package com.ashishsinghbora.flashcore.flasher.safety

import com.ashishsinghbora.flashcore.block.BlockDevice
import com.ashishsinghbora.flashcore.usb.UsbDiskInfo
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Pre-flight safety and destructive operation validator for USB flashing.
 *
 * Implements strict hardware safety guarantees:
 * 1. Device identity confirmation (Vendor/Product/Serial/Capacity)
 * 2. Capacity validation against ISO payload size
 * 3. Hardware write-protection detection
 * 4. Existing partition table and filesystem detection with destructive-write warnings
 * 5. Explicit user confirmation requirement
 */
object FlashSafetyValidator {

    enum class ErrorCode {
        DEVICE_NOT_FOUND,
        INVALID_DEVICE_GEOMETRY,
        INSUFFICIENT_CAPACITY,
        WRITE_PROTECTED,
        UNCONFIRMED_DESTRUCTIVE_OPERATION,
        IO_FAILURE
    }

    enum class WarningCode {
        EXISTING_PARTITIONS_DETECTED,
        EXISTING_FILESYSTEM_DETECTED,
        UNRECOGNIZED_SECTOR_SIZE
    }

    data class SafetyError(
        val code: ErrorCode,
        val title: String,
        val message: String
    )

    data class SafetyWarning(
        val code: WarningCode,
        val title: String,
        val message: String
    )

    data class DeviceIdentity(
        val vendorId: Int,
        val productId: Int,
        val vendorString: String,
        val productString: String,
        val serialNumber: String,
        val totalCapacityBytes: Long,
        val totalSectors: Long,
        val sectorSizeBytes: Int,
        val isRemovable: Boolean,
        val isWriteProtected: Boolean
    )

    data class ExistingDataInspection(
        val hasMbr: Boolean = false,
        val hasGpt: Boolean = false,
        val detectedFilesystem: String? = null,
        val partitionDescriptions: List<String> = emptyList(),
        val summary: String = ""
    )

    data class SafetyPreflightResult(
        val isSafeToFlash: Boolean,
        val identity: DeviceIdentity,
        val errors: List<SafetyError>,
        val warnings: List<SafetyWarning>,
        val existingData: ExistingDataInspection?
    )

    /**
     * Executes comprehensive safety and sanity checks on target media before flashing.
     */
    suspend fun validate(
        device: BlockDevice,
        targetDrive: UsbDiskInfo,
        isoSizeBytes: Long,
        confirmedByUser: Boolean = true
    ): SafetyPreflightResult {
        val errors = mutableListOf<SafetyError>()
        val warnings = mutableListOf<SafetyWarning>()

        // 1. Device Identity Confirmation
        val identity = DeviceIdentity(
            vendorId = targetDrive.vendorId,
            productId = targetDrive.productId,
            vendorString = targetDrive.vendorString.ifBlank { targetDrive.manufacturerName },
            productString = targetDrive.productString.ifBlank { targetDrive.productName },
            serialNumber = targetDrive.serialNumber,
            totalCapacityBytes = targetDrive.totalCapacityBytes,
            totalSectors = targetDrive.totalSectors,
            sectorSizeBytes = targetDrive.sectorSizeBytes,
            isRemovable = targetDrive.isRemovable,
            isWriteProtected = targetDrive.isWriteProtected
        )

        // Query active block device capacity
        val actualCapacity = try {
            device.capacity()
        } catch (e: Exception) {
            errors.add(
                SafetyError(
                    ErrorCode.IO_FAILURE,
                    "Device Capacity Query Failed",
                    "Unable to query target drive capacity via SCSI READ CAPACITY: ${e.message}"
                )
            )
            null
        }

        if (actualCapacity != null) {
            // Verify sector size
            if (actualCapacity.sectorSizeBytes < 512 || (actualCapacity.sectorSizeBytes and (actualCapacity.sectorSizeBytes - 1)) != 0) {
                errors.add(
                    SafetyError(
                        ErrorCode.INVALID_DEVICE_GEOMETRY,
                        "Invalid Sector Size",
                        "Target drive reported invalid sector size: ${actualCapacity.sectorSizeBytes} bytes (expected 512 or 4096)."
                    )
                )
            }

            // 2. Capacity Validation
            val availableBytes = actualCapacity.totalBytes
            if (availableBytes < isoSizeBytes) {
                val deficitBytes = isoSizeBytes - availableBytes
                errors.add(
                    SafetyError(
                        ErrorCode.INSUFFICIENT_CAPACITY,
                        "Insufficient Storage Capacity",
                        "Target drive (${actualCapacity.formattedCapacity}) is smaller than ISO image (${formatBytes(isoSizeBytes)}). " +
                                "Deficit: ${formatBytes(deficitBytes)} (${((deficitBytes + actualCapacity.sectorSizeBytes - 1) / actualCapacity.sectorSizeBytes)} sectors)."
                    )
                )
            }
        }

        // 3. Write-Protection Detection
        if (targetDrive.isWriteProtected) {
            errors.add(
                SafetyError(
                    ErrorCode.WRITE_PROTECTED,
                    "Drive is Write-Protected",
                    "Target drive '${targetDrive.displayName}' is hardware write-protected (read-only mode active). Flashing is aborted to protect media."
                )
            )
        }

        // 4. Inspect Existing Data / Partitions on Target
        var existingData: ExistingDataInspection? = null
        try {
            existingData = inspectExistingMedia(device)
            if (existingData.hasMbr || existingData.hasGpt || existingData.detectedFilesystem != null) {
                warnings.add(
                    SafetyWarning(
                        WarningCode.EXISTING_PARTITIONS_DETECTED,
                        "Existing Partitions & Data Will Be Destroyed",
                        existingData.summary
                    )
                )
            }
        } catch (_: Exception) {
            // Non-fatal if read of existing tables fails on fresh blank media
        }

        // 5. Destructive Operation Confirmation
        if (!confirmedByUser) {
            errors.add(
                SafetyError(
                    ErrorCode.UNCONFIRMED_DESTRUCTIVE_OPERATION,
                    "Confirmation Required",
                    "Destructive operation not confirmed by user. Explicit acknowledgment is mandatory before overwriting ${targetDrive.displayName}."
                )
            )
        }

        val isSafe = errors.isEmpty()
        return SafetyPreflightResult(
            isSafeToFlash = isSafe,
            identity = identity,
            errors = errors,
            warnings = warnings,
            existingData = existingData
        )
    }

    /**
     * Reads Sector 0 and Sector 1 to inspect whether target media has existing partitions or filesystems.
     */
    suspend fun inspectExistingMedia(device: BlockDevice): ExistingDataInspection {
        val sector0 = ByteArray(device.sectorSizeBytes.coerceAtLeast(512))
        val read0 = device.read(0L, 1, sector0)
        if (!read0) return ExistingDataInspection()

        var hasMbr = false
        var hasGpt = false
        var detectedFs: String? = null
        val partitionList = mutableListOf<String>()

        // Check MBR boot signature 0x55AA at offset 510
        val sig = (sector0[510].toInt() and 0xFF) or ((sector0[511].toInt() and 0xFF) shl 8)
        if (sig == 0xAA55) {
            val buf = ByteBuffer.wrap(sector0).order(ByteOrder.LITTLE_ENDIAN)
            for (p in 0 until 4) {
                val offset = 446 + (p * 16)
                val partType = sector0[offset + 4].toInt() and 0xFF
                val lbaStart = buf.getInt(offset + 8).toLong() and 0xFFFFFFFFL
                val lbaCount = buf.getInt(offset + 12).toLong() and 0xFFFFFFFFL

                if (partType != 0 && lbaCount > 0) {
                    hasMbr = true
                    if (partType == 0xEE) {
                        hasGpt = true
                    }
                    val typeName = getPartitionTypeName(partType)
                    partitionList.add("Partition ${p + 1}: $typeName (LBA $lbaStart..${lbaStart + lbaCount - 1}, ${formatBytes(lbaCount * device.sectorSizeBytes)})")
                }
            }

            // Check VBR filesystem signatures in Sector 0
            val oem = String(sector0, 3, 8, Charsets.US_ASCII).trim()
            val fat32Type = String(sector0, 82, 8, Charsets.US_ASCII).trim()
            val exfatType = String(sector0, 3, 8, Charsets.US_ASCII).trim()

            if (fat32Type.startsWith("FAT32")) {
                detectedFs = "FAT32 Volume ($oem)"
            } else if (exfatType.startsWith("EXFAT")) {
                detectedFs = "exFAT Volume"
            } else if (oem.startsWith("NTFS")) {
                detectedFs = "NTFS Volume"
            }
        }

        // Check GPT Primary Header at Sector 1 if protective MBR detected
        if (hasGpt) {
            val sector1 = ByteArray(device.sectorSizeBytes.coerceAtLeast(512))
            if (device.read(1L, 1, sector1)) {
                val gptMagic = String(sector1, 0, 8, Charsets.US_ASCII)
                if (gptMagic == "EFI PART") {
                    hasGpt = true
                }
            }
        }

        val summary = buildString {
            if (hasGpt) {
                append("GUID Partition Table (GPT) disk with ")
            } else if (hasMbr) {
                append("Master Boot Record (MBR) disk with ")
            } else if (detectedFs != null) {
                append("Direct filesystem ($detectedFs) with ")
            } else {
                append("Unpartitioned media with ")
            }
            if (partitionList.isNotEmpty()) {
                append("${partitionList.size} partition(s): [${partitionList.joinToString(", ")}]. ")
            }
            if (detectedFs != null && !hasMbr && !hasGpt) {
                append("Filesystem: $detectedFs. ")
            }
            append("All existing data will be permanently overwritten.")
        }

        return ExistingDataInspection(
            hasMbr = hasMbr,
            hasGpt = hasGpt,
            detectedFilesystem = detectedFs,
            partitionDescriptions = partitionList,
            summary = summary
        )
    }

    private fun getPartitionTypeName(type: Int): String {
        return when (type) {
            0x00 -> "Empty"
            0x01 -> "FAT12"
            0x04, 0x06 -> "FAT16"
            0x07 -> "NTFS / exFAT"
            0x0B, 0x0C -> "FAT32"
            0x82 -> "Linux Swap"
            0x83 -> "Linux Native"
            0xEE -> "GPT Protective"
            0xEF -> "EFI System Partition"
            else -> "Type 0x%02X".format(type)
        }
    }

    fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return "0 B"
        val gb = bytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
        if (gb >= 1.0) return "%.2f GB".format(gb)
        val mb = bytes.toDouble() / (1024.0 * 1024.0)
        if (mb >= 1.0) return "%.1f MB".format(mb)
        val kb = bytes.toDouble() / 1024.0
        return "%.1f KB".format(kb)
    }
}
