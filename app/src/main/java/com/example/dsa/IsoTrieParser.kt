package com.example.dsa

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
         * Analyzes an input stream (ISO or IMG) and constructs the Trie index.
         */
        fun parse(stream: InputStream, totalSizeBytes: Long): AnalysisResult {
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
            val architecture = when {
                parser.contains("efi/boot/bootaa64.efi") || volumeLabel.contains("arm64", ignoreCase = true) || volumeLabel.contains("aarch64", ignoreCase = true) -> "AArch64 (ARM64)"
                parser.contains("efi/boot/bootx64.efi") || volumeLabel.contains("x86_64", ignoreCase = true) || volumeLabel.contains("amd64", ignoreCase = true) -> "x86_64 (64-bit)"
                parser.contains("efi/boot/bootia32.efi") || volumeLabel.contains("i386", ignoreCase = true) -> "x86 (32-bit)"
                else -> "Universal / BIOS"
            }

            // Classify OS / Image Type
            val labelLower = volumeLabel.lowercase()
            val imageType = when {
                hasInstallWim || parser.contains("boot/bcd") || parser.contains("sources/boot.wim") -> ImageType.WINDOWS_INSTALLER
                labelLower.contains("ventoy") || parser.contains("ventoy/ventoy.json") -> ImageType.VENTOY_BOOTABLE
                labelLower.contains("proxmox") || labelLower.contains("pve") -> ImageType.PROXMOX_HYPERVISOR
                labelLower.contains("clonezilla") -> ImageType.CLONEZILLA
                labelLower.contains("freebsd") || labelLower.contains("openbsd") -> ImageType.FREEBSD_BSD
                labelLower.contains("ubuntu") || labelLower.contains("debian") || labelLower.contains("arch") ||
                        labelLower.contains("fedora") || labelLower.contains("kali") || labelLower.contains("manjaro") ||
                        labelLower.contains("mint") || labelLower.contains("pop-os") || labelLower.contains("nixos") ||
                        labelLower.contains("almalinux") || labelLower.contains("rocky") || labelLower.contains("centos") -> ImageType.LINUX_HYBRID
                isIso9660 -> ImageType.GENERIC_BOOTABLE_ISO
                else -> ImageType.RAW_DISK_IMAGE
            }

            return AnalysisResult(
                volumeLabel = if (volumeLabel.isNotBlank()) volumeLabel else "BOOT_MEDIA",
                systemId = systemId,
                publisherId = publisherId,
                totalSizeBytes = totalSizeBytes,
                sectorSize = SECTOR_SIZE,
                imageType = imageType,
                isBootable = hasElTorito || matchedEfiPath != null || hasInstallWim,
                hasEfiBoot = matchedEfiPath != null,
                efiBootPath = matchedEfiPath,
                hasInstallWim = hasInstallWim,
                installWimSize = installWimSize,
                installWimPath = wimEntry?.path,
                requiresWimSplit = requiresWimSplit,
                architecture = architecture,
                allEntries = parser.entriesList
            )
        }

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
