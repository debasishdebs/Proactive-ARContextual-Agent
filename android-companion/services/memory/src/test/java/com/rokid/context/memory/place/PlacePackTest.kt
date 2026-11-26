package com.rokid.context.memory.place

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.IOException

private const val DAY = PlacePack.DAY_MS
private const val CHARMINAR_LAT = 17.361944
private const val CHARMINAR_LON = 78.474722

private fun fp(id: String, lat: Double = CHARMINAR_LAT, lon: Double = CHARMINAR_LON, parent: String? = null, summary: String = "A monument.", kind: String = PlaceKinds.MONUMENT, name: String = id) =
    FetchedPlace(id, parent, name, kind, lat, lon, 60, summary, "wikipedia:en", "page:1")

/** Counts calls and serves a scripted answer per cell. */
private class FakeSource(var answer: suspend (String) -> CellFetch = { CellFetch(emptyList()) }) : CellSource {
    val asked = mutableListOf<String>()
    var failWith: Exception? = null
    override suspend fun fetchCell(cell: String): CellFetch { asked += cell; failWith?.let { throw it }; return answer(cell) }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PlacePackTest {
    private lateinit var db: PlacesDatabase
    private var t = 100 * DAY
    private val logs = mutableListOf<String>()
    private val pauses = mutableListOf<Long>()
    private val cell = Geohash5.encode(CHARMINAR_LAT, CHARMINAR_LON)

    @Before fun open() { db = PlacesDatabase.inMemory(ApplicationProvider.getApplicationContext()) }
    @After fun close() = db.close()

    private fun pack(src: CellSource?, budget: Long = PlacePack.DEFAULT_BUDGET_BYTES) = PlacePack(db, src, budget, { t }, { logs += it }, { pauses += it })
    private fun <T> run(b: suspend () -> T): T = runBlocking { b() }

    @Test fun areaStatsCountsOnlySeededPlacesInTheAreaBoxAndReadsOffline() = run {
        val src = FakeSource { CellFetch(listOf(fp("wd:Q1"), fp("wd:Q2", lat = CHARMINAR_LAT + 0.0001))) }
        val p = pack(src)
        p.seedArea("hyd", Geohash5.bounds(cell)!!)
        val offline = pack(null) // no source: pure read of places.db
        val st = offline.areaStats().getValue("hyd")
        assertEquals(2, st.places)
        assertTrue(st.bytes > 0)
        assertEquals(emptyMap<String, AreaStats>(), pack(null).also { it.removeArea("hyd", true) }.areaStats())
    }

    @Test fun cellStatusesReadsStoredCellsOnlyAndFetchesNothing() = run {
        val src = FakeSource { CellFetch(listOf(fp("wd:Q1"))) }
        val p = pack(src)
        p.candidates(CHARMINAR_LAT, CHARMINAR_LON, true)
        val before = src.asked.size
        val box = Geohash5.bounds(cell)!!
        assertEquals(mapOf(cell to CELL_OK), p.cellStatuses(box))
        assertEquals(emptyMap<String, String>(), p.cellStatuses(GeoBox(-10.0, -10.0, -9.9, -9.9)))
        assertEquals(emptyMap<String, String>(), p.cellStatuses(GeoBox(5.0, 5.0, 1.0, 1.0))) // invalid box
        assertEquals(before, src.asked.size)
    }

    @Test fun cacheAsideFetchesOnceThenServesFreshThenRefetchesAfter30Days() = run {
        val src = FakeSource { CellFetch(listOf(fp("wd:Q1"))) }
        val p = pack(src)
        assertEquals(CellOutcome.FETCHED, p.ensureCell(cell, true))
        assertEquals(CellOutcome.FRESH, p.ensureCell(cell, true))
        t += 29 * DAY
        assertEquals(CellOutcome.FRESH, p.ensureCell(cell, true))
        t += 2 * DAY
        assertEquals(CellOutcome.FETCHED, p.ensureCell(cell, true))
        assertEquals(listOf(cell, cell), src.asked)
    }

    @Test fun offlineNeverFetchesAndStillServesWhatIsStored() = run {
        val src = FakeSource { CellFetch(listOf(fp("wd:Q1"))) }
        val p = pack(src)
        assertEquals(CellOutcome.OFFLINE, p.ensureCell(cell, false))
        assertTrue(src.asked.isEmpty())
        p.ensureCell(cell, true); t += 60 * DAY
        val got = p.candidates(CHARMINAR_LAT, CHARMINAR_LON, networkUp = false)
        assertEquals(CellOutcome.OFFLINE, got.outcome); assertEquals(listOf("wd:Q1"), got.places.map { it.id })
        assertEquals(1, src.asked.size)
    }

    @Test fun aFailedFetchStoresNothingAndIsRetriedAtTheNextGlance() = run {
        val src = FakeSource { CellFetch(listOf(fp("wd:Q1"))) }.apply { failWith = IOException("down") }
        val p = pack(src)
        assertEquals(CellOutcome.FAILED, p.ensureCell(cell, true))
        assertEquals(0, p.size().first)
        src.failWith = null
        assertEquals(CellOutcome.FETCHED, p.ensureCell(cell, true))
    }

    @Test fun anIncompleteCellIsRefetchedAfterMinutesNotThirtyDaysAndPlacesStayWithTheirFlags() = run {
        var complete = false
        val src = FakeSource { CellFetch(listOf(fp("wd:Q1")), complete) }
        val p = pack(src)
        assertEquals(CellOutcome.PARTIAL, p.ensureCell(cell, true))
        assertEquals(CellOutcome.FRESH, p.ensureCell(cell, true))
        t += DAY + 1; complete = true
        assertEquals(CellOutcome.FETCHED, p.ensureCell(cell, true))
        t += 29 * DAY
        assertEquals(CellOutcome.FRESH, p.ensureCell(cell, true))
    }

    @Test fun aPartialCellBacksOffFiveTenTwentyFortySixtyMinutesThenAResetOnComplete() = run {
        var complete = false
        val src = FakeSource { CellFetch(listOf(fp("wd:Q1")), complete) }
        val p = pack(src)
        val min = 60_000L
        assertEquals(CellOutcome.PARTIAL, p.ensureCell(cell, true))
        t += 5 * min - 1; assertEquals(CellOutcome.FRESH, p.ensureCell(cell, true)) // 5 min after the first partial answer
        t += 1; assertEquals(CellOutcome.PARTIAL, p.ensureCell(cell, true)) // second partial answer: wait doubles
        t += 10 * min - 1; assertEquals(CellOutcome.FRESH, p.ensureCell(cell, true))
        t += 1; assertEquals(CellOutcome.PARTIAL, p.ensureCell(cell, true))
        t += 20 * min; assertEquals(CellOutcome.PARTIAL, p.ensureCell(cell, true))
        t += 40 * min; assertEquals(CellOutcome.PARTIAL, p.ensureCell(cell, true))
        t += 60 * min - 1; assertEquals("capped at one hour", CellOutcome.FRESH, p.ensureCell(cell, true))
        t += 1; complete = true; assertEquals(CellOutcome.FETCHED, p.ensureCell(cell, true))
        assertEquals(6, src.asked.size)
        assertEquals(listOf(5 * min, 10 * min, 20 * min, 40 * min, 60 * min, 60 * min), (1..6).map { PlacePack.partialFreshMs(it) })
        assertEquals(60 * min, PlacePack.partialFreshMs(500))
    }

    @Test fun aPartialCellThatKeptPlacesStaysUsableWhileItBacksOff() = run {
        val src = FakeSource { CellFetch(listOf(fp("wd:Q1")), complete = false) }
        val p = pack(src)
        assertEquals(1, p.candidates(CHARMINAR_LAT, CHARMINAR_LON, true).places.size)
        t += 6 * 60_000L; src.failWith = IOException("down") // refetch fails: the stored places are still served
        val c = p.candidates(CHARMINAR_LAT, CHARMINAR_LON, true)
        assertEquals(CellOutcome.FAILED, c.outcome); assertEquals(1, c.places.size)
    }

    // Codex pin r2 #7: a stored partial cell whose refetch FAILS advances the backoff too (no refetch on every glance after 5 min).
    @Test fun aFailedRefetchOfAStoredPartialCellBacksOffLikeAnotherPartialAnswer() = run {
        val src = FakeSource { CellFetch(listOf(fp("wd:Q1")), complete = false) }
        val p = pack(src)
        val min = 60_000L
        assertEquals(CellOutcome.PARTIAL, p.ensureCell(cell, true))
        t += 5 * min; src.failWith = IOException("429")
        assertEquals(CellOutcome.FAILED, p.ensureCell(cell, true)); assertEquals(2, src.asked.size)
        t += 10 * min - 1; assertEquals("the failure doubled the wait to 10 min", CellOutcome.FRESH, p.ensureCell(cell, true)); assertEquals(2, src.asked.size)
        t += 1; assertEquals(CellOutcome.FAILED, p.ensureCell(cell, true)); assertEquals(3, src.asked.size)
        t += 20 * min - 1; assertEquals(CellOutcome.FRESH, p.ensureCell(cell, true))
        assertEquals("the stored places stay", 1, p.candidates(CHARMINAR_LAT, CHARMINAR_LON, true).places.size)
    }

    @Test fun aFailedFetchOfACellThatIsNotStoredDoesNotInventABackoff() = run {
        val src = FakeSource(); src.failWith = IOException("down")
        val p = pack(src)
        assertEquals(CellOutcome.FAILED, p.ensureCell(cell, true)); assertEquals(CellOutcome.FAILED, p.ensureCell(cell, true))
        assertEquals(2, src.asked.size); assertNull(db.dao().cell(cell))
    }

    // Codex pin r2 #8: an evicted cell is forgotten with its partial streak.
    @Test fun anEvictedCellForgetsItsPartialStreak() = run {
        val a = cellAt(1); val c = cellAt(3)
        val src = FakeSource { CellFetch(twoIn(it).places, complete = false) }
        val one = pack(src); one.ensureCell(a, true); val (_, oneCell) = one.size()
        val tight = PlacePack(db, src, oneCell, { t }, { logs += it }, { pauses += it })
        val min = 60_000L
        t += 5 * min; assertEquals(CellOutcome.PARTIAL, tight.ensureCell(a, true)) // streak 1 in this pack
        t += 5 * min; assertEquals(CellOutcome.PARTIAL, tight.ensureCell(a, true)) // streak 2
        assertEquals(CellOutcome.PARTIAL, tight.ensureCell(c, true)) // c does not fit with a: a is evicted, with its cell and streak
        assertNull(db.dao().cell(a))
        assertEquals(CellOutcome.PARTIAL, tight.ensureCell(a, true)) // fetched again: a NEW streak (5 min), not streak 3 (20 min)
        t += 5 * min; assertEquals(CellOutcome.PARTIAL, tight.ensureCell(a, true))
    }

    @Test fun candidatesAreNearbyExpandedToSubPlacesAndStampedWithToday() = run {
        val far = fp("wd:Qsub", lat = CHARMINAR_LAT + 0.02, parent = "wd:Qsite") // 2 km away: only reachable through its parent
        val p = pack(FakeSource { CellFetch(listOf(fp("wd:Qsite"), far, fp("wd:Qother", lat = CHARMINAR_LAT + 0.03))) })
        p.ensureCell(cell, true); t += DAY
        val got = p.candidates(CHARMINAR_LAT, CHARMINAR_LON, true)
        assertEquals(listOf("wd:Qsite", "wd:Qsub"), got.places.map { it.id })
        val day = (t / DAY).toInt()
        assertEquals(setOf(day), db.dao().byIds(listOf("wd:Qsite", "wd:Qsub")).map { it.lastUsedDay }.toSet())
        assertEquals(day - 1, db.dao().byIds(listOf("wd:Qother")).single().lastUsedDay)
        assertTrue(p.candidates(0.5, 0.5, false).places.isEmpty())
    }

    @Test fun summariesAreClippedAndOnlyAllowlistedNamedPlacesAreStored() = run {
        val long = "Word ".repeat(200)
        val p = pack(FakeSource { CellFetch(listOf(fp("wd:Q1", summary = long), fp("wd:Qp", kind = "person"), fp("wd:Qn", name = " "), fp("wd:Q1"))) })
        p.ensureCell(cell, true)
        assertEquals(1, p.size().first)
        val s = db.dao().byIds(listOf("wd:Q1")).single()
        assertTrue(s.summary.length <= PlacePack.SUMMARY_MAX && s.summary.endsWith("…"))
        assertEquals(PlacePack.sizeOf(s), s.bytes)
    }

    private fun twoIn(c: String) = Geohash5.bounds(c)!!.let { b -> CellFetch(listOf(fp("$c-a", lat = b.south + 0.01, lon = b.west + 0.01), fp("$c-b", lat = b.south + 0.02, lon = b.west + 0.01))) }
    private fun cellAt(i: Int) = Geohash5.encode(CHARMINAR_LAT + i * 0.1, CHARMINAR_LON)

    @Test fun overBudgetEvictsLeastRecentlyUsedFetchedPlacesButNeverSeededOrPinned() = run {
        val src = FakeSource { twoIn(it) }
        val roomy = pack(src)
        val seedCell = cellAt(0); val a = cellAt(1); val b = cellAt(2); val c = cellAt(3)
        val sb = Geohash5.bounds(seedCell)!!
        roomy.seedArea("z", GeoBox(sb.south + 0.001, sb.west + 0.001, sb.south + 0.002, sb.west + 0.002))
        roomy.ensureCell(a, true); t += DAY; roomy.ensureCell(b, true); t += DAY
        assertTrue(roomy.pin("$b-a", true)) // pinned: survives even though it is the oldest
        db.dao().touch(listOf("$a-a", "$a-b"), (t / DAY).toInt()) // cell a was used today
        val (_, full) = roomy.size()
        val tight = pack(src, budget = full) // no room for anything more
        tight.ensureCell(c, true)
        val ids = db.dao().inBox(-90.0, 90.0, -180.0, 180.0).map { it.id }.toSet()
        assertFalse("$b-b" in ids) // the unpinned oldest one went
        assertTrue("$seedCell-a" in ids && "$seedCell-b" in ids) // seeded never evicted
        assertTrue("$b-a" in ids) // pinned never evicted
        assertTrue("$c-a" in ids && "$c-b" in ids) // what was just fetched stays
        assertTrue(tight.size().second <= full)
        assertNull("an evicted place's cell is fetched again", db.dao().cell(b))
        assertNotNull(db.dao().cell(c))
    }

    @Test fun pinnedPlacesSurviveDeleteAll() = run {
        val p = pack(FakeSource { CellFetch(listOf(fp("wd:Q1"), fp("wd:Q2", lat = CHARMINAR_LAT + 0.001))) })
        p.ensureCell(cell, true)
        assertTrue(p.pin("wd:Q1", true)); assertFalse(p.pin("wd:none", true))
        p.deleteAll()
        assertEquals(listOf("wd:Q1"), db.dao().inBox(-90.0, 90.0, -180.0, 180.0).map { it.id })
    }

    @Test fun seedAreaFetchesCellByCellMarksSeededAndRecordsTheArea() = run {
        val src = FakeSource { c -> CellFetch(listOf(fp("$c:p", lat = Geohash5.bounds(c)!!.south + 0.01, lon = Geohash5.bounds(c)!!.west + 0.01))) }
        val p = pack(src)
        val box = GeoBox(17.30, 78.40, 17.40, 78.50)
        val progress = mutableListOf<Pair<Int, Int>>()
        val r = p.seedArea("hyd", box) { d, n -> progress += d to n }
        val cells = Geohash5.cellsCovering(box)
        assertEquals(cells, src.asked); assertEquals(cells.size, r.fetched); assertTrue(r.complete)
        assertEquals(cells.size to cells.size, progress.last())
        assertTrue(db.dao().inBox(-90.0, 90.0, -180.0, 180.0).all { it.seeded })
        val a = db.dao().areas().single()
        assertEquals("hyd", a.blueZoneId); assertEquals(AREA_DONE, a.status); assertEquals(box, a.box); assertEquals(t, a.seededAt)
        // seeding again fetches nothing and keeps the area
        val again = p.seedArea("hyd", box)
        assertEquals(cells.size, again.skipped); assertEquals(cells, src.asked)
    }

    // v18 WP11b: the seed-area name is null by default, set and cleared by setAreaName (trimmed, bounded), and survives a re-seed (the seed rewrites the area row)
    @Test fun anAreaNameDefaultsToNullAndSurvivesAReSeed() = runBlocking {
        val src = FakeSource { c -> CellFetch(listOf(fp("$c:p", lat = Geohash5.bounds(c)!!.south + 0.01, lon = Geohash5.bounds(c)!!.west + 0.01))) }
        val p = pack(src)
        val box = GeoBox(17.30, 78.40, 17.40, 78.50)
        assertFalse("no such area", p.setAreaName("hyd", "x"))
        p.seedArea("hyd", box)
        assertEquals(null, p.areas().single().name)
        assertTrue(p.setAreaName("hyd", "  Old town  ")); assertEquals("Old town", p.areas().single().name)
        p.seedArea("hyd", GeoBox(17.30, 78.40, 17.45, 78.55)) // a re-seed rewrites the row: the name stays
        assertEquals("Old town", p.areas().single().name)
        assertTrue(p.setAreaName("hyd", "x".repeat(500))); assertEquals(PlacePack.MAX_AREA_NAME, p.areas().single().name!!.length)
        assertTrue(p.setAreaName("hyd", "   ")); assertEquals(null, p.areas().single().name)
    }

    @Test fun aFailedCellMakesTheAreaPartialAndTheRestGoOn() = run {
        val box = GeoBox(17.30, 78.40, 17.36, 78.46)
        val cells = Geohash5.cellsCovering(box)
        val src = FakeSource { c -> if (c == cells.first()) throw IOException("x") else CellFetch(listOf(fp("$c:p", lat = Geohash5.bounds(c)!!.south + 0.01, lon = Geohash5.bounds(c)!!.west + 0.01))) }
        val r = pack(src).seedArea("z", box)
        assertEquals(1, r.failed); assertFalse(r.complete)
        assertEquals(AREA_PARTIAL, db.dao().areas().single().status)
        assertEquals(cells + cells.first(), src.asked) // the failed cell was tried once more after the others
        assertEquals(listOf(PlacePack.RETRY_BACKOFF_MS), pauses); assertEquals(1, r.retried); assertEquals(cells.size - 1, r.ok)
        assertEquals(0, pack(null).seedArea("z", box).fetched)
    }

    // S3: a seed stopped half way (network lost) is resumed by seeding again: the finished cells are not fetched again, only the rest, and progress counts all cells
    @Test fun aStoppedSeedResumesAndSkipsTheFinishedCells() = run {
        val box = GeoBox(17.30, 78.40, 17.45, 78.55)
        val cells = Geohash5.cellsCovering(box); assertTrue(cells.size >= 4)
        val src = FakeSource { c -> CellFetch(listOf(fp("$c:p", lat = Geohash5.bounds(c)!!.south + 0.01, lon = Geohash5.bounds(c)!!.west + 0.01))) }
        val p = pack(src)
        val stop = runCatching { p.seedArea("a", box, admit = { if (src.asked.size >= 2) "no network" else null }) }.exceptionOrNull()
        assertTrue(stop is SeedStopped); assertEquals("no network", (stop as SeedStopped).reason)
        assertEquals(AREA_PARTIAL, db.dao().areas().single().status)
        val first = src.asked.size; assertEquals(2, first)
        val progress = mutableListOf<Pair<Int, Int>>()
        val r = p.seedArea("a", box, onProgress = { d, n -> progress += d to n })
        assertEquals(cells.size - first, src.asked.size - first) // only the unfinished cells went to the source
        assertEquals(first, r.skipped); assertEquals(cells.size to cells.size, progress.last())
        assertEquals(AREA_DONE, db.dao().areas().single().status)
    }

    // S3: the area is recorded as seeding before the work is queued; reconcile turns a seeding row with no live work into partial (never forever "seeding"), after a grace
    @Test fun anAreaMarkedSeedingWithNoLiveWorkBecomesPartialAfterTheGrace() = run {
        val p = pack(FakeSource())
        val box = GeoBox(17.30, 78.40, 17.36, 78.46)
        p.beginArea("b", box)
        assertEquals(AREA_SEEDING, db.dao().areas().single().status)
        assertFalse("just written: its work is being queued", p.stallSeeding("b", graceMs = 30_000L))
        t += 31_000L
        assertTrue(p.stallSeeding("b", graceMs = 30_000L))
        assertEquals(AREA_PARTIAL, db.dao().areas().single().status)
        assertFalse("not seeding any more", p.stallSeeding("b", graceMs = 0L))
        assertFalse("no such area", p.stallSeeding("zz", graceMs = 0L))
    }

    private fun seedPlace(c: String) = fp("$c:p", lat = Geohash5.bounds(c)!!.south + 0.01, lon = Geohash5.bounds(c)!!.west + 0.01)

    @Test fun aCellThatFailsOnceIsRetriedAfterTheOthersAndRecovers() = run {
        val box = GeoBox(17.30, 78.40, 17.36, 78.46)
        val cells = Geohash5.cellsCovering(box)
        var flaky = true
        val src = FakeSource { c -> if (c == cells.first() && flaky) { flaky = false; throw IOException("429") } else CellFetch(listOf(seedPlace(c))) }
        val r = pack(src).seedArea("z", box)
        assertEquals(0, r.failed); assertTrue(r.complete); assertEquals(1, r.retried); assertEquals(cells.size, r.ok); assertEquals(0, r.partial)
        assertEquals(cells + cells.first(), src.asked)
        assertEquals(AREA_DONE, db.dao().areas().single().status)
        assertEquals(cells.size, db.dao().placeCount())
        assertTrue(logs.any { it == "seed retry cells=1 recovered=1" })
    }

    @Test fun partialCellsAreCountedStoredWithTheMarkerAndRetriedByTheNextSeed() = run {
        val box = GeoBox(17.30, 78.40, 17.36, 78.46)
        val cells = Geohash5.cellsCovering(box)
        var osmUp = false
        val src = FakeSource { c -> CellFetch(listOf(seedPlace(c)), complete = osmUp || c != cells.first()) }
        val p = pack(src)
        val r = p.seedArea("z", box)
        assertEquals(1, r.partial); assertEquals(cells.size - 1, r.ok); assertEquals(0, r.failed); assertEquals(cells.size, r.fetched); assertFalse(r.complete); assertEquals(1, r.retryCells) // a partial cell is not a seed success
        assertEquals(AREA_PARTIAL, db.dao().areas().single().status)
        assertEquals(0, r.retried); assertTrue(pauses.isEmpty())
        assertEquals(CELL_PARTIAL, db.dao().cell(cells.first())!!.status)
        assertEquals(CELL_OK, db.dao().cell(cells.last())!!.status)
        assertEquals(cells.size, db.dao().placeCount()) // Wikipedia/Wikidata places were stored
        assertTrue(logs.any { it.startsWith("seed done cells=${cells.size} ok=${cells.size - 1} partial=1 skipped=0 failed=0") })
        osmUp = true
        val again = p.seedArea("z", box) // only the partial cell is asked again, at once (not only after a day)
        assertEquals(cells + cells.first(), src.asked)
        assertEquals(0, again.partial); assertEquals(cells.size - 1, again.skipped); assertEquals(1, again.ok)
        assertEquals(CELL_OK, db.dao().cell(cells.first())!!.status)
    }

    @Test fun logsCarryCountsOnlyNeverCoordinatesOrNames() = run {
        val box = GeoBox(17.30, 78.40, 17.36, 78.46)
        pack(FakeSource { c -> if (c == Geohash5.cellsCovering(box).first()) throw IOException("x") else CellFetch(listOf(seedPlace(c)), complete = false) }).seedArea("z", box)
        assertTrue(logs.toString(), logs.none { Regex("""\d+\.\d{3}""").containsMatchIn(it) || "wd:" in it || ":p" in it })
    }

    @Test fun deleteAllClearsFetchedAndRecencyButKeepsSeededAreasAndPinned() = run {
        val seededCells = Geohash5.cellsCovering(GeoBox(17.30, 78.40, 17.31, 78.41))
        val src = FakeSource { c -> CellFetch(listOf(fp("$c:p", lat = Geohash5.bounds(c)!!.south + 0.01, lon = Geohash5.bounds(c)!!.west + 0.01))) }
        val p = pack(src)
        p.seedArea("hyd", GeoBox(17.30, 78.40, 17.31, 78.41))
        val other = Geohash5.encode(CHARMINAR_LAT + 0.5, CHARMINAR_LON + 0.5)
        p.ensureCell(other, true)
        val b = Geohash5.bounds(other)!!
        p.candidates(b.south + 0.01, b.west + 0.01, false); p.candidates(Geohash5.bounds(seededCells[0])!!.let { it.south + 0.01 }, Geohash5.bounds(seededCells[0])!!.let { it.west + 0.01 }, false)
        p.pin("$other:p", true) // a pinned fetched place survives too
        p.ensureCell(Geohash5.encode(-10.0, 100.0), true)
        assertTrue(p.size().first > seededCells.size)
        p.deleteAll()
        val left = db.dao().inBox(-90.0, 90.0, -180.0, 180.0)
        assertEquals(seededCells.map { "$it:p" }.toSet() + "$other:p", left.map { it.id }.toSet())
        assertTrue(left.all { it.lastUsedDay == 0 })
        assertEquals(1, db.dao().areas().size)
        assertNotNull(db.dao().cell(seededCells[0])); assertNull(db.dao().cell(Geohash5.encode(-10.0, 100.0)))
        // the cell outside the seeded areas is fetched again at the next glance
        assertEquals(CellOutcome.FETCHED, p.ensureCell(Geohash5.encode(-10.0, 100.0), true))
    }

    @Test fun logsNeverCarryAPositionOrPlaceText() = run {
        val src = FakeSource { CellFetch(listOf(fp("wd:Q1", name = "Charminar", summary = "Secret summary"))) }
        val p = pack(src)
        p.ensureCell(cell, true); p.candidates(CHARMINAR_LAT, CHARMINAR_LON, true); src.failWith = IOException("17.36 78.47"); p.ensureCell(Geohash5.encode(0.0, 0.0), true)
        p.deleteAll()
        assertTrue(logs.isNotEmpty())
        for (l in logs) {
            assertFalse(l, Regex("""\d+\.\d{2,}""").containsMatchIn(l))
            assertFalse(l, "Charminar" in l || "Secret" in l || "wd:" in l || cell in l)
        }
    }

    @Test fun theDatabaseIsItsOwnFileNamedPlacesDb() {
        assertEquals("places.db", PlacesDatabase.NAME)
        assertEquals(1, 1)
    }

    // Codex Place Guide r1: Delete all shares the fetch lock and invalidates a fetch already in flight.
    @Test fun aFetchStartedBeforeDeleteAllCannotReinsertAfterIt() = runBlocking {
        val started = kotlinx.coroutines.CompletableDeferred<Unit>(); val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val src = object : CellSource {
            override suspend fun fetchCell(cell: String): CellFetch { started.complete(Unit); release.await(); return CellFetch(listOf(fp("wd:Q1"))) }
        }
        val p = pack(src)
        val fetch = async { p.ensureCell(cell, true) }
        started.await()
        val del = async { p.deleteAll() }
        yield() // deleteAll has advanced the epoch and is waiting for the lock
        release.complete(Unit)
        del.await(); assertEquals(CellOutcome.FAILED, fetch.await())
        assertEquals("nothing came back after Delete all", 0, p.size().first)
        assertEquals("the cell is fetched again at the next glance", CellOutcome.FETCHED, pack(FakeSource { CellFetch(listOf(fp("wd:Q1"))) }).ensureCell(cell, true))
    }

    // Codex r4: the zone fence sits at the dispatch points of the pack.

    @Test fun anAttemptWaitingForTheFetchLockIsCancelledByTheFenceAndNeverFetches() = runBlocking {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val other = Geohash5.encode(17.3833, 78.4011)
        val src = object : CellSource {
            val asked = mutableListOf<String>()
            override suspend fun fetchCell(cell: String): CellFetch { asked += cell; if (asked.size == 1) gate.await(); return CellFetch(emptyList()) }
        }
        val p = pack(src); val fence = ZoneFence()
        val first = async { p.ensureCell(cell, true) }
        while (src.asked.isEmpty()) yield()
        val second = fence.launch(this) { p.ensureCell(other, true) }
        yield(); fence.hold()
        gate.complete(Unit); first.await(); second.join()
        assertTrue(second.isCancelled); assertEquals(listOf(cell), src.asked)
    }

    @Test fun aTicketThatClosedDuringTheLockWaitIsRevalidatedAfterItEvenWithoutCancellation() = runBlocking {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val other = Geohash5.encode(17.3833, 78.4011)
        var blocked = false
        val src = object : CellSource {
            val asked = mutableListOf<String>()
            override suspend fun fetchCell(cell: String): CellFetch { asked += cell; if (asked.size == 1) gate.await(); return CellFetch(emptyList()) }
        }
        val p = pack(src); val fence = ZoneFence { blocked }
        val first = async { p.ensureCell(cell, true) }
        while (src.asked.isEmpty()) yield()
        val second = async { kotlinx.coroutines.withContext(fence.ticket()) { runCatching { p.ensureCell(other, true) } } }
        yield(); blocked = true // the zone arrives while B waits for the lock; B itself is not cancelled
        gate.complete(Unit); first.await()
        assertTrue(second.await().exceptionOrNull() is ZoneFenced); assertEquals(listOf(cell), src.asked)
    }

    @Test fun aClosedTicketNeverReachesTheSource() = runBlocking {
        val src = FakeSource { CellFetch(listOf(fp("wd:Q1"))) }
        val fence = ZoneFence(); fence.hold()
        val r = runCatching { kotlinx.coroutines.withContext(fence.ticket()) { pack(src).candidates(CHARMINAR_LAT, CHARMINAR_LON, true) } }
        assertTrue(r.exceptionOrNull() is ZoneFenced); assertTrue(src.asked.isEmpty())
    }

    // ---- roaming data saver: stale-while-costly ----

    private val charminarBox = GeoBox(17.30, 78.40, 17.40, 78.50)

    @Test fun aStaleSeededCellIsKeptNotRefetchedOnACostlyNetworkAndRefetchedOnAFreeOne() = run {
        val src = FakeSource { c -> CellFetch(listOf(fp("$c:p", lat = Geohash5.bounds(c)!!.south + 0.01, lon = Geohash5.bounds(c)!!.west + 0.01))) }
        val p = pack(src)
        p.seedArea("hyd", charminarBox)
        val askedAfterSeed = src.asked.size
        t += 60 * DAY // past FRESH_MS: stale
        assertEquals("kept, used as it is", CellOutcome.OFFLINE, p.ensureCell(cell, true, costly = true))
        assertEquals("nothing fetched", askedAfterSeed, src.asked.size)
        assertTrue(logs.any { it == "cell stale kept while costly seeded=true" })
        assertEquals("a free network refreshes it", CellOutcome.FETCHED, p.ensureCell(cell, true, costly = false))
        assertEquals(askedAfterSeed + 1, src.asked.size)
    }

    @Test fun aStalePartialCellIsKeptWhileCostlyEvenOutsideASeededArea() = run {
        val src = FakeSource { CellFetch(listOf(fp("wd:Q1")), complete = false) }
        val p = pack(src)
        assertEquals(CellOutcome.PARTIAL, p.ensureCell(cell, true))
        t += 2 * DAY // past PARTIAL_FRESH_MS
        assertEquals(CellOutcome.OFFLINE, p.ensureCell(cell, true, costly = true))
        t += 30 * 60_000L // data saver: kept as it is however old, never fetched on a costly network
        assertEquals(CellOutcome.OFFLINE, p.ensureCell(cell, true, costly = true))
        assertEquals(1, src.asked.size)
        assertTrue(logs.any { it == "cell stale kept while costly seeded=false" })
        assertEquals(CellOutcome.PARTIAL, p.ensureCell(cell, true, costly = false))
        assertEquals(2, src.asked.size)
    }

    @Test fun aStaleCellOutsideEverySeededAreaAndACellNeverStoredAreStillFetchedWhileCostly() = run {
        val src = FakeSource { CellFetch(listOf(fp("wd:Q1"))) }
        val p = pack(src)
        assertEquals("never stored: a small request", CellOutcome.FETCHED, p.ensureCell(cell, true, costly = true))
        t += 40 * DAY
        assertEquals("stale, not seeded: refetched", CellOutcome.FETCHED, p.ensureCell(cell, true, costly = true))
        assertEquals(2, src.asked.size)
    }

    @Test fun costlyAndNoNetworkNeverFetchesAndTheLogLinesHoldNoCellKey() = run {
        val src = FakeSource { c -> CellFetch(listOf(fp("$c:p", lat = Geohash5.bounds(c)!!.south + 0.01, lon = Geohash5.bounds(c)!!.west + 0.01))) }
        val p = pack(src)
        p.seedArea("hyd", charminarBox); t += 60 * DAY
        val n = src.asked.size
        p.candidates(CHARMINAR_LAT, CHARMINAR_LON, networkUp = false, costly = true)
        p.candidates(CHARMINAR_LAT, CHARMINAR_LON, networkUp = true, costly = true)
        assertEquals(n, src.asked.size)
        assertTrue(logs.none { it.contains(cell) })
    }

    // ---- map P0 fix: seed admission, area removal ----
    private fun cornerPlace(c: String) = fp("$c:p", lat = Geohash5.bounds(c)!!.south + 0.01, lon = Geohash5.bounds(c)!!.west + 0.01)

    @Test fun aSeedStopsWhenTheAdmissionIsWithdrawnMidwayAndSaysWhy() = run {
        val box = GeoBox(17.30, 78.40, 17.36, 78.46)
        assertTrue(Geohash5.cellsCovering(box).size > 1)
        var open = true
        val src = FakeSource { c -> open = false; CellFetch(listOf(cornerPlace(c))) } // the privacy mode flips after the first cell
        val e = runCatching { pack(src).seedArea("z", box, admit = { if (open) null else "offline only" }) }.exceptionOrNull()
        assertTrue(e is SeedStopped); assertEquals("offline only", (e as SeedStopped).reason)
        assertEquals(1, src.asked.size) // no second cell was requested
        assertEquals(AREA_PARTIAL, db.dao().areas().single().status) // not a silent partial: the area says it did not finish
        assertTrue(logs.toString(), logs.none { Regex("""\d+\.\d{3}""").containsMatchIn(it) })
    }

    @Test fun aRefusalByOfflineOnlyIsTheOfflineOutcomeAndStoresNothing() = run {
        val src = FakeSource().also { it.failWith = PlaceOfflineOnly() }
        val p = pack(src)
        assertEquals(CellOutcome.OFFLINE, p.ensureCell(Geohash5.encode(CHARMINAR_LAT, CHARMINAR_LON), networkUp = true))
        assertEquals(1, src.asked.size)
        assertEquals(0, db.dao().placeCount())
        assertTrue(logs.toString(), logs.none { Regex("""\d+\.\d{3}""").containsMatchIn(it) })
    }

    @Test fun anAdmissionThatThrowsFailsClosed() = run {
        val src = FakeSource { c -> CellFetch(listOf(cornerPlace(c))) }
        val e = runCatching { pack(src).seedArea("z", GeoBox(17.30, 78.40, 17.31, 78.41), admit = { throw IllegalStateException("unreadable") }) }.exceptionOrNull()
        assertTrue(e is SeedStopped); assertTrue(src.asked.isEmpty())
    }

    @Test fun removingAnAreaClearsTheSeededFlagOfItsPlacesButKeepsPinsAndOtherAreas() = run {
        val src = FakeSource { c -> CellFetch(listOf(cornerPlace(c))) }
        val p = pack(src)
        val boxA = GeoBox(17.30, 78.40, 17.31, 78.41); val boxB = GeoBox(10.00, 78.40, 10.01, 78.41)
        p.seedArea("a", boxA); p.seedArea("b", boxB)
        val aIds = Geohash5.cellsCovering(boxA).map { "$it:p" }
        val bIds = Geohash5.cellsCovering(boxB).map { "$it:p" }
        assertTrue(p.pin(aIds.first(), true))
        p.removeArea("a")
        val all = db.dao().inBox(-90.0, 90.0, -180.0, 180.0).associateBy { it.id }
        assertTrue(aIds.all { !all.getValue(it).seeded }); assertTrue(all.getValue(aIds.first()).pinned)
        assertTrue(bIds.all { all.getValue(it).seeded })
        assertEquals(listOf("b"), db.dao().areas().map { it.blueZoneId })
        // the places are ordinary fetched places now: Clear fetched removes the unpinned ones; the pin and the other area stay
        assertEquals(aIds.size - 1, p.clearFetched())
        assertEquals((bIds + aIds.first()).toSet(), db.dao().inBox(-90.0, 90.0, -180.0, 180.0).map { it.id }.toSet())
    }

    private fun ref(id: String, caption: String = "a stone gate") = PlaceRefEntity(id, caption, "https://commons.wikimedia.org/wiki/File:X.jpg", "Someone", "CC BY-SA 4.0", 5L, caption.length)

    @Test fun referenceCaptionsAreStoredOnlyForKnownPlacesAndGoWithTheirPlace() = run {
        val p = pack(FakeSource { c -> CellFetch(listOf(cornerPlace(c))) })
        val box = GeoBox(17.30, 78.40, 17.31, 78.41)
        p.seedArea("a", box)
        val ids = Geohash5.cellsCovering(box).map { "$it:p" }
        assertEquals(ids.size, p.putRefs(ids.map { ref(it) } + ref("wd:unknown"))) // an unknown place is dropped
        assertEquals(ids.toSet(), p.refPlaceIds().toSet())
        assertEquals("a stone gate", p.refsOf(ids).first().caption)
        // seeded places and their captions survive Delete all and Clear fetched
        p.deleteAll(); assertEquals(ids.size, p.refPlaceIds().size)
        // an area removed WITHOUT its places keeps them and their captions
        p.removeArea("a"); assertEquals(ids.size, p.refPlaceIds().size)
    }

    @Test fun clearingFetchedPlacesRemovesTheirCaptionsButNotThoseOfSeededOrPinnedPlaces() = run {
        val src = FakeSource { c -> CellFetch(listOf(cornerPlace(c))) }
        val p = pack(src)
        val box = GeoBox(17.30, 78.40, 17.31, 78.41)
        p.seedArea("a", box)
        val seeded = Geohash5.cellsCovering(box).map { "$it:p" }
        p.removeArea("a") // now ordinary fetched places
        p.putRefs(seeded.map { ref(it) })
        assertTrue(p.pin(seeded.first(), true))
        p.clearFetched()
        assertEquals(listOf(seeded.first()), p.refPlaceIds())
    }

    @Test fun removingAnAreaWithItsPlacesDeletesTheSeededPlacesTheirCaptionsAndSparesPinsAndOtherAreas() = run {
        val p = pack(FakeSource { c -> CellFetch(listOf(cornerPlace(c))) })
        val boxA = GeoBox(17.30, 78.40, 17.31, 78.41); val boxB = GeoBox(10.00, 78.40, 10.01, 78.41)
        p.seedArea("a", boxA); p.seedArea("b", boxB)
        val aIds = Geohash5.cellsCovering(boxA).map { "$it:p" }; val bIds = Geohash5.cellsCovering(boxB).map { "$it:p" }
        p.putRefs((aIds + bIds).map { ref(it) })
        assertTrue(p.pin(aIds.first(), true))
        p.removeArea("a", withPlaces = true)
        val left = db.dao().inBox(-90.0, 90.0, -180.0, 180.0).map { it.id }.toSet()
        assertEquals((bIds + aIds.first()).toSet(), left) // the pinned place and the other area's places stay
        assertEquals((bIds + aIds.first()).toSet(), p.refPlaceIds().toSet()) // captions follow their place
        assertEquals(listOf("b"), db.dao().areas().map { it.blueZoneId })
        assertTrue(db.dao().cells().none { Geohash5.bounds(it.geohash5)!!.intersects(boxA) }) // the removed area's cells are forgotten: fetched again on demand
    }

    @Test fun removingAnAreaWithItsPlacesAlsoDeletesSeededPlacesOfItsBoundaryCellsOutsideTheDrawnRectangle() = run {
        val box = GeoBox(17.300, 78.400, 17.301, 78.401) // tiny: its cell is much larger
        val cell = Geohash5.cellsCovering(box).single(); val b = Geohash5.bounds(cell)!!
        val p = pack(FakeSource { c -> CellFetch(listOf(fp("$c:far", lat = b.north - 0.001, lon = b.east - 0.001))) })
        p.seedArea("a", box)
        val far = db.dao().inBox(-90.0, 90.0, -180.0, 180.0).single()
        assertTrue(!box.contains(far.lat, far.lon) && far.seeded)
        p.removeArea("a", withPlaces = true)
        assertEquals(0, db.dao().placeCount())
    }

    @Test fun anAreaWithItsPlacesKeepsPlacesAnotherAreaStillCovers() = run {
        val p = pack(FakeSource { c -> CellFetch(listOf(cornerPlace(c))) })
        p.seedArea("a", GeoBox(17.30, 78.40, 17.31, 78.41)); p.seedArea("a2", GeoBox(17.305, 78.405, 17.315, 78.415))
        val before = db.dao().placeCount()
        p.removeArea("a", withPlaces = true)
        assertEquals(before, db.dao().placeCount())
        assertTrue(db.dao().inBox(-90.0, 90.0, -180.0, 180.0).all { it.seeded })
    }

    @Test fun evictionDeletesTheCaptionsOfTheEvictedPlaces() = run {
        val p = pack(FakeSource { c -> CellFetch(listOf(cornerPlace(c))) }, budget = PlacePack.MIN_BUDGET_BYTES)
        p.ensureCell(cell, networkUp = true)
        val id = db.dao().inBox(-90.0, 90.0, -180.0, 180.0).first().id
        p.putRefs(listOf(ref(id)))
        p.setBudget(0) // evicts every fetched place
        assertEquals(0, db.dao().placeCount()); assertTrue(p.refPlaceIds().isEmpty())
    }

    @Test fun aPlaceStillCoveredByAnotherSeededAreaStaysSeeded() = run {
        val p = pack(FakeSource { c -> CellFetch(listOf(cornerPlace(c))) })
        p.seedArea("a", GeoBox(17.30, 78.40, 17.31, 78.41))
        p.seedArea("a2", GeoBox(17.305, 78.405, 17.315, 78.415)) // overlaps the same cell
        p.removeArea("a")
        assertTrue(db.dao().inBox(-90.0, 90.0, -180.0, 180.0).all { it.seeded })
        p.removeArea("a2")
        assertTrue(db.dao().inBox(-90.0, 90.0, -180.0, 180.0).none { it.seeded })
    }

    @Test fun removingAnAreaThatIsBeingSeededCancelsTheSeedFirstSoItIsNotBroughtBack() = run {
        val box = GeoBox(17.30, 78.40, 17.36, 78.46)
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val src = FakeSource { c -> release.await(); CellFetch(listOf(cornerPlace(c))) }
        val p = pack(src)
        @Suppress("OPT_IN_USAGE")
        val seed = kotlinx.coroutines.GlobalScope.async(kotlinx.coroutines.Dispatchers.Default) { runCatching { p.seedArea("z", box) } }
        kotlinx.coroutines.withTimeout(10_000) { while (src.asked.isEmpty()) kotlinx.coroutines.delay(10) } // the seed is inside its first fetch
        p.removeArea("z")
        val r = seed.await().exceptionOrNull()
        assertTrue(r is SeedStopped); assertEquals("area removed", (r as SeedStopped).reason)
        assertTrue(db.dao().areas().isEmpty()) // the seed's last write did not resurrect it
        release.complete(Unit)
        assertTrue(db.dao().areas().isEmpty())
    }

    @Test fun twoConcurrentSeedsOfOneAreaThenRemovalLeavesTheAreaRemoved() = run {
        val box = GeoBox(17.30, 78.40, 17.36, 78.46)
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val src = FakeSource { c -> release.await(); CellFetch(listOf(cornerPlace(c))) }
        val p = pack(src)
        @Suppress("OPT_IN_USAGE")
        val a = kotlinx.coroutines.GlobalScope.async(kotlinx.coroutines.Dispatchers.Default) { runCatching { p.seedArea("z", box) } }
        @Suppress("OPT_IN_USAGE")
        val b = kotlinx.coroutines.GlobalScope.async(kotlinx.coroutines.Dispatchers.Default) { runCatching { p.seedArea("z", box) } }
        kotlinx.coroutines.withTimeout(10_000) { while (src.asked.isEmpty()) kotlinx.coroutines.delay(10) }; kotlinx.coroutines.delay(300) // one seed is inside its fetch, the other has registered (it may wait for the fetch lock)
        p.removeArea("z")
        release.complete(Unit)
        assertTrue(a.await().exceptionOrNull() is SeedStopped); assertTrue(b.await().exceptionOrNull() is SeedStopped)
        assertTrue(db.dao().areas().isEmpty()) // neither seed's last write brought the area back
    }
}
