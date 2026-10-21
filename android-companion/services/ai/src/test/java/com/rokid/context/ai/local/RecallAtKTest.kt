package com.rokid.context.ai.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class RecallAtKTest {
    private fun unit(i: Int, n: Int = 8) = FloatArray(n) { if (it == i) 1f else 0f }
    private fun q(text: String, vararg rel: String, small: Boolean = false) = RecallAtK.Query(text, rel.toSet(), small)

    // Queries use the raw text path (LayerNorm inside), so a one-hot is shaped to survive it: embed via an identity-friendly vector.
    private fun rawQuery(dir: FloatArray): FloatArray = dir // layerNorm of a centred vector keeps its direction
    private fun centred(i: Int, n: Int = 8): FloatArray = FloatArray(n) { if (it == i) 1f else -1f / (n - 1) }

    @Test fun perfectAndZeroRecall() {
        val images = (0 until 8).associate { "p$it" to unit(it) }
        val perfect = (0 until 8).map { q("q$it", "p$it") to centred(it) }
        val r = RecallAtK.evaluate(images, perfect, dims = 8, k = 1)
        assertEquals(1.0, r.overall, 1e-9); assertEquals(8, r.queries); assertEquals(8, r.photos)
        // each query asks for the photo of the next index but is shaped like index i; with k = 1 nothing hits
        val wrong = (0 until 8).map { q("q$it", "p${(it + 1) % 8}") to centred(it) }
        assertEquals(0.0, RecallAtK.evaluate(images, wrong, dims = 8, k = 1).overall, 1e-9)
    }

    @Test fun smallSubsetSeparate() {
        val images = (0 until 4).associate { "p$it" to unit(it) }
        val qs = listOf(
            q("a", "p0") to centred(0, 8),
            q("b", "p1", small = true) to centred(1, 8),
            q("c", "p3", small = true) to centred(2, 8), // miss at k = 1
            q("d", "p3") to centred(3, 8),
        )
        val r = RecallAtK.evaluate(images, qs, dims = 8, k = 1)
        assertEquals(0.75, r.overall, 1e-9); assertEquals(0.5, r.small, 1e-9)
        assertEquals(4, r.queries); assertEquals(2, r.smallQueries)
        assertEquals(3, r.hits); assertEquals(1, r.smallHits)
    }

    @Test fun shortenThenRenormalise() {
        // 4-d image whose first 2 dims are (3, 4) and tail large: truncation must keep direction (3,4)/5, not (3,4,..)/n.
        val img = floatArrayOf(3f, 4f, 100f, 100f)
        val t = RecallAtK.imageVector(img, 2)
        assertEquals(0.6f, t[0], 1e-6f); assertEquals(0.8f, t[1], 1e-6f)
        assertEquals(1.0, sqrt(t.fold(0.0) { s, x -> s + x * x }), 1e-6)
        // queries: LayerNorm over the full width, then first d, then L2
        val raw = floatArrayOf(1f, 2f, 3f, 10f)
        val expected = NomicText.shorten(NomicText.layerNorm(raw), 2)
        val got = RecallAtK.queryVector(raw, 2)
        assertEquals(expected[0], got[0], 1e-6f); assertEquals(expected[1], got[1], 1e-6f)
    }

    private fun res(overall: Double, small: Double, smallQueries: Int = 15) = RecallAtK.Result(768, overall, small, 40, smallQueries, 60)

    @Test fun ruleBoundaries() {
        val base = res(0.80, 0.70)
        assertTrue(RecallAtK.choose256(base, res(0.77, 0.65)))   // exactly 3.0 / 5.0 points
        assertFalse(RecallAtK.choose256(base, res(0.769, 0.65))) // 3.1 overall
        assertFalse(RecallAtK.choose256(base, res(0.77, 0.649))) // 5.1 small
        assertTrue(RecallAtK.choose256(base, res(0.85, 0.90)))   // better is fine
        assertFalse(RecallAtK.choose256(res(0.8, 0.7, smallQueries = 0), res(0.8, 0.7, smallQueries = 0))) // nothing to judge the small subset on
    }
}
