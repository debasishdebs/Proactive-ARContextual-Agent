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

    override val extraTypes: Set<String> get() = settings.extraTypes
    override fun setExtraType(key: String, on: Boolean) { settings.setExtraType(key, on) }

    override var offlineGuess: Boolean
        get() = settings.offlineGuess
        set(v) { settings.offlineGuess = v; if (!v) com.rokid.context.phone.vision.OfflineCaptionerHolder.releaseIfCreated() }

    override var maybeCutoff: Float
        get() = settings.maybeCutoff
        set(v) { settings.maybeCutoff = v }

    override var candidateRadiusM: Int
        get() = settings.candidateRadiusM
        set(v) { settings.candidateRadiusM = v }

    // Layered confirmation: the user settings live in `place_guide` prefs, the layer switches in the Developer preference file (`pc_dev`).
    override var confirmedOnly: Boolean
        get() = settings.confirmedOnly
        set(v) { settings.confirmedOnly = v }

    override var pinCardsNearby: Boolean
        get() = settings.pinCardsNearby
        set(v) { settings.pinCardsNearby = v }

    override var checkMode: com.rokid.context.work.frames.place.CheckMode
        get() = settings.checkMode
        set(v) { settings.checkMode = v }

    override var photoMatch: com.rokid.context.memory.retrieval.PhotoMatchConfig
        get() = settings.photoMatch
        set(v) { settings.photoMatch = v }

    override fun resetPhotoMatch() { settings.resetPhotoMatch() }

    override val offlineModelReady: Boolean get() = com.rokid.context.phone.vision.OfflineCaptionerHolder.modelFiles(app) != null

    override suspend fun refPhotoLine(): String? = runCatching {
        val st = PlaceGraph.readyPack(app).imageStats()
        com.rokid.context.phone.ui.settings.place.PlaceGuideModel.refPhotoLine(st.ok, st.bytes, st.bytes >= st.capBytes)
    }.getOrNull()

    override var layerRefPhotos: Boolean
        get() = com.rokid.context.orchestration.forget.DeveloperFlags.placeRefPhotos(app)
        set(v) {
            com.rokid.context.orchestration.forget.DeveloperFlags.setPlaceRefPhotos(app, v)
            if (v) { PlacePhotoWork(app).ensure(); PlacePhotoWork(app).ensureEmbed() } else PlacePhotoWork(app).cancelAll()
        }


    override var layerPhoto: Boolean
        get() = com.rokid.context.orchestration.forget.DeveloperFlags.placeL1Photo(app)
        set(v) { com.rokid.context.orchestration.forget.DeveloperFlags.setPlaceL1Photo(app, v) }

    override var layerOnline: Boolean
        get() = com.rokid.context.orchestration.forget.DeveloperFlags.placeL2Vision(app)
        set(v) { com.rokid.context.orchestration.forget.DeveloperFlags.setPlaceL2Vision(app, v) }

    /** Layer 3 = the tier flag and the captioner flag together (the user switch and the model still apply). */
    override var layerOffline: Boolean
        get() = com.rokid.context.orchestration.forget.DeveloperFlags.offlineTier(app) && com.rokid.context.orchestration.forget.DeveloperFlags.offlineCaption(app)
        set(v) {
            com.rokid.context.orchestration.forget.DeveloperFlags.setOfflineTier(app, v); com.rokid.context.orchestration.forget.DeveloperFlags.setOfflineCaption(app, v)
            if (!v) com.rokid.context.phone.vision.OfflineCaptionerHolder.releaseIfCreated()
        }

    override var multiFrame: Boolean
        get() = com.rokid.context.orchestration.forget.DeveloperFlags.placeMultiFrame(app)
        set(v) { com.rokid.context.orchestration.forget.DeveloperFlags.setPlaceMultiFrame(app, v) }

    override var bestOfK: Boolean
        get() = settings.bestOfK
        set(v) { settings.bestOfK = v }

    override var showAllAttempts: Boolean
        get() = settings.showAllAttempts
        set(v) { settings.showAllAttempts = v }

    private val seedWork by lazy { SeedWork(app) }

    /**
     * S3: queues the seed as background work and returns at once; leaving the screen does not cancel it. Offline only still refuses here (no network call, no work). Any other network is the worker's
     * business: it waits (WAITING_NETWORK) until the admission allows, and the admission is asked again before EVERY cell and request (privacy, the global Wi-Fi-only rule, the data saver).
     */
    override suspend fun seed(box: GeoBox): Result<com.rokid.context.phone.ui.settings.place.SeedOutcome> = runCatching {
        if (com.rokid.context.ai.resolve.ModelPrivacyStore.get(app).current() != com.rokid.context.core.net.ModelPrivacy.AUTO) {
            com.rokid.context.core.log.PcLog.i("PcData", "seed refused: offline only")
            throw com.rokid.context.phone.ui.settings.place.SeedOfflineOnlyException()
        }
        val n = com.rokid.context.memory.place.Geohash5.cellsCovering(box).size
        val cap = if (com.rokid.context.orchestration.forget.DeveloperFlags.bulkSeed(app)) com.rokid.context.memory.place.PlacePack.MAX_SEED_CELLS_BULK else com.rokid.context.memory.place.PlacePack.MAX_SEED_CELLS
        if (n == 0 || n > cap) throw IllegalArgumentException("cells=$n") // counts only
        val id = String.format(Locale.ROOT, "seed:%.3f,%.3f,%.3f,%.3f", box.south, box.west, box.north, box.east)
        PlaceGraph.readyPack(app).beginArea(id, box)
        seedWork.enqueue(id)
        com.rokid.context.core.log.PcLog.i("PcPlaceSeed", "seed queued")
        com.rokid.context.phone.ui.settings.place.SeedOutcome("Seeding started in the background. You can leave this screen; progress shows here and in the notification.")
    }

    override suspend fun seedStates(): List<SeedAreaState> {
        val pack = PlaceGraph.readyPack(app)
        runCatching { seedWork.reconcile(pack) }
        return seedWork.states(pack, admission() != null).values.filter { it.active }
    }

    override suspend fun cancelSeeds() { PlaceGraph.readyPack(app).areas().forEach { seedWork.cancel(it.blueZoneId) } }

    private fun admission(): String? = seedAdmission(app)()

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
            onCreated = { id -> PlaceGraph.pinCreated.tryEmit(id) },
        )
    }

    private val refPhotos by lazy { ReferencePhotos(app) }
    override suspend fun fetchPhoto(link: String): PhotoResult = refPhotos.fromLink(link)
    override suspend fun readPhoto(uri: String): PhotoResult = refPhotos.fromUri(uri)
    override suspend fun addReferencePhoto(pinId: String, photo: com.rokid.context.work.frames.place.PreparedPhoto, description: String): Boolean = refPhotos.addTo(pinning, pinId, photo, description)
    override suspend fun referenceIndexed(momentIds: List<String>): Map<String, Boolean> = if (momentIds.isEmpty()) emptyMap() else refPhotos.indexed(momentIds)

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
    override suspend fun placeMomentCount(pinId: String): Int {
        val linked = PlaceGraph.history(app).linkedMomentIds(pinId)
        val ref = pinning.pins().firstOrNull { it.id == pinId }?.momentId
        return (linked + listOfNotNull(ref)).distinct().size
    }
    override suspend fun placeDeletePreview(pinId: String) = com.rokid.context.work.frames.place.PlaceDelete.preview(app, pinId)
    override suspend fun placeDeleteRun(preview: com.rokid.context.work.frames.place.PlaceDelete.Preview, acknowledged: Boolean) =
        com.rokid.context.work.frames.place.PlaceDelete.run(app, preview, acknowledged) { pinning.delete(it) }
    override suspend fun sweepMoments() { com.rokid.context.work.frames.place.PinMomentLinks.sweep(app) }

    /** B2: the place card history. */
    override val history: com.rokid.context.phone.ui.settings.place.PlaceHistoryControl by lazy { AndroidPlaceHistoryControl(app) }
}

