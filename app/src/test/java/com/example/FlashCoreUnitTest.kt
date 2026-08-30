package com.example

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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
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
}
