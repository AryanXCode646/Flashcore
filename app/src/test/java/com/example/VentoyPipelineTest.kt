package com.example

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.example.block.MemoryBlockDevice
import com.example.dsa.IsoTrieParser
import com.example.fat32.Fat32Writer
import com.example.flasher.FlashEngineStrategy
import com.example.flasher.strategies.VentoyStrategy
import com.example.flasher.ventoy.DefaultVentoyAssetProvider
import com.example.flasher.ventoy.VentoyDetector
import com.example.flasher.ventoy.VentoyGeometryCalculator
import com.example.flasher.ventoy.VentoyInstallMode
import com.example.flasher.ventoy.VentoyInstaller
import com.example.flasher.ventoy.VentoyLicenseNotice
import com.example.flasher.ventoy.VentoyPartitionStyle
import com.example.flasher.ventoy.VentoyStorageManager
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
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(RobolectricTestRunner::class)
class VentoyPipelineTest {

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun testVentoyGeometryCalculationMbr() {
        val totalSectors = 8_388_608L // 4 GB @ 512 bytes
        val layout = VentoyGeometryCalculator.computeLayout(
            totalDiskSectors = totalSectors,
            style = VentoyPartitionStyle.MBR,
            sectorSizeBytes = 512
        )

        assertEquals(VentoyPartitionStyle.MBR, layout.partitionStyle)
        assertEquals(2048L, layout.part1StartLba)
        assertEquals(65536L, layout.part2SectorCount) // 32 MiB
        assertEquals(8_388_608L - 65536L, layout.part2StartLba)
        assertEquals(layout.part2StartLba - 2048L, layout.part1SectorCount)
        assertEquals(layout.part2StartLba, layout.part1StartLba + layout.part1SectorCount)
        assertEquals(totalSectors - 1L, layout.part2EndLba)
    }

