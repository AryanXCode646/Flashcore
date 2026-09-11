package com.ashishsinghbora.flashcore

import com.ashishsinghbora.flashcore.block.MemoryBlockDevice
import com.ashishsinghbora.flashcore.fat32.Fat32BootSector
import com.ashishsinghbora.flashcore.fat32.Fat32DirectoryEntry
import com.ashishsinghbora.flashcore.fat32.Fat32FsInfo
import com.ashishsinghbora.flashcore.fat32.Fat32Table
import com.ashishsinghbora.flashcore.fat32.Fat32Writer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Random

class Fat32WriterTest {

    // 100 MB test partition (204,800 sectors)
    private val totalSectors = 204800L
    private val sectorSize = 512

    private fun createDevice(): MemoryBlockDevice =
        MemoryBlockDevice(totalSectors = totalSectors, sectorSizeBytes = sectorSize)

    @Test
    fun testFormatVolumeAndBootSectorValidation() {
        runBlocking {
            val device = createDevice()
            val writer = Fat32Writer.createNew(device, totalSectors, "WININSTALL")
            val formatSuccess = writer.formatVolume("WININSTALL")
            assertTrue("Format should succeed", formatSuccess)

            // Read Sector 0 (VBR)
            val vbrBytes = ByteArray(sectorSize)
            device.read(0L, 1, vbrBytes)
            val boot = Fat32BootSector.parse(vbrBytes)

            assertEquals(sectorSize, boot.sectorSizeBytes)
            assertEquals(8, boot.sectorsPerCluster)
            assertEquals(32, boot.reservedSectors)
            assertEquals(2, boot.numFats)
            assertEquals(totalSectors, boot.totalSectors)
            assertEquals("WININSTALL", boot.volumeLabel)

            // Read Sector 6 (Backup VBR) and check exact byte match
            val backupVbr = ByteArray(sectorSize)
            device.read(6L, 1, backupVbr)
            assertArrayEquals("Backup VBR at sector 6 must match sector 0", vbrBytes, backupVbr)

            // Read Sector 1 (FSInfo)
            val fsInfoBytes = ByteArray(sectorSize)
            device.read(1L, 1, fsInfoBytes)
            val fsInfo = Fat32FsInfo.parse(fsInfoBytes)
            assertTrue("Free cluster count must be > 0", fsInfo.freeClusters > 0)
            assertEquals(3L, fsInfo.nextFreeClusterHint)

            // Read Sector 7 (Backup FSInfo)
            val backupFsInfo = ByteArray(sectorSize)
            device.read(7L, 1, backupFsInfo)
            assertArrayEquals("Backup FSInfo must match Sector 1", fsInfoBytes, backupFsInfo)

            // Check FAT table Cluster 0, 1, 2
            val fatSector = ByteArray(sectorSize)
            device.read(boot.fat1StartLba, 1, fatSector)
            val fBuf = ByteBuffer.wrap(fatSector).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(0x0FFFFFF8, fBuf.getInt(0))
            assertEquals(0x0FFFFFFF, fBuf.getInt(4))
            assertEquals(0x0FFFFFFF, fBuf.getInt(8))
        }
    }

    @Test
    fun testMkdirAndSubdirectoryDotEntries() {
        runBlocking {
            val device = createDevice()
            val writer = Fat32Writer.createNew(device, totalSectors)
            writer.formatVolume("TEST_BOOT")

            // Create nested directory /EFI/BOOT
            val bootCluster = writer.mkdir("/EFI/BOOT")
            assertTrue("Boot cluster must be >= 3", bootCluster >= 3L)

            // Verify /EFI exists
            assertTrue(writer.exists("/EFI"))
            // Verify /EFI/BOOT exists
            assertTrue(writer.exists("/EFI/BOOT"))

            // Verify dot '.' and dot-dot '..' entries in /EFI/BOOT cluster
            val bootLba = writer.bootSector.clusterToLba(bootCluster)
            val dirSector = ByteArray(sectorSize)
            device.read(bootLba, 1, dirSector)

            val dBuf = ByteBuffer.wrap(dirSector).order(ByteOrder.LITTLE_ENDIAN)

            // Dot entry
            val dotName = String(dirSector, 0, 11, Charsets.US_ASCII)
            assertEquals(".          ", dotName)
            val dotClusterHigh = dBuf.getShort(20).toLong() and 0xFFFFL
            val dotClusterLow = dBuf.getShort(26).toLong() and 0xFFFFL
            val dotCluster = (dotClusterHigh shl 16) or dotClusterLow
            assertEquals("Dot entry must point to subdirectory's own cluster", bootCluster, dotCluster)

            // Dot-dot entry
            val dotDotName = String(dirSector, 32, 11, Charsets.US_ASCII)
            assertEquals("..         ", dotDotName)
        }
    }

