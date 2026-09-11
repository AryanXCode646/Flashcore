package com.ashishsinghbora.flashcore.iso

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Parsed ISO 9660 Primary Volume Descriptor (PVD) located at Sector 16.
 */
data class PrimaryVolumeDescriptor(
    val systemId: String,
    val volumeId: String,
    val volumeSpaceBlocks: Long,
    val logicalBlockSizeBytes: Int,
    val rootExtentLba: Long,
    val rootDataLengthBytes: Long,
    val publisherId: String,
    val preparerId: String,
    val applicationId: String,
    val creationDateStr: String
) {
    companion object {
        const val SECTOR_SIZE = 2048
        const val PVD_SECTOR_LBA = 16L
        const val MAGIC = "CD001"

        fun parse(sector: ByteArray): PrimaryVolumeDescriptor? {
            if (sector.size < SECTOR_SIZE) return null
            val type = sector[0].toInt() and 0xFF
            val magic = String(sector, 1, 5, Charsets.US_ASCII)
            if (magic != MAGIC || type != 1) return null

            val systemId = String(sector, 8, 32, Charsets.US_ASCII).trim()
            val volumeId = String(sector, 40, 32, Charsets.US_ASCII).trim()

            val buf = ByteBuffer.wrap(sector).order(ByteOrder.LITTLE_ENDIAN)
            val volumeSpaceBlocks = buf.getInt(80).toLong() and 0xFFFFFFFFL
            val logicalBlockSize = buf.getShort(128).toInt() and 0xFFFF

            // Root directory record at offset 156
            val rootExtentLba = buf.getInt(156 + 2).toLong() and 0xFFFFFFFFL
            val rootDataLength = buf.getInt(156 + 10).toLong() and 0xFFFFFFFFL

            val publisherId = String(sector, 318, 128, Charsets.US_ASCII).trim()
            val preparerId = String(sector, 446, 128, Charsets.US_ASCII).trim()
            val applicationId = String(sector, 574, 128, Charsets.US_ASCII).trim()
            val creationDate = String(sector, 813, 17, Charsets.US_ASCII).trim()

            return PrimaryVolumeDescriptor(
                systemId = systemId,
                volumeId = volumeId,
                volumeSpaceBlocks = volumeSpaceBlocks,
                logicalBlockSizeBytes = if (logicalBlockSize > 0) logicalBlockSize else 2048,
                rootExtentLba = rootExtentLba,
                rootDataLengthBytes = rootDataLength,
                publisherId = publisherId,
                preparerId = preparerId,
                applicationId = applicationId,
                creationDateStr = creationDate
            )
        }
    }
}
