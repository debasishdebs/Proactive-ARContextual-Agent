package com.rokid.context.phone.place

import android.content.Context
import com.rokid.context.memory.place.GeoBox
import com.rokid.context.memory.place.PackStats
import com.rokid.context.phone.ui.settings.place.PlaceGuideControl
import com.rokid.context.work.frames.place.PlaceGraph
import com.rokid.context.work.frames.place.PlacePinning
import java.util.Locale

/** The production [PlaceGuideControl]: the switch and budget in `place_guide` prefs, the process-wide pack and the user's pins. [changed] re-checks the hub's foreground location type after the switch moves. */
class AndroidPlaceGuideControl(
    private val app: Context,
    /** The pause-zone judgement: true while a zone is active, approached or unreadable (also true when the zone state is unknown). */
    private val zoneBlocked: suspend () -> Boolean = { com.rokid.context.phone.zone.ZoneGraph.get(app).let { z -> z.approach || z.enforcing } || PlaceGraph.fence.blocked() ||
        com.rokid.context.phone.hub.HubPauses(com.rokid.context.memory.MemoryGraph.get(app).db.pauseDao()).zoneRecords().isNotEmpty() },
    /** The stored zones for the pin's own geometric check; null = unreadable. */
    private val zones: suspend () -> List<com.rokid.context.core.model.zone.PauseZone>? = { com.rokid.context.phone.zone.ZoneGraph.get(app).zonesForCheck() },
    private val fixes: PlaceFixProvider = PlaceFixProvider.create(app) { com.rokid.context.phone.zone.ZoneGraph.peek() },
    private val changed: (Context) -> Unit,
) : PlaceGuideControl {
    private val settings = com.rokid.context.work.frames.place.PlaceGuideSettings.of(app)
    override var enabled: Boolean
        get() = settings.enabled
        set(v) { settings.enabled = v; changed(app) }

    override var cardSide: com.rokid.context.core.model.PlaceSide
        get() = settings.cardSide
        set(v) { settings.cardSide = v }

    override var budgetMb: Int
        get() = settings.budgetMb
        set(v) { settings.budgetMb = v }

    override suspend fun seed(box: GeoBox): Result<com.rokid.context.phone.ui.settings.place.SeedOutcome> = runCatching {
        val id = String.format(Locale.ROOT, "seed:%.3f,%.3f,%.3f,%.3f", box.south, box.west, box.north, box.east)
        val r = PlaceGraph.readyPack(app).seedArea(id, box)
        com.rokid.context.phone.ui.settings.place.PlaceGuideModel.seedOutcome(r)
    }

    override suspend fun summary(): String {
        val (n, bytes) = PlaceGraph.readyPack(app).size()
        return "$n places in the pack (${bytes / 1024} KB)"
    }

    override suspend fun stats(): PackStats = PlaceGraph.readyPack(app).stats()
    override suspend fun applyBudget(): Int = PlaceGraph.readyPack(app).setBudget(settings.budgetMb * 1024L * 1024L)
    override suspend fun clearFetched(): Int = PlaceGraph.readyPack(app).clearFetched()

    override val pinning: PlacePinning by lazy {
        PlacePinning(
            fix = { fixes.fresh() }, zoneBlocked = zoneBlocked, zones = zones,
            nearby = { lat, lon -> PlaceGraph.readyPack(app).sitesNear(lat, lon) },
            load = { PlaceGraph.pins(app).pins() }, save = { PlaceGraph.savePins(app, it) },
            elapsed = android.os.SystemClock::elapsedRealtime, log = { com.rokid.context.core.log.PcLog.i("PcPlace", it) },
        )
    }
}
