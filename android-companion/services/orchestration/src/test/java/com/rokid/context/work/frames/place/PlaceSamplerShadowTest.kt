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
    private var hookCalls = 0
    private val places = listOf(pl("wd:Q1", "Charminar"), pl("wd:Q2", "Mecca Masjid"))
    private var flight = PlaceFlight()
    private var detached: CoroutineScope? = null
    private var attemptEnds = 0

    private fun scored(top: Int, other: Int): ShadowOutcome {
        val q = floatArrayOf(1f, 0f)
        fun v(milli: Int) = floatArrayOf(milli / 1000f, Math.sqrt(1.0 - (milli / 1000.0) * (milli / 1000.0)).toFloat())
        return ShadowOutcome.scored(CandidateScorer.score(q, listOf(CandidateScorer.Candidate("wd:Q1", textAnchor = v(top)), CandidateScorer.Candidate("wd:Q2", textAnchor = v(other)))), 42L)
    }

    private fun sampler(scope: TestScope, hook: (suspend (String, List<Pair<PlaceEntity, Double>>, () -> Boolean) -> ShadowOutcome)?): PlaceSampler {
        val d = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scope.testScheduler)).also { detached = it }
        return PlaceSampler(
            scope = scope, store = object : PlaceFrameStore {
                override suspend fun newestDwell(sinceReceivedMs: Long) = PlaceFrame("f1", T0 - 5_000)
                override suspend fun tombstoned(frameId: String) = false
            },
            fix = { PlaceFix(17.36, 78.47, 3_000, 10f) }, candidates = { _, _, _ -> PlaceCandidates(places, CellOutcome.FRESH) },
            match = { _, _ -> delay(callMs); reply }, saveMemory = { true }, onCard = { cards += it }, clock = { T0 }, enabled = { true }, zoneBlocked = { false }, networkUp = { true },
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
}
