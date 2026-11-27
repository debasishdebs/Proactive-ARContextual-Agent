package com.rokid.context.memory.place

import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.max

/** A place as a source returned it: no flags, no clock. The pack stamps those. */
data class FetchedPlace(
    val id: String, val parentId: String?, val name: String, val kind: String,
    val lat: Double, val lon: Double, val radiusM: Int, val summary: String, val source: String, val sourceRev: String,
)

/** One cell's answer. [complete] is false when a source that may be missing (Overpass) did not answer: the cell then counts as fresh only for a short backoff ([PlacePack.partialFreshMs]: 5 min, doubling per consecutive partial answer up to 1 h). */
data class CellFetch(val places: List<FetchedPlace>, val complete: Boolean = true)

/**
 * Where a cell's places come from. Receives ONLY the 5 km cell key (the implementation derives the box from it): the exact position never reaches a source. Throws when the cell
 * could not be fetched at all; nothing is stored then and the cell is tried again at the next glance.
 */
fun interface CellSource {
    suspend fun fetchCell(cell: String): CellFetch
}

/**
 * The bulk discovery of a seed (owner decision 27 Nov): ALL qualifying places inside one [GeoBox] in a few calls (production: Wikidata SPARQL box query, then batched Wikipedia intros), already class-ruled, coordinate-checked and with
 * their intro text. The pack stores them through the same rows as the per-cell path. Throws [BulkTooBig] when the box should be split (timeout, 504, too many rows); any other exception sends the affected cells to the per-cell path.
 */
fun interface BulkSource {
    suspend fun fetchBox(box: GeoBox): List<FetchedPlace>
}

/** A bulk box that timed out or returned more rows than one query may carry: the pack splits it in two and asks again. */
class BulkTooBig(why: String) : java.io.IOException(why)

/** The pack as the budget screen shows it. [pinned] counts user pins; [seeded] and [fetched] exclude pinned rows. */
/** The seeded places in one area's box (S5). */
data class AreaStats(val places: Int, val bytes: Long, /** B2: reference photos of this area's seeded places: with a photo, and without one (none free, skipped or failed for good). The rest are still waiting. */ val photoOk: Int = 0, val photoNone: Int = 0)

/** B2: seeded places [seeded], those with a photo [ok] and those without one for good [none]. */
data class PhotoTotals(val seeded: Int, val ok: Int, val none: Int) { val waiting: Int get() = (seeded - ok - none).coerceAtLeast(0) }

/** B1: the reference photos for the budget screen: all rows, rows with a photo state OK, bytes (thumbnails + vectors) and the cap. */
data class ImageStats(val rows: Int, val ok: Int, val bytes: Long, val capBytes: Long)

data class PackStats(val pinned: Int, val seeded: Int, val fetched: Int, val bytes: Long, val budgetBytes: Long)

/**
 * A seed was stopped before it finished ([reason] is a fixed short phrase, never a position or a place name): the privacy mode, the free Wi-Fi rule or the data saver no longer admit it, or the
 * area was removed. A cancellation, so it unwinds every fetch like the zone fence does. The message IS the reason (a recovered copy of the exception keeps it).
 */
class SeedStopped(val reason: String) : CancellationException(reason)

/** The seed's admission, in the coroutine context of a running [PlacePack.seedArea]: [check] returns null while the seed may go on, else the reason it must stop. Asked before every cell and every request. */
class SeedAdmission(val check: () -> String?) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<SeedAdmission>
}

/** Throws [SeedStopped] when the running seed (if any) is no longer admitted; a no-op outside a seed. Fails closed: a check that throws stops the seed. Does not suspend. */
suspend fun seedAdmitCheck() {
    val a = coroutineContext[SeedAdmission] ?: return
    val why = try { a.check() } catch (e: CancellationException) { throw e } catch (e: Exception) { "state unreadable" }
    if (why != null) throw SeedStopped(why)
}

/** A place request refused because Model privacy is Offline only (or unreadable: fail closed). Typed so the pack treats it as "no network" ([CellOutcome.OFFLINE]), never as a failure to retry or back off. */
class PlaceOfflineOnly : java.io.IOException("offline_only")

enum class CellOutcome { FRESH, FETCHED, PARTIAL, OFFLINE, FAILED }

/** What a glance asks for: the candidates (already expanded to sub-places) and how the cell stood. */
data class PlaceCandidates(
    val places: List<PlaceEntity>, val outcome: CellOutcome,
    /** EVERY active user pin within reach (no cap), nearest first: the pin-first ambiguity is judged against these, not against the capped model list in [places]. Empty = not supplied (the pins in [places] are used). */
    val nearbyPins: List<PlaceEntity> = emptyList(),
)

/** Live tally of one seed (counts only): [done] cells processed (ok + [failed]), of [total]; [partial] = ok cells stored without OSM extras (see [SeedTally]); [fetched] = cells fetched (not fresh) so far in this run. */
data class SeedCounts(val done: Int, val total: Int, val failed: Int, val partial: Int, val fetched: Int)

/**
 * What a seed did. [fetched] = [ok] + [partial] (cells stored); [partial] cells were stored without OSM extras (Overpass did not answer): they count as DONE for seeding (Wikipedia and Wikidata
 * answered), a seed or "Retry failed cells" does not fetch them again for 30 days, and a glance on free Wi-Fi fills the OSM extras in after its backoff (5 min up to 1 h). [partialFresh] = cells that were
 * already stored partial (younger than 30 days) and skipped by this seed; [withoutOsm] = both; [failed] cells stored nothing even after the retries ([PlacePack.MAX_ATTEMPTS] tries with a growing wait); [skipped] were already fresh. [retried] counts the cells the retry pass took (whatever its outcome).
 */
data class SeedResult(val cells: Int, val fetched: Int, val skipped: Int, val failed: Int, val places: Int, val partial: Int = 0, val retried: Int = 0, val partialFresh: Int = 0) {
    val ok: Int get() = fetched - partial
    /** Cells stored without OSM extras (Overpass missing), fetched now or already stored. They count as ok. */
    val withoutOsm: Int get() = partial + partialFresh
    /** Cells that need another try: only the [failed] ones (nothing stored). A cell without OSM extras is done for seeding. */
    val retryCells: Int get() = failed
    val complete: Boolean get() = failed == 0
}

