package com.rokid.context.phone.ui.settings.capture

import android.app.Application
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.model.signals.ClassifierThresholds
import com.rokid.context.phone.context.GlassesClassifierStatus
import com.rokid.context.phone.ui.settings.FakeHudSettings
import com.rokid.context.core.ui.theme.PcTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** v0.9.7 A: Settings > Capture > Signals: classifier thresholds (safe presets, always clamped), which signals guards may use, and what the glasses report. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h2400dp")
class SignalSettingsTest {
    @get:Rule val compose = createComposeRule()
    private val reported = MutableStateFlow<String?>(null)
    /** The state once [ok] holds (the view model's flow runs on the main dispatcher). */
    private fun SignalSettingsViewModel.until(ok: (SignalSettingsUi) -> Boolean = { true }): SignalSettingsUi = snapshot().also { check(ok(it)) { "the state does not hold: $it" } }
    private fun vm(hud: FakeHudSettings = FakeHudSettings(), store: SignalConfigStore = SignalConfigStore()) = SignalSettingsViewModel(hud, store, reported)

    @Test fun defaultsShowNoOverridesAndTheDefaultHash() {
        val ui = vm().until()
        assertEquals("default", ui.hash)
        assertEquals(ClassifierThresholds.RANGES.mapValues { it.value.first }, ui.values)
        assertTrue(ui.signals.all { it.second })
    }

    @Test fun anEditIsClampedStoredOnTheWireSettingsAndResetClearsIt() {
        val hud = FakeHudSettings(); val v = vm(hud)
        v.setThreshold("headMovingRadS", 9.0)
        assertEquals(mapOf("headMovingRadS" to 1.2), hud.settings.value.classifier)
        assertEquals(ClassifierThresholds.hash(hud.settings.value.classifier), v.until { it.hash != "default" }.hash)
        v.setThreshold("walkVariance", 0.5)
        assertEquals("a value equal to the default is no override", setOf("headMovingRadS"), hud.settings.value.classifier.keys)
        v.resetThresholds()
        assertEquals(emptyMap<String, Double>(), hud.settings.value.classifier)
        assertEquals("default", v.until { it.hash == "default" }.hash)
    }

    @Test fun presetsCoverTheRangeAndKeepACustomValue() {
        val o = SignalSettingsViewModel.options("walkVariance", 0.5)
        assertEquals(0.2, o.first(), 1e-9); assertEquals(1.5, o.last(), 1e-9); assertTrue(0.5 in o)
        assertTrue(0.333 in SignalSettingsViewModel.options("walkVariance", 0.333))
    }

    @Test fun theGlassesVerdictFollowsTheReportedHash() {
        val hud = FakeHudSettings(); val v = vm(hud)
        assertEquals("unknown", v.until().glasses)
        reported.value = "default"; assertEquals("match", v.until { it.glasses == "match" }.glasses)
        v.setThreshold("headMovingRadS", 0.7)
        assertEquals("pending", v.until { it.glasses == "pending" }.glasses)
        reported.value = ClassifierThresholds.hash(mapOf("headMovingRadS" to 0.7)); assertEquals("match", v.until { it.glasses == "match" }.glasses)
        assertEquals("pending", GlassesClassifierStatus.verdict("abc", "default"))
        assertEquals("unknown", GlassesClassifierStatus.verdict("abc", null))
    }

    @Test fun signalsCanBeSwitchedOffAndDerivedOnesDeleted() {
        val store = SignalConfigStore(); val v = vm(store = store)
        v.setSignalEnabled("accVar", false)
        assertEquals(false, v.until { u -> u.signals.any { it.first == "accVar" && !it.second } }.signals.first { it.first == "accVar" }.second)
        assertEquals(setOf("accVar"), store.config.value.disabled)
    }

    @Test fun theScreenShowsTheRowsAndTheResetWorks() {
        val hud = FakeHudSettings(); val v = vm(hud)
        hud.update { it.copy(classifier = mapOf("walkVariance" to 0.4)) }
        compose.setContent { PcTheme { SignalSettingsScreen(v.state.collectAsState().value, onBack = {}, onThreshold = v::setThreshold, onReset = v::resetThresholds, onSignal = v::setSignalEnabled, onDeleteDerived = v::deleteDerived) } }
        compose.onNodeWithTag("threshold_walkVariance").assertIsDisplayed()
        compose.onNodeWithTag("signal_gyroMax").assertIsDisplayed()
        compose.onNodeWithTag("thresholds_reset").performClick(); compose.waitForIdle()
        assertEquals(emptyMap<String, Double>(), hud.settings.value.classifier)
    }

    @Test fun theCaptureScreenLinksToTheSignalsPage() {
        var opened = 0
        compose.setContent { PcTheme { CaptureSettingsScreen(CaptureSettingsUiState(), onBack = {}, onOpenSignals = { opened++ }) } }
        compose.onNodeWithTag("capture_settings").performScrollToNode(hasTestTag("signals_link"))
        compose.onNodeWithTag("signals_link").performClick()
        assertEquals(1, opened)
    }
}
