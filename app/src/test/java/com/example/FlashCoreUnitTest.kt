package com.example

import com.example.block.BlockDevice
import com.example.block.DeviceCapacity
import com.example.block.DeviceDisconnectedException
import com.example.block.MemoryBlockDevice
import com.example.dsa.DirectRingBuffer
import com.example.dsa.IsoTrieParser
import com.example.dsa.RollingChecksumEngine
import com.example.dsa.WimChunker
import com.example.partition.Fat32Formatter
import com.example.partition.GptBuilder
import com.example.partition.MbrBuilder
import com.example.scsi.CommandBlockWrapper
import com.example.scsi.CommandStatusWrapper
import com.example.scsi.ScsiCdbBuilder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.UUID

/**
 * Unit test suite for FlashCore Low-Level SCSI, DSA, and Partition Engines.
 */
class FlashCoreUnitTest {

    @Test
    fun testScsiCommandBlockWrapperEncoding() {
        val cdb = ScsiCdbBuilder.write10(lba = 1000L, blockCount = 64)
        assertEquals(10, cdb.size)
        assertEquals(0x2A.toByte(), cdb[0]) // WRITE_10 opcode

        val cbw = CommandBlockWrapper.create(
            dataTransferLength = 64 * 512,
            direction = CommandBlockWrapper.Direction.DATA_OUT,
            cdb = cdb,
            tag = 0x12345678
        )

        val bytes = cbw.toByteArray()
        assertEquals(31, bytes.size) // Standard USB Mass Storage CBW length is 31 bytes
        assertEquals(0x55.toByte(), bytes[0])
        assertEquals(0x53.toByte(), bytes[1])
        assertEquals(0x42.toByte(), bytes[2])
        assertEquals(0x43.toByte(), bytes[3]) // "USBC" magic
    }

    @Test
    fun testScsiCommandStatusWrapperParsing() {
        // Construct standard 13-byte CSW ("USBS" magic, tag 0x12345678, residue 0, status 0 = success)
        val cswBytes = ByteArray(13)
        cswBytes[0] = 0x55.toByte()
        cswBytes[1] = 0x53.toByte()
        cswBytes[2] = 0x42.toByte()
        cswBytes[3] = 0x53.toByte() // "USBS"
        cswBytes[4] = 0x78.toByte()
        cswBytes[5] = 0x56.toByte()
        cswBytes[6] = 0x34.toByte()
        cswBytes[7] = 0x12.toByte() // Tag 0x12345678
        cswBytes[12] = 0x00 // Status SUCCESS

        val csw = CommandStatusWrapper.parse(cswBytes)
        assertTrue(csw.isSuccess)
        assertFalse(csw.isPhaseError)
        assertEquals(0x12345678, csw.tag)
    }

    @Test
    fun testDirectRingBufferOffHeapConcurrency() {
        val ring = DirectRingBuffer(chunkCapacity = 4, chunkSizeBytes = 1024)
        assertFalse(ring.isClosed)
        assertEquals(0.0f, ring.getSaturation(), 0.01f)

        // Producer writes
        val slot = ring.acquireWriteSlot()
        assertNotNull(slot)
        slot.buffer.put("HELLO_FLASHCORE".toByteArray(Charsets.US_ASCII))
        ring.commitWrite(slot.slotIndex, 15, 0L)

        assertEquals(0.25f, ring.getSaturation(), 0.01f)

        // Consumer reads
        val readSlot = ring.acquireReadSlot()
        assertNotNull(readSlot)
        assertEquals(15, readSlot!!.validBytes)
        ring.commitRead(readSlot.slotIndex)

        ring.close()
        assertTrue(ring.isClosed)
    }

    @Test
    fun testIsoTrieLookup() {
        val parser = IsoTrieParser()
        val entry = IsoTrieParser.IsoEntry(
            path = "/sources/install.wim",
            name = "install.wim",
            lba = 5000L,
            sizeBytes = 5L * 1024L * 1024L * 1024L, // 5 GB
            isDirectory = false,
            flags = 0
        )
        parser.insert(entry)

        assertTrue(parser.contains("sources/install.wim"))
        assertTrue(parser.contains("/sources/install.wim/"))
        assertFalse(parser.contains("efi/boot/bootx64.efi"))

        val found = parser.find("sources/install.wim")
        assertNotNull(found)
        assertEquals(5000L, found?.lba)
    }

    @Test
    fun testWimSplitPlanning() {
        val fiveGbWim = 5L * 1024L * 1024L * 1024L // 5 GB
        val plan = WimChunker.planSwmSplit(fiveGbWim, maxChunkBytes = 3800L * 1024L * 1024L)

        assertEquals(2, plan.size)
        assertEquals("install.swm", plan[0].fileName)
        assertEquals("install2.swm", plan[1].fileName)
        assertEquals(1, plan[0].partIndex)
        assertEquals(2, plan[1].partIndex)
    }

