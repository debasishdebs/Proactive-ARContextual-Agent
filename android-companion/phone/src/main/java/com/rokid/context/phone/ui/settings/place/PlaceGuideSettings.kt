package com.rokid.context.phone.ui.settings.place

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rokid.context.memory.place.GeoBox
import com.rokid.context.memory.place.Geohash5
import com.rokid.context.memory.place.PlacePack
import com.rokid.context.memory.place.PackStats
import com.rokid.context.memory.place.UserPlace
import com.rokid.context.phone.ui.settings.ChoiceRow
import com.rokid.context.phone.ui.settings.NoticeText
import com.rokid.context.phone.ui.settings.TextFieldRow
import com.rokid.context.work.frames.place.ParentChoice
import com.rokid.context.work.frames.place.PinForm
import com.rokid.context.work.frames.place.PinRefusal
import com.rokid.context.work.frames.place.PinResult
import com.rokid.context.work.frames.place.PlacePinning
import com.rokid.context.phone.ui.settings.PrimaryAction
import com.rokid.context.phone.ui.settings.SecondaryAction
import com.rokid.context.phone.ui.settings.SettingsCard
import com.rokid.context.phone.ui.settings.SettingsPage
import com.rokid.context.phone.ui.settings.SwitchRow
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The pin dialog: [editingId] null = a new pin at the current position ([parents] are the nearby sites it may hang under), else an edit of that pin. */
data class PinDialogState(val editingId: String? = null, val parents: List<ParentChoice> = emptyList(), val name: String = "", val kindIndex: Int = 0, val radius: String = UserPlace.DEFAULT_RADIUS_M.toString(), val parentIndex: Int = 0, val note: String = "", val error: String? = null)

data class PlaceGuideUiState(
    val enabled: Boolean = false, val seeding: Boolean = false, val message: String? = null, val messageIsError: Boolean = false, val packSummary: String = "",
    val stats: PackStats? = null, val budgetMb: Int = 50, val confirmClear: Boolean = false,
    val pins: List<UserPlace> = emptyList(), val pinBusy: Boolean = false, val pinMessage: String? = null, val pinMessageIsError: Boolean = false, val pinDialog: PinDialogState? = null,
)

/** What the screen needs from the pack and the switch (production: [PlaceGraph], [PlaceGuideSettings]; tests: fakes). */
interface PlaceGuideControl {
    var enabled: Boolean
    suspend fun seed(box: GeoBox): Result<String>
    suspend fun summary(): String
    /** The pack's counts and bytes (null = unavailable). */
    suspend fun stats(): PackStats? = null
    var budgetMb: Int
        get() = 50
        set(@Suppress("UNUSED_PARAMETER") v) {}
    /** Applies [budgetMb] to the open pack now (evicts least-recently-used fetched places). */
    suspend fun applyBudget(): Int = 0
    /** Removes the fetched places; seeded and pinned ones stay. Returns how many went. */
    suspend fun clearFetched(): Int = 0
    /** User pins; null = not available on this build. */
    val pinning: PlacePinning? get() = null
}

object PlaceGuideModel {
    /** Charminar and Golconda (Hyderabad): about 11 km by 15 km, about a dozen 5 km cells. */
    val HYDERABAD = GeoBox(17.34, 78.38, 17.41, 78.52)
    const val SEED_NOTE = "Fetches place text (Wikipedia, Wikidata, OpenStreetMap) for the area while you are online. Only 5 km cell keys leave the phone, never your position."
    val BUDGET_CHOICES_MB: List<Int> = PlacePack.BUDGET_CHOICES_MB
    const val PIN_NOTE = "Pins use your position right now (it is not stored or logged apart from the pin itself) and are refused inside or near a pause zone. The name and note are yours: they stay on this phone, and the note is the card text, labelled \"Your note\". Pins are kept by Delete all, are in the settings export, and are never evicted."
    const val CLEAR_NOTE = "Removes places that were fetched while you walked around. Seeded areas and your pins stay."

