package com.rokid.context.phone.place

import kotlinx.coroutines.flow.map
import android.content.Context
import com.rokid.context.core.model.carriesData
import com.rokid.context.memory.place.GeoBox
import com.rokid.context.memory.place.PackStats
import com.rokid.context.phone.ui.settings.place.MomentChoice
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
        // Model privacy: under Offline only no place is fetched from the internet, a user-started seed included.
        if (com.rokid.context.ai.resolve.ModelPrivacyStore.get(app).current() != com.rokid.context.core.net.ModelPrivacy.AUTO) {
            com.rokid.context.core.log.PcLog.i("PcData", "seed refused: offline only")
            throw com.rokid.context.phone.ui.settings.place.SeedOfflineOnlyException()
        }
        // Roaming data saver: a seed is hundreds of requests; only on free Wi-Fi (never mobile, roaming, unknown or a captive portal).
        if (com.rokid.context.ai.datasaver.DataSaverGraph.get(app).costNow() != com.rokid.context.core.net.NetworkCost.FREE) {
            com.rokid.context.core.log.PcLog.i("PcData", "seed refused: not free wifi")
            throw com.rokid.context.phone.ui.settings.place.SeedNeedsWifiException()
        }
        // The same questions are asked again before EVERY cell and every request of the seed (the pack and PlaceHttp call the admission): privacy, free Wi-Fi and the data saver can all change mid-seed.
        admission()?.let { throw com.rokid.context.memory.place.SeedStopped(it) }
        val id = String.format(Locale.ROOT, "seed:%.3f,%.3f,%.3f,%.3f", box.south, box.west, box.north, box.east)
        val r = PlaceGraph.readyPack(app).seedArea(id, box, admit = ::admission)
        com.rokid.context.phone.ui.settings.place.PlaceGuideModel.seedOutcome(r)
    }

    private fun admission(): String? {
        val saver = com.rokid.context.ai.datasaver.DataSaverGraph.get(app)
        return SeedGate.check({ com.rokid.context.ai.resolve.ModelPrivacyStore.get(app).current() }, saver::costNow, saver)
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
            momentAlive = { id -> momentDao().photoMomentsOf(listOf(id)).isNotEmpty() },
        )
    }

    private fun momentDao() = com.rokid.context.memory.MemoryGraph.get(app).db.momentDao()
    private fun com.rokid.context.memory.db.PhotoMomentRow.choice() = MomentChoice(momentId, path, atMs)

    override suspend fun recentMoments(limit: Int): List<MomentChoice> = momentDao().recentPhotoMoments(limit).map { it.choice() }
    override suspend fun momentsOf(ids: List<String>): Map<String, MomentChoice> = if (ids.isEmpty()) emptyMap() else momentDao().photoMomentsOf(ids).associate { it.momentId to it.choice() }
    override suspend fun momentsBetween(fromMs: Long, toMs: Long, limit: Int, beforeId: String?): List<MomentChoice> {
        val lo = com.rokid.context.memory.moment.MomentId.format(Math.floorDiv(maxOf(fromMs, 0L), 1000L))
        val hi = com.rokid.context.memory.moment.MomentId.format(Math.floorDiv(maxOf(toMs, 1L) - 1, 1000L).coerceAtLeast(0))
        return momentDao().photoMomentsBetween(lo, hi, limit, beforeId).map { it.choice() }
    }
    override suspend fun setMoment(pinId: String, momentId: String?): Boolean = pinning.setMoment(pinId, momentId)
    /** A link whose Moment was forgotten is cleared whenever the screen reads the pins (the Forget listener does it at the time; this also covers a crash in between). */
    override suspend fun sweepMoments() { com.rokid.context.work.frames.place.PinMomentLinks.sweep(app) }

    /** B2: the place card history. */
    override val history: com.rokid.context.phone.ui.settings.place.PlaceHistoryControl by lazy { AndroidPlaceHistoryControl(app) }
}

/** The seed's admission, asked before every cell and every request: null = go on, else the short reason to stop. Fails closed (an unreadable state stops the seed). */
object SeedGate {
    const val OFFLINE_ONLY = "offline only"
    const val NOT_FREE_WIFI = "not on free Wi-Fi"
    const val DATA_SAVER = "data saver"
    const val UNREADABLE = "state unreadable"

