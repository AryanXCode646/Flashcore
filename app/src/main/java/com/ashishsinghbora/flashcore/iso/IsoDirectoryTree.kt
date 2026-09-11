package com.ashishsinghbora.flashcore.iso

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * High-performance ISO Directory Tree Index and Traversal Manager.
 *
 * Recursively parses directory record extents from ISO 9660 PVD and Joliet SVD,
 * decodes UTF-16BE and ASCII filenames, normalizes paths, and indexes entries for fast lookup.
 */
class IsoDirectoryTree {

    private val entriesByPath = mutableMapOf<String, IsoFileEntry>()
    private val entriesList = mutableListOf<IsoFileEntry>()

    val allEntries: List<IsoFileEntry> get() = entriesList

    fun add(entry: IsoFileEntry) {
        val norm = normalizePath(entry.path)
        val existing = entriesByPath[norm]
        if (existing != null && !existing.isDirectory && !entry.isDirectory) {
            // ISO 9660 Level 3 multi-extent file: accumulate size across consecutive extents
            val updated = existing.copy(sizeBytes = existing.sizeBytes + entry.sizeBytes)
            entriesByPath[norm] = updated
            val idx = entriesList.indexOfFirst { normalizePath(it.path) == norm }
            if (idx >= 0) entriesList[idx] = updated
        } else {
            entriesByPath[norm] = entry
            entriesList.add(entry)
        }
    }

    fun find(path: String): IsoFileEntry? {
        val norm = normalizePath(path)
        return entriesByPath[norm]
    }

    fun contains(path: String): Boolean = find(path) != null

    fun listChildren(parentPath: String): List<IsoFileEntry> {
        val norm = normalizePath(parentPath)
        val prefix = if (norm == "/") "/" else "$norm/"
        return entriesList.filter {
            val p = normalizePath(it.path)
            if (!p.startsWith(prefix) || p == norm) return@filter false
            val rel = p.removePrefix(prefix)
            !rel.contains('/')
        }
    }

    companion object {
        const val SECTOR_SIZE = 2048

        fun normalizePath(path: String): String {
            val p = path.replace('\\', '/').trim().trimStart('/')
            return if (p.isEmpty()) "/" else "/$p".lowercase()
        }

        /**
         * Builds an [IsoDirectoryTree] by recursively traversing directory extents from an [IsoSource].
         */
        fun build(
            source: IsoSource,
            rootExtentLba: Long,
            rootDataLength: Long,
            isJoliet: Boolean
        ): IsoDirectoryTree {
            val tree = IsoDirectoryTree()
            tree.add(
                IsoFileEntry(
                    path = "/",
                    name = "ROOT",
                    lba = rootExtentLba,
                    sizeBytes = rootDataLength,
                    isDirectory = true
                )
            )

            scanExtent(
                source = source,
                tree = tree,
                parentPath = "",
                extentLba = rootExtentLba,
                dataLength = rootDataLength,
                isJoliet = isJoliet,
                visitedLbas = mutableSetOf()
            )

            return tree
        }

        private fun scanExtent(
            source: IsoSource,
            tree: IsoDirectoryTree,
            parentPath: String,
            extentLba: Long,
            dataLength: Long,
            isJoliet: Boolean,
            visitedLbas: MutableSet<Long>
        ) {
            if (!visitedLbas.add(extentLba)) return // Guard against circular loops
            val extentBytes = ByteArray(dataLength.toInt())
            val read = source.readAt(extentLba * SECTOR_SIZE, extentBytes, 0, extentBytes.size)
            if (read <= 0) return

            var offset = 0
            while (offset < extentBytes.size) {
                val recordLen = extentBytes[offset].toInt() and 0xFF
                if (recordLen == 0) {
                    // Sector padding: advance to next 2048-byte boundary
                    val nextSector = ((offset / SECTOR_SIZE) + 1) * SECTOR_SIZE
                    if (nextSector <= offset) break
                    offset = nextSector
                    continue
                }

                if (offset + recordLen > extentBytes.size) break

                val buf = ByteBuffer.wrap(extentBytes).order(ByteOrder.LITTLE_ENDIAN)
                val fileLba = buf.getInt(offset + 2).toLong() and 0xFFFFFFFFL
                val fileSize = buf.getInt(offset + 10).toLong() and 0xFFFFFFFFL
                val flags = extentBytes[offset + 25].toInt() and 0xFF
                val isDir = (flags and 0x02) != 0
                val nameLen = extentBytes[offset + 32].toInt() and 0xFF

                if (nameLen > 0 && offset + 33 + nameLen <= extentBytes.size) {
                    val isDot = (nameLen == 1 && extentBytes[offset + 33] == 0.toByte())
                    val isDotDot = (nameLen == 1 && extentBytes[offset + 33] == 1.toByte())

                    if (!isDot && !isDotDot) {
                        val rawName = if (isJoliet) {
                            String(extentBytes, offset + 33, nameLen, Charsets.UTF_16BE)
                        } else {
                            val ascii = String(extentBytes, offset + 33, nameLen, Charsets.US_ASCII)
                            ascii.substringBefore(';').removeSuffix(".")
                        }

                        val cleanName = rawName.trim()
                        if (cleanName.isNotEmpty()) {
                            val fullPath = if (parentPath.isEmpty()) "/$cleanName" else "$parentPath/$cleanName"
                            val entry = IsoFileEntry(
                                path = fullPath,
                                name = cleanName,
                                lba = fileLba,
                                sizeBytes = fileSize,
                                isDirectory = isDir
                            )
                            tree.add(entry)

                            if (isDir && fileSize > 0) {
                                scanExtent(source, tree, fullPath, fileLba, fileSize, isJoliet, visitedLbas)
                            }
                        }
                    }
                }

                offset += recordLen
            }
        }
    }
}
