package com.rokid.context.canvas.moment

import android.content.Context
import android.os.SystemClock
import com.rokid.context.connectors.sources.LocationSource
import com.rokid.context.core.log.PcLog
import com.rokid.context.core.model.zone.PauseZone
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.orchestration.DataOpGate
import com.rokid.context.orchestration.MemoryWipe
import com.rokid.context.orchestration.forget.DeveloperFlags
import com.rokid.context.work.frames.place.PlaceSamplers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlin.coroutines.cancellation.CancellationException

/**
 * v18 WP4: the process-wide [MomentLocationRecorder]. Pokes run single-flight on IO (a poke that finds one running is dropped: the recorder's 2-minute lookback covers it).
 * The zone inputs are installed by the hub; until then every input fails closed (zones blocked, no zone fix). This file and the recorder are the only production callers of [deleteAll].
 */
object MomentLocationGraph {
    private const val TAG = "PcMomentLoc"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Mutex()

    /**
     * review2 #6: the deletion epoch of saved locations. Bumped when "Delete saved locations" starts and again when it ended, and after every user Forget, range Forget and Delete all COMMITTED (a [com.rokid.context.memory.forget.ForgetListener]
     * registered with the first read of this object). A reader of locations (the Activity map) remembers the value before it reads and publishes only if it is unchanged; a change also tells it to drop what it shows and read again.
     */
    private val epoch = kotlinx.coroutines.flow.MutableStateFlow(0L)
    val deletionEpoch: kotlinx.coroutines.flow.StateFlow<Long> get() = epoch
    private fun bumpEpoch() { epoch.value = epoch.value + 1 }
    private val forgetWatch = object : com.rokid.context.memory.forget.ForgetListener {
        override suspend fun onFramesForgotten(frameIds: List<String>) = bumpEpoch()
        override suspend fun onRangeForgotten(fromMs: Long, toMs: Long, traceIds: List<String>) = bumpEpoch()
        override suspend fun onDeleteAll() = bumpEpoch()
        override suspend fun onUserForget() = bumpEpoch()
    }
    init { com.rokid.context.memory.forget.ForgetListeners.register(forgetWatch) }

    @Volatile private var zoneFix: () -> HeldFix? = { null }
    @Volatile private var zones: suspend () -> List<PauseZone>? = { null }
    @Volatile private var zoneBlocked: suspend () -> Boolean = { true }

    /** The hub installs the pause-zone view: the stream's last fix (elapsed clock), the zone list for the point check and the "zone active, approaching or unreadable" predicate. */
    fun install(zoneFix: () -> HeldFix?, zones: suspend () -> List<PauseZone>?, zoneBlocked: suspend () -> Boolean) {
        this.zoneFix = zoneFix; this.zones = zones; this.zoneBlocked = zoneBlocked
    }

    /** The installed zone view, for the WP5 import route; fail closed (blocked, null zones) until the hub installs it. */
    suspend fun zoneBlockedNow(): Boolean = zoneBlocked()
    suspend fun zonesNow(): List<PauseZone>? = zones()

    private fun recorder(app: Context) = MomentLocationRecorder(
        MemoryGraph.get(app).db, wall = System::currentTimeMillis, elapsed = SystemClock::elapsedRealtime,
        enabled = { DeveloperFlags.momentLocation(app) },
        zoneBlocked = { zoneBlocked() }, zones = { zones() },
        zoneFix = { zoneFix() },
        placeFix = { PlaceSamplers.flight.good?.let { HeldFix(it.lat, it.lon, it.accM, it.atElapsed, elapsedClock = true, generation = it.generation) } },
        lastKnown = { LocationSource.lastKnown(app)()?.let { HeldFix(it.lat, it.lon, it.accuracyM, it.timeMs, elapsedClock = false) } },
        generation = { MemoryWipe.generation.get() },
        log = { PcLog.i(TAG, it) },
    )

