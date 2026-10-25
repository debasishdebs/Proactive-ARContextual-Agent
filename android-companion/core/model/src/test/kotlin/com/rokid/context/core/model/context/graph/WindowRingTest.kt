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

    // 3d: percent tolerance. 10 s horizon, 90 % = at least 9 valid seconds.
    private fun ring(agg: Int, pct: Int, vararg q: Int?): WindowRing {
        val r = WindowRing(10, agg, pct)
        for (x in q) if (x == null) r.pushInvalid() else r.push(x)
        return r
    }

    @Test fun ToleranceAtTheThresholdAnswersOneBelowDoesNot() {
        val at = ring(WindowRing.AGG_MEAN, 90, 1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000, null)
        assertTrue(at.ready, "9 of 10 valid at 90 %"); assertEquals(1.0, at.value())
        val below = ring(WindowRing.AGG_MEAN, 90, 1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000, null, null)
        assertFalse(below.ready, "8 of 10 valid at 90 %"); assertTrue(below.value().isNaN())
    }

    @Test fun ToleranceIsExactIntegerArithmetic() {
        // 54 of 60 at 90 % answers, 53 does not (53*100 < 90*60); 7 of 9: 700 < 78*9 = 702, 700 >= 77*9 = 693
        fun r(valid: Int, pct: Int, n: Int): WindowRing { val w = WindowRing(n, WindowRing.AGG_MEAN, pct); repeat(n - valid) { w.pushInvalid() }; repeat(valid) { w.push(1000) }; return w }
        assertTrue(r(54, 90, 60).ready); assertFalse(r(53, 90, 60).ready)
        assertFalse(r(7, 78, 9).ready); assertTrue(r(7, 77, 9).ready)
        assertTrue(r(5, 50, 10).ready); assertFalse(r(4, 50, 10).ready)
    }

    @Test fun ToleranceStillWarmsUpTheFullHorizon() {
        val r = WindowRing(10, WindowRing.AGG_MEAN, 50)
        repeat(9) { r.push(1000); assertFalse(r.ready, "all valid but only ${it + 1} of 10 seconds elapsed") }
        r.push(1000); assertTrue(r.ready)
        val g = WindowRing(10, WindowRing.AGG_MEAN, 50)
        repeat(9) { g.pushInvalid() }; assertFalse(g.ready); g.push(1000); assertFalse(g.ready, "1 of 10 valid")
    }

    @Test fun MeanAndFracAreOverTheValidSecondsOnly() {
        val m = ring(WindowRing.AGG_MEAN, 70, 1000, null, 3000, null, 2000, 2000, null, 2000, 2000, 1000)
        assertEquals((13000.0 / 7) / 1000.0, m.value(), "1+3+2+2+2+2+1 = 13 over the 7 valid seconds, not over 10")
    }

    @Test fun FracIsOverTheValidSecondsOnly() {
        val f = ring(WindowRing.AGG_FRAC, 60, 1, null, 1, 0, null, 0, 1, 1, null, 0)
        assertEquals(4.0 / 7.0, f.value())
    }

    @Test fun MaxIgnoresGapsAndFollowsExpiry() {
        val r = WindowRing(4, WindowRing.AGG_MAX, 50)
        for (q in listOf<Int?>(5000, null, 1000, 2000)) if (q == null) r.pushInvalid() else r.push(q)
        assertEquals(5.0, r.value())
        r.pushInvalid(); assertEquals(2.0, r.value(), "5000 expired, a gap is not a zero maximum")
        r.pushInvalid(); r.push(-500); assertEquals(2.0, r.value())
        r.push(-600); assertEquals(-0.5, r.value(), "only valid seconds count: -500 is the max, not a gap's 0")
    }

    @Test fun AllInvalidNeverAnswers() {
        val r = WindowRing(10, WindowRing.AGG_MAX, 50)
        repeat(30) { r.pushInvalid(); assertFalse(r.ready) }
    }

    @Test fun GapsAtTheEdgesOfTheWindow() {
        val r = WindowRing(10, WindowRing.AGG_MEAN, 80)
        r.pushInvalid(); r.pushInvalid(); repeat(8) { r.push(1000) }
        assertTrue(r.ready, "two leading gaps: 8 of 10"); assertEquals(1.0, r.value())
        r.pushInvalid(); r.pushInvalid(); assertTrue(r.ready, "both leading gaps left, two trailing ones entered: still 8 of 10")
        r.pushInvalid(); assertFalse(r.ready, "a valid second left: 7 of 10")
        repeat(7) { r.push(1000); assertFalse(r.ready, "the three gaps are still inside: 7 of 10") }
        r.push(1000); assertTrue(r.ready, "the first trailing gap left: 8 of 10"); assertEquals(1.0, r.value())
    }

    @Test fun ToleranceRangeIsRefused() {
        for (p in listOf(49, 101, 0, -1)) assertTrue(runCatching { WindowRing(5, WindowRing.AGG_MAX, p) }.isFailure, "pct $p")
    }

    @Test fun ToleranceExactSumOverManyGaps() {
        val r = WindowRing(300, WindowRing.AGG_MEAN, 50)
        val rnd = java.util.Random(11)
        val last = arrayOfNulls<Int>(300)
        for (i in 0 until 50_000) {
            val q = if (rnd.nextInt(4) == 0) null else WindowRing.quantise(rnd.nextDouble() * 4000.0 - 2000.0)!!
            if (q == null) r.pushInvalid() else r.push(q); last[i % 300] = q
        }
        val v = last.filterNotNull()
        assertEquals((v.sumOf { it.toLong() }.toDouble() / v.size) / 1000.0, r.value())
    }
}