    fun mb(bytes: Long): String = String.format(java.util.Locale.ROOT, "%.1f MB", bytes / 1_048_576.0)
    fun kindLabel(kind: String): String = kind.replace('_', ' ').replaceFirstChar { it.uppercase() }

    fun refusalText(why: PinRefusal): String = when (why) {
        PinRefusal.ZONE -> "A pause zone is active or near, or its state is unknown: no place can be pinned here."
        PinRefusal.NO_FRESH_FIX -> "No fresh, accurate position yet. Keep the app open outdoors for a moment and try again."
        PinRefusal.DRAFT_EXPIRED -> "That took too long: your position is no longer fresh. Start again."
        PinRefusal.INVALID -> "Check the name (up to 80 characters), the radius (10 to 1500 m) and the note (up to 140 characters)."
        PinRefusal.NOT_STORED -> "The pin could not be saved."
    }

    const val ZONE_NOTE = "Place cards stay off whenever a pause zone is active or you are near one."

    /** "south, west, north, east" in degrees; null when not four numbers or not a valid box. */
    fun parseBox(south: String, west: String, north: String, east: String): GeoBox? {
        fun d(s: String) = s.trim().toDoubleOrNull()?.takeIf { it.isFinite() }
        val b = GeoBox(d(south) ?: return null, d(west) ?: return null, d(north) ?: return null, d(east) ?: return null)
        return b.takeIf { it.valid && Geohash5.cellsCovering(it).size in 1..PlacePack.MAX_SEED_CELLS }
    }
}

class PlaceGuideViewModel(private val control: PlaceGuideControl, private val work: CoroutineDispatcher = Dispatchers.IO) : ViewModel() {
    private val _state = MutableStateFlow(PlaceGuideUiState(enabled = control.enabled, budgetMb = control.budgetMb))
    val state: StateFlow<PlaceGuideUiState> = _state

    init { refreshSummary() }

    fun setEnabled(on: Boolean) { control.enabled = on; _state.update { it.copy(enabled = on) } }

    fun seed(box: GeoBox?) {
        if (box == null) { _state.update { it.copy(message = "Type four numbers: south, west, north, east (degrees).", messageIsError = true) }; return }
        if (_state.value.seeding) return
        _state.update { it.copy(seeding = true, message = "Fetching places…", messageIsError = false) }
        viewModelScope.launch(work) {
            val r = control.seed(box)
            _state.update { it.copy(seeding = false, message = r.getOrElse { "Could not fetch places. Check the connection and try again." }, messageIsError = r.isFailure) }
            refreshSummary()
        }
    }

    /** One of 25/50/100/200 MB; anything else is ignored. Evicts at once when the pack is over the new budget. */
    fun setBudget(mb: Int) {
        if (mb !in PlaceGuideModel.BUDGET_CHOICES_MB) return
        control.budgetMb = mb
        _state.update { it.copy(budgetMb = mb) }
        viewModelScope.launch(work) {
            val n = runCatching { control.applyBudget() }.getOrDefault(0)
            if (n > 0) _state.update { it.copy(message = "Budget set to $mb MB: $n least recently used places were removed.", messageIsError = false) }
            refreshSummary()
        }
    }

    fun askClear() { _state.update { it.copy(confirmClear = true) } }
    fun cancelClear() { _state.update { it.copy(confirmClear = false) } }
    fun confirmClear() {
        _state.update { it.copy(confirmClear = false) }
        viewModelScope.launch(work) {
            val r = runCatching { control.clearFetched() }
            _state.update { it.copy(message = r.fold({ "Removed $it fetched places. Seeded places and your pins were kept." }, { "Could not clear the fetched places." }), messageIsError = r.isFailure) }
            refreshSummary()
        }
    }

    // ---- user pins ----

