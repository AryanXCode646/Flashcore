package com.ashishsinghbora.flashcore

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import com.ashishsinghbora.flashcore.block.MemoryBlockDevice
import com.ashishsinghbora.flashcore.dsa.IsoTrieParser
import com.ashishsinghbora.flashcore.flasher.FlashEngineStrategy
import com.ashishsinghbora.flashcore.flasher.fsm.FlasherState
import com.ashishsinghbora.flashcore.flasher.strategies.LinuxRawDdStrategy
import com.ashishsinghbora.flashcore.ui.FlasherViewModel
import com.ashishsinghbora.flashcore.ui.screens.MainFlasherScreen
import com.ashishsinghbora.flashcore.ui.theme.FlashCoreTheme
import com.ashishsinghbora.flashcore.usb.UsbDiskInfo
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("FlashCore", appName)
    }

    @Test
    fun `viewModel initial state has no hardcoded virtual device`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = FlasherViewModel(app)

        val state = viewModel.uiState.value
        assertNotNull(state)
        // Verify no fake drive is created
        assertTrue(state.connectedDevices.isEmpty())
        assertNull(state.selectedDevice)
        assertEquals(FlasherState.Idle, state.fsmState)
        assertEquals("LINUX_RAW_DD", state.selectedStrategyId)
        assertTrue(state.logs.isNotEmpty())
    }

    @Test
    fun `IsoTrieParser auto detects Linux Kali Ubuntu Arch Fedora Debian Windows`() {
        fun makeDummyStream(): ByteArrayInputStream {
            val data = ByteArray(64 * 1024)
            // Sector 16 PVD magic CD001
            val magic = "CD001".toByteArray(Charsets.US_ASCII)
            System.arraycopy(magic, 0, data, 16 * 2048 + 1, magic.size)
            return ByteArrayInputStream(data)
        }

        // Test Kali Linux Detection
        val kaliResult = IsoTrieParser.parse(makeDummyStream(), 3800000000L, fileName = "kali-linux-2024.2-live-amd64.iso")
        assertEquals("Kali Linux", kaliResult.osName)
        assertEquals("KALI", kaliResult.distroBadge)

        // Test Ubuntu Detection
        val ubuntuResult = IsoTrieParser.parse(makeDummyStream(), 5000000000L, fileName = "ubuntu-24.04-desktop-amd64.iso")
        assertEquals("Ubuntu Linux", ubuntuResult.osName)
        assertEquals("UBUNTU", ubuntuResult.distroBadge)

        // Test Arch Linux Detection
        val archResult = IsoTrieParser.parse(makeDummyStream(), 1100000000L, fileName = "archlinux-2024.08.01-x86_64.iso")
        assertEquals("Arch Linux", archResult.osName)
        assertEquals("ARCH", archResult.distroBadge)

        // Test Fedora Detection
        val fedoraResult = IsoTrieParser.parse(makeDummyStream(), 2200000000L, fileName = "Fedora-Workstation-Live-x86_64-40-1.14.iso")
        assertEquals("Fedora Linux", fedoraResult.osName)
        assertEquals("FEDORA", fedoraResult.distroBadge)

        // Test Windows 11 Detection
        val winResult = IsoTrieParser.parse(makeDummyStream(), 6200000000L, fileName = "Win11_23H2_English_x64v2.iso")
        assertEquals("Windows 11", winResult.osName)
        assertEquals("WINDOWS", winResult.distroBadge)
        assertEquals(IsoTrieParser.ImageType.WINDOWS_INSTALLER, winResult.imageType)

        // Test Debian Detection
        val debianResult = IsoTrieParser.parse(makeDummyStream(), 650000000L, fileName = "debian-12.6.0-amd64-netinst.iso")
        assertEquals("Debian GNU/Linux", debianResult.osName)
        assertEquals("DEBIAN", debianResult.distroBadge)
    }

    @Test
    fun `strategy selection lifecycle`() = runTest {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = FlasherViewModel(app)

        viewModel.selectStrategy("WINDOWS_UEFI")
        assertEquals("WINDOWS_UEFI", viewModel.uiState.value.selectedStrategyId)

        viewModel.selectStrategy("VENTOY_MULTIBOOT")
        assertEquals("VENTOY_MULTIBOOT", viewModel.uiState.value.selectedStrategyId)
    }

    @Test
    fun `safety dialog lifecycle`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = FlasherViewModel(app)

        // If no target device and image, dialog shouldn't open
        viewModel.openSafetyConfirmation()
        assertFalse(viewModel.uiState.value.showSafetyDialog)

        viewModel.dismissSafetyConfirmation()
        assertFalse(viewModel.uiState.value.showSafetyDialog)
    }

    @Test
    fun `full main screen compose rendering`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = FlasherViewModel(app)

        composeTestRule.setContent {
            FlashCoreTheme {
                MainFlasherScreen(viewModel = viewModel)
            }
        }

        // Verify key UI nodes exist in hierarchy
        composeTestRule.onNodeWithTag("drive_selector_card").assertExists()
        composeTestRule.onNodeWithTag("image_inspector_card").assertExists()
        composeTestRule.onNodeWithTag("strategy_selector_card").assertExists()
        composeTestRule.onNodeWithTag("terminal_log_view").assertExists()
    }

    @Test
    fun `LinuxRawDdStrategy writes correctly to MemoryBlockDevice`() = runTest {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val strategy = LinuxRawDdStrategy()
        val memoryDevice = MemoryBlockDevice(totalSectors = 4096L, sectorSizeBytes = 512)

        val diskInfo = UsbDiskInfo(
            device = null,
            vendorId = 0x1234,
            productId = 0x5678,
            manufacturerName = "FlashCore",
            productName = "Virtual Disk",
            vendorString = "FLASHCORE",
            productString = "VIRTUAL_DISK",
            revision = "1.0",
            serialNumber = "VIRTUAL_001",
            totalCapacityBytes = 4096L * 512L,
            totalSectors = 4096L,
            sectorSizeBytes = 512,
            isRemovable = true,
            isWriteProtected = false,
            hasPermission = true
        )

        val isoFile = File(app.cacheDir, "test_ubuntu.iso")
        val isoBytes = ByteArray(64 * 1024) { (it % 256).toByte() }
        "CD001".toByteArray(Charsets.US_ASCII).copyInto(isoBytes, 16 * 2048 + 1)
        isoFile.writeBytes(isoBytes)
        val isoUri = Uri.fromFile(isoFile)

        val analysis = IsoTrieParser.parse(
            stream = isoFile.inputStream(),
            totalSizeBytes = isoBytes.size.toLong(),
            fileName = "ubuntu-24.04-desktop-amd64.iso"
        )

        val progressLogs = mutableListOf<String>()
        val result = strategy.execute(
            context = app,
            device = memoryDevice,
            targetDrive = diskInfo,
            sourceUri = isoUri,
            isoAnalysis = analysis,
            config = FlashEngineStrategy.FlashConfig(blockSizeBytes = 512 * 1024),
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
                override fun onVerificationProgress(verifiedBytes: Long, totalBytes: Long, isMatching: Boolean) {}
                override fun onLogMessage(message: String) { progressLogs.add(message) }
            },
            isCancelled = { false }
        )

        assertTrue(result.success)
        assertEquals(isoBytes.size.toLong(), result.totalBytesWritten)
        assertTrue(memoryDevice.flushCount > 0)
        assertTrue(memoryDevice.writeCount > 0)

        // Verify sector 0 byte content matches source ISO exactly
        val readSector0 = ByteArray(512)
        memoryDevice.read(0L, 1, readSector0)
        val expectedSector0 = isoBytes.copyOfRange(0, 512)
        assertArrayEquals(expectedSector0, readSector0)
    }
}

