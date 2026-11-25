package com.rokid.context.phone.ui.activity

import android.content.Context
import com.rokid.context.memory.SceneMember
import com.rokid.context.memory.db.LocatedMoment
import com.rokid.context.memory.db.SceneLocatedMoment
import com.rokid.context.phone.hub.HubSettingsStore
import com.rokid.context.phone.ui.list.ListItem
import com.rokid.context.phone.ui.preview.PreviewRef
import com.rokid.context.phone.ui.preview.RefKind
import com.rokid.context.phone.ui.settings.privacy.zones.MapViewport
import com.rokid.context.phone.ui.settings.privacy.zones.tiles.OnlineMapWords
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max

// Activity "map view" of Moments (owner decision 2026-11-26: a VIEW MODE of Activity, not a Settings item). Coordinates stay on the device: nothing here logs, exports or sends a position or a cell id.

/** How the Activity page shows its Moments: the list as it always was, or the heat-map. [LIST] is the default. */
enum class ActivityViewMode(val label: String) {
    LIST("List"), MAP("Map");

    companion object {
        val DEFAULT = LIST
        /** Anything unknown or missing is the default (a damaged or newer preference never breaks the page). */
        fun parse(raw: String?): ActivityViewMode = entries.firstOrNull { it.name == raw } ?: DEFAULT
    }
}

/** Where the chosen mode lives; process-wide (PhoneDeps), like [GranularityStore]. */
interface ViewModeStore {
    val mode: StateFlow<ActivityViewMode>
    fun set(mode: ActivityViewMode)
}

/** Tests and previews: not persisted. */
class InMemoryViewMode(initial: ActivityViewMode = ActivityViewMode.DEFAULT) : ViewModeStore {
    private val state = MutableStateFlow(initial)
    override val mode: StateFlow<ActivityViewMode> = state
    override fun set(mode: ActivityViewMode) { state.value = mode }
}

/** A plain UI preference in the restorable `hub_settings` file, like [PrefsGranularity]; a read or write that fails keeps the default. */
class PrefsViewMode(context: Context) : ViewModeStore {
    private val prefs = runCatching { context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }.getOrNull()
    private val state = MutableStateFlow(ActivityViewMode.parse(runCatching { prefs?.getString(KEY, null) }.getOrNull()))
    override val mode: StateFlow<ActivityViewMode> = state

    override fun set(mode: ActivityViewMode) {
        state.value = mode
        runCatching { prefs?.edit()?.putString(KEY, mode.name)?.apply() }
    }

    companion object { const val PREFS = HubSettingsStore.PREFS; const val KEY = "activity_view_mode" }
}

/** One weight on the map: the Moment it stands for (for Scenes, the Scene's first located Moment) and where and when it was saved. Held in memory for the visible page only. */
data class HeatPoint(val momentId: String, val lat: Double, val lon: Double, val atMs: Long)

/** One hot cell: the mean position of its points, how many weights fell in it, how strong (0..1) against the strongest cell, and the Moments (newest first). */
data class HeatCell(val lat: Double, val lon: Double, val count: Int, val level: Float, val momentIds: List<String>)

/** The pure aggregation behind the map view: which points count (granularity, date range), and the cells a zoom level makes of them. No Compose, no Android. */
object MomentHeat {
    /** A cell is this many screen px wide at any zoom, so it is coarse in kilometres at low zoom and fine at high zoom; cells are anchored to the world, so panning does not move them. */
    const val CELL_PX = 48f
    const val MAX_CELLS = 400

    /**
     * The weights for [g] inside [range] (null: all). MOMENTS and PHOTOS: one weight per Moment with a saved location (a photo has no location of its own; its Moment does).
     * SCENES: one weight per Scene, placed at its FIRST Moment (member order) that has a saved location; a Scene with no located Moment has no weight. The Scene rows are already limited to
     * the Scenes starting in the range by the query, so [range] is applied to the Moments only.
     */
    fun points(g: Granularity, moments: List<LocatedMoment>, scenes: List<SceneLocatedMoment>, range: LongRange?): List<HeatPoint> =
        if (g == Granularity.SCENES) {
            val seen = HashSet<String>()
            scenes.filter { seen.add(it.sceneId) }.map { HeatPoint(it.momentId, it.lat, it.lon, it.atMs) }
        } else moments.filter { range == null || it.atMs in range }.map { HeatPoint(it.momentId, it.lat, it.lon, it.atMs) }

    private fun keyOf(world: MapViewport, lat: Double, lon: Double, cellPx: Float): Pair<Long, Long>? {
        if (!(lat.isFinite() && lon.isFinite())) return null
        val w = world.toPx(lat, lon)
        if (!(w.x.isFinite() && w.y.isFinite())) return null
        return floor(w.x / cellPx).toLong() to floor(w.y / cellPx).toLong()
    }

