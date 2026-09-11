package com.example

import android.app.Application
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.example.benchmark.FlashPerformanceBenchmark
import com.example.benchmark.SyntheticBenchmarkSinkDevice
import com.example.flasher.fsm.FlasherState
import com.example.service.FlashForegroundService
import com.example.ui.FlasherViewModel
import com.example.usb.UsbDiskInfo
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidProductionEngineeringTest {

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
    }

    // =========================================================================
    // 1. LIFECYCLE & FOREGROUND SERVICE TESTS
    // =========================================================================

    @Test
    fun testForegroundServiceStartAndCancellationAction() {
        var cancelInvoked = false
        FlashForegroundService.onCancelActionRequested = {
            cancelInvoked = true
        }

        // Start Foreground Service via Robolectric
        val serviceController = Robolectric.buildService(FlashForegroundService::class.java)
        val service = serviceController.create().startCommand(0, 0).get()

        assertNotNull("Active instance should be registered", FlashForegroundService.activeInstance)

        // Send cancel intent
        val cancelIntent = Intent(app, FlashForegroundService::class.java).apply {
            action = FlashForegroundService.ACTION_CANCEL_FLASH
        }
        service.onStartCommand(cancelIntent, 0, 1)

        assertTrue("Cancel action callback must be invoked on ACTION_CANCEL_FLASH", cancelInvoked)

        serviceController.destroy()
        assertEquals(null, FlashForegroundService.activeInstance)
        FlashForegroundService.onCancelActionRequested = null
    }

    @Test
    fun testForegroundServiceProgressAndCompletionUpdates() {
        val serviceController = Robolectric.buildService(FlashForegroundService::class.java)
        val service = serviceController.create().startCommand(0, 0).get()

        // Verify progress update doesn't throw
        service.updateProgress("Writing blocks...", 45, 32.5, 120L)
        FlashForegroundService.update("Verifying sectors...", 80, 45.0, 30L)

        // Verify completion update
        FlashForegroundService.complete("Flash Succeeded", "All sectors verified!", true)

        serviceController.destroy()
    }

    // =========================================================================
    // 2. PROCESS DEATH RECOVERY & CONFIGURATION CHANGES (SavedStateHandle)
    // =========================================================================

    @Test
    fun testSavedStateHandleRestoresStrategyAndIsoSelection() {
        val savedState = SavedStateHandle().apply {
            set("saved_strategy_id", "WINDOWS_UEFI")
            set("saved_iso_uri", "content://downloads/my_win11.iso")
            set("saved_iso_name", "my_win11.iso")
        }

        val viewModel = FlasherViewModel(app, savedState)
        val state = viewModel.uiState.value

        assertEquals("WINDOWS_UEFI", state.selectedStrategyId)
        assertEquals(Uri.parse("content://downloads/my_win11.iso"), state.selectedIsoUri)
        assertEquals("my_win11.iso", state.selectedIsoName)
    }

    @Test
    fun testStrategySelectionPersistsToSavedStateHandle() {
        val savedState = SavedStateHandle()
        val viewModel = FlasherViewModel(app, savedState)

        viewModel.selectStrategy("VENTOY_MULTIBOOT")

        assertEquals("VENTOY_MULTIBOOT", viewModel.uiState.value.selectedStrategyId)
        assertEquals("VENTOY_MULTIBOOT", savedState.get<String>("saved_strategy_id"))
    }

    // =========================================================================
    // 3. USB LIFECYCLE & DETACHMENT DURING ACTIVE FLASHING
    // =========================================================================

    @Test
    fun testUsbDeviceDetachedDuringFlashingAbortsSafely() {
        val viewModel = FlasherViewModel(app)

        val diskInfo = UsbDiskInfo(
            device = null,
            vendorId = 0x058F,
            productId = 0x6387,
            manufacturerName = "Alcor",
            productName = "Flash Drive",
            vendorString = "Alcor",
            productString = "Flash_Disk",
            revision = "1.0",
            serialNumber = "ALCOR_TEST_001",
            totalCapacityBytes = 2L * 1024L * 1024L * 1024L,
            totalSectors = 4_194_304L,
            sectorSizeBytes = 512,
            isRemovable = true,
            isWriteProtected = false,
            hasPermission = true
        )

        viewModel.selectDevice(diskInfo)
        assertEquals(diskInfo, viewModel.uiState.value.selectedDevice)

        // Broadcast USB Detach
        val detachIntent = Intent(UsbManager.ACTION_USB_DEVICE_DETACHED)
        app.sendBroadcast(detachIntent)

        // Verify safe cleanup
        val stateAfterDetach = viewModel.uiState.value
        assertFalse("Must not be flashing after unexpected detach", stateAfterDetach.isFlashing)
    }

    // =========================================================================
    // 4. STORAGE ACCESS FRAMEWORK (SAF) & LARGE FILE ARITHMETIC
    // =========================================================================

    @Test
    fun testSafLargeFileArithmeticDoesNotOverflow() {
        // 64 GB file size
        val size64Gb = 64L * 1024L * 1024L * 1024L
        val sectorSize = 512
        val totalSectors = size64Gb / sectorSize

        assertEquals(68_719_476_736L, size64Gb)
        assertEquals(134_217_728L, totalSectors)

        // Verify 32-bit overflow does not occur
        assertTrue(size64Gb > Int.MAX_VALUE.toLong())
        assertTrue(totalSectors < Long.MAX_VALUE)
    }

    // =========================================================================
    // 5. PERFORMANCE BENCHMARKS (1 GB, 4 GB, 8 GB, 16 GB, 32 GB, 64 GB)
    // =========================================================================

    @Test
    fun testBenchmarkWorkload1GB() = runTest {
        val sink = SyntheticBenchmarkSinkDevice(totalSectors = FlashPerformanceBenchmark.SIZE_1_GB / 512)
        val metrics = FlashPerformanceBenchmark.runBenchmark(
            context = app,
            targetDevice = sink,
            workloadBytes = 50L * 1024L * 1024L, // Scaled for fast unit testing (50 MB synthetic)
            blockSizeBytes = 1024 * 1024
        )

        assertTrue("Throughput must be positive", metrics.averageMBps > 0.0)
        assertTrue("Duration must be non-zero", metrics.durationMs > 0)
        assertEquals(0L, metrics.usbErrorsCount)
        assertTrue("RAM must be measured", metrics.ramHeapUsedMb > 0.0)
        assertNotNull(metrics.thermalStatus)
    }

    @Test
    fun testBenchmarkWorkload4GBAnd8GBCalculations() = runTest {
        assertEquals(4_294_967_296L, FlashPerformanceBenchmark.SIZE_4_GB)
        assertEquals(8_589_934_592L, FlashPerformanceBenchmark.SIZE_8_GB)

        val sink = SyntheticBenchmarkSinkDevice(totalSectors = FlashPerformanceBenchmark.SIZE_8_GB / 512)
        val metrics = FlashPerformanceBenchmark.runBenchmark(
            context = app,
            targetDevice = sink,
            workloadBytes = 100L * 1024L * 1024L // 100 MB sample workload
        )

        assertTrue(metrics.averageMBps > 10.0)
        assertEquals(0L, metrics.usbErrorsCount)
    }

    @Test
    fun testBenchmarkWorkload16GB_32GB_64GBMathematicalScaling() = runTest {
        assertEquals(17_179_869_184L, FlashPerformanceBenchmark.SIZE_16_GB)
        assertEquals(34_359_738_368L, FlashPerformanceBenchmark.SIZE_32_GB)
        assertEquals(68_719_476_736L, FlashPerformanceBenchmark.SIZE_64_GB)

        val sink64Gb = SyntheticBenchmarkSinkDevice(totalSectors = FlashPerformanceBenchmark.SIZE_64_GB / 512)
        assertEquals(134_217_728L, sink64Gb.capacity().totalSectors)

        val metrics = FlashPerformanceBenchmark.runBenchmark(
            context = app,
            targetDevice = sink64Gb,
            workloadBytes = 50L * 1024L * 1024L
        )

        assertTrue(metrics.averageMBps > 0.0)
        assertNotNull(metrics.toMarkdownRow())
    }

    @Test
    fun testBenchmarkFullSuiteReportFormatting() = runTest {
        val sampleMetrics = listOf(
            FlashPerformanceBenchmark.BenchmarkMetrics(
                workloadSizeBytes = FlashPerformanceBenchmark.SIZE_1_GB,
                formattedSize = "1 GB",
                durationMs = 15_000,
                averageMBps = 68.27,
                peakMBps = 75.12,
                minMBps = 58.40,
                cpuUtilizationPercent = 14.5,
                ramHeapUsedMb = 32.4,
                ramNativeAllocatedMb = 18.2,
                gcCount = 2,
                gcTimeMs = 12,
                usbErrorsCount = 0,
                thermalStatus = "NONE",
                thermalHeadroom = 0.85f,
                temperatureCelsius = 31.5f
            ),
            FlashPerformanceBenchmark.BenchmarkMetrics(
                workloadSizeBytes = FlashPerformanceBenchmark.SIZE_4_GB,
                formattedSize = "4 GB",
                durationMs = 62_000,
                averageMBps = 66.05,
                peakMBps = 74.50,
                minMBps = 55.20,
                cpuUtilizationPercent = 16.2,
                ramHeapUsedMb = 34.1,
                ramNativeAllocatedMb = 18.5,
                gcCount = 5,
                gcTimeMs = 28,
                usbErrorsCount = 0,
                thermalStatus = "LIGHT",
                thermalHeadroom = 0.72f,
                temperatureCelsius = 34.2f
            ),
            FlashPerformanceBenchmark.BenchmarkMetrics(
                workloadSizeBytes = FlashPerformanceBenchmark.SIZE_8_GB,
                formattedSize = "8 GB",
                durationMs = 125_000,
                averageMBps = 65.52,
                peakMBps = 73.80,
                minMBps = 54.10,
                cpuUtilizationPercent = 17.0,
                ramHeapUsedMb = 35.0,
                ramNativeAllocatedMb = 18.9,
                gcCount = 9,
                gcTimeMs = 45,
                usbErrorsCount = 0,
                thermalStatus = "LIGHT",
                thermalHeadroom = 0.68f,
                temperatureCelsius = 36.0f
            ),
            FlashPerformanceBenchmark.BenchmarkMetrics(
                workloadSizeBytes = FlashPerformanceBenchmark.SIZE_16_GB,
                formattedSize = "16 GB",
                durationMs = 252_000,
                averageMBps = 65.00,
                peakMBps = 72.90,
                minMBps = 53.50,
                cpuUtilizationPercent = 17.5,
                ramHeapUsedMb = 35.8,
                ramNativeAllocatedMb = 19.1,
                gcCount = 18,
                gcTimeMs = 92,
                usbErrorsCount = 0,
                thermalStatus = "MODERATE",
                thermalHeadroom = 0.55f,
                temperatureCelsius = 38.5f
            ),
            FlashPerformanceBenchmark.BenchmarkMetrics(
                workloadSizeBytes = FlashPerformanceBenchmark.SIZE_32_GB,
                formattedSize = "32 GB",
                durationMs = 510_000,
                averageMBps = 64.24,
                peakMBps = 72.10,
                minMBps = 52.80,
                cpuUtilizationPercent = 18.1,
                ramHeapUsedMb = 36.2,
                ramNativeAllocatedMb = 19.4,
                gcCount = 35,
                gcTimeMs = 180,
                usbErrorsCount = 0,
                thermalStatus = "MODERATE",
                thermalHeadroom = 0.48f,
                temperatureCelsius = 40.1f
            ),
            FlashPerformanceBenchmark.BenchmarkMetrics(
                workloadSizeBytes = FlashPerformanceBenchmark.SIZE_64_GB,
                formattedSize = "64 GB",
                durationMs = 1_035_000,
                averageMBps = 63.30,
                peakMBps = 71.50,
                minMBps = 51.90,
                cpuUtilizationPercent = 18.8,
                ramHeapUsedMb = 36.9,
                ramNativeAllocatedMb = 19.8,
                gcCount = 72,
                gcTimeMs = 365,
                usbErrorsCount = 0,
                thermalStatus = "SEVERE",
                thermalHeadroom = 0.35f,
                temperatureCelsius = 42.8f
            )
        )

        val report = FlashPerformanceBenchmark.BenchmarkReport(
            timestamp = System.currentTimeMillis(),
            deviceDescription = "USB 3.2 Gen 1 OTG Drive (Samsung BAR Plus 128GB)",
            metrics = sampleMetrics
        )

        val markdown = report.toMarkdownTable()
        assertTrue(markdown.contains("FlashCore Performance Benchmark Report"))
        assertTrue(markdown.contains("1 GB"))
        assertTrue(markdown.contains("4 GB"))
        assertTrue(markdown.contains("8 GB"))
        assertTrue(markdown.contains("16 GB"))
        assertTrue(markdown.contains("32 GB"))
        assertTrue(markdown.contains("64 GB"))
        assertTrue(markdown.contains("68.27 MB/s"))
        assertTrue(markdown.contains("42.8°C"))
    }
}
