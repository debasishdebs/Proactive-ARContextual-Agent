package com.rokid.context.core.model.context.graph

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun n(v: Double) = Sample().setNum(v, 0, 0, 1.0, 0)
private fun b(v: Boolean) = Sample().setBool(v, 0, 0, 1.0, 0)
private fun err() = Sample().setError(Sample.ERR_DIV0)

class ArithBasicsTest {
    private val out = Sample()
    @Test fun fourOperations() {
        assertEquals(5.0, StepOps.arith(out, n(2.0), n(3.0), StepOps.ADD).num)
        assertEquals(-1.0, StepOps.arith(out, n(2.0), n(3.0), StepOps.SUB).num)
        assertEquals(6.0, StepOps.arith(out, n(2.0), n(3.0), StepOps.MUL).num)
        assertEquals(0.5, StepOps.arith(out, n(1.0), n(2.0), StepOps.DIV).num)
        assertTrue(out.available); assertFalse(out.error)
    }
    @Test fun unavailableStaysUnavailableNotError() {
        val r = StepOps.arith(out, Sample().setUnavailable(), n(1.0), StepOps.ADD)
        assertFalse(r.available); assertFalse(r.error)
    }
}

class DivZeroIsErrorTest {
    private val out = Sample()
    @Test fun divisionByZeroIsAnErrorNotZero() {
        for (z in listOf(0.0, -0.0)) {
            val r = StepOps.arith(out, n(5.0), n(z), StepOps.DIV)
            assertTrue(r.error); assertEquals(Sample.ERR_DIV0, r.errorKind); assertFalse(r.available)
        }
        val z = StepOps.arith(out, n(0.0), n(0.0), StepOps.DIV)
        assertTrue(z.error && z.errorKind == Sample.ERR_DIV0)
    }
}

class ArithFiniteOnlyTest {
    private val out = Sample()
    @Test fun nanAndInfinityInputsBecomeError() {
        for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            for (op in listOf(StepOps.ADD, StepOps.SUB, StepOps.MUL, StepOps.DIV)) {
                val l = StepOps.arith(out, n(bad), n(1.0), op); assertTrue(l.error && l.errorKind == Sample.ERR_NONFINITE, "left $bad op $op")
                val r = StepOps.arith(out, n(1.0), n(bad), op); assertTrue(r.error && r.errorKind == Sample.ERR_NONFINITE, "right $bad op $op")
            }
        }
    }
    @Test fun overflowIsAnError() {
        val r = StepOps.arith(out, n(Double.MAX_VALUE), n(2.0), StepOps.MUL)
        assertTrue(r.error && r.errorKind == Sample.ERR_NONFINITE)
    }
}

class ErrorPropagatesThroughLogicTest {
    private val out = Sample()
    @Test fun everyOpPropagatesTheErrorAndItsKind() {
        val e = err(); val t = b(true); val x = n(1.0)
        val results = listOf(
            StepOps.cmp(out, e, x, StepOps.LT).copy(), StepOps.cmp(out, x, e, StepOps.LT).copy(), StepOps.inSet(out, e, intArrayOf(1)).copy(),
            StepOps.and(out, e, t).copy(), StepOps.or(out, t, e).copy(), StepOps.not(out, e).copy(), StepOps.choose(out, e, x, x).copy(),
            StepOps.choose(out, t, e, x).copy(), StepOps.clamp(out, e, 0.0, 1.0).copy(), StepOps.fresh(out, e, 100).copy(),
            StepOps.arith(out, e, x, StepOps.ADD).copy(), StepOps.arith(out, x, e, StepOps.MUL).copy(),
        )
        for ((i, r) in results.withIndex()) assertTrue(r.error && r.errorKind == Sample.ERR_DIV0 && !r.available, "op #$i")
    }
    @Test fun errorDominatesUnavailable() {
        val r = StepOps.and(out, Sample().setUnavailable(), err())
        assertTrue(r.error)
    }
    private fun Sample.copy() = Sample().copyFrom(this)
}

class ErrorNeverSatisfiesTest {
    @Test fun anErrorIsNotSatisfied() {
        assertFalse(StepOps.satisfied(err()))
        val out = Sample()
        assertFalse(StepOps.satisfied(StepOps.not(out, err())))
        assertFalse(StepOps.satisfied(StepOps.or(out, b(true), err())))   // true or ERROR is an ERROR, not a quiet true
    }
}

class ArithNoAllocationTest {
    @Test fun arithAndErrorPathsDoNotAllocate() {
        val a = n(3.0); val z = n(0.0); val nan = n(Double.NaN); val o1 = Sample(); val o2 = Sample(); val o3 = Sample()
        fun tick() {
            StepOps.arith(o1, a, z, StepOps.DIV); StepOps.arith(o2, a, nan, StepOps.ADD); StepOps.arith(o3, a, a, StepOps.MUL)
            StepOps.and(o1, o1, o2); StepOps.clamp(o2, o3, 0.0, 1.0)
        }
        repeat(20_000) { tick() }
        val bytes = AllocProbe.bytes { repeat(100_000) { tick() } }
        assertTrue(bytes < 8_192, "allocated $bytes bytes")
    }
}
