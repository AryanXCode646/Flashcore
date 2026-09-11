package com.ashishsinghbora.flashcore.iso

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Parsed El Torito Boot Entry from the Boot Catalog.
 */
data class ElToritoBootEntry(
    val platformId: Int,
    val isBootable: Boolean,
    val mediaType: Int, // 0 = No emulation, 1 = 1.2M floppy, 2 = 1.44M floppy, 4 = Hard disk
    val loadRba: Long,
    val sectorCount: Int,
    val isEfi: Boolean
)

/**
 * Result of parsing the El Torito Boot Record and Boot Catalog.
 */
data class ElToritoCatalog(
    val catalogLba: Long,
    val manufacturerId: String,
    val defaultEntry: ElToritoBootEntry?,
    val efiEntries: List<ElToritoBootEntry>,
    val hasBiosBoot: Boolean,
    val hasEfiBoot: Boolean
)

/**
 * Parser for El Torito Volume Descriptors and Boot Catalogs.
 */
object ElToritoParser {

    const val BOOT_RECORD_TYPE = 0
    const val EL_TORITO_ID = "EL TORITO SPECIFICATION"
    const val PLATFORM_X86 = 0x00
    const val PLATFORM_PPC = 0x01
    const val PLATFORM_MAC = 0x02
    const val PLATFORM_EFI = 0xEF
    const val BOOTABLE_FLAG = 0x88

    /**
     * Checks if a sector contains a valid El Torito Boot Record Descriptor.
     * Returns the Boot Catalog LBA or null if not an El Torito descriptor.
     */
    fun parseBootRecordDescriptor(sector: ByteArray): Long? {
        if (sector.size < 2048) return null
        val type = sector[0].toInt() and 0xFF
        if (type != BOOT_RECORD_TYPE) return null

        val magic = String(sector, 1, 5, Charsets.US_ASCII)
        if (magic != "CD001") return null

        val sysId = String(sector, 7, 32, Charsets.US_ASCII).trim()
        if (!sysId.startsWith(EL_TORITO_ID, ignoreCase = true)) return null

        val buf = ByteBuffer.wrap(sector).order(ByteOrder.LITTLE_ENDIAN)
        val catalogLba = buf.getInt(71).toLong() and 0xFFFFFFFFL
        return if (catalogLba > 0) catalogLba else null
    }

    /**
     * Parses the 2048-byte Boot Catalog sector.
     */
    fun parseCatalog(catalogSector: ByteArray, catalogLba: Long): ElToritoCatalog? {
        if (catalogSector.size < 2048) return null
        val buf = ByteBuffer.wrap(catalogSector).order(ByteOrder.LITTLE_ENDIAN)

        // 1. Validation Entry (32 bytes at offset 0)
        val headerId = buf.get(0).toInt() and 0xFF
        if (headerId != 0x01) return null

        val keyBytes = buf.getShort(30).toInt() and 0xFFFF
        if (keyBytes != 0xAA55) return null

        val mfg = String(catalogSector, 4, 24, Charsets.US_ASCII).trim()

        // 2. Initial / Default Boot Entry (32 bytes at offset 32)
        val bootIndicator = buf.get(32).toInt() and 0xFF
        val mediaType = buf.get(33).toInt() and 0xFF
        val sectorCount = buf.getShort(38).toInt() and 0xFFFF
        val loadRba = buf.getInt(40).toLong() and 0xFFFFFFFFL

        val isBootable = (bootIndicator == BOOTABLE_FLAG)
        val defaultEntry = if (loadRba > 0) {
            ElToritoBootEntry(
                platformId = PLATFORM_X86,
                isBootable = isBootable,
                mediaType = mediaType,
                loadRba = loadRba,
                sectorCount = sectorCount,
                isEfi = false
            )
        } else null

        // 3. Scan remaining 32-byte slots for Section Headers and EFI Boot Entries
        val efiList = mutableListOf<ElToritoBootEntry>()
        var offset = 64
        var activePlatform = PLATFORM_X86

        while (offset + 32 <= catalogSector.size) {
            val entryType = buf.get(offset).toInt() and 0xFF

            if (entryType == 0x90 || entryType == 0x91) {
                // Section Header
                activePlatform = buf.get(offset + 1).toInt() and 0xFF
            } else if (entryType == 0x88 || entryType == 0x00) {
                // Section Entry
                val sectionMedia = buf.get(offset + 1).toInt() and 0xFF
                val sectionSecCount = buf.getShort(offset + 6).toInt() and 0xFFFF
                val sectionRba = buf.getInt(offset + 8).toLong() and 0xFFFFFFFFL

                if (sectionRba > 0) {
                    val entry = ElToritoBootEntry(
                        platformId = activePlatform,
                        isBootable = (entryType == 0x88),
                        mediaType = sectionMedia,
                        loadRba = sectionRba,
                        sectorCount = sectionSecCount,
                        isEfi = (activePlatform == PLATFORM_EFI)
                    )
                    if (entry.isEfi) {
                        efiList.add(entry)
                    }
                }
            } else if (entryType == 0) {
                // End of catalog entries
                break
            }

            offset += 32
        }

        val hasBios = defaultEntry?.isBootable == true
        val hasEfi = efiList.any { it.isBootable }

        return ElToritoCatalog(
            catalogLba = catalogLba,
            manufacturerId = mfg,
            defaultEntry = defaultEntry,
            efiEntries = efiList,
            hasBiosBoot = hasBios,
            hasEfiBoot = hasEfi
        )
    }
}
