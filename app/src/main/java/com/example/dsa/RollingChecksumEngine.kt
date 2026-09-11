package com.example.dsa

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.zip.CRC32

/**
 * High-performance Rolling Block Checksum Engine.
 *
 * Computes streaming SHA-256 and fast CRC32/MurmurHash3 digests during sector streaming
 * to detect bad sectors or bitflips without restarting full flash operations.
 */
object RollingChecksumEngine {

    /**
     * Computes fast 32-bit CRC32 checksum for a direct or array ByteBuffer.
     */
    fun computeCrc32(buffer: ByteBuffer, offset: Int, length: Int): Long {
        val crc = CRC32()
        if (buffer.hasArray()) {
            crc.update(buffer.array(), buffer.arrayOffset() + offset, length)
        } else {
            val temp = ByteArray(length)
            val dup = buffer.duplicate()
            dup.position(offset)
            dup.get(temp, 0, length)
            crc.update(temp, 0, length)
        }
        return crc.value
    }

    fun computeCrc32(data: ByteArray, offset: Int = 0, length: Int = data.size): Long {
        val crc = CRC32()
        crc.update(data, offset, length)
        return crc.value
    }

    /**
     * Computes MurmurHash3 32-bit x86 hash for fast rolling checksums.
     */
    fun murmurHash3(data: ByteArray, offset: Int = 0, length: Int = data.size, seed: Int = 0x9747b28c.toInt()): Int {
        val c1 = 0xcc9e2d51.toInt()
        val c2 = 0x1b873593
        var h1 = seed
        val roundedEnd = offset + (length and 0xFFFFFFFC.toInt()) // round down to 4 byte boundary

        var i = offset
        while (i < roundedEnd) {
            var k1 = (data[i].toInt() and 0xFF) or
                    ((data[i + 1].toInt() and 0xFF) shl 8) or
                    ((data[i + 2].toInt() and 0xFF) shl 16) or
                    (data[i + 3].toInt() shl 24)

            k1 *= c1
            k1 = (k1 shl 15) or (k1 ushr 17)
            k1 *= c2

            h1 = h1 xor k1
            h1 = (h1 shl 13) or (h1 ushr 19)
            h1 = h1 * 5 + 0xe6546b64.toInt()
            i += 4
        }

        var k1 = 0
        val tail = length and 0x03
        if (tail == 3) k1 = k1 xor ((data[roundedEnd + 2].toInt() and 0xFF) shl 16)
        if (tail >= 2) k1 = k1 xor ((data[roundedEnd + 1].toInt() and 0xFF) shl 8)
        if (tail >= 1) {
            k1 = k1 xor (data[roundedEnd].toInt() and 0xFF)
            k1 *= c1
            k1 = (k1 shl 15) or (k1 ushr 17)
            k1 *= c2
            h1 = h1 xor k1
        }

        h1 = h1 xor length
        h1 = h1 xor (h1 ushr 16)
        h1 *= 0x85ebca6b.toInt()
        h1 = h1 xor (h1 ushr 13)
        h1 *= 0xc2b2ae35.toInt()
        h1 = h1 xor (h1 ushr 16)

        return h1
    }

    /**
     * Computes cryptographic SHA-256 hex digest.
     */
    fun sha256Hex(data: ByteArray, offset: Int = 0, length: Int = data.size): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(data, offset, length)
        val digest = md.digest()
        return digest.joinToString("") { "%02x".format(it) }
    }

    class RollingSha256 {
        private val md = MessageDigest.getInstance("SHA-256")

        fun update(buffer: ByteBuffer, offset: Int, length: Int) {
            val originalPos = buffer.position()
            val originalLimit = buffer.limit()
            buffer.position(offset)
            buffer.limit(offset + length)
            md.update(buffer)
            buffer.position(originalPos)
            buffer.limit(originalLimit)
        }

        fun update(data: ByteArray, offset: Int = 0, length: Int = data.size) {
            md.update(data, offset, length)
        }

        fun digestHex(): String {
            val d = md.digest()
            return d.joinToString("") { "%02x".format(it) }
        }
    }
}
