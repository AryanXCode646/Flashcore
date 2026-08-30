package com.example

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import com.example.dsa.IsoTrieParser
import com.example.flasher.fsm.FlasherState
import com.example.ui.FlasherViewModel
import com.example.ui.screens.MainFlasherScreen
import com.example.ui.theme.FlashCoreTheme
import kotlinx.coroutines.test.runTest
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
}