    @Test
    fun testWriteAndReadFileSmall() {
        runBlocking {
            val device = createDevice()
            val writer = Fat32Writer.createNew(device, totalSectors)
            writer.formatVolume()

            val content = "Hello FAT32 UEFI Boot!".toByteArray(Charsets.UTF_8)
            val path = "/EFI/BOOT/BOOTX64.EFI"

            val entry = writer.writeFile(path, content)
            assertEquals(content.size.toLong(), entry.fileSizeBytes)
            assertTrue("File should exist", writer.exists(path))

            val readBack = writer.readFile(path)
            assertArrayEquals("Read-back content must match written bytes", content, readBack)
        }
    }

    @Test
    fun testWriteAndReadFileMultiCluster() {
        runBlocking {
            val device = createDevice()
            val writer = Fat32Writer.createNew(device, totalSectors)
            writer.formatVolume()

            val clusterSize = writer.bootSector.clusterSizeBytes.toInt() // 4096 bytes
            // 2.5 clusters = 10,240 bytes (spans 3 clusters)
            val totalSize = (clusterSize * 2.5).toInt()
            val random = Random(42)
            val testData = ByteArray(totalSize)
            random.nextBytes(testData)

            val path = "/sources/install.wim"
            val entry = writer.writeFile(path, testData)

            assertEquals(totalSize.toLong(), entry.fileSizeBytes)
            assertTrue("First cluster must be valid", entry.firstCluster >= 3L)

            // Verify cluster chain length in FAT
            val chain = writer.fatTable.getClusterChain(entry.firstCluster)
            assertEquals("Cluster chain must span exactly 3 clusters", 3, chain.size)

            val readBack = writer.readFile(path)
            assertEquals(totalSize, readBack.size)

            val originalDigest = MessageDigest.getInstance("SHA-256").digest(testData)
            val readDigest = MessageDigest.getInstance("SHA-256").digest(readBack)
            assertArrayEquals("SHA-256 hash must match", originalDigest, readDigest)
        }
    }

    @Test
    fun testAppendFileAcrossClusters() {
        runBlocking {
            val device = createDevice()
            val writer = Fat32Writer.createNew(device, totalSectors)
            writer.formatVolume()

            val clusterSize = writer.bootSector.clusterSizeBytes.toInt()
            val part1 = ByteArray(clusterSize - 100) { (it and 0xFF).toByte() }
            val part2 = ByteArray(500) { ((it + 3) and 0xFF).toByte() }

            val path = "/log/output.log"
            writer.writeFile(path, part1)
            assertEquals(part1.size.toLong(), writer.readFile(path).size.toLong())

            // Append part2 (causes allocation of a second cluster)
            writer.appendFile(path, part2)

            val expectedTotal = ByteArray(part1.size + part2.size)
            System.arraycopy(part1, 0, expectedTotal, 0, part1.size)
            System.arraycopy(part2, 0, expectedTotal, part1.size, part2.size)

            val readBack = writer.readFile(path)
            assertEquals(expectedTotal.size, readBack.size)
            assertArrayEquals(expectedTotal, readBack)

            val files = writer.list("/log")
            assertEquals(1, files.size)
            assertEquals(expectedTotal.size.toLong(), files[0].fileSizeBytes)
        }
    }

