package com.rokid.context.phone.ui.settings.mapzones

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rokid.context.phone.ui.settings.privacy.zones.MapViewport
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max

// Map zones P3 (flags mapZonesV2 and mapZonesP23, and the user's own "Show my Moments on the map", default OFF): the "My Moments on the map" density layer.
// It READS the saved locations (read only, no schema change), counts them per grid cell in memory for the visible box and the date window, and keeps only the blobs. Nothing is stored, cached across visits,
// logged or sent anywhere; every refresh asks the database again, so a Forget shows at once. No Moment id, name, text or photo is read: only a position and a time.

data class DensityPoint(val lat: Double, val lon: Double, val atMs: Long)

/** What the layer needs from the app: the switch (default OFF, a read error means OFF), the points of a box and window, and the span of saved locations. The route binds the real one. */
interface MomentDensityControl {
    fun isOn(): Boolean
    fun setOn(on: Boolean)
    suspend fun points(south: Double, west: Double, north: Double, east: Double, fromMs: Long, toMs: Long, limit: Int): List<DensityPoint>
    suspend fun range(): Pair<Long, Long>?
}

/** One blob on the map: where it is, how many Moments it stands for, and how strong (0..1) relative to the strongest blob in view. */
data class DensityBlob(val lat: Double, val lon: Double, val count: Int, val level: Float)

enum class DateWindow { LAST_7_DAYS, LAST_30_DAYS, ALL }

object MomentDensity {
    const val CELL_PX = 40f
    const val MAX_POINTS = 20_000
    const val DAY_MS = 86_400_000L

    /** The visible box of [v] as (south, west, north, east); null when the view is unusable or crosses the 180 degree line. */
    fun visibleBox(v: MapViewport): DoubleArray? {
        if (!(v.width.isFinite() && v.height.isFinite() && v.ppd.isFinite()) || v.width < 10f || v.height < 10f) return null
        val tl = v.toLatLon(0f, 0f); val br = v.toLatLon(v.width, v.height)
        val b = doubleArrayOf(br.lat, tl.lon, tl.lat, br.lon)
        if (b.any { !it.isFinite() } || b[0] >= b[2] || b[1] >= b[3] || b[1] < -180.0 || b[3] > 180.0) return null
        return b
    }

    /**
     * Counts [points] per screen-sized cell, anchored to the world at this zoom (so blobs do not jump while panning), and returns at most [maxBlobs] blobs, strongest first.
     * Level is the log of the count over the log of the largest count, so one busy spot does not wash out the rest. Points that are not finite are ignored.
     */
    fun blobs(points: List<DensityPoint>, v: MapViewport, cellPx: Float = CELL_PX, maxBlobs: Int = 300): List<DensityBlob> {
        if (points.isEmpty() || !(v.ppd > 0.0) || !v.ppd.isFinite() || cellPx <= 0f) return emptyList()
        val world = MapViewport(0.0, 0.0, v.ppd, 0f, 0f)
        class Acc(var n: Int = 0, var lat: Double = 0.0, var lon: Double = 0.0)
        val cells = HashMap<Pair<Long, Long>, Acc>()
        for (p in points) {
            if (!(p.lat.isFinite() && p.lon.isFinite())) continue
            val w = world.toPx(p.lat, p.lon)
            if (!(w.x.isFinite() && w.y.isFinite())) continue
            val a = cells.getOrPut(floor(w.x / cellPx).toLong() to floor(w.y / cellPx).toLong()) { Acc() }
            a.n++; a.lat += p.lat; a.lon += p.lon
        }
        if (cells.isEmpty()) return emptyList()
        val top = cells.values.maxOf { it.n }
        val denom = max(ln(top.toDouble()), 1e-9)
        return cells.values.map { a ->
            val lvl = if (top <= 1) 1f else (ln(a.n.toDouble()) / denom).coerceIn(0.0, 1.0).toFloat().coerceAtLeast(0.15f)
            DensityBlob(a.lat / a.n, a.lon / a.n, a.n, lvl)
        }.sortedWith(compareByDescending<DensityBlob> { it.count }.thenBy { it.lat }.thenBy { it.lon }).take(maxBlobs)
    }

    /** Purple at low density to orange at high (ARGB, opaque); the alpha is the caller's. Out of range is clamped. */
    fun colorOf(level: Float): Long {
        val t = if (level.isFinite()) level.coerceIn(0f, 1f) else 0f
        fun mix(a: Int, b: Int) = (a + (b - a) * t).toInt().coerceIn(0, 255)
        return 0xFF000000L or (mix(0x7B, 0xF5).toLong() shl 16) or (mix(0x3F, 0x8C).toLong() shl 8) or mix(0xC4, 0x1E).toLong()
    }

