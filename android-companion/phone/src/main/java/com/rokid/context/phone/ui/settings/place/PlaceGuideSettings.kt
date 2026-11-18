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
    val enabled: Boolean = false, val cardSide: com.rokid.context.core.model.PlaceSide = com.rokid.context.core.model.PlaceSide.CENTRE, val developerMode: Boolean = false, val seeding: Boolean = false, val message: String? = null, val messageIsError: Boolean = false, val packSummary: String = "",
    val stats: PackStats? = null, val budgetMb: Int = 50, val confirmClear: Boolean = false,
    val pins: List<UserPlace> = emptyList(), val pinBusy: Boolean = false, val pinMessage: String? = null, val pinMessageIsError: Boolean = false, val pinDialog: PinDialogState? = null,
)

/**
 * A named seed area for a trip: one or more [boxes] (seeded one after the other), its distinct 5 km cell count shown before seeding, and the short label that says the box is approximate.
 * [help] is the only rule text the app shows ("Check local photography rules."): the rules themselves are in docs/ only.
 */
data class SeedPreset(val id: String, val name: String, val boxes: List<GeoBox>, val help: String = "") {
    val cellCount: Int get() = boxes.flatMap { Geohash5.cellsCovering(it) }.toSet().size
    val buttonLabel: String get() = "Seed $name ($cellCount cells)"
    val note: String get() = if (help.isEmpty()) APPROXIMATE else "$APPROXIMATE $help"
    companion object { const val APPROXIMATE = "Approximate: UNVERIFIED." }
}

/** One box's seed answer: the screen text and how many cells still failed after the retry (0 = nothing to retry). */
data class SeedOutcome(val message: String, val failedCells: Int = 0)

