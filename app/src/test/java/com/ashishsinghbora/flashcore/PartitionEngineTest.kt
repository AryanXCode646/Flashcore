package com.ashishsinghbora.flashcore

import com.ashishsinghbora.flashcore.block.MemoryBlockDevice
import com.ashishsinghbora.flashcore.partition.GptGuidHelper
import com.ashishsinghbora.flashcore.partition.MbrBuilder
import com.ashishsinghbora.flashcore.partition.MbrPartitionTable
import com.ashishsinghbora.flashcore.partition.GptPartitionTable
import com.ashishsinghbora.flashcore.partition.Partition
import com.ashishsinghbora.flashcore.partition.PartitionAlignment
import com.ashishsinghbora.flashcore.partition.PartitionBlockDevice
import com.ashishsinghbora.flashcore.partition.PartitionEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * Test suite for the Partition Subsystem (MBR, GPT, PartitionTable, Partition -> BlockDevice).
 */
class PartitionEngineTest {

    // ------------------------------------------------------------------------
    // MBR Tests
    // ------------------------------------------------------------------------

    @Test
    fun testMbrBuildSerializeAndParse() {
        val totalSectors = 2097152L // 1 GB
        val p1 = Partition(
            index = 1,
            firstLba = 2048L,
            lastLba = 100000L,
            name = "System",
            mbrType = MbrBuilder.TYPE_FAT32_LBA,
            bootable = true
        )
        val p2 = Partition(
            index = 2,
            firstLba = 102400L, // 1MB aligned
            lastLba = 500000L,
            name = "Data",
            mbrType = MbrBuilder.TYPE_NTFS_EXFAT,
            bootable = false
        )

        val table = MbrPartitionTable(
            partitions = listOf(p1, p2),
            totalDiskSectors = totalSectors,
            diskSignature = 0x12345678
        )

        val validation = table.validate()
        assertTrue(validation.isValid)
        assertTrue(validation.errors.isEmpty())

        // Build raw MBR bytes (512 bytes)
        val mbrBytes = PartitionEngine.buildMbrBytes(table)
        assertEquals(512, mbrBytes.size)

        // Verify Boot Signature 0x55, 0xAA at 510..511
        assertEquals(0x55.toByte(), mbrBytes[510])
        assertEquals(0xAA.toByte(), mbrBytes[511])

        // Verify 32-bit Disk Signature at 440
        val buf = ByteBuffer.wrap(mbrBytes).order(ByteOrder.LITTLE_ENDIAN)
        buf.position(440)
        assertEquals(0x12345678, buf.int)

        // Verify Partition 1 Entry (offset 446)
        buf.position(446)
        assertEquals(0x80.toByte(), buf.get()) // Bootable
        buf.position(446 + 4)
        assertEquals(MbrBuilder.TYPE_FAT32_LBA, buf.get()) // Type 0x0C
        buf.position(446 + 8)
        assertEquals(2048, buf.int) // Start LBA
        assertEquals(100000 - 2048 + 1, buf.int) // Sector count

        // Verify Partition 2 Entry (offset 462)
        buf.position(462)
        assertEquals(0x00.toByte(), buf.get()) // Non-bootable
        buf.position(462 + 4)
        assertEquals(MbrBuilder.TYPE_NTFS_EXFAT, buf.get()) // Type 0x07
        buf.position(462 + 8)
        assertEquals(102400, buf.int)

        // Parse bytes back into MbrPartitionTable
        val parsed = PartitionEngine.parseMbr(mbrBytes, totalSectors)
        assertEquals(2, parsed.partitions.size)
        assertEquals(0x12345678, parsed.diskSignature)
        assertFalse(parsed.isProtective)

        assertEquals(1, parsed.partitions[0].index)
        assertEquals(2048L, parsed.partitions[0].firstLba)
        assertEquals(100000L, parsed.partitions[0].lastLba)
        assertTrue(parsed.partitions[0].bootable)
        assertEquals(MbrBuilder.TYPE_FAT32_LBA, parsed.partitions[0].mbrType)

        assertEquals(2, parsed.partitions[1].index)
        assertEquals(102400L, parsed.partitions[1].firstLba)
        assertEquals(500000L, parsed.partitions[1].lastLba)
        assertFalse(parsed.partitions[1].bootable)
    }

