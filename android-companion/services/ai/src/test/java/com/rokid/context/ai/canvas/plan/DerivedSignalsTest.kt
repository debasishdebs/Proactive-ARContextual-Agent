package com.rokid.context.ai.canvas.plan

import com.rokid.context.ai.canvas.plan.ExprTestSupport.G1
import com.rokid.context.ai.canvas.plan.ExprTestSupport.graph
import com.rokid.context.ai.canvas.plan.ExprTestSupport.json
import com.rokid.context.ai.canvas.plan.ExprTestSupport.step
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.9.7 A: user-defined derived signals (expression graphs reused as signals) and the disabled-signal list, through the SAME validator and lowering as guards. */
class DerivedSignalsTest {
    /** "mean stepHz over 10 s above 1.2" */
    private val walkyDef = """{"steps":[{"name":"s","inputs":[{"name":"k","const":1.2}],"nodes":[{"id":"w","op":"win","agg":"mean","sec":10,"name":"stepHz"},{"id":"c","op":"cmp","f":">"}],"wires":[["w","c.a"],["k","c.b"]],"out":"c"}],"out":"s"}"""
    private fun cfg(vararg d: Pair<String, String>, disabled: Set<String> = emptySet()) =
        SignalConfig(disabled, d.map { DerivedSignal(it.first, json(it.second) as JsonObject) })

    /** A guard "not walky". */
    private val useWalky = graph(step("g", """{"name":"w","sig":"d_walky"}""", """{"id":"n","op":"not"}""", """["w","n.a"]""", "n"), out = "g")

    private fun lower(g: String, c: SignalConfig) = ExprLowering.lowerAll(listOf(ExprLowering.Entry("g1", json(g))), cfg = c)

    @Test fun aDerivedSignalLowersLikeTheHandInlinedGraph() {
        val viaDerived = lower(useWalky, cfg("walky" to walkyDef))
        assertEquals(emptyList<String>(), viaDerived.problems)
        val inlined = graph(
            """{"name":"dv_walky_0","inputs":[{"name":"k","const":1.2}],"nodes":[{"id":"w","op":"win","agg":"mean","sec":10,"name":"stepHz"},{"id":"c","op":"cmp","f":">"}],"wires":[["w","c.a"],["k","c.b"]],"out":"c"}""",
            step("g", """{"name":"w","step":"dv_walky_0"}""", """{"id":"n","op":"not"}""", """["w","n.a"]""", "n"), out = "g",
        )
        assertEquals(lower(inlined, SignalConfig()).exprs, viaDerived.exprs)
        assertTrue(viaDerived.exprs.toString().contains("\"op\":\"win\""))
    }

    @Test fun withoutTheDefinitionTheNameIsAnUnknownSignal() {
        val r = lower(useWalky, SignalConfig())
        assertTrue(r.problems.toString(), r.problems.any { "EXPR_UNKNOWN_SIGNAL" in it })
    }

    @Test fun aDerivedSignalCanFeedASigNodeToo() {
        val g = graph(step("g", "", """{"id":"w","op":"sig","name":"d_walky"},{"id":"n","op":"not"}""", """["w","n.a"]""", "n"), out = "g")
        val r = lower(g, cfg("walky" to walkyDef))
        assertEquals(emptyList<String>(), r.problems)
        assertEquals(lower(useWalky, cfg("walky" to walkyDef)).exprs, r.exprs)
    }

    @Test fun theExpandedPlanStillMeetsTheNodeLimit() {
        // a 20-node chain on a constant: 22 lowered nodes per use; two guards that use it are 44 > 32
        val nots = (1..20).joinToString(",") { """{"id":"n$it","op":"not"}""" }
        val wires = (2..20).joinToString(",") { """["n${it - 1}","n$it.a"]""" } + ""","""+ """["t","n1.a"]"""
        val heavy = """{"steps":[{"name":"s","inputs":[{"name":"t","const":true}],"nodes":[$nots],"wires":[$wires],"out":"n20"}],"out":"s"}"""
        val use = graph(step("g", """{"name":"w","sig":"d_heavy"}""", """{"id":"n","op":"not"}""", """["w","n.a"]""", "n"), out = "g")
        val c = cfg("heavy" to heavy)
        assertEquals(emptyList<String>(), lower(use, c).problems)
        val two = ExprLowering.lowerAll(listOf(ExprLowering.Entry("g1", json(use)), ExprLowering.Entry("g2", json(use))), cfg = c)
        assertTrue(two.problems.toString(), two.problems.any { "EXPR_TOO_MANY_NODES" in it })
    }

