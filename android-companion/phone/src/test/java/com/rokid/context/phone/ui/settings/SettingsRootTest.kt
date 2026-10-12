package com.rokid.context.phone.ui.settings

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.model.GlassesStatus
import com.rokid.context.core.model.LinkState
import com.rokid.context.core.ui.theme.PcTheme
import com.rokid.context.phone.ui.Fixtures
import com.rokid.context.phone.ui.glasses.FakeGlassesStatusSource
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class SettingsRootTest {
    @get:Rule val compose = createComposeRule()

    @Test fun glassesSummaryFollowsTheLink() {
        assertEquals("Linked · 82%", SettingsModel.glassesSummary(FakeGlassesStatusSource.connected(Fixtures.stateFrame())))
        assertEquals("Linked", SettingsModel.glassesSummary(FakeGlassesStatusSource.connected(Fixtures.stateFrame(battery = null))))
        assertEquals("Not connected", SettingsModel.glassesSummary(FakeGlassesStatusSource.OFFLINE))
        assertEquals("Update the glasses app", SettingsModel.glassesSummary(GlassesStatus(LinkState.VERSION_MISMATCH, null, 0, null, null)))
    }

    @Test fun onlyEndpointsAndDiagnosticsAreLive() {
        var endpoints = 0; var diagnostics = 0
        compose.setContent { PcTheme { SettingsRootScreen(SettingsRootUiState("Linked · 82%"), onOpenEndpoints = { endpoints++ }, onOpenDiagnostics = { diagnostics++ }) } }
        compose.onNodeWithText("DEVICES").assertIsDisplayed()
        compose.onNodeWithText("Linked · 82%").assertIsDisplayed()
        compose.onNodeWithTag("settings_list").performScrollToNode(hasText("Model endpoints"))
        compose.onNodeWithText("Model endpoints").performClick()
        compose.onNodeWithTag("settings_list").performScrollToNode(hasText("Prompts"))
        compose.onNodeWithText("Prompts").assertIsNotEnabled()
        compose.onNodeWithTag("settings_list").performScrollToNode(hasText("Diagnostics"))
        compose.onNodeWithText("Diagnostics").performClick()
        assertEquals(1, endpoints); assertEquals(1, diagnostics)
    }
}
