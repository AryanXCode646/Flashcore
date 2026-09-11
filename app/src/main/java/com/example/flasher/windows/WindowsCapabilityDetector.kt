package com.example.flasher.windows

import com.example.iso.IsoDirectoryTree
import com.example.iso.IsoFileEntry
import com.example.iso.IsoFilesystemReader

/**
 * Architecture of the target Windows bootloader.
 */
enum class WindowsArchitecture(val displayName: String, val efiFileName: String) {
    X64("UEFI x86_64 (64-bit AMD64)", "bootx64.efi"),
    ARM64("UEFI AArch64 (ARM64 Snapdragon / Surface)", "bootaa64.efi"),
    IA32("UEFI IA-32 (32-bit x86)", "bootia32.efi"),
    MULTI_ARCH("Multi-Architecture (Dual x64 & x86)", "bootx64.efi"),
    UNKNOWN("Unknown Architecture", "bootx64.efi")
}

/**
 * Installation payload packaging format used by the Windows ISO.
 */
enum class WindowsInstallerType(val displayName: String) {
    WIM("Windows Imaging Format (.wim)"),
    ESD("Electronic Software Download (.esd)"),
    SWM("Pre-Split Windows Imaging Format (.swm)"),
    WIN_PE_ONLY("Windows PE / Recovery Image (boot.wim only)"),
    UNKNOWN("Unknown Installer Payload")
}

/**
 * Comprehensive capabilities detected from a Windows installation image.
 */
data class WindowsCapabilities(
    val volumeLabel: String,
    val arch: WindowsArchitecture,
    val installerType: WindowsInstallerType,
    val installImagePath: String?,
    val installImageSizeBytes: Long,
    val requiresWimSplit: Boolean,
    val splitPartCount: Int,
    val hasUefiBoot: Boolean,
    val uefiLoaderPath: String?,
    val hasLegacyBiosBoot: Boolean,
    val legacyBootMgrPath: String?,
    val bcdPath: String?,
    val editionHint: String,
    val isMultiArch: Boolean,
    val totalPayloadBytes: Long,
    val fileCount: Int,
    val dirCount: Int
)

/**
 * Deep capability detector for Windows ISO images.
 *
 * Does not assume all Windows ISOs are identical. Accurately identifies:
 * - Architecture (x86_64, ARM64, IA-32, Dual-arch)
 * - Payload type (install.wim vs install.esd vs install.swm vs WinPE)
 * - Exact 64-bit WIM size and FAT32 4 GB boundary split requirement
 * - UEFI vs Legacy BIOS boot configuration and BCD paths
 * - Edition identity (Windows 11, Windows 10, Server, WinPE)
 */
object WindowsCapabilityDetector {

    const val FAT32_MAX_FILE_SIZE = 4294967295L // 4 GB - 1 byte

