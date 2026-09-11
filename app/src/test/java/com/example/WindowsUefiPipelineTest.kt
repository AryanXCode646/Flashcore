package com.example

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.example.block.MemoryBlockDevice
import com.example.dsa.IsoTrieParser
import com.example.dsa.WimChunker
import com.example.fat32.Fat32Writer
import com.example.flasher.FlashEngineStrategy
import com.example.flasher.strategies.WindowsUefiStrategy
import com.example.flasher.windows.WindowsArchitecture
import com.example.flasher.windows.WindowsBootFilesManager
import com.example.flasher.windows.WindowsCapabilities
import com.example.flasher.windows.WindowsCapacityAnalyzer
import com.example.flasher.windows.WindowsCapabilityDetector
import com.example.flasher.windows.WindowsInstallerType
import com.example.iso.ByteArrayIsoSource
import com.example.iso.IsoFilesystemReader
import com.example.partition.GptGuidHelper
import com.example.partition.PartitionBlockDevice
import com.example.usb.UsbDiskInfo
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Rigorous Unit Test Suite for Phase 7 Windows UEFI Flashing Engine.
 *
 * Validates:
 * 1. Dynamic capability detection (x64, ARM64, Dual-arch, WIM vs ESD vs WinPE)
 * 2. FAT32 capacity and geometry sizing (cluster slack, FAT table mirroring overhead)
 * 3. WIM splitting into SWM parts
 * 4. Boot files provisioning (/efi/boot/bootx64.efi and /efi/microsoft/boot/bcd)
 * 5. Binary signature verification (MZ header for PE loaders and regf for BCD hives)
 * 6. End-to-end Windows UEFI flash pipeline (GPT + FAT32 + extraction + verification)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WindowsUefiPipelineTest {

    private lateinit var app: Application

    // Standard MZ PE header for EFI executables
    private val dummyEfiExecutable = byteArrayOf(
        0x4D.toByte(), 0x5A.toByte(), // "MZ"
        0x90.toByte(), 0x00.toByte(), 0x03.toByte(), 0x00.toByte()
    ) + ByteArray(1024)

    // Standard regf header for Windows Registry Hive (BCD)
    private val dummyBcdHive = byteArrayOf(
        0x72.toByte(), 0x65.toByte(), 0x67.toByte(), 0x66.toByte() // "regf"
    ) + ByteArray(1024)

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun testWindows11X64IsoCapabilityDetection() {
        val isoBytes = SyntheticIsoBuilder("WIN11_23H2_ENGLISH_X64", isIsohybrid = false, hasElToritoBios = false, hasElToritoEfi = true)
            .addFile("/bootmgr", "bootmgr_binary".toByteArray())
            .addFile("/bootmgr.efi", dummyEfiExecutable)
            .addFile("/boot/bcd", dummyBcdHive)
            .addFile("/efi/boot/bootx64.efi", dummyEfiExecutable)
            .addFile("/sources/boot.wim", "winpe_boot_wim".toByteArray(), simulatedSize = 450L * 1024L * 1024L)
            .addFile("/sources/install.wim", "large_wim_payload".toByteArray(), simulatedSize = 5500000000L)
            .build()

        val reader = IsoFilesystemReader(ByteArrayIsoSource(isoBytes))
        assertTrue(reader.open())

        val caps = WindowsCapabilityDetector.detect(reader)
        assertEquals("WIN11_23H2_ENGLISH_X64", caps.volumeLabel)
        assertEquals(WindowsArchitecture.X64, caps.arch)
        assertEquals(WindowsInstallerType.WIM, caps.installerType)
        assertEquals("/sources/install.wim", caps.installImagePath)
        assertEquals(5500000000L, caps.installImageSizeBytes)
        assertTrue("WIM > 4GB must trigger requiresWimSplit = true", caps.requiresWimSplit)
        assertEquals("5.5 GB WIM should plan 2 SWM parts", 2, caps.splitPartCount)
        assertTrue(caps.hasUefiBoot)
        assertEquals("/efi/boot/bootx64.efi", caps.uefiLoaderPath)
        assertTrue(caps.hasLegacyBiosBoot)
        assertEquals("Windows 11", caps.editionHint)
        assertFalse(caps.isMultiArch)
    }

    @Test
    fun testWindowsArm64IsoCapabilityDetection() {
        val isoBytes = SyntheticIsoBuilder("WIN11_ARM64_PRO", isIsohybrid = false, hasElToritoBios = false, hasElToritoEfi = true)
            .addFile("/bootmgr.efi", dummyEfiExecutable)
            .addFile("/efi/microsoft/boot/bcd", dummyBcdHive)
            .addFile("/efi/boot/bootaa64.efi", dummyEfiExecutable)
            .addFile("/sources/install.esd", "compressed_esd_bytes".toByteArray(), simulatedSize = 3200000000L)
            .build()

        val reader = IsoFilesystemReader(ByteArrayIsoSource(isoBytes))
        assertTrue(reader.open())

        val caps = WindowsCapabilityDetector.detect(reader)
        assertEquals(WindowsArchitecture.ARM64, caps.arch)
        assertEquals("bootaa64.efi", caps.arch.efiFileName)
        assertEquals(WindowsInstallerType.ESD, caps.installerType)
        assertEquals("/sources/install.esd", caps.installImagePath)
        assertEquals(3200000000L, caps.installImageSizeBytes)
        assertFalse("3.2 GB ESD fits in FAT32 without splitting", caps.requiresWimSplit)
        assertEquals(1, caps.splitPartCount)
        assertTrue(caps.hasUefiBoot)
        assertEquals("/efi/boot/bootaa64.efi", caps.uefiLoaderPath)
    }

    @Test
    fun testWindowsDualArchIsoCapabilityDetection() {
        val isoBytes = SyntheticIsoBuilder("WIN10_MULTI_ARCH", isIsohybrid = false, hasElToritoBios = false, hasElToritoEfi = true)
            .addFile("/bootmgr.efi", dummyEfiExecutable)
            .addFile("/efi/boot/bootx64.efi", dummyEfiExecutable)
            .addFile("/efi/boot/bootia32.efi", dummyEfiExecutable)
            .addFile("/x64/sources/install.wim", "x64_wim".toByteArray(), simulatedSize = 3800000000L)
            .addFile("/x86/sources/install.wim", "x86_wim".toByteArray(), simulatedSize = 2900000000L)
            .build()

        val reader = IsoFilesystemReader(ByteArrayIsoSource(isoBytes))
        assertTrue(reader.open())

        val caps = WindowsCapabilityDetector.detect(reader)
        assertEquals(WindowsArchitecture.MULTI_ARCH, caps.arch)
        assertTrue(caps.isMultiArch)
        assertEquals(WindowsInstallerType.WIM, caps.installerType)
    }

    @Test
    fun testWindowsCapacityAnalyzerCalculatesGeometryAndHeadroom() {
        val isoBytes = SyntheticIsoBuilder("WIN_TEST")
            .addFile("/bootmgr.efi", dummyEfiExecutable)
            .addFile("/sources/install.wim", "wim".toByteArray(), simulatedSize = 5000000000L)
            .build()

        val reader = IsoFilesystemReader(ByteArrayIsoSource(isoBytes))
        assertTrue(reader.open())
        val caps = WindowsCapabilityDetector.detect(reader)

        val memoryDevice = MemoryBlockDevice(totalSectors = 20971520L, sectorSizeBytes = 512) // 10 GB
        val analysis = WindowsCapacityAnalyzer.analyze(
            device = memoryDevice,
            capabilities = caps,
            reader = reader,
            targetTotalSectors = 20971520L
        )

        assertTrue("10 GB disk must be sufficient for 5 GB payload", analysis.isSufficient)
        assertEquals(0L, analysis.deficitBytes)
        assertEquals(2048L, analysis.partitionFirstLba)
        assertTrue(analysis.totalRequiredBytes > 5000000000L)
        assertTrue(analysis.filesystemOverheadBytes > 0L)

        // Test with undersized target disk (4 GB disk for 5 GB payload)
        val smallAnalysis = WindowsCapacityAnalyzer.analyze(
            device = memoryDevice,
            capabilities = caps,
            reader = reader,
            targetTotalSectors = 8388608L // 4 GB
        )
        assertFalse("4 GB disk must not be sufficient for 5 GB payload", smallAnalysis.isSufficient)
        assertTrue("Deficit must be positive", smallAnalysis.deficitBytes > 0L)
    }

    @Test
    fun testWimChunkerSwmPlanningAndHeaderGeneration() {
        val largeSize = 5500000000L // 5.5 GB
        val plan = WimChunker.planSwmSplit(largeSize)

        assertEquals(2, plan.size)
        assertEquals("install.swm", plan[0].fileName)
        assertEquals(1, plan[0].partIndex)
        assertEquals(2, plan[0].totalParts)
        assertEquals(0L, plan[0].startOffset)
        assertEquals(3900L * 1024L * 1024L, plan[0].lengthBytes)

        assertEquals("install2.swm", plan[1].fileName)
        assertEquals(2, plan[1].partIndex)
        assertEquals(2, plan[1].totalParts)
        assertEquals(3900L * 1024L * 1024L, plan[1].startOffset)
        assertEquals(largeSize - (3900L * 1024L * 1024L), plan[1].lengthBytes)

        // Test SWM Header generation
        val originalHeader = WimChunker.WimHeader(
            imageTag = "MSWIM\u0000\u0000\u0000",
            cbSize = 208,
            dwVersion = 0x00010d00,
            flags = WimChunker.FLAG_HEADER_COMPRESSION or WimChunker.FLAG_HEADER_COMPRESS_LZX,
            compressionChunkSize = 32768,
            guid = ByteArray(16) { it.toByte() },
            partNumber = 1,
            totalParts = 1,
            imageCount = 1,
            offsetTableOffset = 1000L,
            offsetTableSize = 200L,
            xmlDataOffset = 1200L,
            xmlDataSize = 300L,
            bootIndex = 1
        )

        val swmHeaderBytes = WimChunker.createSwmHeader(originalHeader, partNumber = 2, totalParts = 2)
        val buf = ByteBuffer.wrap(swmHeaderBytes).order(ByteOrder.LITTLE_ENDIAN)
        val tagBytes = ByteArray(8)
        buf.get(tagBytes)
        assertEquals("MSWIM\u0000\u0000\u0000", String(tagBytes, Charsets.US_ASCII))
        buf.getInt() // cbSize
        buf.getInt() // dwVersion
        val flags = buf.getInt()
        assertTrue("SWM header must have FLAG_HEADER_SPANNED set", (flags and WimChunker.FLAG_HEADER_SPANNED) != 0)
        buf.getInt() // compChunkSize
        buf.position(buf.position() + 16) // guid
        val partNum = buf.getShort()
        val totalParts = buf.getShort()
        assertEquals(2.toShort(), partNum)
        assertEquals(2.toShort(), totalParts)
    }

    @Test
    fun testWindowsBootFilesManagerProvisionsMissingEfiLoader() = runTest {
        val memoryDevice = MemoryBlockDevice(totalSectors = 40960L, sectorSizeBytes = 512)
        val writer = Fat32Writer.createNew(memoryDevice, 40960L, "TEST_BOOT")
        writer.formatVolume("TEST_BOOT")

        // Write bootmgr.efi and /boot/bcd, but intentionally omit /efi/boot/bootx64.efi and /efi/microsoft/boot/bcd
        writer.writeFile("/bootmgr.efi", dummyEfiExecutable)
        writer.mkdir("/boot")
        writer.writeFile("/boot/bcd", dummyBcdHive)

        val caps = WindowsCapabilities(
            volumeLabel = "WIN_TEST",
            arch = WindowsArchitecture.X64,
            installerType = WindowsInstallerType.WIM,
            installImagePath = "/sources/install.wim",
            installImageSizeBytes = 1000L,
            requiresWimSplit = false,
            splitPartCount = 1,
            hasUefiBoot = true,
            uefiLoaderPath = "/bootmgr.efi",
            hasLegacyBiosBoot = true,
            legacyBootMgrPath = null,
            bcdPath = "/boot/bcd",
            editionHint = "Windows 11",
            isMultiArch = false,
            totalPayloadBytes = 2000L,
            fileCount = 2,
            dirCount = 1
        )

        // Run provisioner
        val actions = WindowsBootFilesManager.resolveAndEnsureBootFiles(writer, caps)
        assertTrue("Should have provisioned bootloader and BCD", actions.isNotEmpty())

        // Confirm both default UEFI paths now exist on the FAT32 volume!
        assertTrue("Standard UEFI loader must now exist", writer.exists("/efi/boot/bootx64.efi"))
        assertTrue("Standard UEFI BCD hive must now exist", writer.exists("/efi/microsoft/boot/bcd"))

        // Verify signatures
        val verifyResult = WindowsBootFilesManager.verifyBootIntegrity(writer, caps)
        assertTrue(verifyResult.isBootable)
        assertTrue(verifyResult.hasValidPeSignature)
        assertTrue(verifyResult.hasValidBcdSignature)
    }

    @Test
    fun testWindowsBootFilesManagerDetectsCorruptedBinaryHeaders() = runTest {
        val memoryDevice = MemoryBlockDevice(totalSectors = 40960L, sectorSizeBytes = 512)
        val writer = Fat32Writer.createNew(memoryDevice, 40960L, "CORRUPT")
        writer.formatVolume("CORRUPT")

        writer.mkdir("/efi")
        writer.mkdir("/efi/boot")
        writer.mkdir("/efi/microsoft")
        writer.mkdir("/efi/microsoft/boot")

        // Write corrupted non-MZ executable
        val corruptedExecutable = byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x00)
        writer.writeFile("/efi/boot/bootx64.efi", corruptedExecutable)
        writer.writeFile("/efi/microsoft/boot/bcd", dummyBcdHive)

        val caps = WindowsCapabilities(
            volumeLabel = "TEST",
            arch = WindowsArchitecture.X64,
            installerType = WindowsInstallerType.WIM,
            installImagePath = null,
            installImageSizeBytes = 0L,
            requiresWimSplit = false,
            splitPartCount = 1,
            hasUefiBoot = true,
            uefiLoaderPath = "/efi/boot/bootx64.efi",
            hasLegacyBiosBoot = false,
            legacyBootMgrPath = null,
            bcdPath = "/efi/microsoft/boot/bcd",
            editionHint = "Windows 11",
            isMultiArch = false,
            totalPayloadBytes = 1000L,
            fileCount = 2,
            dirCount = 4
        )

        val badPeResult = WindowsBootFilesManager.verifyBootIntegrity(writer, caps)
        assertFalse("Corrupted PE header must fail verification", badPeResult.isBootable)
        assertFalse(badPeResult.hasValidPeSignature)
        assertTrue(badPeResult.errorMessage!!.contains("expected MZ signature"))

        // Fix PE header, corrupt BCD hive
        writer.writeFile("/efi/boot/bootx64.efi", dummyEfiExecutable)
        val corruptedBcd = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())
        writer.writeFile("/efi/microsoft/boot/bcd", corruptedBcd)

        val badBcdResult = WindowsBootFilesManager.verifyBootIntegrity(writer, caps)
        assertFalse("Corrupted BCD hive must fail verification", badBcdResult.isBootable)
        assertFalse(badBcdResult.hasValidBcdSignature)
        assertTrue(badBcdResult.errorMessage!!.contains("expected regf signature"))
    }

    @Test
    fun testEndToEndWindowsUefiFlashingPipeline() = runTest {
        val strategy = WindowsUefiStrategy()
        // 50 MB in-memory block device (102400 sectors of 512 bytes)
        val memoryDevice = MemoryBlockDevice(totalSectors = 102400L, sectorSizeBytes = 512)

        val targetDrive = UsbDiskInfo(
            device = null,
            vendorId = 0x0781,
            productId = 0x5583,
            manufacturerName = "SanDisk",
            productName = "Ultra Fit",
            vendorString = "SanDisk",
            productString = "Ultra_Fit",
            revision = "1.0",
            serialNumber = "SANDISK_WIN_001",
            totalCapacityBytes = 102400L * 512L,
            totalSectors = 102400L,
            sectorSizeBytes = 512,
            isRemovable = true,
            isWriteProtected = false,
            hasPermission = true
        )

        // Create synthetic Windows 11 installation ISO with real MZ & regf binary structures
        val isoBytes = SyntheticIsoBuilder("WIN11_TEST", isIsohybrid = false, hasElToritoBios = false, hasElToritoEfi = true)
            .addFile("/bootmgr", "bootmgr_code".toByteArray())
            .addFile("/bootmgr.efi", dummyEfiExecutable)
            .addFile("/boot/bcd", dummyBcdHive)
            .addFile("/efi/boot/bootx64.efi", dummyEfiExecutable)
            .addFile("/sources/boot.wim", "boot_wim_pe".toByteArray())
            .addFile("/sources/install.wim", "install_wim_payload_bytes".toByteArray())
            .build()

        val isoFile = File(app.cacheDir, "win11_test_image.iso")
        isoFile.writeBytes(isoBytes)
        val isoUri = Uri.fromFile(isoFile)

        val analysis = IsoTrieParser.parse(
            stream = isoFile.inputStream(),
            totalSizeBytes = isoBytes.size.toLong(),
            fileName = "win11_test_image.iso"
        )

        val logs = mutableListOf<String>()
        val result = strategy.execute(
            context = app,
            device = memoryDevice,
            targetDrive = targetDrive,
            sourceUri = isoUri,
            isoAnalysis = analysis,
            config = FlashEngineStrategy.FlashConfig(confirmedByUser = true),
            callback = object : FlashEngineStrategy.ProgressCallback {
                override fun onPartitionProgress(stage: String, progress: Float) {}
                override fun onStreamProgress(writtenBytes: Long, totalBytes: Long, speedMBps: Double, etaSeconds: Long, bufferSaturation: Float, currentLba: Long, chunkIndex: Int, totalChunks: Int) {}
                override fun onVerificationProgress(verifiedBytes: Long, totalBytes: Long, isMatching: Boolean) {}
                override fun onLogMessage(message: String) { logs.add(message) }
            },
            isCancelled = { false }
        )

        assertTrue("Windows UEFI strategy flash must succeed: ${result.errorMessage}", result.success)
        assertTrue("Drive write cache flush must be called", memoryDevice.flushCount > 0)
        assertTrue(logs.any { it.contains("STARTING WINDOWS UEFI FLASH PIPELINE") })
        assertTrue(logs.any { it.contains("Validating UEFI binary headers and BCD registry hives") })
        assertTrue(logs.any { it.contains("Media is ready for native UEFI installation") })

        // 1. Verify Protective MBR at Sector 0
        val sector0 = ByteArray(512)
        memoryDevice.read(0L, 1, sector0)
        assertEquals(0x55.toByte(), sector0[510])
        assertEquals(0xAA.toByte(), sector0[511])
        assertEquals(0xEE.toByte(), sector0[446 + 4]) // GPT protective type

        // 2. Verify GPT Primary Header at Sector 1
        val sector1 = ByteArray(512)
        memoryDevice.read(1L, 1, sector1)
        val gptSig = String(sector1, 0, 8, Charsets.US_ASCII)
        assertEquals("EFI PART", gptSig)

        // 3. Mount and verify target FAT32 volume contents directly
        val fat32StartLba = 2048L
        val fat32Sectors = 102400L - 2048L - 2048L + 1L
        val partitionDevice = PartitionBlockDevice(memoryDevice, fat32StartLba, fat32Sectors)
        val fat32Reader = Fat32Writer.mount(partitionDevice)

        assertTrue("Target volume must have /efi/boot/bootx64.efi", fat32Reader.exists("/efi/boot/bootx64.efi"))
        assertTrue("Target volume must have /sources/install.wim", fat32Reader.exists("/sources/install.wim"))
        assertTrue("Target volume must have /sources/boot.wim", fat32Reader.exists("/sources/boot.wim"))
        assertTrue("Target volume must have /bootmgr.efi", fat32Reader.exists("/bootmgr.efi"))

        // 4. Verify binary signature on extracted bootloader
        val readEfi = fat32Reader.readFile("/efi/boot/bootx64.efi")
        assertNotNull(readEfi)
        assertEquals(0x4D.toByte(), readEfi!![0])
        assertEquals(0x5A.toByte(), readEfi[1])
    }
}