    @Test
    fun testDirectoryExpansionWithManyEntries() {
        runBlocking {
            val device = createDevice()
            val writer = Fat32Writer.createNew(device, totalSectors)
            writer.formatVolume()

            // A 4096-byte cluster fits 128 32-byte slots.
            // Writing 150 files ensures the directory expands to at least 2 clusters.
            writer.mkdir("/many_files")
            for (i in 1..150) {
                val fname = "file_%03d.txt".format(i)
                val content = "Content of $fname".toByteArray(Charsets.US_ASCII)
                writer.writeFile("/many_files/$fname", content)
            }

            val list = writer.list("/many_files")
            assertEquals(150, list.size)

            // Read random sample
            val read50 = writer.readFile("/many_files/file_050.txt")
            assertEquals("Content of file_050.txt", String(read50, Charsets.US_ASCII))

            val read150 = writer.readFile("/many_files/file_150.txt")
            assertEquals("Content of file_150.txt", String(read150, Charsets.US_ASCII))
        }
    }

    @Test
    fun testLongFileNamesLfn() {
        runBlocking {
            val device = createDevice()
            val writer = Fat32Writer.createNew(device, totalSectors)
            writer.formatVolume()

            val lfn1 = "This_Is_A_Very_Long_Filename_For_UEFI_Boot.iso"
            val lfn2 = "kernel-6.8.0-40-generic.efi"
            val data1 = "ISO Payload".toByteArray()
            val data2 = "Kernel Payload".toByteArray()

            writer.writeFile("/boot/$lfn1", data1)
            writer.writeFile("/boot/$lfn2", data2)

            val list = writer.list("/boot")
            assertEquals(2, list.size)

            val names = list.map { it.name }
            assertTrue("List must contain lfn1", names.contains(lfn1))
            assertTrue("List must contain lfn2", names.contains(lfn2))

            val read1 = writer.readFile("/boot/$lfn1")
            assertArrayEquals(data1, read1)

            val read2 = writer.readFile("/boot/$lfn2")
            assertArrayEquals(data2, read2)
        }
    }

    @Test
    fun testFsInfoFreeClusterTracking() {
        runBlocking {
            val device = createDevice()
            val writer = Fat32Writer.createNew(device, totalSectors)
            writer.formatVolume()

            val initialFree = writer.fsInfo.freeClusters
            assertTrue(initialFree > 1000)

            // Allocate 10 clusters (40 KB)
            val dummyData = ByteArray(40 * 1024)
            writer.writeFile("/test/bigfile.bin", dummyData)

            // Directory /test takes 1 cluster, file takes 10 clusters = 11 clusters used
            val currentFree = writer.fsInfo.freeClusters
            assertEquals(initialFree - 11L, currentFree)

            // Verify FSInfo was written to disk
            val fsInfoBytes = ByteArray(sectorSize)
            device.read(1L, 1, fsInfoBytes)
            val parsedFsInfo = Fat32FsInfo.parse(fsInfoBytes)
            assertEquals(currentFree, parsedFsInfo.freeClusters)
        }
    }

    @Test
    fun testPersistenceAndMounting() {
        runBlocking {
            val device = createDevice()
            val writer1 = Fat32Writer.createNew(device, totalSectors, "REMOUNT_VOL")
            writer1.formatVolume("REMOUNT_VOL")

            writer1.writeFile("/EFI/BOOT/BOOTX64.EFI", "EFI_LOADER_BINARY".toByteArray())
            writer1.writeFile("/config.sys", "FILES=40".toByteArray())
            writer1.flush()

            // Simulate unmount and re-mount using Fat32Writer.mount()
            val mountedWriter = Fat32Writer.mount(device)
            assertEquals("REMOUNT_VOL", mountedWriter.bootSector.volumeLabel)

            assertTrue(mountedWriter.exists("/EFI/BOOT/BOOTX64.EFI"))
            assertTrue(mountedWriter.exists("/config.sys"))

            val readEfi = mountedWriter.readFile("/EFI/BOOT/BOOTX64.EFI")
            assertEquals("EFI_LOADER_BINARY", String(readEfi))

            val readCfg = mountedWriter.readFile("/config.sys")
            assertEquals("FILES=40", String(readCfg))
        }
    }
}
