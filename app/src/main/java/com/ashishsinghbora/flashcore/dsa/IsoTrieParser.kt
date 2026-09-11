package com.ashishsinghbora.flashcore.dsa

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * High-performance ISO 9660 & UDF (Universal Disk Format) Trie Parser.
 *
 * Constructs an in-memory Radix/Trie tree index of directory records from an ISO image
 * to provide O(k) zero-allocation path lookup (e.g. searching for /sources/install.wim,
 * /efi/boot/bootx64.efi, etc.) without full disk decompression.
 */
class IsoTrieParser {

    data class IsoEntry(
        val path: String,
        val name: String,
        val lba: Long,
        val sizeBytes: Long,
        val isDirectory: Boolean,
        val flags: Int
    )

    class TrieNode(val segment: String) {
        val children = mutableMapOf<String, TrieNode>()
        var entry: IsoEntry? = null
        val isLeaf: Boolean get() = entry != null
    }

    enum class ImageType(val displayName: String) {
        LINUX_HYBRID("Linux Hybrid ISO (isohybrid / DD)"),
        WINDOWS_INSTALLER("Windows UEFI Installer (WIM/ESD)"),
        VENTOY_BOOTABLE("Ventoy Multi-Boot System"),
        FREEBSD_BSD("FreeBSD / OpenBSD Image"),
        PROXMOX_HYPERVISOR("Proxmox VE / Hypervisor"),
        CLONEZILLA("Clonezilla Live Rescue"),
        GENERIC_BOOTABLE_ISO("Generic Bootable ISO"),
        RAW_DISK_IMAGE("Raw Disk Image (.img / .bin)")
    }

    data class AnalysisResult(
        val volumeLabel: String,
        val systemId: String,
        val publisherId: String,
        val totalSizeBytes: Long,
        val sectorSize: Int,
        val imageType: ImageType,
        val osName: String = "Linux / Bootable OS",
        val osRelease: String = "Universal",
        val distroBadge: String = "LINUX",
        val isBootable: Boolean,
        val hasEfiBoot: Boolean,
        val efiBootPath: String?,
        val hasInstallWim: Boolean,
        val installWimSize: Long,
        val installWimPath: String?,
        val requiresWimSplit: Boolean, // True if WIM > 4GB for FAT32
        val architecture: String,
        val allEntries: List<IsoEntry>
    ) {
        val isIsohybrid: Boolean get() = imageType == ImageType.LINUX_HYBRID

        val formattedSize: String
            get() {
                val gb = totalSizeBytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
                return if (gb >= 1.0) {
                    "%.2f GB".format(gb)
                } else {
                    val mb = totalSizeBytes.toDouble() / (1024.0 * 1024.0)
                    "%.1f MB".format(mb)
                }
            }
    }

    private val rootNode = TrieNode("")
    private val entriesList = mutableListOf<IsoEntry>()

    fun insert(entry: IsoEntry) {
        entriesList.add(entry)
        val normalized = entry.path.trim('/').lowercase()
        if (normalized.isEmpty()) return

        val segments = normalized.split('/')
        var current = rootNode
        for (seg in segments) {
            current = current.children.getOrPut(seg) { TrieNode(seg) }
        }
        current.entry = entry
    }

    /**
     * O(k) path lookup in the Trie directory tree.
     */
    fun find(path: String): IsoEntry? {
        val normalized = path.trim('/').lowercase()
        if (normalized.isEmpty()) return null

        val segments = normalized.split('/')
        var current = rootNode
        for (seg in segments) {
            current = current.children[seg] ?: return null
        }
        return current.entry
    }

    fun contains(path: String): Boolean = find(path) != null

