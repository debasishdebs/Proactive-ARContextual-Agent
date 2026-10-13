package com.rokid.context.phone.ui.settings.privacy

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.model.HudSettings
import com.rokid.context.core.model.PauseOrigin
import com.rokid.context.core.model.PauseRecord
import com.rokid.context.core.model.QuietHours
import com.rokid.context.core.ui.theme.PcTheme
import com.rokid.context.phone.ui.MainDispatcherRule
import com.rokid.context.phone.ui.cancelScope
import com.rokid.context.phone.ui.settings.FakeHudSettings
import com.rokid.context.phone.ui.settings.HudSettingsControl
import com.rokid.context.phone.ui.status.PipelineControl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class PrivacySettingsTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val compose = createComposeRule()

    private val now = 1_793_000_000_000L
    private val control = RecordingControl()
    private val vms = mutableListOf<PrivacyViewModel>()
    @After fun tearDown() = vms.forEach { it.cancelScope() }

    private fun show(hud: HudSettingsControl = FakeHudSettings()): PrivacyViewModel {
        val vm = PrivacyViewModel(control, hud, clock = { now }, work = main.dispatcher).also { vms += it }
        compose.setContent {
            val s by vm.state.collectAsStateWithLifecycle()
            PcTheme { PrivacyScreen(s, PrivacyCallbacks.of(vm, back = {})) }
        }
        return vm
    }

    @Test fun thirtyMinutesCreatesTimedPhonePause() {
        show()
        compose.onNodeWithText("For 30 minutes").performClick()
        compose.onNodeWithText("For 1 hour").performClick()
        compose.onNodeWithText("Until I resume").performClick()
        assertEquals(listOf(now + 1_800_000, now + 3_600_000, null), control.paused)
    }

    @Test fun quietHoursEqualBoundsBlocked() {
        val hud = CountingHud()
        val vm = show(hud)
        compose.onNodeWithTag("privacy_settings").performScrollToNode(hasTestTag("quiet_switch"))
        compose.onNodeWithTag("quiet_switch").performClick()
        vm.setQuietFrom(7 * 60) // default To is 07:00
        compose.onNodeWithTag("privacy_settings").performScrollToNode(hasText("Start and end must differ"))
        compose.onNodeWithText("Start and end must differ").assertExists()
        compose.onNodeWithTag("privacy_settings").performScrollToNode(hasTestTag("quiet_save"))
        compose.onNodeWithTag("quiet_save").assertIsNotEnabled()
        vm.saveQuietHours()
        compose.waitForIdle()
        assertEquals(0, hud.updates)
    }

    @Test fun quietHoursSaveAndOffClearsSetting() {
        val hud = FakeHudSettings(HudSettings())
        val vm = show(hud)
        compose.onNodeWithTag("privacy_settings").performScrollToNode(hasTestTag("quiet_switch"))
        compose.onNodeWithTag("quiet_switch").performClick()
        compose.onNodeWithTag("privacy_settings").performScrollToNode(hasTestTag("quiet_save"))
        compose.onNodeWithTag("quiet_save").performClick()
        compose.waitForIdle()
        assertEquals(QuietHours(22 * 60, 7 * 60), hud.settings.value.quietHours)
        compose.onNodeWithTag("privacy_settings").performScrollToNode(hasTestTag("quiet_switch"))
        compose.onNodeWithTag("quiet_switch").performClick()
        compose.waitForIdle()
        assertNull(hud.settings.value.quietHours)
        assertEquals(false, vm.state.value.quietEnabled)
    }

    @Test fun activePauseRowsLabelOrigin() {
        control.active.value = listOf(
            PauseRecord("g", PauseOrigin.GLASSES, now, null),
            PauseRecord("p", PauseOrigin.PHONE, now, null),
        )
        show()
        compose.onNodeWithText("On glasses · until you resume").assertExists()
        compose.onNodeWithText("On phone · until you resume").assertExists()
        compose.onNodeWithTag("privacy_settings").performScrollToNode(hasText("Resume all shown"))
        compose.onNodeWithText("Resume all shown").performClick()
        assertEquals(listOf(setOf("g", "p")), control.resumed)
    }

    @Test fun timedRowShowsItsEndInLocalTime() {
        val until = java.time.LocalDateTime.of(2026, 10, 13, 14, 30).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertEquals("Quiet hours · until 14:30", PrivacyModel.row(PauseRecord("q", PauseOrigin.QUIET_HOURS, now, until)))
    }

    /** The battery card reads the policy constants; they must match the code that enforces them. */
    @Test fun batteryCopyMatchesTheEnforcedPolicy() {
        val worker = File("../services/orchestration/src/main/java/com/rokid/context/work/QueueDrainWorker.kt").readText()
        val guard = File("../glasses/src/main/java/com/rokid/context/glasses/capture/FrameGuard.kt").readText()
        assertEquals(Regex("""battery in 0\.\.(\d+) && !charging""").find(worker)!!.groupValues[1].toInt(), BatteryPolicy.PHONE_DEFER_PCT)
        assertEquals(Regex("""LOW_BATTERY_PCT = (\d+)""").find(guard)!!.groupValues[1].toInt(), BatteryPolicy.GLASSES_STOP_PCT)
    }

    private class CountingHud(private val inner: FakeHudSettings = FakeHudSettings()) : HudSettingsControl {
        var updates = 0
        override val settings: StateFlow<HudSettings> get() = inner.settings
        override fun update(transform: (HudSettings) -> HudSettings) { updates++; inner.update(transform) }
    }
}

class RecordingControl : PipelineControl {
    val active = MutableStateFlow<List<PauseRecord>>(emptyList())
    val paused = mutableListOf<Long?>()
    val resumed = mutableListOf<Set<String>>()
    override val enabled: StateFlow<Boolean> = MutableStateFlow(true)
    override val activePauses: StateFlow<List<PauseRecord>> = active
    override fun setEnabled(enabled: Boolean) = Unit
    override fun toggle() = Unit
    override fun pauseUntil(untilMs: Long?) { paused += untilMs }
    override fun resumeIds(ids: Set<String>) { resumed += ids }
}