/** What the screen needs from the pack and the switch (production: [PlaceGraph], [PlaceGuideSettings]; tests: fakes). */
interface PlaceGuideControl {
    var enabled: Boolean
    suspend fun seed(box: GeoBox): Result<SeedOutcome>
    suspend fun summary(): String
    /** The pack's counts and bytes (null = unavailable). */
    suspend fun stats(): PackStats? = null
    /** HUD card position (B2). Not on the wire unless changed from CENTRE. */
    var cardSide: com.rokid.context.core.model.PlaceSide
        get() = com.rokid.context.core.model.PlaceSide.CENTRE
        set(@Suppress("UNUSED_PARAMETER") v) {}
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
    const val SRI_LANKA_HELP = "Check local photography rules."
    /** Charminar and Golconda (Hyderabad): about 11 km by 15 km, about a dozen 5 km cells. */
    val HYDERABAD = GeoBox(17.34, 78.38, 17.41, 78.52)
    /** Ramoji Film City (23 Nov), 17.2543 N 78.6808 E (Wikidata Q1786944), plus or minus 0.01 degree: one 5 km cell (tepfn), outside [HYDERABAD]. */
    val RAMOJI = GeoBox(17.2443, 78.6708, 17.2643, 78.6908)
    /**
     * Trip seed presets (2026-11-18). EVERY box is approximate: UNVERIFIED on the ground. Each is the bounding box of the sites' Wikidata P625 coordinates (read back from wikidata.org on
     * 2026-11-18, ids in the comments) plus 0.01 degree, rounded outward to 3 decimals. That is tighter than the planned 0.05 to 0.1 degree: one 5 km cell is about 0.044 degree, a 0.1 degree
     * box already touches 9 to 16 cells, and a seed of a dozen cells is what Wikipedia's rate limit (HTTP 429) tolerates in about 2 minutes. A preset with distant sites has two boxes,
     * seeded one after the other. Coverage: docs/superpowers/reviews/2026-11/trip-seed-coverage-2026-11-18.md.
     */
    val GANGTOK = SeedPreset("gangtok", "Gangtok and surroundings", listOf(GeoBox(27.278, 88.551, 27.361, 88.638))) // Gangtok Q186141, Enchey Q5375417, Do-drul Chorten Q5285886, Rumtek Q1546234, Banjhakri Falls Q23038925
    val DARJEELING = SeedPreset("darjeeling", "Darjeeling", listOf(GeoBox(27.001, 88.237, 27.059, 88.274), GeoBox(26.984, 88.275, 27.005, 88.296))) // Darjeeling Q169997, Batasia Loop Q4868648, Ghum Monastery Q3320284, Happy Valley Q5653142, Peace Pagoda Q7157413, station Q24949017; Tiger Hill Q16901632
    val WEST_SIKKIM = SeedPreset("west_sikkim", "Pelling/Yuksom (West Sikkim)", listOf(GeoBox(27.29, 88.223, 27.315, 88.263), GeoBox(27.359, 88.198, 27.384, 88.233))) // Pelling Q7161579, Pemayangtse Q7161856; Yuksom Q839046, Khecheopalri Q3214979
    val SIGIRIYA = SeedPreset("sigiriya", "Sri Lanka: Sigiriya/Dambulla", listOf(GeoBox(7.946, 80.749, 7.967, 80.77), GeoBox(7.846, 80.638, 7.867, 80.659)), SRI_LANKA_HELP) // Sigiriya Q272153; Dambulla cave temple Q45690
    val ANURADHAPURA = SeedPreset("anuradhapura", "Sri Lanka: Anuradhapura", listOf(GeoBox(8.325, 80.386, 8.362, 80.421)), SRI_LANKA_HELP) // Anuradhapura Q5724, Ruwanwelisaya Q3534755, Jetavanaramaya Q1961579, Sri Maha Bodhi Q944175
    val POLONNARUWA = SeedPreset("polonnaruwa", "Sri Lanka: Polonnaruwa", listOf(GeoBox(7.923, 80.99, 7.976, 81.015)), SRI_LANKA_HELP) // Polonnaruwa Q394443, Gal Vihara Q5517744, Vatadage Q7225955
    val KANDY = SeedPreset("kandy", "Sri Lanka: Kandy", listOf(GeoBox(7.261, 80.585, 7.304, 80.652)), SRI_LANKA_HELP) // Kandy Q203197, Temple of the Tooth Q289175, Peradeniya botanical garden Q3119056
    val GALLE = SeedPreset("galle", "Sri Lanka: Galle and south coast", listOf(GeoBox(6.017, 80.207, 6.038, 80.228), GeoBox(5.935, 80.419, 5.985, 80.47)), SRI_LANKA_HELP) // Galle Fort Q2507050; Weligama Q3534843, Mirissa Q1345652
    val TRIP_PRESETS: List<SeedPreset> = listOf(GANGTOK, DARJEELING, WEST_SIKKIM, SIGIRIYA, ANURADHAPURA, POLONNARUWA, KANDY, GALLE)
    const val SEED_NOTE = "Fetches place text (Wikipedia, Wikidata, OpenStreetMap) for the area while you are online. Only 5 km cell keys leave the phone, never your position."
    val BUDGET_CHOICES_MB: List<Int> = PlacePack.BUDGET_CHOICES_MB
    const val PIN_NOTE = "Pins use your position right now (it is not stored or logged apart from the pin itself) and are refused inside or near a pause zone. The name and note are yours: they stay on this phone (not in any backup or device transfer; only a settings export you make yourself contains them), and the note is the card text, labelled \"Your note\". Pins are kept by Delete all, are in the settings export, and are never evicted."
    const val CLEAR_NOTE = "Removes places that were fetched while you walked around. Seeded areas and your pins stay."

    /** The seed's answer as the screen says it: cells ok, seeded without OSM extras (Overpass did not answer) and failed (after one retry), then the pack size. */
    fun seedMessage(r: com.rokid.context.memory.place.SeedResult): String {
        val counts = "Cells: ${r.ok + r.skipped} ok, ${r.partial} seeded without OSM extras, ${r.failed} failed" + if (r.retried > 0) " (${r.retried} retried once)." else "."
        val tail = when {
            r.failed > 0 -> " Try again when online."
            r.partial > 0 -> " Seed again later to add the OSM extras."
            else -> ""
        }
        return "$counts ${r.places} places in the pack.$tail"
    }