    @Test
    fun testProtectiveMbrGenerationAndDetection() {
        val totalSectors = 62914560L // 30 GB disk
        val protectiveBytes = PartitionEngine.buildProtectiveMbr(totalSectors)

        assertEquals(512, protectiveBytes.size)
        assertEquals(0x55.toByte(), protectiveBytes[510])
        assertEquals(0xAA.toByte(), protectiveBytes[511])

        val parsed = PartitionEngine.parseMbr(protectiveBytes, totalSectors)
        assertTrue("Must be identified as protective MBR", parsed.isProtective)
        assertEquals(1, parsed.partitions.size)
        assertEquals(1L, parsed.partitions[0].firstLba)
        assertEquals(MbrBuilder.TYPE_GPT_PROTECTIVE, parsed.partitions[0].mbrType)
    }

    @Test
    fun testMbrValidationRules() {
        val totalSectors = 100000L

        // Error: starts at LBA 0 (overlaps MBR)
        val badLba0 = MbrPartitionTable(
            partitions = listOf(Partition(1, 0L, 500L, "Bad", mbrType = 0x83.toByte())),
            totalDiskSectors = totalSectors
        )
        assertFalse(badLba0.validate().isValid)
        assertTrue(badLba0.validate().errors.any { it.contains("overlaps MBR") })

        // Error: overlapping partitions
        val overlapping = MbrPartitionTable(
            partitions = listOf(
                Partition(1, 2048L, 5000L, "Part1", mbrType = 0x83.toByte()),
                Partition(2, 4000L, 8000L, "Part2", mbrType = 0x83.toByte())
            ),
            totalDiskSectors = totalSectors
        )
        assertFalse(overlapping.validate().isValid)
        assertTrue(overlapping.validate().errors.any { it.contains("overlaps") })

        // Error: extends past disk sectors
        val outOfBounds = MbrPartitionTable(
            partitions = listOf(Partition(1, 2048L, 200000L, "TooBig", mbrType = 0x83.toByte())),
            totalDiskSectors = totalSectors
        )
        assertFalse(outOfBounds.validate().isValid)
        assertTrue(outOfBounds.validate().errors.any { it.contains("exceeds disk sectors") })

        // Error: more than 4 primary partitions
        val tooMany = MbrPartitionTable(
            partitions = (1..5).map { idx ->
                Partition(idx, idx * 2048L, idx * 2048L + 1000L, "P$idx", mbrType = 0x83.toByte())
            },
            totalDiskSectors = totalSectors
        )
        assertFalse(tooMany.validate().isValid)
        assertTrue(tooMany.validate().errors.any { it.contains("at most 4") })

        // Warning: unaligned partition
        val unaligned = MbrPartitionTable(
            partitions = listOf(Partition(1, 100L, 5000L, "Unaligned", mbrType = 0x83.toByte())),
            totalDiskSectors = totalSectors
        )
        assertTrue(unaligned.validate().isValid) // Valid, but has warning
        assertTrue(unaligned.validate().warnings.any { it.contains("not 1MB aligned") })
    }

    // ------------------------------------------------------------------------
    // GPT Tests
    // ------------------------------------------------------------------------

