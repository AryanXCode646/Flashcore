package com.ashishsinghbora.flashcore.block

import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer

/**
 * Thrown when a storage medium (such as a USB OTG drive or virtual disk)
 * is unexpectedly unplugged or disconnected during operation.
 */
class DeviceDisconnectedException(message: String = "Block device was disconnected") : IOException(message)

/**
 * Storage capacity and block geometry descriptor for a block storage medium.
 */
data class DeviceCapacity(
    val totalSectors: Long,
    val sectorSizeBytes: Int
) {
    val totalBytes: Long get() = totalSectors * sectorSizeBytes.toLong()

    val formattedCapacity: String
        get() {
            if (totalBytes <= 0L) return "Unknown"
            val gb = totalBytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
            return if (gb >= 1.0) "%.2f GB".format(gb) else "%.1f MB".format(totalBytes.toDouble() / (1024.0 * 1024.0))
        }
}

/**
 * Abstract Block Device Interface.
 *
 * Decouples flashing and filesystem engines from physical hardware.
 * Backends can represent USB OTG Mass Storage (SCSI BOT), in-memory mock devices for tests,
 * virtual disk files, or future SD Card / MMC controllers.
 */
interface BlockDevice : Closeable {
    val isConnected: Boolean
    val sectorSizeBytes: Int

    /**
     * Queries physical or virtual storage capacity and sector size.
     */
    @Throws(IOException::class)
    suspend fun capacity(): DeviceCapacity

    /**
     * Reads [blockCount] sectors starting at [lba] into [dest] buffer starting at [offset].
     * Returns true if all requested blocks were read successfully.
     */
    @Throws(IOException::class)
    suspend fun read(lba: Long, blockCount: Int, dest: ByteArray, offset: Int = 0): Boolean

    /**
     * Writes [blockCount] sectors starting at [lba] from [src] buffer starting at [offset].
     * Returns true if all requested blocks were written successfully.
     */
    @Throws(IOException::class)
    suspend fun write(lba: Long, blockCount: Int, src: ByteArray, offset: Int = 0): Boolean

    /**
     * Direct buffer write overload used by high-throughput streaming pipelines.
     */
    @Throws(IOException::class)
    suspend fun writeDirectBuffer(
        lba: Long,
        blockCount: Int,
        directBuffer: ByteBuffer,
        offset: Int,
        length: Int
    ): Boolean

    /**
     * Flushes any volatile drive write cache to non-volatile physical storage media.
     */
    @Throws(IOException::class)
    suspend fun flush(): Boolean
}
