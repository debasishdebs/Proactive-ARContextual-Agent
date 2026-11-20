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

    // ---- the Geocoder is a remote call: gated by Model privacy and the data saver ----
    private class Gate(var allowed: Boolean, val reason: String = "roaming") : com.rokid.context.core.net.DataGate {
        var seenKind: com.rokid.context.core.net.DataKind? = null; var seenReach: com.rokid.context.core.net.EndpointReach? = null; var skips = 0
        override fun decide(kind: com.rokid.context.core.net.DataKind, reach: com.rokid.context.core.net.EndpointReach): com.rokid.context.core.net.DataDecision {
            seenKind = kind; seenReach = reach
            return if (allowed) com.rokid.context.core.net.DataDecision.ALLOW else com.rokid.context.core.net.DataDecision(false, reason)
        }
        override fun skipped(kind: com.rokid.context.core.net.DataKind, what: com.rokid.context.core.net.Skipped, d: com.rokid.context.core.net.DataDecision) { skips++ }
        override fun costlyNow() = !allowed
    }
    private val real: suspend (Double, Double) -> Place? = { _, _ -> geocodes++; Place("Indiranagar", "Bengaluru", "Karnataka") }

    @Test fun underOfflineOnlyTheGeocoderIsNeverCalledAndThereIsNoPlaceName() = runBlocking {
        val g = Gate(allowed = true)
        val s = LocationSource({ Fix(1.0, 2.0, now) }, LocationSource.gated(real, { com.rokid.context.core.net.ModelPrivacy.OFFLINE_ONLY }, { g }), Dispatchers.Unconfined)
        assertNull(s.placeLabel(now)); assertEquals(0, geocodes)
        assertEquals("not even asked", null, g.seenKind)
    }

    @Test fun aThrowingPrivacyReaderFailsClosed() = runBlocking {
        val s = LocationSource({ Fix(1.0, 2.0, now) }, LocationSource.gated(real, { throw IllegalStateException("prefs") }, { Gate(true) }), Dispatchers.Unconfined)
        assertNull(s.placeLabel(now)); assertEquals(0, geocodes)
    }

    @Test fun whenTheDataSaverRefusesASmallRemoteCallThereIsNoLookup() = runBlocking {
        val g = Gate(allowed = false, reason = "roaming")
        val s = LocationSource({ Fix(1.0, 2.0, now) }, LocationSource.gated(real, { com.rokid.context.core.net.ModelPrivacy.AUTO }, { g }), Dispatchers.Unconfined)
        assertNull(s.placeLabel(now)); assertEquals(0, geocodes)
        assertEquals(com.rokid.context.core.net.DataKind.SMALL, g.seenKind); assertEquals(com.rokid.context.core.net.EndpointReach.REMOTE, g.seenReach); assertEquals(1, g.skips)
    }

    @Test fun aMissingOrThrowingGateFailsClosedAndAnAllowingOneLooksUp() = runBlocking {
        val none = LocationSource({ Fix(1.0, 2.0, now) }, LocationSource.gated(real, { com.rokid.context.core.net.ModelPrivacy.AUTO }, { null }), Dispatchers.Unconfined)
        assertNull(none.placeLabel(now)); assertEquals(0, geocodes)
        val ok = LocationSource({ Fix(1.0, 2.0, now) }, LocationSource.gated(real, { com.rokid.context.core.net.ModelPrivacy.AUTO }, { Gate(true) }), Dispatchers.Unconfined)
        assertEquals("Indiranagar, Bengaluru", ok.placeLabel(now)); assertEquals(1, geocodes)
    }

    @Test fun geocoderFailureGivesNoPlace() = runBlocking {
        val s = LocationSource({ Fix(1.0, 2.0, now) }, { _, _ -> throw java.io.IOException("offline") }, Dispatchers.Unconfined)
        assertNull(s.placeLabel(now))
    }
}
