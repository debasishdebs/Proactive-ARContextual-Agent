package com.rokid.context.phone.ui.settings.capture

import android.app.Application
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.rokid.context.core.model.CaptureMode
import com.rokid.context.core.model.QUICK_LOOK_MS_ALLOWED
import com.rokid.context.core.model.REST_MINUTES_ALLOWED
import com.rokid.context.core.model.WORK_MINUTES_ALLOWED
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.ui.theme.PcTheme
import com.rokid.context.core.model.HudSettings
import com.rokid.context.phone.ui.MainDispatcherRule
import com.rokid.context.phone.ui.cancelScope
import com.rokid.context.phone.ui.settings.FakeHudSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class CaptureSettingsTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val compose = createComposeRule()

    @Test fun intervalIsClampedToThirtySecondsToOneHour() {
        assertEquals(30, CaptureIntervals.clamp(0)); assertEquals(30, CaptureIntervals.clamp(-5))
        assertEquals(3_600, CaptureIntervals.clamp(7_200)); assertEquals(420, CaptureIntervals.clamp(420))
        assertEquals("30 s", CaptureIntervals.label(30)); assertEquals("5 min", CaptureIntervals.label(300)); assertEquals("1 h", CaptureIntervals.label(3_600))
        assertEquals((CaptureIntervals.PRESETS_SEC + 420).sorted(), CaptureIntervals.options(420))
    }

    @Test fun anOutOfRangeStoredIntervalShowsAndSavesClamped() = runBlocking {
        val hud = FakeHudSettings(HudSettings(captureIntervalSec = 7_200))
        val vm = CaptureSettingsViewModel(hud)
        assertEquals(3_600, withTimeout(5_000) { vm.state.first() }.intervalSec)
        vm.setInterval(5); assertEquals(30, hud.settings.value.captureIntervalSec)
        vm.setInterval(600); assertEquals(600, hud.settings.value.captureIntervalSec)
        vm.cancelScope()
    }

    private fun show(state: CaptureSettingsUiState, vm: CaptureSettingsViewModel? = null) {
        compose.setContent { PcTheme { CaptureSettingsScreen(state, onBack = {}, onMode = { vm?.setMode(it) }, onWork = { vm?.setWork(it) }, onRest = { vm?.setRest(it) }) } }
    }

    @Test fun smartHidesInterval() {
        show(CaptureSettingsUiState(mode = CaptureMode.SMART))
        compose.onNodeWithTag("interval").assertDoesNotExist()
        compose.onNodeWithContentDescription("Capture mode: Smart").assertExists()
    }

    @Test fun fixedShowsInterval() {
        show(CaptureSettingsUiState(mode = CaptureMode.FIXED, intervalSec = 300))
        compose.onNodeWithContentDescription("Every: 5 min").assertExists()
    }

    @Test fun burstSwitchGone() {
        show(CaptureSettingsUiState(mode = CaptureMode.FIXED))
        compose.onNodeWithTag("burst").assertDoesNotExist()
        compose.onNodeWithText("Take an extra photo when the scene changes", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Burst on change", substring = true).assertDoesNotExist()
    }

    @Test fun quickLookOptionsAndDefault() {
        assertEquals(1_000, HudSettings().quickLookMs)
        assertEquals(listOf("1 s", "1.5 s", "2 s", "3 s"), QUICK_LOOK_MS_ALLOWED.map(CaptureLabels::quickLook))
        show(CaptureSettingsUiState())
        compose.onNodeWithContentDescription("Quick look: 1 s").assertExists()
        compose.onNodeWithText("Shorter catches quicker glances; the camera light is on more.").assertExists()
    }

    @Test fun workRestOptions() = runBlocking {
        assertEquals(listOf(30, 40, 45, 50, 60, 75, 90), WORK_MINUTES_ALLOWED)
        assertEquals(listOf(5, 10, 15, 20), REST_MINUTES_ALLOWED)
        val s = withTimeout(5_000) { CaptureSettingsViewModel(FakeHudSettings()).also { it.cancelScope() }.state.first() }
        assertEquals(45, s.workMinutes); assertEquals(15, s.restMinutes)
        val hud = FakeHudSettings(HudSettings(workMinutes = 45, restMinutes = 5))
        val vm = CaptureSettingsViewModel(hud)
        vm.setWork(60)
        assertEquals(60, hud.settings.value.workMinutes); assertEquals(15, hud.settings.value.restMinutes)
        vm.setWork(30); vm.setRest(5)
        assertEquals(5, hud.settings.value.restMinutes)
        vm.cancelScope()
    }

    @Test fun restBelow15DisabledWhenWorkOver45() {
        show(CaptureSettingsUiState(workMinutes = 60, restMinutes = 15))
        compose.onNodeWithText("Rest is at least 15 min when Work is over 45 min.").assertExists()
        compose.onNodeWithTag("rest").performSemanticsAction(SemanticsActions.OnClick); compose.waitForIdle()
        compose.onNodeWithTag("rest_0").assertIsNotEnabled()
        compose.onNodeWithTag("rest_1").assertIsNotEnabled()
        compose.onNodeWithTag("rest_2").assertIsEnabled()
        compose.onNodeWithTag("rest_3").assertIsEnabled()
    }

    @Test fun restOptionsEnabledWhenWorkIs45OrLess() {
        show(CaptureSettingsUiState(workMinutes = 45))
        compose.onNodeWithTag("rest").performSemanticsAction(SemanticsActions.OnClick); compose.waitForIdle()
        compose.onNodeWithTag("rest_0").assertIsEnabled()
        compose.onNodeWithTag("rest_note").assertDoesNotExist()
    }

    @Test fun pacePersists() = runBlocking {
        val prefs = ApplicationProvider.getApplicationContext<Application>().getSharedPreferences("pace_test", 0).also { it.edit().clear().commit() }
        val store = AiCallPaceStore(prefs)
        assertEquals(12_000L, store.paceMs.value)
        val vm = CaptureSettingsViewModel(FakeHudSettings(), store)
        vm.setPace(15_000)
        assertEquals(15_000L, prefs.getLong("ai_call_pace_ms", 0))
        assertEquals(15_000L, AiCallPaceStore(prefs).paceMs.value)
        assertEquals(15_000L, withTimeout(5_000) { vm.state.first { it.paceMs == 15_000L } }.paceMs)
        store.set(11_000); assertEquals(10_000L, store.paceMs.value)
        vm.cancelScope()
    }

    /** Fix wave H: the wear switch is back (real signal, default on), and the capture blink is a synced setting, default on. */
    @Test fun wearSwitchAndCaptureBlinkSync() = runBlocking {
        show(CaptureSettingsUiState())
        compose.onNodeWithTag("pause_not_worn").assertExists()
        val hud = FakeHudSettings()
        val vm = CaptureSettingsViewModel(hud)
        assertTrue(hud.settings.value.captureCue); assertTrue(hud.settings.value.pauseWhenNotWorn)
        vm.setCaptureCue(false); assertFalse(hud.settings.value.captureCue)
        vm.setPauseWhenNotWorn(false); assertFalse(hud.settings.value.pauseWhenNotWorn)
        assertFalse(withTimeout(5_000) { vm.state.first { !it.captureCue && !it.pauseWhenNotWorn } }.captureCue)
        vm.cancelScope()
    }

    @Test fun smartIsSentWhenChosenAndFixedWhenSwitched() = runBlocking {
        val hud = FakeHudSettings(HudSettings(captureMode = CaptureMode.SMART))
        val vm = CaptureSettingsViewModel(hud)
        vm.setMode(CaptureMode.FIXED); assertEquals(CaptureMode.FIXED, hud.settings.value.captureMode)
        vm.cancelScope()
    }

    @Test fun statusLineCountsKeyframesInTheLastTenMinutes() {
        assertEquals("Smart \u00b7 5 keyframes in the last 10 min", CaptureSettingsUiState(keyframesPerHour = 30).statusLine)
        assertEquals("Smart \u00b7 1 keyframe in the last 10 min", CaptureSettingsUiState(keyframesPerHour = 6).statusLine)
        show(CaptureSettingsUiState(mode = CaptureMode.SMART, keyframesPerHour = 30))
        compose.onNodeWithTag("capture_status").assertExists()
    }
}
