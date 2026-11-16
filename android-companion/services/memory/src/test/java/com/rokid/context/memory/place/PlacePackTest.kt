package com.rokid.context.memory.place

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
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
private class FakeSource(var answer: (String) -> CellFetch = { CellFetch(emptyList()) }) : CellSource {
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
    private val cell = Geohash5.encode(CHARMINAR_LAT, CHARMINAR_LON)

    @Before fun open() { db = PlacesDatabase.inMemory(ApplicationProvider.getApplicationContext()) }
    @After fun close() = db.close()

    private fun pack(src: CellSource?, budget: Long = PlacePack.DEFAULT_BUDGET_BYTES) = PlacePack(db, src, budget, { t }, { logs += it })
    private fun <T> run(b: suspend () -> T): T = runBlocking { b() }

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

    @Test fun anIncompleteCellIsRefetchedAfterADayNotThirtyAndPlacesStayWithTheirFlags() = run {
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

    @Test fun aFailedCellMakesTheAreaPartialAndTheRestGoOn() = run {
        val box = GeoBox(17.30, 78.40, 17.36, 78.46)
        val cells = Geohash5.cellsCovering(box)
        val src = FakeSource { c -> if (c == cells.first()) throw IOException("x") else CellFetch(listOf(fp("$c:p", lat = Geohash5.bounds(c)!!.south + 0.01, lon = Geohash5.bounds(c)!!.west + 0.01))) }
        val r = pack(src).seedArea("z", box)
        assertEquals(1, r.failed); assertFalse(r.complete)
        assertEquals(AREA_PARTIAL, db.dao().areas().single().status)
        assertEquals(cells, src.asked)
        assertEquals(0, pack(null).seedArea("z", box).fetched)
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
}
