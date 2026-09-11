package com.ashishsinghbora.flashcore.dsa

import java.io.Closeable
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.Condition
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * High-performance, concurrent Single-Producer Single-Consumer (SPSC) Circular Ring Buffer
 * using off-heap direct memory (ByteBuffer.allocateDirect) to decouple disk stream reading
 * from USB Bulk-Only Transport transfers without triggering JVM Garbage Collection pauses.
 *
 * @param chunkCapacity Number of slots in the circular ring (e.g. 16)
 * @param chunkSizeBytes Size of each slot buffer in bytes (e.g. 1 MB, 2 MB, or 4 MB)
 */
class DirectRingBuffer(
    val chunkCapacity: Int = 16,
    val chunkSizeBytes: Int = 1024 * 1024 // 1 MB per chunk
) : Closeable {

    private val lock = ReentrantLock()
    private val notFull: Condition = lock.newCondition()
    private val notEmpty: Condition = lock.newCondition()

    // Direct byte buffers pre-allocated in native off-heap memory
    private val buffers: Array<ByteBuffer> = Array(chunkCapacity) {
        ByteBuffer.allocateDirect(chunkSizeBytes)
    }

    // Number of valid bytes currently loaded in each slot
    private val slotSizes = IntArray(chunkCapacity)

    // Starting LBA for the data block stored in each slot
    private val slotLbas = LongArray(chunkCapacity)

    // CRC32/Checksum computed for the slot
    private val slotChecksums = LongArray(chunkCapacity)

    private var head = 0 // Producer write index
    private var tail = 0 // Consumer read index
    private var count = 0 // Number of populated slots

    @Volatile
    var isClosed: Boolean = false
        private set

    private val totalBytesProduced = AtomicLong(0)
    private val totalBytesConsumed = AtomicLong(0)
    private val writeWaitCount = AtomicInteger(0)
    private val readWaitCount = AtomicInteger(0)

    data class WriteSlot(
        val buffer: ByteBuffer,
        val slotIndex: Int,
        val maxCapacity: Int
    )

    data class ReadSlot(
        val buffer: ByteBuffer,
        val slotIndex: Int,
        val validBytes: Int,
        val lba: Long,
        val checksum: Long
    )

    /**
     * Producer: Acquires a direct ByteBuffer slot to write data into.
     * Blocks if the ring buffer is completely full until the USB consumer reads slots.
     */
    @Throws(InterruptedException::class, IllegalStateException::class)
    fun acquireWriteSlot(): WriteSlot {
        lock.withLock {
            while (count == chunkCapacity && !isClosed) {
                writeWaitCount.incrementAndGet()
                notFull.await()
            }
            if (isClosed) {
                throw IllegalStateException("RingBuffer has been closed")
            }
            val buf = buffers[head]
            buf.clear()
            return WriteSlot(
                buffer = buf,
                slotIndex = head,
                maxCapacity = chunkSizeBytes
            )
        }
    }

    /**
     * Producer: Commits written bytes into the slot, making it available for the USB consumer.
     */
    fun commitWrite(slotIndex: Int, bytesWritten: Int, lba: Long = 0L, checksum: Long = 0L) {
        lock.withLock {
            if (isClosed) return
            slotSizes[slotIndex] = bytesWritten
            slotLbas[slotIndex] = lba
            slotChecksums[slotIndex] = checksum

            head = (head + 1) % chunkCapacity
            count++
            totalBytesProduced.addAndGet(bytesWritten.toLong())

            notEmpty.signal()
        }
    }

    /**
     * Consumer: Acquires the next available direct ByteBuffer slot populated with data.
     * Blocks if buffer is empty until producer streams more data from disk.
     */
    @Throws(InterruptedException::class, IllegalStateException::class)
    fun acquireReadSlot(): ReadSlot? {
        lock.withLock {
            while (count == 0 && !isClosed) {
                readWaitCount.incrementAndGet()
                notEmpty.await()
            }
            if (count == 0 && isClosed) {
                return null // Stream ended cleanly
            }
            val buf = buffers[tail]
            val validBytes = slotSizes[tail]
            val lba = slotLbas[tail]
            val checksum = slotChecksums[tail]

            buf.position(0)
            buf.limit(validBytes)

            return ReadSlot(
                buffer = buf,
                slotIndex = tail,
                validBytes = validBytes,
                lba = lba,
                checksum = checksum
            )
        }
    }

    /**
     * Consumer: Releases the slot back to the ring buffer for reuse by the disk reader.
     */
    fun commitRead(slotIndex: Int) {
        lock.withLock {
            if (count > 0) {
                totalBytesConsumed.addAndGet(slotSizes[slotIndex].toLong())
                tail = (tail + 1) % chunkCapacity
                count--
                notFull.signal()
            }
        }
    }

    /**
     * Returns the buffer saturation ratio (0.0 = empty, 1.0 = completely full).
     * High saturation indicates disk reading is outpacing USB write speed (healthy backpressure).
     * Low saturation indicates USB writer is waiting on disk I/O.
     */
    fun getSaturation(): Float {
        lock.withLock {
            return count.toFloat() / chunkCapacity.toFloat()
        }
    }

    fun availableReadSlots(): Int = lock.withLock { count }
    fun availableWriteSlots(): Int = lock.withLock { chunkCapacity - count }

    fun getTotalProduced(): Long = totalBytesProduced.get()
    fun getTotalConsumed(): Long = totalBytesConsumed.get()

    /**
     * Marks the stream as finished or aborted.
     */
    override fun close() {
        lock.withLock {
            isClosed = true
            notFull.signalAll()
            notEmpty.signalAll()
        }
    }

    fun reset() {
        lock.withLock {
            head = 0
            tail = 0
            count = 0
            isClosed = false
            totalBytesProduced.set(0)
            totalBytesConsumed.set(0)
            notFull.signalAll()
            notEmpty.signalAll()
        }
    }
}
