package com.rokid.context.phone.ui.settings.place

import androidx.compose.foundation.clickable
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
data class PinDialogState(val editingId: String? = null, val parents: List<ParentChoice> = emptyList(), val name: String = "", val kindIndex: Int = 0, val radius: String = UserPlace.DEFAULT_RADIUS_M.toString(), val parentIndex: Int = 0, val note: String = "", val error: String? = null,
    /** Slice 1.4.2: the reference Moment attached to a NEW pin (or shown on an edit), its thumbnail source, a hint line, and the two steps of "Save without a Moment". */
    val momentId: String? = null, val moment: MomentChoice? = null, val momentHint: String? = null, val askSkipMoment: Boolean = false, val skipMoment: Boolean = false)

/** A photo Moment as the pin screen shows it: its id (whatever created it), the photo path for the thumbnail and its capture time. */
data class MomentChoice(val id: String, val path: String, val atMs: Long)

/** The "Pick from recent Moments" list. [pinId] null = for the pin dialog (a new pin), else that existing pin. */
data class MomentPicker(val pinId: String?, val items: List<MomentChoice>, /** Local midnight of the day shown (epoch ms). */ val dayStartMs: Long = 0L, val hasMore: Boolean = false)

data class PlaceGuideUiState(
    val enabled: Boolean = false, val cardSide: com.rokid.context.core.model.PlaceSide = com.rokid.context.core.model.PlaceSide.CENTRE, val developerMode: Boolean = false, val seeding: Boolean = false, val message: String? = null, val messageIsError: Boolean = false, val packSummary: String = "",
    val stats: PackStats? = null, val budgetMb: Int = 50, val confirmClear: Boolean = false,
    val pins: List<UserPlace> = emptyList(), val moments: Map<String, MomentChoice> = emptyMap(), val momentPicker: MomentPicker? = null, val pinBusy: Boolean = false, val pinMessage: String? = null, val pinMessageIsError: Boolean = false, val pinDialog: PinDialogState? = null,
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

/** Roaming data saver: a user-started seed (hundreds of requests) was refused because the network is not free Wi-Fi. */
class SeedNeedsWifiException : Exception("Seeding needs Wi-Fi (data saver). Connect to Wi-Fi and try again.")

/** Model privacy "Offline only": a user-started seed was refused because no place may be fetched from the internet. */
class SeedOfflineOnlyException : Exception("Offline only is on, so places are not fetched. Switch Model privacy to Automatic to seed an area.")

/** The screen text for a failed seed: the Wi-Fi and Offline-only refusals say so, a seed stopped mid-way says "stopped: <reason>", anything else is the generic connection hint. */
internal fun seedFailureText(t: Throwable): String = when {
    t is SeedNeedsWifiException || t is SeedOfflineOnlyException -> t.message.orEmpty()
    t is com.rokid.context.memory.place.SeedStopped -> "Seeding stopped: ${t.reason}. Places fetched so far are kept; seed the area again to finish."
    else -> "Could not fetch places. Check the connection and try again."
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
    /** Slice 1.4.2: the newest photo Moments, newest first, at most [limit] (no text). Empty = none (or not available on this build). */
    suspend fun recentMoments(limit: Int): List<MomentChoice> = emptyList()
    /** The Moments among [ids] that still have a photo (a forgotten one is missing from the map). */
    suspend fun momentsOf(ids: List<String>): Map<String, MomentChoice> = emptyMap()
    /** The photo Moments of one day ([fromMs] inclusive, [toMs] exclusive), newest first, [limit] of them older than [beforeId] (keyset paging, no offset); forgotten ones are never listed. */
    suspend fun momentsBetween(fromMs: Long, toMs: Long, limit: Int, beforeId: String?): List<MomentChoice> = emptyList()
    /** Attaches (or with null removes) the Moment link of an existing pin. */
    suspend fun setMoment(pinId: String, momentId: String?): Boolean = false
    /** Clears the links of Moments that were forgotten (read-time repair; the Forget listener does it at the time). */
    suspend fun sweepMoments() {}
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
        val tail = when { // a partial cell is not a success: it is refetched at a glance after a few minutes (up to an hour) and by the next seed
            r.failed > 0 && r.partial > 0 -> " ${r.failed} of ${r.cells} areas failed and ${r.partial} lack OSM extras, will retry. Seed again when online to retry now."
            r.failed > 0 -> " ${r.failed} of ${r.cells} areas failed, will retry. Seed again when online to retry now."
            r.partial > 0 -> " ${r.partial} of ${r.cells} areas lack OSM extras, will retry. Seed again to retry now."
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
        PinRefusal.MOMENT_GONE -> "That Moment is gone, pick another or save without a Moment."
    }

    /** "Attach latest Moment" takes the newest photo Moment only when it is at most this old. */
    const val LATEST_WINDOW_MS = 30 * 60_000L
    const val PICK_LIMIT = 20
    const val PICK_PAGE = 30
    const val NO_MOMENT_YET = "No Moment yet: capture one with the glasses first."
    const val NEEDS_MOMENT = "Needs a Moment"
    const val NO_MOMENT_NOTE = "No reference Moment. The pin still works by location."
    const val ATTACH_FIRST = "Attach a Moment first (the latest one, or pick from recent Moments), so you can check the capture later."
    const val MOMENT_NOTE = "A Moment is only a reference to check what was captured here: it does not change which place is named. If you Forget that Moment, or Delete all, the pin is deleted with it (you are asked first). If the Moment only expires, the link goes and the pin stays with \"Needs a Moment\"."

    /** The newest Moment of [recent] (newest first) that is at most [LATEST_WINDOW_MS] old at [now]; null = none. */
    fun latestWithin(recent: List<MomentChoice>, now: Long): MomentChoice? = recent.firstOrNull { now - it.atMs <= LATEST_WINDOW_MS }

    fun dayLabel(dayStartMs: Long): String = java.text.SimpleDateFormat("EEE d MMM", java.util.Locale.getDefault()).format(java.util.Date(dayStartMs))

    fun momentTime(atMs: Long): String = java.text.SimpleDateFormat("d MMM HH:mm", java.util.Locale.getDefault()).format(java.util.Date(atMs))

    /** A pin shows the "Needs a Moment" badge when it has no link, or its Moment is no longer there (Forgotten). */
    fun needsMoment(p: UserPlace, moments: Map<String, MomentChoice>): Boolean = p.momentId == null || p.momentId !in moments

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

class PlaceGuideViewModel(private val control: PlaceGuideControl, private val work: CoroutineDispatcher = Dispatchers.IO, developerMode: Boolean = false, private val now: () -> Long = System::currentTimeMillis, private val zone: java.time.ZoneId = java.time.ZoneId.systemDefault()) : ViewModel() {
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
            _state.update { it.copy(seeding = false, message = r.fold({ o -> o.message }, { seedFailureText(it) }), messageIsError = r.isFailure) }
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
                failure != null -> seedFailureText(failure!!)
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
        _state.update { it.copy(pinDialog = PinDialogState(editingId = id, name = p.name, kindIndex = PlacePinning.KINDS.indexOf(p.kind).coerceAtLeast(0), radius = p.radiusM.toString(), note = p.note, momentId = p.momentId, moment = p.momentId?.let { m -> it.moments[m] }), pinMessage = null) }
    }

    fun updatePinDialog(f: (PinDialogState) -> PinDialogState) { _state.update { s -> s.copy(pinDialog = s.pinDialog?.let(f)?.copy(error = null)) } }

    override fun onCleared() { control.pinning?.cancel(); super.onCleared() }

    fun cancelPin() { control.pinning?.cancel(); _state.update { it.copy(pinDialog = null) } }

    fun savePin() {
        val pinning = control.pinning ?: return
        val d = _state.value.pinDialog ?: return
        val radius = d.radius.trim().toIntOrNull()
        if (radius == null || d.name.isBlank()) { _state.update { it.copy(pinDialog = d.copy(error = PlaceGuideModel.refusalText(PinRefusal.INVALID))) }; return }
        // Slice 1.4.2: a NEW pin needs a Moment unless the user explicitly chose "Save without a Moment" (an edit of an existing pin never blocks)
        if (d.editingId == null && d.momentId == null && !d.skipMoment) { _state.update { it.copy(pinDialog = d.copy(error = PlaceGuideModel.ATTACH_FIRST)) }; return }
        val kind = PlacePinning.KINDS.getOrElse(d.kindIndex) { PlacePinning.KINDS.first() }
        viewModelScope.launch(work) {
            val res = if (d.editingId != null) {
                val old = pinning.pins().firstOrNull { it.id == d.editingId }
                pinning.edit(d.editingId, PinForm(d.name, kind, radius, old?.parentId, d.note, d.momentId))
            } else pinning.create(PinForm(d.name, kind, radius, d.parents.getOrNull(d.parentIndex - 1)?.id, d.note, d.momentId))
            when (res) {
                is PinResult.Saved -> _state.update { it.copy(pinDialog = null, pins = pinning.pins(), pinMessage = "Saved.", pinMessageIsError = false) }.also { refreshSummary() }
                is PinResult.Refused -> _state.update { s ->
                    // a refused zone or expired position ends the dialog (the position is gone); a bad form stays open to fix
                    if (res.why == PinRefusal.MOMENT_GONE) s.copy(pinDialog = d.copy(momentId = null, moment = null, error = PlaceGuideModel.refusalText(res.why))) // the Moment was forgotten while the dialog was open
                    else if (res.why == PinRefusal.INVALID || res.why == PinRefusal.NOT_STORED) s.copy(pinDialog = d.copy(error = PlaceGuideModel.refusalText(res.why)))
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

    // ---- slice 1.4.2: the pin's reference Moment ----

    /** "Attach latest Moment": the newest photo Moment, if it is at most 30 minutes old. [pinId] null = the open pin dialog (a new pin). */
    fun attachLatest(pinId: String?) {
        viewModelScope.launch(work) {
            val recent = runCatching { control.recentMoments(PlaceGuideModel.PICK_LIMIT) }.getOrDefault(emptyList())
            val m = PlaceGuideModel.latestWithin(recent, now())
            if (m != null) applyMoment(pinId, m)
            else momentHint(pinId, if (recent.isEmpty()) PlaceGuideModel.NO_MOMENT_YET else "No Moment in the last 30 minutes. Pick from recent Moments.")
        }
    }

    /** "Pick from recent Moments": opens the list of the last 20 photo Moments (thumbnail and time). */
    fun openMomentPicker(pinId: String?) { loadDay(pinId, dayStart(now()), append = false) }

    /** The picker's day selector: [delta] = -1 previous day, +1 next day (never past today). */
    fun pickerDay(delta: Int) {
        val pk = _state.value.momentPicker ?: return
        val day = java.time.Instant.ofEpochMilli(pk.dayStartMs).atZone(zone).toLocalDate().plusDays(delta.toLong())
        if (day.isAfter(java.time.Instant.ofEpochMilli(now()).atZone(zone).toLocalDate())) return
        loadDay(pk.pinId, day.atStartOfDay(zone).toInstant().toEpochMilli(), append = false)
    }

    /** "Load more": the next page of the same day. */
    fun pickerMore() { val pk = _state.value.momentPicker ?: return; if (pk.hasMore) loadDay(pk.pinId, pk.dayStartMs, append = true) }

    private fun dayStart(ms: Long): Long = java.time.Instant.ofEpochMilli(ms).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()

    /** Each load takes a generation number; a result is applied only if it is still the newest and the picker still shows the day it was started for, so a slow earlier load never overwrites a later day. */
    private val pickerGen = java.util.concurrent.atomic.AtomicInteger(0)

    private fun loadDay(pinId: String?, dayStartMs: Long, append: Boolean) {
        val gen = pickerGen.incrementAndGet()
        val before = if (append) _state.value.momentPicker?.takeIf { it.dayStartMs == dayStartMs }?.items?.lastOrNull()?.id else null
        if (append && before == null) return
        viewModelScope.launch(work) {
            val end = java.time.Instant.ofEpochMilli(dayStartMs).atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val page = runCatching { control.momentsBetween(dayStartMs, end, PlaceGuideModel.PICK_PAGE + 1, before) }.getOrDefault(emptyList())
            val more = page.size > PlaceGuideModel.PICK_PAGE
            _state.update { s ->
                val cur = s.momentPicker
                if (gen != pickerGen.get() || (append && (cur == null || cur.dayStartMs != dayStartMs))) s // superseded: a newer day was asked for, or the picker closed
                else s.copy(momentPicker = MomentPicker(pinId, (if (append) cur!!.items else emptyList()) + page.take(PlaceGuideModel.PICK_PAGE), dayStartMs, more))
            }
        }
    }

    fun closeMomentPicker() { pickerGen.incrementAndGet(); _state.update { it.copy(momentPicker = null) } }

    fun pickMoment(m: MomentChoice) {
        val target = _state.value.momentPicker ?: return
        pickerGen.incrementAndGet()
        _state.update { it.copy(momentPicker = null) }
        viewModelScope.launch(work) { applyMoment(target.pinId, m) }
    }

    /** "Remove link": the pin stays, it only has no reference Moment any more. */
    fun removeMoment(pinId: String?) {
        if (pinId == null) { _state.update { s -> s.copy(pinDialog = s.pinDialog?.copy(momentId = null, moment = null, momentHint = null)) }; return }
        viewModelScope.launch(work) {
            val ok = runCatching { control.setMoment(pinId, null) }.getOrDefault(false)
            _state.update { it.copy(pinMessage = if (ok) "Moment link removed." else "The link could not be removed.", pinMessageIsError = !ok) }
            refreshSummary()
        }
    }

    /** "Save without a Moment": asks first (a confirm dialog), then saves the new pin flagged "No reference Moment". */
    fun askSkipMoment() { _state.update { s -> s.copy(pinDialog = s.pinDialog?.copy(askSkipMoment = true)) } }
    fun cancelSkipMoment() { _state.update { s -> s.copy(pinDialog = s.pinDialog?.copy(askSkipMoment = false)) } }
    fun confirmSkipMoment() { _state.update { s -> s.copy(pinDialog = s.pinDialog?.copy(askSkipMoment = false, skipMoment = true)) }; savePin() }

    private fun momentHint(pinId: String?, text: String) {
        if (pinId == null) _state.update { s -> s.copy(pinDialog = s.pinDialog?.copy(momentHint = text)) }
        else _state.update { it.copy(pinMessage = text, pinMessageIsError = true) }
    }

    private suspend fun applyMoment(pinId: String?, m: MomentChoice) {
        if (pinId == null) { _state.update { s -> s.copy(pinDialog = s.pinDialog?.copy(momentId = m.id, moment = m, momentHint = null, error = null)) }; return }
        val ok = runCatching { control.setMoment(pinId, m.id) }.getOrDefault(false)
        _state.update { it.copy(pinMessage = if (ok) "Moment attached." else "That Moment is gone: pick another.", pinMessageIsError = !ok) }
        refreshSummaryNow()
    }

    private fun refreshSummary() { viewModelScope.launch(work) { refreshSummaryNow() } }

    private suspend fun refreshSummaryNow() {
        val s = runCatching { control.summary() }.getOrDefault("")
        val st = runCatching { control.stats() }.getOrNull()
        runCatching { control.sweepMoments() }
        val pins = runCatching { control.pinning?.pins() }.getOrNull().orEmpty()
        val moments = runCatching { control.momentsOf(pins.mapNotNull { it.momentId }) }.getOrDefault(emptyMap())
        _state.update { it.copy(packSummary = s, stats = st, pins = pins, moments = moments) }
    }
}

@Composable
fun PlaceGuideRoute(control: PlaceGuideControl, back: () -> Unit, changingConfigurations: () -> Boolean = { false }, developerMode: Boolean = false, pickOnMap: (() -> Unit)? = null) {
    val vm = viewModel { PlaceGuideViewModel(control, developerMode = developerMode) }
    val state by vm.state.collectAsStateWithLifecycle()
    // Leaving the screen drops an unused position and the draft; a rotation (configuration change) is not leaving: the ViewModel (name, note, kind, radius, in memory only, never in a bundle) and the in-memory fix with its timer stay, and Save re-checks the draft's age.
    androidx.compose.runtime.DisposableEffect(vm) { onDispose { if (dropsDraftOnDispose(changingConfigurations())) vm.cancelPin() } }
    PlaceGuideScreen(state, back, vm::setEnabled, vm::seed, PlaceGuideActions(vm::setBudget, vm::askClear, vm::confirmClear, vm::cancelClear, vm::beginPin, vm::editPin, vm::deletePin, vm::updatePinDialog, vm::savePin, vm::cancelPin, vm::seedPreset, vm::setCardSide, vm::attachLatest, vm::openMomentPicker, vm::closeMomentPicker, vm::pickMoment, vm::pickerDay, vm::pickerMore, vm::removeMoment, vm::askSkipMoment, vm::cancelSkipMoment, vm::confirmSkipMoment, pickOnMap))
}

/** A disposed route drops the pin draft unless the disposal is a configuration change (rotation); [PlaceGuideViewModel.onCleared] still cancels when the screen is really left. */
internal fun dropsDraftOnDispose(changingConfigurations: Boolean): Boolean = !changingConfigurations

class PlaceGuideActions(
    val setBudget: (Int) -> Unit = {}, val askClear: () -> Unit = {}, val confirmClear: () -> Unit = {}, val cancelClear: () -> Unit = {},
    val beginPin: () -> Unit = {}, val editPin: (String) -> Unit = {}, val deletePin: (String) -> Unit = {},
    val updatePinDialog: ((PinDialogState) -> PinDialogState) -> Unit = {}, val savePin: () -> Unit = {}, val cancelPin: () -> Unit = {},
    val seedPreset: (SeedPreset) -> Unit = {},
    val setCardSide: (com.rokid.context.core.model.PlaceSide) -> Unit = {},
    val attachLatest: (String?) -> Unit = {}, val openMomentPicker: (String?) -> Unit = {}, val closeMomentPicker: () -> Unit = {}, val pickMoment: (MomentChoice) -> Unit = {},
    val pickerDay: (Int) -> Unit = {}, val pickerMore: () -> Unit = {}, val removeMoment: (String?) -> Unit = {}, val askSkipMoment: () -> Unit = {}, val cancelSkipMoment: () -> Unit = {}, val confirmSkipMoment: () -> Unit = {},
    /** Opens the zones map to draw the box to seed; null hides the button (a build without the map). */
    val pickOnMap: (() -> Unit)? = null,
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
                    MomentSection(p.id, state.moments[p.momentId], PlaceGuideModel.needsMoment(p, state.moments), on)
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
                on.pickOnMap?.let { pick -> SecondaryAction("Pick on the map", pick, tag = "seed_pick_on_map") }
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
    state.momentPicker?.let { pk -> MomentPickerDialog(pk, on) }
}

/** The pin's reference Moment: its thumbnail and time, or the "Needs a Moment" badge, and the attach / pick / remove actions. [pinId] null = inside the pin dialog of a new pin. */
@Composable
private fun MomentSection(pinId: String?, moment: MomentChoice?, needs: Boolean, on: PlaceGuideActions, hint: String? = null) {
    val key = pinId ?: "new"
    androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (moment != null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            com.rokid.context.core.ui.components.FrameThumb(moment.path, Modifier.testTag("pin_moment_thumb_$key"), size = 56.dp)
            NoticeText("Moment ${PlaceGuideModel.momentTime(moment.atMs)}", tag = "pin_moment_time_$key")
        } else if (needs) {
            NoticeText(if (pinId == null) PlaceGuideModel.NO_MOMENT_NOTE else PlaceGuideModel.NEEDS_MOMENT, error = pinId != null, tag = "pin_moment_badge_$key")
        }
        hint?.let { NoticeText(it, tag = "pin_moment_hint_$key") }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryAction("Attach latest Moment", { on.attachLatest(pinId) }, tag = "pin_moment_latest_$key")
            SecondaryAction("Pick from recent Moments", { on.openMomentPicker(pinId) }, tag = "pin_moment_pick_$key")
        }
        if (moment != null) SecondaryAction("Remove link", { on.removeMoment(pinId) }, tag = "pin_moment_remove_$key")
    }
}

@Composable
private fun MomentPickerDialog(pk: MomentPicker, on: PlaceGuideActions) {
    AlertDialog(
        onDismissRequest = on.closeMomentPicker, modifier = Modifier.testTag("pin_moment_picker"),
        title = { Text("Pick a Moment") },
        text = {
            androidx.compose.foundation.layout.Column {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    TextButton({ on.pickerDay(-1) }, Modifier.testTag("pin_moment_day_prev")) { Text("\u2039 Previous") }
                    Text(PlaceGuideModel.dayLabel(pk.dayStartMs), Modifier.testTag("pin_moment_day"))
                    TextButton({ on.pickerDay(1) }, Modifier.testTag("pin_moment_day_next")) { Text("Next \u203A") }
                }
                if (pk.items.isEmpty()) Text("No Moments on this day.", Modifier.testTag("pin_moment_day_empty"))
            androidx.compose.foundation.lazy.LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(pk.items.size) { i ->
                    val m = pk.items[i]
                    Row(Modifier.fillMaxWidth().clickable { on.pickMoment(m) }.testTag("pin_moment_item_${m.id}"), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        com.rokid.context.core.ui.components.FrameThumb(m.path, size = 56.dp)
                        Text(PlaceGuideModel.momentTime(m.atMs))
                    }
                }
                if (pk.hasMore) item { TextButton(on.pickerMore, Modifier.testTag("pin_moment_more")) { Text("Load more") } }
            }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(on.closeMomentPicker, Modifier.testTag("pin_moment_picker_close")) { Text("Close") } },
    )
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
                if (d.editingId == null) {
                    NoticeText(PlaceGuideModel.MOMENT_NOTE, tag = "pin_moment_note")
                    MomentSection(null, d.moment, d.momentId == null, on, d.momentHint)
                }
                d.error?.let { NoticeText(it, error = true, tag = "pin_f_error") }
            }
        },
        confirmButton = { TextButton(on.savePin, Modifier.testTag("pin_f_save")) { Text("Save") } },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (d.editingId == null && d.momentId == null) TextButton(on.askSkipMoment, Modifier.testTag("pin_f_skip_moment")) { Text("Save without a Moment") }
                TextButton(on.cancelPin, Modifier.testTag("pin_f_cancel")) { Text("Cancel") }
            }
        },
    )
    if (d.askSkipMoment) AlertDialog(
        onDismissRequest = on.cancelSkipMoment, modifier = Modifier.testTag("pin_skip_dialog"),
        title = { Text("Save without a Moment?") },
        text = { Text("The pin will be flagged \"Needs a Moment\". It still works by location, and you can attach a Moment later.") },
        confirmButton = { TextButton(on.confirmSkipMoment, Modifier.testTag("pin_skip_confirm")) { Text("Save without") } },
        dismissButton = { TextButton(on.cancelSkipMoment, Modifier.testTag("pin_skip_cancel")) { Text("Back") } },
    )
}

