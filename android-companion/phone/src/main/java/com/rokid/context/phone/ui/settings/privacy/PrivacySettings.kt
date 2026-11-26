package com.rokid.context.phone.ui.settings.privacy

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
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

/** The "Save location with Moments" card: the switch, the count of saved locations and the "Delete saved locations" offer after the switch went off. */
data class MomentLocationUi(val on: Boolean, val count: Int, val offerDelete: Boolean = false) {
    val countLine: String get() = if (count == 1) "1 Moment has a saved location" else "$count Moments have a saved location"
}

/** What the Moment location card needs: the setting (a failed read counts as off), the saved count and the one delete. The route binds the real one. */
interface MomentLocationControl {
    fun isOn(): Boolean
    fun setOn(on: Boolean)
    suspend fun count(): Int
    suspend fun deleteAll(): Int
}

/** The Forget v2 switch (multi-select Forget and the fuller Forget rules). Default ON; a failed read counts as off. */
interface ForgetV2Control {
    fun isOn(): Boolean
    fun setOn(on: Boolean)
}

/** One line on screen; [ids] = every record it stands for (all are resumed by "Resume all shown"). */
data class PauseRow(val id: String, val label: String, val ids: Set<String> = setOf(id))

data class PrivacyUiState(
    val rows: List<PauseRow> = emptyList(),
    val quietEnabled: Boolean = false,
    val fromMinute: Int = PrivacyModel.DEFAULT_FROM,
    val toMinute: Int = PrivacyModel.DEFAULT_TO,
    /** Null where the Moment location setting is not wired (tests, previews): the card is then not shown. */
    val momentLocation: MomentLocationUi? = null,
    /** Null where the switch is not wired: the card is not shown. */
    val forgetV2: Boolean? = null,
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

    /**
     * One row per record, except that all QUIET_HOURS records (the phone's and the glasses' copy of one occurrence,
     * BUG-R2-6) share one row at the first one's place, labelled with the latest end.
     */
    fun rows(pauses: List<PauseRecord>, zone: ZoneId = ZoneId.systemDefault()): List<PauseRow> {
        val quiet = pauses.filter { it.origin == PauseOrigin.QUIET_HOURS }
        val latest = quiet.maxByOrNull { it.untilMs ?: Long.MAX_VALUE }
        return pauses.mapNotNull { r ->
            when {
                r.origin != PauseOrigin.QUIET_HOURS -> PauseRow(r.pauseId, row(r, zone))
                r !== quiet.first() -> null
                else -> PauseRow(r.pauseId, row(latest!!, zone), quiet.map { it.pauseId }.toSet())
            }
        }
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
    private val momentLoc: MomentLocationControl? = null,
    private val forgetV2Control: ForgetV2Control? = null,
) : ViewModel() {
    private data class Draft(val enabled: Boolean, val from: Int, val to: Int)

    private val draft = MutableStateFlow(
        hud.settings.value.quietHours.let { q -> Draft(q != null, q?.startMinute ?: PrivacyModel.DEFAULT_FROM, q?.endMinute ?: PrivacyModel.DEFAULT_TO) },
    )

    private val loc = MutableStateFlow(momentLoc?.let { MomentLocationUi(on = it.isOn(), count = 0) })

    /** The Moment location card's own state (also part of [state]). */
    val locationState: StateFlow<MomentLocationUi?> = loc

    init { refreshLocationCount() }

    private val v2 = MutableStateFlow(forgetV2Control?.isOn())

    fun setForgetV2(on: Boolean) { val c = forgetV2Control ?: return; c.setOn(on); v2.value = on }

    val state: StateFlow<PrivacyUiState> = combine(control.activePauses, draft, loc, v2) { pauses, d, l, f ->
        PrivacyUiState(PrivacyModel.rows(pauses), d.enabled, d.from, d.to, l, f)
    }.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000),
        PrivacyUiState(quietEnabled = draft.value.enabled, fromMinute = draft.value.from, toMinute = draft.value.to, momentLocation = loc.value, forgetV2 = v2.value),
    )

    private fun refreshLocationCount() {
        val c = momentLoc ?: return
        viewModelScope.launch(work) { val n = runCatching { c.count() }.getOrDefault(0); loc.value = loc.value?.copy(count = n) }
    }

    /** Switching OFF stops new saves at once and offers to delete what is saved; switching ON just saves from now on. */
    fun setMomentLocation(on: Boolean) {
        val c = momentLoc ?: return
        c.setOn(on)
        val hadRows = (loc.value?.count ?: 0) > 0
        loc.value = loc.value?.copy(on = on, offerDelete = !on && hadRows)
        refreshLocationCount()
    }

    fun keepSavedLocations() { loc.value = loc.value?.copy(offerDelete = false) }

    fun deleteSavedLocations() {
        val c = momentLoc ?: return
        loc.value = loc.value?.copy(offerDelete = false)
        viewModelScope.launch(work) { runCatching { c.deleteAll() }; refreshLocationCount() }
    }

    fun pauseFor(minutes: Int?) = control.pauseUntil(minutes?.let { clock() + it * 60_000L })

    /** Exactly the ids on screen (spec §5.5: a Resume never clears pauses its sender did not know). */
    fun resumeShown() {
        val ids = state.value.rows.flatMap { it.ids }.toSet()
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
    val openZones: () -> Unit = {},
    val momentLocation: (Boolean) -> Unit = {},
    val keepLocations: () -> Unit = {},
    val deleteLocations: () -> Unit = {},
    val forgetV2: (Boolean) -> Unit = {},
) {
    companion object {
        fun of(vm: PrivacyViewModel, back: () -> Unit, openZones: () -> Unit = {}) = PrivacyCallbacks(
            back, vm::pauseFor, vm::resumeShown, vm::setQuietEnabled, vm::setQuietFrom, vm::setQuietTo, vm::saveQuietHours, openZones,
            vm::setMomentLocation, vm::keepSavedLocations, vm::deleteSavedLocations, vm::setForgetV2,
        )
    }
}