    @Test
    fun testMbrAndGptBuilders() {
        val totalSectors = 62914560L // 32 GB drive
        val protectiveMbr = MbrBuilder.buildProtectiveMbr(totalSectors)
        assertEquals(512, protectiveMbr.size)
        assertEquals(0x55.toByte(), protectiveMbr[510])
        assertEquals(0xAA.toByte(), protectiveMbr[511])

        val gptPartition = GptBuilder.GptPartition(
            typeGuid = GptBuilder.GUID_MICROSOFT_BASIC_DATA,
            firstLba = 2048L,
            lastLba = totalSectors - 2048L,
            partitionName = "BOOT_MEDIA"
        )
        val gptLayout = GptBuilder.build(
            totalDiskSectors = totalSectors,
            partitions = listOf(gptPartition)
        )

        assertEquals(512, gptLayout.primaryHeaderSector.size)
        assertEquals(16384, gptLayout.primaryPartitionTableBytes.size) // 128 entries x 128 bytes
        val sig = String(gptLayout.primaryHeaderSector.copyOfRange(0, 8), Charsets.US_ASCII)
        assertEquals("EFI PART", sig)
    }

    @Test
    fun testFat32FormatterStructure() {
        val totalSectors = 10000000L
        val fat32 = Fat32Formatter.format(totalSectors, "WIN_BOOT")

        assertEquals(512, fat32.vbrSector.size)
        assertEquals(512, fat32.fsInfoSector.size)
        assertEquals(0x55.toByte(), fat32.vbrSector[510])
        assertEquals(0xAA.toByte(), fat32.vbrSector[511])
        assertTrue(fat32.sectorsPerFat > 0)
    }

    @Test
    fun testRollingChecksums() {
        val testData = "FlashCore High-Performance Low-Level BOT Driver".toByteArray(Charsets.UTF_8)
        val crc = RollingChecksumEngine.computeCrc32(testData)
        assertTrue(crc != 0L)

        val murmur = RollingChecksumEngine.murmurHash3(testData)
        assertTrue(murmur != 0)

        val sha256 = RollingChecksumEngine.sha256Hex(testData)
        assertEquals(64, sha256.length)
    }

    @Test
    fun testScsi16ByteCdbBuilders() {
        // READ_16: 64-bit LBA (e.g. 0x0000000100000000L = 4,294,967,296) and 32-bit block count
        val lba64 = 0x0000000100000000L
        val blockCount = 128L
        val read16Cdb = ScsiCdbBuilder.read16(lba = lba64, blockCount = blockCount)

        assertEquals(16, read16Cdb.size)
        assertEquals(ScsiCdbBuilder.OP_READ_16, read16Cdb[0]) // Opcode 0x88
        // Byte 2..9: 64-bit LBA in big endian
        assertEquals(0x00.toByte(), read16Cdb[2])
        assertEquals(0x00.toByte(), read16Cdb[3])
        assertEquals(0x00.toByte(), read16Cdb[4])
        assertEquals(0x01.toByte(), read16Cdb[5])
        assertEquals(0x00.toByte(), read16Cdb[6])
        assertEquals(0x00.toByte(), read16Cdb[7])
        assertEquals(0x00.toByte(), read16Cdb[8])
        assertEquals(0x00.toByte(), read16Cdb[9])
        // Byte 10..13: 32-bit transfer length in big endian
        assertEquals(0x00.toByte(), read16Cdb[10])
        assertEquals(0x00.toByte(), read16Cdb[11])
        assertEquals(0x00.toByte(), read16Cdb[12])
        assertEquals(128.toByte(), read16Cdb[13])

        // WRITE_16: 64-bit LBA and 32-bit block count
        val write16Cdb = ScsiCdbBuilder.write16(lba = lba64, blockCount = blockCount)
        assertEquals(16, write16Cdb.size)
        assertEquals(ScsiCdbBuilder.OP_WRITE_16, write16Cdb[0]) // Opcode 0x8A
        assertEquals(0x01.toByte(), write16Cdb[5])
        assertEquals(128.toByte(), write16Cdb[13])
    }

