package com.rokid.context.work.frames.place

import com.rokid.context.ai.contract.PlaceMatchOutput
import com.rokid.context.memory.ImageTextEmbedder
import com.rokid.context.memory.place.CellOutcome
import com.rokid.context.memory.place.PlaceCandidates
import com.rokid.context.memory.place.PlaceEntity
import com.rokid.context.memory.retrieval.CandidateScorer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private fun pl(id: String, name: String) = PlaceEntity(id, null, name, "monument", 17.36, 78.47, 50, "s", "wikipedia:en", "p:1", 1L, false, false, 0, 100)

/** Slice A shadow mode: the line gains content-free fields; nothing else ever changes. Time is virtual: the model call takes [callMs], and the shadow runs on a scope DETACHED from the test scope, like production. */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaceSamplerShadowTest {
    private val T0 = 10_000_000L
    private val logs = mutableListOf<String>()
    private val cards = mutableListOf<PlaceCard>()
    private val outcomes = mutableListOf<PlaceOutcome>()
    private var reply: PlaceMatchResult? = PlaceMatchResult(PlaceMatchOutput("1", 0.9f), true)
    private var callMs = 100L
    private var flag = true
    private var net = true
    private var hookCalls = 0
    private var places = listOf(pl("wd:Q1", "Charminar"), pl("wd:Q2", "Mecca Masjid"))
    private var dwellFrame = "f1"
    private var fixLat = 17.36
    private var flight = PlaceFlight()
    private var detached: CoroutineScope? = null
    private var attemptEnds = 0
    private var matchImpl: suspend (String) -> PlaceMatchResult? = { delay(callMs); reply }

    private fun scored(top: Int, other: Int): ShadowOutcome {
        val q = floatArrayOf(1f, 0f)
        fun v(milli: Int) = floatArrayOf(milli / 1000f, Math.sqrt(1.0 - (milli / 1000.0) * (milli / 1000.0)).toFloat())
        return ShadowOutcome.scored(CandidateScorer.score(q, listOf(CandidateScorer.Candidate("wd:Q1", textAnchor = v(top)), CandidateScorer.Candidate("wd:Q2", textAnchor = v(other)))), 42L)
    }

    private fun sampler(scope: TestScope, hook: (suspend (String, List<Pair<PlaceEntity, Double>>, () -> Boolean) -> ShadowOutcome)?): PlaceSampler {
        val d = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scope.testScheduler)).also { detached = it }
        return PlaceSampler(
            scope = scope, store = object : PlaceFrameStore {
                override suspend fun newestDwell(sinceReceivedMs: Long) = PlaceFrame(dwellFrame, T0 - 5_000)
                override suspend fun tombstoned(frameId: String) = false
            },
            fix = { PlaceFix(fixLat, 78.47, 3_000, 10f) }, candidates = { _, _, _ -> PlaceCandidates(places, CellOutcome.FRESH) },
            match = { id, _ -> matchImpl(id) }, saveMemory = { true }, onCard = { cards += it }, clock = { T0 }, enabled = { true }, zoneBlocked = { false }, networkUp = { net },
            log = { logs += it }, stats = { outcomes += it }, elapsed = { scope.testScheduler.currentTime }, flight = flight,
            shadow = hook?.let { h -> { id, hits, live -> hookCalls++; h(id, hits, live) } }, shadowEnabled = { flag }, shadowScope = d, onAttemptEnd = { attemptEnds++ },
        )
    }

    private fun TestScope.run(s: PlaceSampler) { s.poke(); advanceUntilIdle() }
    private fun attempt() = logs.single { it.startsWith("place attempt") }
    private fun noTotal(l: String) = l.replace(Regex("totalMs=[0-9]+"), "")

    @Test fun flagOffWithALongCallLaunchesNothingAndTheLineIsByteIdentical() = runTest {
        callMs = 3_000
        run(sampler(this, null)); val baseline = attempt(); logs.clear(); outcomes.clear(); flight = PlaceFlight()
        flag = false
        run(sampler(this) { _, _, _ -> scored(800, 500) })
        assertEquals(0, hookCalls); assertEquals(noTotal(baseline), noTotal(attempt()))
        assertFalse(attempt().contains("shadow")); assertEquals(PlaceOutcome.MATCHED, outcomes.last())
    }

    @Test fun flagOffNeverReachesTheReferenceVectorSeamWithARealShadow() = runTest {
        var refAsks = 0; var embeds = 0
        val e = object : ImageTextEmbedder {
            override val spaceId = "x"; override val dim = 2
            override suspend fun embedImage(bytes: ByteArray): FloatArray { embeds++; return floatArrayOf(1f, 0f) }
            override suspend fun embedText(text: String): FloatArray { embeds++; return floatArrayOf(1f, 0f) }
        }
        val real = PlaceShadow(embedder = { e }, photo = { ByteArray(1) }, imageRefs = { _, _, _ -> refAsks++; emptyMap() }, elapsed = { 0L })
        flag = false
        run(sampler(this) { id, hits, live -> real.score(id, hits, live) })
        assertEquals(0, hookCalls); assertEquals(0, refAsks); assertEquals(0, embeds); assertFalse(attempt().contains("shadow"))
    }

    @Test fun turningTheFlagOffAfterLaunchNeverAcquiresTheEmbedderNorRunsInference() = runTest {
        var acquisitions = 0; var embeds = 0; var refAsks = 0
        val e = object : ImageTextEmbedder {
            override val spaceId = "x"; override val dim = 2
            override suspend fun embedImage(bytes: ByteArray): FloatArray { embeds++; return floatArrayOf(1f, 0f) }
            override suspend fun embedText(text: String): FloatArray { embeds++; return floatArrayOf(1f, 0f) }
        }
        val real = PlaceShadow(embedder = { acquisitions++; e }, photo = { ByteArray(1) }, imageRefs = { _, _, _ -> refAsks++; emptyMap() }, elapsed = { 0L })
        // the flag is ON at launch (the shadow is queued on its own dispatcher); it goes OFF during the model call, BEFORE the queued work gets to run
        matchImpl = { flag = false; delay(callMs); reply }
        run(sampler(this) { id, hits, live -> real.score(id, hits, live) })
        assertEquals(1, hookCalls)
        assertEquals("embedder acquired", 0, acquisitions); assertEquals("inference ran", 0, embeds); assertEquals(0, refAsks)
        assertFalse(attempt(), attempt().contains("shadow")); assertEquals(PlaceOutcome.MATCHED, outcomes.last()); assertEquals(1, cards.size)
    }

    @Test fun aFlagTurnedOffMidAttemptLogsNoShadowFieldEvenFromAHookThatScores() = runTest {
        callMs = 3_000
        matchImpl = { delay(100); flag = false; delay(callMs); reply }
        run(sampler(this) { _, _, _ -> scored(800, 500) })
        assertFalse(attempt(), attempt().contains("shadow")); assertEquals(PlaceOutcome.MATCHED, outcomes.last())
    }

    @Test fun theShadowChecksLivenessBeforeTheEmbedderAndBeforeInference() = runTest {
        var acquisitions = 0; var embeds = 0
        val e = object : ImageTextEmbedder {
            override val spaceId = "x"; override val dim = 2
            override suspend fun embedImage(bytes: ByteArray): FloatArray { embeds++; return floatArrayOf(1f, 0f) }
            override suspend fun embedText(text: String): FloatArray { embeds++; return floatArrayOf(1f, 0f) }
        }
        val real = PlaceShadow(embedder = { acquisitions++; e }, photo = { ByteArray(1) }, elapsed = { 0L })
        assertEquals(ShadowOutcome.Kind.DROPPED, real.score("f", listOf(pl("wd:Q1", "A") to 1.0)) { false }.kind)
        assertEquals(0, acquisitions); assertEquals(0, embeds)
        var n = 0 // alive before the embedder, dead after the photo and before the image is embedded
        assertEquals(ShadowOutcome.Kind.DROPPED, real.score("f", listOf(pl("wd:Q1", "A") to 1.0)) { n++ < 1 }.kind)
        assertEquals(0, embeds)
    }

    @Test fun flagOnWithALongCallAndAFastScoreIsOkNotErr() = runTest {
        callMs = 3_000
        run(sampler(this) { _, _, _ -> delay(400); scored(800, 500) })
        val l = attempt()
        assertTrue(l, l.contains(" shadow=ok shCh=text scoreTop=lt900 margin=ge100 chosenRank=1 agrees=true embedMs=42"))
        assertEquals(PlaceOutcome.MATCHED, outcomes.last()); assertEquals(1, cards.size)
    }

    @Test fun aHungShadowNeverDelaysTheCardNorTheCallTiming() = runTest {
        callMs = 1_000
        val never = CompletableDeferred<ShadowOutcome>()
        val s = sampler(this) { _, _, _ -> never.await() }
        val start = testScheduler.currentTime
        s.poke(); advanceTimeBy(1_001)
        assertEquals("the card is out at the end of the call", 1, cards.size)
        assertEquals(start + 1_000, flight.lastCallAt)
        assertTrue("the line waits for the rest of the budget, not the card", logs.none { it.startsWith("place attempt") })
        advanceUntilIdle()
        val l = attempt()
        assertTrue(l, l.contains("callMs=1000") && l.contains(" shadow=err") && !l.contains("scoreTop"))
        assertEquals(PlaceOutcome.MATCHED, outcomes.last())
    }

    @Test fun aSlowCallLeavesNoWaitForAHungShadow() = runTest {
        callMs = 3_000
        val never = CompletableDeferred<ShadowOutcome>()
        val s = sampler(this) { _, _, _ -> never.await() }
        s.poke(); advanceTimeBy(3_001)
        assertEquals(1, cards.size); assertTrue(attempt().contains(" shadow=err"))
    }

    @Test fun disagreementWhenTheModelPicksTheSecondByScore() = runTest {
        reply = PlaceMatchResult(PlaceMatchOutput("2", 0.9f), true)
        run(sampler(this) { _, _, _ -> scored(800, 500) })
        assertTrue(attempt(), attempt().contains("chosenRank=2 agrees=false"))
        assertEquals(PlaceOutcome.MATCHED, outcomes.last())
    }

    @Test fun noChoiceIsNa() = runTest {
        reply = PlaceMatchResult(PlaceMatchOutput("NONE", 0.9f), true)
        run(sampler(this) { _, _, _ -> scored(800, 500) })
        assertTrue(attempt(), attempt().contains("chosenRank=na agrees=na")); assertEquals(PlaceOutcome.NONE, outcomes.last())
    }

    @Test fun aThrowingShadowIsErrAndNothingElseChanges() = runTest {
        run(sampler(this) { _, _, _ -> throw IllegalStateException("boom") })
        assertTrue(attempt(), attempt().contains(" shadow=err")); assertFalse(attempt().contains("scoreTop"))
        assertEquals(PlaceOutcome.MATCHED, outcomes.last()); assertEquals(1, cards.size)
    }

    @Test fun anUnavailableEmbedderIsNa() = runTest {
        run(sampler(this) { _, _, _ -> ShadowOutcome.UNAVAILABLE })
        assertTrue(attempt(), attempt().endsWith(" shadow=na")); assertEquals(PlaceOutcome.MATCHED, outcomes.last())
    }

    @Test fun aDroppedResultLogsNothing() = runTest {
        run(sampler(this) { _, _, _ -> ShadowOutcome.DROPPED })
        assertFalse(attempt().contains("shadow"))
    }

    @Test fun theHookGetsALivenessThatTurnsFalseWhenTheSamplerIsClosed() = runTest {
        var liveSeen: (() -> Boolean)? = null
        val s = sampler(this) { _, _, live -> liveSeen = live; delay(10_000); scored(800, 500) }
        s.poke(); advanceTimeBy(50)
        assertTrue(liveSeen!!()); s.close(); assertFalse(liveSeen!!())
        assertTrue("close cancels the detached shadow scope", detached!!.coroutineContext[Job]!!.isCancelled)
    }

    @Test fun theAttemptEndHookRunsOnceSoTheSharedPhotoIsDropped() = runTest {
        run(sampler(this) { _, _, _ -> scored(800, 500) }); assertEquals(1, attemptEnds)
    }

    @Test fun noFieldHoldsAName() = runTest {
        run(sampler(this) { _, _, _ -> scored(800, 500) })
        val l = attempt()
        assertFalse(l.contains("Charminar")); assertFalse(l.contains("Mecca"))
    }

    @Test fun aCancelDuringTheSettleWindowKeepsTheRealOutcome() = runTest {
        callMs = 100
        val never = CompletableDeferred<ShadowOutcome>()
        val s = sampler(this) { _, _, _ -> never.await() }
        s.poke(); advanceTimeBy(150)
        assertEquals("the card is out", 1, cards.size); assertTrue(logs.none { it.startsWith("place attempt") })
        s.close(); advanceUntilIdle()
        val l = attempt()
        assertTrue(l, l.startsWith("place attempt outcome=MATCHED") && l.contains("card=shown") && !l.contains("shadow") && !l.contains("cancelled"))
        assertEquals(listOf(PlaceOutcome.MATCHED), outcomes)
    }

    @Test fun theSingleFlightIsFreeOnceTheCardIsOutEvenWhileTheShadowIsStillSettling() = runTest {
        callMs = 100
        val never = CompletableDeferred<ShadowOutcome>()
        val s = sampler(this) { _, _, _ -> never.await() }
        s.poke(); advanceTimeBy(150)
        assertFalse("busy is released at the card", flight.busy.get())
        s.poke(); advanceTimeBy(1)
        assertTrue("no BUSY outcome was logged", outcomes.none { it == PlaceOutcome.BUSY })
        advanceUntilIdle()
    }

    @Test fun aSecondPokeDuringTheSettleWindowEndsRateLimitedBecauseOfTheCallGap() = runTest {
        callMs = 100
        val never = CompletableDeferred<ShadowOutcome>()
        val s = sampler(this) { _, _, _ -> never.await() }
        s.poke(); advanceTimeBy(150)
        assertEquals("the card of attempt 1 is out; its shadow is still settling", 1, cards.size)
        dwellFrame = "f2"; fixLat = 17.37 // a new frame, far enough from the last attempt to skip the same-place dedupe
        s.poke(); advanceTimeBy(1)
        assertTrue("the second attempt ends RATE_LIMITED on the call gap, not BUSY: $outcomes", PlaceOutcome.RATE_LIMITED in outcomes && PlaceOutcome.BUSY !in outcomes)
        advanceUntilIdle()
        assertTrue(logs.any { it.startsWith("place attempt outcome=RATE_LIMITED") && it.contains("reason=call_gap_20s") })
        assertTrue("attempt 1 still logs its own shadow result", logs.any { it.startsWith("place attempt outcome=MATCHED") && it.contains(" shadow=err") })
    }

    @Test fun aMixedPinAndPackAttemptLogsHowManyPackPlacesWereDroppedFromTheRanking() = runTest {
        places = listOf(pl("user:abc", "My Desk"), pl("wd:Q1", "Charminar"), pl("wd:Q2", "Mecca Masjid"))
        val q = floatArrayOf(1f, 0f)
        run(sampler(this) { _, _, _ -> ShadowOutcome.scored(CandidateScorer.score(q, listOf(CandidateScorer.Candidate("user:abc", imageRefs = listOf(floatArrayOf(1f, 0f))), CandidateScorer.Candidate("wd:Q1", textAnchor = floatArrayOf(1f, 0f)), CandidateScorer.Candidate("wd:Q2", textAnchor = floatArrayOf(0f, 1f)))), 42L) })
        val l = attempt()
        assertTrue(l, l.contains(" shCh=image ") && l.endsWith(" embedMs=42 shMixed=2"))
    }

    @Test fun aPinsOnlyAttemptHasShMixedZeroAndATextAttemptToo() = runTest {
        places = listOf(pl("user:abc", "My Desk"))
        val q = floatArrayOf(1f, 0f)
        run(sampler(this) { _, _, _ -> ShadowOutcome.scored(CandidateScorer.score(q, listOf(CandidateScorer.Candidate("user:abc", imageRefs = listOf(floatArrayOf(1f, 0f))))), 42L) })
        assertTrue(attempt(), attempt().endsWith(" shMixed=0"))
        logs.clear(); outcomes.clear(); flight = PlaceFlight(); places = listOf(pl("wd:Q1", "Charminar"), pl("wd:Q2", "Mecca Masjid")); dwellFrame = "f9"
        run(sampler(this) { _, _, _ -> scored(800, 500) })
        assertTrue(attempt(), attempt().contains(" shCh=text ") && attempt().endsWith(" shMixed=0"))
    }

    @Test fun shMixedIsAbsentWhenTheShadowIsOffOrNotScored() = runTest {
        flag = false
        run(sampler(this) { _, _, _ -> scored(800, 500) })
        assertFalse(attempt().contains("shMixed"))
        logs.clear(); outcomes.clear(); flight = PlaceFlight(); flag = true; dwellFrame = "f7"
        run(sampler(this) { _, _, _ -> ShadowOutcome.UNAVAILABLE })
        assertFalse(attempt().contains("shMixed"))
    }

    @Test fun theShadowBudgetEndsBeforeTheNextCallMayStart() {
        assertTrue(PlaceSampler.SHADOW_BUDGET_MS < PlaceSampler.MIN_CALL_GAP_MS)
    }

    @Test fun anErrorWhileLoadingThePhotoEndsTheAttemptAndFreesTheFlight() = runTest {
        val share = SharedPhoto { throw OutOfMemoryError() }
        matchImpl = { id -> share.get(id)?.let { reply } }
        val s = sampler(this, null)
        run(s)
        assertEquals(PlaceOutcome.FAILED, outcomes.last()); assertFalse(flight.busy.get()); assertTrue(cards.isEmpty())
        assertTrue(attempt(), attempt().contains("reason=call_failed_or_photo_gone"))
    }

    @Test fun aFenceCancelDuringThePhotoLoadCancelsTheLoad() = runTest {
        var cancelled = false
        val share = SharedPhoto { try { delay(60_000); null } catch (c: kotlinx.coroutines.CancellationException) { cancelled = true; throw c } }
        matchImpl = { id -> share.get(id)?.let { reply } }
        val s = sampler(this, null)
        s.poke(); advanceTimeBy(100); s.close(); advanceUntilIdle()
        assertTrue("the read is cancelled with the attempt", cancelled); assertFalse(flight.busy.get()); assertEquals(1, attemptEnds)
    }

    @Test fun theAttemptEndHookRunsOnAnExceptionAndOnCancel() = runTest {
        matchImpl = { throw IllegalStateException("x") }
        run(sampler(this, null)); assertEquals(1, attemptEnds)
        matchImpl = { delay(60_000); reply }; flight = PlaceFlight()
        val s = sampler(this, null); s.poke(); advanceTimeBy(100); s.close(); advanceUntilIdle()
        assertEquals(2, attemptEnds)
    }

    // ---- PlaceShadow itself ----

    private class FakeEmbedder(override val spaceId: String = "fake") : ImageTextEmbedder {
        override val dim = 2
        var batches = 0; val texts = mutableListOf<String>()
        override suspend fun embedImage(bytes: ByteArray) = floatArrayOf(1f, 0f)
        override suspend fun embedText(text: String): FloatArray = vec(text)
        override suspend fun embedTexts(texts: List<String>): List<FloatArray> { batches++; this.texts += texts; return texts.map(::vec) }
        private fun vec(t: String) = if ("Charminar" in t) floatArrayOf(0.8f, 0.6f) else floatArrayOf(0.2f, 0.98f)
    }

    private fun shadow(e: FakeEmbedder, wipeGen: () -> Long = { 0L }, photo: suspend (String) -> ByteArray? = { ByteArray(3) }) =
        PlaceShadow(embedder = { e }, photo = photo, elapsed = { 0L }, generation = wipeGen)

    @Test fun placeShadowBatchesNameAndKindAndNeverTheSummary() = runTest {
        val e = FakeEmbedder()
        val out = shadow(e).score("f1", places.map { it to 10.0 })
        assertEquals(ShadowOutcome.Kind.SCORED, out.kind)
        assertEquals(listOf("wd:Q1", "wd:Q2"), out.result!!.ranked.map { it.id })
        assertEquals(1, e.batches); assertEquals(listOf("Charminar. monument", "Mecca Masjid. monument"), e.texts)
    }

    @Test fun packTextVectorsAreCachedAndAUserPinNeverIs() = runTest {
        val e = FakeEmbedder(); val sh = shadow(e)
        val hits = listOf(pl("wd:Q1", "Charminar") to 10.0, pl("user:abc", "My Desk") to 20.0)
        sh.score("f", hits); sh.score("f", hits)
        assertEquals(listOf("Charminar. monument", "My Desk. monument", "My Desk. monument"), e.texts)
    }

    @Test fun aWipeGenerationChangeEmptiesTheCache() = runTest {
        val e = FakeEmbedder(); var g = 0L; val sh = shadow(e, { g })
        val hits = listOf(pl("wd:Q1", "Charminar") to 10.0)
        sh.score("f", hits); g = 1; sh.score("f", hits)
        assertEquals(2, e.texts.size)
    }

    @Test fun aSpaceChangeEmptiesTheCache() = runTest {
        var space = "a"; val e1 = FakeEmbedder("a"); val e2 = FakeEmbedder("b")
        val sh = PlaceShadow(embedder = { if (space == "a") e1 else e2 }, photo = { ByteArray(1) }, elapsed = { 0L })
        val hits = listOf(pl("wd:Q1", "Charminar") to 10.0)
        sh.score("f", hits); space = "b"; sh.score("f", hits); space = "a"; sh.score("f", hits)
        assertEquals(2, e1.texts.size); assertEquals(1, e2.texts.size)
    }

    @Test fun theCacheIsBounded() = runTest {
        val e = FakeEmbedder(); val sh = shadow(e)
        repeat(PlaceShadow.CACHE_MAX + 10) { sh.score("f", listOf(pl("wd:Q$it", "Place$it") to 1.0)) }
        e.texts.clear(); sh.score("f", listOf(pl("wd:Q0", "Place0") to 1.0))
        assertEquals("the oldest entry was evicted", 1, e.texts.size)
    }

    @Test fun aLivenessThatFlipsDropsTheResult() = runTest {
        val e = FakeEmbedder()
        assertEquals(ShadowOutcome.Kind.DROPPED, shadow(e).score("f", places.map { it to 1.0 }) { false }.kind)
        var n = 0
        assertEquals(ShadowOutcome.Kind.DROPPED, shadow(e).score("f", places.map { it to 1.0 }) { n++ == 0 }.kind)
    }

    @Test fun unavailableAndGonePhoto() = runTest {
        assertEquals(ShadowOutcome.Kind.UNAVAILABLE, PlaceShadow(embedder = { null }, photo = { ByteArray(1) }, elapsed = { 0 }).score("f", emptyList()).kind)
        assertEquals(ShadowOutcome.Kind.UNAVAILABLE, shadow(FakeEmbedder(), photo = { null }).score("f", emptyList()).kind)
    }

    @Test fun buckets() {
        assertEquals("lt200", PlaceShadow.scoreBucket(-5)); assertEquals("lt300", PlaceShadow.scoreBucket(250)); assertEquals("ge900", PlaceShadow.scoreBucket(900))
        assertEquals("na", PlaceShadow.marginBucket(null)); assertEquals("lt10", PlaceShadow.marginBucket(0)); assertEquals("ge100", PlaceShadow.marginBucket(100))
    }

    // ---- Codex place fixes r3 ----

    private val pinId = "user:00000000-0000-0000-0000-000000000001"

    /** P3-7: the private pin id stays internal for the scoring; only the log token is redacted, so the agreement is decided (not `na`). */
    @Test fun aChosenPinIsScoredByItsPrivateIdButLoggedOnlyAsPin() = runTest {
        places = listOf(pl(pinId, "Room A"), pl("wd:Q2", "Mecca Masjid"))
        val q = floatArrayOf(1f, 0f); fun v(m: Int) = floatArrayOf(m / 1000f, Math.sqrt(1.0 - (m / 1000.0) * (m / 1000.0)).toFloat())
        run(sampler(this) { _, _, _ -> ShadowOutcome.scored(CandidateScorer.score(q, listOf(CandidateScorer.Candidate(pinId, textAnchor = v(800)), CandidateScorer.Candidate("wd:Q2", textAnchor = v(300)))), 42L) })
        val l = attempt()
        assertTrue(l, l.contains("chosen=pin")); assertFalse("no pin id in the log", l.contains("user:"))
        assertTrue(l, l.contains("chosenRank=1 agrees=true"))
    }

    /** P2-5: the photo-match path shares the one reservation: while another path holds the place, no second card is published (the memory stays). */
    @Test fun thePhotoMatchPathHonoursTheSharedReservation() = runTest {
        places = listOf(pl("wd:Q1", "Charminar"))
        assertTrue(flight.reserve(listOf("wd:Q1"), T0)) // e.g. the sign-text path is publishing this place right now
        run(sampler(this, null))
        assertTrue("no second card", cards.isEmpty()); assertEquals(PlaceOutcome.MATCHED, outcomes.last()); assertTrue(attempt().contains("card=suppressed"))
        flight.unreserve(listOf("wd:Q1"))
        logs.clear(); outcomes.clear(); flight = PlaceFlight(); run(sampler(this, null))
        assertEquals("free again: the card is shown and the reservation is released", 1, cards.size); assertTrue(flight.reserve(listOf("wd:Q1"), T0 + 1))
    }

    /** P2-5: the offline nearby fallback shares it too. */
    @Test fun theNearbyFallbackHonoursTheSharedReservation() = runTest {
        places = listOf(pl("wd:Q1", "Charminar")); net = false
        assertTrue(flight.reserve(listOf("wd:Q1"), T0))
        run(sampler(this, null))
        assertTrue("no second card", cards.isEmpty()); assertEquals(PlaceOutcome.DUPLICATE, outcomes.last())
        flight.unreserve(listOf("wd:Q1")); logs.clear(); outcomes.clear(); flight = PlaceFlight(); run(sampler(this, null))
        assertEquals(1, cards.size); assertEquals(PlaceOutcome.NEARBY, outcomes.last()); assertTrue(flight.reserve(listOf("wd:Q1"), T0 + 1))
    }
}
