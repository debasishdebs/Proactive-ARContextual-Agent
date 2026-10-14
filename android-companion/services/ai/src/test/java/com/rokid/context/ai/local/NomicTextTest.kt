package com.rokid.context.ai.local

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import kotlin.math.sqrt

class NomicTextTest {
    @Test fun layerNormMatchesReference() {
        val v = floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f)
        // mean 4.5; population variance (2*(3.5²+2.5²+1.5²+0.5²))/8 = 5.25; no affine, eps 1e-5.
        val inv = 1.0 / sqrt(5.25 + 1e-5)
        val expected = FloatArray(8) { ((v[it] - 4.5) * inv).toFloat() }
        assertArrayEquals(expected, NomicText.layerNorm(v), 1e-6f)
        assertEquals(-1.5275f, NomicText.layerNorm(v)[0], 1e-3f) // the same by hand: -3.5 / 2.2913
    }

    @Test fun shortenRenormalises() {
        val s = NomicText.shorten(floatArrayOf(3f, 4f, 100f, 100f), 2)
        assertArrayEquals(floatArrayOf(0.6f, 0.8f), s, 1e-6f)
    }

    @Test fun prefixesApplied() {
        assertEquals("search_document: ", NomicText.DOC)
        assertEquals("search_query: ", NomicText.QUERY)
    }

    @Test fun fingerprintDiffersByDims() {
        assertNotEquals(NomicText.fingerprint(256), NomicText.fingerprint(768))
        assertEquals("nomic-embed-text-v1.5|mean|ln|d=256|l2", NomicText.fingerprint(256))
    }
}