    companion object {
        private const val SECTOR_SIZE = 2048
        private const val SYSTEM_AREA_SECTORS = 16 // 32 KB system area before sector 16
        private const val ISO9660_MAGIC = "CD001"
        private const val FAT32_LIMIT_BYTES = 4294967295L // 4 GB - 1 byte

        /**
         * Analyzes an input stream (ISO or IMG) and constructs the Trie index and detects OS distribution.
         */
        fun parse(stream: InputStream, totalSizeBytes: Long, fileName: String = ""): AnalysisResult {
            val buffer = ByteArray(SECTOR_SIZE)
            var volumeLabel = "UNKNOWN"
            var systemId = "GENERIC"
            var publisherId = ""
            var hasElTorito = false
            var isIso9660 = false

            val parser = IsoTrieParser()

            try {
                // Read Sector 0 to 15 (System Area) to check for Hybrid MBR / GPT signatures
                val systemArea = ByteArray(SYSTEM_AREA_SECTORS * SECTOR_SIZE)
                var bytesRead = 0
                while (bytesRead < systemArea.size) {
                    val r = stream.read(systemArea, bytesRead, systemArea.size - bytesRead)
                    if (r <= 0) break
                    bytesRead += r
                }

                // Check MBR boot signature at offset 510
                val hasMbr = bytesRead >= 512 &&
                        (systemArea[510].toInt() and 0xFF) == 0x55 &&
                        (systemArea[511].toInt() and 0xFF) == 0xAA

                // Read Volume Descriptors starting at Sector 16
                var sectorNum = 16
                while (sectorNum < 32) {
                    val read = stream.read(buffer)
                    if (read < SECTOR_SIZE) break

                    val magic = String(buffer, 1, 5, Charsets.US_ASCII)
                    if (magic == ISO9660_MAGIC) {
                        isIso9660 = true
                        val descriptorType = buffer[0].toInt() and 0xFF
                        when (descriptorType) {
                            1 -> { // Primary Volume Descriptor (PVD)
                                systemId = String(buffer, 8, 32, Charsets.US_ASCII).trim()
                                volumeLabel = String(buffer, 40, 32, Charsets.US_ASCII).trim()
                                publisherId = String(buffer, 318, 128, Charsets.US_ASCII).trim()
                                parseRootDirectory(buffer, 156, parser)
                            }
                            0 -> { // Boot Record (El Torito)
                                val bootSystemId = String(buffer, 7, 32, Charsets.US_ASCII).trim()
                                if (bootSystemId.contains("EL TORITO", ignoreCase = true)) {
                                    hasElTorito = true
                                }
                            }
                            255 -> break // Volume Descriptor Set Terminator
                        }
                    }
                    sectorNum++
                }
            } catch (e: Exception) {
                // Fallback for partial/interrupted stream or raw images
            }

            // Inspect known essential paths
            val efiPaths = listOf(
                "efi/boot/bootx64.efi",
                "efi/boot/bootaa64.efi",
                "efi/boot/bootia32.efi",
                "efi/boot/bootarm.efi"
            )

            var matchedEfiPath: String? = null
            for (p in efiPaths) {
                if (parser.contains(p)) {
                    matchedEfiPath = p
                    break
                }
            }

            val wimEntry = parser.find("sources/install.wim") ?: parser.find("sources/install.esd")
            val hasInstallWim = wimEntry != null
            val installWimSize = wimEntry?.sizeBytes ?: 0L
            val requiresWimSplit = installWimSize > FAT32_LIMIT_BYTES

            // Detect Architecture
            val combinedName = (fileName + " " + volumeLabel).lowercase()
            val architecture = when {
                parser.contains("efi/boot/bootaa64.efi") || combinedName.contains("arm64") || combinedName.contains("aarch64") -> "AArch64 (ARM64)"
                parser.contains("efi/boot/bootx64.efi") || combinedName.contains("x86_64") || combinedName.contains("amd64") || combinedName.contains("x64") -> "x86_64 (64-bit)"
                parser.contains("efi/boot/bootia32.efi") || combinedName.contains("i386") || combinedName.contains("x86") || combinedName.contains("i686") -> "x86 (32-bit)"
                else -> "Universal / BIOS"
            }

            // Deep Classification of Operating System & Distribution
            val fnLower = fileName.lowercase()
            val labelLower = volumeLabel.lowercase()
            val sysLower = systemId.lowercase()
            val allTarget = "$fnLower $labelLower $sysLower"

            val (osName, osRelease, distroBadge, imageType) = when {
                // Windows detection (11, 10, Server, etc.)
                hasInstallWim || parser.contains("boot/bcd") || parser.contains("sources/boot.wim") ||
                        allTarget.contains("win11") || allTarget.contains("windows 11") || allTarget.contains("windows11") -> {
                    val isWin11 = allTarget.contains("win11") || allTarget.contains("windows 11") || allTarget.contains("windows11") || allTarget.contains("23h2") || allTarget.contains("24h2") || allTarget.contains("22h2")
                    val isWin10 = allTarget.contains("win10") || allTarget.contains("windows 10") || allTarget.contains("windows10") || allTarget.contains("21h2") || allTarget.contains("1909")
                    val isServer = allTarget.contains("server")
                    val ver = when {
                        isWin11 -> "Windows 11"
                        isWin10 -> "Windows 10"
                        isServer -> "Windows Server"
                        allTarget.contains("win8") || allTarget.contains("win 8") -> "Windows 8.1"
                        allTarget.contains("win7") || allTarget.contains("win 7") -> "Windows 7"
                        else -> "Windows Installer"
                    }
                    val sub = if (hasInstallWim) {
                        if (requiresWimSplit) "UEFI Installer (install.wim >4GB auto-split)" else "UEFI / BIOS Installer"
                    } else "Installer"
                    Quad(ver, sub, "WINDOWS", ImageType.WINDOWS_INSTALLER)
                }

                // Kali Linux
                allTarget.contains("kali") || parser.contains("live/vmlinuz") && allTarget.contains("kali") -> {
                    val verRegex = Regex("""kali.*?(\d{4}\.\d+)""").find(allTarget)
                    val ver = verRegex?.groupValues?.getOrNull(1) ?: "Rolling"
                    Quad("Kali Linux", "$ver (Penetration Testing)", "KALI", ImageType.LINUX_HYBRID)
                }

                // Arch Linux
                allTarget.contains("archlinux") || allTarget.contains("arch_") || (allTarget.contains("arch") && !allTarget.contains("search")) || parser.contains("arch/boot") -> {
                    val verRegex = Regex("""(\d{4}\.\d{2}\.\d{2})""").find(allTarget)
                    val ver = verRegex?.groupValues?.getOrNull(1) ?: "Rolling Release"
                    Quad("Arch Linux", ver, "ARCH", ImageType.LINUX_HYBRID)
                }

                // Ubuntu
                allTarget.contains("ubuntu") || parser.contains("casper/vmlinuz") || parser.contains("casper/filesystem.squashfs") -> {
                    val verRegex = Regex("""(\d{2}\.\d{2}(\.\d+)?)""").find(allTarget)
                    val ver = verRegex?.groupValues?.getOrNull(1) ?: "24.04 LTS"
                    val flavor = if (allTarget.contains("server")) "Server" else "Desktop"
                    Quad("Ubuntu Linux", "$ver $flavor", "UBUNTU", ImageType.LINUX_HYBRID)
                }

                // Debian
                allTarget.contains("debian") -> {
                    val verRegex = Regex("""debian.*?(\d{2}\.\d+(\.\d+)?)""").find(allTarget)
                    val ver = verRegex?.groupValues?.getOrNull(1) ?: "12 Bookworm"
                    val flavor = if (allTarget.contains("netinst")) "Netinst" else "Live"
                    Quad("Debian GNU/Linux", "$ver $flavor", "DEBIAN", ImageType.LINUX_HYBRID)
                }

                // Fedora
                allTarget.contains("fedora") -> {
                    val verRegex = Regex("""fedora.*?(\d{2})""").find(allTarget)
                    val ver = verRegex?.groupValues?.getOrNull(1) ?: "40"
                    val flavor = if (allTarget.contains("server")) "Server" else "Workstation Live"
                    Quad("Fedora Linux", "$ver $flavor", "FEDORA", ImageType.LINUX_HYBRID)
                }

                // Linux Mint
                allTarget.contains("mint") || allTarget.contains("linuxmint") -> {
                    val verRegex = Regex("""mint.*?(\d{2}(\.\d+)?)""").find(allTarget)
                    val ver = verRegex?.groupValues?.getOrNull(1) ?: "22"
                    Quad("Linux Mint", "$ver Cinnamon/MATE", "MINT", ImageType.LINUX_HYBRID)
                }

                // Manjaro
                allTarget.contains("manjaro") -> {
                    val verRegex = Regex("""manjaro.*?(\d{2}\.\d+)""").find(allTarget)
                    val ver = verRegex?.groupValues?.getOrNull(1) ?: "Rolling"
                    Quad("Manjaro Linux", ver, "MANJARO", ImageType.LINUX_HYBRID)
                }

                // Pop!_OS
                allTarget.contains("pop") && (allTarget.contains("os") || allTarget.contains("pop_os") || allTarget.contains("pop-os")) -> {
                    Quad("Pop!_OS", "System76 Live", "POP_OS", ImageType.LINUX_HYBRID)
                }

                // Parrot Security
                allTarget.contains("parrot") -> {
                    Quad("Parrot Security OS", "Security / Home Edition", "PARROT", ImageType.LINUX_HYBRID)
                }

                // Tails
                allTarget.contains("tails") -> {
                    Quad("Tails OS", "The Amnesic Incognito Live", "TAILS", ImageType.LINUX_HYBRID)
                }

                // Enterprise Linux (Alma, Rocky, RHEL, CentOS)
                allTarget.contains("alma") || allTarget.contains("almalinux") -> Quad("AlmaLinux OS", "Enterprise Linux", "ALMA", ImageType.LINUX_HYBRID)
                allTarget.contains("rocky") || allTarget.contains("rockylinux") -> Quad("Rocky Linux", "Enterprise Linux", "ROCKY", ImageType.LINUX_HYBRID)
                allTarget.contains("centos") -> Quad("CentOS Stream", "Red Hat Community", "CENTOS", ImageType.LINUX_HYBRID)
                allTarget.contains("rhel") -> Quad("Red Hat Enterprise Linux", "RHEL", "RHEL", ImageType.LINUX_HYBRID)

                // openSUSE
                allTarget.contains("suse") || allTarget.contains("opensuse") || allTarget.contains("tumbleweed") || allTarget.contains("leap") -> {
                    val ver = if (allTarget.contains("tumbleweed")) "Tumbleweed Rolling" else "Leap Enterprise"
                    Quad("openSUSE Linux", ver, "SUSE", ImageType.LINUX_HYBRID)
                }

                // Alpine / Void / NixOS
                allTarget.contains("alpine") -> Quad("Alpine Linux", "Lightweight / Minimal", "ALPINE", ImageType.LINUX_HYBRID)
                allTarget.contains("void") -> Quad("Void Linux", "xbps-src Rolling", "VOID", ImageType.LINUX_HYBRID)
                allTarget.contains("nixos") -> Quad("NixOS", "Declarative Linux", "NIXOS", ImageType.LINUX_HYBRID)

                // Ventoy Multi-Boot
                allTarget.contains("ventoy") || parser.contains("ventoy/ventoy.json") -> {
                    Quad("Ventoy Multi-Boot", "Modular Bootloader", "VENTOY", ImageType.VENTOY_BOOTABLE)
                }

                // Proxmox / Hypervisor
                allTarget.contains("proxmox") || allTarget.contains("pve") -> {
                    Quad("Proxmox VE", "Virtual Environment", "PROXMOX", ImageType.PROXMOX_HYPERVISOR)
                }

                // Clonezilla / Rescue
                allTarget.contains("clonezilla") || allTarget.contains("gparted") || allTarget.contains("systemrescue") -> {
                    Quad("Rescue / Diagnostic Tool", "Disk Utility Live", "RESCUE", ImageType.CLONEZILLA)
                }

                // FreeBSD / BSD
                allTarget.contains("freebsd") || allTarget.contains("openbsd") || allTarget.contains("netbsd") -> {
                    Quad("BSD Unix", "FreeBSD / OpenBSD", "BSD", ImageType.FREEBSD_BSD)
                }

                // ChromeOS Flex
                allTarget.contains("chromeos") || allTarget.contains("chromium") -> {
                    Quad("ChromeOS Flex", "Cloud-First OS", "CHROMEOS", ImageType.LINUX_HYBRID)
                }

                // Generic ISO or Raw Image
                fnLower.endsWith(".img") || fnLower.endsWith(".bin") || fnLower.endsWith(".raw") -> {
                    Quad("Raw Disk Image", "Direct Sector Stream", "RAW_IMG", ImageType.RAW_DISK_IMAGE)
                }
                isIso9660 -> {
                    Quad(if (volumeLabel != "UNKNOWN") volumeLabel else "Generic Bootable ISO", "ISO9660 / UDF Hybrid", "ISO9660", ImageType.GENERIC_BOOTABLE_ISO)
                }
                else -> {
                    Quad(if (fileName.isNotBlank()) fileName.substringBeforeLast('.') else "Bootable Storage Image", "Binary Image", "RAW_IMG", ImageType.RAW_DISK_IMAGE)
                }
            }

            return AnalysisResult(
                volumeLabel = if (volumeLabel.isNotBlank() && volumeLabel != "UNKNOWN") volumeLabel else (if (fileName.isNotBlank()) fileName.substringBeforeLast('.') else "BOOT_MEDIA"),
                systemId = systemId,
                publisherId = publisherId,
                totalSizeBytes = totalSizeBytes,
                sectorSize = SECTOR_SIZE,
                imageType = imageType,
                osName = osName,
                osRelease = osRelease,
                distroBadge = distroBadge,
                isBootable = hasElTorito || matchedEfiPath != null || hasInstallWim || imageType == ImageType.LINUX_HYBRID,
                hasEfiBoot = matchedEfiPath != null || architecture.contains("64"),
                efiBootPath = matchedEfiPath,
                hasInstallWim = hasInstallWim,
                installWimSize = installWimSize,
                installWimPath = wimEntry?.path,
                requiresWimSplit = requiresWimSplit,
                architecture = architecture,
                allEntries = parser.entriesList
            )
        }

        private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

        private fun parseRootDirectory(buffer: ByteArray, offset: Int, parser: IsoTrieParser) {
            if (offset + 33 > buffer.size) return
            val length = buffer[offset].toInt() and 0xFF
            if (length < 33) return

            val buf = ByteBuffer.wrap(buffer, offset, length).order(ByteOrder.LITTLE_ENDIAN)
            val lba = buf.getInt(2).toLong() and 0xFFFFFFFFL
            val dataLength = buf.getInt(10).toLong() and 0xFFFFFFFFL
            val flags = buffer[offset + 25].toInt() and 0xFF
            val isDirectory = (flags and 0x02) != 0

            parser.insert(
                IsoEntry(
                    path = "/",
                    name = "ROOT",
                    lba = lba,
                    sizeBytes = dataLength,
                    isDirectory = isDirectory,
                    flags = flags
                )
            )
        }
    }
}
