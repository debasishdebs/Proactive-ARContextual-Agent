package com.rokid.context.ai.canvas.plan

import com.rokid.context.ai.canvas.plan.ExprTestSupport.graph
import com.rokid.context.ai.canvas.plan.ExprTestSupport.guardNode
import com.rokid.context.ai.canvas.plan.ExprTestSupport.pipeline
import com.rokid.context.ai.canvas.plan.ExprTestSupport.step
import com.rokid.context.capture.plan.PlanJson
import com.rokid.context.capture.plan.PlanValidator
import com.rokid.context.capture.plan.SIG_SCHEMA
import com.rokid.context.capture.plan.SIG_SCHEMA_WIN
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 3c E2a X6: window terms in expression programs (lowering, validation, the plan carriage). */
class ExprWindowPlanTest {
    /** One step: `<agg>(signal, sec) > limit`. */
    private fun winStep(name: String = "calm", signal: String = "stepHz", agg: String = "mean", sec: Int = 10, limit: String = "1.2", t: String? = null) =
        step(name, "", """{"id":"w","op":"win","name":"$signal","agg":"$agg","sec":$sec${if (t != null) ""","t":$t""" else ""}},{"id":"k","op":"const","value":$limit},{"id":"c","op":"cmp","f":">"}""",
            """["w","c.a"],["k","c.b"]""", "c")

    private fun g(signal: String = "stepHz", agg: String = "mean", sec: Int = 10, t: String? = null, limit: String = "1.2") =
        graph(winStep("calm", signal, agg, sec, limit, t), out = "calm")

    private fun compile(vararg graphs: String) =
        PlanCompiler.compile(pipeline(*graphs.mapIndexed { i, s -> guardNode("g${i + 1}", s) }.toTypedArray()))

    @Test fun aWindowGuardLowersToAWinEntryAndSchema2Catalogue2() {
        val r = compile(g())
        assertEquals(r.problems.toString(), emptyList<String>(), r.problems)
        val plan = r.plan!!
        assertEquals(2, plan.planSchema); assertEquals(SIG_SCHEMA_WIN, plan.config.sigSchema)
        val s = (plan.config.exprs.single()["s"] as JsonArray).map { it as JsonObject }
        val w = s.single { (it["op"] as JsonPrimitive).content == "win" }
        assertEquals("""{"op":"win","name":"stepHz","agg":"mean","sec":10}""", w.toString())
        assertEquals(emptyList<String>(), PlanValidator.check(r.planJson!!).first)
        assertEquals(plan.configHash, PlanJson.configHash(PlanJson.decodeStrict(r.planJson!!).getOrThrow().config))
    }

    @Test fun fracCarriesItsThreshold() {
        val r = compile(g(agg = "frac", t = "0.6", sec = 30))
        assertEquals(r.problems.toString(), emptyList<String>(), r.problems)
        assertTrue(r.planJson!!, r.planJson!!.contains(""""agg":"frac","name":"stepHz","op":"win","sec":30,"t":0.6"""))   // the canonical text sorts keys
    }

    @Test fun aGuardWithoutWindowsKeepsCatalogue1() {
        val r = PlanCompiler.compile(pipeline(guardNode("g1", ExprTestSupport.G_NOCTX)))
        assertEquals(SIG_SCHEMA, r.plan!!.config.sigSchema)
        assertTrue(!r.planJson!!.contains("\"win\""))
    }

