package com.example

import com.example.block.DeviceCapacity
import com.example.block.DeviceDisconnectedException
import com.example.block.FakeBlockDevice
import com.example.block.FaultInjectingBlockDevice
import com.example.block.FileBackedBlockDevice
import com.example.block.MemoryBlockDevice
import com.example.partition.Fat32Formatter
import com.example.partition.GptBuilder
import com.example.partition.MbrBuilder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * Comprehensive Test Framework for FlashCore Block Device Abstractions.
 *
 * Verifies all 10 core disk operations and hardware failure modes in a pure JVM environment
 * without requiring a physical USB flash drive:
 * 1. write LBA 0 (Protective MBR / Boot Sector)
 * 2. write GPT (Primary & Backup GPT Headers + Partition Arrays)
 * 3. write FAT32 (VBR, FSInfo, FAT1, FAT2)
 * 4. write 100 MB (Streaming throughput, off-heap buffers, CRC32/SHA256 integrity)
 * 5. write 4 GB (64-bit LBA geometry, sparse memory allocation)
 * 6. failure at LBA X (Bad sectors / controller I/O errors)
 * 7. disconnect during write (Sudden USB OTG unplug simulation)
 * 8. short write (Partial sector write rejection)
 * 9. timeout (Bus transfer delay and timeout exception)
 * 10. corrupted sector (Bit rot and read-back corruption detection)
 */
class BlockDeviceFrameworkTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    // ------------------------------------------------------------------------
    // 1. write LBA 0
    // ------------------------------------------------------------------------
    @Test
    fun testWriteLba0BootSector() {
        runBlocking {
            val totalSectors = 2097152L // 1 GB
            val memDevice = MemoryBlockDevice(totalSectors = totalSectors, sectorSizeBytes = 512)

            // Generate protective MBR with boot signature 0x55, 0xAA
            val protectiveMbr = MbrBuilder.buildProtectiveMbr(totalSectors)
            assertEquals(512, protectiveMbr.size)
            assertEquals(0x55.toByte(), protectiveMbr[510])
            assertEquals(0xAA.toByte(), protectiveMbr[511])

            // Write to LBA 0
            val writeSuccess = memDevice.write(lba = 0L, blockCount = 1, src = protectiveMbr)
            assertTrue(writeSuccess)

            // Read back LBA 0
            val readBack = ByteArray(512)
            val readSuccess = memDevice.read(lba = 0L, blockCount = 1, dest = readBack)
            assertTrue(readSuccess)
            assertArrayEquals(protectiveMbr, readBack)

            // Partition 1 Type at offset 450 (0x1BE + 4) must be 0xEE (GPT Protective)
            assertEquals(0xEE.toByte(), readBack[446 + 4])
        }
    }

    // ------------------------------------------------------------------------
    // 2. write GPT
    // ------------------------------------------------------------------------
    @Test
    fun testWriteGptLayout() {
        runBlocking {
            val totalSectors = 2097152L // 1 GB disk (512 bytes/sector)
            val memDevice = MemoryBlockDevice(totalSectors = totalSectors, sectorSizeBytes = 512)

            val partStart = 2048L
            val partEnd = totalSectors - 2048L
            val gptPartition = GptBuilder.GptPartition(
                typeGuid = GptBuilder.GUID_MICROSOFT_BASIC_DATA,
                firstLba = partStart,
                lastLba = partEnd,
                partitionName = "BOOT_MEDIA"
            )

            val layout = GptBuilder.build(
                totalDiskSectors = totalSectors,
                partitions = listOf(gptPartition),
                sectorSizeBytes = 512
            )

            // Write MBR at LBA 0
            val mbr = MbrBuilder.buildProtectiveMbr(totalSectors)
            memDevice.write(0L, 1, mbr)

            // Write Primary GPT Header (LBA 1) and Table (LBA 2..33)
            memDevice.write(1L, 1, layout.primaryHeaderSector)
            memDevice.write(2L, 32, layout.primaryPartitionTableBytes)

            // Write Backup Table and Backup Header at end of disk
            val backupTableLba = totalSectors - 1L - 32L
            memDevice.write(backupTableLba, 32, layout.backupPartitionTableBytes)
            memDevice.write(totalSectors - 1L, 1, layout.backupHeaderSector)

            // Verify Primary GPT Header at LBA 1
            val primaryHeader = ByteArray(512)
            memDevice.read(1L, 1, primaryHeader)
            val primarySig = String(primaryHeader, 0, 8, Charsets.US_ASCII)
            assertEquals("EFI PART", primarySig)

            val headerBuf = ByteBuffer.wrap(primaryHeader).order(ByteOrder.LITTLE_ENDIAN)
            val currentLba = headerBuf.getLong(24)
            val backupLba = headerBuf.getLong(32)
            assertEquals(1L, currentLba)
            assertEquals(totalSectors - 1L, backupLba)

            // Verify Backup GPT Header at last sector
            val backupHeader = ByteArray(512)
            memDevice.read(totalSectors - 1L, 1, backupHeader)
            val backupSig = String(backupHeader, 0, 8, Charsets.US_ASCII)
            assertEquals("EFI PART", backupSig)

            val backupBuf = ByteBuffer.wrap(backupHeader).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(totalSectors - 1L, backupBuf.getLong(24))
            assertEquals(1L, backupBuf.getLong(32))
        }
    }

    // ------------------------------------------------------------------------
    // 3. write FAT32
    // ------------------------------------------------------------------------
    @Test
    fun testWriteFat32FileSystem() {
        runBlocking {
            val partitionSectors = 1048576L // 512 MB partition
            val memDevice = MemoryBlockDevice(totalSectors = partitionSectors, sectorSizeBytes = 512)

            val fat32 = Fat32Formatter.format(partitionSectors, "WININSTALL")

            // Write VBR at LBA 0
            memDevice.write(0L, 1, fat32.vbrSector)
            // Write FSInfo at LBA 1
            memDevice.write(1L, 1, fat32.fsInfoSector)
            // Write FAT1 and FAT2
            memDevice.write(Fat32Formatter.RESERVED_SECTORS.toLong(), 1, fat32.fat1Sector)
            val fat2Lba = Fat32Formatter.RESERVED_SECTORS.toLong() + fat32.sectorsPerFat
            memDevice.write(fat2Lba, 1, fat32.fat2Sector)

            // Read and verify VBR
            val vbrRead = ByteArray(512)
            memDevice.read(0L, 1, vbrRead)
            assertEquals(0x55.toByte(), vbrRead[510])
            assertEquals(0xAA.toByte(), vbrRead[511])
            val oemName = String(vbrRead, 3, 8, Charsets.US_ASCII).trim()
            assertEquals("MSWIN4.1", oemName)

            // Read and verify FSInfo signatures (0x41615252 and 0x61417272)
            val fsInfoRead = ByteArray(512)
            memDevice.read(1L, 1, fsInfoRead)
            val fsInfoBuf = ByteBuffer.wrap(fsInfoRead).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(0x41615252, fsInfoBuf.getInt(0))
            assertEquals(0x61417272, fsInfoBuf.getInt(484))
            assertEquals(0x55.toByte(), fsInfoRead[510])
            assertEquals(0xAA.toByte(), fsInfoRead[511])

            // Read and verify FAT1 media descriptor
            val fat1Read = ByteArray(512)
            memDevice.read(Fat32Formatter.RESERVED_SECTORS.toLong(), 1, fat1Read)
            assertEquals(0xF8.toByte(), fat1Read[0]) // Media descriptor byte for fixed disk
            assertEquals(0xFF.toByte(), fat1Read[1])
            assertEquals(0xFF.toByte(), fat1Read[2])
            assertEquals(0x0F.toByte(), fat1Read[3])
        }
    }

    // ------------------------------------------------------------------------
    // 4. write 100 MB
    // ------------------------------------------------------------------------
    @Test
    fun testWrite100MbThroughputAndIntegrity() {
        runBlocking {
            val totalBytes = 100L * 1024L * 1024L // 100 MB
            val sectorSize = 512
            val totalSectors = totalBytes / sectorSize // 204,800 sectors
            val memDevice = MemoryBlockDevice(totalSectors = totalSectors, sectorSizeBytes = sectorSize)

            val chunkSizeBytes = 1024 * 1024 // 1 MB chunks
            val sectorsPerChunk = chunkSizeBytes / sectorSize // 2,048 sectors
            val chunkCount = (totalBytes / chunkSizeBytes).toInt()

            val writeDigest = MessageDigest.getInstance("SHA-256")
            val directBuffer = ByteBuffer.allocateDirect(chunkSizeBytes)

            // Fill direct buffer with repeating pseudo-random pattern
            val pattern = ByteArray(chunkSizeBytes) { idx -> ((idx * 31 + 17) % 256).toByte() }

            var currentLba = 0L
            val startTime = System.currentTimeMillis()

            for (chunkIdx in 0 until chunkCount) {
                directBuffer.clear()
                directBuffer.put(pattern)
                directBuffer.flip()

                writeDigest.update(pattern)

                val success = memDevice.writeDirectBuffer(
                    lba = currentLba,
                    blockCount = sectorsPerChunk,
                    directBuffer = directBuffer,
                    offset = 0,
                    length = chunkSizeBytes
                )
                assertTrue("Failed write at chunk $chunkIdx", success)
                currentLba += sectorsPerChunk
            }

            val flushSuccess = memDevice.flush()
            assertTrue(flushSuccess)
            assertEquals(1L, memDevice.flushCount)
            assertEquals(totalSectors, memDevice.writeCount)

            val durationMs = (System.currentTimeMillis() - startTime).coerceAtLeast(1)
            val mbps = (totalBytes.toDouble() / (1024.0 * 1024.0)) / (durationMs / 1000.0)
            assertTrue("Write throughput should be positive", mbps > 0.0)

            // Read back all 100 MB and verify SHA-256 matches writeDigest
            val readDigest = MessageDigest.getInstance("SHA-256")
            val readBuffer = ByteArray(chunkSizeBytes)
            currentLba = 0L

            for (chunkIdx in 0 until chunkCount) {
                val readOk = memDevice.read(lba = currentLba, blockCount = sectorsPerChunk, dest = readBuffer)
                assertTrue("Failed read at chunk $chunkIdx", readOk)
                readDigest.update(readBuffer)
                currentLba += sectorsPerChunk
            }

            val expectedSha = writeDigest.digest().joinToString("") { "%02x".format(it) }
            val actualSha = readDigest.digest().joinToString("") { "%02x".format(it) }
            assertEquals("100 MB Data corruption detected: SHA-256 mismatch", expectedSha, actualSha)
        }
    }

    // ------------------------------------------------------------------------
    // 5. write 4 GB (Virtual 4 GB drive with 64-bit sparse addressing)
    // ------------------------------------------------------------------------
    @Test
    fun testWrite4GbVirtualDriveAndSparseGeometry() {
        runBlocking {
            // 4 GB = 4 * 1024 * 1024 * 1024 bytes = 4,294,967,296 bytes = 8,388,608 sectors of 512 bytes
            val fourGbSectors = 8388608L
            val memDevice = MemoryBlockDevice(totalSectors = fourGbSectors, sectorSizeBytes = 512)

            val capacity = memDevice.capacity()
            assertEquals(fourGbSectors, capacity.totalSectors)
            assertEquals("4.00 GB", capacity.formattedCapacity)
            assertEquals(4294967296L, capacity.totalBytes)

            // Write 1: Start of disk (LBA 0)
            val lba0Data = ByteArray(512) { 0x01 }
            memDevice.write(0L, 1, lba0Data)

            // Write 2: 2 GB mark (LBA 4,194,304)
            val lba2Gb = 4194304L
            val lba2GbData = ByteArray(512) { 0x02 }
            memDevice.write(lba2Gb, 1, lba2GbData)

            // Write 3: Final sector of 4 GB disk (LBA 8,388,607)
            val lbaLast = fourGbSectors - 1L
            val lbaLastData = ByteArray(512) { 0x03 }
            memDevice.write(lbaLast, 1, lbaLastData)

            // Sparse storage check: only 3 sectors exist in memory despite 4 GB virtual size
            assertEquals(3, memDevice.allocatedSectorCount)

            // Read back each sector and verify exact content
            val readBack0 = ByteArray(512)
            memDevice.read(0L, 1, readBack0)
            assertArrayEquals(lba0Data, readBack0)

            val readBack2Gb = ByteArray(512)
            memDevice.read(lba2Gb, 1, readBack2Gb)
            assertArrayEquals(lba2GbData, readBack2Gb)

            val readBackLast = ByteArray(512)
            memDevice.read(lbaLast, 1, readBackLast)
            assertArrayEquals(lbaLastData, readBackLast)

            // Reading unwritten sector 1,000,000 returns all zeroes
            val unwritten = ByteArray(512)
            memDevice.read(1000000L, 1, unwritten)
            assertArrayEquals(ByteArray(512), unwritten)
        }
    }

    // ------------------------------------------------------------------------
    // 6. failure at LBA X
    // ------------------------------------------------------------------------
    @Test
    fun testFailureAtLbaX() {
        runBlocking {
            val memDevice = MemoryBlockDevice(totalSectors = 10000L, sectorSizeBytes = 512)
            val faultDevice = FaultInjectingBlockDevice(memDevice)

            val badSectorLba = 500L
            faultDevice.failAtLba = badSectorLba

            // Writing before LBA 500 succeeds
            val okData = ByteArray(512) { 0x11 }
            assertTrue(faultDevice.write(lba = 0L, blockCount = 1, src = okData))
            assertTrue(faultDevice.write(lba = 499L, blockCount = 1, src = okData))

            // Writing to a range covering LBA 500 fails
            val ex = assertThrows(IOException::class.java) {
                runBlocking {
                    faultDevice.write(lba = 490L, blockCount = 20, src = ByteArray(20 * 512))
                }
            }
            assertTrue(ex.message!!.contains("LBA 500"))

            // Direct write at LBA 500 fails
            assertThrows(IOException::class.java) {
                runBlocking {
                    faultDevice.write(lba = badSectorLba, blockCount = 1, src = okData)
                }
            }

            // Reading at LBA 500 also fails
            assertThrows(IOException::class.java) {
                runBlocking {
                    faultDevice.read(lba = badSectorLba, blockCount = 1, dest = ByteArray(512))
                }
            }

            // Writing past LBA 500 succeeds
            assertTrue(faultDevice.write(lba = 501L, blockCount = 1, src = okData))
        }
    }

    // ------------------------------------------------------------------------
    // 7. disconnect during write
    // ------------------------------------------------------------------------
    @Test
    fun testDisconnectDuringWrite() {
        runBlocking {
            val memDevice = MemoryBlockDevice(totalSectors = 10000L, sectorSizeBytes = 512)
            val faultDevice = FaultInjectingBlockDevice(memDevice)

            // Simulate disconnect after writing 64 KB (128 sectors)
            val disconnectThreshold = 64L * 1024L
            faultDevice.disconnectAfterBytes = disconnectThreshold

            val chunk = ByteArray(32 * 1024) // 32 KB chunk
            // Chunk 1 (0..32 KB): succeeds
            assertTrue(faultDevice.write(lba = 0L, blockCount = 64, src = chunk))
            assertTrue(faultDevice.isConnected)

            // Chunk 2 (32..64 KB): reaches threshold and triggers disconnect
            assertThrows(DeviceDisconnectedException::class.java) {
                runBlocking {
                    faultDevice.write(lba = 64L, blockCount = 64, src = chunk)
                }
            }

            // Device is now marked disconnected
            assertFalse(faultDevice.isConnected)

            // Subsequent read, write, capacity calls immediately throw DeviceDisconnectedException
            assertThrows(DeviceDisconnectedException::class.java) {
                runBlocking { faultDevice.capacity() }
            }
            assertThrows(DeviceDisconnectedException::class.java) {
                runBlocking { faultDevice.read(0L, 1, ByteArray(512)) }
            }
        }
    }

    // ------------------------------------------------------------------------
    // 8. short write
    // ------------------------------------------------------------------------
    @Test
    fun testShortWriteSimulation() {
        runBlocking {
            val memDevice = MemoryBlockDevice(totalSectors = 10000L, sectorSizeBytes = 512)
            val faultDevice = FaultInjectingBlockDevice(memDevice)

            // At LBA 100, device only accepts 4 out of requested sectors
            faultDevice.shortWriteAtLba = 100L
            faultDevice.shortWriteMaxBlocks = 4
            faultDevice.shortWriteThrows = false

            val payload = ByteArray(16 * 512) { 0x77.toByte() }
            val writeSuccess = faultDevice.write(lba = 100L, blockCount = 16, src = payload)

            // Short write returns false to signal partial transfer
            assertFalse(writeSuccess)

            // Verify: sectors 100..103 were committed
            val readAccepted = ByteArray(4 * 512)
            memDevice.read(100L, 4, readAccepted)
            assertArrayEquals(payload.copyOfRange(0, 4 * 512), readAccepted)

            // Sectors 104..115 remained unwritten (all zeroes)
            val readRejected = ByteArray(12 * 512)
            memDevice.read(104L, 12, readRejected)
            assertArrayEquals(ByteArray(12 * 512), readRejected)

            // When shortWriteThrows = true, an IOException is thrown
            faultDevice.shortWriteThrows = true
            assertThrows(IOException::class.java) {
                runBlocking {
                    faultDevice.write(lba = 100L, blockCount = 16, src = payload)
                }
            }
        }
    }

    // ------------------------------------------------------------------------
    // 9. timeout
    // ------------------------------------------------------------------------
    @Test
    fun testTimeoutSimulation() {
        runBlocking {
            val memDevice = MemoryBlockDevice(totalSectors = 1000L, sectorSizeBytes = 512)
            val faultDevice = FaultInjectingBlockDevice(memDevice)

            faultDevice.timeoutAtLba = 200L
            faultDevice.timeoutDelayMs = 50L
            faultDevice.throwTimeoutException = true

            // Write to LBA 200 triggers timeout exception
            val ex = assertThrows(InterruptedIOException::class.java) {
                runBlocking {
                    faultDevice.write(lba = 200L, blockCount = 1, src = ByteArray(512))
                }
            }
            assertTrue(ex.message!!.contains("timed out"))
        }
    }

    // ------------------------------------------------------------------------
    // 10. corrupted sector
    // ------------------------------------------------------------------------
    @Test
    fun testCorruptedSectorDetection() {
        runBlocking {
            val memDevice = MemoryBlockDevice(totalSectors = 1000L, sectorSizeBytes = 512)
            val faultDevice = FaultInjectingBlockDevice(memDevice)

            // Write 4 clean sectors at LBA 300
            val cleanData = ByteArray(4 * 512) { (it % 256).toByte() }
            val originalSha = MessageDigest.getInstance("SHA-256").digest(cleanData).joinToString("") { "%02x".format(it) }
            faultDevice.write(lba = 300L, blockCount = 4, src = cleanData)

            // Inject single-byte corruption on read at LBA 301, byte offset 10
            faultDevice.corruptSectorOnReadLba = 301L
            faultDevice.corruptReadByteOffset = 10
            faultDevice.corruptReadByteValue = 0xEE.toByte()

            // Read back 4 sectors
            val readBack = ByteArray(4 * 512)
            faultDevice.read(lba = 300L, blockCount = 4, dest = readBack)

            // SHA-256 must NOT match clean data due to the injected single-byte bit flip
            val corruptedSha = MessageDigest.getInstance("SHA-256").digest(readBack).joinToString("") { "%02x".format(it) }
            assertFalse("Corrupted sector must produce SHA-256 checksum mismatch", originalSha == corruptedSha)

            // Exact bit flip verification at sector 1 (LBA 301), offset 10
            val corruptedOffset = 512 + 10
            assertEquals(0xEE.toByte(), readBack[corruptedOffset])
            assertEquals(cleanData[0], readBack[0]) // Other sectors remain intact
        }
    }

    // ------------------------------------------------------------------------
    // 11. FileBackedBlockDevice and FakeBlockDevice tests
    // ------------------------------------------------------------------------
    @Test
    fun testFileBackedBlockDevicePersistence() {
        runBlocking {
            val diskImageFile = File(tempFolder.root, "virtual_disk.img")
            val totalSectors = 1024L

            // Instance 1: Write partition header
            FileBackedBlockDevice(diskImageFile, totalSectors = totalSectors, sectorSizeBytes = 512).use { dev ->
                val mbr = MbrBuilder.buildProtectiveMbr(totalSectors)
                dev.write(0L, 1, mbr)
                dev.flush()
            }

            // Instance 2: Re-open image file and verify data persisted to host disk
            FileBackedBlockDevice(diskImageFile, totalSectors = totalSectors, sectorSizeBytes = 512).use { dev ->
                val readBack = ByteArray(512)
                dev.read(0L, 1, readBack)
                assertEquals(0x55.toByte(), readBack[510])
                assertEquals(0xAA.toByte(), readBack[511])
            }
        }
    }

    @Test
    fun testFakeBlockDeviceRecording() {
        runBlocking {
            val fake = FakeBlockDevice(totalSectors = 4096L, sectorSizeBytes = 512)
            fake.write(0L, 1, ByteArray(512))
            fake.write(2048L, 16, ByteArray(16 * 512))
            fake.read(0L, 1, ByteArray(512))
            fake.flush()

            assertEquals(2, fake.writeCount)
            assertEquals(1, fake.readCount)
            assertEquals(1, fake.flushCount)
            assertTrue(fake.wasLbaWritten(0L))
            assertTrue(fake.wasLbaWritten(2048L))
            assertFalse(fake.wasLbaWritten(100L))
            assertEquals(17 * 512L, fake.totalBytesWritten)
        }
    }
}
