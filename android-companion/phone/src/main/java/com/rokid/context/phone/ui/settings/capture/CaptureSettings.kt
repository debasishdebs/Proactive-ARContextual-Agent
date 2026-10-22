package com.rokid.context.phone.ui.settings.capture

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rokid.context.core.model.BATTERY_TARGET_HOURS_ALLOWED
import com.rokid.context.core.model.CameraWake
import com.rokid.context.core.model.CaptureMode
import com.rokid.context.core.model.HudSettings
import com.rokid.context.core.model.MotionLabel
import com.rokid.context.core.model.QUICK_LOOK_MS_ALLOWED
import com.rokid.context.core.model.REST_MINUTES_ALLOWED
import com.rokid.context.core.model.WORK_MINUTES_ALLOWED
import com.rokid.context.canvas.plan.ActivePlan
import com.rokid.context.core.model.context.ContextState
import com.rokid.context.core.model.normalized
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Sensors
import com.rokid.context.core.ui.components.ChipRow
import com.rokid.context.core.ui.theme.PcColors
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
import kotlinx.coroutines.launch

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
    val wake: CameraWake = CameraWake.SETTLE,
    val batteryTargetHours: Int = 0,
    val pauseWhenPhoneInUse: Boolean = false,
    val workRestEnabled: Boolean = false,
    val motionLabel: MotionLabel = MotionLabel.NONE,
    val motionSourcesOff: List<String> = emptyList(),
    /** The committed context state in words (Unknown / Resting / Walking / Passenger / Driving / Flying). */
    val contextWord: String = "Unknown",
    val wearParkEnabled: Boolean = true,
    val wearParkUnknownSec: Int = 120,
    val wearParkWornSec: Int = 600,
    /** Hi Rokid's wear signal reached the phone. */
    val wearSignalKnown: Boolean = true,
    /** The signal has been missing for more than [WearGuide.MISSING_AFTER_MS]. */
    val wearMissingLong: Boolean = false,
    /** The active canvas capture plan (S12): the rows it resolved show "Frozen in canvas plan vN" and the Capture plan row offers "Use settings". Null when settings run the capture. */
    val plan: ActivePlan? = null,
) {
    /** 0 Smart, 1 Settle, 2 Fixed. */
    val modeIndex: Int get() = when {
        mode == CaptureMode.FIXED -> 2
        wake == CameraWake.SETTLE -> 1
        else -> 0
    }

    /** "Smart · N keyframes in the last 10 min": the hourly count scaled to 10 min. */
    val statusLine: String get() {
        val n = Math.round(keyframesPerHour / 6.0).toInt()
        return "${listOf("Smart", "Settle", "Fixed")[modeIndex]} \u00b7 $n keyframe${if (n == 1) "" else "s"} in the last 10 min"
    }
}