    fun seedOutcome(r: com.rokid.context.memory.place.SeedResult) = SeedOutcome(seedMessage(r), r.failed)

    fun mb(bytes: Long): String = String.format(java.util.Locale.ROOT, "%.1f MB", bytes / 1_048_576.0)
    fun kindLabel(kind: String): String = kind.replace('_', ' ').replaceFirstChar { it.uppercase() }

    fun refusalText(why: PinRefusal): String = when (why) {
        PinRefusal.ZONE -> "A pause zone is active or near, or its state is unknown: no place can be pinned here."
        PinRefusal.NO_FRESH_FIX -> "No fresh, accurate position yet. Keep the app open outdoors for a moment and try again."
        PinRefusal.DRAFT_EXPIRED -> "That took too long: your position is no longer fresh. Start again."
        PinRefusal.INVALID -> "Check the name (up to 80 characters), the radius (10 to 1500 m) and the note (up to 140 characters)."
        PinRefusal.NOT_STORED -> "The pin could not be saved."
    }

    val SIDE_LABELS = listOf("Left", "Centre", "Right")
    const val SIDE_NOTE = "Where the card sits on the glasses. The visible area of the glasses was not measured: the margins are cautious and the side must be tuned while worn."

    const val ZONE_NOTE = "Place cards stay off whenever a pause zone is active or you are near one."

    /** "south, west, north, east" in degrees; null when not four numbers or not a valid box. */
    fun parseBox(south: String, west: String, north: String, east: String): GeoBox? {
        fun d(s: String) = s.trim().toDoubleOrNull()?.takeIf { it.isFinite() }
        val b = GeoBox(d(south) ?: return null, d(west) ?: return null, d(north) ?: return null, d(east) ?: return null)
        return b.takeIf { it.valid && Geohash5.cellsCovering(it).size in 1..PlacePack.MAX_SEED_CELLS }
    }
}

class PlaceGuideViewModel(private val control: PlaceGuideControl, private val work: CoroutineDispatcher = Dispatchers.IO, developerMode: Boolean = false) : ViewModel() {
    private val _state = MutableStateFlow(PlaceGuideUiState(enabled = control.enabled, cardSide = control.cardSide, developerMode = developerMode, budgetMb = control.budgetMb))
    val state: StateFlow<PlaceGuideUiState> = _state

    init { refreshSummary() }

    /** Where the glasses HUD shows the place card; only offered in Developer mode. */
    fun setCardSide(side: com.rokid.context.core.model.PlaceSide) { control.cardSide = side; _state.update { it.copy(cardSide = side) } }

    fun setEnabled(on: Boolean) { control.enabled = on; _state.update { it.copy(enabled = on) } }

    fun seed(box: GeoBox?) {
        if (box == null) { _state.update { it.copy(message = "Type four numbers: south, west, north, east (degrees).", messageIsError = true) }; return }
        if (_state.value.seeding) return
        _state.update { it.copy(seeding = true, message = "Fetching places…", messageIsError = false) }
        viewModelScope.launch(work) {
            val r = control.seed(box)
            _state.update { it.copy(seeding = false, message = r.fold({ o -> o.message }, { "Could not fetch places. Check the connection and try again." }), messageIsError = r.isFailure) }
            refreshSummary()
        }
    }

