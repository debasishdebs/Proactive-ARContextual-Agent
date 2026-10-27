package com.rokid.context.phone.ui.settings.capture

import android.app.Application
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.capture.plan.FusionSettings
import com.rokid.context.core.ui.theme.PcTheme
import com.rokid.context.phone.ui.settings.FakeHudSettings
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** v0.9.7 B item 2: Settings > Capture > Signals > Phone fusion: five presets + the custom value per key, clamped, "Back to defaults". */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h2400dp")
class FusionSettingsUiTest {
    @get:Rule val compose = createComposeRule()
    private fun vm(f: FusionSettingsStore = FusionSettingsStore()) = SignalSettingsViewModel(FakeHudSettings(), SignalConfigStore(), MutableStateFlow<String?>(null), f)

    @Test fun defaultsShowEveryKeyAtItsDefault() {
        val ui = vm().snapshot()
        assertEquals(FusionSettings.DEFAULTS, ui.fusion)
    }

    @Test fun anEditIsClampedAndStoredAndResetClearsIt() {
        val store = FusionSettingsStore(); val v = vm(store)
        v.setFusion("fastKmh", 99.0)
        assertEquals(mapOf("fastKmh" to 40.0), store.overrides.value)
        assertEquals(40.0, v.snapshot().fusion.getValue("fastKmh"), 0.0)
        v.setFusion("stillFrac", 0.9)
        v.resetFusion()
        assertTrue(store.overrides.value.isEmpty()); assertEquals(FusionSettings.DEFAULTS, v.snapshot().fusion)
    }

    @Test fun theScreenShowsARowPerKeyAndTheResetWorks() {
        val store = FusionSettingsStore(); val v = vm(store); store.set("fastKmh", 30.0)
        compose.setContent { PcTheme { SignalSettingsScreen(v.state.collectAsState().value, onBack = {}, onFusion = v::setFusion, onFusionReset = v::resetFusion) } }
        for (k in FusionSettings.DEFAULTS.keys) { compose.onNodeWithTag("signal_settings").performScrollToNode(hasTestTag("fusion_$k")); compose.onNodeWithTag("fusion_$k").assertIsDisplayed() }
        compose.onNodeWithTag("signal_settings").performScrollToNode(hasTestTag("fusion_reset"))
        compose.onNodeWithTag("fusion_reset").performClick(); compose.waitForIdle()
        assertTrue(store.overrides.value.isEmpty())
    }
}