class CaptureSettingsViewModel(
    private val hud: HudSettingsControl,
    private val pace: AiCallPaceStore = AiCallPaceStore(),
    private val keyframes: StateFlow<KeyframeStats> = MutableStateFlow(KeyframeStats()),
    private val context: kotlinx.coroutines.flow.Flow<ContextState> = MutableStateFlow(ContextState.UNKNOWN),
    /** Phone clock ms since Hi Rokid's wear signal went missing; null = available. */
    private val wearUnknownSince: StateFlow<Long?> = MutableStateFlow(null),
    private val clock: () -> Long = System::currentTimeMillis,
    private val ticker: kotlinx.coroutines.flow.Flow<Unit> = kotlinx.coroutines.flow.flow { while (true) { emit(Unit); kotlinx.coroutines.delay(30_000L) } },
    private val plan: CapturePlanControl = CapturePlanControl.None,
) : ViewModel() {
    private fun of(h: HudSettings, paceMs: Long, kf: KeyframeStats, cs: ContextState, active: ActivePlan?): CaptureSettingsUiState {
        val n = h.normalized()
        return CaptureSettingsUiState(n.captureMode, CaptureIntervals.clamp(n.captureIntervalSec), n.quickLookMs, n.pauseWhenNotWorn, n.captureCue, n.workMinutes, n.restMinutes, paceMs, kf.keyframesPerHour, n.cameraWake, n.batteryTargetHours, n.pauseWhenPhoneInUse, n.workRestEnabled, n.motionLabel, n.motionSourcesOff, ContextSourcesUiState.word(cs), n.wearParkEnabled, n.wearParkUnknownSec, n.wearParkWornSec, plan = active)
    }
    private val wear = combine(wearUnknownSince, ticker) { since, _ -> since }
    val state: StateFlow<CaptureSettingsUiState> = combine(combine(hud.settings, pace.paceMs, keyframes, context, plan.active, ::of), wear) { ui, since ->
        ui.copy(wearSignalKnown = since == null, wearMissingLong = WearGuide.missingLong(since, clock()))
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), of(hud.settings.value, pace.paceMs.value, keyframes.value, ContextState.UNKNOWN, null))

    fun setMode(m: CaptureMode) = hud.update { it.copy(captureMode = m) }
    /** 0 Smart (motion wake), 1 Settle, 2 Fixed (wake untouched). */
    fun setModeChoice(i: Int) = hud.update {
        when (i) {
            0 -> it.copy(captureMode = CaptureMode.SMART, cameraWake = CameraWake.MOTION)
            1 -> it.copy(captureMode = CaptureMode.SMART, cameraWake = CameraWake.SETTLE)
            else -> it.copy(captureMode = CaptureMode.FIXED)
        }
    }
    fun setBatteryTarget(h: Int) = hud.update { it.copy(batteryTargetHours = h).normalized() }
    fun setPauseWhenPhoneInUse(on: Boolean) = hud.update { it.copy(pauseWhenPhoneInUse = on) }
    fun setInterval(sec: Int) = hud.update { it.copy(captureIntervalSec = CaptureIntervals.clamp(sec)) }
    /** Kept for callers that still toggle the legacy field; the protocol ignores it. */
    fun setBurst(on: Boolean) = hud.update { it.copy(burstOnChange = on) }
    fun setQuickLook(ms: Int) = hud.update { it.copy(quickLookMs = ms).normalized() }
    fun setPauseWhenNotWorn(on: Boolean) = hud.update { it.copy(pauseWhenNotWorn = on) }
    fun setWearParkEnabled(on: Boolean) = hud.update { it.copy(wearParkEnabled = on) }
    fun setWearParkUnknownSec(sec: Int) = hud.update { it.copy(wearParkUnknownSec = sec).normalized() }
    fun setWearParkWornSec(sec: Int) = hud.update { it.copy(wearParkWornSec = sec).normalized() }
    fun setCaptureCue(on: Boolean) = hud.update { it.copy(captureCue = on) }
    /** A Work over 45 min raises a lower Rest to 15 ([normalized]). */
    fun setWork(min: Int) = hud.update { it.copy(workMinutes = min).normalized() }
    fun setRest(min: Int) = hud.update { it.copy(restMinutes = min).normalized() }
    fun setWorkRestEnabled(on: Boolean) = hud.update { it.copy(workRestEnabled = on).normalized() }
    fun setMotionLabel(l: MotionLabel) = hud.update { it.copy(motionLabel = l).normalized() }
    /** [on] = the source is used; off ids are stored. glasses_imu is always on (no-op). */
    fun setMotionSource(id: String, on: Boolean) {
        if (id == "glasses_imu") return
        hud.update { it.copy(motionSourcesOff = (it.motionSourcesOff.filter { s -> s != id } + if (on) emptyList() else listOf(id))).normalized() }
    }
    fun setPace(ms: Long) = pace.set(ms)
    /** "Use settings" (S26): the capture overrides are disabled and the settings run the capture again. */
    fun useSettings() { viewModelScope.launch { runCatching { plan.useSettings() } } }
}