/**
 * The place pack (spec 2026-11-16 §B3): a cache-aside store of place text keyed by 5 km geohash cell.
 *
 * - **Cache-aside**: [candidates] fetches the glance's own cell when it is missing or older than [FRESH_MS] (30 days) and the network is up; offline, whatever is stored is used.
 * - **Budget**: [budgetBytes] (default 50 MB); past it the least recently used fetched places go first (`lastUsedDay`, then the older fetch). Seeded and pinned places are never evicted.
 * - **Seed**: [seedArea] fetches a blue zone cell by cell before the trip and marks what it finds seeded.
 * - **Delete all** ([deleteAll]): clears the fetched places and the recency, keeps the seeded areas (and what they seeded, and pinned places). It touches nothing outside `places.db`:
 *   pause zones are not here.
 *
 * Nothing here keeps a position. Logs carry counts and outcome names only.
 */
class PlacePack(
    private val db: PlacesDatabase,
    private val source: CellSource?,
    budgetBytes: Long = DEFAULT_BUDGET_BYTES,
    private val now: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
    /** The wait before the seed's retry pass (the public servers answer 429 when asked too fast); tests pass a no-op. */
    private val pause: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
    /** The process-wide request limiter and counters (the HTTP layer shares the same instance in production); this pack only reads it for the seed's summary lines. */
    private val net: PlaceNetGuard = PlaceNetGuard(log = log),
    /** The bulk discovery path (null = none): used only by a seed that asks for it ([seedArea]'s `bulk`). */
    private val bulk: BulkSource? = null,
    /** Called AFTER places were deleted (area removal, Clear fetched, Delete all), outside the lock and the transaction: production drops the context.db `place_link` REF rows of places that are gone ([PlaceRefSweep]). A failure is ignored (the start-up sweep catches up). */
    private val onPlacesRemoved: suspend (PlacePack) -> Unit = {},
    /** The reference-photo budget (tests lower it). */
    private val imageCapBytes: Long = IMAGE_CAP_BYTES,
) {
    private suspend fun removed() { try { onPlacesRemoved(this) } catch (c: kotlinx.coroutines.CancellationException) { throw c } catch (_: Exception) { } }
    /** The ids among [ids] that still exist as a place (read only). */
    suspend fun existingIds(ids: List<String>): Set<String> = chunked(ids.distinct()) { dao.byIds(it) }.map { it.id }.toSet()

    private val dao = db.dao()

    /** The budget in bytes: [setBudget] changes it (the Place Guide screen offers 25/50/100/200 MB). */
    @Volatile var budgetBytes: Long = budgetBytes.coerceAtLeast(MIN_BUDGET_BYTES); private set
    private val fetchLock = Mutex()
    /** The running seed per area id (its own child job): [removeArea] cancels and awaits it so a seed's last write cannot bring a removed area back. */
    /** EVERY running seed per area (two callers may seed one area at once): a removal cancels and joins all of them. */
    private val seeds = java.util.concurrent.ConcurrentHashMap<String, Set<Job>>()
    /** Advanced by [deleteAll] before it takes [fetchLock]: a fetch that began earlier discards its answer instead of re-inserting what Delete all removed. */
    private val wipeEpoch = java.util.concurrent.atomic.AtomicLong(0)
    /**
     * Consecutive partial answers per cell (in memory only: a restart starts again at the 5 min step, which is the safe side). Drives [partialFreshMs]. Removed when the cell comes back
     * complete and by Delete all / Clear fetched. Holds cell keys (5 km positions) in memory only: never logged, never stored.
     */
    private val partialStreak = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** The pack's size (count, bytes), for the Trips screen. */
    suspend fun size(): Pair<Int, Long> = dao.placeCount() to dao.totalBytes()

    suspend fun areas(): List<AreaEntity> = dao.areas()

    /** S5: per area id, how many seeded places lie in its box and their text bytes (read only, from places.db, no network). User pins are not counted. */
    suspend fun areaStats(): Map<String, AreaStats> = dao.areas().associate { a ->
        val ps = dao.inBox(a.south, a.north, a.west, a.east).filter { it.seeded && !it.id.startsWith("user:") }
        val st = dao.imageStatusIn(a.south, a.north, a.west, a.east).associate { it.status to it.n }
        a.blueZoneId to AreaStats(ps.size, ps.sumOf { it.bytes.toLong() }, st[ImageState.OK] ?: 0, (st[ImageState.NONE] ?: 0) + (st[ImageState.SKIPPED] ?: 0) + (st[ImageState.FAILED] ?: 0))
    }

    /** B2: all seeded places (never a user pin) and how many of them have a photo / have none, for the budget line and the phase's "N of M". Read only. */
    suspend fun photoTotals(): PhotoTotals {
        val st = dao.imageStatusAll().associate { it.status to it.n }
        return PhotoTotals(dao.photoSeededCount(), st[ImageState.OK] ?: 0, (st[ImageState.NONE] ?: 0) + (st[ImageState.SKIPPED] ?: 0) + (st[ImageState.FAILED] ?: 0))
    }

    /** v18 WP11b: names (or, with null or blank, un-names) a seed area; trimmed and bounded. False when no such area. The rename screen is a later WP. */
    suspend fun setAreaName(blueZoneId: String, name: String?): Boolean = fetchLock.withLock { dao.setAreaName(blueZoneId, name?.trim()?.take(MAX_AREA_NAME)?.takeIf { it.isNotEmpty() }) > 0 }

    /** Map zones "Show cells" (read only): the stored status of each 5 km cell that touches [box] (a cell with no row is absent). Nothing is fetched. */
    suspend fun cellStatuses(box: GeoBox): Map<String, String> {
        val want = Geohash5.cellsCovering(box).toHashSet()
        if (want.isEmpty()) return emptyMap()
        return dao.cells().filter { it.geohash5 in want }.associate { it.geohash5 to it.status }
    }

    /** The map dots inside [box] (read only, nothing is fetched, no lock, no log): at most [limit] stored places, never user pins. A box that is not valid gives none. */
    suspend fun dotsIn(box: GeoBox, limit: Int): List<PlaceDot> =
        if (!box.valid || limit <= 0) emptyList() else dao.dotsIn(box.south, box.north, box.west, box.east, limit)

    /** Read-only lookup by id (v3.0 shadow loop: where a recalled place is, for the zone check). */
    suspend fun placesByIds(ids: List<String>): List<PlaceEntity> = chunked(ids) { dao.byIds(it) }

    /** What is in the pack, for the budget screen: pinned (user pins and pinned places), seeded (not pinned) and fetched (neither), and the bytes. */
    suspend fun stats(): PackStats = PackStats(dao.pinnedCount(), dao.seededCount(), dao.fetchedCount(), dao.totalBytes(), budgetBytes)

    /** Changes the budget and evicts least-recently-used FETCHED places at once until the pack fits (seeded and pinned stay even when that is not enough). Returns how many were evicted. */
    suspend fun setBudget(bytes: Long): Int = fetchLock.withLock {
        budgetBytes = bytes.coerceAtLeast(MIN_BUDGET_BYTES)
        db.withTransaction { evictToBudget(keepIds = emptyList(), keepCell = "") }.also { log("budget set evicted=$it") }
    }

    /**
     * Makes the user's pins (the [UserPinStore] list, the source of truth) the `user:` rows of the pack: new ones are inserted pinned, edited ones replaced (their recency kept), removed ones
     * deleted. Nothing but `user:` rows is touched, and a fetch can never write one (see [fetchAndStore]).
     */
    suspend fun replaceUserPlaces(pins: List<UserPlace>) {
        val good = pins.filter { it.isValid }.distinctBy { it.id }
        db.withTransaction {
            val old = dao.userPlaces().associateBy { it.id }
            dao.putPlaces(good.map { it.toEntity(old[it.id]?.lastUsedDay ?: 0) })
            eachChunk(old.keys.filter { k -> good.none { it.id == k } }) { dao.deletePlaces(it) }
            dao.deleteOrphanRefs(); dao.deleteOrphanImages()
        }
        log("user places synced count=${good.size}")
    }

    /**
     * "Clear fetched places": removes the fetched places (not seeded, not pinned: user pins stay) and forgets the cells outside the seeded areas so those are fetched again. Recency is kept (unlike
     * [deleteAll]). Returns how many places were removed.
     */
    suspend fun clearFetched(): Int = fetchLock.withLock { wipeEpoch.incrementAndGet(); db.withTransaction { clearFetchedLocked(resetRecency = false) } }.also { removed() }

    /**
     * Makes the cell fresh when it is not, and says how it stands. One fetch at a time.
     * [costly] (roaming data saver): the phone is on mobile data, roaming or an unknown network. A STORED cell that a seeded area covers, or that was stored partial (no OSM extras), is then used
     * as it is however old it is, and not fetched again: the refetch is three public APIs per 5 km cell, and a seeded area is exactly what the user downloaded on Wi-Fi to avoid that. It
     * is fetched again when the network is not costly. A cell that is not stored at all, or a stale cell outside every seeded area, is still fetched (a small request). The outcome is [CellOutcome.OFFLINE]:
     * "stored data used, not refreshed".
     */
    suspend fun ensureCell(cell: String, networkUp: Boolean, costly: Boolean = false): CellOutcome {
        if (!Geohash5.isCell(cell)) return CellOutcome.FAILED
        val stored = dao.cell(cell)
        if (isFresh(stored)) return CellOutcome.FRESH
        if (costly && stored != null && keepWhileCostly(stored)) {
            val seeded = stored.status == CELL_OK // an OK cell is only kept here when a seeded area covers it; the other kept cells are partial
            log("cell stale kept while costly seeded=$seeded") // a count-style line: the cell key is a position and is never logged
            return CellOutcome.OFFLINE
        }
        val src = source
        if (!networkUp || src == null) return CellOutcome.OFFLINE
        zoneCheck() // an attempt whose zone fence closed never queues for the lock
        return fetchLock.withLock {
            zoneCheck() // ... and one that waited for it asks again: the fence may have closed during the wait
            if (isFresh(dao.cell(cell))) return@withLock CellOutcome.FRESH // another caller fetched it while we waited
            fetchAndStore(cell, src, seeded = false)
        }
    }

    /**
     * The candidates for a glance at (lat, lon): the glance's cell is made fresh (cache-aside), then the places whose edge is within 300 m, the 8 nearest, plus the sub-places of
     * any that is a site. Every returned place gets today's `lastUsedDay`. The position is used for the lookup only; it is never stored or logged.
     */
    suspend fun candidates(lat: Double, lon: Double, networkUp: Boolean, costly: Boolean = false): PlaceCandidates {
        val t0 = now()
        val outcome = ensureCell(Geohash5.encode(lat, lon), networkUp, costly)
        val w = Geo.window(lat, lon, Geo.CANDIDATE_GAP_M + PlaceKinds.MAX_RADIUS_M)
        val inBox = dao.inBox(w.south, w.north, w.west, w.east)
        val hits = Geo.candidates(inBox, lat, lon).map { it.place }
        val all = if (hits.isEmpty()) hits else Geo.expandSubPlaces(hits, chunked(hits.map { it.id }) { dao.childrenOf(it) })
        if (all.isNotEmpty()) { val day = day(); eachChunk(all.map { it.id }) { dao.touch(it, day) } }
        val pins = all.count { UserPlace.isUserId(it.id) }
        log("pack lookup cell=${outcome.name} online=$networkUp inWindow=${inBox.size} hits=${hits.size} expanded=${all.size} pins=$pins ms=${now() - t0}") // counts and the cell's state only: never the cell key (a 5 km position) or a place
        val everyPin = Geo.candidates(inBox.filter { UserPlace.isUserId(it.id) }, lat, lon, limit = Int.MAX_VALUE).map { it.place } // uncapped: the model list keeps only PIN_SLOTS of them
        return PlaceCandidates(all, outcome, everyPin)
    }

    /** The pack places (never user pins) whose edge is within [maxGapM] of (lat, lon): the sites a new pin may hang under, nearest first, at most [limit]. Reads only; the position is not kept. */
    suspend fun sitesNear(lat: Double, lon: Double, maxGapM: Double = 1_000.0, limit: Int = 12): List<PlaceEntity> {
        val w = Geo.window(lat, lon, maxGapM + PlaceKinds.MAX_RADIUS_M)
        return dao.inBox(w.south, w.north, w.west, w.east).asSequence().filter { !UserPlace.isUserId(it.id) && it.kind in PlaceKinds.ALL }
            .map { it to Geo.distanceM(lat, lon, it.lat, it.lon) - it.radiusM }.filter { it.second <= maxGapM }
            .sortedWith(compareBy({ it.second }, { it.first.id })).map { it.first }.take(limit).toList()
    }

    /**
     * Fetches the blue zone [bbox] cell by cell and marks everything found seeded. A cell that is already fresh is not fetched again but its places are marked. A cell that fails is
     * counted and the rest go on: the area ends [AREA_PARTIAL] and can be seeded again. [onProgress] gets (cells done, cells total).
     */
    suspend fun seedArea(blueZoneId: String, bbox: GeoBox, admit: () -> String? = { null }, onCellMs: (Long) -> Unit = {}, onCounts: (SeedCounts) -> Unit = {}, bulk: Boolean = false, onProgress: (Int, Int) -> Unit = { _, _ -> }): SeedResult {
        val cells = Geohash5.cellsCovering(bbox)
        val src = source
        val useBulk = bulk && this.bulk != null
        if (cells.isEmpty() || cells.size > (if (useBulk) MAX_SEED_CELLS_BULK else MAX_SEED_CELLS) || src == null) { log("seed refused cells=${cells.size}"); return SeedResult(cells.size, 0, 0, if (src == null) cells.size else 0, 0) }
        val mine = Job(currentCoroutineContext()[Job])
        seeds.merge(blueZoneId, setOf(mine)) { a, b -> a + b }
        var stopped: String? = null
        val result: SeedResult? = try {
            withContext(mine + SeedAdmission(admit)) {
                try { seedBody(blueZoneId, bbox, cells, src, onProgress, onCellMs, onCounts, if (useBulk) this@PlacePack.bulk else null) } catch (e: SeedStopped) {
                    stopped = e.reason
                    writeArea(AreaEntity(blueZoneId, bbox.south, bbox.west, bbox.north, bbox.east, now(), AREA_PARTIAL))
                    null
                }
            }
        } catch (e: CancellationException) {
            // Only this seed's own job was cancelled (the area was removed): say so. A cancelled caller stays a plain cancellation.
            if (mine.isCancelled && currentCoroutineContext().isActive) throw SeedStopped("area removed")
            throw e
        } finally {
            seeds.compute(blueZoneId) { _, cur -> (cur.orEmpty() - mine).ifEmpty { null } }; mine.cancel()
        }
        stopped?.let { log("seed stopped"); throw SeedStopped(it) } // one of a few fixed phrases: nothing about a position
        return result!!
    }

    /** Background seeding (S3): records the area as [AREA_SEEDING] BEFORE the work is queued, so the worker can read its box from here (no coordinates travel in the work data). Keeps the owner's name. */
    suspend fun beginArea(blueZoneId: String, bbox: GeoBox) = writeArea(AreaEntity(blueZoneId, bbox.south, bbox.west, bbox.north, bbox.east, now(), AREA_SEEDING))

    /**
     * Reconcile (S3): an area still marked [AREA_SEEDING] whose work is gone (cancelled, process killed with the work cleared, Delete all) becomes [AREA_PARTIAL], so it never stays "(seeding)" forever.
     * Only rows older than [graceMs] are touched (a row written a moment ago belongs to work that is just being queued). A seed running in this process is left alone. Returns how many rows changed.
     */
    suspend fun stallSeeding(blueZoneId: String, graceMs: Long = 30_000L): Boolean = fetchLock.withLock {
        val a = dao.areas().firstOrNull { it.blueZoneId == blueZoneId } ?: return@withLock false
        if (a.status != AREA_SEEDING || seeds.containsKey(blueZoneId) || now() - a.seededAt < graceMs) return@withLock false
        dao.putArea(a.copy(status = AREA_PARTIAL)); true
    }

    /** The area row, written under the fetch lock and only while this seed is still wanted: a removal cancels the seed first, so its last write cannot bring the area back. */
    private suspend fun writeArea(row: AreaEntity) = fetchLock.withLock { currentCoroutineContext().ensureActive(); dao.putArea(if (row.name == null) row.copy(name = dao.areaName(row.blueZoneId)) else row) } // a seed rewrite keeps the owner's name (v18 WP11b)

    private suspend fun seedBody(blueZoneId: String, bbox: GeoBox, cells: List<String>, src: CellSource, onProgress: (Int, Int) -> Unit, onCellMs: (Long) -> Unit, onCounts: (SeedCounts) -> Unit, bulkSrc: BulkSource? = null): SeedResult {
        writeArea(AreaEntity(blueZoneId, bbox.south, bbox.west, bbox.north, bbox.east, now(), AREA_SEEDING))
        var fetched = 0; var skipped = 0; var partial = 0; var done = 0; var partialFresh = 0
        fun withoutOsm() = partial + partialFresh
        val failedCells = ArrayList<String>()
        net.reset()
        suspend fun seedOne(cell: String): CellOutcome {
            seedAdmitCheck() // before the cell's lock; each request asks again in the HTTP layer
            return fetchLock.withLock {
                // A PARTIAL cell (Wikipedia and Wikidata answered, Overpass did not) is DONE for seeding: not fetched again while younger than 30 days.
                val c = dao.cell(cell)
                if (c?.status == CELL_OK && isFresh(c)) CellOutcome.FRESH
                else if (c?.status == CELL_PARTIAL && (now() - c.fetchedAt) in 0 until FRESH_MS) { partialFresh++; CellOutcome.FRESH }
                else { seedAdmitCheck(); fetchAndStore(cell, src, seeded = true) }
            }
        }
        // how long a fetched cell took (fetch only, no retry wait): a duration alone, never which cell; the caller keeps sums and counts
        var attemptNo = 1
        suspend fun timed(cell: String): CellOutcome { val t0 = now(); val o = withContext(SeedAttempt(attemptNo)) { seedOne(cell) }; if (o == CellOutcome.FETCHED || o == CellOutcome.PARTIAL) onCellMs((now() - t0).coerceAtLeast(0)); return o }
        fun count(outcome: CellOutcome): Boolean = when (outcome) {
            CellOutcome.FETCHED -> { fetched++; true }
            CellOutcome.PARTIAL -> { fetched++; partial++; true }
            else -> false
        }
        // BULK discovery (owner decision 27 Nov): fresh cells are skipped as in the per-cell path, the rest go in tiles through [bulkSrc]; cells whose bulk answer failed are walked by the per-cell loop below.
        var loopCells = cells
        if (bulkSrc != null) {
            val todo = ArrayList<String>()
            for (cell in cells) {
                seedAdmitCheck()
                val c = dao.cell(cell)
                val fresh = (c?.status == CELL_OK && isFresh(c)) || (c?.status == CELL_PARTIAL && (now() - c.fetchedAt) in 0 until FRESH_MS)
                if (!fresh) { todo += cell; continue }
                if (c?.status == CELL_PARTIAL) partialFresh++
                skipped++; Geohash5.bounds(cell)!!.let { dao.markSeeded(it.south, it.north, it.west, it.east) }
                done++
            }
            if (done > 0) { onProgress(done, cells.size); onCounts(SeedCounts(done, cells.size, 0, withoutOsm(), fetched)) }
            val fallback = ArrayList<String>()
            val tileRuns = ArrayList<List<String>>()
            tilesOf(todo).forEach { tileRuns += it }
            var boxes = 0; var split = 0; var bulkFailed = 0; var places = 0
            val queue = ArrayDeque(tileRuns)
            while (queue.isNotEmpty()) {
                val tile = queue.removeFirst()
                seedAdmitCheck()
                val epoch = wipeEpoch.get()
                val box = unionBox(tile)
                val t0 = now()
                val got: List<FetchedPlace>? = try { bulkSrc.fetchBox(box) } catch (e: CancellationException) { throw e } catch (e: PlaceOfflineOnly) { null
                } catch (e: BulkTooBig) {
                    if (tile.size > 1) { split++; val (a, b) = splitCells(tile); queue.addFirst(b); queue.addFirst(a); log("bulk box split cells=${tile.size} cls=BulkTooBig"); continue }
                    log("bulk box failed cells=1 cls=BulkTooBig"); null
                } catch (e: Exception) { log("bulk box failed cells=${tile.size} cls=${e.javaClass.simpleName}"); null }
                if (got == null || wipeEpoch.get() != epoch) { bulkFailed++; fallback += tile; continue }
                val stored = storeBulkBox(tile, got, epoch)
                if (stored < 0) { bulkFailed++; fallback += tile; continue }
                boxes++; places += stored; fetched += tile.size; partial += tile.size; done += tile.size
                log("bulk box stored cells=${tile.size} places=$stored ms=${now() - t0}")
                onProgress(done, cells.size)
                onCounts(SeedCounts(done, cells.size, failedCells.size, withoutOsm(), fetched))
                net.tick(done, failedCells.size, cells.size, withoutOsm(), gate = 0)
            }
            log("seed bulk boxes=$boxes split=$split failedBoxes=$bulkFailed places=$places fallbackCells=${fallback.size} skipped=$skipped")
            loopCells = fallback
        }
        for (cell in loopCells) {
            val box = Geohash5.bounds(cell)!!
            val outcome = timed(cell)
            if (outcome == CellOutcome.FRESH) { skipped++; dao.markSeeded(box.south, box.north, box.west, box.east) }
            else if (!count(outcome)) failedCells += cell
            onProgress(++done, cells.size)
            onCounts(SeedCounts(done, cells.size, failedCells.size, withoutOsm(), fetched))
            net.tick(done, failedCells.size, cells.size, withoutOsm())
        }
        // Failed cells (network blip, rate limit 429, server 5xx) are tried again after the others, with a growing wait, up to MAX_ATTEMPTS tries per cell in all; then they are given up on (the area
        // ends PARTIAL and the owner can "Retry failed cells"). A lost network is not a cell failure: the admission stops the seed and the worker waits for a network.
        val retried = failedCells.size
        var remaining: List<String> = failedCells
        var round = 0
        while (remaining.isNotEmpty() && round < MAX_ATTEMPTS - 1) {
            round++
            attemptNo = round + 1
            pause(retryBackoffMs(round))
            val still = ArrayList<String>()
            var n = 0
            for (cell in remaining) {
                if (!count(timed(cell))) still += cell
                n++
                val failedNow = still.size + (remaining.size - n) // cells not yet retried this round still count as failed; a recovered one moves failed -> ok
                onCounts(SeedCounts(cells.size, cells.size, failedNow, withoutOsm(), fetched))
                net.tick(cells.size, failedNow, cells.size, withoutOsm(), gate = n)
            }
            log("seed retry round=$round cells=${remaining.size} recovered=${remaining.size - still.size}")
            remaining = still
        }
        val failed = remaining.size
        net.finish(cells.size, failed, round, withoutOsm())
        writeArea(AreaEntity(blueZoneId, bbox.south, bbox.west, bbox.north, bbox.east, now(), if (failed == 0) AREA_DONE else AREA_PARTIAL)) // cells without OSM extras do not keep the area from ending Done
        val n = dao.placeCount()
        log("seed done cells=${cells.size} ok=${fetched - partial} partial=$partial partialFresh=$partialFresh skipped=$skipped failed=$failed fetched=$fetched places=$n")
        return SeedResult(cells.size, fetched, skipped, failed, n, partial, retried, partialFresh)
    }

    /** The cells grouped in tiles of [BULK_TILE_CELLS] x [BULK_TILE_CELLS] cells (about 200 km): the first box size asked of the bulk source; a tile that is too big is split. Order: south-west to north-east. */
    private fun tilesOf(cells: List<String>): List<List<String>> {
        val groups = LinkedHashMap<Pair<Int, Int>, MutableList<String>>()
        for (c in cells) {
            val b = Geohash5.bounds(c) ?: continue
            val key = Math.floor((b.south + 90.0) / (Geohash5.LAT_STEP * BULK_TILE_CELLS)).toInt() to Math.floor((b.west + 180.0) / (Geohash5.LON_STEP * BULK_TILE_CELLS)).toInt()
            groups.getOrPut(key) { ArrayList() } += c
        }
        return groups.values.toList()
    }

    /** The smallest box holding every cell of [cells] whole (so a place in any of them is inside it). */
    private fun unionBox(cells: List<String>): GeoBox {
        var s = 90.0; var w = 180.0; var n = -90.0; var e = -180.0
        for (c in cells) Geohash5.bounds(c)?.let { s = minOf(s, it.south); w = minOf(w, it.west); n = maxOf(n, it.north); e = maxOf(e, it.east) }
        return GeoBox(s, w, n, e)
    }

    /** Halves [cells] along the longer side of their box. */
    private fun splitCells(cells: List<String>): Pair<List<String>, List<String>> {
        val b = unionBox(cells)
        val byLat = (b.north - b.south) >= (b.east - b.west) * (Geohash5.LAT_STEP / Geohash5.LON_STEP)
        val sorted = cells.sortedBy { c -> Geohash5.bounds(c)!!.let { if (byLat) it.south else it.west } }
        val key = { c: String -> Geohash5.bounds(c)!!.let { if (byLat) it.south else it.west } }
        val mid = key(sorted[sorted.size / 2])
        val lo = sorted.filter { key(it) < mid }; val hi = sorted.filter { key(it) >= mid }
        return if (lo.isEmpty() || hi.isEmpty()) sorted.take(sorted.size / 2) to sorted.drop(sorted.size / 2) else lo to hi
    }

    /**
     * Stores one bulk box through the same rows as [fetchAndStore]: the places that lie in one of [cells] (seeded), and a cell row per cell with status [CELL_PARTIAL]: Wikipedia and Wikidata answered, OSM extras did not
     * run, so the cell is DONE for seeding (30 days), counts under "without OSM extras", and a glance on free Wi-Fi may fill it in later. Returns the places stored, or -1 when Delete all ran meanwhile.
     */
    private suspend fun storeBulkBox(cells: List<String>, places: List<FetchedPlace>, epoch: Long): Int = fetchLock.withLock {
        currentCoroutineContext().ensureActive()
        if (wipeEpoch.get() != epoch) return@withLock -1
        val t = now()
        val inCells = cells.toHashSet()
        val clean = places.filter { !UserPlace.isUserId(it.id) && it.kind in PlaceKinds.ALL && it.lat.isFinite() && it.lon.isFinite() && it.name.isNotBlank() && Geohash5.encode(it.lat, it.lon) in inCells }.distinctBy { it.id }
        db.withTransaction {
            if (wipeEpoch.get() != epoch) return@withTransaction
            val old = chunked(clean.map { it.id }) { dao.byIds(it) }.associateBy { it.id }
            dao.putPlaces(clean.map { f -> placeRow(f, old[f.id], t, seeded = true) })
            for (c in cells) dao.putCell(CellEntity(c, t, CELL_PARTIAL))
            evictToBudget(keepIds = clean.map { it.id }, keepCell = "")
        }
        if (wipeEpoch.get() != epoch) -1 else clean.size
    }

    private fun placeRow(f: FetchedPlace, o: PlaceEntity?, t: Long, seeded: Boolean): PlaceEntity {
        val row = PlaceEntity(
            f.id, f.parentId, f.name.take(NAME_MAX), f.kind, f.lat, f.lon, f.radiusM.coerceIn(10, PlaceKinds.MAX_RADIUS_M), clipSummary(f.summary), f.source, f.sourceRev, t,
            seeded = seeded || o?.seeded == true, pinned = o?.pinned == true, lastUsedDay = max(o?.lastUsedDay ?: 0, day()), bytes = 0,
        )
        return row.copy(bytes = sizeOf(row))
    }

    /**
     * Forgets a seeded area (the user removed it). A seed of that very area is cancelled and awaited first, so its last write cannot bring the area back. Then, in one transaction, the area row
     * goes and every place that no remaining seeded area covers stops being seeded: those are ordinary fetched places from now on (evictable, removed by Clear fetched). Pins are a separate
     * flag and stay.
     */
    suspend fun removeArea(blueZoneId: String, withPlaces: Boolean = false) {
        while (true) { val running = seeds[blueZoneId]?.toList().orEmpty(); if (running.isEmpty()) break; running.forEach { it.cancel() }; running.forEach { it.join() } }
        fetchLock.withLock {
            var gone = 0
            val left = db.withTransaction {
                if (withPlaces) {
                    // "area and its places": the seeded places of this area that no OTHER area covers and no pin owns, their reference captions and the cells only this area covered (those are fetched again on demand)
                    val mine = dao.areas().firstOrNull { it.blueZoneId == blueZoneId }
                    if (mine != null) {
                        val others = dao.areas().filter { it.blueZoneId != blueZoneId }.map { it.box }
                        // Seeding owns WHOLE cells (markSeeded over each covering cell), so ownership is tested on cell bounds, not on the drawn rectangle: a place in a boundary cell outside the rectangle goes with the area, and a place in a cell another area's seeding covers stays.
                        val mineCells = Geohash5.cellsCovering(mine.box).mapNotNull { Geohash5.bounds(it) }
                        val otherCells = others.flatMap { o -> Geohash5.cellsCovering(o).mapNotNull { Geohash5.bounds(it) } }
                        val ids = if (mineCells.isEmpty()) emptyList() else dao.inBox(mineCells.minOf { it.south }, mineCells.maxOf { it.north }, mineCells.minOf { it.west }, mineCells.maxOf { it.east })
                            .filter { it.seeded && !it.pinned && !UserPlace.isUserId(it.id) && mineCells.any { b -> b.contains(it.lat, it.lon) } && otherCells.none { b -> b.contains(it.lat, it.lon) } }.map { it.id }
                        eachChunk(ids) { dao.deletePlaces(it) }
                        gone = ids.size
                        val cells = dao.cells().map { it.geohash5 }.filter { c -> val b = Geohash5.bounds(c); b != null && mine.box.intersects(b) && others.none { o -> o.intersects(b) } }
                        eachChunk(cells) { dao.deleteCells(it) }
                        cells.forEach { partialStreak.remove(it) }
                    }
                }
                dao.deleteArea(blueZoneId)
                dao.clearSeeded()
                val rest = dao.areas()
                for (a in rest) for (c in Geohash5.cellsCovering(a.box)) Geohash5.bounds(c)?.let { dao.markSeeded(it.south, it.north, it.west, it.east) }
                dao.deleteOrphanRefs(); dao.deleteOrphanImages()
                rest.size
            }
            log("area removed remaining=$left places=$gone")
        }
        removed()
    }

    /** C1: stores reference captions for places that exist (a caption for an unknown place is dropped). The caller has the user's explicit confirmation from seed time; nothing here runs on its own. Returns how many were stored. */
    suspend fun putRefs(rows: List<PlaceRefEntity>): Int = fetchLock.withLock {
        db.withTransaction {
            val known = chunked(rows.map { it.placeId }.distinct()) { dao.byIds(it) }.map { it.id }.toSet()
            val keep = rows.filter { it.placeId in known }
            dao.putRefs(keep)
            keep.size
        }
    }

    suspend fun refsOf(ids: List<String>): List<PlaceRefEntity> = chunked(ids) { dao.refsOf(it) }
    /** The ids that have a reference caption: the start-up sweep of `model_attribution` kind PLACE_REF keeps exactly these. */
    suspend fun refPlaceIds(): List<String> = dao.refPlaceIds()

    // ---- B1: reference photos of seeded places (place_image). Nothing here fetches or embeds (B2). ----

    /** Stores photo rows for places that exist (never a user pin); a row for an unknown place is dropped. Then trims to [IMAGE_CAP_BYTES]. Returns how many were stored. */
    suspend fun putImages(rows: List<PlaceImageEntity>): Int = fetchLock.withLock {
        db.withTransaction {
            val known = chunked(rows.map { it.placeId }.distinct()) { dao.byIds(it) }.map { it.id }.filter { !UserPlace.isUserId(it) }.toSet()
            val keep = rows.filter { it.placeId in known }
            dao.putImages(keep)
            trimImagesLocked(imageCapBytes)
            keep.size
        }
    }

    suspend fun imagesOf(ids: List<String>): List<PlaceImageEntity> = chunked(ids) { dao.imagesOf(it) }
    suspend fun imageStats(): ImageStats = ImageStats(dao.imageCount(), dao.imageOkCount(), dao.imageBytes(), imageCapBytes)

    /** The matcher's read: ONE decoded vector per place that has one in [space]; a vector whose length is not its stored dimension (or unreadable) is dropped. Read only, no lock. */
    suspend fun imageVectorsFor(ids: List<String>, space: String): Map<String, FloatArray> {
        val out = HashMap<String, FloatArray>()
        chunked(ids.distinct()) { dao.vectorsFor(it, space) }.forEach { r ->
            val v = runCatching { com.rokid.context.memory.retrieval.VectorCodec.decode(r.vector) }.getOrNull() ?: return@forEach
            if (v.isNotEmpty() && (r.dim == null || r.dim == v.size)) out[r.placeId] = v
        }
        return out
    }

    /** Records the vector of a photo (the active space); false when the row is gone or has no photo state OK. */
    suspend fun markEmbedded(id: String, space: String, vector: FloatArray, version: Int, at: Long): Boolean = fetchLock.withLock {
        val blob = com.rokid.context.memory.retrieval.VectorCodec.encode(vector)
        db.withTransaction { (dao.markEmbedded(id, space, vector.size, blob, version, at, blob.size) > 0).also { if (it) trimImagesLocked(imageCapBytes) } } // a vector adds bytes: the cap holds here too, not only on a put
    }

    suspend fun deleteImages(ids: List<String>): Int = fetchLock.withLock { db.withTransaction { var n = 0; eachChunk(ids) { n += dao.deleteImages(it) }; n } }
    suspend fun needsFetch(space: String, maxTries: Int, limit: Int): List<String> = dao.needsFetch(space, maxTries, limit)
    suspend fun needsEmbed(space: String, limit: Int): List<String> = dao.needsEmbed(space, limit)

    /** Brings the photos under [capBytes]: the oldest thumbnails go first, and only those whose vector stays (so every place with a vector keeps its reference; a photo that has no vector yet is never trimmed). Returns how many thumbnails were dropped. */
    suspend fun trimImages(capBytes: Long = imageCapBytes): Int = fetchLock.withLock { db.withTransaction { trimImagesLocked(capBytes) } }

    private suspend fun trimImagesLocked(capBytes: Long): Int {
        var total = dao.imageBytes()
        var dropped = 0
        while (total > capBytes) {
            val batch = dao.trimmable(EVICT_BATCH)
            if (batch.isEmpty()) break
            for (r in batch) { if (total <= capBytes) break; dao.dropThumbnail(r.placeId, r.vecBytes); total -= (r.bytes - r.vecBytes).coerceAtLeast(0); dropped++ }
        }
        return dropped
    }

    suspend fun pin(id: String, pinned: Boolean): Boolean = dao.setPinned(id, pinned) > 0

    /**
     * The Delete all hook: removes every fetched place that is not seeded or pinned, forgets the cells outside the seeded areas (so they are fetched again), and clears all
     * recency. Seeded areas, what they seeded and pinned places stay. Pause zones are not stored here and are never touched.
     */
    suspend fun deleteAll() {
        wipeEpoch.incrementAndGet() // outstanding fetches are invalidated at once (they cannot store), then the lock waits for them to end
        fetchLock.withLock {
            db.withTransaction { clearFetchedLocked(resetRecency = true) }
        }
        removed()
    }

    private suspend fun clearFetchedLocked(resetRecency: Boolean): Int {
        partialStreak.clear()
        val removed = dao.deleteFetched()
        dao.deleteOrphanRefs(); dao.deleteOrphanImages()
        if (resetRecency) dao.resetRecency()
        val areas = dao.areas().map { it.box }
        val stale = dao.cells().map { it.geohash5 }.filter { c -> val b = Geohash5.bounds(c); b == null || areas.none { it.intersects(b) } }
        eachChunk(stale) { dao.deleteCells(it) }
        log("pack cleared removed=$removed cells=${stale.size} recencyReset=$resetRecency")
        return removed
    }

    /** A stored cell that is kept instead of fetched again on a costly network: stored partial (the retry is every few minutes), or inside a seeded area. */
    private suspend fun keepWhileCostly(c: CellEntity): Boolean {
        if (c.status != CELL_OK) return true
        val b = Geohash5.bounds(c.geohash5) ?: return false
        return dao.areas().any { it.box.intersects(b) }
    }

    private fun isFresh(c: CellEntity?): Boolean {
        if (c == null) return false
        val age = now() - c.fetchedAt
        return age in 0 until (if (c.status == CELL_OK) FRESH_MS else partialFreshMs(partialStreak[c.geohash5] ?: 1))
    }

    private suspend fun fetchAndStore(cell: String, src: CellSource, seeded: Boolean): CellOutcome {
        val epoch = wipeEpoch.get()
        val fetchStart = now()
        val got = try { zoneCheck(); src.fetchCell(cell) } catch (e: CancellationException) { throw e } catch (e: PlaceOfflineOnly) {
            log("cell fetch refused offline_only seeded=$seeded") // not a failure: nothing was sent, nothing is backed off
            return CellOutcome.OFFLINE
        } catch (e: Exception) {
            log("cell fetch failed cls=${e.javaClass.simpleName} seeded=$seeded attempt=${currentCoroutineContext()[SeedAttempt]?.n ?: 1} ms=${now() - fetchStart}")
            backOffStoredPartial(cell, epoch)
            return CellOutcome.FAILED
        }
        if (wipeEpoch.get() != epoch) { log("cell fetch dropped: Delete all ran"); return CellOutcome.FAILED }
        val t = now()
        val clean = got.places.filter { !UserPlace.isUserId(it.id) && it.kind in PlaceKinds.ALL && it.lat.isFinite() && it.lon.isFinite() && it.name.isNotBlank() }.distinctBy { it.id }
        var evicted = 0
        db.withTransaction {
            if (wipeEpoch.get() != epoch) return@withTransaction
            val old = chunked(clean.map { it.id }) { dao.byIds(it) }.associateBy { it.id }
            dao.putPlaces(clean.map { f -> placeRow(f, old[f.id], t, seeded) })
            dao.putCell(CellEntity(cell, t, if (got.complete) CELL_OK else CELL_PARTIAL))
            if (got.complete) partialStreak.remove(cell) else partialStreak.merge(cell, 1) { a, b -> (a + b).coerceAtMost(MAX_STREAK) }
            evicted = evictToBudget(keepIds = clean.map { it.id }, keepCell = cell)
        }
        log("cell stored places=${clean.size} evicted=$evicted complete=${got.complete} seeded=$seeded ms=${now() - fetchStart}")
        return if (got.complete) CellOutcome.FETCHED else CellOutcome.PARTIAL
    }

    /**
     * A STORED partial cell whose refetch failed: it keeps its places, and the failure advances the same backoff as another partial answer (streak + a new retry time), so a free network that keeps failing
     * (a 429, a dead source) is not hit again on every glance after 5 minutes. A cell that is not stored, or stored OK, is left alone (its own staleness already limits the retries).
     */
    private suspend fun backOffStoredPartial(cell: String, epoch: Long) {
        try {
            if (wipeEpoch.get() != epoch) return
            val c = dao.cell(cell) ?: return
            if (c.status != CELL_PARTIAL) return
            partialStreak.merge(cell, 1) { a, b -> (a + b).coerceAtMost(MAX_STREAK) }
            dao.putCell(CellEntity(cell, now(), CELL_PARTIAL))
        } catch (e: CancellationException) { throw e } catch (e: Exception) { log("partial cell backoff not stored cls=${e.javaClass.simpleName}") }
    }

    /** Removes least-recently-used fetched places (never the cell just fetched) until the pack fits [budgetBytes]; forgets the cells they were in so those are fetched again. Returns the count. */
    private suspend fun evictToBudget(keepIds: List<String>, keepCell: String): Int {
        var total = dao.totalBytes()
        var count = 0
        while (total > budgetBytes) {
            val batch = dao.evictable(EVICT_BATCH, keepIds)
            if (batch.isEmpty()) break // everything left is seeded or pinned
            val drop = ArrayList<PlaceEntity>()
            for (p in batch) { if (total <= budgetBytes) break; drop += p; total -= p.bytes }
            eachChunk(drop.map { it.id }) { dao.deletePlaces(it) }
            dao.deleteOrphanRefs(); dao.deleteOrphanImages()
            val cellsDropped = drop.map { Geohash5.encode(it.lat, it.lon) }.filter { it != keepCell }.distinct()
            eachChunk(cellsDropped) { dao.deleteCells(it) }
            cellsDropped.forEach { partialStreak.remove(it) } // an evicted cell is forgotten, with its backoff
            count += drop.size
        }
        return count
    }

    private fun day(): Int = (now() / DAY_MS).toInt()

    private suspend fun eachChunk(ids: List<String>, call: suspend (List<String>) -> Unit) { ids.chunked(SQL_CHUNK).forEach { call(it) } }

    private suspend fun <T> chunked(ids: List<String>, call: suspend (List<String>) -> List<T>): List<T> =
        ids.chunked(SQL_CHUNK).flatMap { call(it) }

    companion object {
        /** The longest stored seed-area name (v18 WP11b). */
        const val MAX_AREA_NAME = 80
        const val DEFAULT_BUDGET_BYTES = 50L * 1024 * 1024
        /** The budgets the screen offers (MB). */
        val BUDGET_CHOICES_MB = listOf(25, 50, 100, 200)
        const val MIN_BUDGET_BYTES = 1L
        const val DAY_MS = 86_400_000L
        const val FRESH_MS = 30 * DAY_MS
        /**
         * How long a PARTIAL cell (Overpass did not answer) counts as fresh: [PARTIAL_FRESH_MS] after the first partial answer, doubling with each consecutive partial answer for that cell
         * (5, 10, 20, 40 min), capped at [PARTIAL_MAX_MS] (1 h). A complete answer resets it. The streak is kept in memory (a restart begins at 5 min again). A partial cell that kept places
         * stays usable the whole time; it is only refetched at the next glance after the backoff. Was a flat 24 h, which left Overpass-less cells without restaurants all day.
         */
        const val PARTIAL_FRESH_MS = 5 * 60_000L
        const val PARTIAL_MAX_MS = 60 * 60_000L
        private const val MAX_STREAK = 10
        fun partialFreshMs(streak: Int): Long = if (streak <= 1) PARTIAL_FRESH_MS else (PARTIAL_FRESH_MS shl (streak - 1).coerceAtMost(MAX_STREAK)).coerceAtMost(PARTIAL_MAX_MS)
        const val SUMMARY_MAX = 400
        const val NAME_MAX = 160
        const val MAX_SEED_CELLS = 4_000
        /** The per-seed cell cap of the BULK path only (owner decision 27 Nov); the per-cell path keeps [MAX_SEED_CELLS]. */
        const val MAX_SEED_CELLS_BULK = 30_000
        /** The first box of the bulk path covers this many cells per side (about 200 km); a box that times out or returns too many rows is halved. */
        const val BULK_TILE_CELLS = 40
        /** The wait before the first retry of the failed cells; it doubles each round (5 s, 10 s, 20 s, 40 s) up to [RETRY_BACKOFF_MAX_MS]. */
        const val RETRY_BACKOFF_MS = 5_000L
        const val RETRY_BACKOFF_MAX_MS = 60_000L
        /** Tries per cell in one seed (the first pass plus the retry rounds), then it is given up on. */
        const val MAX_ATTEMPTS = 5
        fun retryBackoffMs(round: Int): Long = (RETRY_BACKOFF_MS shl (round - 1).coerceIn(0, 10)).coerceAtMost(RETRY_BACKOFF_MAX_MS)
        private const val EVICT_BATCH = 200
        /** Decision D2: the reference photos (thumbnails + vectors) may take 250 MB in total. */
        const val IMAGE_CAP_BYTES = 250L * 1024 * 1024
        private const val SQL_CHUNK = 500

        fun sizeOf(p: PlaceEntity): Int =
            listOf(p.id, p.parentId.orEmpty(), p.name, p.kind, p.summary, p.source, p.sourceRev).sumOf { it.toByteArray(Charsets.UTF_8).size } + 80

        /** At most [SUMMARY_MAX] characters on one line, cut at a word end with an ellipsis. */
        fun clipSummary(s: String): String {
            val one = s.replace(Regex("\\s+"), " ").trim()
            if (one.length <= SUMMARY_MAX) return one
            val cut = one.substring(0, SUMMARY_MAX - 1)
            val at = cut.lastIndexOf(' ')
            return (if (at > SUMMARY_MAX / 2) cut.substring(0, at) else cut).trimEnd(',', ';', ':', ' ') + "…"
        }
    }
}