    fun check(privacy: () -> com.rokid.context.core.net.ModelPrivacy, cost: () -> com.rokid.context.core.net.NetworkCost, gate: com.rokid.context.core.net.DataGate): String? = try {
        when {
            privacy() != com.rokid.context.core.net.ModelPrivacy.AUTO -> OFFLINE_ONLY
            cost() != com.rokid.context.core.net.NetworkCost.FREE -> NOT_FREE_WIFI
            !gate.decide(com.rokid.context.core.net.DataKind.BULK_OTHER).allowed -> DATA_SAVER
            else -> null
        }
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { UNREADABLE }
}

/** The zones map's view of Place Guide: the seeded areas and pins to draw, and the existing seed path ([guide].seed, with its Offline only and Wi-Fi refusals) for a drawn box. */
class AndroidSeedMapControl(private val app: Context, private val guide: PlaceGuideControl) : com.rokid.context.phone.ui.settings.privacy.zones.SeedMapControl {
    override suspend fun areas() = PlaceGraph.readyPack(app).areas().map { com.rokid.context.phone.ui.settings.privacy.zones.SeedAreaInfo(it.blueZoneId, it.south, it.west, it.north, it.east, it.status) }
    override suspend fun pins() = guide.pinning?.pins().orEmpty().map { com.rokid.context.phone.ui.settings.privacy.zones.PinInfo(it.lat, it.lon, it.radiusM) }
    override suspend fun seed(box: GeoBox) = guide.seed(box)
    override suspend fun remove(id: String) { PlaceGraph.readyPack(app).removeArea(id) }
}

/**
 * The production [com.rokid.context.phone.ui.settings.place.PlaceHistoryControl] (B2): the phone-only place card history in `places.db`, the card line re-read from the pack by place id, the photo only while its
 * frame row still exists, and "Show on glasses again" through the same fenced holder the sampler publishes through. Never logs a name, an id or a position.
 */
class AndroidPlaceHistoryControl(
    private val app: Context,
    private val zoneBlocked: () -> Boolean = { com.rokid.context.phone.zone.ZoneGraph.get(app).let { z -> z.approach || z.enforcing } || PlaceGraph.fence.blocked() },
    private val glassesConnected: () -> Boolean = { com.rokid.context.phone.hub.HubGraph.get(app).state.value.link.carriesData },
    private val enabled: () -> Boolean = { com.rokid.context.work.frames.place.PlaceGuideSettings.of(app).enabled },
) : com.rokid.context.phone.ui.settings.place.PlaceHistoryControl {
    private fun history() = PlaceGraph.history(app)
    private suspend fun placesOf(row: com.rokid.context.memory.place.PlaceCardLogEntity) =
        PlaceGraph.readyPack(app).placesByIds(com.rokid.context.work.frames.place.PlaceCardRebuild.idsOf(row))

    override val changes: kotlinx.coroutines.flow.Flow<Unit> = com.rokid.context.work.frames.place.PlaceCardHistory.changes.map { }

    override suspend fun rows() = history().recent()

    override suspend fun line(row: com.rokid.context.memory.place.PlaceCardLogEntity): String? =
        com.rokid.context.work.frames.place.PlaceCardRebuild.card(row, placesOf(row))?.line

    override suspend fun photoPath(frameId: String): String? =
        com.rokid.context.memory.MemoryGraph.get(app).db.frameDao().byIds(listOf(frameId)).firstOrNull()?.path?.takeIf { it.isNotBlank() && java.io.File(it).exists() }

    override suspend fun delete(id: String) = history().delete(id)

    override fun showAgainBlock(row: com.rokid.context.memory.place.PlaceCardLogEntity): String? =
        com.rokid.context.phone.ui.settings.place.PlaceHistoryModel.showAgainBlock(row.kind, row.frameId, row.photoGone == 1, com.rokid.context.phone.ui.settings.place.ShowAgainEnv(enabled(), zoneBlocked(), glassesConnected()))

    override suspend fun showAgain(row: com.rokid.context.memory.place.PlaceCardLogEntity): Boolean {
        if (showAgainBlock(row) != null) return false
        val card = com.rokid.context.work.frames.place.PlaceCardRebuild.card(row, placesOf(row)) ?: return false
        if (PlaceGraph.fence.blocked()) return false // the fence's own word, read again at the moment of publication
        val id = PlaceGraph.port.publishIfId(card, PlaceGraph.port.epoch()) ?: return false
        // A user act: a new card id and a new history row of the same kind (the hub reports its delivery like any card).
        history().add(com.rokid.context.work.frames.place.PlaceHistoryEvent(
            kind = runCatching { com.rokid.context.work.frames.place.HistoryKind.valueOf(row.kind) }.getOrDefault(com.rokid.context.work.frames.place.HistoryKind.PHOTO),
            placeId = row.placeId, title = row.title, members = com.rokid.context.work.frames.place.PlaceCardRebuild.members(row), frameId = row.frameId, cardId = id,
        ))
        return true
    }
}
