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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.rokid.context.phone.ui.settings.HudSettingsControl
import kotlinx.coroutines.flow.StateFlow
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.hud.Hud
import com.rokid.context.core.model.Gesture
import com.rokid.context.core.model.GestureAction
import com.rokid.context.core.model.HudPosition
import com.rokid.context.core.model.IconCorner
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
import com.rokid.context.voice.VoiceCapabilities
import com.rokid.context.voice.VoiceStatus
import kotlinx.coroutines.flow.flowOf
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertContentDescriptionEquals
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

    /** Spec 2026-10-14 §6: icon corner (4 choices) and pop-up time (3-20 s) save through the hub, plus the permission hint. */
    @Test fun glassesSettingsSavesIconCornerAndPopupTime() {
        val hud = FakeHudSettings()
        val vm = GlassesSettingsViewModel(FakeGlassesStatusSource(FakeGlassesStatusSource.connected(Fixtures.stateFrame())), hud) {}
        vm.setIconCorner(IconCorner.BOTTOM_LEFT)
        vm.setPopupSeconds(50)
        assertEquals(IconCorner.BOTTOM_LEFT, hud.settings.value.iconCorner)
        assertEquals(20, hud.settings.value.popupSeconds)
        vm.setPopupSeconds(9)
        assertEquals(9, hud.settings.value.popupSeconds)
        vm.cancelScope()

        var corner: IconCorner? = null
        var popup: Int? = null
        compose.setContent {
            PcTheme {
                GlassesSettingsScreen(GlassesSettingsUiState("Linked", HudSettings()), onBack = {}, onVerbosity = {}, onScale = {}, onPosition = {}, onGesture = { _, _ -> }, onReauthorize = {},
                    onIconCorner = { corner = it }, onPopupSeconds = { popup = it })
            }
        }
        val page = compose.onNodeWithTag("glasses_settings")
        page.performScrollToNode(hasTestTag("icon_corner"))
        compose.onNodeWithContentDescription("Icon corner: Top right").assertExists()
        compose.onNodeWithTag("icon_corner").performClick()
        compose.onNodeWithTag("icon_corner_${IconCorner.entries.indexOf(IconCorner.BOTTOM_RIGHT)}").performClick()
        assertEquals(IconCorner.BOTTOM_RIGHT, corner)
        page.performScrollToNode(hasTestTag("popup_seconds"))
        compose.onNodeWithText("Pop-up time 6 s").assertExists()
        compose.onNodeWithTag("popup_seconds").performTouchInput { swipeRight(startX = left + 10f, endX = right + 50f, durationMillis = 300) } // past the end: the drag clamps to 20
        compose.waitForIdle()
        assertEquals(20, popup)
        page.performScrollToNode(hasTestTag("overlay_hint"))
        compose.onNodeWithText(GlassesLabels.OVERLAY_HINT).assertExists()
        assertEquals("For the minimised icon, allow 'display over other apps' for Proactive Context on the glasses.", GlassesLabels.OVERLAY_HINT)
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

    /** Spec §5.1.1: the gesture map greys out Ask, with the reason, while glasses voice can't work. */
    @Test fun askIsGreyedOutWithItsReasonWhileGlassesVoiceIsUnavailable() = runBlocking<Unit> {
        val vm = GlassesSettingsViewModel(FakeGlassesStatusSource(), FakeHudSettings(),
            voice = flowOf(VoiceCapabilities(VoiceStatus.NEEDS_SPEECH_PACK, VoiceStatus.NEEDS_SPEECH_PACK, canDownloadPack = true))) {}
        assertEquals("Needs speech pack", withTimeout(5_000) { vm.state.first { it.askUnavailable != null } }.askUnavailable)
        vm.cancelScope()
        compose.setContent {
            PcTheme { GlassesSettingsScreen(GlassesSettingsUiState("Linked", HudSettings(), askUnavailable = "Needs speech pack"), onBack = {}, onVerbosity = {}, onScale = {}, onPosition = {}, onGesture = { _, _ -> }, onReauthorize = {}) }
        }
        compose.onNodeWithTag("glasses_settings").performScrollToNode(hasTestTag("ask_unavailable"))
        compose.onNodeWithText("Ask by voice from the glasses: Needs speech pack — see Phone camera & mic").assertExists()
        compose.onNodeWithTag("gesture_TAP").performClick()
        compose.onNodeWithTag("gesture_TAP_${GestureAction.entries.indexOf(GestureAction.ASK)}").assertIsNotEnabled()
        // Fix round 1: the greyed-out item itself carries the reason for TalkBack.
        compose.onNodeWithTag("gesture_TAP_${GestureAction.entries.indexOf(GestureAction.ASK)}").assertContentDescriptionEquals("Ask \u2014 Needs speech pack")
    }

    /** Phase 5 T16 carry: while dragging, the finger's value is shown whatever the stored scale does. */
    @Test fun theSliderShowsTheFingersValueWhileDragging() {
        assertEquals(0.7f, ScaleSlider.shown(dragging = 0.7f, stored = 0.5f))
        assertEquals(0.5f, ScaleSlider.shown(dragging = null, stored = 0.5f))
    }

    /** Phase 5 T16 carry (preflight F10): a stored-scale re-emit mid-drag does not snap the slider back from under the finger. */
    @Test fun aStoredScaleChangeMidDragKeepsTheFingersValue() {
        var hud by mutableStateOf(HudSettings())
        compose.setContent {
            PcTheme { GlassesSettingsScreen(GlassesSettingsUiState("Linked", hud), onBack = {}, onVerbosity = {}, onScale = {}, onPosition = {}, onGesture = { _, _ -> }, onReauthorize = {}) }
        }
        compose.onNodeWithTag("glasses_settings").performScrollToNode(hasTestTag("hud_scale"))
        val slider = compose.onNodeWithTag("hud_scale")
        fun current() = slider.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current
        slider.performTouchInput { down(centerLeft + androidx.compose.ui.geometry.Offset(10f, 0f)); moveBy(androidx.compose.ui.geometry.Offset(width * 0.6f, 0f)) } // no up: still dragging
        compose.waitForIdle()
        val finger = current()
        assertTrue("the drag moved the slider", finger > HudSettings().scale)
        hud = hud.copy(scale = Hud.MIN_SCALE) // the stored scale re-emits while the finger is down
        compose.waitForIdle()
        assertEquals(finger, current(), 0.0001f)
        slider.performTouchInput { up() }
    }

    /** Fix round 1: after release the dragged value stays until the stored scale catches up; then the store leads again. */
    @Test fun aReleasedDragKeepsItsValueUntilTheStoredScaleCatchesUp() {
        var hud by mutableStateOf(HudSettings())
        var written: Float? = null
        compose.setContent {
            PcTheme { GlassesSettingsScreen(GlassesSettingsUiState("Linked", hud), onBack = {}, onVerbosity = {}, onScale = { written = it }, onPosition = {}, onGesture = { _, _ -> }, onReauthorize = {}) }
        }
        compose.onNodeWithTag("glasses_settings").performScrollToNode(hasTestTag("hud_scale"))
        val slider = compose.onNodeWithTag("hud_scale")
        fun current() = slider.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current
        slider.performTouchInput { down(centerLeft + androidx.compose.ui.geometry.Offset(10f, 0f)); moveBy(androidx.compose.ui.geometry.Offset(width * 0.6f, 0f)); up() }
        compose.waitForIdle()
        val finger = written!!
        assertEquals("the write has not landed yet: the finger's value stays", finger, current(), 0.0001f)
        hud = hud.copy(scale = finger); compose.waitForIdle()
        assertEquals(finger, current(), 0.0001f)
        hud = hud.copy(scale = Hud.MIN_SCALE); compose.waitForIdle()
        assertEquals("caught up: the stored scale leads again", Hud.MIN_SCALE, current(), 0.0001f)
    }

    /** Fix round 1: a cancelled gesture (no onValueChangeFinished guarantee) drops the dragged value. */
    @Test fun aCancelledDragShowsTheStoredScale() {
        compose.setContent {
            PcTheme { GlassesSettingsScreen(GlassesSettingsUiState("Linked", HudSettings()), onBack = {}, onVerbosity = {}, onScale = {}, onPosition = {}, onGesture = { _, _ -> }, onReauthorize = {}) }
        }
        compose.onNodeWithTag("glasses_settings").performScrollToNode(hasTestTag("hud_scale"))
        val slider = compose.onNodeWithTag("hud_scale")
        slider.performTouchInput { down(centerLeft + androidx.compose.ui.geometry.Offset(10f, 0f)); moveBy(androidx.compose.ui.geometry.Offset(width * 0.6f, 0f)); cancel() }
        compose.waitForIdle()
        assertEquals(HudSettings().scale, slider.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current, 0.0001f)
    }
}
