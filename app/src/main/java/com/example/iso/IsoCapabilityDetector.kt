package com.example.iso

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Dynamic capability detector for ISO images.
 *
 * Inspects low-level disk structures (Sector 0 MBR, El Torito catalogs, UEFI binary trees,
 * filesystem descriptors, kernel/rootfs payloads, and OS release descriptors) to classify
 * capabilities dynamically without hardcoding distribution names.
 */
object IsoCapabilityDetector {

    const val FAT32_MAX_FILE_SIZE = 4294967295L // 4 GB - 1 byte

    fun detect(
        source: IsoSource,
        pvd: PrimaryVolumeDescriptor,
        elTorito: ElToritoCatalog?,
        tree: IsoDirectoryTree
    ): IsoCapabilities {
        val supportedFirmware = mutableSetOf<FirmwareArchitecture>()
        val bootMechanisms = mutableSetOf<BootMechanism>()

        // 1. Inspect Sector 0 for Isohybrid MBR
        val mbrSector = ByteArray(512)
        val readMbr = source.readAt(0L, mbrSector, 0, 512)
        var isIsohybrid = false

        if (readMbr == 512) {
            val sig = ((mbrSector[510].toInt() and 0xFF) or ((mbrSector[511].toInt() and 0xFF) shl 8))
            if (sig == 0xAA55) {
                // Check if partition 1 or 2 at offset 446 has valid partition type and sector count
                val partType1 = mbrSector[446 + 4].toInt() and 0xFF
                val partSectors1 = ByteBuffer.wrap(mbrSector, 446 + 12, 4).order(ByteOrder.LITTLE_ENDIAN).getInt()
                if (partType1 != 0 && partSectors1 > 0) {
                    isIsohybrid = true
                    bootMechanisms.add(BootMechanism.ISOHYBRID_MBR)
                    supportedFirmware.add(FirmwareArchitecture.BIOS_X86)
                }
            }
        }

        // 2. Inspect El Torito Boot Catalog
        if (elTorito != null) {
            if (elTorito.hasBiosBoot) {
                bootMechanisms.add(BootMechanism.EL_TORITO_BIOS)
                supportedFirmware.add(FirmwareArchitecture.BIOS_X86)
            }
            if (elTorito.hasEfiBoot) {
                bootMechanisms.add(BootMechanism.EL_TORITO_UEFI)
            }
        }

        // 3. Inspect Native UEFI directory tree
        if (tree.find("/efi/boot/bootx64.efi") != null) {
            bootMechanisms.add(BootMechanism.NATIVE_UEFI_TREE)
            supportedFirmware.add(FirmwareArchitecture.X86_64)
        }
        if (tree.find("/efi/boot/bootaa64.efi") != null) {
            bootMechanisms.add(BootMechanism.NATIVE_UEFI_TREE)
            supportedFirmware.add(FirmwareArchitecture.AARCH64)
        }
        if (tree.find("/efi/boot/bootia32.efi") != null) {
            bootMechanisms.add(BootMechanism.NATIVE_UEFI_TREE)
            supportedFirmware.add(FirmwareArchitecture.IA32)
        }
        if (tree.find("/efi/boot/bootarm.efi") != null) {
            bootMechanisms.add(BootMechanism.NATIVE_UEFI_TREE)
            supportedFirmware.add(FirmwareArchitecture.ARM32)
        }
        if (tree.find("/efi/boot/bootriscv64.efi") != null) {
            bootMechanisms.add(BootMechanism.NATIVE_UEFI_TREE)
            supportedFirmware.add(FirmwareArchitecture.RISCV64)
        }

        // 4. Windows Installation Payload Detection
        val wimEntry = tree.find("/sources/install.wim") ?: tree.find("/sources/install.esd")
        val bootWim = tree.find("/sources/boot.wim")
        val bootMgr = tree.find("/bootmgr") ?: tree.find("/bootmgr.efi")
        val hasBcd = tree.find("/boot/bcd") != null || tree.find("/efi/microsoft/boot/bcd") != null

        val isWindows = wimEntry != null || (bootWim != null && (bootMgr != null || hasBcd))
        val installWimSize = wimEntry?.sizeBytes ?: 0L
        val requiresWimSplit = installWimSize > FAT32_MAX_FILE_SIZE

        // 5. Linux Kernel, Initrd, and RootFS Discovery
        val kernelEntry = findFirstMatching(tree, listOf(
            "/casper/vmlinuz",
            "/live/vmlinuz",
            "/install.amd/vmlinuz",
            "/arch/boot/x86_64/vmlinuz-linux",
            "/images/pxeboot/vmlinuz",
            "/boot/vmlinuz"
        ))

        val initrdEntry = findFirstMatching(tree, listOf(
            "/casper/initrd",
            "/live/initrd.img",
            "/install.amd/initrd.gz",
            "/arch/boot/x86_64/initramfs-linux.img",
            "/images/pxeboot/initrd.img",
            "/boot/initrd.img"
        ))

        val rootfsEntry = findFirstMatching(tree, listOf(
            "/casper/filesystem.squashfs",
            "/live/filesystem.squashfs",
            "/LiveOS/squashfs.img",
            "/arch/x86_64/airootfs.sfs",
            "/images/install.img"
        ))

        val isLinux = kernelEntry != null || rootfsEntry != null

        // 6. Dynamic OS Identification from image files (NOT hardcoded!)
        val (osName, osVersion, distroId) = extractOsIdentity(source, tree, pvd.volumeId)

        // 7. Payload Classification & Recommended Strategy
        val payloadType: PayloadType
        val strategy: RecommendedStrategy

        when {
            isWindows -> {
                payloadType = PayloadType.WINDOWS_SETUP
                strategy = RecommendedStrategy.WINDOWS_UEFI
            }
            isLinux -> {
                payloadType = PayloadType.LINUX_LIVE_INSTALLER
                strategy = if (isIsohybrid) RecommendedStrategy.RAW_DD else RecommendedStrategy.VENTOY
            }
            bootMechanisms.isNotEmpty() -> {
                payloadType = PayloadType.GENERIC_BOOTABLE
                strategy = if (isIsohybrid) RecommendedStrategy.RAW_DD else RecommendedStrategy.VENTOY
            }
            else -> {
                payloadType = PayloadType.NON_BOOTABLE
                strategy = RecommendedStrategy.GENERIC_EXTRACT
            }
        }

        return IsoCapabilities(
            volumeLabel = pvd.volumeId,
            systemId = pvd.systemId,
            publisherId = pvd.publisherId,
            totalSizeBytes = pvd.volumeSpaceBlocks * pvd.logicalBlockSizeBytes,
            isIso9660 = true,
            isJoliet = false, // Updated by caller if Joliet SVD was chosen
            isIsohybrid = isIsohybrid,
            hasElTorito = (elTorito != null),
            supportedFirmware = supportedFirmware,
            bootMechanisms = bootMechanisms,
            payloadType = payloadType,
            osName = osName,
            osVersion = osVersion,
            distroIdentifier = distroId,
            hasInstallWim = (wimEntry != null),
            installWimSize = installWimSize,
            installWimPath = wimEntry?.path,
            requiresWimSplit = requiresWimSplit,
            kernelPath = kernelEntry?.path,
            initramfsPath = initrdEntry?.path,
            rootfsPath = rootfsEntry?.path,
            recommendedStrategy = strategy
        )
    }

