package com.ashishsinghbora.flashcore.partition

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * High-performance FAT32 Volume Formatter.
 *
 * Generates valid VBR (Volume Boot Record), FSInfo, File Allocation Tables (FAT1 and FAT2),
 * and standard EFI directory entries for UEFI bootable media.
 */
object Fat32Formatter {

    const val SECTOR_SIZE = 512
    const val RESERVED_SECTORS = 32
    const val NUM_FATS = 2
    const val ROOT_CLUSTER = 2

    data class Fat32Structure(
        val vbrSector: ByteArray,
        val fsInfoSector: ByteArray,
        val fat1Sector: ByteArray,
        val fat2Sector: ByteArray,
        val rootDirSector: ByteArray,
        val sectorsPerCluster: Int,
        val sectorsPerFat: Long,
        val totalSectors: Long
    )

    /**
     * Generates all FAT32 initialization sectors for a given partition size.
     */
    fun format(
        partitionTotalSectors: Long,
        volumeLabel: String = "BOOT_MEDIA"
    ): Fat32Structure {
        val sectorsPerCluster = when {
            partitionTotalSectors < 16777216L -> 8  // 4 KB cluster (< 8 GB)
            partitionTotalSectors < 33554432L -> 16 // 8 KB cluster (< 16 GB)
            partitionTotalSectors < 67108864L -> 32 // 16 KB cluster (< 32 GB)
            else -> 64 // 32 KB cluster
        }

        // Calculate FAT size in sectors
        val totalClusters = (partitionTotalSectors - RESERVED_SECTORS) / sectorsPerCluster
        val bytesForFat = totalClusters * 4
        val sectorsPerFat = (bytesForFat + SECTOR_SIZE - 1) / SECTOR_SIZE

        // 1. Volume Boot Record (Sector 0)
        val vbr = ByteArray(SECTOR_SIZE)
        val vBuf = ByteBuffer.wrap(vbr).order(ByteOrder.LITTLE_ENDIAN)

        // Jump instruction & OEM ID
        vBuf.put(0xEB.toByte())
        vBuf.put(0x58.toByte())
        vBuf.put(0x90.toByte())
        vBuf.put("MSWIN4.1".toByteArray(Charsets.US_ASCII))

        // BIOS Parameter Block (BPB)
        vBuf.putShort(SECTOR_SIZE.toShort()) // Bytes per Sector
        vBuf.put(sectorsPerCluster.toByte()) // Sectors per Cluster
        vBuf.putShort(RESERVED_SECTORS.toShort()) // Reserved Sectors (32)
        vBuf.put(NUM_FATS.toByte()) // Number of FATs (2)
        vBuf.putShort(0.toShort()) // Root entries (0 for FAT32)
        vBuf.putShort(0.toShort()) // Total sectors 16 (0 for FAT32)
        vBuf.put(0xF8.toByte()) // Media descriptor (Fixed disk)
        vBuf.putShort(0.toShort()) // Sectors per FAT 16 (0 for FAT32)
        vBuf.putShort(63.toShort()) // Sectors per track
        vBuf.putShort(255.toShort()) // Heads
        vBuf.putInt(0) // Hidden sectors
        vBuf.putInt((partitionTotalSectors and 0xFFFFFFFFL).toInt()) // Total Sectors 32

        // Extended FAT32 BPB
        vBuf.putInt((sectorsPerFat and 0xFFFFFFFFL).toInt()) // Sectors per FAT 32
        vBuf.putShort(0.toShort()) // Extended Flags (FAT mirroring active)
        vBuf.putShort(0.toShort()) // FS Version (0:0)
        vBuf.putInt(ROOT_CLUSTER) // Root Directory Cluster (2)
        vBuf.putShort(1.toShort()) // FSInfo Sector (1)
        vBuf.putShort(6.toShort()) // Backup Boot Sector (6)

        // 12 bytes Reserved
        for (i in 0 until 12) vBuf.put(0.toByte())

        vBuf.put(0x80.toByte()) // Drive number
        vBuf.put(0.toByte()) // Reserved
        vBuf.put(0x29.toByte()) // Extended Boot Signature
        vBuf.putInt(0x4C5A8821) // Volume Serial Number

        // Volume Label (11 characters)
        val cleanLabel = volumeLabel.padEnd(11, ' ').take(11).uppercase()
        vBuf.put(cleanLabel.toByteArray(Charsets.US_ASCII))
        vBuf.put("FAT32   ".toByteArray(Charsets.US_ASCII))

        // Boot signature at offset 510
        vBuf.position(510)
        vBuf.put(0x55.toByte())
        vBuf.put(0xAA.toByte())

        // 2. FSInfo Sector (Sector 1)
        val fsInfo = ByteArray(SECTOR_SIZE)
        val fBuf = ByteBuffer.wrap(fsInfo).order(ByteOrder.LITTLE_ENDIAN)
        fBuf.putInt(0x41615252) // Lead Signature "RRaA"
        for (i in 0 until 480) fBuf.put(0.toByte())
        fBuf.putInt(0x61417272) // Struct Signature "rrAa"
        fBuf.putInt((totalClusters - 1).toInt()) // Free Cluster Count
        fBuf.putInt(3) // Next Free Cluster hint
        for (i in 0 until 12) fBuf.put(0.toByte())
        fBuf.put(0x00.toByte())
        fBuf.put(0x00.toByte())
        fBuf.put(0x55.toByte())
        fBuf.put(0xAA.toByte())

        // 3. First sector of FAT1 & FAT2 (Cluster 0, 1, and 2 initial allocation)
        val fatSector = ByteArray(SECTOR_SIZE)
        val fatBuf = ByteBuffer.wrap(fatSector).order(ByteOrder.LITTLE_ENDIAN)
        fatBuf.putInt(0x0FFFFFF8.toInt()) // Cluster 0: Media descriptor + status
        fatBuf.putInt(0x0FFFFFFF.toInt()) // Cluster 1: End of cluster chain marker
        fatBuf.putInt(0x0FFFFFFF.toInt()) // Cluster 2 (Root Dir): End of cluster chain

        // 4. Root Directory Initial Sector (Volume Label entry)
        val rootDir = ByteArray(SECTOR_SIZE)
        val rBuf = ByteBuffer.wrap(rootDir).order(ByteOrder.LITTLE_ENDIAN)
        rBuf.put(cleanLabel.toByteArray(Charsets.US_ASCII))
        rBuf.put(0x08.toByte()) // Attribute: ATTR_VOLUME_ID
        for (i in 0 until 20) rBuf.put(0.toByte())

        return Fat32Structure(
            vbrSector = vbr,
            fsInfoSector = fsInfo,
            fat1Sector = fatSector,
            fat2Sector = fatSector.clone(),
            rootDirSector = rootDir,
            sectorsPerCluster = sectorsPerCluster,
            sectorsPerFat = sectorsPerFat,
            totalSectors = partitionTotalSectors
        )
    }
}
