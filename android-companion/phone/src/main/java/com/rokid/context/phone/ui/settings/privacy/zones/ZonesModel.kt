package com.rokid.context.phone.ui.settings.privacy.zones

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rokid.context.core.model.zone.PauseZone
import com.rokid.context.core.model.zone.ZoneEvaluator
import com.rokid.context.core.model.zone.ZoneFix
import com.rokid.context.core.model.zone.ZonePresets
import com.rokid.context.core.model.zone.ZoneReason
import com.rokid.context.core.model.zone.ZoneState
import com.rokid.context.core.model.zone.ZoneStatus
import com.rokid.context.phone.zone.ZoneChange
import com.rokid.context.phone.zone.ZoneController
import com.rokid.context.phone.zone.ZoneSnapshot
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** What the map screen needs of the zone engine. The UI never writes pauses: it only edits zones through these calls and reads the engine's own judgement. */
interface ZoneEditor {
    val snapshot: StateFlow<ZoneSnapshot>
    /** The saved zones, read from the store: shown while the hub has not started the engine (the snapshot is empty then), so a saved zone never looks absent. */
    fun storedZones(): List<PauseZone>
    /** The newest fix, in memory only (the "you are here" dot); null when none. */
    fun lastFix(): ZoneFix?
    suspend fun upsert(zone: PauseZone): ZoneChange
    suspend fun setEnabled(id: String, enabled: Boolean): ZoneChange
    suspend fun remove(id: String): ZoneChange
    suspend fun addPreset(preset: PauseZone): ZoneChange
}

class ControllerZoneEditor(private val c: ZoneController) : ZoneEditor {
    override val snapshot: StateFlow<ZoneSnapshot> get() = c.snapshot
    override fun storedZones(): List<PauseZone> = c.zones()
    override fun lastFix(): ZoneFix? = c.lastFix()
    override suspend fun upsert(zone: PauseZone) = c.upsert(zone)
    override suspend fun setEnabled(id: String, enabled: Boolean) = c.setEnabled(id, enabled)
    override suspend fun remove(id: String) = c.remove(id)
    override suspend fun addPreset(preset: PauseZone) = c.addPreset(preset)
}

enum class ChipLevel { OFF, CLEAR, WARN, PAUSED }

/** The state chip of one zone: [text] says in words what the zone is doing and why. */
data class ZoneChip(val text: String, val level: ChipLevel)

object ZoneWords {
    const val DENIED_BANNER = "Location is not allowed. While any pause zone is on, capture is PAUSED, because the app cannot tell whether you are inside one. Allow location to let capture run outside the zones."
    /** Owner round 6e: the red banner at the top of every map screen while there is no network. */
    const val NO_NETWORK_BANNER = "No network: the map background is unavailable. Seeding and pause zones need a connection."
    const val OFFLINE_NOTICE = "You are offline. Go online to draw or change zones."
    const val PROTECTED_BANNER = "The saved zones cannot be read, so capture is paused everywhere until they are restored. Nothing can be changed here meanwhile."
    const val NOT_MONITORED = "Not monitored (hub stopped)"
    const val PRESET_LABEL = "Add Jarawa reserve preset (UNVERIFIED, approximate)"
    const val PRESET_NOTE = "The Jarawa box was typed from memory of the road, not measured. Check it on the map and adjust it before relying on it."

    /** Chip text for a zone: the engine's reading in plain words, with the fail-closed reason when capture is held without a fix saying so. */
    fun chip(zone: PauseZone, status: ZoneStatus?, monitoring: Boolean = true): ZoneChip {
        if (!zone.enabled) return ZoneChip("Off: not checked, this zone does not pause capture", ChipLevel.OFF)
        if (!monitoring) return ZoneChip(NOT_MONITORED, ChipLevel.OFF)
        if (status == null) return ZoneChip("Not judged yet: counted as inside, capture is held until the first location check", ChipLevel.PAUSED)
        return when (status.state) {
            ZoneState.OUTSIDE -> ZoneChip("Outside" + away(status), ChipLevel.CLEAR)
            ZoneState.APPROACH -> ZoneChip("Approaching" + away(status) + ": the glasses hold capture if the link drops", ChipLevel.WARN)
            ZoneState.INSIDE -> ZoneChip(
                when (status.reason) {
                    ZoneReason.NONE -> "Inside: capture is paused"
                    ZoneReason.NO_FIX -> "Counted as inside: no location fix yet, capture is paused"
                    ZoneReason.STALE_FIX -> "Counted as inside: the last location fix is too old, capture is paused"
                    ZoneReason.LOCATION_OFF -> "Counted as inside: location is off or denied, capture is paused"
                },
                ChipLevel.PAUSED,
            )
        }
    }

    private fun away(s: ZoneStatus): String = s.distanceM?.let { ", " + distance(it) + " from the box" } ?: ""

    fun distance(m: Double): String = if (m >= 1_000) String.format(Locale.US, "%.1f km", m / 1_000) else "${m.roundToInt()} m"

