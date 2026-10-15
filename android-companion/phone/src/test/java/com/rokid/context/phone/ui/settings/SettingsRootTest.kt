package com.rokid.context.phone.ui.settings

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.model.GlassesStatus
import com.rokid.context.core.model.HudSettings
import com.rokid.context.core.model.LinkState
import com.rokid.context.core.ui.theme.PcTheme
import com.rokid.context.phone.ui.Fixtures
import com.rokid.context.phone.ui.glasses.FakeGlassesStatusSource
import com.rokid.context.phone.ui.shell.Routes
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class SettingsRootTest {
    @get:Rule val compose = createComposeRule()

    @Test fun summariesFollowTheLinkAndTheCaptureSettings() {
        assertEquals("Linked · 82%", SettingsModel.glassesSummary(FakeGlassesStatusSource.connected(Fixtures.stateFrame())))
        assertEquals("Not connected", SettingsModel.glassesSummary(FakeGlassesStatusSource.OFFLINE))
        assertEquals("Minimised on glasses \u00b7 capturing", SettingsModel.glassesSummary(GlassesStatus(LinkState.MINIMISED, null, 0, null, null)))
        assertEquals("Update the glasses app", SettingsModel.glassesSummary(GlassesStatus(LinkState.VERSION_MISMATCH, null, 0, null, null)))
        assertEquals("Every 5 min", SettingsModel.captureSummary(HudSettings()))
        assertEquals("Smart", SettingsModel.captureSummary(HudSettings(captureMode = com.rokid.context.core.model.CaptureMode.SMART)))
        assertEquals("Every 1 h", SettingsModel.captureSummary(HudSettings(captureIntervalSec = 9_000, burstOnChange = false)))
    }

    @Test fun everyGroupIsListedAndOnlyLiveRowsOpen() {
        val opened = mutableListOf<String>()
        // Phase 7: every row is live, so a set without Data sources checks that a non-live row stays closed.
        val live = Routes.LIVE_SETTINGS - Routes.SETTINGS_SOURCES
        compose.setContent { PcTheme { SettingsRootScreen(SettingsRootUiState("Linked · 82%", "Every 5 min · burst on change"), live = live, onOpen = { opened += it }) } }
        listOf("DEVICES", "CAPTURE & PRIVACY", "AI", "CONNECTORS", "MEMORY & SYSTEM").forEach {
            compose.onNodeWithTag("settings_list").performScrollToNode(hasText(it)); compose.onNodeWithText(it).assertIsDisplayed()
        }
        // The tree is taller than the Robolectric screen: scroll back up before tapping the first row.
        compose.onNodeWithTag("settings_list").performScrollToNode(hasText("Rokid Glasses"))
        compose.onNodeWithText("Rokid Glasses").performClick()
        compose.onNodeWithTag("settings_list").performScrollToNode(hasText("Data sources"))
        compose.onNodeWithText("Data sources").assertIsNotEnabled()
        compose.onNodeWithTag("settings_list").performScrollToNode(hasText("Diagnostics"))
        compose.onNodeWithText("Diagnostics").assertIsEnabled()
        compose.onNodeWithText("Diagnostics").performClick()
        assertEquals(listOf(Routes.SETTINGS_GLASSES, Routes.SETTINGS_DIAGNOSTICS), opened)
    }

    @Test fun phoneCameraAndMicOpens() {
        val opened = mutableListOf<String>()
        compose.setContent { PcTheme { SettingsRootScreen(SettingsRootUiState("Linked", "Every 5 min", "Voice · Supported"), live = Routes.LIVE_SETTINGS, onOpen = { opened += it }) } }
        compose.onNodeWithTag("settings_list").performScrollToNode(hasText("Phone camera & mic"))
        compose.onNodeWithText("Voice · Supported").assertIsDisplayed()
        compose.onNodeWithText("Phone camera & mic").performClick()
        assertEquals(listOf(Routes.SETTINGS_PHONE_INPUTS), opened)
    }

    @Test fun connectorsAndPrivacyRowsOpen() {
        val opened = mutableListOf<String>()
        compose.setContent { PcTheme { SettingsRootScreen(SettingsRootUiState("Linked", "Every 5 min"), live = Routes.LIVE_SETTINGS, onOpen = { opened += it }) } }
        listOf("Privacy" to "Pause, quiet hours", "Data sources" to "Calendar, contacts, notes, location", "Actions & tools" to "Reminders, shopping list, calendar")
            .forEach { (title, subtitle) ->
                compose.onNodeWithTag("settings_list").performScrollToNode(hasText(title))
                compose.onNodeWithText(subtitle).assertIsDisplayed()
                compose.onNodeWithText(title).performClick()
            }
        assertEquals(listOf(Routes.SETTINGS_PRIVACY, Routes.SETTINGS_SOURCES, Routes.SETTINGS_ACTIONS), opened)
    }
}