    @Test fun aDisabledSignalIsRefusedInGuardsAndThroughDerivedSignals() {
        val direct = lower(G1, SignalConfig(disabled = setOf("gyroMax")))
        assertTrue(direct.problems.toString(), direct.problems.any { "EXPR_UNKNOWN_SIGNAL" in it && "disabled" in it })
        val alias = graph(step("g", """{"name":"a","sig":"headSpeed"}""", """{"id":"k","op":"const","value":1},{"id":"c","op":"cmp","f":">"}""", """["a","c.a"],["k","c.b"]""", "c"), out = "g")
        assertTrue("an alias is disabled with its signal", lower(alias, SignalConfig(disabled = setOf("gyroMax"))).problems.isNotEmpty())
        val viaDerived = lower(useWalky, cfg("walky" to walkyDef, disabled = setOf("stepHz")))
        assertTrue(viaDerived.problems.toString(), viaDerived.problems.any { "disabled" in it })
        assertEquals(emptyList<String>(), lower(G1, SignalConfig(disabled = setOf("accVar"))).problems)
    }

    @Test fun defaultConfigChangesNothing() {
        val plain = ExprLowering.lowerAll(listOf(ExprLowering.Entry("g1", json(G1))), cfg = SignalConfig())
        val none = ExprLowering.lowerAll(listOf(ExprLowering.Entry("g1", json(G1))))
        assertEquals(plain.exprs, none.exprs)
        assertEquals(plain.problems, none.problems)
    }

    @Test fun aDefinitionIsValidatedLikeAGuardOrValue() {
        assertEquals(emptyList<String>(), DerivedSignals.validate("walky", json(walkyDef), SignalConfig()))
        val numeric = """{"steps":[{"name":"s","inputs":[{"name":"x","sig":"gyroMax"}],"nodes":[{"id":"c","op":"clamp","lo":0,"hi":2}],"wires":[["x","c.a"]],"out":"c"}],"out":"s"}"""
        assertEquals("a number is a fine derived signal", emptyList<String>(), DerivedSignals.validate("speedc", json(numeric), SignalConfig()))
        assertTrue(DerivedSignals.validate("Bad Name", json(walkyDef), SignalConfig()).isNotEmpty())
        assertTrue(DerivedSignals.validate("x", json(walkyDef.replace("stepHz", "nope")), SignalConfig()).any { "EXPR_UNKNOWN_SIGNAL" in it })
        assertTrue("derived in derived is refused, with its own reason", DerivedSignals.validate("x", json(numeric.replace("gyroMax", "d_speedc")), SignalConfig()).any { "another derived signal" in it })
        val enumOut = """{"steps":[{"name":"s","inputs":[{"name":"x","sig":"ctx"}],"nodes":[],"wires":[],"out":"x"}],"out":"s"}"""
        assertTrue(DerivedSignals.validate("e", json(enumOut), SignalConfig()).isNotEmpty())
    }

    @Test fun atMostEightDefinitions() {
        val eight = SignalConfig(derived = (1..8).map { DerivedSignal("d$it", json(walkyDef) as JsonObject) })
        assertTrue(DerivedSignals.validate("ninth", json(walkyDef), eight).isNotEmpty())
        assertEquals("replacing one is fine", emptyList<String>(), DerivedSignals.validate("d3", json(walkyDef), eight))
    }

    @Test fun theTryItRunsADerivedSignal() {
        val body = json("""{"graph":$useWalky,"signals":{"stepHz":[2,2,2,2,2,2,2,2,2,2]}}""") as JsonObject
        val out = (ExprTry.run(body, cfg = cfg("walky" to walkyDef)) as ExprTry.Outcome.Ok).body
        assertEquals(JsonPrimitive(true), out["ok"])
        assertEquals("walking for 10 s: not walky is false, so the guard drops", JsonPrimitive("drop"), out["verdict"])
        assertTrue((out["steps"] as JsonArray).toString().contains("dv_walky_0"))
    }
}
