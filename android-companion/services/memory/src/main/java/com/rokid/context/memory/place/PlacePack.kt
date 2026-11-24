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

/** The pack as the budget screen shows it. [pinned] counts user pins; [seeded] and [fetched] exclude pinned rows. */
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

enum class CellOutcome { FRESH, FETCHED, PARTIAL, OFFLINE, FAILED }

/** What a glance asks for: the candidates (already expanded to sub-places) and how the cell stood. */
data class PlaceCandidates(val places: List<PlaceEntity>, val outcome: CellOutcome)

/**
 * What a seed did. [fetched] = [ok] + [partial] (cells stored); [partial] cells were stored without OSM extras (Overpass did not answer) and are fetched again by the next seed or
 * at a glance after their backoff (5 min up to 1 h); [failed] cells stored nothing even after the one retry; [skipped] were already fresh. [retried] counts the cells the retry pass took (whatever its outcome).
 */
data class SeedResult(val cells: Int, val fetched: Int, val skipped: Int, val failed: Int, val places: Int, val partial: Int = 0, val retried: Int = 0) {
    val ok: Int get() = fetched - partial
    /** Cells that need another try: [failed] ones (nothing stored) plus [partial] ones (stored without OSM extras). A partial cell is NOT a seed success. */
    val retryCells: Int get() = failed + partial
    val complete: Boolean get() = failed == 0 && partial == 0
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
) {
    private val dao = db.dao()
    /** The budget in bytes: [setBudget] changes it (the Place Guide screen offers 25/50/100/200 MB). */
    @Volatile var budgetBytes: Long = budgetBytes.coerceAtLeast(MIN_BUDGET_BYTES); private set
    private val fetchLock = Mutex()
    /** The running seed per area id (its own child job): [removeArea] cancels and awaits it so a seed's last write cannot bring a removed area back. */
    private val seeds = java.util.concurrent.ConcurrentHashMap<String, Job>()
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
        }
        log("user places synced count=${good.size}")
    }

    /**
     * "Clear fetched places": removes the fetched places (not seeded, not pinned: user pins stay) and forgets the cells outside the seeded areas so those are fetched again. Recency is kept (unlike
     * [deleteAll]). Returns how many places were removed.
     */
    suspend fun clearFetched(): Int = fetchLock.withLock { wipeEpoch.incrementAndGet(); db.withTransaction { clearFetchedLocked(resetRecency = false) } }

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
        return PlaceCandidates(all, outcome)
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
    suspend fun seedArea(blueZoneId: String, bbox: GeoBox, admit: () -> String? = { null }, onProgress: (Int, Int) -> Unit = { _, _ -> }): SeedResult {
        val cells = Geohash5.cellsCovering(bbox)
        val src = source
        if (cells.isEmpty() || cells.size > MAX_SEED_CELLS || src == null) { log("seed refused cells=${cells.size}"); return SeedResult(cells.size, 0, 0, if (src == null) cells.size else 0, 0) }
        val mine = Job(currentCoroutineContext()[Job])
        seeds[blueZoneId] = mine
        var stopped: String? = null
        val result: SeedResult? = try {
            withContext(mine + SeedAdmission(admit)) {
                try { seedBody(blueZoneId, bbox, cells, src, onProgress) } catch (e: SeedStopped) {
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
            seeds.remove(blueZoneId, mine); mine.cancel()
        }
        stopped?.let { log("seed stopped"); throw SeedStopped(it) } // one of a few fixed phrases: nothing about a position
        return result!!
    }

    /** The area row, written under the fetch lock and only while this seed is still wanted: a removal cancels the seed first, so its last write cannot bring the area back. */
    private suspend fun writeArea(row: AreaEntity) = fetchLock.withLock { currentCoroutineContext().ensureActive(); dao.putArea(row) }

    private suspend fun seedBody(blueZoneId: String, bbox: GeoBox, cells: List<String>, src: CellSource, onProgress: (Int, Int) -> Unit): SeedResult {
        writeArea(AreaEntity(blueZoneId, bbox.south, bbox.west, bbox.north, bbox.east, now(), AREA_SEEDING))
        var fetched = 0; var skipped = 0; var partial = 0; var done = 0
        val failedCells = ArrayList<String>()
        suspend fun seedOne(cell: String): CellOutcome {
            seedAdmitCheck() // before the cell's lock; each request asks again in the HTTP layer
            return fetchLock.withLock {
                // A PARTIAL cell (stored without OSM extras) is not "fresh" for a seed: seeding again tries it again.
                val c = dao.cell(cell)
                if (c?.status == CELL_OK && isFresh(c)) CellOutcome.FRESH else { seedAdmitCheck(); fetchAndStore(cell, src, seeded = true) }
            }
        }
        fun count(outcome: CellOutcome): Boolean = when (outcome) {
            CellOutcome.FETCHED -> { fetched++; true }
            CellOutcome.PARTIAL -> { fetched++; partial++; true }
            else -> false
        }
        for (cell in cells) {
            val box = Geohash5.bounds(cell)!!
            val outcome = seedOne(cell)
            if (outcome == CellOutcome.FRESH) { skipped++; dao.markSeeded(box.south, box.north, box.west, box.east) }
            else if (!count(outcome)) failedCells += cell
            onProgress(++done, cells.size)
        }
        // A failed cell gets one more try after the others (a rate limit or a blip has usually passed by then).
        val retried = failedCells.size
        var failed = 0
        if (failedCells.isNotEmpty()) {
            pause(RETRY_BACKOFF_MS)
            for (cell in failedCells) if (!count(seedOne(cell))) failed++
            log("seed retry cells=$retried recovered=${retried - failed}")
        }
        writeArea(AreaEntity(blueZoneId, bbox.south, bbox.west, bbox.north, bbox.east, now(), if (failed == 0 && partial == 0) AREA_DONE else AREA_PARTIAL))
        val n = dao.placeCount()
        log("seed done cells=${cells.size} ok=${fetched - partial} partial=$partial skipped=$skipped failed=$failed fetched=$fetched places=$n")
        return SeedResult(cells.size, fetched, skipped, failed, n, partial, retried)
    }

    /**
     * Forgets a seeded area (the user removed it). A seed of that very area is cancelled and awaited first, so its last write cannot bring the area back. Then, in one transaction, the area row
     * goes and every place that no remaining seeded area covers stops being seeded: those are ordinary fetched places from now on (evictable, removed by Clear fetched). Pins are a separate
     * flag and stay.
     */
    suspend fun removeArea(blueZoneId: String) {
        seeds[blueZoneId]?.cancelAndJoin()
        fetchLock.withLock {
            val left = db.withTransaction {
                dao.deleteArea(blueZoneId)
                dao.clearSeeded()
                val rest = dao.areas()
                for (a in rest) for (c in Geohash5.cellsCovering(a.box)) Geohash5.bounds(c)?.let { dao.markSeeded(it.south, it.north, it.west, it.east) }
                rest.size
            }
            log("area removed remaining=$left")
        }
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
    }

    private suspend fun clearFetchedLocked(resetRecency: Boolean): Int {
        partialStreak.clear()
        val removed = dao.deleteFetched()
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
        val got = try { zoneCheck(); src.fetchCell(cell) } catch (e: CancellationException) { throw e } catch (e: Exception) {
            log("cell fetch failed cls=${e.javaClass.simpleName} seeded=$seeded ms=${now() - fetchStart}")
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
            dao.putPlaces(clean.map { f ->
                val o = old[f.id]
                val summary = clipSummary(f.summary)
                val row = PlaceEntity(
                    f.id, f.parentId, f.name.take(NAME_MAX), f.kind, f.lat, f.lon, f.radiusM.coerceIn(10, PlaceKinds.MAX_RADIUS_M), summary, f.source, f.sourceRev, t,
                    seeded = seeded || o?.seeded == true, pinned = o?.pinned == true, lastUsedDay = max(o?.lastUsedDay ?: 0, day()), bytes = 0,
                )
                row.copy(bytes = sizeOf(row))
            })
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
        /** The wait before a seed retries its failed cells once. */
        const val RETRY_BACKOFF_MS = 5_000L
        private const val EVICT_BATCH = 200
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
