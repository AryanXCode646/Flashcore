package com.example.dsa

import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Windows Imaging Format (WIM) Parser & SWM (Split WIM) Chunker.
 *
 * Handles inspection of `sources/install.wim` and automatic splitting into `.swm` parts
 * when file size exceeds the FAT32 4 GB boundary (4,294,967,295 bytes).
 */
object WimChunker {

    const val WIM_MAGIC = "MSWIM\u0000\u0000\u0000"
    const val WIM_HEADER_SIZE = 208

    // Flags in WIM Header
    const val FLAG_HEADER_RESERVED = 0x00000001
    const val FLAG_HEADER_COMPRESSION = 0x00000002
    const val FLAG_HEADER_READONLY = 0x00000004
    const val FLAG_HEADER_SPANNED = 0x00000008 // Set for .swm split files
    const val FLAG_HEADER_RESOURCE_ONLY = 0x00000010
    const val FLAG_HEADER_METADATA_ONLY = 0x00000020
    const val FLAG_HEADER_WRITE_IN_PROGRESS = 0x00000040
    const val FLAG_HEADER_RP_FIX = 0x00000080
    const val FLAG_HEADER_COMPRESS_RESERVED = 0x00010000
    const val FLAG_HEADER_COMPRESS_LZX = 0x00020000
    const val FLAG_HEADER_COMPRESS_XPRESS = 0x00040000

    data class WimHeader(
        val imageTag: String,
        val cbSize: Int,
        val dwVersion: Int,
        val flags: Int,
        val compressionChunkSize: Int,
        val guid: ByteArray,
        val partNumber: Short,
        val totalParts: Short,
        val imageCount: Int,
        val offsetTableOffset: Long,
        val offsetTableSize: Long,
        val xmlDataOffset: Long,
        val xmlDataSize: Long,
        val bootIndex: Int
    ) {
        val isSpanned: Boolean get() = (flags and FLAG_HEADER_SPANNED) != 0
        val isLzx: Boolean get() = (flags and FLAG_HEADER_COMPRESS_LZX) != 0
        val isXpress: Boolean get() = (flags and FLAG_HEADER_COMPRESS_XPRESS) != 0
    }

    /**
     * Parses the 208-byte WIM header from an InputStream or direct byte buffer.
     */
    fun parseHeader(stream: InputStream): WimHeader? {
        val headerBytes = ByteArray(WIM_HEADER_SIZE)
        var read = 0
        while (read < WIM_HEADER_SIZE) {
            val r = stream.read(headerBytes, read, WIM_HEADER_SIZE - read)
            if (r <= 0) break
            read += r
        }
        if (read < WIM_HEADER_SIZE) return null

        val buf = ByteBuffer.wrap(headerBytes).order(ByteOrder.LITTLE_ENDIAN)
        val tagBytes = ByteArray(8)
        buf.get(tagBytes)
        val tag = String(tagBytes, Charsets.US_ASCII)
        if (!tag.startsWith("MSWIM")) return null

        val cbSize = buf.int
        val dwVersion = buf.int
        val flags = buf.int
        val compChunkSize = buf.int

        val guid = ByteArray(16)
        buf.get(guid)

        val partNumber = buf.short
        val totalParts = buf.short
        val imageCount = buf.int

        // Offset Table (ResHD)
        val offsetTableOffset = buf.long
        val offsetTableSize = buf.long

        // XML Data (ResHD)
        val xmlDataOffset = buf.long
        val xmlDataSize = buf.long

        val bootMetadataOffset = buf.long
        val bootMetadataSize = buf.long
        val bootIndex = buf.int

        return WimHeader(
            imageTag = tag,
            cbSize = cbSize,
            dwVersion = dwVersion,
            flags = flags,
            compressionChunkSize = compChunkSize,
            guid = guid,
            partNumber = partNumber,
            totalParts = totalParts,
            imageCount = imageCount,
            offsetTableOffset = offsetTableOffset,
            offsetTableSize = offsetTableSize,
            xmlDataOffset = xmlDataOffset,
            xmlDataSize = xmlDataSize,
            bootIndex = bootIndex
        )
    }

    data class SwmPartSpec(
        val partIndex: Int,
        val totalParts: Int,
        val fileName: String, // e.g. "install.swm", "install2.swm"
        val startOffset: Long,
        val lengthBytes: Long
    )

    /**
     * Calculates the SWM split plan for an install.wim file.
     * Default target part max size is 3.8 GB (well below the 4.0 GB FAT32 limit).
     */
    fun planSwmSplit(
        totalSizeBytes: Long,
        maxChunkBytes: Long = 3900L * 1024L * 1024L // 3.9 GB
    ): List<SwmPartSpec> {
        val totalParts = ((totalSizeBytes + maxChunkBytes - 1) / maxChunkBytes).toInt().coerceAtLeast(1)
        val parts = mutableListOf<SwmPartSpec>()

        var currentOffset = 0L
        for (i in 1..totalParts) {
            val partLen = minOf(maxChunkBytes, totalSizeBytes - currentOffset)
            val name = if (i == 1) "install.swm" else "install$i.swm"
            parts.add(
                SwmPartSpec(
                    partIndex = i,
                    totalParts = totalParts,
                    fileName = name,
                    startOffset = currentOffset,
                    lengthBytes = partLen
                )
            )
            currentOffset += partLen
        }

        return parts
    }

    /**
     * Writes an updated SWM header for a split chunk.
     */
    fun createSwmHeader(
        original: WimHeader,
        partNumber: Short,
        totalParts: Short
    ): ByteArray {
        val buf = ByteBuffer.allocate(WIM_HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("MSWIM\u0000\u0000\u0000".toByteArray(Charsets.US_ASCII))
        buf.putInt(original.cbSize)
        buf.putInt(original.dwVersion)
        buf.putInt(original.flags or FLAG_HEADER_SPANNED)
        buf.putInt(original.compressionChunkSize)
        buf.put(original.guid)
        buf.putShort(partNumber)
        buf.putShort(totalParts)
        buf.putInt(original.imageCount)
        buf.putLong(original.offsetTableOffset)
        buf.putLong(original.offsetTableSize)
        buf.putLong(original.xmlDataOffset)
        buf.putLong(original.xmlDataSize)
        buf.putLong(0L) // boot metadata
        buf.putLong(0L)
        buf.putInt(original.bootIndex)

        // Pad remaining header bytes
        while (buf.hasRemaining()) {
            buf.put(0.toByte())
        }
        return buf.array()
    }
}
