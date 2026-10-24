package com.rokid.context.capture.plan

import com.rokid.context.core.model.context.ContextState
import com.rokid.context.glasses.capture.motion.model.SecondFeatures
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 3c E2a X6: window terms read by the glasses runner at its one-second tick. */
class ExprWindowRunnerTest {
    private fun entry(id: String, s: String, out: Int): JsonObject =
        Json.parseToJsonElement("""{"id":"$id","role":"guard","out":$out,"s":[$s]}""").jsonObject

    private fun win(name: String, agg: String, sec: Int, t: String? = null) =
        """{"op":"win","name":"$name","agg":"$agg","sec":$sec${if (t != null) ""","t":$t""" else ""}}"""

    /** keep while `<agg>(stepHz, sec) > limit`, i.e. DROP when the window mean is at or below the limit. */
    private fun guard(agg: String, sec: Int, limit: String, name: String = "stepHz", id: String = "w", t: String? = null) =
        entry(id, "${win(name, agg, sec, t)},{\"op\":\"const\",\"v\":$limit},{\"op\":\"cmp\",\"f\":\">\",\"a\":0,\"b\":1}", 2)

    private fun feats(stepHz: Float = 0f, gyroMax: Float = 0f) = SecondFeatures(gyroMax, 0f, 0f, 0f, 0f, stepHz, 0f)
    private val ctx = ContextState.UNKNOWN

    /** One second of feed at [t] seconds, then a guard evaluation half a second later (the runner is read at its own tick). */
    private fun step(r: ExprRunner, s: ExprSignals, t: Int, stepHz: Float, gyroMax: Float = 0f): Boolean {
        s.set(feats(stepHz, gyroMax), t * 1000L)
        r.tick(s)
        return r.keep(t * 1000L + 500, s, ctx, 0)
    }

    @Test fun WindowWarmupFallbackSafe() {
        val r = ExprRunner.of(listOf(guard("mean", 3, "1.0")))!!
        val s = ExprSignals(); r.bind(s)
        // a window of 0.0 would DROP (0 > 1 is false) once full; while it warms the answer is UNAVAILABLE and a guard keeps
        assertTrue(step(r, s, 1, 0f), "1 of 3 s: warming keeps")
        assertTrue(step(r, s, 2, 0f), "2 of 3 s: warming keeps")
        assertFalse(step(r, s, 3, 0f), "3 of 3 s: the full window answers and drops")
        assertEquals(0, r.exprErr)
        assertEquals(1, r.drops(0))
    }

    @Test fun windowMeanMaxAndFracAnswerOverTheLastSeconds() {
        val sMean = ExprSignals(); val mean = ExprRunner.of(listOf(guard("mean", 3, "1.9")))!!; mean.bind(sMean)
        val sMax = ExprSignals(); val max = ExprRunner.of(listOf(guard("max", 3, "2.5")))!!; max.bind(sMax)
        val sFrac = ExprSignals(); val frac = ExprRunner.of(listOf(guard("frac", 4, "0.6", t = "1.0")))!!; frac.bind(sFrac)
        val hz = floatArrayOf(1f, 2f, 3f, 0f, 4f)   // five seconds
        var m = true; var x = true; var f = true
        for (i in hz.indices) { m = step(mean, sMean, i + 1, hz[i]); x = step(max, sMax, i + 1, hz[i]); f = step(frac, sFrac, i + 1, hz[i]) }
        assertTrue(m, "mean of the last 3 s (3, 0, 4) is 2.33 > 1.9: keep")
        assertTrue(x, "max of the last 3 s is 4 > 2.5: keep")
        assertTrue(f, "frac(stepHz > 1) over the last 4 s (2, 3, 0, 4 -> 3/4) is 0.75 > 0.6: keep")
    }

    @Test fun windowThresholdFracDropsWhenTheShareIsLow() {
        val s = ExprSignals(); val r = ExprRunner.of(listOf(guard("frac", 4, "0.6", t = "1.0")))!!; r.bind(s)
        val hz = floatArrayOf(2f, 0f, 0f, 3f)   // 2 of 4 above 1.0 = 0.5
        var k = true
        for (i in hz.indices) k = step(r, s, i + 1, hz[i])
        assertFalse(k, "0.5 > 0.6 is false: dropped")
    }