    /** "Pin a place here": takes a fresh position (refused inside or near a pause zone, or when the zone state is unknown) and opens the form with the nearby sites. */
    fun beginPin() {
        val pinning = control.pinning ?: return
        if (_state.value.pinBusy) return
        _state.update { it.copy(pinBusy = true, pinMessage = null, pinMessageIsError = false) }
        viewModelScope.launch(work) {
            val r = runCatching { pinning.begin() }.getOrElse { Result.failure(it) }
            r.fold(
                { parents -> _state.update { it.copy(pinBusy = false, pinDialog = PinDialogState(parents = parents)) } },
                { e -> _state.update { it.copy(pinBusy = false, pinMessage = PlaceGuideModel.refusalText((e as? PlacePinning.PinRefused)?.why ?: PinRefusal.NO_FRESH_FIX), pinMessageIsError = true) } },
            )
        }
    }

    fun editPin(id: String) {
        val p = control.pinning?.pins()?.firstOrNull { it.id == id } ?: return
        _state.update { it.copy(pinDialog = PinDialogState(editingId = id, name = p.name, kindIndex = PlacePinning.KINDS.indexOf(p.kind).coerceAtLeast(0), radius = p.radiusM.toString(), note = p.note), pinMessage = null) }
    }

    fun updatePinDialog(f: (PinDialogState) -> PinDialogState) { _state.update { s -> s.copy(pinDialog = s.pinDialog?.let(f)?.copy(error = null)) } }

    fun cancelPin() { control.pinning?.cancel(); _state.update { it.copy(pinDialog = null) } }

    fun savePin() {
        val pinning = control.pinning ?: return
        val d = _state.value.pinDialog ?: return
        val radius = d.radius.trim().toIntOrNull()
        if (radius == null || d.name.isBlank()) { _state.update { it.copy(pinDialog = d.copy(error = PlaceGuideModel.refusalText(PinRefusal.INVALID))) }; return }
        val kind = PlacePinning.KINDS.getOrElse(d.kindIndex) { PlacePinning.KINDS.first() }
        viewModelScope.launch(work) {
            val res = if (d.editingId != null) {
                val old = pinning.pins().firstOrNull { it.id == d.editingId }
                pinning.edit(d.editingId, PinForm(d.name, kind, radius, old?.parentId, d.note))
            } else pinning.create(PinForm(d.name, kind, radius, d.parents.getOrNull(d.parentIndex - 1)?.id, d.note))
            when (res) {
                is PinResult.Saved -> _state.update { it.copy(pinDialog = null, pins = pinning.pins(), pinMessage = "Saved.", pinMessageIsError = false) }
                is PinResult.Refused -> _state.update { s ->
                    // a refused zone or expired position ends the dialog (the position is gone); a bad form stays open to fix
                    if (res.why == PinRefusal.INVALID || res.why == PinRefusal.NOT_STORED) s.copy(pinDialog = d.copy(error = PlaceGuideModel.refusalText(res.why)))
                    else s.copy(pinDialog = null, pinMessage = PlaceGuideModel.refusalText(res.why), pinMessageIsError = true)
                }
            }
            refreshSummary()
        }
    }

    fun deletePin(id: String) {
        val pinning = control.pinning ?: return
        viewModelScope.launch(work) {
            val ok = pinning.delete(id)
            _state.update { it.copy(pins = pinning.pins(), pinMessage = if (ok) "Pin deleted." else "The pin could not be deleted.", pinMessageIsError = !ok) }
            refreshSummary()
        }
    }

    private fun refreshSummary() {
        viewModelScope.launch(work) {
            val s = runCatching { control.summary() }.getOrDefault("")
            val st = runCatching { control.stats() }.getOrNull()
            val pins = runCatching { control.pinning?.pins() }.getOrNull().orEmpty()
            _state.update { it.copy(packSummary = s, stats = st, pins = pins) }
        }
    }
}

