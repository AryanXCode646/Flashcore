package com.ashishsinghbora.flashcore.fat32

import com.ashishsinghbora.flashcore.block.BlockDevice
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * FAT32 File Allocation Table (FAT) Manager.
 *
 * Handles reading, writing, linking, and traversing 32-bit FAT entries with automatic dual FAT
 * mirroring (FAT1 and FAT2) and sector-level caching for high-speed batch allocations.
 */
class Fat32Table(
    private val device: BlockDevice,
    private val bootSector: Fat32BootSector
) {
    companion object {
        const val CLUSTER_FREE = 0x00000000L
        const val CLUSTER_RESERVED = 0x00000001L
        const val CLUSTER_BAD = 0x0FFFFFF7L
        const val CLUSTER_EOC_MIN = 0x0FFFFFF8L
        const val CLUSTER_EOC = 0x0FFFFFFFL
        const val CLUSTER_MASK = 0x0FFFFFFFL
        const val HIGH_BITS_MASK = 0xF0000000L

        fun isEoc(clusterValue: Long): Boolean =
            (clusterValue and CLUSTER_MASK) >= CLUSTER_EOC_MIN

        fun isFree(clusterValue: Long): Boolean =
            (clusterValue and CLUSTER_MASK) == CLUSTER_FREE
    }

    private val sectorSize = bootSector.sectorSizeBytes
    private val entriesPerSector = sectorSize / 4

    // Sector cache
    private var cachedFatSectorOffset: Long = -1L
    private val sectorBuffer = ByteArray(sectorSize)
    private var isDirty = false

    /**
     * Initializes the first sectors of FAT1 and FAT2 with Cluster 0 (media), Cluster 1 (EOC),
     * and Cluster 2 (Root Directory EOC).
     */
    suspend fun initializeTable() {
        val firstSector = ByteArray(sectorSize)
        val buf = ByteBuffer.wrap(firstSector).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(0x0FFFFFF8.toInt()) // Cluster 0: Media descriptor (0xF8)
        buf.putInt(0x0FFFFFFF.toInt()) // Cluster 1: EOC / Clean bit
        buf.putInt(0x0FFFFFFF.toInt()) // Cluster 2 (Root Directory): EOC

        // Write to FAT1
        device.write(bootSector.fat1StartLba, 1, firstSector)
        // Write to FAT2
        device.write(bootSector.fat2StartLba, 1, firstSector)

        // Cache first FAT sector
        System.arraycopy(firstSector, 0, sectorBuffer, 0, sectorSize)
        cachedFatSectorOffset = 0L
        isDirty = false
    }

    /**
     * Reads a 32-bit FAT entry for the given cluster.
     */
    suspend fun getEntry(cluster: Long): Long {
        require(cluster >= 0) { "Cluster must be non-negative: $cluster" }
        val fatSectorOffset = (cluster * 4L) / sectorSize
        val byteOffset = ((cluster * 4L) % sectorSize).toInt()

        loadSector(fatSectorOffset)

        val buf = ByteBuffer.wrap(sectorBuffer).order(ByteOrder.LITTLE_ENDIAN)
        val rawValue = buf.getInt(byteOffset).toLong() and 0xFFFFFFFFL
        return rawValue and CLUSTER_MASK
    }

    /**
     * Writes a 32-bit FAT entry for the given cluster, preserving the upper 4 reserved bits.
     */
    suspend fun setEntry(cluster: Long, value: Long) {
        require(cluster >= 0) { "Cluster must be non-negative: $cluster" }
        val fatSectorOffset = (cluster * 4L) / sectorSize
        val byteOffset = ((cluster * 4L) % sectorSize).toInt()

        loadSector(fatSectorOffset)

        val buf = ByteBuffer.wrap(sectorBuffer).order(ByteOrder.LITTLE_ENDIAN)
        val existingRaw = buf.getInt(byteOffset).toLong() and 0xFFFFFFFFL
        val highBits = existingRaw and HIGH_BITS_MASK
        val newRaw = highBits or (value and CLUSTER_MASK)

        buf.putInt(byteOffset, (newRaw and 0xFFFFFFFFL).toInt())
        isDirty = true
    }

    /**
     * Links a contiguous or fragmented list of clusters into a single FAT cluster chain,
     * ending with the standard End-Of-Cluster (EOC) marker.
     */
    suspend fun linkChain(clusters: List<Long>) {
        if (clusters.isEmpty()) return
        for (i in 0 until clusters.size - 1) {
            setEntry(clusters[i], clusters[i + 1])
        }
        setEntry(clusters.last(), CLUSTER_EOC)
    }

    /**
     * Follows the FAT cluster chain starting from [startCluster] until the EOC marker.
     */
    suspend fun getClusterChain(startCluster: Long): List<Long> {
        if (startCluster < 2L || isEoc(startCluster)) return emptyList()

        val chain = mutableListOf<Long>()
        var current = startCluster
        val visited = hashSetOf<Long>()

        while (!isEoc(current) && !isFree(current) && current != CLUSTER_BAD) {
            if (!visited.add(current)) {
                throw IllegalStateException("Cyclic FAT cluster chain detected at cluster $current")
            }
            chain.add(current)
            current = getEntry(current)
        }

        return chain
    }

    /**
     * Marks all clusters in the chain starting at [startCluster] as free (0x00000000).
     */
    suspend fun freeChain(startCluster: Long): Int {
        val chain = getClusterChain(startCluster)
        for (cluster in chain) {
            setEntry(cluster, CLUSTER_FREE)
        }
        return chain.size
    }

    /**
     * Loads a specific FAT sector into memory, writing the dirty cached sector to both FAT1 & FAT2 first.
     */
    private suspend fun loadSector(fatSectorOffset: Long) {
        if (cachedFatSectorOffset == fatSectorOffset) return

        flush()

        val lba = bootSector.fat1StartLba + fatSectorOffset
        val success = device.read(lba, 1, sectorBuffer)
        if (!success) {
            // If read failed, fill with zeros to avoid uninitialized memory
            sectorBuffer.fill(0)
        }

        cachedFatSectorOffset = fatSectorOffset
        isDirty = false
    }

    /**
     * Flushes dirty cached FAT sector to both FAT1 and FAT2 on the underlying [BlockDevice].
     */
    suspend fun flush() {
        if (!isDirty || cachedFatSectorOffset < 0) return

        val fat1Lba = bootSector.fat1StartLba + cachedFatSectorOffset
        val fat2Lba = bootSector.fat2StartLba + cachedFatSectorOffset

        // Mirror write to FAT1 and FAT2
        device.write(fat1Lba, 1, sectorBuffer)
        device.write(fat2Lba, 1, sectorBuffer)
        device.flush()

        isDirty = false
    }
}
