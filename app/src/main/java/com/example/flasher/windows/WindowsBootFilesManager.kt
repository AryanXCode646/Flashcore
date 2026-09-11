package com.example.flasher.windows

import com.example.fat32.Fat32Writer

/**
 * Result of UEFI boot files inspection and signature validation.
 */
data class BootVerificationResult(
    val isBootable: Boolean,
    val uefiLoaderPath: String?,
    val uefiLoaderSize: Long,
    val hasValidPeSignature: Boolean,
    val bcdPath: String?,
    val bcdSize: Long,
    val hasValidBcdSignature: Boolean,
    val bootMgrPath: String?,
    val verifiedFiles: List<String>,
    val errorMessage: String? = null
)

/**
 * Manages UEFI boot loaders, BCD configuration hives, and binary signature validation.
 *
 * Windows ISO variations:
 * - Some ISOs lack the default removable-media UEFI path (/efi/boot/bootx64.efi) and only contain
 *   /bootmgr.efi or /efi/microsoft/boot/bootmgfw.efi.
 * - Some ISOs store the BCD hive exclusively at /boot/bcd rather than /efi/microsoft/boot/bcd.
 *
 * This manager resolves, copies fallback boot structures if needed, and verifies the PE (MZ)
 * and BCD (regf) magic signatures directly on the target FAT32 volume.
 */
object WindowsBootFilesManager {

    val MZ_SIGNATURE = byteArrayOf(0x4D.toByte(), 0x5A.toByte()) // "MZ"
    val REGF_SIGNATURE = byteArrayOf(0x72.toByte(), 0x65.toByte(), 0x67.toByte(), 0x66.toByte()) // "regf"

    /**
     * Ensures critical UEFI bootloader and BCD paths exist on the target volume.
     * If secondary locations exist, copies them to the standard UEFI firmware paths.
     */
    suspend fun resolveAndEnsureBootFiles(
        writer: Fat32Writer,
        capabilities: WindowsCapabilities
    ): List<String> {
        val actionsTaken = mutableListOf<String>()

        // 1. Ensure /efi/boot directory exists
        if (!writer.exists("/efi")) writer.mkdir("/efi")
        if (!writer.exists("/efi/boot")) writer.mkdir("/efi/boot")
        if (!writer.exists("/efi/microsoft")) writer.mkdir("/efi/microsoft")
        if (!writer.exists("/efi/microsoft/boot")) writer.mkdir("/efi/microsoft/boot")

        // 2. Ensure default UEFI loader (/efi/boot/bootx64.efi or arch loader)
        val defaultLoaderPath = "/efi/boot/${capabilities.arch.efiFileName}"
        if (!writer.exists(defaultLoaderPath)) {
            val candidateSource = when {
                writer.exists("/bootmgr.efi") -> "/bootmgr.efi"
                writer.exists("/efi/microsoft/boot/bootmgfw.efi") -> "/efi/microsoft/boot/bootmgfw.efi"
                writer.exists("/efi/microsoft/boot/cdboot.efi") -> "/efi/microsoft/boot/cdboot.efi"
                else -> null
            }

            if (candidateSource != null) {
                val candidateBytes = writer.readFile(candidateSource)
                if (candidateBytes.isNotEmpty()) {
                    writer.writeFile(defaultLoaderPath, candidateBytes)
                    actionsTaken.add("Provisioned standard UEFI loader $defaultLoaderPath from $candidateSource (${candidateBytes.size} bytes)")
                }
            }
        }

        // 3. Ensure BCD hive exists at /efi/microsoft/boot/bcd
        val uefiBcdPath = "/efi/microsoft/boot/bcd"
        if (!writer.exists(uefiBcdPath)) {
            if (writer.exists("/boot/bcd")) {
                val bcdBytes = writer.readFile("/boot/bcd")
                if (bcdBytes.isNotEmpty()) {
                    writer.writeFile(uefiBcdPath, bcdBytes)
                    actionsTaken.add("Provisioned UEFI BCD hive at $uefiBcdPath from /boot/bcd (${bcdBytes.size} bytes)")
                }
            }
        }

        return actionsTaken
    }

