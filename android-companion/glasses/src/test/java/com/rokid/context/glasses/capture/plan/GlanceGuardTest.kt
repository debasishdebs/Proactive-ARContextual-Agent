package com.rokid.context.glasses.capture.plan

import com.rokid.context.capture.plan.ExprRunner
import com.rokid.context.capture.plan.ExprSignals
import com.rokid.context.core.model.MotionState
import com.rokid.context.core.model.PlanMode
import com.rokid.context.core.model.context.ContextState
import com.rokid.context.core.model.context.MotionContextMessage
import com.rokid.context.glasses.capture.session.ContextGate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 3c X3: the glance guard glue (errors, auto-revert, the walking veto). */
class GlanceGuardTest {
    private fun entry(id: String, s: String, out: Int): JsonObject = Json.parseToJsonElement("""{"id":"$id","role":"guard","out":$out,"s":[$s]}""").jsonObject
    private val div0 = entry("d", """{"op":"const","v":1},{"op":"const","v":0},{"op":"arith","f":"/","a":0,"b":1},{"op":"const","v":0},{"op":"cmp","f":">","a":2,"b":3}""", 4)
    private val nonFinite = entry("n", """{"op":"const","v":1.0E308},{"op":"const","v":10},{"op":"arith","f":"*","a":0,"b":1},{"op":"const","v":0},{"op":"cmp","f":">","a":2,"b":3}""", 4)
    private val restOnly = entry("c", """{"op":"sig","name":"ctx"},{"op":"in","a":0,"set":["REST"]}""", 1)
    private val A = PlanId("p1", 1, PlanMode.TRIAL)
    private val B = PlanId("p1", 2, PlanMode.TRIAL)

    private fun cfg(vararg e: JsonObject) = ActiveConfig.SETTINGS.copy(exprRunner = ExprRunner.of(e.toList()))
    private class Rig(val cfg: ActiveConfig, val revert: AutoRevert = AutoRevert(), var running: PlanId? = null, var applied: ContextState = ContextState.UNKNOWN) {
        val guard = GlanceGuard({ cfg }, ExprSignals(), { applied }, { 0L }, revert) { running }
    }

    /** [n] glance candidates 600 ms apart (inside the 2/s limit) starting at [from]; returns the last time. */
    private fun feed(r: Rig, n: Int, from: Long = 1_000): Long { var t = from; repeat(n) { r.guard.keep(t); t += 600 }; return t }

    @Test fun noPlanOrNoGuardKeeps() {
        assertTrue(GlanceGuard({ ActiveConfig.SETTINGS }, ExprSignals(), { ContextState.UNKNOWN }, { 0 }, AutoRevert()) { null }.keep(1_000))
    }

    @Test fun Div0NotCountedTowardRevertTest() {
        val r = Rig(cfg(div0), running = A)
        val t = feed(r, 40)                                  // 40 div0 errors in 24 s: far above 10 per minute
        assertEquals(40, r.cfg.exprRunner!!.exprDiv0); assertEquals(0, r.cfg.exprRunner!!.exprErr)
        assertNull(r.revert.check(t, A), "x / 0 never auto-reverts (Q11)")
    }

    @Test fun ErrAutoRevertsTest() {
        val r = Rig(cfg(nonFinite), running = A)
        val t = feed(r, 10)                                  // exactly 10 in 6 s: not MORE than 10
        assertNull(r.revert.check(t, A))
        r.guard.keep(t)                                      // the 11th inside the minute
        assertEquals("auto:exprErr", r.revert.check(t, A))
        assertNull(r.revert.check(t, A), "fires once per breach")
    }

    @Test fun errorsOutsideOneMinuteDoNotAccumulate() {
        val r = Rig(cfg(nonFinite), running = A)
        var t = 1_000L
        repeat(30) { r.guard.keep(t); assertNull(r.revert.check(t, A), "at $t"); t += 7_000 }   // 8.5 per minute
    }

    @Test fun errorsOfAnotherPlanOrOfSettingsNeverRevert() {
        val s = Rig(cfg(nonFinite), running = null)
        val t = feed(s, 20)
        assertNull(s.revert.check(t, null)); assertNull(s.revert.check(t, A))
        val r = Rig(cfg(nonFinite), running = A)
        val t2 = feed(r, 20)
        assertNull(r.revert.check(t2, B), "A's errors do not count against B")
    }

    @Test fun guardErrorKeepsTheCandidate() {
        val r = Rig(cfg(nonFinite, div0), running = A)
        assertTrue(r.guard.keep(1_000))
        assertEquals(0, r.cfg.exprRunner!!.totalDrops)
    }

    /** S32: the guard reads the state AFTER the local walking veto (ContextGate), so a phone REST while walking is still UNKNOWN to it. */
    @Test fun WalkingVetoAfterGuardTest() {
        val gate = ContextGate()
        gate.onMessage(MotionContextMessage(state = ContextState.REST, atMs = 1, apply = true, reasons = emptyList()), 0)
        var local = MotionState.WALKING
        val rig = Rig(cfg(restOnly), running = A)
        val g = GlanceGuard({ rig.cfg }, ExprSignals(), { gate.effective(5_000, local, null, emptyList()) }, { 0 }, AutoRevert()) { A }
        assertFalse(g.keep(5_000), "walking vetoes REST: the guard sees UNKNOWN and drops")
        assertEquals(1, gate.takeVetoes())
        local = MotionState.STILL
        assertTrue(g.keep(6_000), "without the veto the guard sees REST")
    }

    @Test fun SeveralFailingProgramsEachCountTowardRevertTest() {
        val r = Rig(cfg(nonFinite, entry("n2", nonFinite["s"].toString().let { "" + it.substring(1, it.length - 1) }, 4), entry("n3", nonFinite["s"].toString().let { it.substring(1, it.length - 1) }, 4)), running = A)
        val t = feed(r, 4)                                   // 3 failing guards x 4 evaluations = 12 errors in 2.4 s
        assertEquals(12L, r.cfg.exprRunner!!.errTotal)
        assertEquals("auto:exprErr", r.revert.check(t, A))
    }

    @Test fun guardReadsAppliedContextWhateverTheBindingFlag() {
        val r = Rig(cfg(restOnly), running = A, applied = ContextState.REST)
        assertTrue(r.guard.keep(1_000))
    }
}
