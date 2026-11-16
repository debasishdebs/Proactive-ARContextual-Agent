package com.rokid.context.phone.zone

import android.content.Context
import android.os.SystemClock
import com.rokid.context.core.log.PcLog
import com.rokid.context.core.model.PauseRecord
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.phone.hub.ContextHubService
import com.rokid.context.phone.hub.HubGraph
import com.rokid.context.phone.hub.HubPauses

/** The one process-wide pause-zone controller. The hub starts it (see [HubGraph]); the map UI reads [ZoneController.snapshot] and edits zones through it. */
object ZoneGraph {
    const val TAG = "PcZone"

    @Volatile private var controller: ZoneController? = null

    fun get(context: Context): ZoneController = controller ?: synchronized(this) { controller ?: create(context.applicationContext).also { controller = it } }

    /** Null before the controller exists; the hub's `zoneApproach` push and `wantLocation` read it without creating anything. */
    internal fun peek(): ZoneController? = controller

    private fun create(app: Context): ZoneController {
        val pauses = HubPauses(MemoryGraph.get(app).db.pauseDao())
        val sink = object : ZoneSink {
            override suspend fun activeZoneRecords(): List<PauseRecord> = pauses.zoneRecords()
            override suspend fun add(record: PauseRecord) = pauses.add(record)
            override suspend fun end(ids: Set<String>) = pauses.resumeZones(ids)
            override suspend fun publish() = HubGraph.zonesChanged()
        }
        val state = ZoneStateStore(app.getSharedPreferences(ZoneStateStore.PREFS, Context.MODE_PRIVATE))
        // A backward move of the wall clock is remembered across a process death (it widens the zone admission window for the retained history).
        state.clock().let { (peak, rollback) -> ClockGuard.restore(peak, rollback, state.rollbackAt()) { p, r, at -> state.saveClock(p, r, at) } }
        return ZoneController(
            ZoneStore(app.getSharedPreferences(ZoneStore.PREFS, Context.MODE_PRIVATE)), sink, AndroidZoneNotifier(app),
            elapsed = SystemClock::elapsedRealtime, log = { PcLog.i(TAG, it) },
            onZonesChanged = { ContextHubService.refreshLocationType(app) },
            stateStore = state,
        )
    }
}