    /**
     * Verifies that the bootloader and BCD on the target volume have valid binary signatures.
     */
    suspend fun verifyBootIntegrity(
        writer: Fat32Writer,
        capabilities: WindowsCapabilities
    ): BootVerificationResult {
        val verifiedFiles = mutableListOf<String>()

        // 1. Check UEFI Boot Loader
        val preferredLoaderPath = "/efi/boot/${capabilities.arch.efiFileName}"
        val actualLoaderPath = when {
            writer.exists(preferredLoaderPath) -> preferredLoaderPath
            writer.exists("/efi/boot/bootx64.efi") -> "/efi/boot/bootx64.efi"
            else -> null
        }

        if (actualLoaderPath == null) {
            return BootVerificationResult(
                isBootable = false,
                uefiLoaderPath = null,
                uefiLoaderSize = 0L,
                hasValidPeSignature = false,
                bcdPath = null,
                bcdSize = 0L,
                hasValidBcdSignature = false,
                bootMgrPath = null,
                verifiedFiles = verifiedFiles,
                errorMessage = "Critical UEFI boot loader ($preferredLoaderPath) is missing from target volume"
            )
        }

        val loaderBytes = writer.readFile(actualLoaderPath)
        val loaderPath = actualLoaderPath

        if (loaderBytes.isEmpty()) {
            return BootVerificationResult(
                isBootable = false,
                uefiLoaderPath = null,
                uefiLoaderSize = 0L,
                hasValidPeSignature = false,
                bcdPath = null,
                bcdSize = 0L,
                hasValidBcdSignature = false,
                bootMgrPath = null,
                verifiedFiles = verifiedFiles,
                errorMessage = "Critical UEFI boot loader ($loaderPath) is missing from target volume"
            )
        }

        // Verify MZ header
        val hasMz = loaderBytes.size >= 2 &&
                loaderBytes[0] == MZ_SIGNATURE[0] &&
                loaderBytes[1] == MZ_SIGNATURE[1]

        if (!hasMz) {
            return BootVerificationResult(
                isBootable = false,
                uefiLoaderPath = loaderPath,
                uefiLoaderSize = loaderBytes.size.toLong(),
                hasValidPeSignature = false,
                bcdPath = null,
                bcdSize = 0L,
                hasValidBcdSignature = false,
                bootMgrPath = null,
                verifiedFiles = verifiedFiles,
                errorMessage = "UEFI loader ($loaderPath) has corrupted executable header (expected MZ signature)"
            )
        }
        verifiedFiles.add("$loaderPath (${loaderBytes.size} bytes, Valid PE/MZ)")

        // 2. Check BCD Hive
        val bcdPath = if (writer.exists("/efi/microsoft/boot/bcd")) {
            "/efi/microsoft/boot/bcd"
        } else if (writer.exists("/boot/bcd")) {
            "/boot/bcd"
        } else null

        val bcdBytes = bcdPath?.let { writer.readFile(it) }

        if (bcdBytes == null || bcdBytes.isEmpty()) {
            return BootVerificationResult(
                isBootable = false,
                uefiLoaderPath = loaderPath,
                uefiLoaderSize = loaderBytes.size.toLong(),
                hasValidPeSignature = true,
                bcdPath = null,
                bcdSize = 0L,
                hasValidBcdSignature = false,
                bootMgrPath = null,
                verifiedFiles = verifiedFiles,
                errorMessage = "Boot Configuration Data (BCD) hive is missing from target volume"
            )
        }

        // Verify regf header
        val hasRegf = bcdBytes.size >= 4 &&
                bcdBytes[0] == REGF_SIGNATURE[0] &&
                bcdBytes[1] == REGF_SIGNATURE[1] &&
                bcdBytes[2] == REGF_SIGNATURE[2] &&
                bcdBytes[3] == REGF_SIGNATURE[3]

        if (!hasRegf) {
            return BootVerificationResult(
                isBootable = false,
                uefiLoaderPath = loaderPath,
                uefiLoaderSize = loaderBytes.size.toLong(),
                hasValidPeSignature = true,
                bcdPath = bcdPath,
                bcdSize = bcdBytes.size.toLong(),
                hasValidBcdSignature = false,
                bootMgrPath = null,
                verifiedFiles = verifiedFiles,
                errorMessage = "BCD registry hive ($bcdPath) is corrupted (expected regf signature)"
            )
        }
        verifiedFiles.add("$bcdPath (${bcdBytes.size} bytes, Valid regf Hive)")

        val bootMgrPath = if (writer.exists("/bootmgr.efi")) "/bootmgr.efi" else if (writer.exists("/bootmgr")) "/bootmgr" else null
        if (bootMgrPath != null) {
            verifiedFiles.add(bootMgrPath)
        }

        return BootVerificationResult(
            isBootable = true,
            uefiLoaderPath = loaderPath,
            uefiLoaderSize = loaderBytes.size.toLong(),
            hasValidPeSignature = true,
            bcdPath = bcdPath,
            bcdSize = bcdBytes.size.toLong(),
            hasValidBcdSignature = true,
            bootMgrPath = bootMgrPath,
            verifiedFiles = verifiedFiles
        )
    }
}