/** Settings › Privacy route: the VM over the shared pause control ([SettingsDeps.pauses]) and the hub's HUD settings. */
@Composable
fun PrivacyRoute(deps: SettingsDeps, back: () -> Unit, openZones: () -> Unit = {}) {
    val vm = viewModel { PrivacyViewModel(deps.pauses, deps.hud, momentLoc = deps.momentLocation, forgetV2Control = deps.forgetV2) }
    val state by vm.state.collectAsStateWithLifecycle()
    PrivacyScreen(state, PrivacyCallbacks.of(vm, back, openZones))
}

@Composable
fun PrivacyScreen(state: PrivacyUiState, on: PrivacyCallbacks) {
    if (state.momentLocation?.offerDelete == true) {
        AlertDialog(
            onDismissRequest = on.keepLocations, modifier = Modifier.testTag("moment_location_dialog"),
            title = { Text("Delete saved locations?") },
            text = { Text("New Moments will not save a location. The ones already saved stay until you delete them. Deleting does not change the Moments or their photos.") },
            confirmButton = { TextButton(on.deleteLocations, Modifier.testTag("moment_location_delete")) { Text("Delete saved locations") } },
            dismissButton = { TextButton(on.keepLocations, Modifier.testTag("moment_location_keep")) { Text("Keep them") } },
        )
    }
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
        state.momentLocation?.let { l ->
            item {
                SettingsCard {
                    SwitchRow("Save location with Moments", "From a fix the phone already has. Never inside a pause zone. Not exported or sent.", l.on, on.momentLocation, tag = "moment_location_switch")
                    NoticeText(l.countLine, tag = "moment_location_count")
                }
            }
        }
        state.forgetV2?.let { f ->
            item {
                SettingsCard {
                    SwitchRow("Select several to Forget", "Long-press in the Activity lists to select items and Forget them together. Also applies the fuller Forget rules: answers, history and Scenes built from forgotten items are cleaned up too. Turn off to go back to the earlier behaviour.", f, on.forgetV2, tag = "forget_v2_switch")
                }
            }
        }
        item {
            SettingsCard {
                Text("Pause zones", style = MaterialTheme.typography.titleMedium)
                Text("Capture stops inside the areas you mark on a map, and in a ring around them.", style = MaterialTheme.typography.bodyMedium, color = PcColors.InkSoft)
                SecondaryAction("Open pause zones", on.openZones, tag = "open_zones")
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

/** The production [MomentLocationControl]: the Developer flag file (`pc_dev`) for the switch, the one graph for the count and the delete. */
class AppMomentLocation(private val app: android.content.Context) : MomentLocationControl {
    override fun isOn() = com.rokid.context.orchestration.forget.DeveloperFlags.momentLocation(app)
    override fun setOn(on: Boolean) = com.rokid.context.orchestration.forget.DeveloperFlags.setMomentLocation(app, on)
    override suspend fun count() = com.rokid.context.canvas.moment.MomentLocationGraph.count(app)
    override suspend fun deleteAll() = com.rokid.context.canvas.moment.MomentLocationGraph.deleteAll(app)
}

/** The production [ForgetV2Control]: the Developer flag file (`pc_dev`), the same preference the Forget executor reads on every Forget. */
class AppForgetV2(private val app: android.content.Context) : ForgetV2Control {
    override fun isOn() = com.rokid.context.orchestration.forget.DeveloperFlags.forgetV2(app)
    override fun setOn(on: Boolean) = com.rokid.context.orchestration.forget.DeveloperFlags.setForgetV2(app, on)
}
