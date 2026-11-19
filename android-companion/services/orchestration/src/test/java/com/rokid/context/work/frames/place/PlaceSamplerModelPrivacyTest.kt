package com.rokid.context.work.frames.place

import com.rokid.context.ai.contract.PlaceMatchOutput
import com.rokid.context.core.net.Backend
import com.rokid.context.core.net.ModelPrivacy
import com.rokid.context.core.net.ResolveReason
import com.rokid.context.memory.place.CellOutcome
import com.rokid.context.memory.place.PlaceCandidates
import com.rokid.context.memory.place.PlaceEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Slice 1.4.3 in Place Guide: the resolver's pick decides "online", the per-backend floor decides "low confidence", and Offline only never fetches or calls. */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaceSamplerModelPrivacyTest {
    private val t0 = 10_000_000L
    private val lat = 17.361944
    private val lon = 78.474722
    private var choice = BackendChoice.ONLINE
    private var networkUp = true
    private var reply = PlaceMatchResult(PlaceMatchOutput("1", 0.86f), true)
    private val candidateOnline = mutableListOf<Boolean>()
    private val matchCalls = mutableListOf<String>()
    private val cards = mutableListOf<PlaceCard>()
    private val outcomes = mutableListOf<PlaceOutcome>()
    private val logs = mutableListOf<String>()
    private var backendAsked = 0

    private val place = PlaceEntity("wd:Q1", null, "Charminar", "monument", lat, lon, 50, "Charminar is a monument in Hyderabad.", "wikipedia:en", "page:1", 1_700_000_000_000L, false, false, 0, 100)
    private val store = object : PlaceFrameStore {
        override suspend fun newestDwell(sinceReceivedMs: Long) = PlaceFrame("f1", t0 - 5_000)
        override suspend fun tombstoned(frameId: String) = false
    }

    private fun sampler(scope: TestScope, useResolverSeam: Boolean = true) = PlaceSampler(
        scope = scope, store = store, fix = { PlaceFix(lat, lon, 3_000, 10f) },
        candidates = { _, _, up -> candidateOnline += up; PlaceCandidates(listOf(place), CellOutcome.FRESH) },
        match = { id, _ -> matchCalls += id; reply },
        saveMemory = { true }, onCard = { cards += it }, clock = { t0 }, enabled = { true }, zoneBlocked = { false }, networkUp = { networkUp },
        backend = if (useResolverSeam) ({ backendAsked++; choice }) else ({ if (networkUp) BackendChoice.ONLINE else BackendChoice.NO_NETWORK }),
        log = { logs += it }, stats = { outcomes += it }, flight = PlaceFlight(),
    )

    private fun TestScope.poke(s: PlaceSampler = sampler(this)) { s.poke(); runCurrent() }

    private val offlineOnlyNoModel = BackendChoice(null, ResolveReason.OFFLINE_ONLY_NO_LOCAL_MODEL, ModelPrivacy.OFFLINE_ONLY)

    @Test fun offlineOnlyWithoutAnOnDeviceModelNeverFetchesNorCallsEvenWithAGoodNetwork() = runTest {
        choice = offlineOnlyNoModel; networkUp = true
        poke()
        assertEquals("the pack is told it is offline: no cell fetch", listOf(false), candidateOnline)
        assertTrue("no model call, so no photo and no name left the phone", matchCalls.isEmpty())
        assertEquals(listOf(PlaceOutcome.NEARBY), outcomes) // the pack-only name-and-distance card, as when offline
        assertEquals(1, cards.size)
        val line = logs.last { it.startsWith("place attempt") }
        assertTrue(line, line.contains("call=skipped_offline_only online=false"))
        assertTrue(line, line.contains("mode=OFFLINE_ONLY backend=none resolve=offline_only_no_local_model label=- thrV=1"))
    }

    @Test fun offlineOnlyWithNoPlaceNearbyIsSilentAndStillMakesNoCall() = runTest {
        choice = offlineOnlyNoModel
        val far = place.copy(lat = lat + 0.01)
        val s = PlaceSampler(
            scope = this, store = store, fix = { PlaceFix(lat, lon, 3_000, 10f) }, candidates = { _, _, up -> candidateOnline += up; PlaceCandidates(listOf(far), CellOutcome.FRESH) },
            match = { id, _ -> matchCalls += id; reply }, saveMemory = { true }, onCard = { cards += it }, clock = { t0 }, enabled = { true }, zoneBlocked = { false },
            networkUp = { true }, backend = { choice }, stats = { outcomes += it }, flight = PlaceFlight(),
        )
        poke(s)
        assertTrue(matchCalls.isEmpty()); assertTrue(cards.isEmpty()); assertEquals(listOf(PlaceOutcome.NOTHING_NEARBY), outcomes)
    }

    @Test fun anOnlineBackendMakesTheCallAndLogsWhichBackendRanAndWhy() = runTest {
        choice = BackendChoice(Backend.ONLINE, ResolveReason.OK, ModelPrivacy.AUTO, "remote:AZURE_OPENAI")
        poke()
        assertEquals(listOf(true), candidateOnline); assertEquals(listOf("f1"), matchCalls)
        val line = logs.last { it.startsWith("place attempt") }
        assertTrue(line, line.contains("outcome=MATCHED"))
        assertTrue(line, line.contains("mode=AUTO backend=online resolve=ok label=remote:AZURE_OPENAI thrV=1"))
    }

    @Test fun autoWithNoNetworkIsTodaysOfflinePathAndSaysOffline() = runTest {
        choice = BackendChoice.NO_NETWORK
        poke()
        assertEquals(listOf(false), candidateOnline); assertTrue(matchCalls.isEmpty())
        val line = logs.last { it.startsWith("place attempt") }
        assertTrue(line, line.contains("call=skipped_offline online=false"))
        assertFalse(line, line.contains("offline_only"))
    }

    @Test fun anOfflineBackendIsNotWiredYetSoItMakesNoCallEither() = runTest {
        choice = BackendChoice(Backend.OFFLINE, ResolveReason.OK, ModelPrivacy.OFFLINE_ONLY, "device:X")
        poke()
        assertTrue(matchCalls.isEmpty()); assertEquals(listOf(false), candidateOnline)
    }

    @Test fun withoutAResolverTheDefaultIsTodaysNetworkUpRule() = runTest {
        val s = sampler(this, useResolverSeam = false)
        networkUp = true; poke(s)
        assertEquals(listOf(true), candidateOnline); assertEquals(1, matchCalls.size)
    }

    // ---- per-backend confidence floors ----

    @Test fun theOnlineFloorIsStillFiftyPercentToTheBoundary() = runTest {
        reply = PlaceMatchResult(PlaceMatchOutput("1", 0.49f), true)
        poke()
        assertEquals(listOf(PlaceOutcome.LOW_CONFIDENCE), outcomes)
        assertTrue(logs.last { it.startsWith("place attempt") }.contains("reason=confidence_below_50 "))
        assertTrue(cards.isEmpty())
    }

    @Test fun exactlyTheFloorPasses() = runTest {
        reply = PlaceMatchResult(PlaceMatchOutput("1", 0.50f), true)
        poke()
        assertEquals(listOf(PlaceOutcome.MATCHED), outcomes)
    }
}
