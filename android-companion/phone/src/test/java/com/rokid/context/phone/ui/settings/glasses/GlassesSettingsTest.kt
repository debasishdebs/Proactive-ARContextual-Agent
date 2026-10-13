package com.rokid.context.phone.ui.settings.glasses

import android.app.Application
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.rokid.context.phone.ui.settings.HudSettingsControl
import kotlinx.coroutines.flow.StateFlow
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
import org.junit.Assert.assertTrue
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

    /** Counts every write the hub would persist and push (`Hub.updateSettings`). */
    private class CountingHud(private val inner: FakeHudSettings = FakeHudSettings()) : HudSettingsControl {
        var updates = 0
        override val settings: StateFlow<HudSettings> get() = inner.settings
        override fun update(transform: (HudSettings) -> HudSettings) { updates++; inner.update(transform) }
    }

    /** Fix round 1 (review I2): a drag is many frames but one settings write, so the glasses get one `pc_settings`. */
    @Test fun aSliderDragWritesTheSettingsExactlyOnce() {
        val hud = CountingHud()
        val vm = GlassesSettingsViewModel(FakeGlassesStatusSource(FakeGlassesStatusSource.connected(Fixtures.stateFrame())), hud) {}
        compose.setContent {
            val state by vm.state.collectAsState()
            PcTheme { GlassesSettingsScreen(state, onBack = {}, onVerbosity = vm::setVerbosity, onScale = vm::setScale, onPosition = vm::setPosition, onGesture = vm::setGesture, onReauthorize = vm::reauthorize) }
        }
        compose.onNodeWithTag("glasses_settings").performScrollToNode(hasTestTag("hud_scale"))
        compose.onNodeWithTag("hud_scale").performTouchInput { swipeRight(startX = left + 10f, endX = right - 10f, durationMillis = 500) }
        compose.waitForIdle()
        assertEquals(1, hud.updates)
        assertTrue(hud.settings.value.scale > HudSettings().scale)
        vm.cancelScope()
    }

    /** Fix round 1 (review I3): 48 dp radio cells TalkBack can name; plus labels for the slider and the gesture menus. */
    @Test fun positionCellsAreNamedSelectableRadioButtonsOfAtLeast48dp() {
        var picked: HudPosition? = null
        compose.setContent { PcTheme { GlassesSettingsScreen(GlassesSettingsUiState("Linked", HudSettings()), onBack = {}, onVerbosity = {}, onScale = {}, onPosition = { picked = it }, onGesture = { _, _ -> }, onReauthorize = {}) } }
        val page = compose.onNodeWithTag("glasses_settings")
        page.performScrollToNode(hasTestTag("hud_scale"))
        compose.onNodeWithContentDescription("HUD size").assertExists()
        page.performScrollToNode(hasTestTag("pos_TOP_LEFT"))
        compose.onNodeWithTag("pos_TOP_LEFT").assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).assertIsNotSelected()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Top left")))
        page.performScrollToNode(hasTestTag("pos_BOTTOM_CENTER"))
        compose.onNodeWithTag("pos_BOTTOM_CENTER").assertIsSelected()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Bottom center")))
        compose.onNodeWithTag("pos_BOTTOM_CENTER").performClick()
        assertEquals(HudPosition.BOTTOM_CENTER, picked)
        val tap = GlassesLabels.ACTIONS.getValue(HudSettings().gestures.getValue(Gesture.TAP))
        page.performScrollToNode(hasTestTag("gesture_TAP"))
        compose.onNodeWithContentDescription("Tap: $tap").assertExists()
    }
}
