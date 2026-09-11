package com.ashishsinghbora.flashcore.usb

import android.hardware.usb.UsbDevice

/**
 * Metadata and hardware descriptors for an enumerated USB Mass Storage Device.
 */
data class UsbDiskInfo(
    val device: UsbDevice?,
    val vendorId: Int,
    val productId: Int,
    val manufacturerName: String,
    val productName: String,
    val vendorString: String,
    val productString: String,
    val revision: String,
    val serialNumber: String,
    val totalCapacityBytes: Long,
    val totalSectors: Long,
    val sectorSizeBytes: Int,
    val isRemovable: Boolean,
    val isWriteProtected: Boolean,
    val hasPermission: Boolean
) {
    val formattedCapacity: String
        get() {
            if (totalCapacityBytes <= 0L) return "Unknown Capacity"
            val gb = totalCapacityBytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
            return if (gb >= 1.0) {
                "%.2f GB".format(gb)
            } else {
                val mb = totalCapacityBytes.toDouble() / (1024.0 * 1024.0)
                "%.1f MB".format(mb)
            }
        }

    val displayName: String
        get() {
            val name = when {
                productString.isNotBlank() && vendorString.isNotBlank() -> "$vendorString $productString"
                productName.isNotBlank() -> productName
                device?.productName != null -> device.productName!!
                else -> "USB Mass Storage Drive"
            }
            return "$name ($formattedCapacity)"
        }
}

