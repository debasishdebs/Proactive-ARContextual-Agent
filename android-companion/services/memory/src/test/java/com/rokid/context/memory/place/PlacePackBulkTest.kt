package com.rokid.context.memory.place

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.IOException

private const val DAY2 = PlacePack.DAY_MS
private fun place(id: String, lat: Double, lon: Double) = FetchedPlace(id, null, id, PlaceKinds.ATTRACTION, lat, lon, 60, "An intro.", "wikipedia:en", "page:1")

/** Bulk discovery in the pack (owner decision 27 Nov): cell bookkeeping, progress, split, fallback, cap, resume, with fake sources only. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PlacePackBulkTest {
    private lateinit var db: PlacesDatabase
    private var t = 100 * DAY2
    private val logs = mutableListOf<String>()
    private val bounds = Geohash5.bounds(Geohash5.encode(27.3, 88.6))!!
    /** 3 x 2 cells around a generic point. */
    private val smallBox = GeoBox(bounds.south + 0.001, bounds.west + 0.001, bounds.north + 0.001 + 2 * Geohash5.LAT_STEP, bounds.east + 0.001 + Geohash5.LON_STEP)
    /** A tall one-cell-wide strip that spans at least two bulk tiles (46 cells north-south > 40). */
    private val strip = GeoBox(bounds.south + 0.001, bounds.west + 0.001, bounds.south + 0.001 + 46 * Geohash5.LAT_STEP, bounds.west + 0.002)

    @Before fun open() { db = PlacesDatabase.inMemory(ApplicationProvider.getApplicationContext()) }
    @After fun close() = db.close()

    private class Cells(var fail: Boolean = false) : CellSource {
        val asked = mutableListOf<String>()
        override suspend fun fetchCell(cell: String): CellFetch { asked += cell; if (fail) throw IOException("down"); return CellFetch(emptyList()) }
    }
    private class Bulk(val answer: suspend (GeoBox) -> List<FetchedPlace> = { emptyList() }) : BulkSource {
        val boxes = mutableListOf<GeoBox>()
        override suspend fun fetchBox(box: GeoBox): List<FetchedPlace> { boxes += box; return answer(box) }
    }
    private fun pack(cells: CellSource?, bulk: BulkSource?) = PlacePack(db, cells, PlacePack.DEFAULT_BUDGET_BYTES, { t }, { logs += it }, {}, bulk = bulk)
    private fun <T> run(b: suspend () -> T): T = runBlocking { b() }
    private fun nCells(b: GeoBox) = Geohash5.cellsCovering(b).size
    /** The cells INSIDE a box handed to the bulk source (a box that ends exactly on a cell edge would otherwise count the next cell too). */
    private fun cellsIn(b: GeoBox) = Geohash5.cellsCovering(GeoBox(b.south + 1e-7, b.west + 1e-7, b.north - 1e-7, b.east - 1e-7))

    @Test fun bulkStoresPlacesMarksCellsAndNeverAsksThePerCellPath() = run {
        val cells = Cells()
        val inside = place("wd:Q1", smallBox.south + 0.01, smallBox.west + 0.01)
        val bulk = Bulk { listOf(inside, place("wd:Q9", 0.0, 0.0)) } // the second lies outside every cell of the tile: not stored
        val counts = mutableListOf<SeedCounts>()
        val r = pack(cells, bulk).seedArea("a", smallBox, onCounts = { counts += it }, bulk = true)
        assertTrue(cells.asked.isEmpty())
        assertEquals(1, bulk.boxes.size)
        assertEquals(nCells(smallBox), r.cells); assertEquals(0, r.failed); assertEquals(1, r.places)
        assertEquals(nCells(smallBox), r.withoutOsm) // done for seeding, without OSM extras
        val p = pack(null, null)
        assertTrue(Geohash5.cellsCovering(smallBox).all { p.cellStatuses(smallBox)[it] == CELL_PARTIAL })
        assertEquals("DONE", db.dao().areas().single().status)
        val row = db.dao().byIds(listOf("wd:Q1")).single()
        assertTrue(row.seeded); assertEquals("An intro.", row.summary)
        assertEquals(SeedCounts(r.cells, r.cells, 0, r.cells, r.cells), counts.last())
        assertTrue(logs.any { it.startsWith("seed progress ") }); assertTrue(logs.any { it.startsWith("seed final ") })
        assertTrue(logs.none { it.contains("wd:Q1") })
    }

    @Test fun bulkOffKeepsThePerCellPathUnchanged() = run {
        val cells = Cells(); val bulk = Bulk()
        val r = pack(cells, bulk).seedArea("a", smallBox) // bulk = false (the default)
        assertEquals(nCells(smallBox), cells.asked.size); assertTrue(bulk.boxes.isEmpty()); assertEquals(0, r.failed)
        // a pack without a bulk source also stays per-cell even when asked
        val other = GeoBox(smallBox.south + 5.0, smallBox.west, smallBox.north + 5.0, smallBox.east) // another area: the first one's cells are stored
        val c2 = Cells(); pack(c2, null).seedArea("b", other, bulk = true); assertEquals(nCells(other), c2.asked.size)
    }

    @Test fun aSecondSeedSkipsCellsThatAreFreshAndKeepsTheThirtyDayRule() = run {
        val bulk = Bulk(); val p = pack(Cells(), bulk)
        p.seedArea("a", smallBox, bulk = true)
        val r = p.seedArea("a", smallBox, bulk = true)
        assertEquals(1, bulk.boxes.size) // nothing asked the second time
        assertEquals(nCells(smallBox), r.skipped); assertEquals(nCells(smallBox), r.partialFresh)
        t += 31 * DAY2 // older than 30 days: bulk again
        p.seedArea("a", smallBox, bulk = true)
        assertEquals(2, bulk.boxes.size)
    }

    @Test fun aBoxThatTimesOutIsSplitUntilItAnswers() = run {
        val bulk = Bulk { b -> if (cellsIn(b).size > 2) throw BulkTooBig("timeout") else emptyList() }
        val cells = Cells()
        val r = pack(cells, bulk).seedArea("a", smallBox, bulk = true)
        assertTrue(bulk.boxes.size > 1)
        assertTrue(cells.asked.isEmpty()); assertEquals(0, r.failed)
        assertEquals(nCells(smallBox), Geohash5.cellsCovering(smallBox).count { pack(null, null).cellStatuses(smallBox)[it] == CELL_PARTIAL })
        assertTrue(logs.any { it.startsWith("bulk box split") })
    }

    @Test fun aBoxThatCannotBeSplitFurtherFallsBackPerCell() = run {
        val bulk = Bulk { throw BulkTooBig("always") }
        val cells = Cells()
        val r = pack(cells, bulk).seedArea("a", smallBox, bulk = true)
        assertEquals(nCells(smallBox), cells.asked.toSet().size) // every cell walked per cell
        assertEquals(0, r.failed)
    }

    @Test fun anErrorFallsBackPerCellOnlyForTheAffectedTile() = run {
        var n = 0
        val bulk = Bulk { if (n++ == 0) throw IOException("error body") else emptyList() } // the first tile fails, the second answers
        val cells = Cells()
        val r = pack(cells, bulk).seedArea("a", strip, bulk = true)
        assertEquals(2, bulk.boxes.size)
        val firstTile = cellsIn(bulk.boxes[0]).toSet(); val secondTile = cellsIn(bulk.boxes[1]).toSet()
        assertEquals(firstTile, cells.asked.toSet()); assertTrue(secondTile.none { it in cells.asked })
        assertEquals(nCells(strip), r.cells); assertEquals(0, r.failed)
        assertTrue(logs.any { it.startsWith("seed bulk boxes=1 ") && it.contains("failedBoxes=1") })
    }

    @Test fun failedCellsCountAndTheRetryRecoversThemThroughBulk() = run {
        val bulk = Bulk { throw IOException("down") }
        val cells = Cells(fail = true)
        val p = pack(cells, bulk)
        val r = p.seedArea("a", smallBox, bulk = true)
        assertEquals(nCells(smallBox), r.failed); assertEquals(r.cells, r.retryCells)
        assertEquals("PARTIAL", db.dao().areas().single().status)
        val fixed = Bulk() // "Retry failed cells" = the same seed again: nothing is stored for a failed cell, so bulk takes them
        val r2 = pack(Cells(), fixed).seedArea("a", smallBox, bulk = true)
        assertEquals(0, r2.failed); assertEquals(1, fixed.boxes.size)
        assertEquals("DONE", db.dao().areas().single().status)
    }

    @Test fun offlineOnlyFromBulkIsNotAFailureOfTheBoxButCellsFallBack() = run {
        val bulk = Bulk { throw PlaceOfflineOnly() }
        val cells = Cells()
        pack(cells, bulk).seedArea("a", smallBox, bulk = true)
        assertEquals(nCells(smallBox), cells.asked.toSet().size)
    }

    @Test fun cancelStopsBetweenBoxesAndResumeSkipsTheFinishedOnes() = run {
        var allow = true
        val bulk = Bulk { b -> allow = false; emptyList() } // after the first box the admission says stop
        val p = pack(Cells(), bulk)
        val e = assertThrows(SeedStopped::class.java) { run { p.seedArea("a", strip, admit = { if (allow) null else "stop" }, bulk = true) } }
        assertEquals("stop", e.reason)
        assertEquals("PARTIAL", db.dao().areas().single().status)
        val stored = db.dao().cells().size
        assertTrue(stored in 1 until nCells(strip))
        allow = true
        val bulk2 = Bulk()
        val r = pack(Cells(), bulk2).seedArea("a", strip, bulk = true)
        assertEquals(stored, r.skipped) // the finished box is not asked again
        assertEquals(nCells(strip), r.cells); assertEquals(0, r.failed)
        assertEquals(1, bulk2.boxes.size)
    }

    @Test fun progressIsMonotonicAndEndsAtTheTotal() = run {
        val seen = mutableListOf<Pair<Int, Int>>(); val counts = mutableListOf<SeedCounts>()
        pack(Cells(), Bulk()).seedArea("a", strip, onProgress = { d, n -> seen += d to n }, onCounts = { counts += it }, bulk = true)
        assertTrue(seen.zipWithNext().all { (a, b) -> a.first <= b.first })
        assertEquals(nCells(strip) to nCells(strip), seen.last())
        assertTrue(counts.all { it.done <= it.total && it.failed == 0 })
        assertEquals(nCells(strip), counts.last().done)
    }

    @Test fun theBulkCapIs30000AndThePerCellCapStays4000() = run {
        val big = GeoBox(10.0, 70.0, 10.0 + 70 * Geohash5.LAT_STEP, 70.0 + 80 * Geohash5.LON_STEP) // 71 x 81 = 5,751 cells
        assertTrue(nCells(big) in 4_001..30_000)
        val cells = Cells(); val bulk = Bulk()
        val refused = pack(cells, bulk).seedArea("big", big) // per cell: over 4,000
        assertEquals(0, refused.fetched); assertTrue(cells.asked.isEmpty()); assertTrue(bulk.boxes.isEmpty())
        val ok = pack(cells, bulk).seedArea("big", big, bulk = true)
        assertEquals(nCells(big), ok.cells); assertEquals(0, ok.failed); assertTrue(bulk.boxes.isNotEmpty())
        val huge = GeoBox(10.0, 70.0, 10.0 + 200 * Geohash5.LAT_STEP, 70.0 + 160 * Geohash5.LON_STEP) // over 30,000
        assertTrue(nCells(huge) > 30_000)
        val bulk2 = Bulk(); pack(cells, bulk2).seedArea("huge", huge, bulk = true); assertTrue(bulk2.boxes.isEmpty())
        assertEquals(30_000, PlacePack.MAX_SEED_CELLS_BULK); assertEquals(4_000, PlacePack.MAX_SEED_CELLS)
    }

    @Test fun placesStoredByBulkAreEvictableOnlyWhenNotSeededAndAreSeeded() = run {
        val inside = place("wd:Q1", smallBox.south + 0.01, smallBox.west + 0.01)
        val p = pack(Cells(), Bulk { listOf(inside) })
        p.seedArea("a", smallBox, bulk = true)
        assertFalse(db.dao().evictable(10, emptyList()).any { it.id == "wd:Q1" })
    }
}
