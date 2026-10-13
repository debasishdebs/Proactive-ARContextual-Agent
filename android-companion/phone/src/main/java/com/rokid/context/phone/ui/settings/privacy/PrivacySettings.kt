package com.rokid.context.phone.ui.settings.privacy

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rokid.context.core.model.PauseOrigin
import com.rokid.context.core.model.PauseRecord
import com.rokid.context.core.model.QuietHours
import com.rokid.context.core.ui.theme.PcColors
import com.rokid.context.phone.ui.settings.ChoiceRow
import com.rokid.context.phone.ui.settings.HudSettingsControl
import com.rokid.context.phone.ui.settings.NoticeText
import com.rokid.context.phone.ui.settings.PrimaryAction
import com.rokid.context.phone.ui.settings.SecondaryAction
import com.rokid.context.phone.ui.settings.SettingsCard
import com.rokid.context.phone.ui.settings.SettingsDeps
import com.rokid.context.phone.ui.settings.SettingsPage
import com.rokid.context.phone.ui.settings.SwitchRow
import com.rokid.context.phone.ui.status.PipelineControl
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/**
 * The battery thresholds the code enforces (QueueDrainWorker defers background processing at or below the phone's;
 * FrameGuard stops glasses capture at or below the glasses'). :phone can't see either constant; PrivacySettingsTest
 * checks these against those sources so the card never drifts.
 */
object BatteryPolicy {
    const val PHONE_DEFER_PCT = 30
    const val GLASSES_STOP_PCT = 10
    val text = "Processing defers at or below $PHONE_DEFER_PCT % phone battery · glasses stop capture at or below $GLASSES_STOP_PCT %"
}

data class PauseRow(val id: String, val label: String)

data class PrivacyUiState(
    val rows: List<PauseRow> = emptyList(),
    val quietEnabled: Boolean = false,
    val fromMinute: Int = PrivacyModel.DEFAULT_FROM,
    val toMinute: Int = PrivacyModel.DEFAULT_TO,
) {
    val quietError: String? get() = if (quietEnabled && fromMinute == toMinute) PrivacyModel.EQUAL_BOUNDS else null
    val canSave: Boolean get() = quietEnabled && quietError == null
}

object PrivacyModel {
    const val DEFAULT_FROM = 22 * 60
    const val DEFAULT_TO = 7 * 60
    const val STEP = 15
    const val EQUAL_BOUNDS = "Start and end must differ"
    val TIMES: List<String> = (0 until 24 * 60 step STEP).map { hhmm(it) }

    fun hhmm(minute: Int): String = String.format(Locale.US, "%02d:%02d", minute / 60, minute % 60)

    fun origin(o: PauseOrigin): String = when (o) {
        PauseOrigin.GLASSES -> "On glasses"
        PauseOrigin.PHONE -> "On phone"
        PauseOrigin.QUIET_HOURS -> "Quiet hours"
    }

    /** "On glasses · until you resume" / "Quiet hours · until 14:30" (local time, phone clock). */
    fun row(r: PauseRecord, zone: ZoneId = ZoneId.systemDefault()): String {
        val until = r.untilMs?.let { val t = Instant.ofEpochMilli(it).atZone(zone); "until " + hhmm(t.hour * 60 + t.minute) } ?: "until you resume"
        return "${origin(r.origin)} · $until"
    }
}

/**
 * Settings › Privacy (spec §2.4, R-65): timed/indefinite PHONE pauses, the active pauses with one "Resume all shown"
 * (only the ids on screen, spec §5.5), and quiet hours written through the hub's HUD settings.
 */
