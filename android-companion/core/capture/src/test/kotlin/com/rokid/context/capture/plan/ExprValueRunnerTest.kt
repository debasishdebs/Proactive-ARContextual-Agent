package com.rokid.context.capture.plan

import com.rokid.context.core.model.context.ContextCapturePolicy
import com.rokid.context.core.model.context.ContextPolicies
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
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** 3c E2a: value programs tune the cap and the gaps of the editable rows, clamped; the row default stands on error or while warming. */
class ExprValueRunnerTest {
    private fun value(id: String, row: String, field: String, s: String, out: Int): JsonObject =
        Json.parseToJsonElement("""{"id":"$id","role":"value","row":"$row","field":"$field","out":$out,"s":[$s]}""").jsonObject

    private fun const(v: String) = """{"op":"const","v":$v}"""
    private fun constValue(row: String, field: String, v: String, id: String = "v") = value(id, row, field, const(v), 0)
    private val feats = SecondFeatures(0f, 0f, 0f, 0f, 0f, 2f, 0f)
    private fun sig(at: Long = 1_000) = ExprSignals().also { it.set(feats, at) }

    private val rest = ContextPolicies.DEFAULT.getValue(ContextState.REST)

    private fun tuned(r: ExprRunner, state: ContextState = ContextState.REST, now: Long = 2_000, s: ExprSignals = sig()): ContextCapturePolicy {
        r.tickValues(now, s, state, 0)
        return r.tune(state, ContextPolicies.DEFAULT.getValue(state))
    }

    @Test fun aValueOverridesTheCapOfItsRow() {
        val r = ExprRunner.of(listOf(constValue("REST", "capPerMin", "3")))!!
        assertEquals(3, tuned(r).capPerMin)
        assertEquals(rest.copy(capPerMin = 3), tuned(r))
    }

    @Test fun aValueOverridesAGapInSeconds() {
        val r = ExprRunner.of(listOf(constValue("REST", "quietGapS", "42.5")))!!
        val p = tuned(r)
        assertEquals(42_500L, p.minQuietCheckMs)
        assertEquals(rest.minSettledCheckMs, p.minSettledCheckMs, "only the named field changes")
        assertEquals(rest.copy(minQuietCheckMs = 42_500L), p)
    }

    @Test fun ValueClampedToBoundsTest() {
        for ((v, cap) in listOf("0" to 1, "-5" to 1, "1.4" to 1, "2.6" to 3, "4" to 4, "99" to 4, "1000000" to 4)) {
            assertEquals(cap, tuned(ExprRunner.of(listOf(constValue("FLIGHT", "capPerMin", v)))!!, ContextState.FLIGHT).capPerMin, "cap $v")
        }
        for ((v, ms) in listOf("0" to 5_000L, "1" to 5_000L, "4.9996" to 5_000L, "5" to 5_000L, "120" to 120_000L, "300" to 300_000L, "301" to 300_000L, "86400" to 300_000L, "-3" to 5_000L)) {
            assertEquals(ms, tuned(ExprRunner.of(listOf(constValue("REST", "walkGapS", v)))!!).minWalkCheckMs, "gap $v")
        }
    }

    @Test fun WalkingAndUnknownRowsAreNeverTuned() {
        val r = ExprRunner.of(listOf(constValue("REST", "capPerMin", "4"), constValue("FLIGHT", "settledGapS", "60", id = "w")))!!
        r.tickValues(2_000, sig(), ContextState.WALKING, 0)
        for (s in listOf(ContextState.WALKING, ContextState.UNKNOWN)) {
            val base = ContextPolicies.DEFAULT.getValue(s)
            assertSame(base, r.tune(s, base), "$s: the very same row, untouched")
        }
        // and a program cannot even name them
        assertNull(ExprRunner.of(listOf(constValue("WALKING", "capPerMin", "1"))))
        assertNull(ExprRunner.of(listOf(constValue("UNKNOWN", "capPerMin", "1"))))
        assertNull(ExprRunner.of(listOf(constValue("REST", "pause", "1"))))
        assertNull(ExprRunner.of(listOf(constValue("REST", "forceSettle", "1"))))
    }

    @Test fun ValueErrorUsesDefaultTest() {
        val div0 = value("e", "REST", "capPerMin", """${const("1")},${const("0")},{"op":"arith","f":"/","a":0,"b":1}""", 2)
        val r = ExprRunner.of(listOf(div0))!!
        assertEquals(rest, tuned(r))
        assertEquals(1, r.exprDiv0); assertEquals(0, r.errTotal, "div0 never counts toward auto-revert")
        val nonFinite = value("n", "REST", "capPerMin", """${const("1.0E308")},${const("10")},{"op":"arith","f":"*","a":0,"b":1}""", 2)
        val n = ExprRunner.of(listOf(nonFinite))!!
        assertEquals(rest, tuned(n)); assertEquals(1, n.errTotal)
    }

