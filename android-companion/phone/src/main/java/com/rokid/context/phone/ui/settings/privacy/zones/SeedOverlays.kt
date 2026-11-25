package com.rokid.context.phone.ui.settings.privacy.zones

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rokid.context.memory.place.AREA_PARTIAL
import com.rokid.context.memory.place.AREA_SEEDING
import com.rokid.context.memory.place.Geohash5
import com.rokid.context.memory.place.GeoBox
import com.rokid.context.memory.place.PlacePack
import com.rokid.context.phone.ui.settings.place.SeedOutcome
import com.rokid.context.phone.ui.settings.place.seedFailureText
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.floor

// Map zones P0: seeded areas and pins as read-only overlays on the pause-zones map, and "seed this drawn box" through the existing Place Guide seed path.
// Pause zones are NOT touched here: their store, evaluator and save path are unchanged.

/** A seeded area as the map needs it: id, box and the pack's status text. Held on screen only; never logged. */
data class SeedAreaInfo(val id: String, val south: Double, val west: Double, val north: Double, val east: Double, val status: String) {
    val box: DoubleArray get() = doubleArrayOf(south, west, north, east)
}

/** A user pin as the map needs it: a dot (no name, no note). */
data class PinInfo(val lat: Double, val lon: Double, val radiusM: Int)

/** What the zones map needs of Place Guide: the seeded areas and pins to draw, the existing seed path and the area delete. */
interface SeedMapControl {
    suspend fun areas(): List<SeedAreaInfo>
    suspend fun pins(): List<PinInfo>
    /** The existing Place Guide seed (same Offline only, Wi-Fi and data saver refusals). */
    suspend fun seed(box: GeoBox): Result<SeedOutcome>
    /** Removes the seeded-area record; its places stay as ordinary fetched places. */
    suspend fun remove(id: String)
    /** Map zones "Show cells" (read only): the stored status of each 5 km cell touching [box]; a missing cell is absent. The default is empty (nothing known). */
    suspend fun cellStatuses(box: GeoBox): Map<String, String> = emptyMap()
}

enum class SeedLevel { EMPTY, OK, CONFIRM, TOO_LARGE }

/** The cell count and time estimate for a drawn box, and the thresholds around it. Pure. */
object SeedEstimate {
    /** Above this many 5 km cells the app asks before seeding (design 3.2). */
    const val CONFIRM_CELLS = 100L
    /** The pack's own hard cap; a box above it is refused here before any request. */
    const val MAX_CELLS: Long = PlacePack.MAX_SEED_CELLS.toLong()
    /** About 10 s a cell (from "a dozen cells in about 2 minutes"). UNVERIFIED: measure on the first real seed. */
    const val SECONDS_PER_CELL = 10L

    /** The box as a [GeoBox], or null when it is missing or not a valid box. */
    fun boxOf(b: DoubleArray?): GeoBox? = b?.takeIf { it.size == 4 }?.let { GeoBox(it[0], it[1], it[2], it[3]) }?.takeIf { it.valid }

    /** How many 5 km cells touch [box], without building the list (a huge box must not allocate millions of keys). Equals `Geohash5.cellsCovering(box).size`. */
    fun cells(box: GeoBox): Long {
        if (!box.valid) return 0
        val la0 = latIndex(box.south); val la1 = latIndex(box.north); val lo0 = lonIndex(box.west); val lo1 = lonIndex(box.east)
        return (la1 - la0 + 1L) * (lo1 - lo0 + 1L)
    }

    fun level(cells: Long): SeedLevel = when {
        cells <= 0 -> SeedLevel.EMPTY
        cells > MAX_CELLS -> SeedLevel.TOO_LARGE
        cells > CONFIRM_CELLS -> SeedLevel.CONFIRM
        else -> SeedLevel.OK
    }

    fun seconds(cells: Long): Long = cells * SECONDS_PER_CELL