    fun detect(reader: IsoFilesystemReader): WindowsCapabilities {
        val tree = reader.directoryTree
        val allEntries = tree.allEntries
        val fileEntries = allEntries.filter { !it.isDirectory }
        val dirEntries = allEntries.filter { it.isDirectory }

        // 1. Detect Install Image Payload
        val wim = tree.find("/sources/install.wim")
        val esd = tree.find("/sources/install.esd")
        val swm = tree.find("/sources/install.swm")
        val bootWim = tree.find("/sources/boot.wim")

        // Check for multi-architecture subdirectories (e.g. /x64/sources/install.wim)
        val x64Wim = tree.find("/x64/sources/install.wim")
        val x86Wim = tree.find("/x86/sources/install.wim")

        val installerType: WindowsInstallerType
        val installEntry: IsoFileEntry?

        when {
            wim != null -> {
                installerType = WindowsInstallerType.WIM
                installEntry = wim
            }
            esd != null -> {
                installerType = WindowsInstallerType.ESD
                installEntry = esd
            }
            swm != null -> {
                installerType = WindowsInstallerType.SWM
                installEntry = swm
            }
            x64Wim != null || x86Wim != null -> {
                installerType = WindowsInstallerType.WIM
                installEntry = x64Wim ?: x86Wim
            }
            bootWim != null -> {
                installerType = WindowsInstallerType.WIN_PE_ONLY
                installEntry = bootWim
            }
            else -> {
                installerType = WindowsInstallerType.UNKNOWN
                installEntry = null
            }
        }

        val installSize = installEntry?.sizeBytes ?: 0L
        val requiresSplit = (installerType == WindowsInstallerType.WIM || installerType == WindowsInstallerType.ESD) &&
                installSize > FAT32_MAX_FILE_SIZE
        val splitPartCount = if (requiresSplit) {
            ((installSize + 3900L * 1024L * 1024L - 1L) / (3900L * 1024L * 1024L)).toInt().coerceAtLeast(2)
        } else {
            1
        }

        // 2. Detect Architecture & Firmware Support
        val hasX64 = tree.find("/efi/boot/bootx64.efi") != null || tree.find("/x64/efi/boot/bootx64.efi") != null
        val hasArm64 = tree.find("/efi/boot/bootaa64.efi") != null || tree.find("/sources/arm64") != null
        val hasIa32 = tree.find("/efi/boot/bootia32.efi") != null || tree.find("/x86/efi/boot/bootia32.efi") != null
        val isMultiArch = (hasX64 && hasIa32) || (x64Wim != null && x86Wim != null)

        val arch = when {
            isMultiArch -> WindowsArchitecture.MULTI_ARCH
            hasArm64 -> WindowsArchitecture.ARM64
            hasX64 -> WindowsArchitecture.X64
            hasIa32 -> WindowsArchitecture.IA32
            tree.find("/bootmgr.efi") != null -> WindowsArchitecture.X64
            else -> WindowsArchitecture.UNKNOWN
        }

        // 3. Detect Boot Managers and BCD Configuration
        val uefiLoader = tree.find("/efi/boot/${arch.efiFileName}")
            ?: tree.find("/efi/boot/bootx64.efi")
            ?: tree.find("/efi/microsoft/boot/bootmgfw.efi")
            ?: tree.find("/bootmgr.efi")

        val legacyBootMgr = tree.find("/bootmgr")
        val bcdEntry = tree.find("/efi/microsoft/boot/bcd") ?: tree.find("/boot/bcd")

        val hasUefi = uefiLoader != null
        val hasLegacyBios = legacyBootMgr != null

        // 4. Determine Edition Hint
        val label = reader.volumeLabel.uppercase()
        val editionHint = when {
            label.contains("WIN11") || label.contains("23H2") || label.contains("24H2") || label.contains("22H2") -> "Windows 11"
            label.contains("WIN10") || label.contains("CCCOMA_X64") || label.contains("CCCOMA_X86") -> "Windows 10"
            label.contains("SERVER") || label.contains("SSS_X64") -> "Windows Server"
            installerType == WindowsInstallerType.WIN_PE_ONLY -> "Windows PE / Recovery Environment"
            else -> "Windows Setup (${arch.displayName})"
        }

        val totalPayloadBytes = fileEntries.sumOf { it.sizeBytes }

        return WindowsCapabilities(
            volumeLabel = reader.volumeLabel,
            arch = arch,
            installerType = installerType,
            installImagePath = installEntry?.path,
            installImageSizeBytes = installSize,
            requiresWimSplit = requiresSplit,
            splitPartCount = splitPartCount,
            hasUefiBoot = hasUefi,
            uefiLoaderPath = uefiLoader?.path,
            hasLegacyBiosBoot = hasLegacyBios,
            legacyBootMgrPath = legacyBootMgr?.path,
            bcdPath = bcdEntry?.path,
            editionHint = editionHint,
            isMultiArch = isMultiArch,
            totalPayloadBytes = totalPayloadBytes,
            fileCount = fileEntries.size,
            dirCount = dirEntries.size
        )
    }
}
