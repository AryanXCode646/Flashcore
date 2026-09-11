package com.example.block

import java.io.IOException
import java.io.InterruptedIOException
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/**
 * Fault-Injecting Block Device Decorator.
 *
 * Wraps any underlying [BlockDevice] (e.g. [MemoryBlockDevice], [FileBackedBlockDevice])
 * to inject controlled hardware faults, I/O errors, timeouts, short writes,
 * sector corruptions, and bus disconnects for rigorous testing without physical media.
 */
class FaultInjectingBlockDevice(
    val delegate: BlockDevice
) : BlockDevice {

    override val sectorSizeBytes: Int get() = delegate.sectorSizeBytes

    @Volatile
    private var explicitlyDisconnected: Boolean = false

    override val isConnected: Boolean
        get() = !explicitlyDisconnected && delegate.isConnected

    val totalBytesWritten = AtomicLong(0L)
    val totalBytesRead = AtomicLong(0L)

    // --- Configurable Fault Hooks ---

    var failAtLba: Long? = null
    var failLbaRange: LongRange? = null
    var failException: IOException? = null

    var disconnectAtLba: Long? = null
    var disconnectAfterBytes: Long? = null

    var shortWriteAtLba: Long? = null
    var shortWriteMaxBlocks: Int = 0
    var shortWriteThrows: Boolean = false

    var timeoutAtLba: Long? = null
    var timeoutDelayMs: Long = 0L
    var throwTimeoutException: Boolean = true

    var corruptSectorOnReadLba: Long? = null
    var corruptReadByteOffset: Int = 0
    var corruptReadByteValue: Byte = 0xAA.toByte()

    var corruptSectorOnWriteLba: Long? = null
    var corruptWriteByteOffset: Int = 0
    var corruptWriteByteValue: Byte = 0xFF.toByte()

    var failOnFlush: Boolean = false
    var failOnCapacity: Boolean = false

    override suspend fun capacity(): DeviceCapacity {
        checkConnected()
        if (failOnCapacity) throw IOException("Injected failure during capacity query")
        return delegate.capacity()
    }

    override suspend fun read(lba: Long, blockCount: Int, dest: ByteArray, offset: Int): Boolean {
        currentCoroutineContext().ensureActive()
        checkConnected()
        checkDisconnectTriggers(lba, blockCount)
        checkFailureTriggers(lba, blockCount)
        handleTimeoutTrigger(lba, blockCount)

        val success = delegate.read(lba, blockCount, dest, offset)
        if (success) {
            totalBytesRead.addAndGet(blockCount * sectorSizeBytes.toLong())

            // Apply read corruption if configured
            val corruptLba = corruptSectorOnReadLba
            if (corruptLba != null && corruptLba in lba until (lba + blockCount)) {
                val sectorIndex = (corruptLba - lba).toInt()
                val targetByteIndex = offset + (sectorIndex * sectorSizeBytes) + corruptReadByteOffset
                if (targetByteIndex < dest.size) {
                    dest[targetByteIndex] = corruptReadByteValue
                }
            }
        }
        return success
    }

    override suspend fun write(lba: Long, blockCount: Int, src: ByteArray, offset: Int): Boolean {
        currentCoroutineContext().ensureActive()
        checkConnected()
        checkDisconnectTriggers(lba, blockCount)
        checkFailureTriggers(lba, blockCount)
        handleTimeoutTrigger(lba, blockCount)

        // Handle short write simulation
        val shortLba = shortWriteAtLba
        if (shortLba != null && lba == shortLba && blockCount > shortWriteMaxBlocks) {
            if (shortWriteMaxBlocks > 0) {
                delegate.write(lba, shortWriteMaxBlocks, src, offset)
                totalBytesWritten.addAndGet(shortWriteMaxBlocks * sectorSizeBytes.toLong())
            }
            if (shortWriteThrows) {
                throw IOException("Injected short write at LBA $lba: only $shortWriteMaxBlocks of $blockCount sectors accepted")
            }
            return false // Device rejected full write
        }

        // Handle write corruption simulation
        var bufferToWrite = src
        var bufferOffset = offset
        val corruptLba = corruptSectorOnWriteLba
        if (corruptLba != null && corruptLba in lba until (lba + blockCount)) {
            bufferToWrite = src.copyOf()
            val sectorIndex = (corruptLba - lba).toInt()
            val targetByteIndex = offset + (sectorIndex * sectorSizeBytes) + corruptWriteByteOffset
            if (targetByteIndex < bufferToWrite.size) {
                bufferToWrite[targetByteIndex] = corruptWriteByteValue
            }
        }

        val success = delegate.write(lba, blockCount, bufferToWrite, bufferOffset)
        if (success) {
            val written = totalBytesWritten.addAndGet(blockCount * sectorSizeBytes.toLong())
            val limit = disconnectAfterBytes
            if (limit != null && written >= limit) {
                triggerDisconnect("Injected device disconnect after $written bytes transferred")
            }
        }
        return success
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
        checkDisconnectTriggers(lba, blockCount)
        checkFailureTriggers(lba, blockCount)
        handleTimeoutTrigger(lba, blockCount)

        val shortLba = shortWriteAtLba
        if (shortLba != null && lba == shortLba && blockCount > shortWriteMaxBlocks) {
            if (shortWriteMaxBlocks > 0) {
                delegate.writeDirectBuffer(
                    lba,
                    shortWriteMaxBlocks,
                    directBuffer,
                    offset,
                    shortWriteMaxBlocks * sectorSizeBytes
                )
                totalBytesWritten.addAndGet(shortWriteMaxBlocks * sectorSizeBytes.toLong())
            }
            if (shortWriteThrows) {
                throw IOException("Injected short write direct buffer at LBA $lba: only $shortWriteMaxBlocks of $blockCount accepted")
            }
            return false
        }

        // Handle write corruption simulation
        val corruptLba = corruptSectorOnWriteLba
        var prevByte: Byte? = null
        var targetByteIndex: Int = -1
        if (corruptLba != null && corruptLba in lba until (lba + blockCount)) {
            val sectorIndex = (corruptLba - lba).toInt()
            targetByteIndex = offset + (sectorIndex * sectorSizeBytes) + corruptWriteByteOffset
            if (targetByteIndex < directBuffer.limit()) {
                prevByte = directBuffer.get(targetByteIndex)
                directBuffer.put(targetByteIndex, corruptWriteByteValue)
            }
        }

        val success = try {
            delegate.writeDirectBuffer(lba, blockCount, directBuffer, offset, length)
        } finally {
            if (prevByte != null && targetByteIndex >= 0) {
                directBuffer.put(targetByteIndex, prevByte)
            }
        }
        if (success) {
            val written = totalBytesWritten.addAndGet(length.toLong())
            val limit = disconnectAfterBytes
            if (limit != null && written >= limit) {
                triggerDisconnect("Injected device disconnect after $written bytes transferred")
            }
        }
        return success
    }

    override suspend fun flush(): Boolean {
        currentCoroutineContext().ensureActive()
        checkConnected()
        if (failOnFlush) throw IOException("Injected media flush failure")
        return delegate.flush()
    }

    private fun checkConnected() {
        if (!isConnected) {
            throw DeviceDisconnectedException("FaultInjectingBlockDevice: Device is disconnected")
        }
    }

    private fun checkDisconnectTriggers(lba: Long, blockCount: Int) {
        val targetLba = disconnectAtLba
        if (targetLba != null && targetLba in lba until (lba + blockCount)) {
            triggerDisconnect("Injected device disconnect triggered at LBA $targetLba")
        }
    }

    private fun triggerDisconnect(message: String) {
        explicitlyDisconnected = true
        throw DeviceDisconnectedException(message)
    }

    private fun checkFailureTriggers(lba: Long, blockCount: Int) {
        val targetLba = failAtLba
        if (targetLba != null && targetLba in lba until (lba + blockCount)) {
            throw failException ?: IOException("Injected hardware sector failure at LBA $targetLba")
        }

        val range = failLbaRange
        if (range != null && (lba until (lba + blockCount)).any { it in range }) {
            throw failException ?: IOException("Injected hardware sector failure in LBA range $range")
        }
    }

    private suspend fun handleTimeoutTrigger(lba: Long, blockCount: Int) {
        val targetLba = timeoutAtLba
        if (targetLba != null && targetLba in lba until (lba + blockCount)) {
            if (timeoutDelayMs > 0) {
                delay(timeoutDelayMs)
            }
            if (throwTimeoutException) {
                throw InterruptedIOException("Operation timed out at LBA $targetLba after ${timeoutDelayMs}ms")
            }
        }
    }

    fun resetFaults() {
        failAtLba = null
        failLbaRange = null
        failException = null
        disconnectAtLba = null
        disconnectAfterBytes = null
        shortWriteAtLba = null
        shortWriteMaxBlocks = 0
        shortWriteThrows = false
        timeoutAtLba = null
        timeoutDelayMs = 0L
        throwTimeoutException = true
        corruptSectorOnReadLba = null
        corruptSectorOnWriteLba = null
        failOnFlush = false
        failOnCapacity = false
        explicitlyDisconnected = false
        totalBytesWritten.set(0L)
        totalBytesRead.set(0L)
    }

    override fun close() {
        explicitlyDisconnected = true
        delegate.close()
    }
}
