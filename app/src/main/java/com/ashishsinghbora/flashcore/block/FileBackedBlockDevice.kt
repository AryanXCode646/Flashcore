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
 *    The backing file may be preallocated (physical length == totalBytes) or sparse
 *    (physical length on disk <= totalBytes; unwritten regions return deterministic zeroes).
 * 2. Existing Image Auto-Capacity: When opened via [openExisting] or secondary constructor
 *    without [totalSectors], the sector count is derived from the backing file's physical
 *    byte length (`imageFile.length() / sectorSizeBytes`). The file must already exist, be
 *    non-empty, and its size must be an exact integer multiple of [sectorSizeBytes].
 */
class FileBackedBlockDevice @JvmOverloads constructor(
    val imageFile: File,
    val totalSectors: Long,
    override val sectorSizeBytes: Int = DEFAULT_SECTOR_SIZE,
    preallocate: Boolean = false
) : BlockDevice {

    private val raf: RandomAccessFile
    private val channel: FileChannel

    @Volatile
    override var isConnected: Boolean = true
        private set

    val totalCapacityBytes: Long
        get() = totalSectors * sectorSizeBytes.toLong()

    init {
        validateGeometry(totalSectors, sectorSizeBytes)
        if (!imageFile.exists()) {
            imageFile.parentFile?.mkdirs()
            imageFile.createNewFile()
        }
        raf = RandomAccessFile(imageFile, "rw")
        channel = raf.channel

        val requiredBytes = totalSectors * sectorSizeBytes.toLong()
        if (preallocate && raf.length() < requiredBytes) {
            raf.setLength(requiredBytes)
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
        synchronized(raf) {
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
        synchronized(raf) {
            raf.seek(fileOffset)
            raf.write(src, offset, totalBytes)
        }
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
                "Requested range [$offset..${offset.toLong() + length.toLong()}] exceeds direct buffer limit ($bufferLimit)"
            )
        }

        val fileOffset = lba * sectorSizeBytes.toLong()
        val slice = directBuffer.duplicate()
        slice.position(offset)
        slice.limit(offset + length)

        synchronized(raf) {
            channel.position(fileOffset)
            while (slice.hasRemaining()) {
                val written = channel.write(slice)
                if (written < 0) {
                    throw IOException("Unexpected EOF while writing direct buffer to file channel")
                }
            }
        }
        return true
    }

    override suspend fun flush(): Boolean {
        currentCoroutineContext().ensureActive()
        checkConnected()
        synchronized(raf) {
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
