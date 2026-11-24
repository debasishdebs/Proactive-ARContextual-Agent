package com.rokid.context.phone.ui.settings.mapzones

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rokid.context.core.model.zone.PauseZone
import com.rokid.context.memory.place.GeoBox
import com.rokid.context.phone.ui.settings.privacy.zones.DraftResult
import com.rokid.context.phone.ui.settings.privacy.zones.LatLon
import com.rokid.context.phone.ui.settings.privacy.zones.SeedEstimate
import com.rokid.context.phone.ui.settings.privacy.zones.SeedLevel
import com.rokid.context.phone.ui.settings.privacy.zones.ZoneDraft
import com.rokid.context.phone.ui.settings.privacy.zones.ZoneDrafts
import com.rokid.context.phone.ui.settings.privacy.zones.ZoneEditor
import com.rokid.context.phone.ui.settings.privacy.zones.ZoneWords
import com.rokid.context.phone.zone.ZoneChange
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// Map zones P1 (flag mapZonesV2). The drawn shape, the sheet and its fields live ONLY here, in memory. A pause zone reaches the zone store through exactly one call, ZoneEditor.upsert, made by [savePause].
// Cancel, back, rotation and process death drop the draft; losing an unsaved draft never loses protection. No coordinate or name is ever logged.

enum class SheetMode { NONE, CHOOSE, PAUSE, SEED }
enum class SelectionKind { PAUSE, SEED }
data class Selection(val kind: SelectionKind, val id: String)

data class MapZonesUiState(
    val shape: DrawnShape? = null,
    val kind: ShapeKind = ShapeKind.RECT,
    /** "+ Draw area" was tapped: the next long-press drag or the placed default shape starts a draft. */
    val drawing: Boolean = false,
    val sheet: SheetMode = SheetMode.NONE,
    val name: String = "",
    val bufferText: String = PauseZone.DEFAULT_BUFFER_M.toString(),
    /** The stored pause zone being reshaped (same id on save); null for a new zone. The stored zone stays in force until the save returns OK. */
    val editingId: String? = null,
    val selected: Selection? = null,
    val saving: Boolean = false,
    /** The refusal shown inside the open sheet. */
    val sheetError: String? = null,
    val message: String? = null,
    val messageIsError: Boolean = false,
    val layers: Set<MapLayer> = MapLayer.values().toSet(),
    /** Counts up on every successful pause save: the screen asks for location permission then if it is missing (as the shipped screen does). */
    val savedTick: Int = 0,
    val locationGranted: Boolean = true,
) {
    val box: DoubleArray? get() = shape?.let { ShapeEdit.box(it) }
    val hasDraft: Boolean get() = shape != null
}