    @Test fun anErrorAfterATuneRestoresTheDefault() {
        // 1 / (gyroMax - 0.5): a finite number while gyroMax != 0.5, a div0 error at exactly 0.5
        val e = value("e", "REST", "capPerMin", """{"op":"sig","name":"gyroMax"},${const("0.5")},{"op":"arith","f":"-","a":0,"b":1},${const("1")},{"op":"arith","f":"/","a":3,"b":2}""", 4)
        val r = ExprRunner.of(listOf(e))!!
        val s = ExprSignals()
        s.set(SecondFeatures(2.5f, 0f, 0f, 0f, 0f, 0f, 0f), 1_000)
        assertEquals(1, tuned(r, now = 2_000, s = s).capPerMin, "1 / 2 = 0.5 clamps to 1")
        s.set(SecondFeatures(0.5f, 0f, 0f, 0f, 0f, 0f, 0f), 2_000)
        assertEquals(rest.capPerMin, tuned(r, now = 3_000, s = s).capPerMin, "div0: back to the row's own value")
    }

    @Test fun unavailableSignalKeepsTheDefaultAndIsNotAnError() {
        val v = value("u", "REST", "capPerMin", """{"op":"sig","name":"gyroMax"}""", 0)
        val r = ExprRunner.of(listOf(v))!!
        assertEquals(rest, tuned(r, s = ExprSignals()), "no feature yet")
        assertEquals(0, r.exprErr)
    }

    @Test fun aWarmingWindowKeepsTheRowDefaultThenTunes() {
        val v = value("w", "REST", "capPerMin", """{"op":"win","name":"stepHz","agg":"mean","sec":3}""", 0)
        val r = ExprRunner.of(listOf(v))!!
        val s = ExprSignals(); r.bind(s)
        for (t in 1..2) { s.set(feats, t * 1_000L); assertEquals(rest, tuned(r, now = t * 1_000L + 500, s = s), "second $t of 3: warming") }
        s.set(feats, 3_000)
        assertEquals(2, tuned(r, now = 3_500, s = s).capPerMin, "mean stepHz = 2 over three seconds")
    }

    @Test fun twoValuesTuneTwoFieldsOfTwoRows() {
        val r = ExprRunner.of(listOf(constValue("REST", "capPerMin", "2", id = "a"), constValue("FLIGHT", "stillCapGapS", "90", id = "b")))!!
        r.tickValues(2_000, sig(), ContextState.REST, 0)
        assertEquals(2, r.tune(ContextState.REST, rest).capPerMin); assertEquals(rest.minStillCapMs, r.tune(ContextState.REST, rest).minStillCapMs)
        val fl = ContextPolicies.DEFAULT.getValue(ContextState.FLIGHT)
        assertEquals(90_000L, r.tune(ContextState.FLIGHT, fl).minStillCapMs); assertEquals(fl.capPerMin, r.tune(ContextState.FLIGHT, fl).capPerMin)
        assertEquals(listOf("a", "b"), r.valueIds)
    }

    @Test fun valuesRunAtMostOncePerSecondAndAllocateNothing() {
        val r = ExprRunner.of(listOf(constValue("REST", "capPerMin", "3")))!!
        val s = sig()
        r.tickValues(2_000, s, ContextState.REST, 0); assertEquals(1, r.tunedTicks(0))
        r.tickValues(2_400, s, ContextState.REST, 0); assertEquals(1, r.tunedTicks(0), "inside the second")
        r.tickValues(3_000, s, ContextState.REST, 0); assertEquals(2, r.tunedTicks(0))
        val base = ContextPolicies.DEFAULT.getValue(ContextState.WALKING)
        var t = 3_000L
        repeat(10_000) { t += 1_000; r.tickValues(t, s, ContextState.REST, 0) }
        val bytes = AllocProbe.bytes { repeat(50_000) { t += 1_000; r.tickValues(t, s, ContextState.REST, 0); r.tune(ContextState.WALKING, base) } }
        assertTrue(bytes < 8_192, "allocated $bytes bytes")
    }

    @Test fun aValueRunnerAloneNeverDropsAGlance() {
        val r = ExprRunner.of(listOf(constValue("REST", "capPerMin", "1")))!!
        assertTrue(r.keep(2_000, sig(), ContextState.REST, 0)); assertEquals(0, r.guardIds.size)
        assertFalse(r.guardIds.contains("v"))
        assertNotNull(ExprRunner.of(listOf(constValue("REST", "capPerMin", "1"), Json.parseToJsonElement("""{"id":"g","role":"guard","out":0,"s":[${const("false")}]}""").jsonObject)))
    }

    @Test fun planValidatorRefusesLockedAndDuplicateTargets() {
        fun problems(vararg v: JsonObject): List<String> {
            val c = PlanResolver.identity().copy(exprs = v.toList(), sigSchema = SIG_SCHEMA_WIN)
            return PlanValidator.problems(PlanJson.toJson(CapturePlan(planSchemaFor(c), PlanJson.configHash(c), c, PlanMeta("t", 1))), null, 600)
        }
        assertEquals(emptyList(), problems(constValue("REST", "capPerMin", "2")))
        assertTrue(problems(constValue("WALKING", "capPerMin", "2")).any { "policy row WALKING is locked" in it })
        assertTrue(problems(constValue("UNKNOWN", "quietGapS", "20")).any { "policy row UNKNOWN is locked" in it })
        assertTrue(problems(constValue("REST", "pause", "1")).any { "value field must be one of" in it })
        assertTrue(problems(constValue("REST", "capPerMin", "2", id = "a"), constValue("REST", "capPerMin", "3", id = "b")).any { "tuned by another value program" in it })
    }

