package com.example.iso

import com.example.fat32.Fat32Writer
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * ISO 9660 & Joliet Filesystem Engine.
 *
 * Implements full ISO 9660 PVD parsing, Joliet Unicode extensions, El Torito boot catalog
 * inspection, tree indexing, streaming reads, dynamic capability detection, and direct
 * extraction to [Fat32Writer].
 */
class IsoFilesystemReader(private val source: IsoSource) {

    companion object {
        const val SECTOR_SIZE = 2048
        const val PVD_SECTOR = 16L
        const val ISO_MAGIC = "CD001"
        const val FAT32_MAX_FILE_SIZE = 4294967295L
    }

    var pvd: PrimaryVolumeDescriptor? = null
        private set
    var elTorito: ElToritoCatalog? = null
        private set
    var isJoliet: Boolean = false
        private set
    var directoryTree: IsoDirectoryTree = IsoDirectoryTree()
        private set
    var capabilities: IsoCapabilities? = null
        private set

    val volumeLabel: String
        get() = pvd?.volumeId ?: "UNKNOWN"

    val entries: List<IsoFileEntry>
        get() = directoryTree.allEntries

    /**
     * Initializes the ISO reader, parses volume descriptors, indexes directory records,
     * and analyzes image capabilities.
     */
    fun open(): Boolean {
        val sector = ByteArray(SECTOR_SIZE)

        // 1. Read Primary Volume Descriptor at Sector 16
        val readPvd = source.readAt(PVD_SECTOR * SECTOR_SIZE, sector, 0, SECTOR_SIZE)
        if (readPvd < SECTOR_SIZE) return false

        val parsedPvd = PrimaryVolumeDescriptor.parse(sector) ?: return false
        pvd = parsedPvd

        var rootExtentLba = parsedPvd.rootExtentLba
        var rootDataLength = parsedPvd.rootDataLengthBytes
        var useJoliet = false
        var catalogLba: Long? = null

        // 2. Scan Sectors 17..31 for Joliet SVD, Boot Record, and Terminator
        for (sec in 17L until 32L) {
            val r = source.readAt(sec * SECTOR_SIZE, sector, 0, SECTOR_SIZE)
            if (r < SECTOR_SIZE) break

            val magic = String(sector, 1, 5, Charsets.US_ASCII)
            if (magic != ISO_MAGIC) break

            val descType = sector[0].toInt() and 0xFF
            if (descType == 255) break // Terminator

            if (descType == 0) { // Boot Record (El Torito)
                val catLba = ElToritoParser.parseBootRecordDescriptor(sector)
                if (catLba != null) {
                    catalogLba = catLba
                }
            } else if (descType == 2) { // Supplementary Volume Descriptor (Joliet)
                val escape = String(sector, 88, 3, Charsets.US_ASCII)
                if (escape.startsWith("%/")) {
                    useJoliet = true
                    val buf = ByteBuffer.wrap(sector).order(ByteOrder.LITTLE_ENDIAN)
                    rootExtentLba = buf.getInt(156 + 2).toLong() and 0xFFFFFFFFL
                    rootDataLength = buf.getInt(156 + 10).toLong() and 0xFFFFFFFFL
                }
            }
        }

        isJoliet = useJoliet

        // 3. Parse El Torito Boot Catalog if present
        if (catalogLba != null) {
            val catalogSector = ByteArray(SECTOR_SIZE)
            val readCat = source.readAt(catalogLba * SECTOR_SIZE, catalogSector, 0, SECTOR_SIZE)
            if (readCat == SECTOR_SIZE) {
                elTorito = ElToritoParser.parseCatalog(catalogSector, catalogLba)
            }
        }

        // 4. Build in-memory directory tree
        directoryTree = IsoDirectoryTree.build(
            source = source,
            rootExtentLba = rootExtentLba,
            rootDataLength = rootDataLength,
            isJoliet = useJoliet
        )

        // 5. Detect capabilities dynamically
        capabilities = IsoCapabilityDetector.detect(
            source = source,
            pvd = parsedPvd,
            elTorito = elTorito,
            tree = directoryTree
        ).copy(isJoliet = useJoliet)

        return true
    }

    /**
     * Looks up an entry by absolute normalized path.
     */
    fun find(path: String): IsoFileEntry? = directoryTree.find(path)

    /**
     * Opens an [InputStream] over the payload of a file in the ISO.
     */
    fun openStream(entry: IsoFileEntry): InputStream {
        require(!entry.isDirectory) { "Cannot open InputStream on directory: ${entry.path}" }

        return object : InputStream() {
            private var bytesRead = 0L
            private val fileStartOffset = entry.lba * SECTOR_SIZE

            override fun read(): Int {
                if (bytesRead >= entry.sizeBytes) return -1
                val b = ByteArray(1)
                val r = read(b, 0, 1)
                return if (r == 1) b[0].toInt() and 0xFF else -1
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (bytesRead >= entry.sizeBytes) return -1
                val toRead = minOf(len.toLong(), entry.sizeBytes - bytesRead).toInt()
                val read = source.readAt(fileStartOffset + bytesRead, b, off, toRead)
                if (read > 0) {
                    bytesRead += read
                }
                return read
            }

            override fun available(): Int = (entry.sizeBytes - bytesRead).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }
    }

    /**
     * Extracts all files and directories from the ISO into the provided [Fat32Writer].
     */
    suspend fun extractTo(
        writer: Fat32Writer,
        onProgress: ((entryPath: String, bytesCopied: Long, totalBytes: Long) -> Unit)? = null
    ) {
        val fileEntries = entries.filter { !it.isDirectory }
        val totalPayloadBytes = fileEntries.sumOf { it.sizeBytes }
        var overallBytesCopied = 0L

        // First create all directories
        for (entry in entries.filter { it.isDirectory }) {
            writer.mkdir(entry.path)
        }

        // Then copy all files
        for (entry in fileEntries) {
            val stream = openStream(entry)
            writer.writeFileStream(entry.path, stream, entry.sizeBytes) { fileBytesWritten, _ ->
                onProgress?.invoke(entry.path, overallBytesCopied + fileBytesWritten, totalPayloadBytes)
            }
            overallBytesCopied += entry.sizeBytes
            onProgress?.invoke(entry.path, overallBytesCopied, totalPayloadBytes)
        }

        writer.flush()
    }
}
