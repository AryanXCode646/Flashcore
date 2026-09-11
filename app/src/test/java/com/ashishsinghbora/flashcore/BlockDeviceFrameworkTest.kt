package com.ashishsinghbora.flashcore

import com.ashishsinghbora.flashcore.block.DeviceCapacity
import com.ashishsinghbora.flashcore.block.DeviceDisconnectedException
import com.ashishsinghbora.flashcore.block.FakeBlockDevice
import com.ashishsinghbora.flashcore.block.FaultInjectingBlockDevice
import com.ashishsinghbora.flashcore.block.FileBackedBlockDevice
import com.ashishsinghbora.flashcore.block.MemoryBlockDevice
import com.ashishsinghbora.flashcore.partition.Fat32Formatter
import com.ashishsinghbora.flashcore.partition.GptBuilder
import com.ashishsinghbora.flashcore.partition.GptGuidHelper
import com.ashishsinghbora.flashcore.partition.MbrBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.CRC32

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
    fun testFileBackedBlockDeviceMultiSectorAndOffset() {
        runBlocking {
            val diskImageFile = File(tempFolder.root, "multisector_test.img")
            val totalSectors = 2048L

            FileBackedBlockDevice(diskImageFile, totalSectors = totalSectors, sectorSizeBytes = 512).use { dev ->
                // Create a 4-sector buffer with distinct pattern per sector
                val sectorCount = 4
                val dataSize = sectorCount * 512
                val patternData = ByteArray(dataSize) { idx ->
                    val sectorIndex = idx / 512
                    ((sectorIndex + 1) * 0x11).toByte()
                }

                // Write with a non-zero buffer offset: embed patternData inside a larger buffer
                val prefixPad = 128
                val writeBuffer = ByteArray(prefixPad + dataSize + 64)
                writeBuffer.fill(0xAA.toByte(), 0, prefixPad)
                System.arraycopy(patternData, 0, writeBuffer, prefixPad, dataSize)

                val writeOk = dev.write(lba = 100L, blockCount = sectorCount, src = writeBuffer, offset = prefixPad)
                assertTrue(writeOk)
                dev.flush()

                // Read back with a non-zero buffer offset into padded destination
                val destPad = 64
                val readBuffer = ByteArray(destPad + dataSize + destPad)
                readBuffer.fill(0xBB.toByte())

                val readOk = dev.read(lba = 100L, blockCount = sectorCount, dest = readBuffer, offset = destPad)
                assertTrue(readOk)

                // Verify padding bytes remain untouched
                for (i in 0 until destPad) {
                    assertEquals(0xBB.toByte(), readBuffer[i])
                    assertEquals(0xBB.toByte(), readBuffer[destPad + dataSize + i])
                }

                // Verify data contents per sector
                val extracted = readBuffer.copyOfRange(destPad, destPad + dataSize)
                assertArrayEquals(patternData, extracted)

                // Verify sector 0 (LBA 100) has 0x11, sector 3 (LBA 103) has 0x44
                assertEquals(0x11.toByte(), extracted[0])
                assertEquals(0x44.toByte(), extracted[3 * 512])
            }
        }
    }

    @Test
    fun testFileBackedBlockDeviceConfigurableSectorSize4096() {
        runBlocking {
            val diskImageFile = File(tempFolder.root, "4k_disk.img")
            val totalSectors = 256L // 256 * 4096 = 1,048,576 bytes (1 MB)
            val sectorSize = 4096

            FileBackedBlockDevice(diskImageFile, totalSectors = totalSectors, sectorSizeBytes = sectorSize).use { dev ->
                assertEquals(sectorSize, dev.sectorSizeBytes)
                val cap = dev.capacity()
                assertEquals(totalSectors, cap.totalSectors)
                assertEquals(sectorSize, cap.sectorSizeBytes)
                assertEquals(1048576L, cap.totalBytes)
                assertEquals("1.0 MB", cap.formattedCapacity)

                // Write 2 sectors (8192 bytes) at LBA 10
                val payload = ByteArray(2 * sectorSize) { (it % 251).toByte() }
                assertTrue(dev.write(lba = 10L, blockCount = 2, src = payload))
                dev.flush()
            }

            // Reopen with 4096-byte sector size and verify read-back
            FileBackedBlockDevice(diskImageFile, totalSectors = totalSectors, sectorSizeBytes = sectorSize).use { dev ->
                val readBack = ByteArray(2 * sectorSize)
                assertTrue(dev.read(lba = 10L, blockCount = 2, dest = readBack))
                val expected = ByteArray(2 * sectorSize) { (it % 251).toByte() }
                assertArrayEquals(expected, readBack)

                // Unwritten sector LBA 0 must be 4096 zeroes
                val unwritten = ByteArray(sectorSize)
                assertTrue(dev.read(lba = 0L, blockCount = 1, dest = unwritten))
                assertArrayEquals(ByteArray(sectorSize), unwritten)
            }
        }
    }

    @Test
    fun testFileBackedBlockDeviceCapacityDetection() {
        runBlocking {
            val diskImageFile = File(tempFolder.root, "existing_aligned.img")
            // Create an 8192-byte file (16 sectors of 512B or 2 sectors of 4096B)
            val testBytes = ByteArray(8192) { 0x5A.toByte() }
            diskImageFile.writeBytes(testBytes)

            // Test 1: openExisting with default 512-byte sectors
            FileBackedBlockDevice.openExisting(diskImageFile).use { dev ->
                assertEquals(512, dev.sectorSizeBytes)
                assertEquals(16L, dev.totalSectors)
                val cap = dev.capacity()
                assertEquals(16L, cap.totalSectors)
                assertEquals(8192L, cap.totalBytes)

                val readBack = ByteArray(512)
                assertTrue(dev.read(0L, 1, readBack))
                assertEquals(0x5A.toByte(), readBack[0])
            }

            // Test 2: secondary constructor FileBackedBlockDevice(File)
            FileBackedBlockDevice(diskImageFile).use { dev ->
                assertEquals(16L, dev.totalSectors)
                assertEquals(512, dev.sectorSizeBytes)
            }

            // Test 3: openExisting with 4096-byte sectors (8192 / 4096 = 2 sectors)
            FileBackedBlockDevice.openExisting(diskImageFile, sectorSizeBytes = 4096).use { dev ->
                assertEquals(4096, dev.sectorSizeBytes)
                assertEquals(2L, dev.totalSectors)
                assertEquals(8192L, dev.capacity().totalBytes)
            }

            // Test 4: Reject unaligned file size (500 bytes is not divisible by 512)
            val unalignedFile = File(tempFolder.root, "unaligned.img")
            unalignedFile.writeBytes(ByteArray(500))
            assertThrows(IllegalArgumentException::class.java) {
                FileBackedBlockDevice.openExisting(unalignedFile)
            }

            // Test 5: Reject non-existent file
            val nonExistent = File(tempFolder.root, "does_not_exist.img")
            assertThrows(java.io.FileNotFoundException::class.java) {
                FileBackedBlockDevice.openExisting(nonExistent)
            }

            // Test 6: Reject empty file (0 bytes)
            val emptyFile = File(tempFolder.root, "empty.img")
            emptyFile.createNewFile()
            assertThrows(IllegalArgumentException::class.java) {
                FileBackedBlockDevice.openExisting(emptyFile)
            }
        }
    }

    @Test
    fun testFileBackedBlockDeviceBoundsChecking() {
        runBlocking {
            val diskImageFile = File(tempFolder.root, "bounds_test.img")
            val totalSectors = 100L

            FileBackedBlockDevice(diskImageFile, totalSectors = totalSectors, sectorSizeBytes = 512).use { dev ->
                val singleSector = ByteArray(512)

                // 1. Valid boundary: LBA 0
                assertTrue(dev.write(0L, 1, singleSector))
                assertTrue(dev.read(0L, 1, singleSector))

                // 2. Valid boundary: last valid sector (totalSectors - 1 = 99)
                assertTrue(dev.write(99L, 1, singleSector))
                assertTrue(dev.read(99L, 1, singleSector))

                // 3. Valid boundary: multi-sector ending exactly at end of disk (90..99)
                assertTrue(dev.write(90L, 10, ByteArray(10 * 512)))
                assertTrue(dev.read(90L, 10, ByteArray(10 * 512)))

                // 4. Invalid: request begins exactly at totalSectors (LBA 100)
                assertThrows(IOException::class.java) {
                    runBlocking { dev.write(100L, 1, singleSector) }
                }
                assertThrows(IOException::class.java) {
                    runBlocking { dev.read(100L, 1, singleSector) }
                }

                // 5. Invalid: request extends beyond capacity (LBA 99, count 2 -> 99..100)
                assertThrows(IOException::class.java) {
                    runBlocking { dev.write(99L, 2, ByteArray(1024)) }
                }
                assertThrows(IOException::class.java) {
                    runBlocking { dev.read(99L, 2, ByteArray(1024)) }
                }

                // 6. Invalid: negative LBA
                assertThrows(IOException::class.java) {
                    runBlocking { dev.write(-1L, 1, singleSector) }
                }
                assertThrows(IOException::class.java) {
                    runBlocking { dev.read(-1L, 1, singleSector) }
                }

                // 7. Invalid: zero block count
                assertThrows(IOException::class.java) {
                    runBlocking { dev.write(0L, 0, singleSector) }
                }
                assertThrows(IOException::class.java) {
                    runBlocking { dev.read(0L, 0, singleSector) }
                }

                // 8. Invalid: negative block count
                assertThrows(IOException::class.java) {
                    runBlocking { dev.write(0L, -5, singleSector) }
                }
                assertThrows(IOException::class.java) {
                    runBlocking { dev.read(0L, -5, singleSector) }
                }

                // 9. Overflow near Long.MAX_VALUE
                assertThrows(IOException::class.java) {
                    runBlocking { dev.write(Long.MAX_VALUE, 1, singleSector) }
                }
                assertThrows(IOException::class.java) {
                    runBlocking { dev.read(Long.MAX_VALUE, 1, singleSector) }
                }
                assertThrows(IOException::class.java) {
                    runBlocking { dev.write(Long.MAX_VALUE - 5, 10, ByteArray(5120)) }
                }
            }
        }
    }

    @Test
    fun testFileBackedBlockDeviceDirectBufferValidation() {
        runBlocking {
            val diskImageFile = File(tempFolder.root, "direct_buffer_test.img")
            val totalSectors = 100L

            FileBackedBlockDevice(diskImageFile, totalSectors = totalSectors, sectorSizeBytes = 512).use { dev ->
                val direct = ByteBuffer.allocateDirect(2048)
                val testBytes = ByteArray(1024) { (it % 127).toByte() }
                direct.put(testBytes)
                direct.flip() // position = 0, limit = 1024, capacity = 2048

                // 1. Valid direct buffer write: 2 sectors = 1024 bytes
                val originalPos = direct.position()
                val originalLimit = direct.limit()
                val writeOk = dev.writeDirectBuffer(
                    lba = 10L,
                    blockCount = 2,
                    directBuffer = direct,
                    offset = 0,
                    length = 1024
                )
                assertTrue(writeOk)
                dev.flush()

                // Verify directBuffer position and limit were NOT modified by writeDirectBuffer
                assertEquals(originalPos, direct.position())
                assertEquals(originalLimit, direct.limit())

                // Verify content on disk
                val readBack = ByteArray(1024)
                assertTrue(dev.read(10L, 2, readBack))
                assertArrayEquals(testBytes, readBack)

                // 2. Negative offset
                assertThrows(IndexOutOfBoundsException::class.java) {
                    runBlocking {
                        dev.writeDirectBuffer(0L, 1, direct, offset = -1, length = 512)
                    }
                }

                // 3. Offset beyond buffer limit
                assertThrows(IndexOutOfBoundsException::class.java) {
                    runBlocking {
                        dev.writeDirectBuffer(0L, 1, direct, offset = 1500, length = 512)
                    }
                }

                // 4. Length larger than available buffer from offset
                assertThrows(IndexOutOfBoundsException::class.java) {
                    runBlocking {
                        dev.writeDirectBuffer(0L, 2, direct, offset = 600, length = 1024)
                    }
                }

                // 5. Length inconsistent with blockCount * sectorSizeBytes
                assertThrows(IllegalArgumentException::class.java) {
                    runBlocking {
                        dev.writeDirectBuffer(0L, 2, direct, offset = 0, length = 512) // 2 blocks * 512 = 1024 != 512
                    }
                }

                // 6. Negative length
                assertThrows(IllegalArgumentException::class.java) {
                    runBlocking {
                        dev.writeDirectBuffer(0L, 1, direct, offset = 0, length = -512)
                    }
                }
            }
        }
    }

    @Test
    fun testFileBackedBlockDeviceSparseAndUnwrittenRegions() {
        runBlocking {
            val diskImageFile = File(tempFolder.root, "sparse_test.img")
            val totalSectors = 1000L

            FileBackedBlockDevice(diskImageFile, totalSectors = totalSectors, sectorSizeBytes = 512, preallocate = false).use { dev ->
                // Write a single sector at LBA 50
                val sector50Data = ByteArray(512) { 0x7E.toByte() }
                assertTrue(dev.write(50L, 1, sector50Data))
                dev.flush()

                // Read LBA 0 (unwritten gap before LBA 50) -> must return deterministic zeroes
                val readLba0 = ByteArray(512) { 0xFF.toByte() }
                assertTrue(dev.read(0L, 1, readLba0))
                assertArrayEquals(ByteArray(512), readLba0)

                // Read LBA 49 (immediately preceding LBA 50) -> must return zeroes
                val readLba49 = ByteArray(512) { 0xFF.toByte() }
                assertTrue(dev.read(49L, 1, readLba49))
                assertArrayEquals(ByteArray(512), readLba49)

                // Read LBA 50 -> must return written data
                val readLba50 = ByteArray(512)
                assertTrue(dev.read(50L, 1, readLba50))
                assertArrayEquals(sector50Data, readLba50)

                // Read LBA 51 (beyond the physical file length) -> must return deterministic zeroes
                val readLba51 = ByteArray(512) { 0xFF.toByte() }
                assertTrue(dev.read(51L, 1, readLba51))
                assertArrayEquals(ByteArray(512), readLba51)

                // Multi-sector read spanning [49..51]: LBA 49 (zeros), LBA 50 (data), LBA 51 (zeros)
                val multiRead = ByteArray(3 * 512) { 0xFF.toByte() }
                assertTrue(dev.read(49L, 3, multiRead))

                // Verify LBA 49 slice is all zeroes
                assertArrayEquals(ByteArray(512), multiRead.copyOfRange(0, 512))
                // Verify LBA 50 slice is sector50Data
                assertArrayEquals(sector50Data, multiRead.copyOfRange(512, 1024))
                // Verify LBA 51 slice is all zeroes
                assertArrayEquals(ByteArray(512), multiRead.copyOfRange(1024, 1536))
            }
        }
    }

    @Test
    fun testFileBackedBlockDeviceClosedDeviceBehavior() {
        runBlocking {
            val diskImageFile = File(tempFolder.root, "closed_test.img")
            val dev = FileBackedBlockDevice(diskImageFile, totalSectors = 100L, sectorSizeBytes = 512)
            assertTrue(dev.isConnected)

            // Close the device
            dev.close()
            assertFalse(dev.isConnected)

            // Verify all operations throw DeviceDisconnectedException
            assertThrows(DeviceDisconnectedException::class.java) {
                runBlocking { dev.capacity() }
            }
            assertThrows(DeviceDisconnectedException::class.java) {
                runBlocking { dev.read(0L, 1, ByteArray(512)) }
            }
            assertThrows(DeviceDisconnectedException::class.java) {
                runBlocking { dev.write(0L, 1, ByteArray(512)) }
            }
            assertThrows(DeviceDisconnectedException::class.java) {
                runBlocking {
                    dev.writeDirectBuffer(0L, 1, ByteBuffer.allocateDirect(512), 0, 512)
                }
            }
            assertThrows(DeviceDisconnectedException::class.java) {
                runBlocking { dev.flush() }
            }

            // Close should be safe and idempotent when called multiple times
            dev.close()
            assertFalse(dev.isConnected)
        }
    }

    @Test
    fun testFileBackedBlockDeviceDiskImageMbrGptIntegration() {
        runBlocking {
            val diskImageFile = File(tempFolder.root, "integration_disk.img")
            val totalSectors = 2048L // 1 MB virtual disk

            // Step 1: Initialize disk image, write protective MBR and GPT layout
            FileBackedBlockDevice(diskImageFile, totalSectors = totalSectors, sectorSizeBytes = 512, preallocate = true).use { dev ->
                val mbr = MbrBuilder.buildProtectiveMbr(totalSectors)
                assertTrue(dev.write(0L, 1, mbr))

                val gptPart = GptBuilder.GptPartition(
                    typeGuid = GptBuilder.GUID_MICROSOFT_BASIC_DATA,
                    firstLba = 100L,
                    lastLba = 1900L,
                    partitionName = "TEST_PART"
                )
                val layout = GptBuilder.build(
                    totalDiskSectors = totalSectors,
                    partitions = listOf(gptPart),
                    sectorSizeBytes = 512
                )

                assertTrue(dev.write(1L, 1, layout.primaryHeaderSector))
                assertTrue(dev.write(2L, 32, layout.primaryPartitionTableBytes))

                val backupTableLba = totalSectors - 1L - 32L
                assertTrue(dev.write(backupTableLba, 32, layout.backupPartitionTableBytes))
                assertTrue(dev.write(totalSectors - 1L, 1, layout.backupHeaderSector))

                assertTrue(dev.flush())
            }

            // Step 2: Reopen existing disk image via openExisting() and perform independent structural validation
            FileBackedBlockDevice.openExisting(diskImageFile).use { dev ->
                val cap = dev.capacity()
                assertEquals(totalSectors, cap.totalSectors)
                assertEquals(512, cap.sectorSizeBytes)

                // 1. Independent Protective MBR verification
                val mbrRead = ByteArray(512)
                assertTrue(dev.read(0L, 1, mbrRead))
                assertEquals(0x55.toByte(), mbrRead[510])
                assertEquals(0xAA.toByte(), mbrRead[511])
                assertEquals(0x00.toByte(), mbrRead[446]) // Boot indicator: not active
                assertEquals(0xEE.toByte(), mbrRead[446 + 4]) // Partition 1 type: GPT protective
                val mbrBuf = ByteBuffer.wrap(mbrRead).order(ByteOrder.LITTLE_ENDIAN)
                assertEquals(1, mbrBuf.getInt(446 + 8)) // Starting LBA == 1

                // 2. Independent Primary GPT Header verification (LBA 1)
                val primaryHeader = ByteArray(512)
                assertTrue(dev.read(1L, 1, primaryHeader))
                val sig = String(primaryHeader, 0, 8, Charsets.US_ASCII)
                assertEquals("EFI PART", sig)

                val headerBuf = ByteBuffer.wrap(primaryHeader).order(ByteOrder.LITTLE_ENDIAN)
                assertEquals(0x00010000, headerBuf.getInt(8)) // Revision 1.0
                val headerSize = headerBuf.getInt(12)
                assertEquals(92, headerSize) // Standard GPT header size is 92 bytes
                val onDiskHeaderCrc = headerBuf.getInt(16)
                assertEquals(1L, headerBuf.getLong(24)) // current LBA == 1
                assertEquals(totalSectors - 1L, headerBuf.getLong(32)) // backup LBA
                assertEquals(34L, headerBuf.getLong(40)) // first usable LBA
                assertEquals(totalSectors - 34L, headerBuf.getLong(48)) // last usable LBA
                assertEquals(2L, headerBuf.getLong(72)) // partition table LBA
                val numEntries = headerBuf.getInt(80)
                val entrySize = headerBuf.getInt(84)
                assertEquals(128, numEntries)
                assertEquals(128, entrySize)
                val onDiskPartitionArrayCrc = headerBuf.getInt(88)

                // Verify Header CRC32 independently: compute CRC32 over 92 bytes with CRC field zeroed
                val headerForCrc = primaryHeader.copyOfRange(0, headerSize)
                headerForCrc[16] = 0; headerForCrc[17] = 0; headerForCrc[18] = 0; headerForCrc[19] = 0
                val headerCrcCalc = CRC32()
                headerCrcCalc.update(headerForCrc)
                assertEquals(onDiskHeaderCrc, headerCrcCalc.value.toInt())

                // 3. Independent Partition Table verification (LBA 2..33 = 32 sectors = 16384 bytes)
                val partArrayBytes = ByteArray(numEntries * entrySize)
                assertTrue(dev.read(2L, 32, partArrayBytes))
                val partArrayCrcCalc = CRC32()
                partArrayCrcCalc.update(partArrayBytes)
                assertEquals(onDiskPartitionArrayCrc, partArrayCrcCalc.value.toInt())

                // Parse Partition Entry 1 independently (first 128 bytes of partition array)
                val partTypeGuid = GptGuidHelper.fromMixedEndianByteArray(partArrayBytes, 0)
                assertEquals(GptGuidHelper.GUID_MICROSOFT_BASIC_DATA, partTypeGuid)

                val entryBuf = ByteBuffer.wrap(partArrayBytes).order(ByteOrder.LITTLE_ENDIAN)
                val partStartLba = entryBuf.getLong(32)
                val partEndLba = entryBuf.getLong(40)
                assertEquals(100L, partStartLba)
                assertEquals(1900L, partEndLba)

                val nameChars = CharArray(36)
                for (i in 0 until 36) {
                    nameChars[i] = entryBuf.getChar(56 + i * 2)
                }
                val partName = String(nameChars).trimEnd('\u0000')
                assertEquals("TEST_PART", partName)

                // 4. Independent Backup GPT Header verification (last sector)
                val backupHeader = ByteArray(512)
                assertTrue(dev.read(totalSectors - 1L, 1, backupHeader))
                val backupSig = String(backupHeader, 0, 8, Charsets.US_ASCII)
                assertEquals("EFI PART", backupSig)

                val backupBuf = ByteBuffer.wrap(backupHeader).order(ByteOrder.LITTLE_ENDIAN)
                assertEquals(totalSectors - 1L, backupBuf.getLong(24))
                assertEquals(1L, backupBuf.getLong(32))
                val onDiskBackupHeaderCrc = backupBuf.getInt(16)

                val backupForCrc = backupHeader.copyOfRange(0, headerSize)
                backupForCrc[16] = 0; backupForCrc[17] = 0; backupForCrc[18] = 0; backupForCrc[19] = 0
                val backupCrcCalc = CRC32()
                backupCrcCalc.update(backupForCrc)
                assertEquals(onDiskBackupHeaderCrc, backupCrcCalc.value.toInt())
            }
        }
    }

    @Test
    fun testConcurrentCloseDuringOperations() {
        runBlocking(Dispatchers.Default) {
            val diskImageFile = File(tempFolder.root, "concurrent_close.img")
            val totalSectors = 4096L
            val dev = FileBackedBlockDevice(diskImageFile, totalSectors = totalSectors, sectorSizeBytes = 512)

            val opCount = 40
            val latch = CountDownLatch(opCount / 2)
            val successCount = AtomicInteger(0)
            val disconnectCount = AtomicInteger(0)

            val jobs = (0 until opCount).map { i ->
                launch {
                    val lba = (i * 10L) % 100L
                    val buffer = ByteArray(512) { (i % 128).toByte() }
                    try {
                        if (i % 2 == 0) {
                            latch.countDown()
                            dev.write(lba, 1, buffer)
                        } else {
                            dev.read(lba, 1, buffer)
                        }
                        successCount.incrementAndGet()
                    } catch (e: DeviceDisconnectedException) {
                        disconnectCount.incrementAndGet()
                    }
                }
            }

            // Await half the operations to start, then trigger close
            latch.await(2, TimeUnit.SECONDS)
            dev.close()

            jobs.forEach { it.join() }

            assertFalse(dev.isConnected)
            assertEquals(opCount, successCount.get() + disconnectCount.get())
        }
    }

    @Test
    fun testConcurrentMultipleReadsAndWrites() {
        runBlocking(Dispatchers.Default) {
            val diskImageFile = File(tempFolder.root, "concurrent_rw.img")
            val totalSectors = 2048L
            FileBackedBlockDevice(diskImageFile, totalSectors = totalSectors, sectorSizeBytes = 512).use { dev ->
                val workerCount = 16
                val jobs = (0 until workerCount).map { workerId ->
                    launch {
                        val sectorLba = workerId.toLong() * 10L
                        val payload = ByteArray(512) { (workerId + 1).toByte() }
                        assertTrue(dev.write(sectorLba, 1, payload))

                        val readBack = ByteArray(512)
                        assertTrue(dev.read(sectorLba, 1, readBack))
                        assertArrayEquals(payload, readBack)
                    }
                }
                jobs.forEach { it.join() }
                assertTrue(dev.flush())
            }
        }
    }

    @Test
    fun testConcurrentRepeatedClose() {
        runBlocking(Dispatchers.Default) {
            val diskImageFile = File(tempFolder.root, "concurrent_repeated_close.img")
            val dev = FileBackedBlockDevice(diskImageFile, totalSectors = 100L, sectorSizeBytes = 512)
            assertTrue(dev.isConnected)

            val threads = 8
            val jobs = (0 until threads).map {
                launch {
                    dev.close()
                }
            }
            jobs.forEach { it.join() }

            assertFalse(dev.isConnected)
            assertThrows(DeviceDisconnectedException::class.java) {
                runBlocking { dev.capacity() }
            }
        }
    }

    @Test
    fun testFileBackedBlockDeviceDirectBufferContractAndHeapBuffer() {
        runBlocking {
            val diskImageFile = File(tempFolder.root, "buffer_contract.img")
            val totalSectors = 100L

            FileBackedBlockDevice(diskImageFile, totalSectors = totalSectors, sectorSizeBytes = 512).use { dev ->
                // 1. Heap ByteBuffer support
                val heapPayload = ByteArray(1024) { 0x42.toByte() }
                val heapBuffer = ByteBuffer.wrap(heapPayload)
                assertTrue(dev.writeDirectBuffer(lba = 0L, blockCount = 2, directBuffer = heapBuffer, offset = 0, length = 1024))
                val readBack = ByteArray(1024)
                assertTrue(dev.read(0L, 2, readBack))
                assertArrayEquals(heapPayload, readBack)

                // 2. Direct buffer with non-zero caller position
                val direct = ByteBuffer.allocateDirect(2048)
                val directPayload = ByteArray(512) { 0x33.toByte() }
                direct.position(256)
                direct.put(directPayload)
                direct.position(128)
                direct.limit(1024)

                val originalPos = direct.position()
                val originalLimit = direct.limit()

                // Write at offset 256 for 512 bytes
                assertTrue(dev.writeDirectBuffer(lba = 10L, blockCount = 1, directBuffer = direct, offset = 256, length = 512))

                // Verify caller's buffer position and limit are completely preserved
                assertEquals(originalPos, direct.position())
                assertEquals(originalLimit, direct.limit())

                val readBackDirect = ByteArray(512)
                assertTrue(dev.read(10L, 1, readBackDirect))
                assertArrayEquals(directPayload, readBackDirect)
            }
        }
    }

    @Test
    fun testFileBackedBlockDeviceBackingFileGeometrySemantics() {
        runBlocking {
            val exactFile = File(tempFolder.root, "exact.img")
            exactFile.writeBytes(ByteArray(2048)) // 4 sectors of 512B

            // Exact match: succeeds
            FileBackedBlockDevice(exactFile, totalSectors = 4L, sectorSizeBytes = 512).use { dev ->
                assertEquals(4L, dev.totalSectors)
                assertEquals(2048L, dev.capacity().totalBytes)
            }

            // Smaller existing file (valid sparse disk image): succeeds without truncating
            val sparseFile = File(tempFolder.root, "sparse_small.img")
            sparseFile.writeBytes(ByteArray(1024)) // 2 sectors on disk
            FileBackedBlockDevice(sparseFile, totalSectors = 8L, sectorSizeBytes = 512, preallocate = false).use { dev ->
                assertEquals(8L, dev.totalSectors)
                assertEquals(4096L, dev.capacity().totalBytes)
                assertEquals(1024L, sparseFile.length()) // physical size on disk remains 1024 bytes
            }

            // Larger existing file (8 sectors on disk, requested 4 sectors): rejected to avoid destructive truncation
            val largerFile = File(tempFolder.root, "larger.img")
            largerFile.writeBytes(ByteArray(4096))
            val exLarger = assertThrows(IllegalArgumentException::class.java) {
                FileBackedBlockDevice(largerFile, totalSectors = 4L, sectorSizeBytes = 512)
            }
            assertTrue(exLarger.message!!.contains("exceeds requested logical capacity"))

            // Sector size mismatch: 1024-byte file is not divisible by 768
            val mismatchFile = File(tempFolder.root, "mismatch.img")
            mismatchFile.writeBytes(ByteArray(1024))
            val exMismatch = assertThrows(IllegalArgumentException::class.java) {
                FileBackedBlockDevice(mismatchFile, totalSectors = 2L, sectorSizeBytes = 768)
            }
            assertTrue(exMismatch.message!!.contains("not an exact multiple of sector size"))
        }
    }

    @Test
    fun testFileBackedBlockDeviceConstructorResourceCleanup() {
        val testFile = File(tempFolder.root, "leak_test.img")
        testFile.writeBytes(ByteArray(4096))

        // Constructor fails due to backing file size exceeding requested capacity
        assertThrows(IllegalArgumentException::class.java) {
            FileBackedBlockDevice(testFile, totalSectors = 2L, sectorSizeBytes = 512)
        }

        // Verify file is not locked by an unclosed file channel / descriptor
        assertTrue("Backing file should be immediately deletable without descriptor leaks", testFile.delete())
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