    @Test fun valuePlansNeedCatalogue2AndOldRunnersRefuseThem() {
        val c = PlanResolver.identity().copy(exprs = listOf(constValue("REST", "capPerMin", "2")), sigSchema = SIG_SCHEMA)
        val p = CapturePlan(planSchemaFor(c), PlanJson.configHash(c), c, PlanMeta("t", 1))
        assertTrue(PlanValidator.check(PlanJson.encode(p)).first.any { it == "sigSchema must be 2" })
        assertTrue(ExprWindows.needsSig2(c.exprs))
    }
}

/** The glasses enforce the compile-time program limits themselves (the phone compiler is not trusted). */
class ExprLimitsOnTheGlassesTest {
    private fun consts(n: Int) = (0 until n).joinToString(",") { """{"op":"const","v":$it}""" }
    private fun prog(role: String, id: String, steps: Int, extra: String = ""): JsonObject =
        Json.parseToJsonElement("""{"id":"$id","role":"$role",${extra}"out":0,"s":[${consts(steps)}]}""").jsonObject
    private fun value(steps: Int, id: String = "v") = prog("value", id, steps, """"row":"REST","field":"capPerMin",""")
    private fun guard(steps: Int, id: String = "g") = prog("guard", id, steps)
    private fun check(vararg e: JsonObject): List<String> {
        val c = PlanResolver.identity().copy(exprs = e.toList(), sigSchema = SIG_SCHEMA_WIN)
        val p = CapturePlan(planSchemaFor(c), PlanJson.configHash(c), c, PlanMeta("t", 1))
        return PlanValidator.check(PlanJson.encode(p)).first
    }

    @Test fun anOverLimitValueProgramIsRefusedWithABoundsReason() {
        assertEquals(emptyList(), check(value(32)))
        assertTrue(check(value(200)).any { "nodes in all (max 32)" in it }, check(value(200)).toString())
        assertTrue(check(value(200)).any { "evaluation cost 200 (max 64)" in it })
        // 33 nodes of cost 1 is over the node limit alone; cost 64 is reached by 32 two-cost nodes
        assertTrue(check(value(33)).any { "33 nodes" in it })
        val twoCost = Json.parseToJsonElement("""{"id":"c","role":"guard","out":0,"s":[{"op":"const","v":1},${(1 until 32).joinToString(",") { """{"op":"clamp","a":0,"lo":0,"hi":1}""" }}]}""").jsonObject
        assertEquals(emptyList<String>(), check(twoCost).filter { "cost" in it }, "cost 1 + 31 * 2 = 63 is allowed")
        // 32 nodes, so the node limit holds, but `in` over six states costs 7 each: 1 + 31 * 7 = 218
        val over = Json.parseToJsonElement("""{"id":"c","role":"guard","out":0,"s":[{"op":"const","v":1},${(1 until 32).joinToString(",") { """{"op":"in","a":0,"set":["REST","FLIGHT","WALKING","UNKNOWN","VEHICLE_DRIVING","VEHICLE_PASSENGER"]}""" }}]}""").jsonObject
        assertTrue(check(over).any { "evaluation cost 218 (max 64)" in it } && check(over).none { "nodes in all" in it }, check(over).toString())
        assertTrue(check(guard(40)).any { "nodes in all" in it }, "guards too")
    }

    @Test fun theNodeLimitIsOverAllPrograms() {
        assertEquals(emptyList(), check(guard(16, "a"), value(16, "b")))
        assertTrue(check(guard(17, "a"), value(16, "b")).any { "33 nodes in all (max 32)" in it })
    }

    @Test fun theReceiverRejectsAnOverLimitPlanAsBounds() {
        val c = PlanResolver.identity().copy(exprs = listOf(value(200)), sigSchema = SIG_SCHEMA_WIN)
        val p = CapturePlan(planSchemaFor(c), PlanJson.configHash(c), c, PlanMeta("t", 1))
        val msg = com.rokid.context.core.model.CapturePlanMessage(gen = 1, mode = com.rokid.context.core.model.PlanMode.SHADOW, configHash = p.configHash, planJson = PlanJson.encode(p), phoneInstall = "p1", atMs = 5)
        class Mem : PlanPersistence { var s: ReceiverState? = null; override fun load() = s; override fun commit(s: ReceiverState) { this.s = s } }
        val a = PlanReceiver(Mem(), { 1L }, { "b" }, caps = PlanReceiver.CAPS_EXPR).onMessage(msg)
        assertEquals(com.rokid.context.core.model.PlanAckState.REJECTED, a.state); assertEquals("bounds", a.reason)
    }
}