    /** The refusal or failure text for an edit that did not apply; null for OK. */
    fun refusal(change: ZoneChange, what: String): String? = when (change) {
        ZoneChange.OK -> null
        ZoneChange.BLOCKED_INSIDE -> "Not changed: you are inside this zone, or the app cannot prove you are outside it, and capture is paused by it. A zone cannot be $what while it pauses capture. Leave the area and wait for the state to read Outside."
        ZoneChange.INVALID -> "Those bounds are not valid. Nothing was saved."
        ZoneChange.NOT_FOUND -> "That zone no longer exists."
        ZoneChange.PROTECTED -> PROTECTED_BANNER
        ZoneChange.NOT_READY -> "Zones are not checked yet (the app is still starting). Nothing was changed; try again in a moment."
        ZoneChange.STORE_FAILED -> "The change could not be saved. Nothing was changed."
    }
}

/** The text fields of the zone being drawn or typed. Numbers stay text until Save so half-typed input is never rejected on the spot. */
data class ZoneDraft(
    val name: String = "", val south: String = "", val west: String = "", val north: String = "", val east: String = "",
    val buffer: String = PauseZone.DEFAULT_BUFFER_M.toString(),
) {
    val isEmpty: Boolean get() = name.isEmpty() && south.isEmpty() && west.isEmpty() && north.isEmpty() && east.isEmpty()

    /** The box as typed, when all four numbers parse and are in order; for the dashed outline on the map. */
    fun box(): DoubleArray? {
        val s = south.trim().toDoubleOrNull(); val w = west.trim().toDoubleOrNull(); val n = north.trim().toDoubleOrNull(); val e = east.trim().toDoubleOrNull()
        if (s == null || w == null || n == null || e == null || !(s.isFinite() && w.isFinite() && n.isFinite() && e.isFinite()) || s >= n || w >= e) return null
        return doubleArrayOf(s, w, n, e)
    }
}

/** Draft to zone, with the reason in words when it cannot be one. */
sealed interface DraftResult {
    data class Ok(val zone: PauseZone) : DraftResult
    data class Error(val text: String) : DraftResult
}

object ZoneDrafts {
    fun parse(d: ZoneDraft, id: String): DraftResult {
        if (d.name.isBlank()) return DraftResult.Error("Give the zone a name.")
        val s = d.south.trim().toDoubleOrNull(); val w = d.west.trim().toDoubleOrNull(); val n = d.north.trim().toDoubleOrNull(); val e = d.east.trim().toDoubleOrNull()
        if (s == null || w == null || n == null || e == null || !(s.isFinite() && w.isFinite() && n.isFinite() && e.isFinite())) return DraftResult.Error("Enter all four bounds as decimal degrees, or draw the box on the map.")
        if (s !in -90.0..90.0 || n !in -90.0..90.0) return DraftResult.Error("Latitude must be between -90 and 90.")
        if (w !in -180.0..180.0 || e !in -180.0..180.0) return DraftResult.Error("Longitude must be between -180 and 180.")
        if (s >= n) return DraftResult.Error("South must be less than north.")
        if (w >= e) return DraftResult.Error("West must be less than east. A box that crosses the 180 degree line is not supported.")
        val b = d.buffer.trim().toIntOrNull()
        if (b == null || b !in 0..PauseZone.MAX_BUFFER_M) return DraftResult.Error("Buffer must be a whole number of metres from 0 to 50,000.")
        val zone = PauseZone(id = id, name = d.name.trim(), south = s, west = w, north = n, east = e, bufferM = b)
        return if (zone.isValid()) DraftResult.Ok(zone) else DraftResult.Error("Those bounds are not valid.")
    }

    /** Fills the four bounds from a drag between two corners (any order). A drag too small to be a box is ignored (returns [d] unchanged). */
    fun fromDrag(d: ZoneDraft, a: LatLon, b: LatLon): ZoneDraft {
        val s = min(a.lat, b.lat); val n = max(a.lat, b.lat); val w = min(a.lon, b.lon); val e = max(a.lon, b.lon)
        if (n - s < MIN_SPAN_DEG || e - w < MIN_SPAN_DEG) return d
        return d.copy(south = deg(s), west = deg(w), north = deg(n), east = deg(e))
    }

    fun deg(v: Double): String = String.format(Locale.US, "%.5f", v)

    const val MIN_SPAN_DEG = 0.0005
}

data class ZoneRowUi(val zone: PauseZone, val chip: ZoneChip, val inside: Boolean)

data class ZonesUiState(
    val rows: List<ZoneRowUi> = emptyList(),
    val protectedMode: Boolean = false,
    val draft: ZoneDraft = ZoneDraft(),
    val draftError: String? = null,
    val message: String? = null,
    val messageIsError: Boolean = false,
    val locationGranted: Boolean = false,
    /** The screen should show the system location prompt now (set after a zone was added without permission); [ZonesViewModel.permissionPromptShown] clears it. */
    val requestPermission: Boolean = false,
    val drawing: Boolean = false,
    /** The user's position for the dot, only while a fresh fix exists. Never stored or logged. */
    val you: LatLon? = null,
    val presetAvailable: Boolean = true,
    val pendingDeleteId: String? = null,
) {
    val anyEnabled: Boolean get() = rows.any { it.zone.enabled }
    /** Zones are on but the app may not read location: capture is paused (fail closed). */
    val deniedBanner: Boolean get() = !protectedMode && !locationGranted && anyEnabled
}