/** The app's seed admission as the pack asks it before every cell and request: null = go on, else the short reason to stop. */
fun seedAdmission(app: Context): () -> String? {
    val saver = com.rokid.context.ai.datasaver.DataSaverGraph.get(app)
    return { SeedGate.check({ com.rokid.context.ai.resolve.ModelPrivacyStore.get(app).current() }, saver::costNow, saver) }
}

/**
 * The seed's admission, asked before every cell and every request: null = go on, else the short reason to stop. Fails closed (an unreadable state stops the seed).
 * Network rule (S3, owner 26 Nov): any connected network is fine by default (home mobile data or Wi-Fi); the shared data saver decision for BULK_OTHER ("Photos and big uploads") is the one global rule, so with
 * the user's "Wi-Fi only" ON only free (unmetered) Wi-Fi passes; roaming, unknown networks, Android Data Saver and the daily limit keep refusing as for every big job. No network at all = [NO_NETWORK]. Offline only stops everything.
 */
object SeedGate {
    const val OFFLINE_ONLY = "offline only"
    const val NOT_FREE_WIFI = "not on free Wi-Fi"
    const val NO_NETWORK = "no network"
    const val DATA_SAVER = "data saver"
    const val UNREADABLE = "state unreadable"

    fun check(privacy: () -> com.rokid.context.core.net.ModelPrivacy, cost: () -> com.rokid.context.core.net.NetworkCost, gate: com.rokid.context.core.net.DataGate): String? = try {
        when {
            privacy() != com.rokid.context.core.net.ModelPrivacy.AUTO -> OFFLINE_ONLY
            cost() == com.rokid.context.core.net.NetworkCost.OFFLINE -> NO_NETWORK
            else -> gate.decide(com.rokid.context.core.net.DataKind.BULK_OTHER).let { d ->
                when {
                    d.allowed -> null
                    d.reason == com.rokid.context.core.net.DataPolicy.R_WIFI_ONLY -> NOT_FREE_WIFI // the global "Wi-Fi only" is ON and the network is mobile
                    else -> DATA_SAVER
                }
            }
        }
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { UNREADABLE }
}

/** The zones map's view of Place Guide: the seeded areas and pins to draw, and the existing seed path ([guide].seed, with its Offline only and Wi-Fi refusals) for a drawn box. */
class AndroidSeedMapControl(private val app: Context, private val guide: PlaceGuideControl) : com.rokid.context.phone.ui.settings.privacy.zones.SeedMapControl {
    private val seedWork by lazy { SeedWork(app) }
    private val colours by lazy { com.rokid.context.phone.ui.settings.privacy.zones.SeedColourStore.of(app) }
    override suspend fun setColour(id: String, index: Int) { colours.set(id, index) }
    override suspend fun showPlaces() = colours.showPlaces()
    override suspend fun setShowPlaces(on: Boolean) { colours.setShowPlaces(on) }
    override suspend fun dots(box: GeoBox, cap: Int) = PlaceGraph.readyPack(app).dotsIn(box, cap)
    override suspend fun dotLabel(id: String): String? = PlaceGraph.readyPack(app).placesByIds(listOf(id)).firstOrNull()?.let { com.rokid.context.phone.ui.settings.privacy.zones.SeedDots.label(it.name, it.kind, it.summary) }
    override suspend fun areas(): List<com.rokid.context.phone.ui.settings.privacy.zones.SeedAreaInfo> {
        val pack = PlaceGraph.readyPack(app)
        runCatching { seedWork.reconcile(pack) } // an area marked seeding with no live work becomes partial
        val states = runCatching { seedWork.states(pack, seedAdmission(app)() != null) }.getOrDefault(emptyMap())
        val stats = runCatching { pack.areaStats() }.getOrDefault(emptyMap())
        val photoNote = PlacePhotoText.note(PhotoPhaseStore(app).get())
        val matchCounts = runCatching { PlacePhotoWork(app).embedCounts() }.getOrDefault(0 to 0) // (downloaded, matched): one line for every area while matching runs
        return pack.areas().map {
            val st = states[it.blueZoneId]
            val live = st?.takeIf { s -> s.active }
            val failed = st?.takeIf { s -> s.phase == SeedPhase.FAILED }?.failedCells ?: 0
            // a row still marked seeding with no live work is shown as partial at once (reconcile rewrites it after its grace): it can never read "(seeding)" forever
            val status = if (it.status == com.rokid.context.memory.place.AREA_SEEDING && live == null) com.rokid.context.memory.place.AREA_PARTIAL else it.status
            // The state is read from the stored cells, not guessed: an area from an older build that is marked partial but has every cell stored is Done; otherwise it says how many cells did not seed.
            val box = it.box
            val check = live == null && status != com.rokid.context.memory.place.AREA_DONE && com.rokid.context.phone.ui.settings.privacy.zones.SeedEstimate.cells(box) in 1..com.rokid.context.phone.ui.settings.privacy.zones.SeedAreaList.TRUTH_MAX_CELLS
            val wanted = if (check) com.rokid.context.memory.place.Geohash5.cellsCovering(box) else emptyList()
            val stored = if (check) runCatching { pack.cellStatuses(box) }.getOrNull() else null
            val t = com.rokid.context.phone.ui.settings.privacy.zones.SeedAreaList.truth(status, live != null, st?.phase, failed, wanted, stored)
            val noOsm = if (t.status == com.rokid.context.memory.place.AREA_DONE && stored == null) (st?.counts?.noOsm ?: 0) else t.noOsm
            com.rokid.context.phone.ui.settings.privacy.zones.SeedAreaInfo(it.blueZoneId, it.south, it.west, it.north, it.east, t.status, it.name, live, stats[it.blueZoneId]?.places, stats[it.blueZoneId]?.bytes ?: 0, it.seededAt, t.failed, t.cancelled, noOsm, savedColour = colours.get(it.blueZoneId), photos = stats[it.blueZoneId]?.let { x -> com.rokid.context.phone.ui.settings.privacy.zones.AreaPhotos(x.photoOk, x.photoNone, x.places, photoNote, matchCounts.second, matchCounts.first) })
        }
    }
    override suspend fun rates(): com.rokid.context.phone.ui.settings.privacy.zones.SeedRates {
        val pack = PlaceGraph.readyPack(app)
        val cells = pack.areas().flatMap { com.rokid.context.memory.place.Geohash5.cellsCovering(it.box) }.toSet().size
        val st = pack.areaStats().values
        return com.rokid.context.phone.ui.settings.privacy.zones.SeedRates.of(cells, st.sumOf { it.places }, st.sumOf { it.bytes }, SeedTimingStore(app).secondsPerCell())
    }
    override suspend fun cancelSeed(id: String) { seedWork.cancel(id) }
    override suspend fun retryPhotos() { PlacePhotoWork(app).retry() }
    override suspend fun retry(id: String): Result<com.rokid.context.phone.ui.settings.place.SeedOutcome> = runCatching {
        if (com.rokid.context.ai.resolve.ModelPrivacyStore.get(app).current() != com.rokid.context.core.net.ModelPrivacy.AUTO) {
            com.rokid.context.core.log.PcLog.i("PcData", "seed refused: offline only")
            throw com.rokid.context.phone.ui.settings.place.SeedOfflineOnlyException()
        }
        val pack = PlaceGraph.readyPack(app)
        val a = pack.areas().firstOrNull { it.blueZoneId == id } ?: throw IllegalArgumentException("area gone")
        pack.beginArea(id, a.box) // the area's own id and box; cells that are fresh are skipped, so only the failed ones are fetched
        seedWork.enqueue(id)
        com.rokid.context.core.log.PcLog.i("PcPlaceSeed", "seed retry queued")
        com.rokid.context.phone.ui.settings.place.SeedOutcome("Retrying the failed cells in the background.")
    }
    override suspend fun reseed(id: String, box: GeoBox): Result<com.rokid.context.phone.ui.settings.place.SeedOutcome> = runCatching {
        if (com.rokid.context.ai.resolve.ModelPrivacyStore.get(app).current() != com.rokid.context.core.net.ModelPrivacy.AUTO) {
            com.rokid.context.core.log.PcLog.i("PcData", "seed refused: offline only")
            throw com.rokid.context.phone.ui.settings.place.SeedOfflineOnlyException()
        }
        val n = com.rokid.context.memory.place.Geohash5.cellsCovering(box).size
        val cap = if (com.rokid.context.orchestration.forget.DeveloperFlags.bulkSeed(app)) com.rokid.context.memory.place.PlacePack.MAX_SEED_CELLS_BULK else com.rokid.context.memory.place.PlacePack.MAX_SEED_CELLS
        if (n == 0 || n > cap) throw IllegalArgumentException("cells=$n") // counts only
        val pack = PlaceGraph.readyPack(app)
        if (pack.areas().none { it.blueZoneId == id }) throw IllegalArgumentException("area gone")
        // The SAME area id (its name and colour stay) takes the new rectangle. Nothing is deleted: places stored before stay, also those outside a smaller box. The pack skips cells that are fresh.
        pack.beginArea(id, box)
        seedWork.enqueue(id)
        com.rokid.context.core.log.PcLog.i("PcPlaceSeed", "seed edit queued")
        com.rokid.context.phone.ui.settings.place.SeedOutcome("Seeding the changed box in the background. Places already fetched are kept.")
    }
    override suspend fun rename(id: String, name: String?) = PlaceGraph.readyPack(app).setAreaName(id, name)
    override suspend fun pins() = guide.pinning?.pins().orEmpty().map { com.rokid.context.phone.ui.settings.privacy.zones.PinInfo(it.lat, it.lon, it.radiusM) }
    override suspend fun seed(box: GeoBox) = guide.seed(box)
    override suspend fun remove(id: String, withPlaces: Boolean) { seedWork.cancel(id); PlaceGraph.readyPack(app).removeArea(id, withPlaces); colours.clear(id) }
    override suspend fun cellStatuses(box: GeoBox) = PlaceGraph.readyPack(app).cellStatuses(box)
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
    override val historyOn: Boolean get() = com.rokid.context.orchestration.forget.DeveloperFlags.placeCardHistory(app)
    private suspend fun placesOf(row: com.rokid.context.memory.db.PlaceCardLogEntity) =
        PlaceGraph.readyPack(app).placesByIds(com.rokid.context.work.frames.place.PlaceCardRebuild.idsOf(row))

