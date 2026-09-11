package com.example

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.example.block.FaultInjectingBlockDevice
import com.example.block.MemoryBlockDevice
import com.example.dsa.IsoTrieParser
import com.example.flasher.FlashEngineStrategy
import com.example.flasher.checksum.ChecksumValidator
import com.example.flasher.safety.FlashSafetyValidator
import com.example.flasher.strategies.LinuxRawDdStrategy
import com.example.flasher.verification.FlashVerifier
import com.example.usb.UsbDiskInfo
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
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
import java.security.MessageDigest

/**
 * Rigorous Unit Test Suite for Phase 6 Linux Flashing Subsystem.
 *
 * Validates the complete pipeline:
 * ISO -> Validate -> Optional Checksum -> USB Capacity Check -> Safety Warnings ->
 * Raw Write -> Flush -> Genuine Verification -> Success.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LinuxFlashingPipelineTest {

    private lateinit var app: Application
    private lateinit var defaultTargetDrive: UsbDiskInfo
    private lateinit var testIsoFile: File
    private lateinit var testIsoBytes: ByteArray
    private lateinit var testIsoSha256: String

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()

        // Create standard test target drive descriptor (10 MB virtual drive)
        defaultTargetDrive = UsbDiskInfo(
            device = null,
            vendorId = 0x0781,
            productId = 0x5583,
            manufacturerName = "SanDisk",
            productName = "Ultra USB 3.0",
            vendorString = "SanDisk",
            productString = "Ultra",
            revision = "1.00",
            serialNumber = "4C53000123041211",
            totalCapacityBytes = 20480L * 512L, // 10 MB
            totalSectors = 20480L,
            sectorSizeBytes = 512,
            isRemovable = true,
            isWriteProtected = false,
            hasPermission = true
        )

        // Generate synthetic Linux isohybrid test ISO (128 KB)
        val size = 128 * 1024
        testIsoBytes = ByteArray(size) { (it % 251).toByte() }

        // Sector 0: Isohybrid MBR signature (0xAA55) and valid partition 1
        testIsoBytes[510] = 0x55.toByte()
        testIsoBytes[511] = 0xAA.toByte()
        testIsoBytes[446] = 0x80.toByte() // Bootable
        testIsoBytes[446 + 4] = 0x83.toByte() // Linux Native
        ByteBuffer.wrap(testIsoBytes, 446 + 8, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(0)
        ByteBuffer.wrap(testIsoBytes, 446 + 12, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(size / 512)

        // Sector 16: ISO 9660 PVD
        "CD001".toByteArray(Charsets.US_ASCII).copyInto(testIsoBytes, 16 * 2048 + 1)
        val volName = "UBUNTU_24_04_LIVE".padEnd(32, ' ').toByteArray(Charsets.US_ASCII)
        volName.copyInto(testIsoBytes, 16 * 2048 + 40)

        // Calculate expected SHA-256
        val md = MessageDigest.getInstance("SHA-256")
        testIsoSha256 = md.digest(testIsoBytes).joinToString("") { "%02x".format(it) }

        testIsoFile = File(app.cacheDir, "test_linux_pipeline.iso")
        testIsoFile.writeBytes(testIsoBytes)
    }

    @Test
    fun testFullFlashingPipelineSuccessWithVerification() = runTest {
        val strategy = LinuxRawDdStrategy()
        val memoryDevice = MemoryBlockDevice(totalSectors = 20480L, sectorSizeBytes = 512)

        val analysis = IsoTrieParser.parse(
            stream = testIsoFile.inputStream(),
            totalSizeBytes = testIsoBytes.size.toLong(),
            fileName = "ubuntu-24.04-desktop-amd64.iso"
        )

        val logs = mutableListOf<String>()
        var verifiedBytesRecorded = 0L
        var verifyMatching = false

        val result = strategy.execute(
            context = app,
            device = memoryDevice,
            targetDrive = defaultTargetDrive,
            sourceUri = Uri.fromFile(testIsoFile),
            isoAnalysis = analysis,
            config = FlashEngineStrategy.FlashConfig(
                blockSizeBytes = 64 * 1024,
                verifyAfterWrite = true,
                expectedChecksum = testIsoSha256,
                checksumAlgorithm = "SHA-256",
                confirmedByUser = true
            ),
            callback = object : FlashEngineStrategy.ProgressCallback {
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
                override fun onVerificationProgress(verifiedBytes: Long, totalBytes: Long, isMatching: Boolean) {
                    verifiedBytesRecorded = verifiedBytes
                    verifyMatching = isMatching
                }
                override fun onLogMessage(message: String) {
                    logs.add(message)
                }
            },
            isCancelled = { false }
        )

        // Verify successful execution
        assertTrue("Strategy execution must succeed", result.success)
        assertEquals(testIsoBytes.size.toLong(), result.totalBytesWritten)
        assertEquals(testIsoSha256, result.sha256)
        assertTrue("Drive write cache must be flushed", memoryDevice.flushCount > 0)
        assertTrue("Sectors must be written", memoryDevice.writeCount > 0)

        // Verify genuine verification occurred
        assertEquals(testIsoBytes.size.toLong(), verifiedBytesRecorded)
        assertTrue(verifyMatching)
        assertTrue(logs.any { it.contains("Target media verification PASSED") })
        assertTrue(logs.any { it.contains("Source checksum matches expected digest") })

        // Verify media content directly on BlockDevice
        val readBack = ByteArray(testIsoBytes.size)
        memoryDevice.read(0L, testIsoBytes.size / 512, readBack)
        assertArrayEquals("BlockDevice content must match source ISO byte-for-byte", testIsoBytes, readBack)
    }

    @Test
    fun testCapacityValidationFailsWhenDeviceTooSmall() = runTest {
        val strategy = LinuxRawDdStrategy()
        // MemoryBlockDevice with only 64 sectors (32 KB) while ISO is 128 KB
        val smallDevice = MemoryBlockDevice(totalSectors = 64L, sectorSizeBytes = 512)
        val smallDriveInfo = defaultTargetDrive.copy(
            totalCapacityBytes = 64L * 512L,
            totalSectors = 64L
        )

        val analysis = IsoTrieParser.parse(
            stream = testIsoFile.inputStream(),
            totalSizeBytes = testIsoBytes.size.toLong(),
            fileName = "ubuntu.iso"
        )

        val logs = mutableListOf<String>()
        val result = strategy.execute(
            context = app,
            device = smallDevice,
            targetDrive = smallDriveInfo,
            sourceUri = Uri.fromFile(testIsoFile),
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

        assertFalse("Strategy must fail when target drive capacity is insufficient", result.success)
        assertTrue("Error message must indicate insufficient storage capacity", result.errorMessage!!.contains("smaller than ISO image"))
        assertEquals("0 sectors should be written to drive", 0L, smallDevice.writeCount)
        assertTrue(logs.any { it.contains("[SAFETY ERROR]") })
    }

    @Test
    fun testWriteProtectionDetectionAbortsFlashing() = runTest {
        val strategy = LinuxRawDdStrategy()
        val memoryDevice = MemoryBlockDevice(totalSectors = 20480L, sectorSizeBytes = 512)
        val writeProtectedDrive = defaultTargetDrive.copy(isWriteProtected = true)

        val analysis = IsoTrieParser.parse(
            stream = testIsoFile.inputStream(),
            totalSizeBytes = testIsoBytes.size.toLong(),
            fileName = "ubuntu.iso"
        )

        val logs = mutableListOf<String>()
        val result = strategy.execute(
            context = app,
            device = memoryDevice,
            targetDrive = writeProtectedDrive,
            sourceUri = Uri.fromFile(testIsoFile),
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

        assertFalse("Strategy must fail when target drive is write-protected", result.success)
        assertTrue("Error message must mention write-protected", result.errorMessage!!.contains("write-protected"))
        assertEquals("0 sectors should be written to write-protected drive", 0L, memoryDevice.writeCount)
    }

    @Test
    fun testExistingPartitionsWarningGenerated() = runTest {
        val memoryDevice = MemoryBlockDevice(totalSectors = 20480L, sectorSizeBytes = 512)

        // Initialize device with an existing MBR partition table containing FAT32
        val existingSector0 = ByteArray(512)
        existingSector0[510] = 0x55.toByte()
        existingSector0[511] = 0xAA.toByte()
        existingSector0[446 + 4] = 0x0C.toByte() // FAT32 LBA
        ByteBuffer.wrap(existingSector0, 446 + 8, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(2048)
        ByteBuffer.wrap(existingSector0, 446 + 12, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(8192)
        // FAT32 volume string
        "FAT32   ".toByteArray(Charsets.US_ASCII).copyInto(existingSector0, 82)

        memoryDevice.write(0L, 1, existingSector0)

        val inspection = FlashSafetyValidator.inspectExistingMedia(memoryDevice)
        assertTrue("Should detect existing MBR partition table", inspection.hasMbr)
        assertNotNull("Should detect existing FAT32 filesystem", inspection.detectedFilesystem)
        assertTrue("Partition descriptions must not be empty", inspection.partitionDescriptions.isNotEmpty())
        assertTrue("Warning summary should note that data will be overwritten", inspection.summary.contains("permanently overwritten"))
    }

    @Test
    fun testUnconfirmedDestructiveOperationRejected() = runTest {
        val strategy = LinuxRawDdStrategy()
        val memoryDevice = MemoryBlockDevice(totalSectors = 20480L, sectorSizeBytes = 512)

        val analysis = IsoTrieParser.parse(
            stream = testIsoFile.inputStream(),
            totalSizeBytes = testIsoBytes.size.toLong(),
            fileName = "ubuntu.iso"
        )

        val result = strategy.execute(
            context = app,
            device = memoryDevice,
            targetDrive = defaultTargetDrive,
            sourceUri = Uri.fromFile(testIsoFile),
            isoAnalysis = analysis,
            config = FlashEngineStrategy.FlashConfig(confirmedByUser = false), // Not confirmed!
            callback = object : FlashEngineStrategy.ProgressCallback {
                override fun onPartitionProgress(stage: String, progress: Float) {}
                override fun onStreamProgress(writtenBytes: Long, totalBytes: Long, speedMBps: Double, etaSeconds: Long, bufferSaturation: Float, currentLba: Long, chunkIndex: Int, totalChunks: Int) {}
                override fun onVerificationProgress(verifiedBytes: Long, totalBytes: Long, isMatching: Boolean) {}
                override fun onLogMessage(message: String) {}
            },
            isCancelled = { false }
        )

        assertFalse("Strategy must reject unconfirmed destructive operations", result.success)
        assertTrue("Error message must require confirmation", result.errorMessage!!.contains("Destructive operation not confirmed"))
        assertEquals("0 sectors should be written", 0L, memoryDevice.writeCount)
    }

    @Test
    fun testSourceChecksumMismatchAbortsBeforeWrite() = runTest {
        val strategy = LinuxRawDdStrategy()
        val memoryDevice = MemoryBlockDevice(totalSectors = 20480L, sectorSizeBytes = 512)

        val analysis = IsoTrieParser.parse(
            stream = testIsoFile.inputStream(),
            totalSizeBytes = testIsoBytes.size.toLong(),
            fileName = "ubuntu.iso"
        )

        val badChecksum = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

        val result = strategy.execute(
            context = app,
            device = memoryDevice,
            targetDrive = defaultTargetDrive,
            sourceUri = Uri.fromFile(testIsoFile),
            isoAnalysis = analysis,
            config = FlashEngineStrategy.FlashConfig(
                expectedChecksum = badChecksum,
                checksumAlgorithm = "SHA-256",
                confirmedByUser = true
            ),
            callback = object : FlashEngineStrategy.ProgressCallback {
                override fun onPartitionProgress(stage: String, progress: Float) {}
                override fun onStreamProgress(writtenBytes: Long, totalBytes: Long, speedMBps: Double, etaSeconds: Long, bufferSaturation: Float, currentLba: Long, chunkIndex: Int, totalChunks: Int) {}
                override fun onVerificationProgress(verifiedBytes: Long, totalBytes: Long, isMatching: Boolean) {}
                override fun onLogMessage(message: String) {}
            },
            isCancelled = { false }
        )

        assertFalse("Strategy must fail on checksum mismatch", result.success)
        assertTrue("Error message must specify checksum mismatch", result.errorMessage!!.contains("CHECKSUM MISMATCH"))
        assertEquals("0 sectors should be written on checksum mismatch", 0L, memoryDevice.writeCount)
    }

    @Test
    fun testVerificationCatchesCorruptedSectorOnDevice() = runTest {
        val strategy = LinuxRawDdStrategy()
        val rawMemoryDevice = MemoryBlockDevice(totalSectors = 20480L, sectorSizeBytes = 512)
        val faultDevice = FaultInjectingBlockDevice(rawMemoryDevice)

        // Inject corruption: write to LBA 5 will corrupt byte offset 10 to 0xEE
        faultDevice.corruptSectorOnWriteLba = 5L
        faultDevice.corruptWriteByteOffset = 10
        faultDevice.corruptWriteByteValue = 0xEE.toByte()

        val analysis = IsoTrieParser.parse(
            stream = testIsoFile.inputStream(),
            totalSizeBytes = testIsoBytes.size.toLong(),
            fileName = "ubuntu.iso"
        )

        val logs = mutableListOf<String>()
        val result = strategy.execute(
            context = app,
            device = faultDevice,
            targetDrive = defaultTargetDrive,
            sourceUri = Uri.fromFile(testIsoFile),
            isoAnalysis = analysis,
            config = FlashEngineStrategy.FlashConfig(
                verifyAfterWrite = true,
                confirmedByUser = true
            ),
            callback = object : FlashEngineStrategy.ProgressCallback {
                override fun onPartitionProgress(stage: String, progress: Float) {}
                override fun onStreamProgress(writtenBytes: Long, totalBytes: Long, speedMBps: Double, etaSeconds: Long, bufferSaturation: Float, currentLba: Long, chunkIndex: Int, totalChunks: Int) {}
                override fun onVerificationProgress(verifiedBytes: Long, totalBytes: Long, isMatching: Boolean) {}
                override fun onLogMessage(message: String) { logs.add(message) }
            },
            isCancelled = { false }
        )

        // The verification step MUST catch the corrupted sector and fail!
        assertFalse("Flashing must fail when verification detects a corrupted sector", result.success)
        assertNotNull("Error message must not be null", result.errorMessage)
        assertTrue("Error message must report exact LBA 5 mismatch", result.errorMessage!!.contains("LBA 5"))
        assertTrue("Logs must contain verification failed", logs.any { it.contains("[VERIFICATION FAILED]") })
    }

    @Test
    fun testCooperativeCancellationStopsExecution() = runTest {
        val strategy = LinuxRawDdStrategy()
        val memoryDevice = MemoryBlockDevice(totalSectors = 20480L, sectorSizeBytes = 512)

        val analysis = IsoTrieParser.parse(
            stream = testIsoFile.inputStream(),
            totalSizeBytes = testIsoBytes.size.toLong(),
            fileName = "ubuntu.iso"
        )

        var cancelled = false
        val result = strategy.execute(
            context = app,
            device = memoryDevice,
            targetDrive = defaultTargetDrive,
            sourceUri = Uri.fromFile(testIsoFile),
            isoAnalysis = analysis,
            config = FlashEngineStrategy.FlashConfig(confirmedByUser = true),
            callback = object : FlashEngineStrategy.ProgressCallback {
                override fun onPartitionProgress(stage: String, progress: Float) {}
                override fun onStreamProgress(writtenBytes: Long, totalBytes: Long, speedMBps: Double, etaSeconds: Long, bufferSaturation: Float, currentLba: Long, chunkIndex: Int, totalChunks: Int) {
                    // Cancel as soon as first chunk starts streaming
                    cancelled = true
                }
                override fun onVerificationProgress(verifiedBytes: Long, totalBytes: Long, isMatching: Boolean) {}
                override fun onLogMessage(message: String) {}
            },
            isCancelled = { cancelled }
        )

        assertFalse("Cancelled execution must report success = false", result.success)
        assertTrue("Error message should mention cancellation", result.errorMessage!!.contains("cancelled"))
    }
}