@Composable
fun PlaceGuideRoute(control: PlaceGuideControl, back: () -> Unit) {
    val vm = viewModel { PlaceGuideViewModel(control) }
    val state by vm.state.collectAsStateWithLifecycle()
    PlaceGuideScreen(state, back, vm::setEnabled, vm::seed, PlaceGuideActions(vm::setBudget, vm::askClear, vm::confirmClear, vm::cancelClear, vm::beginPin, vm::editPin, vm::deletePin, vm::updatePinDialog, vm::savePin, vm::cancelPin))
}

class PlaceGuideActions(
    val setBudget: (Int) -> Unit = {}, val askClear: () -> Unit = {}, val confirmClear: () -> Unit = {}, val cancelClear: () -> Unit = {},
    val beginPin: () -> Unit = {}, val editPin: (String) -> Unit = {}, val deletePin: (String) -> Unit = {},
    val updatePinDialog: ((PinDialogState) -> PinDialogState) -> Unit = {}, val savePin: () -> Unit = {}, val cancelPin: () -> Unit = {},
)

@Composable
fun PlaceGuideScreen(state: PlaceGuideUiState, back: () -> Unit, onEnabled: (Boolean) -> Unit, onSeed: (GeoBox?) -> Unit, on: PlaceGuideActions = PlaceGuideActions()) {
    var s by remember { mutableStateOf("") }; var w by remember { mutableStateOf("") }; var n by remember { mutableStateOf("") }; var e by remember { mutableStateOf("") }
    SettingsPage("Place Guide", back, tag = "place_guide_settings") {
        item {
            SettingsCard {
                SwitchRow("Place Guide", "Names the monument you are looking at after you pause on it", state.enabled, onEnabled, tag = "place_guide_switch")
                NoticeText(PlaceGuideModel.ZONE_NOTE, tag = "place_guide_zone_note")
                if (state.packSummary.isNotEmpty()) NoticeText(state.packSummary, tag = "place_guide_pack")
            }
        }
        item {
            SettingsCard {
                Text("Pack size and budget", style = MaterialTheme.typography.titleMedium)
                state.stats?.let { st ->
                    NoticeText("${PlaceGuideModel.mb(st.bytes)} of ${st.budgetBytes / 1_048_576} MB used", tag = "pack_size")
                    NoticeText("${st.seeded} seeded, ${st.fetched} fetched, ${st.pinned} pinned", tag = "pack_counts")
                }
                ChoiceRow("Budget", PlaceGuideModel.BUDGET_CHOICES_MB.map { "$it MB" }, PlaceGuideModel.BUDGET_CHOICES_MB.indexOf(state.budgetMb), { on.setBudget(PlaceGuideModel.BUDGET_CHOICES_MB[it]) }, tag = "pack_budget")
                NoticeText("When the pack is over the budget the least recently used fetched places go first. Seeded and pinned places are never removed.", tag = "pack_budget_note")
                NoticeText(PlaceGuideModel.CLEAR_NOTE, tag = "pack_clear_note")
                SecondaryAction("Clear fetched places", on.askClear, tag = "pack_clear")
            }
        }
        item {
            SettingsCard {
                Text("Your pinned places", style = MaterialTheme.typography.titleMedium)
                NoticeText(PlaceGuideModel.PIN_NOTE, tag = "pin_note")
                PrimaryAction("Pin a place here", on.beginPin, tag = "pin_here", enabled = !state.pinBusy)
                state.pinMessage?.let { NoticeText(it, error = state.pinMessageIsError, tag = "pin_message") }
            }
        }
        state.pins.forEach { p ->
            item(key = "pin_${p.id}") {
                SettingsCard {
                    Text(p.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.testTag("pin_name_${p.id}"))
                    NoticeText("${PlaceGuideModel.kindLabel(p.kind)}, ${p.radiusM} m" + if (p.parentId != null) ", part of a site" else "", tag = "pin_detail_${p.id}")
                    if (p.note.isNotEmpty()) NoticeText("Your note: ${p.note}", tag = "pin_note_${p.id}")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SecondaryAction("Edit", { on.editPin(p.id) }, tag = "pin_edit_${p.id}")
                        SecondaryAction("Delete", { on.deletePin(p.id) }, tag = "pin_delete_${p.id}")
                    }
                }
            }
        }
        item {
            SettingsCard {
                Text("Seed this area", style = MaterialTheme.typography.titleMedium)
                NoticeText(PlaceGuideModel.SEED_NOTE, tag = "place_guide_seed_note")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(s, { s = it }, label = { Text("South") }, singleLine = true, modifier = Modifier.weight(1f).testTag("seed_south"))
                    OutlinedTextField(w, { w = it }, label = { Text("West") }, singleLine = true, modifier = Modifier.weight(1f).testTag("seed_west"))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(n, { n = it }, label = { Text("North") }, singleLine = true, modifier = Modifier.weight(1f).testTag("seed_north"))
                    OutlinedTextField(e, { e = it }, label = { Text("East") }, singleLine = true, modifier = Modifier.weight(1f).testTag("seed_east"))
                }
                PrimaryAction("Seed this area", { onSeed(PlaceGuideModel.parseBox(s, w, n, e)) }, tag = "seed_area", enabled = !state.seeding)
                SecondaryAction("Seed Hyderabad (Charminar, Golconda)", { onSeed(PlaceGuideModel.HYDERABAD) }, tag = "seed_hyderabad", enabled = !state.seeding)
                state.message?.let { NoticeText(it, error = state.messageIsError, tag = "place_guide_message") }
            }
        }
    }
    if (state.confirmClear) AlertDialog(
        onDismissRequest = on.cancelClear, modifier = Modifier.testTag("pack_clear_dialog"),
        title = { Text("Clear fetched places?") },
        text = { Text(PlaceGuideModel.CLEAR_NOTE + " They are fetched again when you look at something.") },
        confirmButton = { TextButton(on.confirmClear, Modifier.testTag("pack_clear_confirm")) { Text("Clear") } },
        dismissButton = { TextButton(on.cancelClear, Modifier.testTag("pack_clear_cancel")) { Text("Keep") } },
    )
    state.pinDialog?.let { d -> PinDialog(d, on) }
}