    /** Seeds a preset's boxes one after the other (never in parallel: Wikipedia's rate limit), stopping at the first failure or the first box with cells that failed after the retry. */
    fun seedPreset(preset: SeedPreset) {
        if (_state.value.seeding) return
        _state.update { it.copy(seeding = true, message = "Fetching places…", messageIsError = false) }
        viewModelScope.launch(work) {
            val msgs = ArrayList<String>()
            var failure: Throwable? = null
            var stoppedAt = 0 // 1-based box where a cell still failed after its retry
            for ((i, box) in preset.boxes.withIndex()) {
                val r = control.seed(box)
                r.onSuccess { msgs += it.message; if (it.failedCells > 0) stoppedAt = i + 1 }.onFailure { failure = it }
                if (failure != null || stoppedAt > 0) break
            }
            val n = preset.boxes.size
            val text = when {
                failure != null -> "Could not fetch places. Check the connection and try again."
                stoppedAt > 0 -> "Stopped at box $stoppedAt of $n: some cells failed after a retry (the server may be rate limiting). ${msgs.last()} Tap seed again to retry."
                msgs.size == 1 -> msgs[0]
                else -> msgs.withIndex().joinToString(" ") { "Part ${it.index + 1} of ${msgs.size}: ${it.value}" }
            }
            _state.update { it.copy(seeding = false, message = text, messageIsError = failure != null || stoppedAt > 0) }
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

    override fun onCleared() { control.pinning?.cancel(); super.onCleared() }

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
fun PlaceGuideRoute(control: PlaceGuideControl, back: () -> Unit, changingConfigurations: () -> Boolean = { false }, developerMode: Boolean = false) {
    val vm = viewModel { PlaceGuideViewModel(control, developerMode = developerMode) }
    val state by vm.state.collectAsStateWithLifecycle()
    // Leaving the screen drops an unused position and the draft; a rotation (configuration change) is not leaving: the ViewModel (name, note, kind, radius, in memory only, never in a bundle) and the in-memory fix with its timer stay, and Save re-checks the draft's age.
    androidx.compose.runtime.DisposableEffect(vm) { onDispose { if (dropsDraftOnDispose(changingConfigurations())) vm.cancelPin() } }
    PlaceGuideScreen(state, back, vm::setEnabled, vm::seed, PlaceGuideActions(vm::setBudget, vm::askClear, vm::confirmClear, vm::cancelClear, vm::beginPin, vm::editPin, vm::deletePin, vm::updatePinDialog, vm::savePin, vm::cancelPin, vm::seedPreset, vm::setCardSide))
}

/** A disposed route drops the pin draft unless the disposal is a configuration change (rotation); [PlaceGuideViewModel.onCleared] still cancels when the screen is really left. */
internal fun dropsDraftOnDispose(changingConfigurations: Boolean): Boolean = !changingConfigurations

class PlaceGuideActions(
    val setBudget: (Int) -> Unit = {}, val askClear: () -> Unit = {}, val confirmClear: () -> Unit = {}, val cancelClear: () -> Unit = {},
    val beginPin: () -> Unit = {}, val editPin: (String) -> Unit = {}, val deletePin: (String) -> Unit = {},
    val updatePinDialog: ((PinDialogState) -> PinDialogState) -> Unit = {}, val savePin: () -> Unit = {}, val cancelPin: () -> Unit = {},
    val seedPreset: (SeedPreset) -> Unit = {},
    val setCardSide: (com.rokid.context.core.model.PlaceSide) -> Unit = {},
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
        if (state.developerMode) item {
            SettingsCard {
                Text("Card position (Developer)", style = MaterialTheme.typography.titleMedium)
                ChoiceRow("Position", PlaceGuideModel.SIDE_LABELS, state.cardSide.ordinal, { on.setCardSide(com.rokid.context.core.model.PlaceSide.entries[it]) }, tag = "place_card_side")
                NoticeText(PlaceGuideModel.SIDE_NOTE, tag = "place_card_side_note")
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
                SecondaryAction("Seed Ramoji Film City (23 Nov)", { onSeed(PlaceGuideModel.RAMOJI) }, tag = "seed_ramoji", enabled = !state.seeding)
                for (p in PlaceGuideModel.TRIP_PRESETS) {
                    SecondaryAction(p.buttonLabel, { on.seedPreset(p) }, tag = "seed_preset_${p.id}", enabled = !state.seeding)
                    NoticeText(p.note, tag = "seed_preset_note_${p.id}")
                }
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
                TextFieldRow("Name (stays on this phone, never backed up)", d.name, { v -> on.updatePinDialog { it.copy(name = v) } }, tag = "pin_f_name")
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

