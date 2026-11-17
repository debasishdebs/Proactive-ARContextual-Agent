package com.rokid.context.memory.place

import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.max

/** A place as a source returned it: no flags, no clock. The pack stamps those. */
data class FetchedPlace(
    val id: String, val parentId: String?, val name: String, val kind: String,
    val lat: Double, val lon: Double, val radiusM: Int, val summary: String, val source: String, val sourceRev: String,
)

/** One cell's answer. [complete] is false when a source that may be missing (Overpass) did not answer: the cell then counts as fresh for a day only. */
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

enum class CellOutcome { FRESH, FETCHED, PARTIAL, OFFLINE, FAILED }

/** What a glance asks for: the candidates (already expanded to sub-places) and how the cell stood. */
data class PlaceCandidates(val places: List<PlaceEntity>, val outcome: CellOutcome)

data class SeedResult(val cells: Int, val fetched: Int, val skipped: Int, val failed: Int, val places: Int) {
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
) {
    private val dao = db.dao()
    /** The budget in bytes: [setBudget] changes it (the Place Guide screen offers 25/50/100/200 MB). */
    @Volatile var budgetBytes: Long = budgetBytes.coerceAtLeast(MIN_BUDGET_BYTES); private set
    private val fetchLock = Mutex()
    /** Advanced by [deleteAll] before it takes [fetchLock]: a fetch that began earlier discards its answer instead of re-inserting what Delete all removed. */
    private val wipeEpoch = java.util.concurrent.atomic.AtomicLong(0)

    /** The pack's size (count, bytes), for the Trips screen. */
    suspend fun size(): Pair<Int, Long> = dao.placeCount() to dao.totalBytes()

    suspend fun areas(): List<AreaEntity> = dao.areas()

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

    /** Makes the cell fresh when it is not, and says how it stands. One fetch at a time. */
    suspend fun ensureCell(cell: String, networkUp: Boolean): CellOutcome {
        if (!Geohash5.isCell(cell)) return CellOutcome.FAILED
        if (isFresh(dao.cell(cell))) return CellOutcome.FRESH
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
    suspend fun candidates(lat: Double, lon: Double, networkUp: Boolean): PlaceCandidates {
        val outcome = ensureCell(Geohash5.encode(lat, lon), networkUp)
        val w = Geo.window(lat, lon, Geo.CANDIDATE_GAP_M + PlaceKinds.MAX_RADIUS_M)
        val hits = Geo.candidates(dao.inBox(w.south, w.north, w.west, w.east), lat, lon).map { it.place }
        val all = if (hits.isEmpty()) hits else Geo.expandSubPlaces(hits, chunked(hits.map { it.id }) { dao.childrenOf(it) })
        if (all.isNotEmpty()) { val day = day(); eachChunk(all.map { it.id }) { dao.touch(it, day) } }
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
    suspend fun seedArea(blueZoneId: String, bbox: GeoBox, onProgress: (Int, Int) -> Unit = { _, _ -> }): SeedResult {
        val cells = Geohash5.cellsCovering(bbox)
        val src = source
        if (cells.isEmpty() || cells.size > MAX_SEED_CELLS || src == null) { log("seed refused cells=${cells.size}"); return SeedResult(cells.size, 0, 0, if (src == null) cells.size else 0, 0) }
        dao.putArea(AreaEntity(blueZoneId, bbox.south, bbox.west, bbox.north, bbox.east, now(), AREA_SEEDING))
        var fetched = 0; var skipped = 0; var failed = 0; var done = 0
        for (cell in cells) {
            val box = Geohash5.bounds(cell)!!
            val outcome = fetchLock.withLock {
                if (isFresh(dao.cell(cell))) CellOutcome.FRESH else fetchAndStore(cell, src, seeded = true)
            }
            when (outcome) {
                CellOutcome.FRESH -> { skipped++; dao.markSeeded(box.south, box.north, box.west, box.east) }
                CellOutcome.FETCHED, CellOutcome.PARTIAL -> fetched++
                else -> failed++
            }
            onProgress(++done, cells.size)
        }
        dao.putArea(AreaEntity(blueZoneId, bbox.south, bbox.west, bbox.north, bbox.east, now(), if (failed == 0) AREA_DONE else AREA_PARTIAL))
        val n = dao.placeCount()
        log("seed done cells=${cells.size} fetched=$fetched skipped=$skipped failed=$failed places=$n")
        return SeedResult(cells.size, fetched, skipped, failed, n)
    }

    /** Forgets a seeded area (the user deleted the blue zone). Its places stay until evicted: they are ordinary fetched places from now on. */
    suspend fun removeArea(blueZoneId: String) { dao.deleteArea(blueZoneId) }

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
        val removed = dao.deleteFetched()
        if (resetRecency) dao.resetRecency()
        val areas = dao.areas().map { it.box }
        val stale = dao.cells().map { it.geohash5 }.filter { c -> val b = Geohash5.bounds(c); b == null || areas.none { it.intersects(b) } }
        eachChunk(stale) { dao.deleteCells(it) }
        log("pack cleared removed=$removed cells=${stale.size} recencyReset=$resetRecency")
        return removed
    }

    private fun isFresh(c: CellEntity?): Boolean {
        if (c == null) return false
        val age = now() - c.fetchedAt
        return age in 0 until (if (c.status == CELL_OK) FRESH_MS else PARTIAL_FRESH_MS)
    }

    private suspend fun fetchAndStore(cell: String, src: CellSource, seeded: Boolean): CellOutcome {
        val epoch = wipeEpoch.get()
        val got = try { zoneCheck(); src.fetchCell(cell) } catch (e: CancellationException) { throw e } catch (e: Exception) {
            log("cell fetch failed cls=${e.javaClass.simpleName}")
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
            evicted = evictToBudget(keepIds = clean.map { it.id }, keepCell = cell)
        }
        log("cell stored places=${clean.size} evicted=$evicted complete=${got.complete}")
        return if (got.complete) CellOutcome.FETCHED else CellOutcome.PARTIAL
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
            eachChunk(drop.map { Geohash5.encode(it.lat, it.lon) }.filter { it != keepCell }.distinct()) { dao.deleteCells(it) }
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
        const val PARTIAL_FRESH_MS = DAY_MS
        const val SUMMARY_MAX = 400
        const val NAME_MAX = 160
        const val MAX_SEED_CELLS = 4_000
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