    override val changes: kotlinx.coroutines.flow.Flow<Unit> = com.rokid.context.work.frames.place.PlaceCardHistory.changes.map { }

    /** Off: nothing is read and `places.db` is never opened for the list. */
    override suspend fun rows() = if (!historyOn) emptyList() else history().recent()

    override suspend fun line(row: com.rokid.context.memory.db.PlaceCardLogEntity): String? =
        com.rokid.context.work.frames.place.PlaceCardRebuild.card(row, placesOf(row))?.line

    /** B2: the credit of the stored reference photo of the first cited place that has one. Public Commons credit only; no vector, no bytes. */
    override suspend fun credit(row: com.rokid.context.memory.db.PlaceCardLogEntity): String? {
        val ids = com.rokid.context.work.frames.place.PlaceCardRebuild.idsOf(row)
        val img = PlaceGraph.readyPack(app).imagesOf(ids).firstOrNull { it.status == com.rokid.context.memory.place.ImageState.OK } ?: return null
        return com.rokid.context.phone.ui.settings.place.PlaceHistoryModel.creditLine(img.author, img.licence)
    }

    override suspend fun photoPath(frameId: String): String? =
        com.rokid.context.memory.MemoryGraph.get(app).db.frameDao().byIds(listOf(frameId)).firstOrNull()?.path?.takeIf { it.isNotBlank() && java.io.File(it).exists() }