    /** The time range to ask for: the window ending at the newest saved location (or [now]), clamped to what exists. */
    fun window(w: DateWindow, range: Pair<Long, Long>?, now: Long): Pair<Long, Long>? {
        range ?: return null
        val end = max(range.second, 0L)
        val from = when (w) { DateWindow.ALL -> range.first; DateWindow.LAST_7_DAYS -> max(range.first, minOf(now, end) - 7 * DAY_MS); DateWindow.LAST_30_DAYS -> max(range.first, minOf(now, end) - 30 * DAY_MS) }
        return from to end
    }

    private val fmt = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.US)
    fun day(ms: Long, zone: ZoneId): String = fmt.format(Instant.ofEpochMilli(ms).atZone(zone))

    /** "1,153 Moments - 19 Nov 2026 to 24 Nov 2026". Counts and dates only. */
    fun summary(count: Int, fromMs: Long?, toMs: Long?, zone: ZoneId): String {
        val n = String.format(Locale.US, "%,d", count) + if (count == 1) " Moment" else " Moments"
        return if (fromMs == null || toMs == null) n else "$n - ${day(fromMs, zone)} to ${day(toMs, zone)}"
    }

    const val NONE_TEXT = "No Moments with a saved location yet."
    const val NONE_IN_VIEW = "No Moments with a saved location in this view."
    const val CAPPED = "Showing the first 20,000 saved locations in this view. Zoom in to see more."
}

data class MomentDensityState(
    val on: Boolean = false,
    val window: DateWindow = DateWindow.ALL,
    val blobs: List<DensityBlob> = emptyList(),
    val count: Int = 0,
    val capped: Boolean = false,
    val summary: String = "",
    val hasAny: Boolean = false,
)

/** The state of the density layer. Off (the default) means no database read at all and no blobs held. */
class MomentDensityViewModel(
    private val control: MomentDensityControl,
    private val work: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) : ViewModel() {
    private val _state = MutableStateFlow(MomentDensityState(on = runCatching { control.isOn() }.getOrDefault(false)))
    val state: StateFlow<MomentDensityState> = _state
    private var job: Job? = null
    private var last: MapViewport? = null

    private val views = kotlinx.coroutines.flow.MutableSharedFlow<MapViewport>(replay = 1, onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST)
    init { viewModelScope.launch(work) { views.debounce(VIEW_DEBOUNCE_MS).collect { refresh(it) } } }

    /** The view moved or zoomed: refresh once it has been still for [VIEW_DEBOUNCE_MS]. Does nothing while the layer is off. */
    fun onViewChanged(v: MapViewport) { if (_state.value.on) views.tryEmit(v) }

    fun setOn(on: Boolean) {
        runCatching { control.setOn(on) }
        job?.cancel()
        if (!on) { _state.update { MomentDensityState(on = false, window = it.window) }; return }
        _state.update { it.copy(on = true) }
        last?.let { refresh(it) }
    }

    fun setWindow(w: DateWindow) { _state.update { it.copy(window = w) }; last?.let { refresh(it) } }

    /** Drops what is held (blobs and counts). The map screen calls it when it goes away; the next visit asks the database again. */
    fun clear() { job?.cancel(); _state.update { MomentDensityState(on = it.on, window = it.window) } }

    /** Recomputes for [v]. Does nothing at all while the layer is off. The newest call wins. */
    fun refresh(v: MapViewport) {
        last = v
        if (!_state.value.on) return
        val box = MomentDensity.visibleBox(v) ?: run { _state.update { it.copy(blobs = emptyList(), count = 0, summary = "") }; return }
        val w = _state.value.window
        job?.cancel()
        job = viewModelScope.launch(work) {
            val range = runCatching { control.range() }.getOrNull()
            val win = MomentDensity.window(w, range, clock())
            if (win == null) { _state.update { if (it.on) it.copy(blobs = emptyList(), count = 0, capped = false, hasAny = false, summary = MomentDensity.NONE_TEXT) else it }; return@launch }
            val pts = runCatching { control.points(box[0], box[1], box[2], box[3], win.first, win.second, MomentDensity.MAX_POINTS) }.getOrDefault(emptyList())
            val blobs = MomentDensity.blobs(pts, v)
            val text = if (pts.isEmpty()) MomentDensity.NONE_IN_VIEW else MomentDensity.summary(pts.size, pts.minOf { it.atMs }, pts.maxOf { it.atMs }, zone())
            _state.update { if (it.on && it.window == w) it.copy(blobs = blobs, count = pts.size, capped = pts.size >= MomentDensity.MAX_POINTS, summary = text, hasAny = true) else it }
        }
    }

    private companion object { const val VIEW_DEBOUNCE_MS = 300L }

    override fun onCleared() { job?.cancel() }
}
