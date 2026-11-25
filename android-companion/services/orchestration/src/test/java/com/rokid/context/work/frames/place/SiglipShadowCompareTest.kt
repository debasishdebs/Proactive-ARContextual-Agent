package com.rokid.context.work.frames.place

import com.rokid.context.memory.ImageTextEmbedder
import com.rokid.context.memory.place.PlaceEntity
import com.rokid.context.memory.retrieval.CandidateScorer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class SiglipShadowCompareTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @After fun stop() { scope.coroutineContext[kotlinx.coroutines.Job]!!.cancel() }

    private fun pack(id: String, name: String) = PlaceEntity(id, null, name, "monument", 17.36, 78.47, 50, "s", "wikipedia:en", "p:1", 1L, false, false, 0, 100)
    private val hits = listOf(pack("wd:Q1", "Charminar") to 10.0, pack("wd:Q2", "Mecca Masjid") to 90.0)
    private val q = floatArrayOf(1f, 0f)

    /** Both candidates as text anchors; [top] is the cosine of Q1, [other] of Q2. */
    private fun scored(top: Float, other: Float, ms: Long = 40L): ShadowOutcome {
        fun v(c: Float) = floatArrayOf(c, kotlin.math.sqrt(1 - c * c))
        return ShadowOutcome.scored(CandidateScorer.score(q, listOf(CandidateScorer.Candidate("wd:Q1", textAnchor = v(top)), CandidateScorer.Candidate("wd:Q2", textAnchor = v(other)))), ms)
    }

    private class Rig(val scope: CoroutineScope, val on: Boolean, val nomic: ShadowScorer, val siglip: ShadowScorer, budget: Long = 5_000L) {
        val lines = CopyOnWriteArrayList<String>()
        val c = SiglipShadowCompare(nomic, siglip, { on }, scope, { lines += it }, budgetMs = budget, siglipSpace = { "siglip2-test" }, nomicSpace = { "nomic-test" })
        fun waitLine(): String = runBlocking { withTimeout(5_000) { while (lines.isEmpty()) delay(5); lines.single() } }
    }

    @Test fun `flag off calls only Nomic and logs nothing`() = runBlocking {
        val sig = AtomicInteger(); val n = scored(0.9f, 0.2f)
        val r = Rig(scope, false, { _, _, _ -> n }, { _, _, _ -> sig.incrementAndGet(); scored(0.5f, 0.1f) })
        assertSame(n, r.c.score("f", hits) { true })
        delay(100)
        assertEquals(0, sig.get()); assertTrue(r.lines.isEmpty())
    }

    @Test fun `flag on returns the Nomic outcome untouched and logs one counts-only line`() = runBlocking {
        val n = scored(0.9f, 0.2f); val s = scored(0.3f, 0.1f, ms = 120L)
        val r = Rig(scope, true, { _, _, _ -> n }, { _, _, _ -> s })
        assertSame("Nomic's outcome is returned as it is", n, r.c.score("frame-secret", hits) { true })
        val line = r.waitLine()
        assertTrue(line, line.startsWith("place shadow2 v=1 thrV=0 cand=2 nomic=ok sig=ok"))
        assertTrue(line, line.contains("chN=text chS=text top1Same=true top3Overlap=2 topN=2/2"))
        assertTrue(line, line.contains("scoreN=ge900") && line.contains("scoreS=lt400"))
        assertTrue(line, line.contains("bandN=borderline bandS=borderline"))
        assertTrue(line, line.contains("msN=40 msS=120"))
        for (secret in listOf("Charminar", "Mecca", "wd:Q", "user:", "frame-secret", "17.36")) assertFalse("$secret leaked: $line", line.contains(secret))
        assertTrue(line.all { it.code in 32..126 })
    }

    @Test fun `different top ones are reported as top1Same false`() = runBlocking {
        val r = Rig(scope, true, { _, _, _ -> scored(0.9f, 0.2f) }, { _, _, _ -> scored(0.1f, 0.4f) })
        r.c.score("f", hits) { true }
        val line = r.waitLine()
        assertTrue(line, line.contains("top1Same=false") && line.contains("top3Overlap=2"))
    }

    @Test fun `a SigLIP failure, a missing model and a timeout are statuses and never change Nomic`() = runBlocking {
        val n = scored(0.9f, 0.2f)
        val err = Rig(scope, true, { _, _, _ -> n }, { _, _, _ -> error("boom") })
        assertSame(n, err.c.score("f", hits) { true }); assertTrue(err.waitLine(), err.waitLine().endsWith("nomic=ok sig=err"))
        val na = Rig(scope, true, { _, _, _ -> n }, { _, _, _ -> ShadowOutcome.UNAVAILABLE })
        assertSame(n, na.c.score("f", hits) { true }); assertTrue(na.waitLine(), na.waitLine().endsWith("nomic=ok sig=na"))
        val slow = Rig(scope, true, { _, _, _ -> n }, { _, _, _ -> delay(10_000); scored(0.5f, 0.1f) }, budget = 60L)
        assertSame(n, slow.c.score("f", hits) { true }); assertTrue(slow.waitLine(), slow.waitLine().endsWith("nomic=ok sig=timeout"))
    }

    @Test fun `Nomic unavailable is logged and a dropped side logs nothing`() = runBlocking {
        val na = Rig(scope, true, { _, _, _ -> ShadowOutcome.UNAVAILABLE }, { _, _, _ -> scored(0.5f, 0.1f) })
        assertSame(ShadowOutcome.UNAVAILABLE, na.c.score("f", hits) { true }); assertTrue(na.waitLine(), na.waitLine().contains("nomic=na sig=ok"))
        for ((a, b) in listOf(ShadowOutcome.DROPPED to scored(0.5f, 0.1f), scored(0.5f, 0.1f) to ShadowOutcome.DROPPED)) {
            val r = Rig(scope, true, { _, _, _ -> a }, { _, _, _ -> b })
            r.c.score("f", hits) { true }; delay(150)
            assertTrue(r.lines.toString(), r.lines.isEmpty())
        }
    }

    @Test fun `a Nomic exception propagates as before and nothing is logged`() = runBlocking {
        val r = Rig(scope, true, { _, _, _ -> throw IllegalStateException("nomic") }, { _, _, _ -> delay(50); scored(0.5f, 0.1f) })
        try { r.c.score("f", hits) { true }; error("must throw") } catch (e: IllegalStateException) { assertEquals("nomic", e.message) }
        delay(200); assertTrue(r.lines.isEmpty())
    }

    @Test fun `the SigLIP side sees the fence AND its own flag`() = runBlocking {
        var seen: (() -> Boolean)? = null
        var fence = true
        val r = Rig(scope, true, { _, _, _ -> scored(0.9f, 0.2f) }, { _, _, live -> seen = live; scored(0.5f, 0.1f) })
        r.c.score("f", hits) { fence }; r.waitLine()
        assertTrue(seen!!()); fence = false; assertFalse(seen!!())
    }

    @Test fun `two real shadows over fake embedders in different spaces rank independently`() = runBlocking {
        // Synthetic 2-D "spaces": the same two texts land in different places in each, so the two spaces may disagree and are never mixed.
        fun fake(id: String, a: FloatArray, b: FloatArray, img: FloatArray) = object : ImageTextEmbedder {
            override val spaceId = id; override val dim = 2
            override suspend fun embedImage(bytes: ByteArray) = img
            override suspend fun embedText(text: String) = if (text.startsWith("Charminar")) a else b
        }
        val nomic = PlaceShadow({ fake("nomic-x", floatArrayOf(1f, 0f), floatArrayOf(0f, 1f), floatArrayOf(1f, 0f)) }, { ByteArray(1) }, elapsed = { 0L })
        val sig = PlaceShadow({ fake("siglip2-x", floatArrayOf(1f, 0f), floatArrayOf(0f, 1f), floatArrayOf(0f, 1f)) }, { ByteArray(1) }, elapsed = { 0L })
        val r = Rig(scope, true, nomic::score, sig::score)
        r.c.score("f", hits) { true }
        val line = r.waitLine()
        assertTrue(line, line.contains("top1Same=false") && line.contains("scoreN=ge900") && line.contains("scoreS=ge900"))
    }
}
