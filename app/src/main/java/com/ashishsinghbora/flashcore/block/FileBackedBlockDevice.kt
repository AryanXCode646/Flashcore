package com.ashishsinghbora.flashcore.block

import java.io.File
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
 */
class FileBackedBlockDevice(
    val imageFile: File,
    val totalSectors: Long,
    override val sectorSizeBytes: Int = 512,
    preallocate: Boolean = false
) : BlockDevice {

    private val raf: RandomAccessFile
    private val channel: FileChannel

    @Volatile
    override var isConnected: Boolean = true
        private set

    init {
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

    override suspend fun capacity(): DeviceCapacity {
        checkConnected()
        return DeviceCapacity(totalSectors, sectorSizeBytes)
    }

    override suspend fun read(lba: Long, blockCount: Int, dest: ByteArray, offset: Int): Boolean {
        currentCoroutineContext().ensureActive()
        checkConnected()
        validateBounds(lba, blockCount)

        val totalBytes = blockCount * sectorSizeBytes
        if (offset < 0 || offset + totalBytes > dest.size) {
            throw IndexOutOfBoundsException("Buffer offset $offset + $totalBytes exceeds buffer size ${dest.size}")
        }

        val fileOffset = lba * sectorSizeBytes.toLong()
        synchronized(raf) {
            val fileLength = raf.length()
            if (fileOffset >= fileLength) {
                // Reading beyond current file length returns zeroes
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

            // If file ended before totalBytes, pad remainder with zeroes
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

        val totalBytes = blockCount * sectorSizeBytes
        if (offset < 0 || offset + totalBytes > src.size) {
            throw IndexOutOfBoundsException("Buffer offset $offset + $totalBytes exceeds buffer size ${src.size}")
        }

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

        val fileOffset = lba * sectorSizeBytes.toLong()
        val slice = directBuffer.duplicate()
        slice.position(offset)
        slice.limit(offset + length)

        synchronized(raf) {
            channel.position(fileOffset)
            while (slice.hasRemaining()) {
                channel.write(slice)
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
        if (!isConnected) throw DeviceDisconnectedException("FileBackedBlockDevice is closed/disconnected")
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
        try { channel.close() } catch (_: Exception) {}
        try { raf.close() } catch (_: Exception) {}
    }
}
