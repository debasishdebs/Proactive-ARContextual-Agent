package com.rokid.context.connectors.sources

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocationSourceTest {
    private val now = 10_000_000_000L
    private var geocodes = 0

    private fun source(fixAt: Long) = LocationSource(
        lastFix = { Fix(12.97, 77.59, fixAt) },
        geocode = { _, _ -> geocodes++; Place(subLocality = "Indiranagar", locality = "Bengaluru", adminArea = "Karnataka") },
        io = Dispatchers.Unconfined,
    )

    @Test fun staleFixGivesNoPlace() = runBlocking {
        assertNull(source(now - 31 * 60_000L).placeLabel(now))
        assertEquals(0, geocodes)
    }

    @Test fun freshFixIsLabelledAndCachedTenMinutes() = runBlocking {
        val s = source(now - 5 * 60_000L)
        assertEquals("Indiranagar, Bengaluru", s.placeLabel(now))
        assertEquals(listOf("place \u00b7 Indiranagar, Bengaluru"), s.lines(null, now + 9 * 60_000L))
        assertEquals(1, geocodes)
        s.placeLabel(now + 11 * 60_000L)
        assertEquals(2, geocodes)
    }

    @Test fun localityFallsBackToAdminArea() = runBlocking {
        val s = LocationSource({ Fix(1.0, 2.0, now) }, { _, _ -> Place(null, "Pune", "Maharashtra") }, Dispatchers.Unconfined)
        assertEquals("Pune, Maharashtra", s.placeLabel(now))
    }

    @Test fun geocoderFailureGivesNoPlace() = runBlocking {
        val s = LocationSource({ Fix(1.0, 2.0, now) }, { _, _ -> throw java.io.IOException("offline") }, Dispatchers.Unconfined)
        assertNull(s.placeLabel(now))
    }
}
