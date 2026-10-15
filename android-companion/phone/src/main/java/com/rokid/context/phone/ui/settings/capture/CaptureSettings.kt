package com.rokid.context.phone.ui.settings.capture

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rokid.context.core.model.CaptureMode
import com.rokid.context.core.model.HudSettings
import com.rokid.context.core.model.QUICK_LOOK_MS_ALLOWED
import com.rokid.context.core.model.REST_MINUTES_ALLOWED
import com.rokid.context.core.model.WORK_MINUTES_ALLOWED
import com.rokid.context.core.model.normalized
import com.rokid.context.phone.ui.settings.ChoiceRow
import com.rokid.context.phone.ui.settings.HudSettingsControl
import com.rokid.context.phone.ui.settings.NoticeText
import com.rokid.context.phone.ui.settings.SettingsCard
import com.rokid.context.phone.ui.settings.SettingsPage
import com.rokid.context.phone.ui.settings.SwitchRow
import com.rokid.context.work.frames.KeyframeStats
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** Glasses `CaptureCadence` accepts 30 s – 1 h (Phase 3 ruling); everything shown or saved is clamped to that. */
object CaptureIntervals {
    const val MIN_SEC = 30
    const val MAX_SEC = 3_600
    val PRESETS_SEC = listOf(30, 60, 120, 300, 600, 900, 1_800, 3_600)

    fun clamp(sec: Int): Int = sec.coerceIn(MIN_SEC, MAX_SEC)
    fun label(sec: Int): String = when {
        sec >= 3_600 && sec % 3_600 == 0 -> "${sec / 3_600} h"
        sec >= 60 && sec % 60 == 0 -> "${sec / 60} min"
        else -> "$sec s"
    }
    /** The presets, plus a stored custom value so it stays selectable. */
    fun options(current: Int): List<Int> = (PRESETS_SEC + clamp(current)).distinct().sorted()
}

/** Quick look labels: 1 s, 1.5 s, 2 s, 3 s. */
object CaptureLabels {
    fun quickLook(ms: Int): String = if (ms % 1_000 == 0) "${ms / 1_000} s" else "${ms / 1_000}.${(ms % 1_000) / 100} s"
    fun pace(ms: Long): String = "${ms / 1_000} s"
}

data class CaptureSettingsUiState(
    val mode: CaptureMode = CaptureMode.SMART,
    val intervalSec: Int = 300,
    val quickLookMs: Int = 1_000,
    val pauseWhenNotWorn: Boolean = true,
    /** Fix wave H: blink a mark on the HUD for each saved keyframe. */
    val captureCue: Boolean = true,
    val workMinutes: Int = 45,
    val restMinutes: Int = 15,
    val paceMs: Long = AiCallPaceStore.DEFAULT_MS,
    /** Keyframes stored in the last hour (the glasses send no SessionStatus, so the phone counts its own). */
    val keyframesPerHour: Int = 0,
) {
    /** "Smart · N keyframes in the last 10 min": the hourly count scaled to 10 min. */
    val statusLine: String get() {
        val n = Math.round(keyframesPerHour / 6.0).toInt()
        return "${if (mode == CaptureMode.SMART) "Smart" else "Fixed"} \u00b7 $n keyframe${if (n == 1) "" else "s"} in the last 10 min"
    }
}

class CaptureSettingsViewModel(
    private val hud: HudSettingsControl,
    private val pace: AiCallPaceStore = AiCallPaceStore(),
    private val keyframes: StateFlow<KeyframeStats> = MutableStateFlow(KeyframeStats()),
) : ViewModel() {
    private fun of(h: HudSettings, paceMs: Long, kf: KeyframeStats): CaptureSettingsUiState {
        val n = h.normalized()
        return CaptureSettingsUiState(n.captureMode, CaptureIntervals.clamp(n.captureIntervalSec), n.quickLookMs, n.pauseWhenNotWorn, n.captureCue, n.workMinutes, n.restMinutes, paceMs, kf.keyframesPerHour)
    }
    val state: StateFlow<CaptureSettingsUiState> = combine(hud.settings, pace.paceMs, keyframes, ::of)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), of(hud.settings.value, pace.paceMs.value, keyframes.value))

    fun setMode(m: CaptureMode) = hud.update { it.copy(captureMode = m) }
    fun setInterval(sec: Int) = hud.update { it.copy(captureIntervalSec = CaptureIntervals.clamp(sec)) }
    /** Kept for callers that still toggle the legacy field; the protocol ignores it. */
    fun setBurst(on: Boolean) = hud.update { it.copy(burstOnChange = on) }
    fun setQuickLook(ms: Int) = hud.update { it.copy(quickLookMs = ms).normalized() }
    fun setPauseWhenNotWorn(on: Boolean) = hud.update { it.copy(pauseWhenNotWorn = on) }
    fun setCaptureCue(on: Boolean) = hud.update { it.copy(captureCue = on) }
    /** A Work over 45 min raises a lower Rest to 15 ([normalized]). */
    fun setWork(min: Int) = hud.update { it.copy(workMinutes = min).normalized() }
    fun setRest(min: Int) = hud.update { it.copy(restMinutes = min).normalized() }
    fun setPace(ms: Long) = pace.set(ms)
}

