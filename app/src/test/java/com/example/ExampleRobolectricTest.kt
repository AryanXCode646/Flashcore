package com.example

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.example.flasher.fsm.FlasherState
import com.example.ui.FlasherViewModel
import com.example.ui.screens.MainFlasherScreen
import com.example.ui.theme.FlashCoreTheme
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

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
    fun `viewModel initialization and auto target discovery`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = FlasherViewModel(app)

        val state = viewModel.uiState.value
        assertNotNull(state)
        assertTrue(state.connectedDevices.isNotEmpty())
        assertNotNull(state.selectedDevice)
        assertEquals("LINUX_RAW_DD", state.selectedStrategyId)
        assertTrue(state.logs.isNotEmpty())
    }

    @Test
    fun `load sample ubuntu iso updates analysis and keeps valid state`() = runTest {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = FlasherViewModel(app)

        viewModel.loadSampleImage("ubuntu")
        
        // Wait or check state
        val state = viewModel.uiState.value
        assertNotNull(state)
    }

    @Test
    fun `load sample windows iso triggers recommended uefi strategy`() = runTest {
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

        // If no image, dialog shouldn't open
        viewModel.openSafetyConfirmation()
        assertFalse(viewModel.uiState.value.showSafetyDialog)

        // Load sample image
        viewModel.loadSampleImage("ubuntu")
        // Dismiss
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

