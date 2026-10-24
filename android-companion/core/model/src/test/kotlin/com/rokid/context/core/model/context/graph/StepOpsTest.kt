package com.rokid.context.core.model.context.graph

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StepOpsTest {
    private fun num(v: Double, age: Long = 0, cov: Double = 1.0) = Sample().setNum(v, atMs = 1_000, ageMs = age, coverage = cov, gen = 3)
    private fun bool(v: Boolean) = Sample().setBool(v, atMs = 1_000, ageMs = 0, coverage = 1.0, gen = 3)
    private fun code(v: Int) = Sample().setEnum(v, atMs = 1_000, ageMs = 0, coverage = 1.0, gen = 3)
    private val na = Sample().setUnavailable()
    private val out = Sample()

    @Test fun cmpAllOperators() {
        val a = num(2.0); val b = num(3.0)
        assertTrue(StepOps.cmp(out, a, b, StepOps.LT).flag); assertTrue(StepOps.cmp(out, a, b, StepOps.LE).flag)
        assertFalse(StepOps.cmp(out, a, b, StepOps.GT).flag); assertFalse(StepOps.cmp(out, a, b, StepOps.GE).flag)
        assertFalse(StepOps.cmp(out, a, b, StepOps.EQ).flag)
        assertTrue(StepOps.cmp(out, b, b, StepOps.GE).flag); assertTrue(StepOps.cmp(out, b, b, StepOps.LE).flag)
        assertTrue(StepOps.cmp(out, b, b, StepOps.EQ).flag); assertFalse(StepOps.cmp(out, b, b, StepOps.GT).flag)
        assertEquals(Sample.Type.BOOL, out.type); assertTrue(out.available)
    }

    @Test fun inChecksSetMembership() {
        val set = intArrayOf(2, 5)
        assertTrue(StepOps.inSet(out, code(5), set).flag)
        assertFalse(StepOps.inSet(out, code(3), set).flag)
        assertFalse(StepOps.inSet(out, code(3), intArrayOf()).flag)
    }

    @Test fun logicTruthTables() {
        for (x in listOf(false, true)) for (y in listOf(false, true)) {
            assertEquals(x && y, StepOps.and(out, bool(x), bool(y)).flag)
            assertEquals(x || y, StepOps.or(out, bool(x), bool(y)).flag)
        }
        assertTrue(StepOps.not(out, bool(false)).flag); assertFalse(StepOps.not(out, bool(true)).flag)
    }

    @Test fun chooseAndClamp() {
        assertEquals(1.0, StepOps.choose(out, bool(true), num(1.0), num(2.0)).num)
        assertEquals(2.0, StepOps.choose(out, bool(false), num(1.0), num(2.0)).num)
        assertEquals(4.0, StepOps.clamp(out, num(9.0), 1.0, 4.0).num)
        assertEquals(1.0, StepOps.clamp(out, num(-9.0), 1.0, 4.0).num)
        assertEquals(2.5, StepOps.clamp(out, num(2.5), 1.0, 4.0).num)
    }

    @Test fun freshBoundaryIsInclusive() {
        assertTrue(StepOps.fresh(out, num(1.0, age = 3_000), 3_000).available)
        assertFalse(StepOps.fresh(out, num(1.0, age = 3_001), 3_000).available)
        assertEquals(7.0, StepOps.fresh(out, num(7.0, age = 10), 3_000).num)
    }

    @Test fun resultCarriesTheWeakestInputMetadata() {
        StepOps.cmp(out, num(1.0, age = 50, cov = 0.9), num(2.0, age = 200, cov = 0.4), StepOps.LT)
        assertEquals(200L, out.ageMs); assertEquals(0.4, out.coverage); assertEquals(3L, out.gen)
    }
}

class UnavailableDoesNotSatisfyTest {
    private val na = Sample().setUnavailable()
    private val t = Sample().setBool(true, 0, 0, 1.0, 0)
    private val n = Sample().setNum(1.0, 0, 0, 1.0, 0)
    private val e = Sample().setEnum(1, 0, 0, 1.0, 0)
    private val out = Sample()

    private fun assertNotSatisfied(s: Sample) { assertFalse(s.available); assertFalse(StepOps.satisfied(s)) }

    @Test fun everyOpPropagatesUnavailableAndNeverSatisfies() {
        assertNotSatisfied(StepOps.cmp(out, na, n, StepOps.LT)); assertNotSatisfied(StepOps.cmp(out, n, na, StepOps.GT))
        assertNotSatisfied(StepOps.inSet(out, na, intArrayOf(1)))
        assertNotSatisfied(StepOps.and(out, na, t)); assertNotSatisfied(StepOps.and(out, t, na))
        assertNotSatisfied(StepOps.or(out, na, t)); assertNotSatisfied(StepOps.or(out, t, na))
        assertNotSatisfied(StepOps.not(out, na))   // not(unavailable) must NOT become "true"
        assertNotSatisfied(StepOps.choose(out, na, n, n)); assertNotSatisfied(StepOps.choose(out, t, na, n))
        assertNotSatisfied(StepOps.clamp(out, na, 0.0, 1.0)); assertNotSatisfied(StepOps.fresh(out, na, 1_000))
        assertTrue(StepOps.satisfied(t)); assertFalse(StepOps.satisfied(n)); assertFalse(StepOps.satisfied(e))
    }

    @Test fun staleSampleBecomesUnavailable() {
        val stale = Sample().setBool(true, 0, ageMs = 9_999, coverage = 1.0, gen = 0)
        assertNotSatisfied(StepOps.fresh(out, stale, 3_000))
    }
}

class NoAllocationTest {
    @Test fun stepOpsDoNotAllocateOverManyTicks() {
        val a = Sample().setNum(1.5, 0, 0, 1.0, 0); val b = Sample().setNum(2.5, 0, 0, 1.0, 0)
        val p = Sample().setBool(true, 0, 0, 1.0, 0); val q = Sample().setBool(false, 0, 0, 1.0, 0)
        val e = Sample().setEnum(2, 0, 0, 1.0, 0)
        val set = intArrayOf(1, 2); val o1 = Sample(); val o2 = Sample(); val o3 = Sample()
        fun tick() {
            StepOps.cmp(o1, a, b, StepOps.LT); StepOps.inSet(o2, e, set); StepOps.and(o3, o1, o2)
            StepOps.or(o3, o3, q); StepOps.not(o3, o3); StepOps.choose(o1, p, a, b); StepOps.clamp(o2, o1, 0.0, 2.0); StepOps.fresh(o1, o2, 1_000)
        }
        repeat(20_000) { tick() }   // warm up the JIT
        val bytes = AllocProbe.bytes { repeat(100_000) { tick() } }
        assertTrue(bytes < 8_192, "allocated $bytes bytes over 100000 ticks")
    }
}
