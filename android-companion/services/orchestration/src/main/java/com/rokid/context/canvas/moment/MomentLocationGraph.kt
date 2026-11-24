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

    @Volatile private var zoneFix: () -> HeldFix? = { null }
    @Volatile private var zones: suspend () -> List<PauseZone>? = { null }
    @Volatile private var zoneBlocked: suspend () -> Boolean = { true }

    /** The hub installs the pause-zone view: the stream's last fix (elapsed clock), the zone list for the point check and the "zone active, approaching or unreadable" predicate. */
    fun install(zoneFix: () -> HeldFix?, zones: suspend () -> List<PauseZone>?, zoneBlocked: suspend () -> Boolean) {
        this.zoneFix = zoneFix; this.zones = zones; this.zoneBlocked = zoneBlocked
    }

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

    /** "Delete saved locations": removes every `moment_location` row, under the delete side of the data-operation gate. Returns the number of rows removed. */
    suspend fun deleteAll(app: Context): Int = DataOpGate.shared.delete { MemoryGraph.get(app).db.momentLocationDao().deleteAllMomentLocations() }
}
