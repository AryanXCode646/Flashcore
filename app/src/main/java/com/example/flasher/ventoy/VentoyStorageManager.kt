package com.example.flasher.ventoy

import com.example.block.BlockDevice
import com.example.fat32.Fat32DirectoryEntry
import com.example.fat32.Fat32Writer
import java.io.InputStream
import java.util.Locale

/**
 * Manages bootable image files (.iso, .img, .wim, .vhd) and configuration on the Ventoy data partition.
 *
 * Unlike raw sector flashing, Ventoy stores OS images as regular files in the filesystem.
 */
object VentoyStorageManager {

    private val BOOTABLE_EXTENSIONS = setOf("iso", "img", "wim", "vhd", "vhdx", "efi")

    private const val DEFAULT_VENTOY_JSON = """{
  "control": [
    { "VTOY_DEFAULT_SEARCH_ROOT": "/ISO" },
    { "VTOY_MENU_TIMEOUT": "10" },
    { "VTOY_DEFAULT_IMAGE": "" }
  ]
}
"""

    /**
     * Lists all bootable OS images stored in the Ventoy data partition filesystem.
     */
    suspend fun listStoredImages(dataPartitionDevice: BlockDevice): List<VentoyIsoFile> {
        val result = mutableListOf<VentoyIsoFile>()
        val writer = try {
            Fat32Writer.mount(dataPartitionDevice)
        } catch (_: Exception) {
            return emptyList()
        }

        suspend fun scanDir(dirPath: String) {
            val entries = try {
                writer.list(dirPath)
            } catch (_: Exception) {
                return
            }

            for (entry in entries) {
                if (entry.isDirectory) {
                    val subPath = if (dirPath == "/") "/${entry.name}" else "$dirPath/${entry.name}"
                    // Don't recurse into system /ventoy directory
                    if (!entry.name.equals("ventoy", ignoreCase = true) && !entry.name.equals("System Volume Information", ignoreCase = true)) {
                        scanDir(subPath)
                    }
                } else {
                    val ext = entry.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
                    if (ext in BOOTABLE_EXTENSIONS) {
                        val filePath = if (dirPath == "/") "/${entry.name}" else "$dirPath/${entry.name}"
                        result.add(
                            VentoyIsoFile(
                                fileName = entry.name,
                                relativePath = filePath,
                                sizeBytes = entry.fileSizeBytes
                            )
                        )
                    }
                }
            }
        }

        scanDir("/")
        return result
    }

    /**
     * Stores a bootable ISO image stream into the Ventoy data partition filesystem.
     *
     * @param targetDirectory Directory path on the data partition (default "/ISO")
     */
    suspend fun storeIso(
        dataPartitionDevice: BlockDevice,
        isoStream: InputStream,
        fileName: String,
        fileSizeBytes: Long,
        targetDirectory: String = "/ISO",
        onProgress: ((bytesWritten: Long, totalBytes: Long) -> Unit)? = null
    ): Fat32DirectoryEntry {
        val writer = Fat32Writer.mount(dataPartitionDevice)

        val cleanDir = if (targetDirectory.startsWith("/")) targetDirectory else "/$targetDirectory"
        if (!writer.exists(cleanDir)) {
            writer.mkdir(cleanDir)
        }

        val destinationPath = if (cleanDir == "/") "/$fileName" else "$cleanDir/$fileName"
        val entry = writer.writeFileStream(
            path = destinationPath,
            inputStream = isoStream,
            totalBytes = fileSizeBytes,
            onProgress = onProgress
        )
        writer.flush()
        return entry
    }

    /**
     * Writes the standard Ventoy plugin configuration `/ventoy/ventoy.json`.
     */
    suspend fun writeVentoyConfig(
        dataPartitionDevice: BlockDevice,
        configJson: String = DEFAULT_VENTOY_JSON
    ) {
        val writer = Fat32Writer.mount(dataPartitionDevice)
        if (!writer.exists("/ventoy")) {
            writer.mkdir("/ventoy")
        }
        writer.writeFile("/ventoy/ventoy.json", configJson.toByteArray(Charsets.UTF_8))
        writer.flush()
    }
}
