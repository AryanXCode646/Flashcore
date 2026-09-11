package com.ashishsinghbora.flashcore

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reusable Tree-based synthetic ISO 9660 generator for multi-OS capability testing.
 */
class SyntheticIsoBuilder(
    val volumeLabel: String,
    val isIsohybrid: Boolean = false,
    val hasElToritoBios: Boolean = false,
    val hasElToritoEfi: Boolean = false
) {
    private class Node(
        val name: String,
        val isDirectory: Boolean,
        val content: ByteArray = ByteArray(0),
        val simulatedSize: Long = content.size.toLong()
    ) {
        var lba: Long = 0L
        val children = mutableListOf<Node>()

        fun getOrAddDir(dirName: String): Node {
            return children.firstOrNull { it.isDirectory && it.name.equals(dirName, ignoreCase = true) }
                ?: Node(dirName, true).also { children.add(it) }
        }

        fun addFile(fileName: String, bytes: ByteArray, simSize: Long) {
            children.add(Node(fileName, false, bytes, simSize))
        }
    }

    private val rootNode = Node("", true)

    fun addFile(path: String, content: ByteArray = ByteArray(0), simulatedSize: Long = content.size.toLong()): SyntheticIsoBuilder {
        val segments = path.replace('\\', '/').trim('/').split('/')
        var current = rootNode
        for (i in 0 until segments.size - 1) {
            current = current.getOrAddDir(segments[i])
        }
        current.addFile(segments.last(), content, simulatedSize)
        return this
    }

    fun build(): ByteArray {
        val totalSectors = 80
        val image = ByteArray(totalSectors * 2048)

        // 1. Sector 0: Isohybrid MBR
        if (isIsohybrid) {
            val mbr = ByteBuffer.wrap(image, 0, 512).order(ByteOrder.LITTLE_ENDIAN)
            mbr.position(510)
            mbr.put(0x55.toByte())
            mbr.put(0xAA.toByte())

            mbr.position(446)
            mbr.put(0x80.toByte()) // Bootable
            mbr.put(0x00.toByte())
            mbr.put(0x02.toByte())
            mbr.put(0x00.toByte())
            mbr.put(0x83.toByte()) // Linux Native type
            mbr.put(0xFF.toByte())
            mbr.put(0xFF.toByte())
            mbr.put(0xFF.toByte())
            mbr.putInt(0) // Start LBA 0
            mbr.putInt(totalSectors * 4) // Sectors in 512-byte blocks
        }

        // 2. Assign LBAs to directories and files
        val rootLba = 30L
        rootNode.lba = rootLba
        var currentLba = 31L

        // Assign directory LBAs first
        val allDirs = mutableListOf<Pair<Node, Node>>() // child, parent
        fun collectDirs(parent: Node) {
            for (child in parent.children) {
                if (child.isDirectory) {
                    child.lba = currentLba++
                    allDirs.add(Pair(child, parent))
                    collectDirs(child)
                }
            }
        }
        collectDirs(rootNode)

        // Assign file LBAs compactly in image
        val allFiles = mutableListOf<Node>()
        fun collectFiles(dir: Node) {
            for (child in dir.children) {
                if (!child.isDirectory) {
                    child.lba = currentLba
                    allFiles.add(child)
                    val contentSecs = ((child.content.size + 2047) / 2048).coerceAtLeast(1)
                    currentLba += contentSecs
                } else {
                    collectFiles(child)
                }
            }
        }
        collectFiles(rootNode)

        // 3. Sector 16: Primary Volume Descriptor (PVD)
        val pvdOffset = 16 * 2048
        val pvd = ByteBuffer.wrap(image, pvdOffset, 2048).slice().order(ByteOrder.LITTLE_ENDIAN)
        pvd.put(1.toByte()) // Type 1: PVD
        pvd.put("CD001".toByteArray(Charsets.US_ASCII))
        pvd.put(1.toByte()) // Version

        val volBytes = volumeLabel.padEnd(32, ' ').toByteArray(Charsets.US_ASCII)
        System.arraycopy(volBytes, 0, image, pvdOffset + 40, 32)

        // Volume space size (both endian)
        pvd.position(80)
        pvd.putInt(totalSectors)
        pvd.order(ByteOrder.BIG_ENDIAN)
        pvd.putInt(totalSectors)
        pvd.order(ByteOrder.LITTLE_ENDIAN)

        // Logical block size 2048
        pvd.position(128)
        pvd.putShort(2048.toShort())
        pvd.order(ByteOrder.BIG_ENDIAN)
        pvd.putShort(2048.toShort())
        pvd.order(ByteOrder.LITTLE_ENDIAN)

        // Root directory record at offset 156
        writeRecord(image, pvdOffset + 156, rootLba, 2048L, true, "\u0000")

        // 4. Sector 17: El Torito Boot Record
        if (hasElToritoBios || hasElToritoEfi) {
            val brOffset = 17 * 2048
            val br = ByteBuffer.wrap(image, brOffset, 2048).slice().order(ByteOrder.LITTLE_ENDIAN)
            br.put(0.toByte()) // Type 0: Boot Record
            br.put("CD001".toByteArray(Charsets.US_ASCII))
            br.put(1.toByte()) // Version
            val elToritoId = "EL TORITO SPECIFICATION".padEnd(32, '\u0000').toByteArray(Charsets.US_ASCII)
            System.arraycopy(elToritoId, 0, image, brOffset + 7, 32)

            val catalogLba = 20L
            br.position(71)
            br.putInt(catalogLba.toInt())

            // Write Boot Catalog at Sector 20
            val catOffset = 20 * 2048
            val cat = ByteBuffer.wrap(image, catOffset, 2048).slice().order(ByteOrder.LITTLE_ENDIAN)

            // Validation Entry (offset 0)
            cat.put(0x01.toByte())
            cat.put(0x00.toByte()) // x86
            cat.putShort(0.toShort())
            val mfg = "Flashcore".padEnd(24, ' ').toByteArray(Charsets.US_ASCII)
            cat.put(mfg)
            cat.putShort(0.toShort())
            cat.put(0x55.toByte())
            cat.put(0xAA.toByte())

            // Initial / Default Entry (offset 32)
            cat.position(32)
            cat.put((if (hasElToritoBios) 0x88 else 0x00).toByte())
            cat.put(0x00.toByte()) // No emulation
            cat.putShort(0.toShort())
            cat.put(0.toByte())
            cat.put(0.toByte())
            cat.putShort(4.toShort())
            cat.putInt(22) // Load RBA 22

            // Section Header & Entry for EFI (offset 64 & 96)
            if (hasElToritoEfi) {
                cat.position(64)
                cat.put(0x91.toByte()) // Final Section Header
                cat.put(0xEF.toByte()) // Platform EFI
                cat.putShort(1.toShort())

                cat.position(96)
                cat.put(0x88.toByte()) // Bootable
                cat.put(0x00.toByte()) // No emulation
                cat.position(96 + 8)
                cat.putInt(23) // Load RBA 23
            }
        }

        // 5. Volume Descriptor Set Terminator (Sector 18)
        val termOffset = 18 * 2048
        image[termOffset] = 255.toByte() // Type 255: Terminator
        System.arraycopy("CD001".toByteArray(Charsets.US_ASCII), 0, image, termOffset + 1, 5)
        image[termOffset + 6] = 1.toByte()

        // 6. Write Root Directory (Sector 30)
        writeDirectorySector(image, rootNode, rootLba, rootLba)

        // 7. Write Subdirectories
        for ((childDir, parentDir) in allDirs) {
            writeDirectorySector(image, childDir, childDir.lba, parentDir.lba)
        }

        // 8. Write File Payloads
        for (file in allFiles) {
            if (file.content.isNotEmpty()) {
                val dest = (file.lba * 2048).toInt()
                if (dest >= 0 && dest < image.size) {
                    System.arraycopy(file.content, 0, image, dest, minOf(file.content.size, image.size - dest))
                }
            }
        }

        return image
    }

    private fun writeDirectorySector(dest: ByteArray, dirNode: Node, dirLba: Long, parentLba: Long) {
        var pos = (dirLba * 2048).toInt()
        // Record 0: '.'
        pos += writeRecord(dest, pos, dirLba, 2048L, true, "\u0000")
        // Record 1: '..'
        pos += writeRecord(dest, pos, parentLba, 2048L, true, "\u0001")

        for (child in dirNode.children) {
            val dataLen = if (child.isDirectory) 2048L else maxOf(child.content.size.toLong(), child.simulatedSize)
            val isoName = if (child.isDirectory) child.name else child.name + ";1"
            if (!child.isDirectory && dataLen > 0xFFFFFFFFL) {
                val chunk1 = 3000000000L
                val chunk2 = dataLen - chunk1
                pos += writeRecord(dest, pos, child.lba, chunk1, false, isoName, flags = 0x80)
                pos += writeRecord(dest, pos, child.lba + 1, chunk2, false, isoName, flags = 0x00)
            } else {
                pos += writeRecord(dest, pos, child.lba, dataLen, child.isDirectory, isoName)
            }
        }
    }

    private fun writeRecord(
        dest: ByteArray,
        offset: Int,
        extentLba: Long,
        dataLength: Long,
        isDirectory: Boolean,
        name: String,
        flags: Int = if (isDirectory) 0x02 else 0x00
    ): Int {
        val nameBytes = name.toByteArray(Charsets.US_ASCII)
        var recordLen = 33 + nameBytes.size
        if (recordLen % 2 != 0) recordLen++ // Must be even

        val buf = ByteBuffer.wrap(dest, offset, recordLen).slice().order(ByteOrder.LITTLE_ENDIAN)
        buf.put(recordLen.toByte())
        buf.put(0.toByte()) // Extended attr length

        buf.putInt(extentLba.toInt())
        buf.order(ByteOrder.BIG_ENDIAN)
        buf.putInt(extentLba.toInt())
        buf.order(ByteOrder.LITTLE_ENDIAN)

        buf.putInt(dataLength.toInt())
        buf.order(ByteOrder.BIG_ENDIAN)
        buf.putInt(dataLength.toInt())
        buf.order(ByteOrder.LITTLE_ENDIAN)

        for (i in 0 until 7) buf.put(0.toByte()) // Date
        buf.put(flags.toByte()) // Flags (0x80 for multi-extent, 0x02 for dir)
        buf.put(0.toByte())
        buf.put(0.toByte())
        buf.putShort(1.toShort())
        buf.putShort(1.toShort())

        buf.put(nameBytes.size.toByte())
        buf.put(nameBytes)

        return recordLen
    }
}
