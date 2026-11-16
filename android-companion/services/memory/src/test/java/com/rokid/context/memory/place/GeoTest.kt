package com.rokid.context.memory.place

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoTest {
    private fun place(id: String, lat: Double, lon: Double, r: Int = 50, parent: String? = null, kind: String = PlaceKinds.MONUMENT) =
        PlaceEntity(id, parent, id, kind, lat, lon, r, "", "wikipedia:en", "", 0L, false, false, 0, 100)

    @Test fun geohash5MatchesTheReferenceVectors() {
        assertEquals("u4pru", Geohash5.encode(57.64911, 10.40744))
        assertEquals("ezs42", Geohash5.encode(42.605, -5.603))
        assertEquals("s0000", Geohash5.encode(0.0001, 0.0001))
    }

    @Test fun boundsContainThePointAndAreAboutFiveKm() {
        val c = Geohash5.encode(17.361944, 78.474722)
        val b = Geohash5.bounds(c)!!
        assertTrue(b.contains(17.361944, 78.474722))
        assertEquals(Geohash5.LAT_STEP, b.north - b.south, 1e-9); assertEquals(Geohash5.LON_STEP, b.east - b.west, 1e-9)
        assertTrue(Geo.distanceM(b.south, b.west, b.south, b.east) in 4_000.0..5_000.0)
        assertEquals(c, Geohash5.encode((b.south + b.north) / 2, (b.west + b.east) / 2))
        assertNull(Geohash5.bounds("abc")); assertNull(Geohash5.bounds("tepfa")) // 'a' is not in the alphabet
    }

    @Test fun cellsCoveringAZoneAreExactlyTheCellsItTouches() {
        val box = GeoBox(17.30, 78.40, 17.45, 78.55)
        val cells = Geohash5.cellsCovering(box)
        assertEquals(cells.size, cells.toSet().size)
        for (c in cells) assertTrue(Geohash5.bounds(c)!!.intersects(box))
        for (p in listOf(17.30 to 78.40, 17.45 to 78.55, 17.37 to 78.47, 17.30 to 78.55)) assertTrue(Geohash5.encode(p.first, p.second) in cells)
        assertEquals(emptyList<String>(), Geohash5.cellsCovering(GeoBox(1.0, 1.0, 1.0, 2.0)))
        assertTrue(cells.size in 16..64)
    }

    @Test fun distanceIsHaversine() {
        assertEquals(0.0, Geo.distanceM(10.0, 20.0, 10.0, 20.0), 1e-6)
        assertEquals(111_195.0, Geo.distanceM(0.0, 0.0, 1.0, 0.0), 200.0)
        assertEquals(8_166.0, Geo.distanceM(17.361944, 78.474722, 17.3833, 78.4011), 60.0) // Charminar to Golconda
    }

    @Test fun candidatesAreWithin300MetresOfTheEdgeNearestFirstAndAtMostEight() {
        val here = 17.0 to 78.0
        fun at(dM: Double) = here.first + dM / 111_320.0
        val places = listOf(
            place("far", at(900.0), 78.0, r = 50),            // gap 850: out
            place("edge", at(340.0), 78.0, r = 50),           // gap 290: in
            place("just-out", at(360.0), 78.0, r = 50),       // gap 310: out
            place("big", at(600.0), 78.0, r = 400),           // gap 200: in
            place("here", at(10.0), 78.0, r = 30),            // gap -20: first
        )
        assertEquals(listOf("here", "big", "edge"), Geo.candidates(places, here.first, here.second).map { it.place.id })
        val many = (1..12).map { place("p%02d".format(it), at(it * 10.0), 78.0) }
        val eight = Geo.candidates(many, here.first, here.second)
        assertEquals(8, eight.size); assertEquals((1..8).map { "p%02d".format(it) }, eight.map { it.place.id })
    }

    @Test fun onlyAllowlistedKindsAreEverCandidates() {
        val person = place("wd:Q5x", 17.0, 78.0, kind = "person")
        assertTrue(Geo.candidates(listOf(person), 17.0, 78.0).isEmpty())
        assertFalse("person" in PlaceKinds.ALL)
    }

    @Test fun aSiteBringsItsSubPlaces() {
        val site = place("site", 17.0, 78.0); val a = place("a", 17.5, 78.5, parent = "site"); val b = place("b", 17.6, 78.6, parent = "site"); val other = place("o", 17.7, 78.7, parent = "zzz")
        assertEquals(listOf("site", "a", "b"), Geo.expandSubPlaces(listOf(site), listOf(b, a, other, a)).map { it.id })
        assertEquals(listOf("site", "a"), Geo.expandSubPlaces(listOf(site, a), listOf(a, b.copy(parentId = "other"))).map { it.id })
    }

    @Test fun windowHoldsEveryPointWithinTheRadius() {
        val w = Geo.window(17.0, 78.0, 1_000.0)
        assertNotNull(w); assertTrue(w.contains(17.0 + 900 / 111_320.0, 78.0)); assertTrue(w.contains(17.0, 78.0 + 900 / 106_000.0))
        assertFalse(w.contains(17.0 + 1_100 / 111_320.0, 78.0))
    }
}
