package com.rokid.context.memory.retrieval

import com.rokid.context.memory.retrieval.MultiFrameQuery.Frame
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class MultiFrameQueryTest {
    private val dwell = floatArrayOf(1f, 0f)
    private val t = 100_000L
    /** A unit vector with cosine [milli]/1000 to the dwell vector. */
    private fun at(milli: Int): FloatArray { val c = milli / 1000.0; return floatArrayOf(c.toFloat(), sqrt(1 - c * c).toFloat()) }
    private fun f(id: String, ageMs: Long, milli: Int?) = Frame(id, t - ageMs, milli?.let { at(it) })
    private fun build(others: List<Frame>, moved: Boolean = false, cfg: MultiFrameConfig = MultiFrameConfig()) = MultiFrameQuery.build(dwell, "d", t, others, moved, cfg)!!

    @Test fun `defaults are 20 s 4 frames 920 and the mean`() {
        val c = MultiFrameConfig(); assertEquals(20_000L, c.windowMs); assertEquals(4, c.maxExtra); assertEquals(920, c.minSimilarityMilli); assertEquals(QueryMode.MEAN, c.mode)
    }

    @Test fun `only the dwell frame when there is nothing else`() {
        val q = build(emptyList()); assertEquals(1, q.k); assertEquals(1, q.scoringVectors().size); assertArrayEquals(dwell, q.scoringVectors().single(), 0f)
    }

    @Test fun `similarity 920 is inclusive and 919 is out`() {
        assertEquals(2, build(listOf(f("a", 1_000, 920))).k)
        assertEquals(1, build(listOf(f("a", 1_000, 919))).k)
    }

    @Test fun `window is 20 s inclusive and later frames or the dwell itself are ignored`() {
        assertEquals(2, build(listOf(f("a", 20_000, 990))).k)
        assertEquals(1, build(listOf(f("a", 20_001, 990))).k)
        assertEquals(1, build(listOf(Frame("a", t + 1, at(990)))).k)
        assertEquals(1, build(listOf(Frame("d", t, at(990)))).k)
    }

    @Test fun `frames without a vector are never embedded or counted`() {
        assertEquals(1, build(listOf(f("a", 1_000, null))).k)
    }

    @Test fun `at most four extras and the newest ones win`() {
        val others = (1..7).map { f("f$it", it * 1_000L, 990) }
        val q = build(others); assertEquals(5, q.k)
        val moved = build(others, moved = true); assertEquals(1, moved.k) // the glasses moved: no extra
        assertEquals(2, build(others, cfg = MultiFrameConfig(maxExtra = 1)).k)
        assertEquals(1, build(others, cfg = MultiFrameConfig(maxExtra = 0)).k)
    }

    @Test fun `the mean is normalised and sits between the frames`() {
        val q = build(listOf(f("a", 1_000, 920)))
        val m = q.scoringVectors().single()
        assertEquals(1.0, VectorMath.norm(m), 1e-5)
        assertTrue(VectorMath.cosine(m, dwell) < 1.0 && VectorMath.cosine(m, dwell) > 0.97)
    }

    @Test fun `best of k keeps every kept frame as its own vector`() {
        val q = build(listOf(f("a", 1_000, 950), f("b", 2_000, 930)), cfg = MultiFrameConfig(mode = QueryMode.BEST_OF_K))
        assertEquals(3, q.scoringVectors().size)
    }

    @Test fun `bad inputs never throw`() {
        assertNull(MultiFrameQuery.build(null, "d", t, emptyList(), false))
        assertNull(MultiFrameQuery.build(floatArrayOf(0f, 0f), "d", t, emptyList(), false))
        assertNull(MultiFrameQuery.build(floatArrayOf(Float.NaN), "d", t, emptyList(), false))
        assertNull(MultiFrameQuery.build(floatArrayOf(), "d", t, emptyList(), false))
        val q = build(listOf(Frame("a", t - 1, floatArrayOf(1f, 0f, 0f)), Frame("b", t - 1, floatArrayOf(0f, 0f)), Frame("c", t - 1, floatArrayOf(Float.NaN, 0f)), Frame("d2", t - 1, floatArrayOf())))
        assertEquals(1, q.k)
    }

    @Test fun `opposite frames cannot make a zero mean query`() {
        // similarity gate makes this unreachable through build; the mean helper itself stays safe
        assertNull(MultiFrameQuery.mean(listOf(floatArrayOf(1f, 0f), floatArrayOf(-1f, 0f))))
    }
}