class ZonesViewModel(
    private val editor: ZoneEditor,
    private val elapsed: () -> Long = SystemClock::elapsedRealtime,
    private val newId: () -> String = { "zone-" + System.currentTimeMillis().toString(36) },
    private val work: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private data class Local(
        val draft: ZoneDraft = ZoneDraft(), val draftError: String? = null, val message: String? = null, val messageIsError: Boolean = false,
        val granted: Boolean = false, val request: Boolean = false, val drawing: Boolean = false, val pendingDelete: String? = null,
    )

    private val local = MutableStateFlow(Local())

    val state: StateFlow<ZonesUiState> = combine(editor.snapshot, local) { snap, l ->
        // Before the hub starts the engine its snapshot is empty: show the saved zones, unmonitored, never an empty list.
        val rows = (if (snap.monitoring) snap.zones else editor.storedZones()).map { z ->
            val st = snap.evaluation.zones.firstOrNull { it.zoneId == z.id }
            ZoneRowUi(z, ZoneWords.chip(z, st, snap.monitoring), snap.monitoring && z.enabled && (st == null || st.state == ZoneState.INSIDE))
        }
        val fix = editor.lastFix()?.takeIf { elapsed() - it.timeMs <= ZoneEvaluator.STALE_MS }
        ZonesUiState(
            rows, snap.protectedMode, l.draft, l.draftError, l.message, l.messageIsError, l.granted, l.request, l.drawing,
            fix?.let { LatLon(it.lat, it.lon) }, rows.none { it.zone.id == ZonePresets.JARAWA_ID }, l.pendingDelete,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ZonesUiState())

    /** From the screen: whether the app holds a location permission right now. */
    fun setLocationGranted(granted: Boolean) = local.update { it.copy(granted = granted, request = it.request && !granted) }

    fun permissionPromptShown() = local.update { it.copy(request = false) }

    /** The user asks for the system prompt (the banner button). */
    fun requestLocation() = local.update { it.copy(request = true) }

    fun setDrawing(on: Boolean) = local.update { it.copy(drawing = on) }

    fun editDraft(f: (ZoneDraft) -> ZoneDraft) = local.update { it.copy(draft = f(it.draft), draftError = null) }

    /** A drag on the map between two corners fills the bounds and ends draw mode. */
    fun drawn(a: LatLon, b: LatLon) = local.update {
        val next = ZoneDrafts.fromDrag(it.draft, a, b)
        if (next === it.draft) it.copy(drawing = false, message = "That box is too small. Drag a larger area.", messageIsError = true)
        else it.copy(draft = next, draftError = null, drawing = false, message = null)
    }

    fun clearDraft() = local.update { it.copy(draft = ZoneDraft(), draftError = null, drawing = false) }

    fun dismissMessage() = local.update { it.copy(message = null) }

    fun save() {
        val l = local.value
        when (val r = ZoneDrafts.parse(l.draft, newId())) {
            is DraftResult.Error -> local.update { it.copy(draftError = r.text) }
            is DraftResult.Ok -> viewModelScope.launch(work) {
                val change = editor.upsert(r.zone)
                val text = ZoneWords.refusal(change, "replaced")
                local.update {
                    if (text != null) it.copy(message = text, messageIsError = true)
                    else it.copy(draft = ZoneDraft(), draftError = null, message = "Zone \"${r.zone.name}\" added." + permissionNote(it.granted), messageIsError = false, request = !it.granted)
                }
            }
        }
    }

    fun addPreset() = viewModelScope.launch(work) {
        val change = editor.addPreset(ZonePresets.JARAWA)
        val text = ZoneWords.refusal(change, "replaced")
        local.update {
            if (text != null) it.copy(message = text, messageIsError = true)
            else it.copy(message = "Jarawa preset added. It is UNVERIFIED and approximate: check the box on the map." + permissionNote(it.granted), messageIsError = false, request = !it.granted)
        }
    }

    fun setEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch(work) {
            val change = editor.setEnabled(id, enabled)
            val text = ZoneWords.refusal(change, "turned off")
            local.update {
                if (text != null) it.copy(message = text, messageIsError = true)
                else it.copy(message = null, request = enabled && !it.granted)
            }
        }
    }

    fun askDelete(id: String) = local.update { it.copy(pendingDelete = id) }

    fun cancelDelete() = local.update { it.copy(pendingDelete = null) }

    fun confirmDelete() {
        val id = local.value.pendingDelete ?: return
        local.update { it.copy(pendingDelete = null) }
        viewModelScope.launch(work) {
            val text = ZoneWords.refusal(editor.remove(id), "deleted")
            local.update { if (text != null) it.copy(message = text, messageIsError = true) else it.copy(message = "Zone deleted.", messageIsError = false) }
        }
    }

    private fun permissionNote(granted: Boolean) = if (granted) "" else " Location is not allowed yet: until it is, capture stays paused (fail closed)."
}
