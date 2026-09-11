package com.ashishsinghbora.flashcore.iso

import com.ashishsinghbora.flashcore.block.BlockDevice
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * Abstraction for reading data at arbitrary byte offsets from an ISO image source.
 */
interface IsoSource {
    val sizeBytes: Long
    fun readAt(offset: Long, buffer: ByteArray, bufferOffset: Int = 0, length: Int = buffer.size): Int
}

/**
 * In-memory byte-array ISO source.
 */
class ByteArrayIsoSource(private val data: ByteArray) : IsoSource {
    override val sizeBytes: Long get() = data.size.toLong()
    override fun readAt(offset: Long, buffer: ByteArray, bufferOffset: Int, length: Int): Int {
        if (offset >= data.size) return -1
        val toCopy = minOf(length.toLong(), data.size - offset).toInt()
        System.arraycopy(data, offset.toInt(), buffer, bufferOffset, toCopy)
        return toCopy
    }
}

/**
 * FileChannel ISO source (for Android ParcelFileDescriptor and local files).
 */
class FileChannelIsoSource(private val channel: FileChannel) : IsoSource {
    override val sizeBytes: Long get() = channel.size()
    override fun readAt(offset: Long, buffer: ByteArray, bufferOffset: Int, length: Int): Int {
        val byteBuffer = ByteBuffer.wrap(buffer, bufferOffset, length)
        return channel.read(byteBuffer, offset)
    }
}

/**
 * BlockDevice ISO source (for reading an ISO directly from a block device or virtual disk).
 */
class BlockDeviceIsoSource(
    private val device: BlockDevice,
    private val totalCapacitySectors: Long? = null,
    private val sectorSizeBytes: Int = 512
) : IsoSource {
    override val sizeBytes: Long = (totalCapacitySectors ?: kotlinx.coroutines.runBlocking { device.capacity().totalSectors }) * sectorSizeBytes

    override fun readAt(offset: Long, buffer: ByteArray, bufferOffset: Int, length: Int): Int {
        val startSector = offset / sectorSizeBytes
        val sectorOffset = (offset % sectorSizeBytes).toInt()
        val sectorsToRead = ((sectorOffset + length + sectorSizeBytes - 1) / sectorSizeBytes).toInt()

        val temp = ByteArray(sectorsToRead * sectorSizeBytes)
        val success = kotlinx.coroutines.runBlocking {
            device.read(startSector, sectorsToRead, temp)
        }
        if (!success) return -1

        val available = minOf(length, temp.size - sectorOffset)
        System.arraycopy(temp, sectorOffset, buffer, bufferOffset, available)
        return available
    }
}

/**
 * ISO File or Directory Entry metadata.
 */
data class IsoFileEntry(
    val path: String,
    val name: String,
    val lba: Long,
    val sizeBytes: Long,
    val isDirectory: Boolean
)

/**
 * Target firmware architectures supported by an ISO bootloader.
 */
enum class FirmwareArchitecture(val displayName: String) {
    X86_64("UEFI x86_64 (64-bit)"),
    AARCH64("UEFI AArch64 (ARM64)"),
    IA32("UEFI IA-32 (32-bit)"),
    ARM32("UEFI ARM 32-bit"),
    RISCV64("UEFI RISC-V 64-bit"),
    BIOS_X86("Legacy BIOS / MBR")
}

/**
 * Boot mechanism detected within the ISO image.
 */
enum class BootMechanism {
    ISOHYBRID_MBR,
    EL_TORITO_BIOS,
    EL_TORITO_UEFI,
    NATIVE_UEFI_TREE
}

/**
 * High-level classification of the operating system payload.
 */
enum class PayloadType(val displayName: String) {
    WINDOWS_SETUP("Windows Setup / PE"),
    LINUX_LIVE_INSTALLER("Linux Live / Installer"),
    GENERIC_BOOTABLE("Generic Bootable Media"),
    NON_BOOTABLE("Non-Bootable ISO")
}

/**
 * Recommended flashing strategy based on detected capabilities.
 */
enum class RecommendedStrategy(val id: String, val displayName: String) {
    RAW_DD("LINUX_RAW_DD", "Direct Raw Sector Copy (dd)"),
    WINDOWS_UEFI("WINDOWS_UEFI", "Windows UEFI (GPT + FAT32)"),
    VENTOY("VENTOY_BOOT", "Ventoy Multi-Boot"),
    GENERIC_EXTRACT("GENERIC_EXTRACT", "Filesystem Extraction")
}

/**
 * Rich capability report extracted dynamically from an ISO image.
 */
data class IsoCapabilities(
    val volumeLabel: String,
    val systemId: String,
    val publisherId: String,
    val totalSizeBytes: Long,
    val isIso9660: Boolean,
    val isJoliet: Boolean,
    val isIsohybrid: Boolean,
    val hasElTorito: Boolean,
    val supportedFirmware: Set<FirmwareArchitecture>,
    val bootMechanisms: Set<BootMechanism>,
    val payloadType: PayloadType,
    val osName: String?,
    val osVersion: String?,
    val distroIdentifier: String?,
    val hasInstallWim: Boolean,
    val installWimSize: Long,
    val installWimPath: String?,
    val requiresWimSplit: Boolean,
    val kernelPath: String?,
    val initramfsPath: String?,
    val rootfsPath: String?,
    val recommendedStrategy: RecommendedStrategy
) {
    val isBootable: Boolean
        get() = bootMechanisms.isNotEmpty() || supportedFirmware.isNotEmpty()
}
