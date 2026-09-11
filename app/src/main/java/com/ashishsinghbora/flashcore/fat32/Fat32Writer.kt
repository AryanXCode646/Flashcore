package com.ashishsinghbora.flashcore.fat32

import com.ashishsinghbora.flashcore.block.BlockDevice
import java.io.FileNotFoundException
import java.io.InputStream

/**
 * Real FAT32 Filesystem Writer & Subsystem.
 *
 * Provides a unified filesystem API capable of formatting, recursive directory creation (mkdir),
 * file creation, writing, appending, reading, cluster allocation, FAT table synchronization,
 * and FSInfo state tracking on top of any [BlockDevice].
 *
 * Architecture:
 * FAT32Writer
 *  ├── BootSector (Fat32BootSector)
 *  ├── FSInfo (Fat32FsInfo)
 *  ├── FAT (Fat32Table)
 *  ├── ClusterAllocator (Fat32ClusterAllocator)
 *  ├── Directory (Fat32Directory)
 *  └── FileWriter (Fat32FileWriter)
 */
class Fat32Writer private constructor(
    val device: BlockDevice,
    var bootSector: Fat32BootSector,
    var fsInfo: Fat32FsInfo
) {
    var fatTable = Fat32Table(device, bootSector)
        private set
    var clusterAllocator = Fat32ClusterAllocator(device, bootSector, fatTable, fsInfo)
        private set
    var directory = Fat32Directory(device, bootSector, fatTable, clusterAllocator)
        private set
    var fileWriter = Fat32FileWriter(device, bootSector, fatTable, clusterAllocator, directory)
        private set

    /**
     * Formats the target [BlockDevice] partition with a fresh FAT32 filesystem.
     * Writes Sector 0 (VBR), Sector 6 (Backup VBR), Sector 1 (FSInfo), Sector 7 (Backup FSInfo),
     * initializes FAT1 & FAT2 tables, zeroes Root Directory (Cluster 2), and writes volume label.
     */
    suspend fun formatVolume(volumeLabel: String = "BOOT_MEDIA"): Boolean {
        // 1. Write Volume Boot Record (Sector 0 and Backup Sector 6)
        val vbrBytes = bootSector.serialize()
        device.write(0L, 1, vbrBytes)
        device.write(bootSector.backupBootSector.toLong(), 1, vbrBytes)

        // 2. Initialize FSInfo (Free clusters = totalDataClusters - 1 for root cluster)
        fsInfo.freeClusters = (bootSector.totalDataClusters - 1L).coerceAtLeast(0L)
        fsInfo.nextFreeClusterHint = 3L
        val fsInfoBytes = fsInfo.serialize(bootSector.sectorSizeBytes)
        device.write(bootSector.fsInfoSector.toLong(), 1, fsInfoBytes)
        device.write((bootSector.fsInfoSector + bootSector.backupBootSector).toLong(), 1, fsInfoBytes)

        // 3. Initialize FAT1 & FAT2 tables
        fatTable.initializeTable()

        // 4. Initialize Root Directory (Cluster 2) with Volume Label
        clusterAllocator.zeroCluster(bootSector.rootCluster)

        val cleanLabel = volumeLabel.padEnd(11, ' ').take(11).uppercase()
        val rootDirLba = bootSector.clusterToLba(bootSector.rootCluster)
        val rootSector = ByteArray(bootSector.sectorSizeBytes)
        System.arraycopy(cleanLabel.toByteArray(Charsets.US_ASCII), 0, rootSector, 0, 11)
        rootSector[11] = Fat32DirectoryEntry.ATTR_VOLUME_ID.toByte()
        device.write(rootDirLba, 1, rootSector)

        // 5. Commit state
        flush()
        return true
    }

    /**
     * Creates a directory (including intermediate parent directories if missing).
     * Returns the cluster number of the newly created or existing directory.
     */
    suspend fun mkdir(path: String): Long {
        val segments = parsePathSegments(path)
        if (segments.isEmpty()) return bootSector.rootCluster

        var currentCluster = bootSector.rootCluster
        for (seg in segments) {
            val existing = directory.findEntry(currentCluster, seg)
            if (existing != null) {
                if (!existing.first.isDirectory) {
                    throw IllegalStateException("Path component '$seg' in '$path' exists and is not a directory")
                }
                currentCluster = existing.first.firstCluster
            } else {
                // Allocate new cluster for this directory
                val newDirCluster = clusterAllocator.allocateCluster(zeroOut = true)
                // Initialize '.' and '..' entries
                directory.initializeSubdirectoryCluster(newDirCluster, currentCluster)
                // Add entry into parent directory
                directory.addEntry(
                    dirCluster = currentCluster,
                    entryName = seg,
                    attributes = Fat32DirectoryEntry.ATTR_DIRECTORY,
                    firstCluster = newDirCluster,
                    fileSizeBytes = 0L
                )
                currentCluster = newDirCluster
            }
        }

        flush()
        return currentCluster
    }

    /**
     * Creates an empty file at [path], creating parent directories if necessary.
     */
    suspend fun createFile(path: String, attributes: Int = Fat32DirectoryEntry.ATTR_ARCHIVE): Fat32DirectoryEntry {
        val (parentCluster, fileName) = resolveParentAndName(path, autoCreateDirs = true)
        return fileWriter.writeFile(parentCluster, fileName, ByteArray(0), attributes)
    }

    /**
     * Writes byte array data to [path]. Creates parent directories automatically.
     */
    suspend fun writeFile(path: String, data: ByteArray, attributes: Int = Fat32DirectoryEntry.ATTR_ARCHIVE): Fat32DirectoryEntry {
        val (parentCluster, fileName) = resolveParentAndName(path, autoCreateDirs = true)
        val entry = fileWriter.writeFile(parentCluster, fileName, data, attributes)
        flush()
        return entry
    }

    /**
     * Streams data from an [InputStream] into a FAT32 file at [path].
     */
    suspend fun writeFileStream(
        path: String,
        inputStream: InputStream,
        totalBytes: Long,
        attributes: Int = Fat32DirectoryEntry.ATTR_ARCHIVE,
        onProgress: ((bytesWritten: Long, total: Long) -> Unit)? = null
    ): Fat32DirectoryEntry {
        val (parentCluster, fileName) = resolveParentAndName(path, autoCreateDirs = true)
        val entry = fileWriter.writeFileStream(parentCluster, fileName, inputStream, totalBytes, attributes, onProgress)
        flush()
        return entry
    }

    /**
     * Appends data to an existing file at [path] (or creates it if it does not exist).
     */
    suspend fun appendFile(path: String, appendData: ByteArray): Fat32DirectoryEntry {
        val (parentCluster, fileName) = resolveParentAndName(path, autoCreateDirs = true)
        val entry = fileWriter.appendFile(parentCluster, fileName, appendData)
        flush()
        return entry
    }

    /**
     * Reads all bytes from a file at [path].
     */
    suspend fun readFile(path: String): ByteArray {
        val (parentCluster, fileName) = resolveParentAndName(path, autoCreateDirs = false)
        return fileWriter.readFile(parentCluster, fileName)
    }

    /**
     * Opens an [InputStream] over a file at [path].
     */
    suspend fun openInputStream(path: String): InputStream {
        val (parentCluster, fileName) = resolveParentAndName(path, autoCreateDirs = false)
        return fileWriter.readFileStream(parentCluster, fileName)
    }

    /**
     * Checks if a file or directory exists at [path].
     */
    suspend fun exists(path: String): Boolean {
        return try {
            val segments = parsePathSegments(path)
            if (segments.isEmpty()) return true
            val (parentCluster, fileName) = resolveParentAndName(path, autoCreateDirs = false)
            directory.findEntry(parentCluster, fileName) != null
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Lists directory entries at [path] (e.g. "/" or "/EFI").
     */
    suspend fun list(path: String = "/", includeSpecialEntries: Boolean = false): List<Fat32DirectoryEntry> {
        val segments = parsePathSegments(path)
        var currentCluster = bootSector.rootCluster

        for (seg in segments) {
            val found = directory.findEntry(currentCluster, seg)
                ?: throw FileNotFoundException("Directory not found: $seg in $path")
            if (!found.first.isDirectory) {
                throw IllegalArgumentException("Path component '$seg' is not a directory")
            }
            currentCluster = found.first.firstCluster
        }

        val entries = directory.listEntries(currentCluster).map { it.first }
        return if (includeSpecialEntries) entries else entries.filter { it.name != "." && it.name != ".." }
    }

    /**
     * Synchronizes cached FAT sectors, FSInfo state, and flushes the underlying [BlockDevice].
     */
    suspend fun flush() {
        fatTable.flush()

        // Update FSInfo sectors
        val fsInfoBytes = fsInfo.serialize(bootSector.sectorSizeBytes)
        device.write(bootSector.fsInfoSector.toLong(), 1, fsInfoBytes)
        device.write((bootSector.fsInfoSector + bootSector.backupBootSector).toLong(), 1, fsInfoBytes)

        device.flush()
    }

    private suspend fun resolveParentAndName(path: String, autoCreateDirs: Boolean): Pair<Long, String> {
        val segments = parsePathSegments(path)
        if (segments.isEmpty()) {
            throw IllegalArgumentException("Path cannot be empty: $path")
        }

        val fileName = segments.last()
        val parentSegments = segments.dropLast(1)

        var currentCluster = bootSector.rootCluster
        for (dir in parentSegments) {
            val found = directory.findEntry(currentCluster, dir)
            if (found != null) {
                if (!found.first.isDirectory) {
                    throw IllegalStateException("Path element '$dir' is not a directory")
                }
                currentCluster = found.first.firstCluster
            } else if (autoCreateDirs) {
                // Auto create missing directory
                val newDirCluster = clusterAllocator.allocateCluster(zeroOut = true)
                directory.initializeSubdirectoryCluster(newDirCluster, currentCluster)
                directory.addEntry(
                    dirCluster = currentCluster,
                    entryName = dir,
                    attributes = Fat32DirectoryEntry.ATTR_DIRECTORY,
                    firstCluster = newDirCluster,
                    fileSizeBytes = 0L
                )
                currentCluster = newDirCluster
            } else {
                throw FileNotFoundException("Directory '$dir' in path '$path' not found")
            }
        }

        return Pair(currentCluster, fileName)
    }

    private fun parsePathSegments(path: String): List<String> {
        return path.replace('\\', '/')
            .split('/')
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != "." }
    }

    companion object {
        /**
         * Creates a new [Fat32Writer] configured for a partition with [totalSectors].
         */
        fun createNew(
            device: BlockDevice,
            totalSectors: Long,
            volumeLabel: String = "BOOT_MEDIA"
        ): Fat32Writer {
            val boot = Fat32BootSector.create(totalSectors, volumeLabel)
            val fsInfo = Fat32FsInfo()
            return Fat32Writer(device, boot, fsInfo)
        }

        /**
         * Mounts an existing FAT32 filesystem from [device] by reading Sector 0 and Sector 1.
         */
        suspend fun mount(device: BlockDevice): Fat32Writer {
            val vbrBytes = ByteArray(512)
            val readVbr = device.read(0L, 1, vbrBytes)
            if (!readVbr) throw IllegalStateException("Failed to read Sector 0 for FAT32 VBR")

            val boot = Fat32BootSector.parse(vbrBytes)

            val fsInfoBytes = ByteArray(boot.sectorSizeBytes)
            val readFsInfo = device.read(boot.fsInfoSector.toLong(), 1, fsInfoBytes)
            val fsInfo = if (readFsInfo) {
                try {
                    Fat32FsInfo.parse(fsInfoBytes)
                } catch (e: Exception) {
                    Fat32FsInfo()
                }
            } else {
                Fat32FsInfo()
            }

            return Fat32Writer(device, boot, fsInfo)
        }
    }
}