    /** Fire and forget: never throws, never blocks the caller. */
    fun poke(app: Context) {
        if (!DeveloperFlags.momentLocation(app)) return
        if (!gate.tryLock()) return
        scope.launch {
            try { recorder(app).poke() } catch (e: CancellationException) { throw e } catch (e: Exception) { PcLog.w(TAG, "poke failed: ${e.javaClass.simpleName}") } finally { gate.unlock() }
        }
    }

    /** The number of Moments with a saved location (0 when unreadable). */
    suspend fun count(app: Context): Int = try { MemoryGraph.get(app).db.momentLocationDao().count() } catch (e: CancellationException) { throw e } catch (e: Exception) { 0 }

    /** Map zones P3 density layer (read only): the saved positions in [south..north] x [west..east] between [from] and [to], at most [limit]; empty when unreadable. The caller must never log them. */
    suspend fun pointsIn(app: Context, south: Double, west: Double, north: Double, east: Double, from: Long, to: Long, limit: Int): List<com.rokid.context.memory.db.MomentPoint> =
        try { MemoryGraph.get(app).db.momentLocationDao().pointsIn(south, west, north, east, from, to, limit) } catch (e: CancellationException) { throw e } catch (e: Exception) { emptyList() }

    /** The first and last time of any saved location, or null when there is none or it is unreadable. */
    suspend fun timeRange(app: Context): Pair<Long, Long>? = try {
        val d = MemoryGraph.get(app).db.momentLocationDao(); val a = d.firstAt(); val b = d.lastAt(); if (a != null && b != null) a to b else null
    } catch (e: CancellationException) { throw e } catch (e: Exception) { null }

    /** Activity map view (read only, bounded by [MAP_MAX_ROWS]): every saved location with its Moment id in [range]; empty when unreadable. The caller must never log them. */
    suspend fun located(app: Context, range: LongRange): List<com.rokid.context.memory.db.LocatedMoment> =
        try { MemoryGraph.get(app).db.momentLocationDao().located(range.first, range.last, MAP_MAX_ROWS) } catch (e: CancellationException) { throw e } catch (e: Exception) { emptyList() }

    /** The same per Scene starting in [range], in member order (the caller takes the first row of each Scene). */
    suspend fun sceneLocated(app: Context, range: LongRange): List<com.rokid.context.memory.db.SceneLocatedMoment> =
        try { MemoryGraph.get(app).db.momentLocationDao().sceneLocated(range.first, range.last + 1, MAP_MAX_ROWS) } catch (e: CancellationException) { throw e } catch (e: Exception) { emptyList() }

    /** What the named Moments hold (a memory, a photo, a mark), by reference only, oldest first, with the time of their saved location; a Moment that is gone is absent. */
    suspend fun members(app: Context, momentIds: List<String>): List<com.rokid.context.memory.SceneMember> = try {
        val db = MemoryGraph.get(app).db
        val ids = momentIds.distinct()
        val held = ids.chunked(500).flatMap { db.momentDao().partsOf(it) }.groupBy { it.momentId }
        val at = db.momentLocationDao().of(ids).associate { it.momentId to it.atMs }
        ids.filter { it in held || it in at }.map { id ->
            val mine = held[id].orEmpty()
            com.rokid.context.memory.SceneMember(
                id, at[id] ?: 0L,
                mine.filter { it.kind == com.rokid.context.memory.db.MomentPartKind.MEMORY }.map { it.refId },
                mine.filter { it.kind == com.rokid.context.memory.db.MomentPartKind.FRAME }.map { it.refId },
                mine.filter { it.kind == com.rokid.context.memory.db.MomentPartKind.MARK }.map { it.refId },
            )
        }.sortedWith(compareBy({ it.atMs }, { it.momentId }))
    } catch (e: CancellationException) { throw e } catch (e: Exception) { emptyList() }

    const val MAP_MAX_ROWS = 20_000

    /** "Delete saved locations": removes every `moment_location` row, under the delete side of the data-operation gate. Returns the number of rows removed. */
    suspend fun deleteAll(app: Context): Int { bumpEpoch(); try { return DataOpGate.shared.delete { MemoryGraph.get(app).db.momentLocationDao().deleteAllMomentLocations() } } finally { bumpEpoch() } }
}
