package com.example.partition

import com.example.block.BlockDevice
import com.example.block.DeviceCapacity
import com.example.block.DeviceDisconnectedException
import java.io.IOException
import java.nio.ByteBuffer
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Sub-device mapping a specific [Partition] to the [BlockDevice] interface.
 *
 * Translates partition-relative LBAs [0 until partition.sectorCount]
 * directly into physical storage LBAs [partition.firstLba until partition.lastLba + 1]
 * on the parent [BlockDevice]. Strictly bounds operations to prevent partition boundary overflows.
 */
class PartitionBlockDevice(
    val parent: BlockDevice,
    val partition: Partition
) : BlockDevice {

    constructor(parent: BlockDevice, startLba: Long, sizeLba: Long, name: String = "Partition") : this(
        parent = parent,
        partition = Partition(
            index = 1,
            firstLba = startLba,
            lastLba = startLba + sizeLba - 1L,
            name = name
        )
    )

    override val isConnected: Boolean get() = parent.isConnected
    override val sectorSizeBytes: Int get() = parent.sectorSizeBytes

    override suspend fun capacity(): DeviceCapacity {
        return DeviceCapacity(
            totalSectors = partition.sectorCount,
            sectorSizeBytes = sectorSizeBytes
        )
    }

    override suspend fun read(lba: Long, blockCount: Int, dest: ByteArray, offset: Int): Boolean {
        currentCoroutineContext().ensureActive()
        validatePartitionBounds(lba, blockCount)
        val physicalLba = partition.firstLba + lba
        return parent.read(physicalLba, blockCount, dest, offset)
    }

    override suspend fun write(lba: Long, blockCount: Int, src: ByteArray, offset: Int): Boolean {
        currentCoroutineContext().ensureActive()
        validatePartitionBounds(lba, blockCount)
        val physicalLba = partition.firstLba + lba
        return parent.write(physicalLba, blockCount, src, offset)
    }

    override suspend fun writeDirectBuffer(
        lba: Long,
        blockCount: Int,
        directBuffer: ByteBuffer,
        offset: Int,
        length: Int
    ): Boolean {
        currentCoroutineContext().ensureActive()
        validatePartitionBounds(lba, blockCount)
        val physicalLba = partition.firstLba + lba
        return parent.writeDirectBuffer(physicalLba, blockCount, directBuffer, offset, length)
    }

    override suspend fun flush(): Boolean {
        currentCoroutineContext().ensureActive()
        return parent.flush()
    }

    private fun validatePartitionBounds(lba: Long, blockCount: Int) {
        if (!isConnected) throw DeviceDisconnectedException("Parent block device is disconnected")
        if (lba < 0) throw IOException("Partition LBA cannot be negative: $lba")
        if (blockCount <= 0) throw IOException("Block count must be positive: $blockCount")
        if (lba + blockCount > partition.sectorCount) {
            throw IOException(
                "Access out of partition bounds: partition relative [$lba..${lba + blockCount - 1}] " +
                "exceeds partition sector count ${partition.sectorCount} (Partition ${partition.index}: ${partition.name})"
            )
        }
    }

    override fun close() {
        // Closing a partition sub-device flushes any pending writes but does not close parent
    }
}
