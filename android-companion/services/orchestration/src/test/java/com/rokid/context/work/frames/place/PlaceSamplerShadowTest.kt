package com.rokid.context.work.frames.place

import com.rokid.context.ai.contract.PlaceMatchOutput
import com.rokid.context.memory.ImageTextEmbedder
import com.rokid.context.memory.place.CellOutcome
import com.rokid.context.memory.place.PlaceCandidates
import com.rokid.context.memory.place.PlaceEntity
import com.rokid.context.memory.retrieval.CandidateScorer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private fun pl(id: String, name: String) = PlaceEntity(id, null, name, "monument", 17.36, 78.47, 50, "s", "wikipedia:en", "p:1", 1L, false, false, 0, 100)

/** Slice A shadow mode: the line gains content-free fields; nothing else ever changes. */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaceSamplerShadowTest {
    private val T0 = 10_000_000L
    private var elapsedMs = 0L
    private val logs = mutableListOf<String>()
    private val cards = mutableListOf<PlaceCard>()
    private val outcomes = mutableListOf<PlaceOutcome>()
    private var reply: PlaceMatchResult? = PlaceMatchResult(PlaceMatchOutput("1", 0.9f), true)
    private val places = listOf(pl("wd:Q1", "Charminar"), pl("wd:Q2", "Mecca Masjid"))

    private fun scored(top: Int, other: Int): ShadowOutcome {
        val q = floatArrayOf(1f, 0f)
        fun v(milli: Int) = floatArrayOf(milli / 1000f, Math.sqrt(1.0 - (milli / 1000.0) * (milli / 1000.0)).toFloat())
        return ShadowOutcome.scored(CandidateScorer.score(q, listOf(CandidateScorer.Candidate("wd:Q1", textAnchor = v(top)), CandidateScorer.Candidate("wd:Q2", textAnchor = v(other)))), 42L)
    }

    private fun sampler(scope: TestScope, shadow: (suspend (String, List<Pair<PlaceEntity, Double>>) -> ShadowOutcome)?) = PlaceSampler(
        scope = scope, store = object : PlaceFrameStore {
            override suspend fun newestDwell(sinceReceivedMs: Long) = PlaceFrame("f1", T0 - 5_000)
            override suspend fun tombstoned(frameId: String) = false
        },
        fix = { PlaceFix(17.36, 78.47, 3_000, 10f) }, candidates = { _, _, _ -> PlaceCandidates(places, CellOutcome.FRESH) },
        match = { _, _ -> reply }, saveMemory = { true }, onCard = { cards += it }, clock = { T0 }, enabled = { true }, zoneBlocked = { false }, networkUp = { true },
        log = { logs += it }, stats = { outcomes += it }, elapsed = { elapsedMs }, shadow = shadow, shadowScope = scope,
    )

    private fun TestScope.run(s: PlaceSampler) { s.poke(); runCurrent() }
    private fun attempt() = logs.single { it.startsWith("place attempt") }

    @Test fun offAddsNothingAndTheLineIsUnchanged() = runTest {
        run(sampler(this, null))
        assertFalse(attempt().contains("shadow")); assertFalse(attempt().contains("scoreTop")); assertTrue(attempt().endsWith("thrV=${com.rokid.context.core.net.BackendThresholds.VERSION}"))
        assertEquals(PlaceOutcome.MATCHED, outcomes.last())
    }

    @Test fun agreementIsLoggedAndTheOutcomeIsTheSame() = runTest {
        run(sampler(this) { _, _ -> scored(800, 500) })
        val l = attempt()
        assertTrue(l, l.contains(" shadow=ok shCh=text scoreTop=lt900 margin=ge100 chosenRank=1 agrees=true embedMs=42"))
        assertEquals(PlaceOutcome.MATCHED, outcomes.last()); assertEquals(1, cards.size)
    }

    @Test fun disagreementWhenTheModelPicksTheSecondByScore() = runTest {
        reply = PlaceMatchResult(PlaceMatchOutput("2", 0.9f), true)
        run(sampler(this) { _, _ -> scored(800, 500) })
        assertTrue(attempt(), attempt().contains("chosenRank=2 agrees=false"))
        assertEquals(PlaceOutcome.MATCHED, outcomes.last())
    }

    @Test fun noChoiceIsNa() = runTest {
        reply = PlaceMatchResult(PlaceMatchOutput("NONE", 0.9f), true)
        run(sampler(this) { _, _ -> scored(800, 500) })
        assertTrue(attempt(), attempt().contains("chosenRank=na agrees=na")); assertEquals(PlaceOutcome.NONE, outcomes.last())
    }

    @Test fun aThrowingShadowIsErrAndNothingElseChanges() = runTest {
        run(sampler(this) { _, _ -> throw IllegalStateException("boom") })
        assertTrue(attempt(), attempt().contains(" shadow=err")); assertFalse(attempt().contains("scoreTop"))
        assertEquals(PlaceOutcome.MATCHED, outcomes.last()); assertEquals(1, cards.size)
    }

    @Test fun aHungShadowTimesOutAsErrWithoutHoldingTheAttempt() = runTest {
        val never = CompletableDeferred<ShadowOutcome>()
        val s = sampler(this) { _, _ -> never.await() }
        elapsedMs = 0L
        s.poke(); runCurrent()
        advanceTimeBy(PlaceSampler.SHADOW_BUDGET_MS + 1); runCurrent()
        assertTrue(attempt(), attempt().contains(" shadow=err"))
        assertEquals(PlaceOutcome.MATCHED, outcomes.last()); assertEquals(1, cards.size)
    }

    @Test fun anUnavailableEmbedderIsNa() = runTest {
        run(sampler(this) { _, _ -> ShadowOutcome.UNAVAILABLE })
        assertTrue(attempt(), attempt().endsWith(" shadow=na")) ; assertEquals(PlaceOutcome.MATCHED, outcomes.last())
    }

    @Test fun theFlagOffOutcomeAddsNothing() = runTest {
        run(sampler(this) { _, _ -> ShadowOutcome.OFF })
        assertFalse(attempt().contains("shadow"))
    }

    @Test fun noFieldHoldsAName() = runTest {
        run(sampler(this) { _, _ -> scored(800, 500) })
        val l = attempt()
        assertFalse(l.contains("Charminar")); assertFalse(l.contains("Mecca"))
    }

    // ---- PlaceShadow itself ----

    private class FakeEmbedder : ImageTextEmbedder {
        override val spaceId = "fake"; override val dim = 2
        val texts = mutableListOf<String>()
        override suspend fun embedImage(bytes: ByteArray) = floatArrayOf(1f, 0f)
        override suspend fun embedText(text: String): FloatArray { texts += text; return if ("Charminar" in text) floatArrayOf(0.8f, 0.6f) else floatArrayOf(0.2f, 0.98f) }
    }

    @Test fun placeShadowScoresNameAndKindAndNeverTheSummary() = runTest {
        val e = FakeEmbedder()
        val sh = PlaceShadow(enabled = { true }, embedder = { e }, photo = { ByteArray(3) }, elapsed = { elapsedMs })
        val out = sh.score("f1", places.map { it to 10.0 })
        assertEquals(ShadowOutcome.Kind.SCORED, out.kind)
        assertEquals(listOf("wd:Q1", "wd:Q2"), out.result!!.ranked.map { it.id })
        assertEquals(listOf("Charminar. monument", "Mecca Masjid. monument"), e.texts)
    }

    @Test fun placeShadowOffUnavailableAndGonePhoto() = runTest {
        assertEquals(ShadowOutcome.Kind.OFF, PlaceShadow(enabled = { false }, embedder = { FakeEmbedder() }, photo = { ByteArray(1) }, elapsed = { 0 }).score("f", emptyList()).kind)
        assertEquals(ShadowOutcome.Kind.UNAVAILABLE, PlaceShadow(enabled = { true }, embedder = { null }, photo = { ByteArray(1) }, elapsed = { 0 }).score("f", emptyList()).kind)
        assertEquals(ShadowOutcome.Kind.UNAVAILABLE, PlaceShadow(enabled = { true }, embedder = { FakeEmbedder() }, photo = { null }, elapsed = { 0 }).score("f", emptyList()).kind)
    }

    @Test fun buckets() {
        assertEquals("lt200", PlaceShadow.scoreBucket(-5)); assertEquals("lt300", PlaceShadow.scoreBucket(250)); assertEquals("ge900", PlaceShadow.scoreBucket(900))
        assertEquals("na", PlaceShadow.marginBucket(null)); assertEquals("lt10", PlaceShadow.marginBucket(0)); assertEquals("ge100", PlaceShadow.marginBucket(100))
    }
}