    @Test fun WindowGapInvalidNotZero() {
        val s = ExprSignals(); val r = ExprRunner.of(listOf(guard("mean", 3, "1.0")))!!; r.bind(s)
        for (t in 1..3) assertEquals(t < 3, step(r, s, t, 0f))   // the third second fills the window and it drops
        // the sensor feed stalls for 6 s: three missing seconds are INVALID slots, not zeros and not repeats
        assertTrue(step(r, s, 10, 0f), "a window holding a gap answers nothing: keep")
        assertTrue(step(r, s, 11, 0f), "slots 9..11: 9 is still a gap")
        assertFalse(step(r, s, 12, 0f), "slots 10..12 are three valid seconds in a row again")
    }

    @Test fun WindowResetOnApply() {
        val s = ExprSignals()
        for (t in 1..10) s.set(feats(0f), t * 1000L)   // ten seconds of feed before the plan is applied
        val r = ExprRunner.of(listOf(guard("mean", 3, "1.0")))!!
        r.bind(s)   // the plan apply: empty rings, no ring snapshot
        assertTrue(step(r, s, 11, 0f), "history from before the apply is not read")
        assertTrue(step(r, s, 12, 0f))
        assertFalse(step(r, s, 13, 0f), "warm after three seconds of its own")
    }

    @Test fun WindowResetOnSessionRestart() {
        val s = ExprSignals(); val r = ExprRunner.of(listOf(guard("mean", 3, "1.0")))!!; r.bind(s)
        for (t in 1..3) step(r, s, t, 0f)
        assertFalse(step(r, s, 4, 0f))
        s.reset()   // the capture session restarts
        assertTrue(step(r, s, 5, 0f), "warming again after the restart"); assertTrue(step(r, s, 6, 0f))
        assertFalse(step(r, s, 7, 0f))
    }

    @Test fun staleFeaturesMakeTheWindowUnavailable() {
        val s = ExprSignals(); val r = ExprRunner.of(listOf(guard("mean", 2, "1.0")))!!; r.bind(s)
        step(r, s, 1, 0f); assertFalse(step(r, s, 2, 0f))
        assertTrue(r.keep(9_000, s, ctx, 0), "the newest second is older than 3 s")
    }

    @Test fun NoAllocationWithWindowsOnTheTick() {
        val r = ExprRunner.of(listOf(guard("mean", 30, "1.0"), guard("max", 60, "9.0", id = "m"), guard("frac", 10, "0.5", t = "1.0", id = "f")))!!
        val s = ExprSignals(); r.bind(s)
        val f = feats(2f)
        var t = 0L
        fun second() { t += 1_000; s.set(f, t); r.tick(s); r.keep(t + 500, s, ctx, 0) }
        repeat(2_000) { second() }
        val bytes = AllocProbe.bytes { repeat(50_000) { second() } }
        assertTrue(bytes < 8_192, "allocated $bytes bytes")
    }

    @Test fun twoProgramsShareOneRingForTheSameTerm() {
        val r = ExprRunner.of(listOf(guard("mean", 3, "1.0", id = "a"), guard("mean", 3, "2.0", id = "b")))!!
        assertEquals(1, r.windowTerms); assertEquals(4 * 3, r.windowStateBytes)
    }

    @Test fun WindowReadsOnlyOneHzSignalsTest() {
        assertNull(ExprWindows.termsOf(listOf(guard("mean", 3, "1.0", name = "ctx"))))
        assertNull(ExprRunner.of(listOf(guard("mean", 3, "1.0", name = "ctxAgeS"))))
        assertNotNull(ExprRunner.of(listOf(guard("mean", 3, "1.0", name = "headSpeed"))), "the alias reads gyroMax")
        assertNull(ExprWindows.termsOf(listOf(guard("mean", 0, "1.0"))), "1 to 300 s")
        assertNull(ExprWindows.termsOf(listOf(guard("mean", 301, "1.0"))))
        assertNull(ExprWindows.termsOf(listOf(guard("median", 5, "1.0"))))
        assertNull(ExprWindows.termsOf(listOf(guard("frac", 5, "1.0"))), "frac needs a threshold")
        assertNull(ExprWindows.termsOf(listOf(guard("mean", 5, "1.0", t = "2"))), "only frac has a threshold")
    }

