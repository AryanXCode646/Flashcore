package com.example

import com.example.block.MemoryBlockDevice
import com.example.fat32.Fat32Writer
import com.example.iso.ByteArrayIsoSource
import com.example.iso.IsoFilesystemReader
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class IsoFilesystemReaderTest {

    private val sectorSize = 2048

    private val readmeContent = "This is a test ISO image payload for Flashcore.".toByteArray(Charsets.US_ASCII)
    private val efiContent = "MZ\u0090\u0000Mock UEFI BOOTX64 Binary Data".toByteArray(Charsets.ISO_8859_1)

    /**
     * Synthesizes a valid minimal ISO 9660 image in memory containing:
     * - /README.TXT
     * - /EFI/BOOTX64.EFI
     */
    private fun createSyntheticIso(): ByteArray {
        val totalSectors = 25
        val image = ByteArray(totalSectors * sectorSize)

        // Sector 16: Primary Volume Descriptor (PVD)
        val pvdOffset = 16 * sectorSize
        val pvd = ByteBuffer.wrap(image, pvdOffset, sectorSize).order(ByteOrder.LITTLE_ENDIAN)
        pvd.put(1.toByte()) // Type 1: PVD
        pvd.put("CD001".toByteArray(Charsets.US_ASCII)) // Magic
        pvd.put(1.toByte()) // Version

        // System ID & Volume ID
        image.fill(0x20.toByte(), pvdOffset + 8, pvdOffset + 40)
        image.fill(0x20.toByte(), pvdOffset + 40, pvdOffset + 72)
        val volName = "TEST_ISO".toByteArray(Charsets.US_ASCII)
        System.arraycopy(volName, 0, image, pvdOffset + 40, volName.size)

        // Volume space size (both endian, 25 sectors)
        pvd.position(80)
        pvd.putInt(25) // Little endian
        pvd.order(ByteOrder.BIG_ENDIAN)
        pvd.putInt(25) // Big endian
        pvd.order(ByteOrder.LITTLE_ENDIAN)

        // Root Directory Record at PVD offset 156 (34 bytes)
        val rootRecOffset = pvdOffset + 156
        writeDirectoryRecord(
            dest = image,
            offset = rootRecOffset,
            extentLba = 17L,
            dataLength = sectorSize.toLong(),
            isDirectory = true,
            name = "\u0000" // Root
        )

        // Sector 17: Root Directory Extent
        val rootSecOffset = 17 * sectorSize
        var rootPos = rootSecOffset

        // Root '.' entry
        rootPos += writeDirectoryRecord(image, rootPos, 17L, sectorSize.toLong(), true, "\u0000")
        // Root '..' entry
        rootPos += writeDirectoryRecord(image, rootPos, 17L, sectorSize.toLong(), true, "\u0001")
        // Directory 'EFI' -> LBA 18
        rootPos += writeDirectoryRecord(image, rootPos, 18L, sectorSize.toLong(), true, "EFI")
        // File 'README.TXT;1' -> LBA 19
        rootPos += writeDirectoryRecord(image, rootPos, 19L, readmeContent.size.toLong(), false, "README.TXT;1")

        // Sector 18: EFI Directory Extent
        val efiSecOffset = 18 * sectorSize
        var efiPos = efiSecOffset
        efiPos += writeDirectoryRecord(image, efiPos, 18L, sectorSize.toLong(), true, "\u0000")
        efiPos += writeDirectoryRecord(image, efiPos, 17L, sectorSize.toLong(), true, "\u0001")
        // File 'BOOTX64.EFI;1' -> LBA 20
        efiPos += writeDirectoryRecord(image, efiPos, 20L, efiContent.size.toLong(), false, "BOOTX64.EFI;1")

        // Sector 19: README.TXT payload
        val file1Offset = 19 * sectorSize
        System.arraycopy(readmeContent, 0, image, file1Offset, readmeContent.size)

        // Sector 20: BOOTX64.EFI payload
        val file2Offset = 20 * sectorSize
        System.arraycopy(efiContent, 0, image, file2Offset, efiContent.size)

        return image
    }

    private fun writeDirectoryRecord(
        dest: ByteArray,
        offset: Int,
        extentLba: Long,
        dataLength: Long,
        isDirectory: Boolean,
        name: String
    ): Int {
        val nameBytes = name.toByteArray(Charsets.US_ASCII)
        val recordLen = (33 + nameBytes.size + 1) and 0xFE.inv().inv() // Even length
        val actualLen = if (recordLen % 2 != 0) recordLen + 1 else recordLen

        val buf = ByteBuffer.wrap(dest, offset, actualLen).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(actualLen.toByte()) // Record length
        buf.put(0.toByte()) // Extended attribute record length

        // Location of extent (both endian)
        buf.putInt(extentLba.toInt())
        buf.order(ByteOrder.BIG_ENDIAN)
        buf.putInt(extentLba.toInt())
        buf.order(ByteOrder.LITTLE_ENDIAN)

        // Data length (both endian)
        buf.putInt(dataLength.toInt())
        buf.order(ByteOrder.BIG_ENDIAN)
        buf.putInt(dataLength.toInt())
        buf.order(ByteOrder.LITTLE_ENDIAN)

        // Date/Time (7 bytes)
        for (i in 0 until 7) buf.put(0.toByte())

        // Flags
        val flags = if (isDirectory) 0x02 else 0x00
        buf.put(flags.toByte())

        buf.put(0.toByte()) // File unit size
        buf.put(0.toByte()) // Interleave gap size
        buf.putShort(1.toShort()) // Volume sequence number LE
        buf.putShort(1.toShort()) // BE

        // Name length
        buf.put(nameBytes.size.toByte())
        buf.put(nameBytes)

        return actualLen
    }

    @Test
    fun testParseIsoAndExtractToFat32() {
        runBlocking {
            val isoBytes = createSyntheticIso()
            val isoSource = ByteArrayIsoSource(isoBytes)
            val reader = IsoFilesystemReader(isoSource)

            val opened = reader.open()
            assertTrue("ISO open should succeed", opened)
            assertEquals("TEST_ISO", reader.volumeLabel)

            val entryPaths = reader.entries.map { it.path }
            assertTrue("Should discover /EFI", entryPaths.contains("/EFI"))
            assertTrue("Should discover /README.TXT", entryPaths.contains("/README.TXT"))
            assertTrue("Should discover /EFI/BOOTX64.EFI", entryPaths.contains("/EFI/BOOTX64.EFI"))

            // Verify streaming read directly from ISO reader
            val readmeEntry = reader.entries.first { it.path == "/README.TXT" }
            val readStream = reader.openStream(readmeEntry)
            val readmeRead = readStream.readBytes()
            assertEquals("This is a test ISO image payload for Flashcore.", String(readmeRead, Charsets.US_ASCII))

            // Now test full pipeline: ISO -> IsoFilesystemReader -> Fat32Writer -> BlockDevice!
            val targetDisk = MemoryBlockDevice(totalSectors = 204800L, sectorSizeBytes = 512)
            val fat32Writer = Fat32Writer.createNew(targetDisk, 204800L, "UEFI_DISK")
            fat32Writer.formatVolume("UEFI_DISK")

            var filesCopied = 0
            reader.extractTo(fat32Writer) { path, copied, total ->
                if (copied == total) filesCopied++
            }

            // Verify all files and directories exist in FAT32
            assertTrue(fat32Writer.exists("/EFI"))
            assertTrue(fat32Writer.exists("/README.TXT"))
            assertTrue(fat32Writer.exists("/EFI/BOOTX64.EFI"))

            // Verify byte content read back from FAT32
            val fat32Readme = fat32Writer.readFile("/README.TXT")
            assertEquals("This is a test ISO image payload for Flashcore.", String(fat32Readme, Charsets.US_ASCII))

            val fat32Efi = fat32Writer.readFile("/EFI/BOOTX64.EFI")
            assertArrayEquals(efiContent, fat32Efi)
        }
    }
}
