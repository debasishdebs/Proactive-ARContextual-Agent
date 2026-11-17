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

private const val D = PlacePack.DAY_MS
private const val LAT = 7.957; private const val LON = 80.759 // Sigiriya

private fun fetched(id: String, lat: Double = LAT, lon: Double = LON, parent: String? = null, name: String = id, kind: String = PlaceKinds.ARCHAEOLOGICAL_SITE, summary: String = "Rock fortress.") =
    FetchedPlace(id, parent, name, kind, lat, lon, 100, summary, "wikipedia:en", "page:1")

private class Src(var answer: (String) -> CellFetch = { CellFetch(emptyList()) }) : CellSource {
    override suspend fun fetchCell(cell: String) = answer(cell)
}

/** Place Guide v2: user pins in the pack, the budget, pinned and seeded places at budget pressure, clear fetched. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PlacePackUserTest {
    private lateinit var db: PlacesDatabase
    private var t = 100 * D
    private val logs = mutableListOf<String>()
    private val cell = Geohash5.encode(LAT, LON)

    @Before fun open() { db = PlacesDatabase.inMemory(ApplicationProvider.getApplicationContext()) }
    @After fun close() = db.close()
    private fun pack(src: CellSource? = null, budget: Long = PlacePack.DEFAULT_BUDGET_BYTES) = PlacePack(db, src, budget, { t }, { logs += it })
    private fun <T> run(b: suspend () -> T): T = runBlocking { b() }
    private fun pin(name: String, lat: Double = LAT, lon: Double = LON, parent: String? = null, note: String = "My note.") =
        UserPlace(UserPlace.newId(), name, PlaceKinds.VIEWPOINT, lat, lon, 60, parent, note)
    private fun metresNorth(m: Double) = LAT + m / 111_320.0

    @Test fun syncingPinsInsertsPinnedUserRowsKeepsRecencyOnEditAndDeletesTheRemoved() = run {
        val p = pack()
        val a = pin("Mirror Wall"); val b = pin("Lion's Paw")
        p.replaceUserPlaces(listOf(a, b, a.copy(id = "wd:bad")))
        val rows = db.dao().userPlaces().associateBy { it.id }
        assertEquals(setOf(a.id, b.id), rows.keys)
        assertTrue(rows.values.all { it.pinned && !it.seeded && it.source == "user" })
        db.dao().touch(listOf(a.id), 321)
        p.replaceUserPlaces(listOf(a.copy(name = "Mirror Wall (edited)", note = "New note.")))
        val after = db.dao().userPlaces().single()
        assertEquals(a.id, after.id); assertEquals("Mirror Wall (edited)", after.name); assertEquals("New note.", after.summary); assertEquals(321, after.lastUsedDay)
        p.replaceUserPlaces(emptyList()); assertTrue(db.dao().userPlaces().isEmpty())
        assertTrue(logs.any { it.startsWith("user places synced count=") } && logs.none { it.contains("Mirror") || it.contains("Lion") })
    }

    @Test fun aFetchCanNeverWriteOrOverwriteAUserPlace() = run {
        val mine = pin("Mine", note = "Only mine.")
        val p = pack(Src { CellFetch(listOf(fetched("wd:Q1"), fetched(mine.id, name = "Hijacked", summary = "From the network."))) })
        p.replaceUserPlaces(listOf(mine))
        assertEquals(CellOutcome.FETCHED, p.ensureCell(cell, true))
        val row = db.dao().byIds(listOf(mine.id)).single()
        assertEquals("Mine", row.name); assertEquals("Only mine.", row.summary); assertEquals("user", row.source); assertTrue(row.pinned)
        assertEquals(1, db.dao().byIds(listOf("wd:Q1")).size)
    }

    @Test fun aUserPlaceCannotBeUnpinnedByTheGenericPinCall() = run {
        val mine = pin("Mine"); val p = pack(); p.replaceUserPlaces(listOf(mine))
        assertFalse(p.pin(mine.id, false)); assertTrue(db.dao().byIds(listOf(mine.id)).single().pinned)
    }

    @Test fun userPinsAreCandidatesAndTheirSiteExpandsToThePinnedSubPlaces() = run {
        val site = fetched("wd:Q1", name = "Sigiriya")
        val p = pack(Src { CellFetch(listOf(site)) })
        val near = pin("Lion's Paw", metresNorth(120.0), LON, parent = "wd:Q1")
        val farChild = pin("Mirror Wall", metresNorth(900.0), LON, parent = "wd:Q1", note = "Mirror Wall note.") // beyond the 300 m reach, found through its site
        val alone = pin("Stall", metresNorth(250.0), LON, parent = null)
        p.replaceUserPlaces(listOf(near, farChild, alone))
        val c = p.candidates(LAT, LON, networkUp = true)
        val ids = c.places.map { it.id }
        assertTrue(site.id in ids); assertTrue(near.id in ids); assertTrue("a pin 250 m away is a candidate", alone.id in ids)
        assertTrue("the site's pinned sub-place is added although far", farChild.id in ids)
        assertEquals("Mirror Wall note.", c.places.first { it.id == farChild.id }.summary)
    }

    @Test fun aPinKeepsItsSlotWhenMoreThanEightPackPlacesAreNearer() {
        val fetchedNear = (1..10).map { PlaceEntity("wd:Q$it", null, "P$it", PlaceKinds.MONUMENT, metresNorth(it * 5.0), LON, 50, "s", "wikipedia:en", "x", 1, false, false, 0, 10) }
        val mine = pin("Mine", metresNorth(240.0), LON).toEntity()
        val hits = Geo.candidates(fetchedNear + mine, LAT, LON)
        assertEquals(Geo.MAX_CANDIDATES, hits.size)
        assertTrue("the user pin is in the list", hits.any { it.place.id == mine.id })
        val none = Geo.candidates(fetchedNear, LAT, LON)
        assertEquals(Geo.MAX_CANDIDATES, none.size); assertEquals(hits.size, none.size)
    }

    @Test fun budgetPressureEvictsOnlyFetchedLeastRecentlyUsedNeverSeededPinnedOrUserPlaces() = run {
        val p = pack()
        val rows = listOf(
            PlaceEntity("wd:fetchedOld", null, "Old", PlaceKinds.MONUMENT, LAT, LON, 50, "s", "wikipedia:en", "x", 1, false, false, 1, 0),
            PlaceEntity("wd:fetchedNew", null, "New", PlaceKinds.MONUMENT, LAT, LON, 50, "s", "wikipedia:en", "x", 1, false, false, 9, 0),
            PlaceEntity("wd:seeded", null, "Seeded", PlaceKinds.MONUMENT, LAT, LON, 50, "s", "wikipedia:en", "x", 1, true, false, 0, 0),
            PlaceEntity("wd:pinned", null, "Pinned", PlaceKinds.MONUMENT, LAT, LON, 50, "s", "wikipedia:en", "x", 1, false, true, 0, 0),
        ).map { it.copy(bytes = PlacePack.sizeOf(it)) }
        db.dao().putPlaces(rows)
        val mine = pin("Mine"); p.replaceUserPlaces(listOf(mine))
        val before = p.stats()
        assertEquals(PackStats(pinned = 2, seeded = 1, fetched = 2, bytes = before.bytes, budgetBytes = PlacePack.DEFAULT_BUDGET_BYTES), before)
        // a budget below everything: every fetched place goes, oldest first, and the rest stays although the pack is still over budget
        assertEquals(2, p.setBudget(1))
        assertEquals(setOf("wd:seeded", "wd:pinned", mine.id), db.dao().inBox(-90.0, 90.0, -180.0, 180.0).map { it.id }.toSet())
        assertEquals(PackStats(2, 1, 0, p.stats().bytes, 1), p.stats())
        assertEquals("nothing more can go", 0, p.setBudget(1))
    }

    @Test fun lowerBudgetEvictsLeastRecentlyUsedFirstAndStopsAtTheBudget() = run {
        val p = pack()
        val rows = (1..4).map { PlaceEntity("wd:F$it", null, "F$it", PlaceKinds.MONUMENT, LAT, LON, 50, "s", "wikipedia:en", "x", 1, false, false, it, 0).let { r -> r.copy(bytes = 1000) } }
        db.dao().putPlaces(rows)
        assertEquals(2, p.setBudget(2_000)) // 4000 bytes -> 2000: day 1 and 2 go
        assertEquals(setOf("wd:F3", "wd:F4"), db.dao().inBox(-90.0, 90.0, -180.0, 180.0).map { it.id }.toSet())
        assertEquals(2_000L, p.budgetBytes)
    }

    @Test fun clearFetchedKeepsSeededPinnedAndUserPlacesAndRecency() = run {
        val p = pack(Src { CellFetch(listOf(fetched("wd:Q9"))) })
        p.ensureCell(cell, true)
        val seeded = PlaceEntity("wd:S", null, "S", PlaceKinds.MONUMENT, 50.0, 10.0, 50, "s", "wikipedia:en", "x", 1, true, false, 5, 10)
        db.dao().putPlaces(listOf(seeded)); val mine = pin("Mine"); p.replaceUserPlaces(listOf(mine)); p.pin("wd:Q9", false)
        db.dao().touch(listOf("wd:Q9", mine.id), 77)
        assertEquals(1, p.clearFetched())
        val left = db.dao().inBox(-90.0, 90.0, -180.0, 180.0).associateBy { it.id }
        assertEquals(setOf("wd:S", mine.id), left.keys)
        assertEquals("recency is kept (only Delete all resets it)", 77, left.getValue(mine.id).lastUsedDay); assertEquals(5, left.getValue("wd:S").lastUsedDay)
        assertNull("the cell is fetched again", db.dao().cell(cell))
        assertEquals(PackStats(1, 1, 0, p.stats().bytes, p.budgetBytes), p.stats())
    }

    @Test fun deleteAllKeepsUserPinsAndPinnedPlaces() = run {
        val p = pack(Src { CellFetch(listOf(fetched("wd:Q9"))) }); p.ensureCell(cell, true)
        val mine = pin("Mine"); p.replaceUserPlaces(listOf(mine))
        p.deleteAll()
        assertEquals(listOf(mine.id), db.dao().inBox(-90.0, 90.0, -180.0, 180.0).map { it.id })
        assertNotNull(db.dao().byIds(listOf(mine.id)).single())
    }

    @Test fun sitesNearListsPackPlacesNearestFirstAndNeverPins() = run {
        val p = pack(Src { CellFetch(listOf(fetched("wd:Q1", name = "Far", lat = metresNorth(600.0)), fetched("wd:Q2", name = "Close", lat = metresNorth(30.0)))) })
        p.ensureCell(cell, true); p.replaceUserPlaces(listOf(pin("Mine")))
        assertEquals(listOf("wd:Q2", "wd:Q1"), p.sitesNear(LAT, LON).map { it.id })
        assertTrue(p.sitesNear(LAT + 1.0, LON).isEmpty())
    }

    @Test fun theBudgetChoicesAreTheOnesTheScreenOffers() {
        assertEquals(listOf(25, 50, 100, 200), PlacePack.BUDGET_CHOICES_MB)
        assertEquals(50L * 1024 * 1024, PlacePack.DEFAULT_BUDGET_BYTES)
    }
}