    @Test fun FiveWindowsRefusedTest() {
        val four = (1..4).map { guard("mean", it + 1, "1.0", id = "g$it") }
        assertEquals(4, ExprWindows.termsOf(four)!!.size)
        assertEquals(emptyList(), problemsOf(four))
        val five = four + guard("max", 9, "1.0", id = "g5")
        assertTrue(problemsOf(five).any { "5 window terms (max 4)" in it }, problemsOf(five).toString())
        // the same term twice is one term
        val dup = (1..6).map { guard("mean", 5, "1.0", id = "g$it") }
        assertEquals(1, ExprWindows.termsOf(dup)!!.size)
    }

    @Test fun WindowStateBytesCountedInThePlanCap() {
        val big = (1..3).map { guard("max", 300, "1.0", name = listOf("gyroMax", "gyroMean", "gyroRms")[it - 1], id = "g$it") }   // 3 x 2400 B of ring and deque
        assertEquals(7200, ExprWindows.stateBytes(ExprWindows.termsOf(big)!!))
        val p = problemsOf(big, planBytes = 1_000)
        assertTrue(p.any { "bytes of window state" in it }, p.toString())
        assertEquals(emptyList(), problemsOf(big, planBytes = 992), "7200 + 992 = 8192 is accepted")
        assertTrue(problemsOf(big, planBytes = 993).isNotEmpty(), "one byte over")
    }

    @Test fun sigSchemaTwoIsRequiredForWindowsAndOnlyThen() {
        val w = listOf(guard("mean", 3, "1.0"))
        val plain = listOf(entry("p", """{"op":"const","v":true}""", 0))
        assertTrue(ExprWindows.needsSig2(w)); assertFalse(ExprWindows.needsSig2(plain))
        val cfg = PlanResolver.identity()
        fun check(exprs: List<JsonObject>, sig: Int): List<String> {
            val c = cfg.copy(exprs = exprs, sigSchema = sig)
            val p = CapturePlan(planSchemaFor(c), PlanJson.configHash(c), c, PlanMeta("t", 1))
            return PlanValidator.check(PlanJson.encode(p)).first
        }
        assertEquals(emptyList(), check(w, SIG_SCHEMA_WIN))
        assertTrue(check(w, SIG_SCHEMA).any { it == "sigSchema must be 2" })
        assertEquals(emptyList(), check(plain, SIG_SCHEMA))
        assertTrue(check(plain, SIG_SCHEMA_WIN).any { it == "sigSchema must be 1" })
    }

    @Test fun OldGlassesRefuseWindowPlans() {
        val c = PlanResolver.identity().copy(exprs = listOf(guard("mean", 3, "1.0")), sigSchema = SIG_SCHEMA_WIN)
        val p = CapturePlan(planSchemaFor(c), PlanJson.configHash(c), c, PlanMeta("t", 1))
        val msg = com.rokid.context.core.model.CapturePlanMessage(gen = 1, mode = com.rokid.context.core.model.PlanMode.SHADOW, configHash = p.configHash, planJson = PlanJson.encode(p), phoneInstall = "p1", atMs = 5)
        class Mem : PlanPersistence { var s: ReceiverState? = null; override fun load() = s; override fun commit(s: ReceiverState) { this.s = s } }
        val e1only = PlanReceiver(Mem(), { 1L }, { "b" }, caps = PlanReceiver.CAPS + listOf(PlanReceiver.CAP_EXPR, PlanReceiver.CAP_SIG1))
        val a = e1only.onMessage(msg)
        assertEquals(com.rokid.context.core.model.PlanAckState.REJECTED, a.state); assertEquals("unsupported:sig2", a.reason)
        assertEquals(com.rokid.context.core.model.PlanAckState.STAGED, PlanReceiver(Mem(), { 1L }, { "b" }, caps = PlanReceiver.CAPS_EXPR).onMessage(msg).state)
    }

    private fun problemsOf(exprs: List<JsonObject>, planBytes: Int = 500): List<String> {
        val c = PlanResolver.identity().copy(exprs = exprs, sigSchema = SIG_SCHEMA_WIN)
        val raw = PlanJson.toJson(CapturePlan(planSchemaFor(c), PlanJson.configHash(c), c, PlanMeta("t", 1)))
        return PlanValidator.problems(raw, null, planBytes)
    }
}