    fun duration(cells: Long): String {
        val s = seconds(cells)
        return if (s < 90) "about $s s" else if (s < 5_400) "about ${ceil(s / 60.0).toLong()} min" else "about ${ceil(s / 3_600.0).toLong()} h"
    }

    /** The line under the map: the count, the time and the Wi-Fi rule; or why the box cannot be seeded. */
    fun line(box: GeoBox?): String {
        if (box == null) return "Draw a box to see how many 5 km cells it covers."
        val n = cells(box)
        return when (level(n)) {
            SeedLevel.EMPTY -> "Draw a box to see how many 5 km cells it covers."
            SeedLevel.TOO_LARGE -> "Too large: $n cells. Draw a smaller area (at most $MAX_CELLS cells)."
            else -> "$n cell${if (n == 1L) "" else "s"} (5 km each), ${duration(n)}, Wi-Fi only."
        }
    }

    fun confirmText(cells: Long): String = "This is a large area ($cells cells, ${duration(cells)}). Seed it? Only the 5 km cell keys are sent, never your position."

    private fun latIndex(lat: Double) = minOf(LAT_CELLS - 1, floor((lat + 90.0) / Geohash5.LAT_STEP).toInt())
    private fun lonIndex(lon: Double) = minOf(LON_CELLS - 1, floor((lon + 180.0) / Geohash5.LON_STEP).toInt())
    private const val LAT_CELLS = 4096
    private const val LON_CELLS = 8192
}

enum class AreaLook { DONE, PARTIAL, SEEDING }

/** One seeded area to draw: its box, its look (blue / amber with a badge / moving dash) and the list label. */
data class SeedOverlay(val id: String, val box: DoubleArray, val look: AreaLook, val label: String)

/** The pure overlay model: what the canvas draws and the legend line. No Compose, no coordinates in any text. */
object SeedOverlays {
    /** Smaller than this on screen (dp) a shape is drawn as a chip of this size so a small area still shows when zoomed out. */
    const val MIN_SHAPE_DP = 16f

    fun look(status: String): AreaLook = when (status) { AREA_SEEDING -> AreaLook.SEEDING; AREA_PARTIAL -> AreaLook.PARTIAL; else -> AreaLook.DONE }

    fun of(areas: List<SeedAreaInfo>): List<SeedOverlay> = areas.mapIndexed { i, a ->
        val look = look(a.status)
        SeedOverlay(a.id, a.box, look, "Seeded area ${i + 1}" + when (look) { AreaLook.DONE -> ""; AreaLook.PARTIAL -> " (some cells failed, will retry)"; AreaLook.SEEDING -> " (seeding)" })
    }

    /** The legend: counts of what is drawn. Pause zones are counted by the caller's own list. */
    fun legend(pause: Int, seed: Int, pins: Int): String =
        "$pause pause zone${if (pause == 1) "" else "s"} · $seed seed area${if (seed == 1) "" else "s"} · $pins pin${if (pins == 1) "" else "s"}"

    /** The on-screen rectangle (x, y, w, h) of [box], widened around its centre to at least [minPx]. */
    fun screenRect(v: MapViewport, box: DoubleArray, minPx: Float): FloatArray {
        val tl = v.toPx(box[2], box[1]); val br = v.toPx(box[0], box[3])
        var x = tl.x; var y = tl.y; var w = br.x - tl.x; var h = br.y - tl.y
        if (w < minPx) { x -= (minPx - w) / 2; w = minPx }
        if (h < minPx) { y -= (minPx - h) / 2; h = minPx }
        return floatArrayOf(x, y, w, h)
    }

    /** True when the box is smaller than [minPx] in either direction on screen. */
    fun isTiny(v: MapViewport, box: DoubleArray, minPx: Float): Boolean {
        val tl = v.toPx(box[2], box[1]); val br = v.toPx(box[0], box[3])
        return (br.x - tl.x) < minPx || (br.y - tl.y) < minPx
    }

