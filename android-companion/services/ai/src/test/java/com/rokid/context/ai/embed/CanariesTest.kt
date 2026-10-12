package com.rokid.context.ai.embed

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CanariesTest {
    private val a = listOf(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f), floatArrayOf(0.6f, 0.8f))

    @Test fun encodeDecodeRoundTrip() {
        val back = Canaries.decode(Canaries.encode(a))
        assertEquals(3, back.size); assertArrayEquals(a[2], back[2], 0f)
    }

    @Test fun staleWhenAnyCanaryDrifts() {
        assertFalse(Canaries.stale(a, a.map { it.copyOf() }))
        assertTrue(Canaries.stale(a, listOf(floatArrayOf(1f, 0f), floatArrayOf(0.2f, 0.98f), floatArrayOf(0.6f, 0.8f)))) // cos ≈ 0.98
        assertTrue(Canaries.stale(a, a.take(2)))
        assertTrue(Canaries.stale(a, listOf(floatArrayOf(1f, 0f, 0f), a[1], a[2])))
        assertEquals(3, Canaries.TEXTS.size); assertTrue(Canaries.TEXTS.any { t -> t.any { it in 'ऀ'..'ॿ' } })
    }
}
