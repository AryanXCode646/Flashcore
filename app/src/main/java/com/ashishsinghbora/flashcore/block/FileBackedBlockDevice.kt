package com.ashishsinghbora.flashcore.block

import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * File-Backed Block Device.
 *
 * Implements [BlockDevice] using a raw disk image file on the host filesystem.
 * Enables persistent virtual disks, inspection of partitioned images with standard tools,
 * and realistic I/O behavior without requiring physical USB storage.
 *
 * Capacity and Geometry Semantics:
 * 1. Explicit Logical Capacity: When constructed with an explicit [totalSectors] count,
 *    the device geometry is fixed to [totalSectors] sectors of [sectorSizeBytes] each.
 *    - New files are created if they do not exist.
 *    - Existing files are validated: an existing file whose length exceeds the requested
 *      capacity is rejected to prevent silent data destruction/truncation. Existing files
 *      smaller than requested capacity are treated as valid sparse disk images.
 *    - Setting [preallocate] to true extends the file length to the full logical capacity
 *      via [RandomAccessFile.setLength]. Note that on sparse-capable host filesystems, physical
 *      allocation of disk blocks remains on-demand by the OS filesystem driver.
 * 2. Existing Image Auto-Capacity: When opened via [openExisting] or secondary constructors
 *    without [totalSectors], the sector count is derived directly from the backing file's
 *    physical byte length (`imageFile.length() / sectorSizeBytes`). The file must already exist,
 *    be a regular non-empty file, and its size must be an exact integer multiple of [sectorSizeBytes].
 *
 * Concurrency & Lifecycle:
 * - All file I/O operations (read, write, direct-buffer write, flush) and [close] synchronize
 *   on a dedicated internal lock.
 * - Calling [close] transitions the device atomically to disconnected state and releases underlying
 *   file channels and handles. In-flight and subsequent operations fail deterministically with
 *   [DeviceDisconnectedException].
 * - [close] is safely idempotent.
 */
