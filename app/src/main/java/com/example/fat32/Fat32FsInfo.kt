package com.example.fat32

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * FAT32 FSInfo (File System Information) Sector Manager.
 *
 * Tracks the number of free clusters and the next free cluster allocation hint.
 * Stored at Sector 1 (and backup at Sector 7) of the FAT32 volume.
 */
data class Fat32FsInfo(
    var freeClusters: Long = -1L,
    var nextFreeClusterHint: Long = 3L
) {
    /**
     * Serializes FSInfo structure into a standard 512-byte sector.
     */
    fun serialize(sectorSize: Int = 512): ByteArray {
        val bytes = ByteArray(sectorSize)
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        // Lead signature "RRaA" (0x41615252)
        buf.putInt(LEAD_SIGNATURE)

        // 480 bytes reserved (zeros)
        for (i in 0 until 480) {
            buf.put(0.toByte())
        }

        // Struct signature "rrAa" (0x61417272)
        buf.putInt(STRUCT_SIGNATURE)

        // Free cluster count (-1 / 0xFFFFFFFF if unknown)
        buf.putInt((freeClusters and 0xFFFFFFFFL).toInt())

        // Next free cluster hint
        buf.putInt((nextFreeClusterHint and 0xFFFFFFFFL).toInt())

        // 12 bytes reserved
        for (i in 0 until 12) {
            buf.put(0.toByte())
        }

        // Trail signature (0xAA550000: 0x00, 0x00, 0x55, 0xAA)
        buf.putInt(TRAIL_SIGNATURE)

        return bytes
    }

    companion object {
        const val LEAD_SIGNATURE = 0x41615252
        const val STRUCT_SIGNATURE = 0x61417272
        const val TRAIL_SIGNATURE = 0xAA550000.toInt()

        /**
         * Parses a 512-byte sector as an FSInfo sector.
         */
        fun parse(sectorBytes: ByteArray): Fat32FsInfo {
            require(sectorBytes.size >= 512) { "Sector bytes too short: ${sectorBytes.size}" }
            val buf = ByteBuffer.wrap(sectorBytes).order(ByteOrder.LITTLE_ENDIAN)

            val lead = buf.getInt(0)
            val struct = buf.getInt(484)
            val trail = buf.getInt(508)

            require(lead == LEAD_SIGNATURE) {
                "Invalid FSInfo lead signature: 0x${"%08X".format(lead)}"
            }
            require(struct == STRUCT_SIGNATURE) {
                "Invalid FSInfo struct signature: 0x${"%08X".format(struct)}"
            }
            require(trail == TRAIL_SIGNATURE) {
                "Invalid FSInfo trail signature: 0x${"%08X".format(trail)}"
            }

            val freeCount = buf.getInt(488).toLong() and 0xFFFFFFFFL
            val nextHint = buf.getInt(492).toLong() and 0xFFFFFFFFL

            return Fat32FsInfo(
                freeClusters = if (freeCount == 0xFFFFFFFFL) -1L else freeCount,
                nextFreeClusterHint = nextHint
            )
        }
    }
}