    /** Every box to fit when the map opens: the zones' rings plus the seeded areas, so a seeded area is never off screen at the start. */
    fun fitBoxes(zones: List<DoubleArray>, areas: List<SeedAreaInfo>): List<DoubleArray> = zones + areas.map { it.box }
}

data class SeedMapUiState(
    val areas: List<SeedOverlay> = emptyList(),
    val pins: List<PinInfo> = emptyList(),
    val seeding: Boolean = false,
    val message: String? = null,
    val messageIsError: Boolean = false,
    /** A seed waiting for the user's yes (above [SeedEstimate.CONFIRM_CELLS]): the cell count to show. */
    val confirmCells: Long? = null,
    val pendingRemoveId: String? = null,
)

/** The seed side of the zones map. It only reads areas and pins and calls the existing seed; it never writes a pause zone. */
class SeedMapViewModel(private val control: SeedMapControl, private val work: CoroutineDispatcher = Dispatchers.IO) : ViewModel() {
    private val _state = MutableStateFlow(SeedMapUiState())
    val state: StateFlow<SeedMapUiState> = _state
    private var pendingBox: GeoBox? = null
    private var infos: List<SeedAreaInfo> = emptyList()

    /** The raw areas (for fitting the map). */
    fun areaInfos(): List<SeedAreaInfo> = infos

    fun refresh() {
        viewModelScope.launch(work) {
            val a = runCatching { control.areas() }.getOrDefault(emptyList())
            val p = runCatching { control.pins() }.getOrDefault(emptyList())
            infos = a
            _state.update { it.copy(areas = SeedOverlays.of(a), pins = p) }
        }
    }

    /** The user taps "Seed places in this box". Too large is refused here; above the confirm threshold it waits for a yes. */
    fun requestSeed(box: GeoBox?) {
        if (_state.value.seeding) return
        if (box == null) { _state.update { it.copy(message = "Draw a box on the map first.", messageIsError = true) }; return }
        val n = SeedEstimate.cells(box)
        when (SeedEstimate.level(n)) {
            SeedLevel.EMPTY -> _state.update { it.copy(message = "Draw a box on the map first.", messageIsError = true) }
            SeedLevel.TOO_LARGE -> _state.update { it.copy(message = SeedEstimate.line(box), messageIsError = true) }
            SeedLevel.CONFIRM -> { pendingBox = box; _state.update { it.copy(confirmCells = n) } }
            SeedLevel.OK -> start(box)
        }
    }

    fun confirmSeed() {
        val b = pendingBox ?: return
        pendingBox = null
        _state.update { it.copy(confirmCells = null) }
        start(b)
    }

    fun cancelConfirm() { pendingBox = null; _state.update { it.copy(confirmCells = null) } }

    private fun start(box: GeoBox) {
        if (_state.value.seeding) return
        _state.update { it.copy(seeding = true, message = "Fetching places…", messageIsError = false) }
        viewModelScope.launch(work) {
            val r = control.seed(box)
            val byRemoval = (r.exceptionOrNull() as? com.rokid.context.memory.place.SeedStopped)?.reason == "area removed" // the removal's own message says it
            _state.update { if (byRemoval) it.copy(seeding = false) else it.copy(seeding = false, message = r.fold({ o -> o.message }, { t -> seedFailureText(t) }), messageIsError = r.isFailure) }
            refresh()
        }
    }

    fun askRemove(id: String) = _state.update { it.copy(pendingRemoveId = id) }
    fun cancelRemove() = _state.update { it.copy(pendingRemoveId = null) }

    fun confirmRemove() {
        val id = _state.value.pendingRemoveId ?: return
        _state.update { it.copy(pendingRemoveId = null) }
        viewModelScope.launch(work) {
            val ok = runCatching { control.remove(id) }.isSuccess
            _state.update { it.copy(message = if (ok) "Seeded area removed. Its places stay as ordinary fetched places." else "Could not remove the seeded area.", messageIsError = !ok) }
            refresh()
        }
    }

    fun dismissMessage() = _state.update { it.copy(message = null) }
}
