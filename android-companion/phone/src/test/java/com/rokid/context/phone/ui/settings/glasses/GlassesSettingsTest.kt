package com.rokid.context.phone.ui.settings.glasses

import android.app.Application
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.model.Gesture
import com.rokid.context.core.model.GestureAction
import com.rokid.context.core.model.HudPosition
import com.rokid.context.core.model.HudSettings
import com.rokid.context.core.model.Verbosity
import com.rokid.context.core.ui.theme.PcTheme
import com.rokid.context.phone.ui.Fixtures
import com.rokid.context.phone.ui.MainDispatcherRule
import com.rokid.context.phone.ui.cancelScope
import com.rokid.context.phone.ui.glasses.FakeGlassesStatusSource
import com.rokid.context.phone.ui.settings.FakeHudSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class GlassesSettingsTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val compose = createComposeRule()

    @Test fun everyControlWritesTheHudSettings() = runBlocking {
        val hud = FakeHudSettings()
        var reauth = 0
        val vm = GlassesSettingsViewModel(FakeGlassesStatusSource(FakeGlassesStatusSource.connected(Fixtures.stateFrame())), hud) { reauth++ }
        vm.setVerbosity(Verbosity.MAX); vm.setScale(5f); vm.setPosition(HudPosition.TOP_RIGHT); vm.setGesture(Gesture.TAP, GestureAction.NONE); vm.reauthorize()
        assertEquals(HudSettings(verbosity = Verbosity.MAX, scale = 1f, position = HudPosition.TOP_RIGHT, gestures = HudSettings().gestures + (Gesture.TAP to GestureAction.NONE)), hud.settings.value)
        vm.setScale(Float.NaN)
        assertEquals(0.4f, hud.settings.value.scale)
        assertEquals(1, reauth)
        val s = withTimeout(5_000) { vm.state.first { it.linkSummary == "Linked · 82%" } }
        assertEquals(Verbosity.MAX, s.settings.verbosity)
        vm.cancelScope()
    }

    @Test fun screenShowsThePreviewAndTheThreeVerbosities() {
        var picked: Verbosity? = null
        compose.setContent { PcTheme { GlassesSettingsScreen(GlassesSettingsUiState("Linked · 82%", HudSettings()), onBack = {}, onVerbosity = { picked = it }, onScale = {}, onPosition = {}, onGesture = { _, _ -> }, onReauthorize = {}) } }
        compose.onNodeWithTag("hud_preview").assertExists()
        compose.onNodeWithText("Max").performClick()
        assertEquals(Verbosity.MAX, picked)
        compose.onNodeWithText("Re-authorize").assertExists()
        // The position grid sits below the preview, past the Robolectric screen: scroll the page to it.
        compose.onNodeWithTag("glasses_settings").performScrollToNode(hasTestTag("pos_BOTTOM_CENTER"))
        compose.onNodeWithTag("pos_BOTTOM_CENTER").assertExists()
    }
}