@Composable
private fun PinDialog(d: PinDialogState, on: PlaceGuideActions) {
    AlertDialog(
        onDismissRequest = on.cancelPin, modifier = Modifier.testTag("pin_dialog"),
        title = { Text(if (d.editingId == null) "Pin a place here" else "Edit pin") },
        text = {
            androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextFieldRow("Name (stays on this phone)", d.name, { v -> on.updatePinDialog { it.copy(name = v) } }, tag = "pin_f_name")
                ChoiceRow("Kind", PlacePinning.KINDS.map(PlaceGuideModel::kindLabel), d.kindIndex, { i -> on.updatePinDialog { it.copy(kindIndex = i) } }, tag = "pin_f_kind")
                TextFieldRow("Radius (metres, 10 to 1500)", d.radius, { v -> on.updatePinDialog { it.copy(radius = v) } }, tag = "pin_f_radius", number = true)
                if (d.editingId == null) ChoiceRow("Part of", listOf("No site") + d.parents.map { it.name }, d.parentIndex, { i -> on.updatePinDialog { it.copy(parentIndex = i) } }, tag = "pin_f_parent")
                TextFieldRow("Your note (up to 140 characters, shown on the card)", d.note, { v -> on.updatePinDialog { it.copy(note = v.take(UserPlace.NOTE_MAX)) } }, tag = "pin_f_note", singleLine = false)
                d.error?.let { NoticeText(it, error = true, tag = "pin_f_error") }
            }
        },
        confirmButton = { TextButton(on.savePin, Modifier.testTag("pin_f_save")) { Text("Save") } },
        dismissButton = { TextButton(on.cancelPin, Modifier.testTag("pin_f_cancel")) { Text("Cancel") } },
    )
}