    @Test
    fun testGptLayoutGenerationAndCrc32Integrity() {
        val totalSectors = 2097152L // 1 GB disk
        val diskGuid = UUID.fromString("6F81B1F2-92AE-4394-9D22-A4B7D26E6C54")

        val efiPart = Partition(
            index = 1,
            firstLba = 2048L,
            lastLba = 65535L,
            name = "EFI System",
            typeGuid = GptGuidHelper.GUID_EFI_SYSTEM,
            uniqueGuid = UUID.fromString("A0B1C2D3-E4F5-6789-0123-456789ABCDEF")
        )
        val dataPart = Partition(
            index = 2,
            firstLba = 65536L,
            lastLba = 2095103L,
            name = "Linux Data",
            typeGuid = GptGuidHelper.GUID_LINUX_FILESYSTEM,
            uniqueGuid = UUID.fromString("FEDCBA98-7654-3210-FEDC-BA9876543210")
        )

        val table = GptPartitionTable(
            partitions = listOf(efiPart, dataPart),
            totalDiskSectors = totalSectors,
            diskGuid = diskGuid
        )

        val validation = table.validate()
        assertTrue(validation.isValid)
        assertTrue(validation.errors.isEmpty())

        val layout = PartitionEngine.buildGptLayout(table)

        // 1. Primary Header Verification (LBA 1)
        val hBytes = layout.primaryHeader
        assertEquals(512, hBytes.size)
        val sig = String(hBytes, 0, 8, Charsets.US_ASCII)
        assertEquals("EFI PART", sig)

        val hBuf = ByteBuffer.wrap(hBytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(PartitionEngine.GPT_REVISION, hBuf.getInt(8))
        assertEquals(92, hBuf.getInt(12))

        val recordedHeaderCrc = hBuf.getInt(16).toLong() and 0xFFFFFFFFL
        val computedHeaderCrc = PartitionEngine.computeHeaderCrc32(hBytes, 92)
        assertEquals("Primary Header CRC32 must match", computedHeaderCrc, recordedHeaderCrc)

        assertEquals(1L, hBuf.getLong(24)) // MyLBA == 1
        assertEquals(totalSectors - 1L, hBuf.getLong(32)) // AlternateLBA == Last sector
        assertEquals(34L, hBuf.getLong(40)) // FirstUsableLBA
        assertEquals(totalSectors - 34L, hBuf.getLong(48)) // LastUsableLBA

        // Verify Disk GUID
        val readDiskGuid = GptGuidHelper.readMixedEndian(ByteBuffer.wrap(hBytes, 56, 16))
        assertEquals(diskGuid, readDiskGuid)

        // 2. Partition Array CRC32 Verification
        val recordedTableCrc = hBuf.getInt(88).toLong() and 0xFFFFFFFFL
        val computedTableCrc = PartitionEngine.computeCrc32(layout.primaryPartitionTable)
        assertEquals("Partition Array CRC32 must match", computedTableCrc, recordedTableCrc)

        // 3. Backup Header Verification
        val bBytes = layout.backupHeader
        val bBuf = ByteBuffer.wrap(bBytes).order(ByteOrder.LITTLE_ENDIAN)
        val bRecordedCrc = bBuf.getInt(16).toLong() and 0xFFFFFFFFL
        val bComputedCrc = PartitionEngine.computeHeaderCrc32(bBytes, 92)
        assertEquals("Backup Header CRC32 must match", bComputedCrc, bRecordedCrc)
        assertEquals(totalSectors - 1L, bBuf.getLong(24)) // MyLBA == Last
        assertEquals(1L, bBuf.getLong(32)) // AlternateLBA == 1

        // 4. Structural Validator Verification
        val structValidation = PartitionEngine.validateGptStructures(
            layout.primaryHeader,
            layout.primaryPartitionTable,
            layout.backupHeader,
            totalSectors
        )
        assertTrue(structValidation.isValid)
        assertTrue(structValidation.errors.isEmpty())
    }

    @Test
    fun testGptDeviceWriteAndRoundTripParsing() {
        runBlocking {
            val totalSectors = 1048576L // 512 MB virtual drive
            val memDevice = MemoryBlockDevice(totalSectors = totalSectors, sectorSizeBytes = 512)

            val efiGuid = GptGuidHelper.GUID_EFI_SYSTEM
            val ntfsGuid = GptGuidHelper.GUID_MICROSOFT_BASIC_DATA
            val diskGuid = UUID.randomUUID()

            val p1 = Partition(1, 2048L, 65535L, "EFI_SYSTEM", typeGuid = efiGuid, uniqueGuid = UUID.randomUUID())
            val p2 = Partition(2, 65536L, 1040000L, "WINDOWS_C", typeGuid = ntfsGuid, uniqueGuid = UUID.randomUUID())

            val originalTable = GptPartitionTable(
                partitions = listOf(p1, p2),
                totalDiskSectors = totalSectors,
                diskGuid = diskGuid
            )

            // Write GPT layout to MemoryBlockDevice
            val writeSuccess = PartitionEngine.writeGptToDevice(originalTable, memDevice)
            assertTrue(writeSuccess)

            // Read partition table back from the device
            val parsedTable = PartitionEngine.readFromDevice(memDevice)
            assertNotNull(parsedTable)
            assertTrue(parsedTable is GptPartitionTable)

            val gpt = parsedTable as GptPartitionTable
            assertEquals(diskGuid, gpt.diskGuid)
            assertEquals(2, gpt.partitions.size)

            assertEquals(1, gpt.partitions[0].index)
            assertEquals(2048L, gpt.partitions[0].firstLba)
            assertEquals(65535L, gpt.partitions[0].lastLba)
            assertEquals("EFI_SYSTEM", gpt.partitions[0].name)
            assertEquals(efiGuid, gpt.partitions[0].typeGuid)

            assertEquals(2, gpt.partitions[1].index)
            assertEquals(65536L, gpt.partitions[1].firstLba)
            assertEquals(1040000L, gpt.partitions[1].lastLba)
            assertEquals("WINDOWS_C", gpt.partitions[1].name)
            assertEquals(ntfsGuid, gpt.partitions[1].typeGuid)
        }
    }

    @Test
    fun testGptTamperDetectionAndValidation() {
        val totalSectors = 2097152L
        val table = GptPartitionTable(
            partitions = listOf(
                Partition(1, 2048L, 100000L, "Root", typeGuid = GptGuidHelper.GUID_LINUX_FILESYSTEM)
            ),
            totalDiskSectors = totalSectors
        )
        val layout = PartitionEngine.buildGptLayout(table)

        // Tamper 1: Flip bit in primary header signature
        val tamperedHeader = layout.primaryHeader.clone()
        tamperedHeader[0] = 'X'.code.toByte()
        val res1 = PartitionEngine.validateGptStructures(tamperedHeader, layout.primaryPartitionTable, null, totalSectors)
        assertFalse(res1.isValid)
        assertTrue(res1.errors.any { it.contains("Invalid Primary GPT Header signature") })

        // Tamper 2: Corrupt 1 byte in partition table entry
        val tamperedTable = layout.primaryPartitionTable.clone()
        tamperedTable[10] = (tamperedTable[10].toInt() xor 0xFF).toByte()
        val res2 = PartitionEngine.validateGptStructures(layout.primaryHeader, tamperedTable, null, totalSectors)
        assertFalse(res2.isValid)
        assertTrue(res2.errors.any { it.contains("Partition Array CRC32 mismatch") })

        // Tamper 3: Corrupt byte in backup header
        val tamperedBackup = layout.backupHeader.clone()
        tamperedBackup[50] = (tamperedBackup[50].toInt() xor 0xFF).toByte()
        val res3 = PartitionEngine.validateGptStructures(layout.primaryHeader, layout.primaryPartitionTable, tamperedBackup, totalSectors)
        assertFalse(res3.isValid)
        assertTrue(res3.errors.any { it.contains("Backup GPT Header CRC32 mismatch") })
    }

    // ------------------------------------------------------------------------
    // Partition -> BlockDevice Subsystem Tests
    // ------------------------------------------------------------------------

    @Test
    fun testPartitionAsBlockDeviceMappingAndBounds() {
        runBlocking {
            val totalSectors = 100000L
            val memDevice = MemoryBlockDevice(totalSectors = totalSectors, sectorSizeBytes = 512)

            // Partition 1 spans LBA 2048 to 4095 (2,048 sectors = 1 MB)
            val p1 = Partition(
                index = 1,
                firstLba = 2048L,
                lastLba = 4095L,
                name = "Partition1",
                typeGuid = GptGuidHelper.GUID_LINUX_FILESYSTEM
            )

            // Expose partition as an isolated BlockDevice
            val partDevice = p1.asBlockDevice(memDevice)
            assertEquals(2048L, partDevice.capacity().totalSectors)
            assertEquals(512, partDevice.sectorSizeBytes)
            assertTrue(partDevice.isConnected)

            // Write 2 sectors to partition-relative LBA 0 (which should map to physical LBA 2048)
            val p1Payload = ByteArray(1024) { 0x33 }
            val writeOk = partDevice.write(lba = 0L, blockCount = 2, src = p1Payload)
            assertTrue(writeOk)

            // Verify physical sector 2048 and 2049 on underlying device match the written payload
            val physicalCheck = ByteArray(1024)
            memDevice.read(2048L, 2, physicalCheck)
            assertArrayEquals(p1Payload, physicalCheck)

            // Physical sector 2047 (outside partition) must be untouched zeroes
            val preSector = ByteArray(512)
            memDevice.read(2047L, 1, preSector)
            assertArrayEquals(ByteArray(512), preSector)

            // Read back through partDevice at relative LBA 0
            val readBack = ByteArray(1024)
            partDevice.read(lba = 0L, blockCount = 2, dest = readBack)
            assertArrayEquals(p1Payload, readBack)

            // Direct buffer write
            val directBuf = ByteBuffer.allocateDirect(512)
            directBuf.put(ByteArray(512) { 0x55 })
            directBuf.flip()
            partDevice.writeDirectBuffer(lba = 10L, blockCount = 1, directBuffer = directBuf, offset = 0, length = 512)

            val directPhysical = ByteArray(512)
            memDevice.read(2048L + 10L, 1, directPhysical)
            assertEquals(0x55.toByte(), directPhysical[0])

            // Flush sub-device flushes parent
            partDevice.flush()
            assertTrue(memDevice.flushCount > 0)

            // Bounds Enforcement: Attempt to write past the end of partition (relative LBA 2048 is out of bounds for a 2048-sector partition)
            assertThrows(IOException::class.java) {
                runBlocking {
                    partDevice.write(lba = 2047L, blockCount = 2, src = ByteArray(1024))
                }
            }

            // Negative relative LBA throws IOException
            assertThrows(IOException::class.java) {
                runBlocking {
                    partDevice.read(lba = -1L, blockCount = 1, dest = ByteArray(512))
                }
            }
        }
    }

    // ------------------------------------------------------------------------
    // UEFI Mixed-Endian UUID Tests
    // ------------------------------------------------------------------------

    @Test
    fun testUefiMixedEndianGuidConversion() {
        val originalUuid = GptGuidHelper.GUID_EFI_SYSTEM // C12A7328-F81F-11D2-BA4B-00A0C93EC93B
        val bytes = GptGuidHelper.toMixedEndianByteArray(originalUuid)
        assertEquals(16, bytes.size)

        // time_low (C12A7328) in little endian: 28 73 2A C1
        assertEquals(0x28.toByte(), bytes[0])
        assertEquals(0x73.toByte(), bytes[1])
        assertEquals(0x2A.toByte(), bytes[2])
        assertEquals(0xC1.toByte(), bytes[3])

        // time_mid (F81F) in little endian: 1F F8
        assertEquals(0x1F.toByte(), bytes[4])
        assertEquals(0xF8.toByte(), bytes[5])

        // time_hi (11D2) in little endian: D2 11
        assertEquals(0xD2.toByte(), bytes[6])
        assertEquals(0x11.toByte(), bytes[7])

        // clock_seq (BA4B) in big endian: BA 4B
        assertEquals(0xBA.toByte(), bytes[8])
        assertEquals(0x4B.toByte(), bytes[9])

        // Node (00A0C93EC93B) in big endian
        assertEquals(0x00.toByte(), bytes[10])
        assertEquals(0xA0.toByte(), bytes[11])
        assertEquals(0xC9.toByte(), bytes[12])
        assertEquals(0x3E.toByte(), bytes[13])
        assertEquals(0xC9.toByte(), bytes[14])
        assertEquals(0x3B.toByte(), bytes[15])

        // Convert back
        val decodedUuid = GptGuidHelper.fromMixedEndianByteArray(bytes)
        assertEquals(originalUuid, decodedUuid)
    }

    // ------------------------------------------------------------------------
    // Partition Alignment Tests
    // ------------------------------------------------------------------------

    @Test
    fun testPartitionAlignmentCalculation() {
        assertEquals(2048L, PartitionAlignment.alignUp(1L))
        assertEquals(2048L, PartitionAlignment.alignUp(2048L))
        assertEquals(4096L, PartitionAlignment.alignUp(2049L))

        assertEquals(0L, PartitionAlignment.alignDown(100L))
        assertEquals(2048L, PartitionAlignment.alignDown(2048L))
        assertEquals(2048L, PartitionAlignment.alignDown(3000L))

        assertTrue(PartitionAlignment.isAligned(0L))
        assertTrue(PartitionAlignment.isAligned(2048L))
        assertTrue(PartitionAlignment.isAligned(409600L))
        assertFalse(PartitionAlignment.isAligned(2049L))
    }
}