class MapZonesViewModel(
    private val editor: ZoneEditor,
    private val newId: () -> String = { "zone-" + System.currentTimeMillis().toString(36) },
    private val work: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val _state = MutableStateFlow(MapZonesUiState())
    val state: StateFlow<MapZonesUiState> = _state

    fun setLocationGranted(granted: Boolean) = _state.update { it.copy(locationGranted = granted) }

    private fun protectedNow(): Boolean = editor.snapshot.value.protectedMode

    // --- drawing ---
    fun beginDraw() = _state.update { it.copy(drawing = true, message = null) }

    fun setKind(kind: ShapeKind) = _state.update { s ->
        val shape = s.shape
        if (shape == null || shape.kind == kind) return@update s.copy(kind = kind)
        val b = ShapeEdit.box(shape) ?: return@update s.copy(kind = kind)
        val next: DrawnShape? = when (kind) {
            ShapeKind.RECT -> DrawnShape.Rect(b[0], b[1], b[2], b[3])
            ShapeKind.CIRCLE -> {
                val c = LatLon((b[0] + b[2]) / 2, (b[1] + b[3]) / 2)
                val r = minOf(ShapeEdit.distanceM(c, LatLon(c.lat, b[1])), ShapeEdit.distanceM(c, LatLon(b[0], c.lon))).coerceIn(ShapeEdit.MIN_RADIUS_M, ShapeEdit.MAX_RADIUS_M)
                DrawnShape.Circle(c.lat, c.lon, r).takeIf { ShapeEdit.box(it) != null }
            }
        }
        if (next == null) s.copy(kind = kind) else s.copy(kind = kind, shape = next)
    }

    /** A new shape from a long-press drag or the "+ Draw area" default. Anything unusable is ignored. */
    fun place(shape: DrawnShape?) = _state.update { s ->
        if (shape == null || ShapeEdit.box(shape) == null) s.copy(drawing = false, message = "That area is too small. Drag a larger one.", messageIsError = true)
        else s.copy(shape = shape, kind = shape.kind, drawing = false, sheet = SheetMode.NONE, selected = null, sheetError = null, message = null)
    }

    /** A handle or body drag on the draft. */
    fun reshape(shape: DrawnShape) = _state.update { s -> if (s.shape != null && ShapeEdit.box(shape) != null) s.copy(shape = shape, sheetError = null) else s }

    /** Drops the draft, the sheet and any edit. Makes no store call. */
    fun cancelDraw() = _state.update { it.copy(shape = null, drawing = false, sheet = SheetMode.NONE, editingId = null, name = "", bufferText = PauseZone.DEFAULT_BUFFER_M.toString(), sheetError = null, saving = false) }

    // --- the sheet ---
    fun next() = _state.update { if (it.shape != null && ShapeEdit.box(it.shape) != null) it.copy(sheet = if (it.editingId != null) SheetMode.PAUSE else SheetMode.CHOOSE, sheetError = null) else it }

    /** Pause or Seed. Pause is refused while the saved zones cannot be read (the sheet says why); Seed stays available (it does not create capture). */
    fun choose(mode: SheetMode) = _state.update {
        when {
            mode == SheetMode.PAUSE && protectedNow() -> it.copy(sheetError = ZoneWords.PROTECTED_BANNER)
            mode == SheetMode.PAUSE || mode == SheetMode.SEED -> it.copy(sheet = mode, sheetError = null)
            else -> it
        }
    }

    fun back() = _state.update { it.copy(sheet = if (it.sheet == SheetMode.CHOOSE || it.editingId != null) SheetMode.NONE else SheetMode.CHOOSE, sheetError = null) }

    fun setName(v: String) = _state.update { it.copy(name = v, sheetError = null) }
    fun setBuffer(v: String) = _state.update { it.copy(bufferText = v, sheetError = null) }

    /** The cell estimate for the drawn shape (Seed sheet). */
    fun seedBox(): GeoBox? = SeedEstimate.boxOf(_state.value.box)
    fun seedLevel(): SeedLevel = seedBox()?.let { SeedEstimate.level(SeedEstimate.cells(it)) } ?: SeedLevel.EMPTY

    // --- save: the ONE pause store call ---
    /**
     * Saves the drawn shape as a pause zone: ONE [ZoneEditor.upsert] call (the existing path: the engine judges the zone before it returns). The sheet closes ONLY on [ZoneChange.OK]; on any refusal it stays open
     * with the existing wording and the stored zone stays as it was. A circle is saved as the box around it (outward rounded, so it never covers less). A second tap while a save is running does nothing.
     */
    fun savePause() {
        val s = _state.value
        if (s.saving) return
        val box = s.box
        if (box == null) { _state.update { it.copy(sheetError = "Draw an area first.") }; return }
        val o = ShapeEdit.outward(box)
        val existing = s.editingId?.let { id -> editor.snapshot.value.zones.firstOrNull { z -> z.id == id } ?: editor.storedZones().firstOrNull { z -> z.id == id } }
        if (s.editingId != null && existing == null) { _state.update { it.copy(sheetError = ZoneWords.refusal(ZoneChange.NOT_FOUND, "replaced")) }; return }
        val draft = ZoneDraft(s.name, ZoneDrafts.deg(o[0]), ZoneDrafts.deg(o[1]), ZoneDrafts.deg(o[2]), ZoneDrafts.deg(o[3]), s.bufferText)
        when (val r = ZoneDrafts.parse(draft, existing?.id ?: newId())) {
            is DraftResult.Error -> _state.update { it.copy(sheetError = r.text) }
            is DraftResult.Ok -> {
                val zone = if (existing != null) r.zone.copy(enabled = existing.enabled, preset = existing.preset) else r.zone
                _state.update { it.copy(saving = true, sheetError = null) }
                viewModelScope.launch(work) {
                    val change = editor.upsert(zone)
                    val text = ZoneWords.refusal(change, "replaced")
                    _state.update {
                        if (text != null) it.copy(saving = false, sheetError = text)
                        else MapZonesUiState(layers = it.layers, locationGranted = it.locationGranted, savedTick = it.savedTick + 1, message = "Zone \"${zone.name}\" saved." + if (it.locationGranted) "" else " Location is not allowed yet: until it is, capture stays paused (fail closed).")
                    }
                }
            }
        }
    }

    // --- selecting and editing what exists ---
    fun select(sel: Selection?) = _state.update { it.copy(selected = sel) }

    /** Loads a stored pause zone into the editor as a draft with the same id. Refused while the saved zones cannot be read. The stored zone keeps pausing until the save returns OK. */
    fun editPause(id: String) {
        if (protectedNow()) { _state.update { it.copy(message = ZoneWords.PROTECTED_BANNER, messageIsError = true) }; return }
        val z = editor.snapshot.value.zones.firstOrNull { it.id == id } ?: editor.storedZones().firstOrNull { it.id == id }
        if (z == null) { _state.update { it.copy(message = ZoneWords.refusal(ZoneChange.NOT_FOUND, "replaced"), messageIsError = true) }; return }
        _state.update { it.copy(shape = DrawnShape.Rect(z.south, z.west, z.north, z.east), kind = ShapeKind.RECT, editingId = z.id, name = z.name, bufferText = z.bufferM.toString(), sheet = SheetMode.NONE, selected = null, drawing = false, sheetError = null) }
    }

    fun toggleLayer(layer: MapLayer, pauseLocked: Boolean) = _state.update { it.copy(layers = MapOverview.toggle(it.layers, layer, pauseLocked)) }

    fun dismissMessage() = _state.update { it.copy(message = null) }
}