@Composable
fun CaptureSettingsScreen(
    state: CaptureSettingsUiState, onBack: () -> Unit,
    onModeChoice: (Int) -> Unit = {}, onBatteryTarget: (Int) -> Unit = {}, onPauseWhenPhoneInUse: (Boolean) -> Unit = {}, onInterval: (Int) -> Unit = {}, onQuickLook: (Int) -> Unit = {},
    onPauseWhenNotWorn: (Boolean) -> Unit = {}, onCaptureCue: (Boolean) -> Unit = {}, onWork: (Int) -> Unit = {}, onRest: (Int) -> Unit = {}, onPace: (Long) -> Unit = {},
    onWorkRestEnabled: (Boolean) -> Unit = {}, onMotionLabel: (MotionLabel) -> Unit = {}, onOpenContext: () -> Unit = {}, onUseSettings: () -> Unit = {},
    onWearParkEnabled: (Boolean) -> Unit = {}, onWearParkUnknown: (Int) -> Unit = {}, onWearParkWorn: (Int) -> Unit = {}, onOpenHiRokid: (() -> Unit)? = null,
) {
    val options = CaptureIntervals.options(state.intervalSec)
    val longWork = state.workMinutes > 45
    SettingsPage("Capture schedule", onBack, tag = "capture_settings") {
        state.plan?.let { plan ->
            item {
                SettingsCard(Modifier.testTag("capture_plan")) {
                    androidx.compose.foundation.layout.Box(Modifier.testTag("capture_plan_row")) {
                        ChipRow(Icons.Outlined.Sensors, PcColors.ChipSky, plan.settingsRow, "Tap to run the capture from these settings again", onClick = onUseSettings)
                    }
                }
            }
        }
        item {
            SettingsCard {
                Text("How the glasses take photos", style = MaterialTheme.typography.titleMedium)
                NoticeText(state.statusLine, tag = "capture_status")
                ChoiceRow("Capture mode", listOf("Smart", "Settle", "Fixed"), state.modeIndex, onModeChoice, tag = "mode")
                state.plan?.let { NoticeText(it.frozenLabel, tag = "frozen_mode") }
                if (state.mode == CaptureMode.FIXED) {
                    ChoiceRow("Every", options.map(CaptureIntervals::label), options.indexOf(state.intervalSec), { onInterval(options[it]) }, tag = "interval")
                    state.plan?.let { NoticeText(it.frozenLabel, tag = "frozen_interval") }
                    NoticeText("Doubled automatically when the glasses battery is low.")
                } else if (state.wake == CameraWake.SETTLE) {
                    NoticeText("Photos only when your head settles on something (about 1.5\u20132 s). Uses much less battery.", tag = "settle_note")
                } else {
                    NoticeText("Smart takes photos when you look at something new. Needs the latest glasses app; older glasses keep taking photos at a fixed interval.", tag = "smart_note")
                    ChoiceRow("Quick look", QUICK_LOOK_MS_ALLOWED.map(CaptureLabels::quickLook), QUICK_LOOK_MS_ALLOWED.indexOf(state.quickLookMs), { onQuickLook(QUICK_LOOK_MS_ALLOWED[it]) }, tag = "quick_look")
                    NoticeText("Shorter catches quicker glances; the camera light is on more.", tag = "quick_look_note")
                    state.plan?.let { NoticeText(it.frozenLabel, tag = "frozen_quick_look") }
                }
                ChoiceRow("Battery target", BATTERY_TARGET_HOURS_ALLOWED.map { if (it == 0) "Off" else "$it h" }, BATTERY_TARGET_HOURS_ALLOWED.indexOf(state.batteryTargetHours), { onBatteryTarget(BATTERY_TARGET_HOURS_ALLOWED[it]) }, tag = "battery_target")
                NoticeText("Takes fewer photos when the glasses battery would not last this long.")
                SwitchRow("Pause when not worn", null, state.pauseWhenNotWorn, onPauseWhenNotWorn, tag = "pause_not_worn")
                SwitchRow("Pause while I use my phone", null, state.pauseWhenPhoneInUse, onPauseWhenPhoneInUse, tag = "pause_phone_use")
            }
        }
        item { WearDetectionCard(state, onWearParkEnabled, onWearParkUnknown, onWearParkWorn, onOpenHiRokid) }
        item {
            SettingsCard {
                Text("Work and rest", style = MaterialTheme.typography.titleMedium)
                SwitchRow("Work and rest", "Off: capture never rests. On: rest captures glances only, at most 2 photos a minute.", state.workRestEnabled, onWorkRestEnabled, tag = "work_rest")
                state.plan?.let { NoticeText(it.frozenLabel, tag = "frozen_work_rest") }
                if (state.workRestEnabled) {
                    ChoiceRow("Work", WORK_MINUTES_ALLOWED.map { "$it min" }, WORK_MINUTES_ALLOWED.indexOf(state.workMinutes), { onWork(WORK_MINUTES_ALLOWED[it]) }, tag = "work")
                    val locked = if (longWork) REST_MINUTES_ALLOWED.indices.filter { REST_MINUTES_ALLOWED[it] < 15 }.toSet() else emptySet()
                    ChoiceRow("Rest", REST_MINUTES_ALLOWED.map { "$it min" }, REST_MINUTES_ALLOWED.indexOf(state.restMinutes), { onRest(REST_MINUTES_ALLOWED[it]) }, tag = "rest",
                        disabled = locked, disabledReason = "needs Work of 45 min or less")
                    if (longWork) NoticeText("Rest is at least 15 min when Work is over 45 min.", tag = "rest_note")
                }
                SwitchRow("Capture blink on HUD", null, state.captureCue, onCaptureCue, tag = "capture_cue")
            }
        }
        item {
            SettingsCard(Modifier.testTag("motion_sources")) {
                Text("Motion sources", style = MaterialTheme.typography.titleMedium)
                SwitchRow("Glasses motion sensor", "Always on", true, {}, tag = "source_glasses_imu", enabled = false)
                androidx.compose.foundation.layout.Box(Modifier.testTag("context_sources_link")) {
                    ChipRow(Icons.Outlined.Sensors, PcColors.ChipSky, "Phone, car, watch and photo context", state.contextWord, onClick = onOpenContext)
                }
                Text("Motion labels (training)", style = MaterialTheme.typography.titleMedium)
                val labels = listOf("None", "Sitting", "Glancing", "Chores", "Eating", "Walking", "Not worn")
                ChoiceRow("Motion label", labels, MotionLabel.entries.indexOf(state.motionLabel), { onMotionLabel(MotionLabel.entries[it]) }, tag = "motion_label")
                if (state.motionLabel != MotionLabel.NONE) NoticeText("Recording motion features on the glasses. Set back to None when done.", tag = "motion_label_note")
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