    @Test
    fun testScsiRequestSenseAndModeSenseParsing() {
        // MODE_SENSE_6: write protect bit set in byte 2 (device specific parameter)
        val modeDataWp = byteArrayOf(0x03, 0x00, 0x80.toByte(), 0x00)
        val modeRespWp = ScsiCdbBuilder.parseModeSense6(modeDataWp)
        assertTrue(modeRespWp.isWriteProtected)
        assertEquals(3, modeRespWp.modeDataLength)

        // MODE_SENSE_6: write protect NOT set
        val modeDataRw = byteArrayOf(0x03, 0x00, 0x00, 0x00)
        val modeRespRw = ScsiCdbBuilder.parseModeSense6(modeDataRw)
        assertFalse(modeRespRw.isWriteProtected)

        // REQUEST_SENSE: 18-byte standard response
        val senseBytes = ByteArray(18)
        senseBytes[0] = 0x70 // Current fixed error code
        senseBytes[2] = 0x03 // MEDIUM ERROR
        senseBytes[12] = 0x27 // ASC: Write protected
        senseBytes[13] = 0x00 // ASCQ: 00

        val sense = ScsiCdbBuilder.parseRequestSense(senseBytes)
        assertEquals(0x70, sense.responseCode)
        assertEquals(0x03, sense.senseKey)
        assertEquals("MEDIUM ERROR", sense.senseKeyDescription)
        assertEquals(0x27, sense.additionalSenseCode)
        assertEquals("Write protected", sense.ascDescription)

        // READ_CAPACITY_16: 32 bytes
        val cap16Bytes = ByteArray(32)
        val capBuf = ByteBuffer.wrap(cap16Bytes)
        capBuf.putLong(8589934591L) // Max LBA for 4 TB
        capBuf.putInt(512) // Sector size
        val cap16 = ScsiCdbBuilder.parseReadCapacity16(cap16Bytes)
        assertEquals(8589934591L, cap16.maxLba)
        assertEquals(512, cap16.blockSizeBytes)
        assertEquals(8589934592L * 512L, cap16.totalCapacityBytes)
    }

    @Test
    fun testMemoryBlockDeviceBasicIo() {
        runBlocking {
        val totalSectors = 4096L
        val sectorSize = 512
        val device = MemoryBlockDevice(totalSectors = totalSectors, sectorSizeBytes = sectorSize)

        // Initial capacity verification
        val cap = device.capacity()
        assertEquals(totalSectors, cap.totalSectors)
        assertEquals(sectorSize, cap.sectorSizeBytes)
        assertEquals(2.0, cap.totalBytes.toDouble() / (1024 * 1024), 0.01) // 2 MB
        assertTrue(device.isConnected)

        // Reading unallocated sector returns zeroes
        val readZeroes = ByteArray(sectorSize)
        device.read(lba = 10L, blockCount = 1, dest = readZeroes)
        assertArrayEquals(ByteArray(sectorSize), readZeroes)

        // Write sector 10 and 11
        val writePayload = ByteArray(sectorSize * 2) { idx -> (idx % 256).toByte() }
        val writeOk = device.write(lba = 10L, blockCount = 2, src = writePayload)
        assertTrue(writeOk)
        assertEquals(2, device.allocatedSectorCount)
        assertEquals(2L, device.writeCount)

        // Read back sector 10 and 11
        val readBack = ByteArray(sectorSize * 2)
        val readOk = device.read(lba = 10L, blockCount = 2, dest = readBack)
        assertTrue(readOk)
        assertArrayEquals(writePayload, readBack)

        // DirectBuffer write
        val directBuf = ByteBuffer.allocateDirect(sectorSize)
        val directPattern = ByteArray(sectorSize) { 0x42.toByte() }
        directBuf.put(directPattern)
        directBuf.flip()

        val directOk = device.writeDirectBuffer(lba = 20L, blockCount = 1, directBuffer = directBuf, offset = 0, length = sectorSize)
        assertTrue(directOk)
        assertEquals(3, device.allocatedSectorCount)

        val directRead = ByteArray(sectorSize)
        device.read(lba = 20L, blockCount = 1, dest = directRead)
        assertArrayEquals(directPattern, directRead)

        // Flush
        val flushOk = device.flush()
        assertTrue(flushOk)
        assertEquals(1L, device.flushCount)

        device.close()
        assertFalse(device.isConnected)
        }
    }

    @Test
    fun testMemoryBlockDeviceBoundsAndErrorHandling() {
        runBlocking {
            val device = MemoryBlockDevice(totalSectors = 100L, sectorSizeBytes = 512)

            // Negative LBA should throw IOException
            assertThrows(IOException::class.java) {
                runBlocking { device.read(lba = -1L, blockCount = 1, dest = ByteArray(512)) }
            }

            // Out of bounds LBA
            assertThrows(IOException::class.java) {
                runBlocking { device.write(lba = 99L, blockCount = 2, src = ByteArray(1024)) }
            }

            // Fault injection
            device.simulateIoFailure = true
            assertThrows(IOException::class.java) {
                runBlocking { device.read(lba = 0L, blockCount = 1, dest = ByteArray(512)) }
            }
            assertThrows(IOException::class.java) {
                runBlocking { device.write(lba = 0L, blockCount = 1, src = ByteArray(512)) }
            }
            assertThrows(IOException::class.java) {
                runBlocking { device.flush() }
            }
            device.simulateIoFailure = false

            // Disconnection
            device.isConnected = false
            assertThrows(DeviceDisconnectedException::class.java) {
                runBlocking { device.capacity() }
            }
            assertThrows(DeviceDisconnectedException::class.java) {
                runBlocking { device.read(lba = 0L, blockCount = 1, dest = ByteArray(512)) }
            }
        }
    }
}