@Composable
fun CaptureSettingsScreen(
    state: CaptureSettingsUiState, onBack: () -> Unit,
    onMode: (CaptureMode) -> Unit = {}, onInterval: (Int) -> Unit = {}, onQuickLook: (Int) -> Unit = {},
    onPauseWhenNotWorn: (Boolean) -> Unit = {}, onCaptureCue: (Boolean) -> Unit = {}, onWork: (Int) -> Unit = {}, onRest: (Int) -> Unit = {}, onPace: (Long) -> Unit = {},
) {
    val options = CaptureIntervals.options(state.intervalSec)
    val modes = listOf(CaptureMode.SMART, CaptureMode.FIXED)
    val longWork = state.workMinutes > 45
    SettingsPage("Capture schedule", onBack, tag = "capture_settings") {
        item {
            SettingsCard {
                Text("How the glasses take photos", style = MaterialTheme.typography.titleMedium)
                NoticeText(state.statusLine, tag = "capture_status")
                ChoiceRow("Capture mode", listOf("Smart", "Fixed"), modes.indexOf(state.mode), { onMode(modes[it]) }, tag = "mode")
                if (state.mode == CaptureMode.FIXED) {
                    ChoiceRow("Every", options.map(CaptureIntervals::label), options.indexOf(state.intervalSec), { onInterval(options[it]) }, tag = "interval")
                    NoticeText("Doubled automatically when the glasses battery is low.")
                } else {
                    NoticeText("Smart takes photos when you look at something new. Needs the latest glasses app; older glasses keep taking photos at a fixed interval.", tag = "smart_note")
                    ChoiceRow("Quick look", QUICK_LOOK_MS_ALLOWED.map(CaptureLabels::quickLook), QUICK_LOOK_MS_ALLOWED.indexOf(state.quickLookMs), { onQuickLook(QUICK_LOOK_MS_ALLOWED[it]) }, tag = "quick_look")
                    NoticeText("Shorter catches quicker glances; the camera light is on more.", tag = "quick_look_note")
                }
                SwitchRow("Pause when not worn", null, state.pauseWhenNotWorn, onPauseWhenNotWorn, tag = "pause_not_worn")
            }
        }
        item {
            SettingsCard {
                Text("Work and rest", style = MaterialTheme.typography.titleMedium)
                ChoiceRow("Work", WORK_MINUTES_ALLOWED.map { "$it min" }, WORK_MINUTES_ALLOWED.indexOf(state.workMinutes), { onWork(WORK_MINUTES_ALLOWED[it]) }, tag = "work")
                val locked = if (longWork) REST_MINUTES_ALLOWED.indices.filter { REST_MINUTES_ALLOWED[it] < 15 }.toSet() else emptySet()
                ChoiceRow("Rest", REST_MINUTES_ALLOWED.map { "$it min" }, REST_MINUTES_ALLOWED.indexOf(state.restMinutes), { onRest(REST_MINUTES_ALLOWED[it]) }, tag = "rest",
                    disabled = locked, disabledReason = "needs Work of 45 min or less")
                if (longWork) NoticeText("Rest is at least 15 min when Work is over 45 min.", tag = "rest_note")
                SwitchRow("Capture blink on HUD", null, state.captureCue, onCaptureCue, tag = "capture_cue")
            }
        }
        item {
            SettingsCard {
                Text("AI", style = MaterialTheme.typography.titleMedium)
                val paces = AiCallPaceStore.OPTIONS_MS
                ChoiceRow("AI call pace", paces.map(CaptureLabels::pace), paces.indexOf(state.paceMs), { onPace(paces[it]) }, tag = "pace")
                NoticeText("One background AI call per this long.")
            }
        }
    }
}
