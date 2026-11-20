package com.rokid.context.memory.retrieval

import com.rokid.context.memory.retrieval.CandidateScorer.Candidate
import com.rokid.context.memory.retrieval.CandidateScorer.Channel
import com.rokid.context.memory.retrieval.CandidateScorer.Scored
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CandidateScorerTest {
    private val q = floatArrayOf(1f, 0f, 0f)
    private fun v(x: Float, y: Float, z: Float = 0f) = floatArrayOf(x, y, z)

    @Test fun `golden text channel scores, order and margin`() {
        val r = CandidateScorer.score(q, listOf(
            Candidate("wd:Q1", textAnchor = v(0.6f, 0.8f)), // 0.6
            Candidate("wd:Q2", textAnchor = v(0.8f, 0.6f)), // 0.8
            Candidate("osm:node/3", textAnchor = v(0f, 1f)), // 0.0
        ))
        assertEquals(Channel.TEXT, r.channel)
        assertEquals(listOf(Scored("wd:Q2", 800), Scored("wd:Q1", 600), Scored("osm:node/3", 0)), r.ranked)
        assertEquals(200, r.margin)
        assertEquals(1, r.rankOf("wd:Q2")); assertEquals(3, r.rankOf("osm:node/3")); assertNull(r.rankOf("nope"))
    }

    @Test fun `image channel wins when any candidate has a reference, and takes the max over references`() {
        val r = CandidateScorer.score(q, listOf(
            Candidate("user:a", imageRefs = listOf(v(0f, 1f), v(0.9f, 0.435889894f)), textAnchor = v(1f, 0f)),
            Candidate("user:b", imageRefs = listOf(v(0.5f, 0.866025404f))),
            Candidate("wd:Q9", textAnchor = v(1f, 0f)), // text only: not ranked on the image channel
        ))
        assertEquals(Channel.IMAGE, r.channel)
        assertEquals(listOf(Scored("user:a", 900), Scored("user:b", 500)), r.ranked)
        assertNull(r.rankOf("wd:Q9"))
        assertEquals(400, r.margin)
    }

    @Test fun `scores are rounded to 1e-3 so last-bit noise cannot reorder`() {
        val a = Candidate("b", textAnchor = v(0.8f, 0.6f))
        val b = Candidate("a", textAnchor = v(0.8000001f, 0.6f))
        assertEquals(listOf("a", "b"), CandidateScorer.score(q, listOf(a, b)).ranked.map { it.id })
        assertEquals(listOf("a", "b"), CandidateScorer.score(q, listOf(b, a)).ranked.map { it.id })
    }

    @Test fun `ties break by edge gap, then distance, then id`() {
        val t = v(0.8f, 0.6f)
        val r = CandidateScorer.score(q, listOf(
            Candidate("z", textAnchor = t, edgeGapM = 5.0, distanceM = 9.0),
            Candidate("y", textAnchor = t, edgeGapM = 0.0, distanceM = 30.0),
            Candidate("x", textAnchor = t, edgeGapM = 0.0, distanceM = 10.0),
            Candidate("w", textAnchor = t, edgeGapM = 0.0, distanceM = 10.0),
        ))
        assertEquals(listOf("w", "x", "y", "z"), r.ranked.map { it.id })
        assertEquals(0, r.margin)
    }

    @Test fun `deterministic under any input order`() {
        val cs = (0 until 12).map { Candidate("c$it", textAnchor = v((it % 4) / 4f + 0.1f, 0.5f), edgeGapM = (it % 3).toDouble(), distanceM = it.toDouble()) }
        val base = CandidateScorer.score(q, cs).ranked
        val rnd = java.util.Random(7)
        repeat(20) { assertEquals(base, CandidateScorer.score(q, cs.shuffled(rnd)).ranked) }
    }

    @Test fun `top k and k below one`() {
        val cs = (1..5).map { Candidate("c$it", textAnchor = v(1f, it.toFloat())) }
        assertEquals(listOf("c1", "c2"), CandidateScorer.score(q, cs, k = 2).top.map { it.id })
        assertEquals(1, CandidateScorer.score(q, cs, k = 0).top.size)
        assertEquals(5, CandidateScorer.score(q, cs, k = 99).top.size)
    }

    @Test fun `single candidate has no margin`() {
        val r = CandidateScorer.score(q, listOf(Candidate("only", textAnchor = q)))
        assertEquals(1000, r.top1?.milli); assertNull(r.margin)
    }

    @Test fun `unusable vectors are skipped, never thrown on`() {
        val r = CandidateScorer.score(q, listOf(
            Candidate("nan", textAnchor = floatArrayOf(Float.NaN, 0f, 0f)),
            Candidate("zero", textAnchor = v(0f, 0f)),
            Candidate("short", textAnchor = floatArrayOf(1f, 0f)),
            Candidate("ok", textAnchor = q),
        ))
        assertEquals(listOf("ok"), r.ranked.map { it.id })
        assertEquals(Channel.NONE, CandidateScorer.score(floatArrayOf(0f, 0f, 0f), listOf(Candidate("a", textAnchor = q))).channel)
        assertEquals(Channel.NONE, CandidateScorer.score(q, emptyList()).channel)
        assertEquals(Channel.NONE, CandidateScorer.score(q, listOf(Candidate("a"))).channel)
    }

    @Test fun `a duplicated id keeps its best occurrence whatever the input order`() {
        val weak = Candidate("a", textAnchor = v(0f, 1f), edgeGapM = 0.0)
        val strong = Candidate("a", textAnchor = v(0.8f, 0.6f), edgeGapM = 9.0)
        val other = Candidate("b", textAnchor = v(0.5f, 0.866025404f))
        val r1 = CandidateScorer.score(q, listOf(weak, strong, other)).ranked
        val r2 = CandidateScorer.score(q, listOf(other, strong, weak)).ranked
        assertEquals(listOf(Scored("a", 800), Scored("b", 500)), r1)
        assertEquals(r1, r2)
    }

    @Test fun `duplicate id keeps one entry`() {
        val r = CandidateScorer.score(q, listOf(Candidate("a", textAnchor = q), Candidate("a", textAnchor = v(0f, 1f))))
        assertEquals(1, r.ranked.size)
    }
}