    /** The cells of [points] at the zoom of [ppd] (pixels per degree), strongest first, at most [maxCells]. Level is the log of the count over the log of the largest count. Not finite points are ignored. */
    fun cells(points: List<HeatPoint>, ppd: Double, cellPx: Float = CELL_PX, maxCells: Int = MAX_CELLS): List<HeatCell> {
        if (points.isEmpty() || !(ppd > 0.0) || !ppd.isFinite() || cellPx <= 0f) return emptyList()
        val world = MapViewport(0.0, 0.0, ppd, 0f, 0f)
        class Acc { val ids = ArrayList<HeatPoint>(); var lat = 0.0; var lon = 0.0 }
        val acc = HashMap<Pair<Long, Long>, Acc>()
        for (p in points) {
            val k = keyOf(world, p.lat, p.lon, cellPx) ?: continue
            val a = acc.getOrPut(k) { Acc() }
            a.ids += p; a.lat += p.lat; a.lon += p.lon
        }
        if (acc.isEmpty()) return emptyList()
        val top = acc.values.maxOf { it.ids.size }
        val denom = max(ln(top.toDouble()), 1e-9)
        return acc.values.map { a ->
            val n = a.ids.size
            val lvl = if (top <= 1) 1f else (ln(n.toDouble()) / denom).coerceIn(0.0, 1.0).toFloat().coerceAtLeast(0.15f)
            HeatCell(a.lat / n, a.lon / n, n, lvl, a.ids.sortedWith(compareByDescending<HeatPoint> { it.atMs }.thenBy { it.momentId }).map { it.momentId })
        }.sortedWith(compareByDescending<HeatCell> { it.count }.thenBy { it.lat }.thenBy { it.lon }).take(maxCells)
    }

    /** The cell a tap at ([x], [y]) opens: the nearest cell centre within [touchPx], or null. */
    fun cellAt(cells: List<HeatCell>, v: MapViewport, x: Float, y: Float, touchPx: Float): HeatCell? {
        if (!(x.isFinite() && y.isFinite() && touchPx > 0f)) return null
        var best: HeatCell? = null; var bestD = Float.MAX_VALUE
        for (c in cells) {
            val p = v.toPx(c.lat, c.lon)
            val d = kotlin.math.hypot(p.x - x, p.y - y)
            if (d.isFinite() && d <= touchPx && d < bestD) { bestD = d; best = c }
        }
        return best
    }

    /** The smallest view holding every point (with padding), or the default view when there is none. */
    fun fit(points: List<HeatPoint>, w: Float, h: Float): MapViewport {
        val ok = points.filter { it.lat.isFinite() && it.lon.isFinite() }
        if (ok.isEmpty()) return MapViewport.default(w, h)
        return MapViewport.fit(listOf(doubleArrayOf(ok.minOf { it.lat }, ok.minOf { it.lon }, ok.maxOf { it.lat }, ok.maxOf { it.lon })), w, h)
    }

    const val EMPTY = "No saved locations yet. Turn on Save location with Moments in Settings."
    const val NONE_IN_RANGE = "No Moments with a saved location in this period."
    const val CAPPED = "Showing the first 20,000 saved locations. Choose a shorter period to see the rest."
    const val NEEDS_CONSENT = "Turn on the street map under Pause zones to see streets here. The plain grid is used until then."

    /** The line under the map about its tiles: no key means the existing "Add a map key in Settings" notice, a key without the street-map switch asks for it; null when streets are shown. */
    fun tileNotice(keyAvailable: Boolean, streetMapOn: Boolean): String? = when {
        !keyAvailable -> OnlineMapWords.UNAVAILABLE
        !streetMapOn -> NEEDS_CONSENT
        else -> null
    }

    /** The time range the map asks for: the custom range, else the period window, in millis. */
    fun range(state: ActivityUiState, zone: java.time.ZoneId): LongRange? =
        state.range?.let { com.rokid.context.phone.ui.list.RangeFilter.millis(it, zone) }
            ?: state.today?.let { PeriodWindow.of(state.period, it).millis(zone) }

    /** One Moment of a hot cell as a row: its first visible caption, else its photo, else its mark (the same rule as a Scene's member list). [ref] is what a tap previews, null when it holds nothing to open. */
    fun row(m: SceneMember, memoryText: String?, memoryId: String?, clock: (Long) -> String): Pair<ListItem, String?> {
        val t = clock(m.atMs)
        return when {
            memoryId != null && memoryText != null -> ListItem(m.momentId, memoryText, subtitle = t) to PreviewRef.of(RefKind.MEMORY, memoryId)
            m.frameIds.isNotEmpty() -> ListItem(m.momentId, "Photo", subtitle = t) to PreviewRef.of(RefKind.FRAME, m.frameIds.first())
            m.markIds.isNotEmpty() -> ListItem(m.momentId, "Mark", subtitle = t) to PreviewRef.of(RefKind.MARK, m.markIds.first())
            else -> ListItem(m.momentId, "Moment", subtitle = t) to null
        }
    }
}