    private fun findFirstMatching(tree: IsoDirectoryTree, candidates: List<String>): IsoFileEntry? {
        for (c in candidates) {
            val found = tree.find(c)
            if (found != null && !found.isDirectory) return found
        }
        return null
    }

    /**
     * Reads authentic OS metadata directly from standard filesystem descriptors in the image.
     */
    private fun extractOsIdentity(
        source: IsoSource,
        tree: IsoDirectoryTree,
        volumeLabel: String
    ): Triple<String?, String?, String?> {
        // 1. Check Debian/Ubuntu style /.disk/info
        val diskInfo = tree.find("/.disk/info")
        if (diskInfo != null && diskInfo.sizeBytes in 1..4096) {
            val text = readSmallFile(source, diskInfo)
            if (text.isNotBlank()) {
                val line = text.lines().firstOrNull { it.isNotBlank() } ?: text
                val parsed = parseDiskInfoLine(line)
                if (parsed.first != null) return parsed
            }
        }

        // 2. Check Fedora/RHEL style /README.diskdefines
        val diskDef = tree.find("/README.diskdefines") ?: tree.find("/readme.diskdefines")
        if (diskDef != null && diskDef.sizeBytes in 1..4096) {
            val text = readSmallFile(source, diskDef)
            val name = text.lineSequence().firstOrNull { it.startsWith("NAME ") }?.removePrefix("NAME ")?.trim()
            val ver = text.lineSequence().firstOrNull { it.startsWith("VERSION ") }?.removePrefix("VERSION ")?.trim()
            if (name != null) {
                return Triple(name, ver, name.uppercase())
            }
        }

        // 3. Check /loader/entries/ or /boot/grub/grub.cfg for boot titles
        val grubCfg = tree.find("/boot/grub/grub.cfg") ?: tree.find("/efi/boot/grub.cfg")
        if (grubCfg != null && grubCfg.sizeBytes in 1..65536) {
            val text = readSmallFile(source, grubCfg)
            val menuMatch = Regex("""menuentry\s+['"]([^'"]+)['"]""").find(text)
            if (menuMatch != null) {
                val title = menuMatch.groupValues[1]
                return Triple(title, null, title.take(10).uppercase())
            }
        }

        // 4. Fallback to volume label
        if (volumeLabel.isNotBlank() && volumeLabel != "UNKNOWN") {
            return Triple(volumeLabel, null, volumeLabel.take(8).uppercase())
        }

        return Triple(null, null, null)
    }

    private fun parseDiskInfoLine(line: String): Triple<String?, String?, String?> {
        val clean = line.trim()
        val parts = clean.split(' ')
        val name = parts.firstOrNull() ?: clean
        val ver = parts.getOrNull(1)
        return Triple(clean, ver, name.uppercase())
    }

    private fun readSmallFile(source: IsoSource, entry: IsoFileEntry): String {
        val bytes = ByteArray(entry.sizeBytes.toInt())
        val r = source.readAt(entry.lba * 2048L, bytes, 0, bytes.size)
        return if (r > 0) String(bytes, 0, r, Charsets.UTF_8) else ""
    }
}