class PrivacyViewModel(
    private val control: PipelineControl,
    private val hud: HudSettingsControl,
    private val clock: () -> Long = System::currentTimeMillis,
    private val work: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private data class Draft(val enabled: Boolean, val from: Int, val to: Int)

    private val draft = MutableStateFlow(
        hud.settings.value.quietHours.let { q -> Draft(q != null, q?.startMinute ?: PrivacyModel.DEFAULT_FROM, q?.endMinute ?: PrivacyModel.DEFAULT_TO) },
    )

    val state: StateFlow<PrivacyUiState> = combine(control.activePauses, draft) { pauses, d ->
        PrivacyUiState(pauses.map { PauseRow(it.pauseId, PrivacyModel.row(it)) }, d.enabled, d.from, d.to)
    }.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000),
        PrivacyUiState(quietEnabled = draft.value.enabled, fromMinute = draft.value.from, toMinute = draft.value.to),
    )

    fun pauseFor(minutes: Int?) = control.pauseUntil(minutes?.let { clock() + it * 60_000L })

    /** Exactly the ids on screen (spec §5.5: a Resume never clears pauses its sender did not know). */
    fun resumeShown() {
        val ids = state.value.rows.map { it.id }.toSet()
        if (ids.isNotEmpty()) control.resumeIds(ids)
    }

    /** On: shows the pickers (Save writes). Off: clears the setting at once. */
    fun setQuietEnabled(on: Boolean) {
        draft.value = draft.value.copy(enabled = on)
        if (!on) viewModelScope.launch(work) { hud.update { it.copy(quietHours = null) } }
    }

    fun setQuietFrom(minute: Int) { draft.value = draft.value.copy(from = minute) }
    fun setQuietTo(minute: Int) { draft.value = draft.value.copy(to = minute) }

    fun saveQuietHours() {
        val d = draft.value
        if (!d.enabled || d.from == d.to) return
        viewModelScope.launch(work) { hud.update { it.copy(quietHours = QuietHours(d.from, d.to)) } }
    }
}

class PrivacyCallbacks(
    val back: () -> Unit,
    val pauseFor: (Int?) -> Unit,
    val resumeShown: () -> Unit,
    val quietEnabled: (Boolean) -> Unit,
    val quietFrom: (Int) -> Unit,
    val quietTo: (Int) -> Unit,
    val saveQuiet: () -> Unit,
) {
    companion object {
        fun of(vm: PrivacyViewModel, back: () -> Unit) = PrivacyCallbacks(
            back, vm::pauseFor, vm::resumeShown, vm::setQuietEnabled, vm::setQuietFrom, vm::setQuietTo, vm::saveQuietHours,
        )
    }
}

/** Settings › Privacy route: the VM over the shared pause control ([SettingsDeps.pauses]) and the hub's HUD settings. */
@Composable
fun PrivacyRoute(deps: SettingsDeps, back: () -> Unit) {
    val vm = viewModel { PrivacyViewModel(deps.pauses, deps.hud) }
    val state by vm.state.collectAsStateWithLifecycle()
    PrivacyScreen(state, PrivacyCallbacks.of(vm, back))
}

@Composable
fun PrivacyScreen(state: PrivacyUiState, on: PrivacyCallbacks) {
    SettingsPage("Privacy", on.back, tag = "privacy_settings") {
        item {
            SettingsCard {
                Text("Pause capture", style = MaterialTheme.typography.titleMedium)
                SecondaryAction("For 30 minutes", { on.pauseFor(30) }, tag = "pause_30")
                SecondaryAction("For 1 hour", { on.pauseFor(60) }, tag = "pause_60")
                SecondaryAction("Until I resume", { on.pauseFor(null) }, tag = "pause_indefinite")
            }
        }
        item {
            SettingsCard {
                Text("Active pauses", style = MaterialTheme.typography.titleMedium)
                if (state.rows.isEmpty()) NoticeText("Capture is running", tag = "no_pauses")
                state.rows.forEach { r ->
                    Text(r.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("pause_row_${r.id}"))
                }
                SecondaryAction("Resume all shown", on.resumeShown, tag = "resume_shown", enabled = state.rows.isNotEmpty())
            }
        }
        item {
            SettingsCard {
                SwitchRow("Quiet hours", "Capture pauses on phone and glasses", state.quietEnabled, on.quietEnabled, tag = "quiet_switch")
                if (state.quietEnabled) {
                    ChoiceRow("From", PrivacyModel.TIMES, state.fromMinute / PrivacyModel.STEP, { on.quietFrom(it * PrivacyModel.STEP) }, tag = "quiet_from")
                    ChoiceRow("To", PrivacyModel.TIMES, state.toMinute / PrivacyModel.STEP, { on.quietTo(it * PrivacyModel.STEP) }, tag = "quiet_to")
                    state.quietError?.let { NoticeText(it, error = true, tag = "quiet_error") }
                    PrimaryAction("Save", on.saveQuiet, tag = "quiet_save", enabled = state.canSave)
                }
            }
        }
        item {
            SettingsCard {
                Text("Battery policy", style = MaterialTheme.typography.titleMedium)
                Text(BatteryPolicy.text, style = MaterialTheme.typography.bodyMedium, color = PcColors.InkSoft, modifier = Modifier.testTag("battery_policy"))
            }
        }
    }
}