    @Test
    fun testVentoyGeometryCalculationGpt() {
        val totalSectors = 16_777_216L // 8 GB @ 512 bytes
        val layout = VentoyGeometryCalculator.computeLayout(
            totalDiskSectors = totalSectors,
            style = VentoyPartitionStyle.GPT,
            sectorSizeBytes = 512
        )

        assertEquals(VentoyPartitionStyle.GPT, layout.partitionStyle)
        assertEquals(2048L, layout.part1StartLba)
        assertEquals(65536L, layout.part2SectorCount)
        // GPT leaves 34 sectors at the tail for backup GPT
        assertEquals(totalSectors - 65536L - 34L, layout.part2StartLba)
        assertEquals(totalSectors - 1L, layout.backupGptLba)
        assertEquals(layout.part2StartLba - 1L, layout.part1EndLba)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testVentoyGeometryRejectsUndersizedDisk() {
        val smallSectors = 60_000L // ~30 MB (less than 100 MB minimum)
        VentoyGeometryCalculator.computeLayout(smallSectors)
    }

    @Test
    fun testDefaultVentoyAssetProviderGeneratesValidVtoyEfi() = runTest {
        val provider = DefaultVentoyAssetProvider(version = "1.0.99-test")

        val bootstrap = provider.getMbrBootstrap()
        assertEquals(440, bootstrap.size)
        assertEquals(0xEB.toByte(), bootstrap[0])
        assertEquals("VENTOY", String(bootstrap, 3, 6, Charsets.US_ASCII))

        assertEquals(32L * 1024L * 1024L, provider.getVtoyEfiSizeBytes())

        // Read the first 64 KB from the stream to verify FAT VBR
        val stream = provider.getVtoyEfiStream()
        val firstBlock = ByteArray(512)
        val read = stream.read(firstBlock)
        stream.close()

        assertEquals(512, read)
        // Verify boot signature 0x55, 0xAA
        assertEquals(0x55.toByte(), firstBlock[510])
        assertEquals(0xAA.toByte(), firstBlock[511])
    }

    @Test
    fun testVentoyDetectorIdentifiesExistingVentoyMedia() = runTest {
        val totalSectors = 300_000L // ~150 MB
        val memDevice = MemoryBlockDevice(totalSectors = totalSectors)

        val targetDrive = createTargetDrive(totalSectors)

        val installer = VentoyInstaller()
        val installResult = installer.install(
            device = memDevice,
            targetDrive = targetDrive,
            mode = VentoyInstallMode.FRESH_INSTALL,
            callback = NoOpProgressCallback,
            isCancelled = { false }
        )

        assertTrue("Fresh install must succeed", installResult.success)

        // Run detector
        val detected = VentoyDetector.detect(memDevice)
        assertTrue("Ventoy must be detected on formatted device", detected.isVentoyInstalled)
        assertEquals(VentoyPartitionStyle.MBR, detected.partitionStyle)
        assertTrue("Non-destructive update must be permitted", detected.canNonDestructiveUpdate)
        assertNotNull(detected.installedVersion)
        assertTrue(detected.installedVersion!!.contains("1.0.99"))
    }

    @Test
    fun testVentoyStorageManagerStoresAndListsIsoFiles() = runTest {
        val sectors = 150_000L // ~75 MB partition
        val partitionDevice = MemoryBlockDevice(totalSectors = sectors)

        val writer = Fat32Writer.createNew(partitionDevice, totalSectors = sectors, volumeLabel = "Ventoy")
        writer.formatVolume("Ventoy")

        // Store a dummy ISO
        val dummyIsoContent = ByteArray(8192) { (it % 256).toByte() }
        val stream = ByteArrayInputStream(dummyIsoContent)

        VentoyStorageManager.storeIso(
            dataPartitionDevice = partitionDevice,
            isoStream = stream,
            fileName = "ubuntu-24.04-desktop-amd64.iso",
            fileSizeBytes = dummyIsoContent.size.toLong(),
            targetDirectory = "/ISO"
        )

        val images = VentoyStorageManager.listStoredImages(partitionDevice)
        assertEquals(1, images.size)
        assertEquals("ubuntu-24.04-desktop-amd64.iso", images[0].fileName)
        assertEquals("/ISO/ubuntu-24.04-desktop-amd64.iso", images[0].relativePath)
        assertEquals(8192L, images[0].sizeBytes)
    }

    @Test
    fun testVentoyInstallerFreshInstallEndToEnd() = runTest {
        val totalSectors = 350_000L // ~175 MB
        val memDevice = MemoryBlockDevice(totalSectors = totalSectors)

        val targetDrive = createTargetDrive(totalSectors)

        val dummyIsoBytes = "DUMMY_DEBIAN_ISO_CONTENT_12345".toByteArray(Charsets.UTF_8)
        val isoStream = ByteArrayInputStream(dummyIsoBytes)

        val installer = VentoyInstaller()
        val result = installer.install(
            device = memDevice,
            targetDrive = targetDrive,
            mode = VentoyInstallMode.FRESH_INSTALL,
            partitionStyle = VentoyPartitionStyle.MBR,
            initialIsoStream = isoStream,
            initialIsoName = "debian-12.iso",
            initialIsoSize = dummyIsoBytes.size.toLong(),
            callback = NoOpProgressCallback,
            isCancelled = { false }
        )

        assertTrue(result.success)
        assertEquals(1, result.isoFilesStored.size)
        assertEquals("debian-12.iso", result.isoFilesStored[0])

        // Verify MBR Disk Signature
        val sector0 = ByteArray(512)
        memDevice.read(0L, 1, sector0)
        val sig = ByteBuffer.wrap(sector0).order(ByteOrder.LITTLE_ENDIAN).getInt(440)
        assertEquals(VentoyDetector.VENTOY_DISK_SIGNATURE, sig)

        // Verify Partition 1 has the ISO
        val part1Device = PartitionBlockDevice(memDevice, result.layout.part1StartLba, result.layout.part1SectorCount)
        val images = VentoyStorageManager.listStoredImages(part1Device)
        assertEquals(1, images.size)
        assertEquals("debian-12.iso", images[0].fileName)
        assertEquals(dummyIsoBytes.size.toLong(), images[0].sizeBytes)

        // Verify /ventoy/ventoy.json was created
        val p1Writer = Fat32Writer.mount(part1Device)
        assertTrue(p1Writer.exists("/ventoy/ventoy.json"))
    }

    @Test
    fun testVentoyInstallerNonDestructiveUpdatePreservesIsoFiles() = runTest {
        val totalSectors = 350_000L
        val memDevice = MemoryBlockDevice(totalSectors = totalSectors)

        val targetDrive = createTargetDrive(totalSectors)

        // 1. Fresh Install with initial ISO
        val initialIso = "archlinux-2026.iso".toByteArray(Charsets.UTF_8)
        val installerV1 = VentoyInstaller(DefaultVentoyAssetProvider(version = "1.0.98"))

        val freshResult = installerV1.install(
            device = memDevice,
            targetDrive = targetDrive,
            mode = VentoyInstallMode.FRESH_INSTALL,
            initialIsoStream = ByteArrayInputStream(initialIso),
            initialIsoName = "archlinux-2026.iso",
            initialIsoSize = initialIso.size.toLong(),
            callback = NoOpProgressCallback,
            isCancelled = { false }
        )
        assertTrue(freshResult.success)

        // 2. Perform Non-Destructive Update to 1.0.99
        val installerV2 = VentoyInstaller(DefaultVentoyAssetProvider(version = "1.0.99"))
        val updateResult = installerV2.install(
            device = memDevice,
            targetDrive = targetDrive,
            mode = VentoyInstallMode.NON_DESTRUCTIVE_UPDATE,
            callback = NoOpProgressCallback,
            isCancelled = { false }
        )
        assertTrue(updateResult.success)
        assertEquals(VentoyInstallMode.NON_DESTRUCTIVE_UPDATE, updateResult.mode)

        // 3. Verify Version was updated
        val detected = VentoyDetector.detect(memDevice)
        assertEquals("1.0.99", detected.installedVersion)

        // 4. CRITICAL: Verify initial ISO on Partition 1 was NOT destroyed!
        val part1Device = PartitionBlockDevice(memDevice, updateResult.layout.part1StartLba, updateResult.layout.part1SectorCount)
        val images = VentoyStorageManager.listStoredImages(part1Device)
        assertEquals(1, images.size)
        assertEquals("archlinux-2026.iso", images[0].fileName)
    }

    @Test
    fun testVentoyLicenseNoticeAndAttribution() {
        val notice = VentoyLicenseNotice.getFullNotice()
        assertTrue(notice.contains("longpanda"))
        assertTrue(notice.contains("GPL-3.0"))
        assertTrue(notice.contains("https://github.com/ventoy/Ventoy"))
        assertTrue(notice.contains("Flashcore is an independent open-source client"))
    }

    private fun createTargetDrive(totalSectors: Long): UsbDiskInfo {
        return UsbDiskInfo(
            device = null,
            vendorId = 0x0781,
            productId = 0x5583,
            manufacturerName = "SanDisk",
            productName = "Ventoy Test Media",
            vendorString = "SanDisk",
            productString = "Ultra_Fit",
            revision = "1.0",
            serialNumber = "VTOY_TEST_12345",
            totalCapacityBytes = totalSectors * 512L,
            totalSectors = totalSectors,
            sectorSizeBytes = 512,
            isRemovable = true,
            isWriteProtected = false,
            hasPermission = true
        )
    }

    private object NoOpProgressCallback : FlashEngineStrategy.ProgressCallback {
        override fun onPartitionProgress(stage: String, progress: Float) {}
        override fun onStreamProgress(
            writtenBytes: Long,
            totalBytes: Long,
            speedMBps: Double,
            etaSeconds: Long,
            bufferSaturation: Float,
            currentLba: Long,
            chunkIndex: Int,
            totalChunks: Int
        ) {}
        override fun onVerificationProgress(verifiedBytes: Long, totalBytes: Long, isMatching: Boolean) {}
        override fun onLogMessage(message: String) {}
    }
}
