package com.ashishsinghbora.flashcore.block

import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Programmable Fake Block Device for unit testing and behavior verification.
 *
 * Records all invocations (read, write, flush, capacity) and allows
 * inspecting execution history, touched LBAs, and configuring mock return values.
 */
class FakeBlockDevice(
    val totalSectors: Long = 2097152L, // Default 1 GB (2097152 * 512 bytes)
    override val sectorSizeBytes: Int = 512,
    @Volatile override var isConnected: Boolean = true
) : BlockDevice {

    enum class OperationType {
        CAPACITY,
        READ,
        WRITE,
        WRITE_DIRECT,
        FLUSH
    }

    data class RecordedOperation(
        val type: OperationType,
        val lba: Long = 0L,
        val blockCount: Int = 0,
        val byteCount: Int = 0,
        val timestamp: Long = System.currentTimeMillis()
    )

    val history = CopyOnWriteArrayList<RecordedOperation>()

    var writeResult: Boolean = true
    var readResult: Boolean = true
    var flushResult: Boolean = true

    val writtenLbas: Set<Long>
        get() = history
            .filter { it.type == OperationType.WRITE || it.type == OperationType.WRITE_DIRECT }
            .flatMap { op -> (op.lba until op.lba + op.blockCount) }
            .toSet()

    val totalBytesWritten: Long
        get() = history
            .filter { it.type == OperationType.WRITE || it.type == OperationType.WRITE_DIRECT }
            .sumOf { it.byteCount.toLong() }

    val totalBytesRead: Long
        get() = history
            .filter { it.type == OperationType.READ }
            .sumOf { it.byteCount.toLong() }

    val flushCount: Int
        get() = history.count { it.type == OperationType.FLUSH }

    val writeCount: Int
        get() = history.count { it.type == OperationType.WRITE || it.type == OperationType.WRITE_DIRECT }

    val readCount: Int
        get() = history.count { it.type == OperationType.READ }

    override suspend fun capacity(): DeviceCapacity {
        checkConnected()
        history.add(RecordedOperation(OperationType.CAPACITY))
        return DeviceCapacity(totalSectors, sectorSizeBytes)
    }

    override suspend fun read(lba: Long, blockCount: Int, dest: ByteArray, offset: Int): Boolean {
        currentCoroutineContext().ensureActive()
        checkConnected()
        validateBounds(lba, blockCount)
        history.add(RecordedOperation(OperationType.READ, lba, blockCount, blockCount * sectorSizeBytes))
        return readResult
    }

    override suspend fun write(lba: Long, blockCount: Int, src: ByteArray, offset: Int): Boolean {
        currentCoroutineContext().ensureActive()
        checkConnected()
        validateBounds(lba, blockCount)
        history.add(RecordedOperation(OperationType.WRITE, lba, blockCount, blockCount * sectorSizeBytes))
        return writeResult
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
        validateBounds(lba, blockCount)
        history.add(RecordedOperation(OperationType.WRITE_DIRECT, lba, blockCount, length))
        return writeResult
    }

    override suspend fun flush(): Boolean {
        currentCoroutineContext().ensureActive()
        checkConnected()
        history.add(RecordedOperation(OperationType.FLUSH))
        return flushResult
    }

    fun wasLbaWritten(lba: Long): Boolean = writtenLbas.contains(lba)

    fun clearHistory() {
        history.clear()
    }

    private fun checkConnected() {
        if (!isConnected) throw DeviceDisconnectedException("FakeBlockDevice is disconnected")
    }

    private fun validateBounds(lba: Long, blockCount: Int) {
        if (lba < 0) throw IOException("LBA cannot be negative: $lba")
        if (blockCount <= 0) throw IOException("Block count must be positive: $blockCount")
        if (lba + blockCount > totalSectors) {
            throw IOException("Requested LBA range [$lba..${lba + blockCount - 1}] exceeds device total sectors $totalSectors")
        }
    }

    override fun close() {
        isConnected = false
    }
}
