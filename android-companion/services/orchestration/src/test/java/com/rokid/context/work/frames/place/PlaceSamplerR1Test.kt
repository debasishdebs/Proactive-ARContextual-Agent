package com.rokid.context.work.frames.place

import com.rokid.context.ai.contract.PlaceMatchOutput
import com.rokid.context.memory.forget.ForgetListeners
import com.rokid.context.memory.place.CellOutcome
import com.rokid.context.memory.place.PlaceCandidates
import com.rokid.context.memory.place.PlaceEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val LAT1 = 17.361944
private const val LON1 = 78.474722
private val CHARMINAR = PlaceEntity("wd:Q1", null, "Charminar", "monument", LAT1, LON1, 50, "Charminar is a monument. It was built in 1591.", "wikipedia:en", "page:1", 1_700_000_000_000L, false, false, 0, 100)

/**
 * Codex Place Guide r1: a zone (or a Forget, or a wipe) that begins at ANY suspension point stops the work before its next side effect, and a card is never published, or is withdrawn, after one.
 * The fakes suspend and flip the state exactly where the real calls would.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaceSamplerR1Test {
    private val t0 = 10_000_000L
    private var now = t0
    private var zone = false
    private var tomb = false
    private var gen = 0L
    private var fixCalls = 0
    private var candidateCalls = 0
    private var matchCalls = 0
    private var saved = 0
    private val deleted = mutableListOf<String>()
    private val outcomes = mutableListOf<PlaceOutcome>()
    private val holder = PlaceCardHolder()
    private var onNewest: () -> Unit = {}
    private var onFix: () -> Unit = {}
    private var onCandidates: () -> Unit = {}
    private var onMatch: () -> Unit = {}
    private var onSave: () -> Unit = {}
    private var online = true
    private var reply: PlaceMatchResult? = PlaceMatchResult(PlaceMatchOutput("1", 0.9f), true)
    private var flight = PlaceFlight()
    private var frameId = "f1"
    private var blockedOverride: (() -> Boolean)? = null
    private val fence = com.rokid.context.memory.place.ZoneFence()

    private val store = object : PlaceFrameStore {
        override suspend fun newestDwell(sinceReceivedMs: Long): PlaceFrame { onNewest(); return PlaceFrame(frameId, now - 5_000) }
        override suspend fun tombstoned(frameId: String) = tomb
    }

    private fun sampler(scope: TestScope) = PlaceSampler(
        scope = scope, store = store, fix = { fixCalls++; onFix(); PlaceFix(LAT1, LON1, 1_000, 10f) },
        candidates = { _, _, _ -> candidateCalls++; onCandidates(); PlaceCandidates(listOf(CHARMINAR), CellOutcome.FRESH) },
        match = { _, _ -> matchCalls++; onMatch(); reply },
        saveMemory = { saved++; onSave(); true }, onCard = { holder.publish(it) }, clock = { now }, enabled = { true },
        zoneBlocked = { blockedOverride?.invoke() ?: zone }, networkUp = { online }, generation = { gen }, flight = flight, stats = { outcomes += it },
        cards = holder, deleteMemory = { deleted += it }, fence = fence,
    )

    private fun TestScope.poke(s: PlaceSampler = sampler(this)) { s.poke(); runCurrent() }

    @Test fun aZoneDuringTheFrameLookupStopsBeforeTheFix() = runTest {
        onNewest = { zone = true }; poke()
        assertEquals(listOf(PlaceOutcome.ZONE), outcomes); assertEquals(0, fixCalls); assertEquals(0, candidateCalls); assertEquals(0, matchCalls); assertNull(holder.card.value)
    }

    @Test fun aZoneDuringTheFixStopsBeforeTheFetchAndTheUpload() = runTest {
        onFix = { zone = true }; poke()
        assertEquals(listOf(PlaceOutcome.ZONE), outcomes); assertEquals(1, fixCalls); assertEquals(0, candidateCalls); assertEquals(0, matchCalls); assertNull(holder.card.value)
    }

    @Test fun aZoneDuringThePackFetchStopsBeforeTheUpload() = runTest {
        onCandidates = { zone = true }; poke()
        assertEquals(listOf(PlaceOutcome.ZONE), outcomes); assertEquals(1, candidateCalls); assertEquals(0, matchCalls); assertEquals(0, saved); assertNull(holder.card.value)
    }

    @Test fun aZoneDuringTheModelWaitAndNoDeliveryMakesNoFallbackCard() = runTest {
        onMatch = { zone = true }; reply = null; poke()
        assertEquals(listOf(PlaceOutcome.DROPPED), outcomes); assertEquals(0, saved); assertNull(holder.card.value)
    }

    @Test fun aZoneDuringTheMemoryCommitRollsTheMemoryBackAndPublishesNothing() = runTest {
        onSave = { zone = true }; poke()
        assertEquals(listOf(PlaceOutcome.DROPPED), outcomes); assertEquals(1, saved)
        assertEquals(listOf("f1"), deleted); assertNull(holder.card.value)
    }

    @Test fun aForgetDuringTheMemoryCommitFencesThePublication() = runTest {
        onSave = { holder.withdrawFrames(listOf("f1")) }; poke() // the Forget listener ran after its commit, before the card existed
        assertEquals(listOf(PlaceOutcome.DROPPED), outcomes); assertNull(holder.card.value)
    }

    @Test fun aZoneThatBeginsRightAfterPublicationWithdrawsTheCard() = runTest {
        // The zone controller holds the fence (PlaceGraph.protect) the moment a zone is approached: the card is withdrawn with it.
        val s = sampler(this); s.poke(); runCurrent()
        assertEquals(PlaceOutcome.MATCHED, outcomes.last()); assertTrue(holder.card.value != null)
        fence.hold(); holder.clear()
        assertNull(holder.card.value)
    }

    @Test fun theFallbackCardIsFencedToo() = runTest {
        online = false; onFix = { fence.hold() }
        poke()
        assertNull(holder.card.value); assertEquals(PlaceOutcome.ZONE, outcomes.last())
    }

    @Test fun theTwentySecondGapRunsFromTheEndOfTheCall() = runTest {
        val s = sampler(this)
        onMatch = { now += 30_000 } // a long permit wait: the call ends at t0 + 30 s
        poke(s)
        assertEquals(1, matchCalls)
        onMatch = {}; frameId = "f2"; now += 5_000 // 5 s after the call ended, 35 s after the attempt began
        flight.lastAttempt = PlaceFlight.Attempt(0.0, 0.0, 0L) // not the same place
        poke(s)
        assertEquals(PlaceOutcome.RATE_LIMITED, outcomes.last()); assertEquals(1, matchCalls)
    }

    @Test fun aSlowPackFetchDoesNotLetTheNextFrameCallImmediately() = runTest {
        val s = sampler(this)
        onCandidates = { now += 25_000 } // the fetch took 25 s: the call is dispatched at t0 + 25 s
        poke(s)
        assertEquals(1, matchCalls)
        onCandidates = {}; now += 5_000; frameId = "f2" // t0 + 30 s: only 5 s after the dispatch (30 s after the start of the first attempt)
        val far = PlaceFlight.Attempt(0.0, 0.0, 0L); flight.lastAttempt = far // not the same place
        poke(s)
        assertEquals(PlaceOutcome.RATE_LIMITED, outcomes.last()); assertEquals(1, matchCalls)
    }

    @Test fun aCardIsWithdrawnOnForgetOfItsPhotoAndOnDeleteAll() {
        val h = PlaceCardHolder()
        h.publish(PlaceCards.photo(CHARMINAR, "f1"))
        h.withdrawFrames(listOf("other")); assertTrue(h.card.value != null)
        h.withdrawFrames(listOf("f1")); assertNull(h.card.value)
        h.publish(PlaceCards.photo(CHARMINAR, "f2")); h.withdraw(null, "wd:Q1"); assertNull(h.card.value)
        h.publish(PlaceCards.photo(CHARMINAR, "f3")); h.clear(); assertNull(h.card.value)
    }

    @Test fun aPublicationThatStartedBeforeAWithdrawalNeverLandsAfterIt() = runTest {
        val h = PlaceCardHolder(); val e = h.epoch()
        h.clear()
        assertFalse(h.publishIf(PlaceCards.photo(CHARMINAR, "f1"), e)); assertNull(h.card.value)
        assertTrue(h.publishIf(PlaceCards.photo(CHARMINAR, "f1"), h.epoch()))
    }

    @Test fun theForgetAndDeleteAllListenersWithdrawThroughTheGraph() = runTest {
        PlaceGraph.port.publish(PlaceCards.photo(CHARMINAR, "f1"))
        ForgetListeners.framesForgotten(listOf("f1")); assertNull(PlaceGraph.port.card.value)
        PlaceGraph.port.publish(PlaceCards.photo(CHARMINAR, "f9"))
        ForgetListeners.deleteAll(); assertNull(PlaceGraph.port.card.value)
    }
}
