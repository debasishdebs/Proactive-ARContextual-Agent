package com.rokid.context.core.model.context.graph

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 3c C1b: the exact, full-horizon window ring. */
class WindowRingTest {
    private val out = Sample()

    @Test fun WindowWarmupNotReady() {
        val r = WindowRing(5, WindowRing.AGG_MEAN)
        assertFalse(r.read(out, 0, 0).available)
        for (i in 1..4) { r.push(1000); assertFalse(r.ready, "n=$i of 5"); assertTrue(r.value().isNaN()); assertFalse(r.read(out, 0, 0).available) }
        r.push(1000)
        assertTrue(r.ready); assertEquals(1.0, r.value()); assertTrue(r.read(out, 7, 3).available)
    }

    @Test fun WindowWarmupAtOneSecondHorizonAnswersAtFirstSlot() {
        val r = WindowRing(1, WindowRing.AGG_MAX)
        assertFalse(r.ready); r.push(2500); assertEquals(2.5, r.value())
    }

    @Test fun WindowGapInvalidNotZero() {
        val r = WindowRing(4, WindowRing.AGG_MEAN)
        repeat(4) { r.push(2000) }
        assertEquals(2.0, r.value())
        r.pushInvalid()
        assertFalse(r.ready, "a gap is not a zero: the window answers nothing")
        assertTrue(r.value().isNaN())
        repeat(3) { r.push(2000); assertFalse(r.ready) }
        r.push(2000)   // the invalid slot has left the window
        assertTrue(r.ready); assertEquals(2.0, r.value(), "no zero crept into the mean")
    }

    @Test fun maxFollowsTheMonotonicDequeAcrossExpiry() {
        val r = WindowRing(3, WindowRing.AGG_MAX)
        for (q in listOf(5000, 1000, 2000)) r.push(q)
        assertEquals(5.0, r.value())
        r.push(1500); assertEquals(2.0, r.value(), "the 5000 expired")
        r.push(1000); r.push(900); assertEquals(1.5, r.value())
        r.push(100); assertEquals(1.0, r.value())
        r.push(-3000); r.push(-4000); r.push(-5000); assertEquals(-3.0, r.value())
    }

    @Test fun fracIsTheShareOfOnes() {
        val r = WindowRing(4, WindowRing.AGG_FRAC)
        for (q in listOf(1, 0, 1, 1)) r.push(q)
        assertEquals(0.75, r.value())
    }

    @Test fun WindowExactSum() {
        val r = WindowRing(300, WindowRing.AGG_MEAN)
        val rnd = java.util.Random(7)
        val last = IntArray(300)
        for (i in 0 until 100_000) {
            val q = WindowRing.quantise(rnd.nextDouble() * 4000.0 - 2000.0)!!
            r.push(q); last[i % 300] = q
        }
        var exact = 0L
        for (q in last) exact += q
        assertEquals((exact.toDouble() / 300) / 1000.0, r.value(), "bit-identical to a full recompute after 1e5 samples")
    }

    @Test fun WindowResetOnApply() {
        val r = WindowRing(3, WindowRing.AGG_MAX)
        repeat(5) { r.push(1000) }
        assertTrue(r.ready)
        r.reset()
        assertFalse(r.ready); assertEquals(0, r.filled); assertTrue(r.value().isNaN())
        r.push(7000); r.push(1000); assertFalse(r.ready)
        r.push(2000); assertEquals(7.0, r.value())
    }

    @Test fun WindowStateBytes() {
        assertEquals(1200, WindowRing.stateBytes(300, WindowRing.AGG_MEAN))
        assertEquals(1200, WindowRing.stateBytes(300, WindowRing.AGG_FRAC))
        assertEquals(2400, WindowRing.stateBytes(300, WindowRing.AGG_MAX))
        assertEquals(8, WindowRing.stateBytes(1, WindowRing.AGG_MAX))
    }

    @Test fun quantiseRoundsHalvesUpClampsAndRefusesNonFinite() {
        assertEquals(1, WindowRing.quantise(0.0005)); assertEquals(0, WindowRing.quantise(-0.0005)); assertEquals(-1, WindowRing.quantise(-0.0015))
        assertEquals(2_000_000, WindowRing.quantise(1e9)); assertEquals(-2_000_000, WindowRing.quantise(-1e9))
        assertEquals(null, WindowRing.quantise(Double.NaN)); assertEquals(null, WindowRing.quantise(Double.POSITIVE_INFINITY))
    }
}