    @Test fun FiveWindowsRefusedTest() {
        val four = compile(g(sec = 2), g(sec = 3), g(sec = 4), g(sec = 5))
        assertEquals(four.problems.toString(), emptyList<String>(), four.problems)
        val five = compile(g(sec = 2), g(sec = 3), g(sec = 4), g(sec = 5), g(sec = 6))
        assertNull(five.plan)
        assertTrue(five.problems.toString(), five.problems.any { "EXPR_TOO_MANY_WINDOWS" in it && it.startsWith("node g5:") })
        // the same triple twice is one term, however many programs use it (6 programs is the program cap)
        val same = compile(*Array(6) { g(sec = 7, limit = "1.$it") })
        assertEquals(same.problems.toString(), emptyList<String>(), same.problems)
        // a different aggregate, signal or horizon is a different term
        val fifth = compile(g(sec = 2), g(sec = 2, agg = "max"), g(sec = 2, signal = "gyroMax"), g(sec = 3), g(sec = 2, agg = "frac", t = "1"))
        assertTrue(fifth.problems.any { "EXPR_TOO_MANY_WINDOWS" in it })
    }

    @Test fun WindowReadsOnlyOneHzSignalsTest() {
        fun code(g: String) = ExprTestSupport.codes(g)
        assertEquals(listOf(ExprCode.EXPR_WINDOW_SIGNAL), code(g(signal = "ctx")))
        assertEquals(listOf(ExprCode.EXPR_WINDOW_SIGNAL), code(g(signal = "ctxAgeS")))
        assertEquals(listOf(ExprCode.EXPR_UNKNOWN_SIGNAL), code(g(signal = "nope")))
        assertEquals(emptyList<ExprCode>(), code(g(signal = "headSpeed")))   // the alias reads gyroMax
        assertEquals(listOf(ExprCode.EXPR_BAD_PARAM), code(g(sec = 0)))
        assertEquals(listOf(ExprCode.EXPR_BAD_PARAM), code(g(sec = 301)))
        assertEquals(emptyList<ExprCode>(), code(g(sec = 300)))
        assertEquals(listOf(ExprCode.EXPR_BAD_PARAM), code(g(agg = "median")))
        assertEquals("frac needs a threshold", listOf(ExprCode.EXPR_BAD_PARAM), code(g(agg = "frac")))
        assertEquals("only frac takes one", listOf(ExprCode.EXPR_BAD_PARAM), code(g(agg = "mean", t = "1")))
    }

    @Test fun aWindowIsANumberSoItCannotBeAGuardOutput() {
        val s = step("w", "", """{"id":"w","op":"win","name":"stepHz","agg":"mean","sec":5}""", "", "w")
        assertEquals(listOf(ExprCode.EXPR_GUARD_NOT_BOOL), ExprTestSupport.codes(graph(s, out = "w")))
    }

    @Test fun WindowStateBytesCountedInThePlanCap() {
        // four max windows of 300 s hold 4 x 2400 bytes of ring and deque: the plan itself is small but the state does not fit 8192
        val r = compile(g(signal = "gyroMax", agg = "max", sec = 300), g(signal = "gyroMean", agg = "max", sec = 300),
            g(signal = "gyroRms", agg = "max", sec = 300), g(signal = "stepHz", agg = "max", sec = 300))
        assertNull(r.plan)
        assertTrue(r.problems.toString(), r.problems.any { "EXPR_BYTES" in it && "9600 bytes of window state" in it })
        // the third at 7200 does not fit either (the plan is about 1.6 KB); two max windows and a mean (6000) do
        val three7200 = compile(g(signal = "gyroMax", agg = "max", sec = 300), g(signal = "gyroMean", agg = "max", sec = 300), g(signal = "gyroRms", agg = "max", sec = 300))
        assertNull(three7200.plan)
        val three = compile(g(signal = "gyroMax", agg = "max", sec = 300), g(signal = "gyroMean", agg = "max", sec = 300), g(signal = "gyroRms", agg = "mean", sec = 300))
        assertEquals(three.problems.toString(), emptyList<String>(), three.problems.map { it.take(160) })
        assertNotNull(three.plan)
        // the lowering reports the figure the plan check adds
        val lowered = ExprLowering.lower(listOf("a" to ExprTestSupport.json(g(sec = 300, agg = "mean"))))
        assertEquals(1, lowered.windowTerms); assertEquals(1200, lowered.stateBytes)
    }
}