    override suspend fun delete(id: String) = history().delete(id)
    override suspend fun setLabel(id: String, label: String?) = history().setLabel(id, label)
    override suspend fun labelSummary() = if (!historyOn) null else history().labelSummary()

    override fun showAgainBlock(row: com.rokid.context.memory.db.PlaceCardLogEntity): String? =
        if (!historyOn) com.rokid.context.phone.ui.settings.place.PlaceHistoryModel.OFF_LINE else
        com.rokid.context.phone.ui.settings.place.PlaceHistoryModel.showAgainBlock(row.kind, row.frameId, row.photoGone == 1, com.rokid.context.phone.ui.settings.place.ShowAgainEnv(enabled(), zoneBlocked(), glassesConnected()))

    override suspend fun showAgain(row: com.rokid.context.memory.db.PlaceCardLogEntity): Boolean {
        if (!historyOn || showAgainBlock(row) != null) return false
        // P1-3: the epoch and the wipe generation are captured BEFORE the pack is read (PlaceShowAgain), and the row, its photo and both authorities are asked again right before the fenced publication and the insertion.
        return com.rokid.context.work.frames.place.PlaceShowAgain(
            holder = PlaceGraph.port, history = ::history, places = ::placesOf, generation = { com.rokid.context.orchestration.MemoryWipe.generation.get() },
            rowAlive = { r ->
                history().get(r.id) != null && (r.frameId == null ||
                    com.rokid.context.provenance.ProvenanceGate(com.rokid.context.memory.MemoryGraph.get(app).db).goneFrames(setOf(r.frameId!!), requirePresent = false).isEmpty())
            },
            fenceBlocked = { PlaceGraph.fence.blocked() },
        ).run(row)
    }
}