class FileBackedBlockDevice @JvmOverloads constructor(
    val imageFile: File,
    val totalSectors: Long,
    override val sectorSizeBytes: Int = DEFAULT_SECTOR_SIZE,
    preallocate: Boolean = false
) : BlockDevice {

    private val lock = Any()
    private val raf: RandomAccessFile
    private val channel: FileChannel

    @Volatile
    override var isConnected: Boolean = true
        private set

    val totalCapacityBytes: Long
        get() = totalSectors * sectorSizeBytes.toLong()

    init {
        validateGeometry(totalSectors, sectorSizeBytes)
        validateBackingFile(imageFile, totalSectors, sectorSizeBytes)

        if (!imageFile.exists()) {
            imageFile.parentFile?.mkdirs()
            imageFile.createNewFile()
        }

        var openedRaf: RandomAccessFile? = null
        var openedChannel: FileChannel? = null
        try {
            openedRaf = RandomAccessFile(imageFile, "rw")
            openedChannel = openedRaf.channel

            val requiredBytes = totalSectors * sectorSizeBytes.toLong()
            if (preallocate && openedRaf.length() < requiredBytes) {
                openedRaf.setLength(requiredBytes)
            }
            this.raf = openedRaf
            this.channel = openedChannel
        } catch (t: Throwable) {
            try { openedChannel?.close() } catch (_: Exception) {}
            try { openedRaf?.close() } catch (_: Exception) {}
            throw t
        }
    }

    /**
     * Secondary constructor to open an existing disk image file, automatically deriving
     * total sector capacity from the file's byte length.
     */
    constructor(
        imageFile: File,
        sectorSizeBytes: Int
    ) : this(
        imageFile = imageFile,
        totalSectors = deriveTotalSectors(imageFile, sectorSizeBytes),
        sectorSizeBytes = sectorSizeBytes,
        preallocate = false
    )

    /**
     * Secondary constructor to open an existing disk image file with default
     * 512-byte sector size, deriving sector count from the file's byte length.
     */
    constructor(imageFile: File) : this(imageFile, DEFAULT_SECTOR_SIZE)

    override suspend fun capacity(): DeviceCapacity {
        checkConnected()
        return DeviceCapacity(totalSectors, sectorSizeBytes)
    }

    override suspend fun read(lba: Long, blockCount: Int, dest: ByteArray, offset: Int): Boolean {
        currentCoroutineContext().ensureActive()
        checkConnected()
        validateBounds(lba, blockCount)

        val totalBytes = calculateTotalBytes(blockCount)
        validateBufferBounds(dest.size, offset, totalBytes)

        val fileOffset = lba * sectorSizeBytes.toLong()
        synchronized(lock) {
            checkConnected()
            val fileLength = raf.length()
            if (fileOffset >= fileLength) {
                // Reading beyond current physical file length returns deterministic zeroes
                dest.fill(0, offset, offset + totalBytes)
                return true
            }

            raf.seek(fileOffset)
            val bytesToRead = minOf(totalBytes.toLong(), fileLength - fileOffset).toInt()
            var readBytes = 0
            while (readBytes < bytesToRead) {
                val r = raf.read(dest, offset + readBytes, bytesToRead - readBytes)
                if (r < 0) break
                readBytes += r
            }

            // If file ended before totalBytes, zero-fill remainder (sparse region)
            if (readBytes < totalBytes) {
                dest.fill(0, offset + readBytes, offset + totalBytes)
            }
        }
        return true
    }

    override suspend fun write(lba: Long, blockCount: Int, src: ByteArray, offset: Int): Boolean {
        currentCoroutineContext().ensureActive()
        checkConnected()
        validateBounds(lba, blockCount)

        val totalBytes = calculateTotalBytes(blockCount)
        validateBufferBounds(src.size, offset, totalBytes)

        val fileOffset = lba * sectorSizeBytes.toLong()
        synchronized(lock) {
            checkConnected()
            raf.seek(fileOffset)
            raf.write(src, offset, totalBytes)
        }
        return true
    }

    /**
     * Writes sectors from [directBuffer] starting at [offset] for [length] bytes.
     *
     * While optimized for direct ByteBuffers, standard heap ByteBuffers are also accepted.
     * The caller's buffer position and limit are preserved.
     */
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

        if (offset < 0) {
            throw IndexOutOfBoundsException("Buffer offset cannot be negative: $offset")
        }
        if (length < 0) {
            throw IllegalArgumentException("Length cannot be negative: $length")
        }
        val expectedBytes = blockCount.toLong() * sectorSizeBytes.toLong()
        if (length.toLong() != expectedBytes) {
            throw IllegalArgumentException(
                "Requested length ($length bytes) does not match blockCount ($blockCount sectors * $sectorSizeBytes bytes/sector = $expectedBytes bytes)"
            )
        }
        val bufferLimit = directBuffer.limit()
        if (offset > bufferLimit || offset.toLong() + length.toLong() > bufferLimit.toLong()) {
            throw IndexOutOfBoundsException(
                "Requested range [$offset..${offset.toLong() + length.toLong()}] exceeds buffer limit ($bufferLimit)"
            )
        }

        val fileOffset = lba * sectorSizeBytes.toLong()
        val slice = directBuffer.duplicate()
        slice.position(offset)
        slice.limit(offset + length)

        synchronized(lock) {
            checkConnected()
            channel.position(fileOffset)
            var zeroProgressAttempts = 0
            val maxZeroProgressRetries = 10
            while (slice.hasRemaining()) {
                val written = channel.write(slice)
                if (written < 0) {
                    throw IOException("Unexpected EOF while writing buffer to file channel at LBA $lba")
                }
                if (written == 0) {
                    zeroProgressAttempts++
                    if (zeroProgressAttempts > maxZeroProgressRetries) {
                        throw IOException(
                            "Zero-progress write detected: channel.write made no progress after $maxZeroProgressRetries attempts at LBA $lba"
                        )
                    }
                    Thread.yield()
                } else {
                    zeroProgressAttempts = 0
                }
            }
        }
        return true
    }

    override suspend fun flush(): Boolean {
        currentCoroutineContext().ensureActive()
        checkConnected()
        synchronized(lock) {
            checkConnected()
            channel.force(true)
        }
        return true
    }

    private fun checkConnected() {
        if (!isConnected) {
            throw DeviceDisconnectedException("FileBackedBlockDevice is closed/disconnected")
        }
    }

    private fun validateBounds(lba: Long, blockCount: Int) {
        if (lba < 0L) {
            throw IOException("LBA cannot be negative: $lba")
        }
        if (blockCount <= 0) {
            throw IOException("Block count must be positive: $blockCount")
        }
        if (lba >= totalSectors || blockCount.toLong() > totalSectors - lba) {
            val endSector = if (Long.MAX_VALUE - lba < blockCount.toLong()) "overflow" else "${lba + blockCount.toLong() - 1L}"
            throw IOException("Requested LBA range [$lba..$endSector] exceeds device total sectors $totalSectors")
        }
    }

    private fun calculateTotalBytes(blockCount: Int): Int {
        val totalBytesLong = blockCount.toLong() * sectorSizeBytes.toLong()
        if (totalBytesLong > Int.MAX_VALUE.toLong()) {
            throw IllegalArgumentException(
                "Transfer size in bytes ($totalBytesLong) exceeds maximum supported buffer size (${Int.MAX_VALUE})"
            )
        }
        return totalBytesLong.toInt()
    }

    private fun validateBufferBounds(bufferSize: Int, offset: Int, totalBytes: Int) {
        if (offset < 0) {
            throw IndexOutOfBoundsException("Buffer offset cannot be negative: $offset")
        }
        if (offset.toLong() + totalBytes.toLong() > bufferSize.toLong()) {
            throw IndexOutOfBoundsException(
                "Buffer range [$offset..${offset.toLong() + totalBytes.toLong()}] exceeds buffer size $bufferSize"
            )
        }
    }

    override fun close() {
        synchronized(lock) {
            if (!isConnected) return
            isConnected = false
            try {
                if (channel.isOpen) {
                    channel.close()
                }
            } catch (_: Exception) {}
            try {
                raf.close()
            } catch (_: Exception) {}
        }
    }

    companion object {
        const val DEFAULT_SECTOR_SIZE = 512

        private fun validateGeometry(totalSectors: Long, sectorSizeBytes: Int) {
            if (sectorSizeBytes <= 0) {
                throw IllegalArgumentException("Sector size must be positive: $sectorSizeBytes")
            }
            if (totalSectors <= 0L) {
                throw IllegalArgumentException("Total sectors must be positive: $totalSectors")
            }
            if (totalSectors > Long.MAX_VALUE / sectorSizeBytes.toLong()) {
                throw IllegalArgumentException(
                    "Total capacity overflows Long: totalSectors=$totalSectors, sectorSizeBytes=$sectorSizeBytes"
                )
            }
        }

        private fun validateBackingFile(imageFile: File, totalSectors: Long, sectorSizeBytes: Int) {
            if (imageFile.exists()) {
                if (!imageFile.isFile) {
                    throw IllegalArgumentException("Path is not a regular file: ${imageFile.absolutePath}")
                }
                val fileLength = imageFile.length()
                if (fileLength % sectorSizeBytes.toLong() != 0L) {
                    throw IllegalArgumentException(
                        "Existing backing file size ($fileLength bytes) is not an exact multiple of sector size ($sectorSizeBytes bytes)"
                    )
                }
                val maxAllowedBytes = totalSectors * sectorSizeBytes.toLong()
                if (fileLength > maxAllowedBytes) {
                    throw IllegalArgumentException(
                        "Backing file size ($fileLength bytes) exceeds requested logical capacity ($totalSectors sectors * $sectorSizeBytes bytes = $maxAllowedBytes bytes). Destructive truncation is not permitted."
                    )
                }
            }
        }

        /**
         * Derives the total number of sectors for an existing disk image file.
         *
         * @param imageFile The existing disk image file.
         * @param sectorSizeBytes Sector size in bytes.
         * @return Total number of sectors.
         * @throws FileNotFoundException if the image file does not exist.
         * @throws IllegalArgumentException if the file is not a regular file, is empty,
         *         or its byte length is not divisible by [sectorSizeBytes].
         */
        fun deriveTotalSectors(imageFile: File, sectorSizeBytes: Int): Long {
            if (sectorSizeBytes <= 0) {
                throw IllegalArgumentException("Sector size must be positive: $sectorSizeBytes")
            }
            if (!imageFile.exists()) {
                throw FileNotFoundException("Disk image file does not exist: ${imageFile.absolutePath}")
            }
            if (!imageFile.isFile) {
                throw IllegalArgumentException("Path is not a regular file: ${imageFile.absolutePath}")
            }
            val fileLength = imageFile.length()
            if (fileLength == 0L) {
                throw IllegalArgumentException("Existing disk image file is empty (0 bytes): ${imageFile.absolutePath}")
            }
            if (fileLength % sectorSizeBytes.toLong() != 0L) {
                throw IllegalArgumentException(
                    "Disk image file size ($fileLength bytes) is not an exact multiple of sector size ($sectorSizeBytes bytes)"
                )
            }
            val sectors = fileLength / sectorSizeBytes.toLong()
            validateGeometry(sectors, sectorSizeBytes)
            return sectors
        }

        /**
         * Opens an existing disk image file, deriving its total sector capacity from the file length.
         *
         * @param imageFile The existing disk image file to open.
         * @param sectorSizeBytes The sector size in bytes (default [DEFAULT_SECTOR_SIZE]).
         * @return An active [FileBackedBlockDevice] bound to the existing image file.
         */
        @JvmStatic
        @JvmOverloads
        fun openExisting(
            imageFile: File,
            sectorSizeBytes: Int = DEFAULT_SECTOR_SIZE
        ): FileBackedBlockDevice = FileBackedBlockDevice(imageFile, sectorSizeBytes)
    }
}
