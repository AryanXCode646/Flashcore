package com.ashishsinghbora.flashcore.block

import java.io.IOException
import java.io.InterruptedIOException
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/**
 * Fault-Injecting Block Device Decorator.
 *
 * Wraps any underlying [BlockDevice] (e.g. [MemoryBlockDevice], [FileBackedBlockDevice])
 * to inject controlled hardware faults, I/O errors, timeouts, short reads, short writes,
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

    private val faultLock = Any()

    // --- Shared & Generic Fault Hooks ---
    @Volatile var failAtLba: Long? = null
    @Volatile var failLbaRange: LongRange? = null
    @Volatile var failException: IOException? = null
    @Volatile var oneShotFailAtLba: Boolean = false
    @Volatile var oneShotFailRange: Boolean = false

    // --- Operation-Specific Read Faults ---
    @Volatile var failReadAtLba: Long? = null
    @Volatile var failReadLbaRange: LongRange? = null
    @Volatile var failReadException: IOException? = null
    @Volatile var oneShotFailReadAtLba: Boolean = false
    @Volatile var oneShotFailReadRange: Boolean = false
    private val nextReadFailuresRemaining = AtomicInteger(0)
    @Volatile var failNextReadException: IOException? = null

    // --- Operation-Specific Write Faults ---
    @Volatile var failWriteAtLba: Long? = null
    @Volatile var failWriteLbaRange: LongRange? = null
    @Volatile var failWriteException: IOException? = null
    @Volatile var oneShotFailWriteAtLba: Boolean = false
    @Volatile var oneShotFailWriteRange: Boolean = false
    private val nextWriteFailuresRemaining = AtomicInteger(0)
    @Volatile var failNextWriteException: IOException? = null

    // --- Disconnect Configuration ---
    @Volatile var disconnectAtLba: Long? = null
    @Volatile var disconnectAfterBytes: Long? = null
    @Volatile var disconnectAfterBlocks: Long? = null

    // --- Short Write Configuration ---
    @Volatile var shortWriteAtLba: Long? = null
    @Volatile var shortWriteMaxBlocks: Int = 0
    @Volatile var shortWriteThrows: Boolean = false
    @Volatile var shortWriteException: IOException? = null
    @Volatile var oneShotShortWrite: Boolean = false

    // --- Short Read Configuration ---
    @Volatile var shortReadAtLba: Long? = null
    @Volatile var shortReadMaxBlocks: Int = 0
    @Volatile var shortReadThrows: Boolean = false
    @Volatile var shortReadException: IOException? = null
    @Volatile var oneShotShortRead: Boolean = false

    // --- Timeout Configuration ---
    @Volatile var timeoutAtLba: Long? = null
    @Volatile var timeoutReadAtLba: Long? = null
    @Volatile var timeoutWriteAtLba: Long? = null
    @Volatile var timeoutOnRead: Boolean = false
    @Volatile var timeoutOnWrite: Boolean = false
    @Volatile var timeoutDelayMs: Long = 0L
    @Volatile var throwTimeoutException: Boolean = true
    @Volatile var timeoutException: IOException? = null
    @Volatile var oneShotTimeout: Boolean = false

    // --- Corruption Configuration ---
    @Volatile var corruptSectorOnReadLba: Long? = null
    @Volatile var corruptReadByteOffset: Int = 0
    @Volatile var corruptReadByteValue: Byte = 0xAA.toByte()

    @Volatile var corruptSectorOnWriteLba: Long? = null
    @Volatile var corruptWriteByteOffset: Int = 0
    @Volatile var corruptWriteByteValue: Byte = 0xFF.toByte()

    // --- Flush Configuration ---
    @Volatile var failOnFlush: Boolean = false
    private val nextFlushFailuresRemaining = AtomicInteger(0)
    @Volatile var flushThrows: Boolean = true
    @Volatile var flushException: IOException? = null
    @Volatile var flushResult: Boolean = true

    // --- Capacity Configuration ---
    @Volatile var failOnCapacity: Boolean = false

    // ------------------------------------------------------------------------
    // Ergonomic Helper APIs for Tests
    // ------------------------------------------------------------------------

    fun failNextRead(exception: IOException? = null, count: Int = 1) {
        synchronized(faultLock) {
            failNextReadException = exception
            nextReadFailuresRemaining.set(count)
        }
    }

    fun failNextWrite(exception: IOException? = null, count: Int = 1) {
        synchronized(faultLock) {
            failNextWriteException = exception
            nextWriteFailuresRemaining.set(count)
        }
    }

    fun failNextFlush(exception: IOException? = null, throws: Boolean = true, count: Int = 1) {
        synchronized(faultLock) {
            flushException = exception
            flushThrows = throws
            nextFlushFailuresRemaining.set(count)
        }
    }

    fun failReadAt(lba: Long, exception: IOException? = null, oneShot: Boolean = false) {
        failReadAtLba = lba
        failReadException = exception
        oneShotFailReadAtLba = oneShot
    }

    fun failWriteAt(lba: Long, exception: IOException? = null, oneShot: Boolean = false) {
        failWriteAtLba = lba
        failWriteException = exception
        oneShotFailWriteAtLba = oneShot
    }

    fun failReadRange(range: LongRange, exception: IOException? = null, oneShot: Boolean = false) {
        failReadLbaRange = range
        failReadException = exception
        oneShotFailReadRange = oneShot
    }

    fun failWriteRange(range: LongRange, exception: IOException? = null, oneShot: Boolean = false) {
        failWriteLbaRange = range
        failWriteException = exception
        oneShotFailWriteRange = oneShot
    }

    fun shortReadAt(
        lba: Long,
        maxBlocks: Int = 0,
        throws: Boolean = false,
        exception: IOException? = null,
        oneShot: Boolean = false
    ) {
        shortReadAtLba = lba
        shortReadMaxBlocks = maxBlocks
        shortReadThrows = throws
        shortReadException = exception
        oneShotShortRead = oneShot
    }

    fun shortWriteAt(
        lba: Long,
        maxBlocks: Int = 0,
        throws: Boolean = false,
        exception: IOException? = null,
        oneShot: Boolean = false
    ) {
        shortWriteAtLba = lba
        shortWriteMaxBlocks = maxBlocks
        shortWriteThrows = throws
        shortWriteException = exception
        oneShotShortWrite = oneShot
    }

    fun timeoutAt(
        lba: Long,
        delayMs: Long = 0L,
        throws: Boolean = true,
        exception: IOException? = null,
        oneShot: Boolean = false
    ) {
        timeoutAtLba = lba
        timeoutDelayMs = delayMs
        throwTimeoutException = throws
        timeoutException = exception
        oneShotTimeout = oneShot
    }

    fun disconnect() {
        explicitlyDisconnected = true
    }

    fun disconnectAt(lba: Long) {
        disconnectAtLba = lba
    }

    fun disconnectAfterBytes(bytes: Long) {
        disconnectAfterBytes = bytes
    }

    fun disconnectAfterBlocks(blocks: Long) {
        disconnectAfterBlocks = blocks
    }

    // ------------------------------------------------------------------------
    // BlockDevice Implementation
    // ------------------------------------------------------------------------

    override suspend fun capacity(): DeviceCapacity {
        checkConnected()
        if (failOnCapacity) throw IOException("Injected failure during capacity query")
        return delegate.capacity()
    }

    override suspend fun read(lba: Long, blockCount: Int, dest: ByteArray, offset: Int): Boolean {
        currentCoroutineContext().ensureActive()
        checkConnected()

        synchronized(faultLock) {
            if (nextReadFailuresRemaining.get() > 0) {
                nextReadFailuresRemaining.decrementAndGet()
                throw failNextReadException ?: IOException("Injected failure on next read at LBA $lba")
            }
        }

        checkDisconnectTriggers(lba, blockCount)
        checkReadFailureTriggers(lba, blockCount)
        handleTimeoutTrigger(lba, blockCount, isWrite = false)

        // Handle short read simulation
        val shortLba = shortReadAtLba
        if (shortLba != null && shortLba in lba until (lba + blockCount)) {
            val allowedBlocks = (shortLba - lba).toInt() + shortReadMaxBlocks
            if (allowedBlocks < blockCount) {
                if (allowedBlocks > 0) {
                    val delegateOk = delegate.read(lba, allowedBlocks, dest, offset)
                    if (!delegateOk) return false
                    totalBytesRead.addAndGet(allowedBlocks * sectorSizeBytes.toLong())
                }
                if (oneShotShortRead) {
                    shortReadAtLba = null
                }
                if (shortReadThrows) {
                    throw shortReadException
                        ?: IOException("Injected short read at LBA $lba: only $allowedBlocks of $blockCount sectors read")
                }
                return false // Partial transfer returned as failure per BlockDevice contract
            }
        }

        val success = delegate.read(lba, blockCount, dest, offset)
        if (success) {
            val total = totalBytesRead.addAndGet(blockCount * sectorSizeBytes.toLong())
            checkPostIoDisconnect(total)

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

        synchronized(faultLock) {
            if (nextWriteFailuresRemaining.get() > 0) {
                nextWriteFailuresRemaining.decrementAndGet()
                throw failNextWriteException ?: IOException("Injected failure on next write at LBA $lba")
            }
        }

        checkDisconnectTriggers(lba, blockCount)
        checkWriteFailureTriggers(lba, blockCount)
        handleTimeoutTrigger(lba, blockCount, isWrite = true)

        // Handle short write simulation
        val shortLba = shortWriteAtLba
        if (shortLba != null && shortLba in lba until (lba + blockCount)) {
            val allowedBlocks = (shortLba - lba).toInt() + shortWriteMaxBlocks
            if (allowedBlocks < blockCount) {
                if (allowedBlocks > 0) {
                    delegate.write(lba, allowedBlocks, src, offset)
                    val written = totalBytesWritten.addAndGet(allowedBlocks * sectorSizeBytes.toLong())
                    checkPostIoDisconnect(written)
                }
                if (oneShotShortWrite) {
                    shortWriteAtLba = null
                }
                if (shortWriteThrows) {
                    throw shortWriteException
                        ?: IOException("Injected short write at LBA $lba: only $allowedBlocks of $blockCount sectors accepted")
                }
                return false // Device rejected full write
            }
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
            checkPostIoDisconnect(written)
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

        synchronized(faultLock) {
            if (nextWriteFailuresRemaining.get() > 0) {
                nextWriteFailuresRemaining.decrementAndGet()
                throw failNextWriteException ?: IOException("Injected failure on next write direct buffer at LBA $lba")
            }
        }

        checkDisconnectTriggers(lba, blockCount)
        checkWriteFailureTriggers(lba, blockCount)
        handleTimeoutTrigger(lba, blockCount, isWrite = true)

        val shortLba = shortWriteAtLba
        if (shortLba != null && shortLba in lba until (lba + blockCount)) {
            val allowedBlocks = (shortLba - lba).toInt() + shortWriteMaxBlocks
            if (allowedBlocks < blockCount) {
                if (allowedBlocks > 0) {
                    delegate.writeDirectBuffer(
                        lba,
                        allowedBlocks,
                        directBuffer,
                        offset,
                        allowedBlocks * sectorSizeBytes
                    )
                    val written = totalBytesWritten.addAndGet(allowedBlocks * sectorSizeBytes.toLong())
                    checkPostIoDisconnect(written)
                }
                if (oneShotShortWrite) {
                    shortWriteAtLba = null
                }
                if (shortWriteThrows) {
                    throw shortWriteException
                        ?: IOException("Injected short write direct buffer at LBA $lba: only $allowedBlocks of $blockCount accepted")
                }
                return false
            }
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
            checkPostIoDisconnect(written)
        }
        return success
    }

    override suspend fun flush(): Boolean {
        currentCoroutineContext().ensureActive()
        checkConnected()

        val hasNextFlushFailure = synchronized(faultLock) {
            if (nextFlushFailuresRemaining.get() > 0) {
                nextFlushFailuresRemaining.decrementAndGet()
                true
            } else {
                false
            }
        }

        if (failOnFlush || hasNextFlushFailure) {
            if (flushThrows) {
                throw flushException ?: IOException("Injected media flush failure")
            }
            return false
        }

        val delegateSuccess = delegate.flush()
        if (!delegateSuccess || !flushResult) {
            return false
        }
        return true
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

    private fun checkPostIoDisconnect(transferredBytes: Long) {
        val byteLimit = disconnectAfterBytes
        if (byteLimit != null && transferredBytes >= byteLimit) {
            triggerDisconnect("Injected device disconnect after $transferredBytes bytes transferred")
        }
        val blockLimit = disconnectAfterBlocks
        val transferredBlocks = transferredBytes / sectorSizeBytes
        if (blockLimit != null && transferredBlocks >= blockLimit) {
            triggerDisconnect("Injected device disconnect after $transferredBlocks blocks transferred")
        }
    }

    private fun triggerDisconnect(message: String) {
        explicitlyDisconnected = true
        throw DeviceDisconnectedException(message)
    }

    private fun checkReadFailureTriggers(lba: Long, blockCount: Int) {
        // Shared / generic failAtLba
        val targetLba = failAtLba
        if (targetLba != null && targetLba in lba until (lba + blockCount)) {
            if (oneShotFailAtLba) {
                failAtLba = null
            }
            throw failException ?: IOException("Injected hardware sector failure at LBA $targetLba")
        }

        // Shared / generic failLbaRange
        val range = failLbaRange
        if (range != null && (lba until (lba + blockCount)).any { it in range }) {
            if (oneShotFailRange) {
                failLbaRange = null
            }
            throw failException ?: IOException("Injected hardware sector failure in LBA range $range")
        }

        // Operation-specific read failAtLba
        val readLba = failReadAtLba
        if (readLba != null && readLba in lba until (lba + blockCount)) {
            if (oneShotFailReadAtLba) {
                failReadAtLba = null
            }
            throw failReadException ?: failException ?: IOException("Injected hardware read sector failure at LBA $readLba")
        }

        // Operation-specific read range
        val readRange = failReadLbaRange
        if (readRange != null && (lba until (lba + blockCount)).any { it in readRange }) {
            if (oneShotFailReadRange) {
                failReadLbaRange = null
            }
            throw failReadException ?: failException ?: IOException("Injected hardware read sector failure in LBA range $readRange")
        }
    }

    private fun checkWriteFailureTriggers(lba: Long, blockCount: Int) {
        // Shared / generic failAtLba
        val targetLba = failAtLba
        if (targetLba != null && targetLba in lba until (lba + blockCount)) {
            if (oneShotFailAtLba) {
                failAtLba = null
            }
            throw failException ?: IOException("Injected hardware sector failure at LBA $targetLba")
        }

        // Shared / generic failLbaRange
        val range = failLbaRange
        if (range != null && (lba until (lba + blockCount)).any { it in range }) {
            if (oneShotFailRange) {
                failLbaRange = null
            }
            throw failException ?: IOException("Injected hardware sector failure in LBA range $range")
        }

        // Operation-specific write failAtLba
        val writeLba = failWriteAtLba
        if (writeLba != null && writeLba in lba until (lba + blockCount)) {
            if (oneShotFailWriteAtLba) {
                failWriteAtLba = null
            }
            throw failWriteException ?: failException ?: IOException("Injected hardware write sector failure at LBA $writeLba")
        }

        // Operation-specific write range
        val writeRange = failWriteLbaRange
        if (writeRange != null && (lba until (lba + blockCount)).any { it in writeRange }) {
            if (oneShotFailWriteRange) {
                failWriteLbaRange = null
            }
            throw failWriteException ?: failException ?: IOException("Injected hardware write sector failure in LBA range $writeRange")
        }
    }

    private suspend fun handleTimeoutTrigger(lba: Long, blockCount: Int, isWrite: Boolean) {
        val shouldTimeout = when {
            isWrite && timeoutOnWrite -> true
            !isWrite && timeoutOnRead -> true
            isWrite && timeoutWriteAtLba != null && timeoutWriteAtLba!! in lba until (lba + blockCount) -> true
            !isWrite && timeoutReadAtLba != null && timeoutReadAtLba!! in lba until (lba + blockCount) -> true
            timeoutAtLba != null && timeoutAtLba!! in lba until (lba + blockCount) -> true
            else -> false
        }

        if (shouldTimeout) {
            if (oneShotTimeout) {
                timeoutAtLba = null
                timeoutReadAtLba = null
                timeoutWriteAtLba = null
                timeoutOnRead = false
                timeoutOnWrite = false
            }
            if (timeoutDelayMs > 0) {
                delay(timeoutDelayMs)
            }
            if (throwTimeoutException) {
                throw timeoutException ?: InterruptedIOException("Operation timed out at LBA $lba after ${timeoutDelayMs}ms")
            }
        }
    }

    fun resetFaults() {
        synchronized(faultLock) {
            failAtLba = null
            failLbaRange = null
            failException = null
            oneShotFailAtLba = false
            oneShotFailRange = false

            failReadAtLba = null
            failReadLbaRange = null
            failReadException = null
            oneShotFailReadAtLba = false
            oneShotFailReadRange = false
            nextReadFailuresRemaining.set(0)
            failNextReadException = null

            failWriteAtLba = null
            failWriteLbaRange = null
            failWriteException = null
            oneShotFailWriteAtLba = false
            oneShotFailWriteRange = false
            nextWriteFailuresRemaining.set(0)
            failNextWriteException = null

            disconnectAtLba = null
            disconnectAfterBytes = null
            disconnectAfterBlocks = null

            shortWriteAtLba = null
            shortWriteMaxBlocks = 0
            shortWriteThrows = false
            shortWriteException = null
            oneShotShortWrite = false

            shortReadAtLba = null
            shortReadMaxBlocks = 0
            shortReadThrows = false
            shortReadException = null
            oneShotShortRead = false

            timeoutAtLba = null
            timeoutReadAtLba = null
            timeoutWriteAtLba = null
            timeoutOnRead = false
            timeoutOnWrite = false
            timeoutDelayMs = 0L
            throwTimeoutException = true
            timeoutException = null
            oneShotTimeout = false

            corruptSectorOnReadLba = null
            corruptReadByteOffset = 0
            corruptReadByteValue = 0xAA.toByte()

            corruptSectorOnWriteLba = null
            corruptWriteByteOffset = 0
            corruptWriteByteValue = 0xFF.toByte()

            failOnFlush = false
            nextFlushFailuresRemaining.set(0)
            flushThrows = true
            flushException = null
            flushResult = true

            failOnCapacity = false
            explicitlyDisconnected = false
            totalBytesWritten.set(0L)
            totalBytesRead.set(0L)
        }
    }

    override fun close() {
        explicitlyDisconnected = true
        delegate.close()
    }
}
