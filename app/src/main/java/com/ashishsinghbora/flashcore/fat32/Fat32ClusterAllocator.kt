package com.ashishsinghbora.flashcore.fat32

import com.ashishsinghbora.flashcore.block.BlockDevice

/**
 * High-performance FAT32 Cluster Allocator.
 *
 * Discovers and allocates free clusters using [Fat32Table], updates [Fat32FsInfo] state,
 * zeroes newly allocated clusters on [BlockDevice], and links cluster chains.
 */
class Fat32ClusterAllocator(
    private val device: BlockDevice,
    private val bootSector: Fat32BootSector,
    private val fatTable: Fat32Table,
    private val fsInfo: Fat32FsInfo
) {
    private val maxCluster = bootSector.totalDataClusters + 1L // Clusters are 2..totalDataClusters+1
    private val zeroBuffer = ByteArray(bootSector.sectorSizeBytes)

    /**
     * Translates a cluster index to its starting physical LBA.
     */
    fun clusterToLba(cluster: Long): Long = bootSector.clusterToLba(cluster)

    /**
     * Allocates a single free cluster, marks it as EOC, zeroes its disk blocks,
     * and updates FSInfo counters.
     */
    suspend fun allocateCluster(zeroOut: Boolean = true): Long {
        var candidate = fsInfo.nextFreeClusterHint.coerceAtLeast(3L)
        var foundCluster: Long? = null

        // Pass 1: Search from hint to maxCluster
        while (candidate <= maxCluster) {
            if (Fat32Table.isFree(fatTable.getEntry(candidate))) {
                foundCluster = candidate
                break
            }
            candidate++
        }

        // Pass 2: Wrap around from cluster 3 to initial hint
        if (foundCluster == null) {
            candidate = 3L
            val limit = fsInfo.nextFreeClusterHint.coerceAtMost(maxCluster)
            while (candidate < limit) {
                if (Fat32Table.isFree(fatTable.getEntry(candidate))) {
                    foundCluster = candidate
                    break
                }
                candidate++
            }
        }

        if (foundCluster == null) {
            throw IllegalStateException("FAT32 Volume is completely full. No free clusters available.")
        }

        val allocated = foundCluster

        // Mark as EOC in FAT
        fatTable.setEntry(allocated, Fat32Table.CLUSTER_EOC)

        // Zero cluster sectors if requested
        if (zeroOut) {
            zeroCluster(allocated)
        }

        // Update FSInfo
        if (fsInfo.freeClusters > 0) {
            fsInfo.freeClusters--
        }
        fsInfo.nextFreeClusterHint = if (allocated + 1L <= maxCluster) allocated + 1L else 3L

        return allocated
    }

    /**
     * Allocates a linked chain of [count] clusters.
     */
    suspend fun allocateClusterChain(count: Int, zeroOut: Boolean = true): List<Long> {
        require(count >= 0) { "Cluster count must be non-negative: $count" }
        if (count == 0) return emptyList()

        val chain = ArrayList<Long>(count)
        for (i in 0 until count) {
            chain.add(allocateCluster(zeroOut = zeroOut))
        }

        // Link them in FAT table
        fatTable.linkChain(chain)
        return chain
    }

    /**
     * Frees an entire cluster chain starting from [startCluster].
     */
    suspend fun freeClusterChain(startCluster: Long): Int {
        val freedCount = fatTable.freeChain(startCluster)
        if (fsInfo.freeClusters >= 0) {
            fsInfo.freeClusters += freedCount
        }
        if (startCluster < fsInfo.nextFreeClusterHint) {
            fsInfo.nextFreeClusterHint = startCluster
        }
        return freedCount
    }

    /**
     * Zeroes all sectors belonging to the specified cluster.
     */
    suspend fun zeroCluster(cluster: Long) {
        val startLba = clusterToLba(cluster)
        val sectors = bootSector.sectorsPerCluster
        for (sec in 0 until sectors) {
            device.write(startLba + sec, 1, zeroBuffer)
        }
    }
}
