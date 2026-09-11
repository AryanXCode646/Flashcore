package com.example.block

import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * In-Memory Sparse Block Device for testing, benchmarking, and simulation.
 *
 * Emulates physical sector-addressable storage without requiring physical USB OTG hardware.
 * Unwritten sectors return zeroes. Sectors are allocated dynamically on write.
 */
class MemoryBlockDevice(
    val totalSectors: Long = 2097152L, // Default 1 GB (2097152 * 512 bytes)
    override val sectorSizeBytes: Int = 512,
    @Volatile override var isConnected: Boolean = true
) : BlockDevice {

    private val sectors = ConcurrentHashMap<Long, ByteArray>()

    var writeCount: Long = 0L
        private set
    var readCount: Long = 0L
        private set
    var flushCount: Long = 0L
        private set

    /**
     * Simulated fault injection: if set to true, read/write/flush operations fail with IOException.
     */
    var simulateIoFailure: Boolean = false

    override suspend fun capacity(): DeviceCapacity {
        checkConnected()
        return DeviceCapacity(totalSectors, sectorSizeBytes)
    }

    override suspend fun read(lba: Long, blockCount: Int, dest: ByteArray, offset: Int): Boolean {
        currentCoroutineContext().ensureActive()
        checkConnected()
        if (simulateIoFailure) throw IOException("Simulated I/O failure during read")
        validateBounds(lba, blockCount)

        val totalBytes = blockCount * sectorSizeBytes
        if (offset < 0 || offset + totalBytes > dest.size) {
            throw IndexOutOfBoundsException("Buffer offset $offset + $totalBytes exceeds buffer size ${dest.size}")
        }

        for (i in 0 until blockCount) {
            val sectorLba = lba + i
            val destOffset = offset + i * sectorSizeBytes
            val sectorData = sectors[sectorLba]
            if (sectorData != null) {
                System.arraycopy(sectorData, 0, dest, destOffset, sectorSizeBytes)
            } else {
                dest.fill(0, destOffset, destOffset + sectorSizeBytes)
            }
        }
        readCount += blockCount
        return true
    }

    override suspend fun write(lba: Long, blockCount: Int, src: ByteArray, offset: Int): Boolean {
        currentCoroutineContext().ensureActive()
        checkConnected()
        if (simulateIoFailure) throw IOException("Simulated I/O failure during write")
        validateBounds(lba, blockCount)

        val totalBytes = blockCount * sectorSizeBytes
        if (offset < 0 || offset + totalBytes > src.size) {
            throw IndexOutOfBoundsException("Buffer offset $offset + $totalBytes exceeds buffer size ${src.size}")
        }

        for (i in 0 until blockCount) {
            val sectorLba = lba + i
            val srcOffset = offset + i * sectorSizeBytes
            val sectorData = ByteArray(sectorSizeBytes)
            System.arraycopy(src, srcOffset, sectorData, 0, sectorSizeBytes)
            sectors[sectorLba] = sectorData
        }
        writeCount += blockCount
        return true
    }

    override suspend fun writeDirectBuffer(
        lba: Long,
        blockCount: Int,
        directBuffer: ByteBuffer,
        offset: Int,
        length: Int
    ): Boolean {
        currentCoroutineContext().ensureActive()
        checkConnected()
        if (simulateIoFailure) throw IOException("Simulated I/O failure during direct write")
        validateBounds(lba, blockCount)

        val temp = ByteArray(length)
        val originalPos = directBuffer.position()
        try {
            directBuffer.position(offset)
            directBuffer.get(temp, 0, length)
        } finally {
            directBuffer.position(originalPos)
        }

        val sectorsToWrite = minOf(blockCount, length / sectorSizeBytes)
        for (i in 0 until sectorsToWrite) {
            val sectorLba = lba + i
            val srcOffset = i * sectorSizeBytes
            val sectorData = ByteArray(sectorSizeBytes)
            System.arraycopy(temp, srcOffset, sectorData, 0, sectorSizeBytes)
            sectors[sectorLba] = sectorData
        }
        writeCount += sectorsToWrite
        return true
    }

    override suspend fun flush(): Boolean {
        currentCoroutineContext().ensureActive()
        checkConnected()
        if (simulateIoFailure) throw IOException("Simulated I/O failure during flush")
        flushCount++
        return true
    }

    /**
     * Inspects a written sector directly for assertions in unit tests.
     */
    fun getSector(lba: Long): ByteArray? = sectors[lba]

    /**
     * Number of non-zero sectors currently stored in memory.
     */
    val allocatedSectorCount: Int get() = sectors.size

    fun clear() {
        sectors.clear()
    }

    private fun checkConnected() {
        if (!isConnected) {
            throw DeviceDisconnectedException("MemoryBlockDevice is disconnected")
        }
    }

    private fun validateBounds(lba: Long, blockCount: Int) {
        if (lba < 0) {
            throw IOException("LBA cannot be negative: $lba")
        }
        if (blockCount <= 0) {
            throw IOException("Block count must be positive: $blockCount")
        }
        if (lba + blockCount > totalSectors) {
            throw IOException("Requested LBA range [$lba..${lba + blockCount - 1}] exceeds total sectors $totalSectors")
        }
    }

    override fun close() {
        isConnected = false
        sectors.clear()
    }
}
