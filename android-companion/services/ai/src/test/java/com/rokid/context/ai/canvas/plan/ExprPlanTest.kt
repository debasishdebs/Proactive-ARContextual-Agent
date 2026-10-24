package com.rokid.context.ai.canvas.plan

import com.rokid.context.ai.canvas.NodeTypes
import com.rokid.context.ai.canvas.plan.ExprTestSupport.G1
import com.rokid.context.ai.canvas.plan.ExprTestSupport.G1_SHUFFLED
import com.rokid.context.ai.canvas.plan.ExprTestSupport.G_NOCTX
import com.rokid.context.ai.canvas.plan.ExprTestSupport.graph
import com.rokid.context.ai.canvas.plan.ExprTestSupport.guardNode
import com.rokid.context.ai.canvas.plan.ExprTestSupport.pipeline
import com.rokid.context.ai.canvas.plan.ExprTestSupport.step
import com.rokid.context.capture.plan.CanonicalJson
import com.rokid.context.capture.plan.PLAN_MAX_BYTES
import com.rokid.context.capture.plan.PlanJson
import com.rokid.context.capture.plan.SIG_SCHEMA
import com.rokid.context.capture.plan.planSchemaFor
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun compile(vararg g: Pair<String, String>, name: String = "P") =
    PlanCompiler.compile(pipeline(*g.map { guardNode(it.first, it.second) }.toTypedArray(), name = name))

class LoweringDeterministicTest {
    @Test fun sameHashWhateverTheAuthoringOrder() {
        val a = compile("g1" to G1)
        val b = compile("g1" to G1_SHUFFLED)
        assertEquals(a.problems.toString(), emptyList<String>(), a.problems)
        assertEquals(a.plan!!.configHash, b.plan!!.configHash)
        assertEquals(a.planJson, b.planJson)
    }

    @Test fun nodeOrderInThePipelineDoesNotMatter() {
        val p = pipeline(guardNode("g1", G1), guardNode("g2", G_NOCTX))
        val a = PlanCompiler.compile(p)
        val b = PlanCompiler.compile(p.copy(nodes = p.nodes.reversed()))
        assertEquals(a.planJson, b.planJson)
    }

    @Test fun planIsSchema2WithSigSchemaAndRoundTrips() {
        val r = compile("g1" to G1)
        val plan = r.plan!!
        assertEquals(2, plan.planSchema)
        assertEquals(planSchemaFor(plan.config), plan.planSchema)
        assertEquals(SIG_SCHEMA, plan.config.sigSchema)
        assertEquals(1, plan.config.exprs.size)
        assertEquals(plan.configHash, PlanJson.configHash(PlanJson.decodeStrict(r.planJson!!).getOrThrow().config))
    }

    /** The editor's meters (X5, exprgraph.js exprMeters) read `config.exprs` of planJson: pin that shape. */
    @Test fun planJsonCarriesTheExprsUnderConfig() {
        val root = kotlinx.serialization.json.Json.parseToJsonElement(compile("g1" to G1).planJson!!) as JsonObject
        assertTrue("top level has no exprs", "exprs" !in root)
        val exprs = (root["config"] as JsonObject)["exprs"] as JsonArray
        assertEquals(listOf("g1"), exprs.map { ((it as JsonObject)["id"] as JsonPrimitive).content })
        assertTrue(((exprs[0] as JsonObject)["s"] as JsonArray).isNotEmpty())
    }

    @Test fun noExprNodeMeansSchema1AndNoExprs() {
        val plan = compile().plan!!
        assertEquals(1, plan.planSchema)
        assertTrue(plan.config.exprs.isEmpty())
    }
}

class CycleRefusedTest {
    @Test fun loopIsAProblemNamingTheNodeAndNoPlanIsEmitted() {
        val a = step("a", """{"name":"x","step":"b"}""", """{"id":"n","op":"not"}""", """["x","n.a"]""", "n")
        val b = step("b", """{"name":"y","step":"a"}""", """{"id":"n","op":"not"}""", """["y","n.a"]""", "n")
        val r = compile("g1" to graph(a, b, out = "a"))
        assertNull(r.plan); assertNull(r.planJson)
        assertTrue(r.problems.toString(), r.problems.any { it.startsWith("node g1: EXPR_LOOP") })
    }
}

class CostBoundTest {
    private fun inChain(n: Int): String {
        val all = """["UNKNOWN","REST","WALKING","VEHICLE_PASSENGER","VEHICLE_DRIVING","FLIGHT"]"""
        val inputs = (0 until n).joinToString(",") { """{"name":"c$it","sig":"ctx"}""" }
        val ins = (0 until n).joinToString(",") { """{"id":"i$it","op":"in","set":$all}""" }
        val ors = (1 until n).joinToString(",") { """{"id":"o$it","op":"or"}""" }
        val w = (0 until n).map { """["c$it","i$it.a"]""" } +
            (1 until n).map { k -> """["${if (k == 1) "i0" else "o${k - 1}"}","o$k.a"]""" } + (1 until n).map { k -> """["i$k","o$k.b"]""" }
        return graph(step("a", inputs, ins + (if (n > 1) ",$ors" else ""), w.joinToString(","), if (n > 1) "o${n - 1}" else "i0"), out = "a")
    }

    @Test fun costAtTheBoundIsAcceptedAndAboveIsRefused() {
        // n inputs (1 each) + n `in` over six states (7 each) + n-1 `or` (1 each) = 9n - 1: n=7 is 62, n=8 is 71 (bound 64)
        assertEquals(emptyList<ExprCode>(), ExprTestSupport.codes(inChain(7)))
        assertEquals(listOf(ExprCode.EXPR_COST), ExprTestSupport.codes(inChain(8)))
        val r = compile("g1" to inChain(8))
        assertNull(r.plan)
        assertTrue(r.problems.toString(), r.problems.any { it.startsWith("node g1: EXPR_COST") })
    }

    @Test fun costIsReportedForAValidProgram() {
        val c = ExprProgram.parse(ExprRole.GUARD, ExprTestSupport.json(inChain(7))).first!!.check()
        assertEquals(62, c.cost)
        assertEquals(7 + 7 + 6, c.nodes)
    }
}

class GuardReadsCtxWarnsTest {
    @Test fun readingCtxWarnsAndStillCompiles() {
        val r = compile("g1" to G1)
        assertNotNull(r.plan)
        assertTrue(r.warnings.toString(), r.warnings.any { it.startsWith("node g1: EXPR_GUARD_READS_CTX") })
    }

    @Test fun aMotionOnlyGuardDoesNotWarn() {
        val r = compile("g1" to G_NOCTX)
        assertNotNull(r.plan)
        assertTrue(r.warnings.toString(), r.warnings.none { it.contains("EXPR_GUARD_READS_CTX") })
    }

    @Test fun ctxAgeAlsoCounts() {
        val s = step("a", """{"name":"x","sig":"ctxAgeS"}""", """{"id":"k","op":"const","value":5},{"id":"c","op":"cmp","f":">"}""", """["x","c.a"],["k","c.b"]""", "c")
        assertTrue(compile("g1" to graph(s, out = "a")).warnings.any { it.contains("EXPR_GUARD_READS_CTX") })
    }
}

class ExprBytesCountedInCapTest {
    @Test fun exprsCountTowardsTheCap() {
        // pad the plan name so the plan alone fits, but the plan plus the program does not
        val base = compile(name = "x").planJson!!.toByteArray().size
        val pad = PLAN_MAX_BYTES - base - 40
        val fits = compile(name = "x".repeat(1 + pad))
        assertNotNull(fits.problems.toString(), fits.plan)
        val over = compile("g1" to G1, name = "x".repeat(1 + pad))
        assertNull(over.plan)
        assertTrue(over.problems.toString(), over.problems.any { it.startsWith("node g1: EXPR_BYTES") })
    }
}

class ExprGraphLimitsTest {
    @Test fun sevenProgramsAreTooMany() {
        val r = compile(*(0 until 7).map { "g$it" to G_NOCTX }.toTypedArray())
        assertNull(r.plan)
        assertTrue(r.problems.toString(), r.problems.any { it.contains("EXPR_TOO_MANY_PROGRAMS") })
    }

    @Test fun sixSmallProgramsAreAccepted() {
        val r = compile(*(0 until 6).map { "g$it" to G_NOCTX }.toTypedArray())
        assertNotNull(r.problems.toString(), r.plan)
        assertEquals(6, r.plan!!.config.exprs.size)
    }

    @Test fun totalNodesOverAllProgramsAreBoundedBy32() {
        // G_NOCTX lowers to 3 nodes (sig, const, cmp); 11 of them are 33 nodes, but the program limit is 6, so use the 17-node ones
        val big = ExprProgramTestsBig.chain(17)
        val r = compile("g1" to big, "g2" to big)
        assertNull(r.plan)
        assertTrue(r.problems.toString(), r.problems.any { it.contains("EXPR_TOO_MANY_NODES") })
        assertNotNull(compile("g1" to big).plan)
    }
}

object ExprProgramTestsBig {
    /** a bool constant plus [n]-1 nots: n lowered nodes. */
    fun chain(n: Int): String {
        val nodes = (0 until n - 1).joinToString(",") { """{"id":"n$it","op":"not"}""" }
        val w = listOf("""["b","n0.a"]""") + (1 until n - 1).map { """["n${it - 1}","n$it.a"]""" }
        return graph(step("a", """{"name":"b","const":true}""", nodes, w.joinToString(","), "n${n - 2}"), out = "a")
    }
}

class GuardCannotOpenCameraTest {
    @Test fun anExprNodeChangesNothingButTheExprFields() {
        val with = compile("g1" to G1).plan!!.config
        val without = compile().plan!!.config
        assertEquals(without, with.copy(exprs = emptyList(), sigSchema = 0))
    }

    @Test fun loweredProgramsHoldOnlyPureOpsAndNoCaptureKeys() {
        val exprs = compile("g1" to G1).plan!!.config.exprs
        val allowedOps = setOf("sig", "const", "cmp", "in", "and", "or", "not", "arith", "clamp", "choose", "fresh")
        val allowedKeys = setOf("op", "a", "b", "c", "f", "v", "name", "set", "lo", "hi", "ms")
        for (p in exprs) {
            assertEquals(setOf("id", "role", "out", "s"), p.keys)
            assertEquals("guard", (p["role"] as JsonPrimitive).content)
            for (s in p["s"] as JsonArray) {
                s as JsonObject
                assertTrue(s.toString(), (s["op"] as JsonPrimitive).content in allowedOps)
                assertTrue(s.toString(), s.keys.all { it in allowedKeys })
            }
        }
    }

    @Test fun theOutputOfAGuardIsOnlyEverABoolean() {
        // by construction: a guard whose output is not boolean does not lower at all
        val s = step("a", "", """{"id":"k","op":"const","value":1}""", "", "k")
        assertNull(compile("g1" to graph(s, out = "a")).plan)
    }
}

class ExprRegistryTest {
    @Test fun rowIsInThePaletteButNotLiveable() {
        val t = NodeTypes.resolve(NodeTypes.EXPR_GUARD, 2)!!
        assertTrue(t.palette); assertFalse(t.liveable)
        assertEquals("expr.guard", t.type)
    }

    @Test fun graphParamValidatesFromJson() {
        val p = pipeline(guardNode("g1", G1))
        assertEquals(emptyList<String>(), com.rokid.context.ai.canvas.PipelineValidator.validate(p))
        val bad = pipeline(guardNode("g1", graph(step("a", "", """{"id":"n","op":"not"}""", "", "n"), out = "a")))
        assertTrue(com.rokid.context.ai.canvas.PipelineValidator.validate(bad).any { it.startsWith("node g1: EXPR_PORT") })
    }

    @Test fun unknownParamsAreRefused() {
        val p = pipeline(guardNode("g1", G1))
        val n = p.nodes.first { it.id == "g1" }
        val q = p.copy(nodes = p.nodes.map { if (it.id == "g1") n.copy(params = JsonObject(n.params + ("extra" to JsonPrimitive(1)))) else it })
        assertTrue(com.rokid.context.ai.canvas.PipelineValidator.validate(q).any { it.contains("unknown param") })
    }
}

/** Pins the stored shape of `params.graph` and the lowered `exprs` it produces (docs: spec section "expr.guard graph"). */
class ExprGraphGoldenTest {
    @Test fun loweredProgramIsPinned() {
        val exprs = compile("g1" to G1).plan!!.config.exprs
        val expected = """[{"id":"g1","out":6,"role":"guard","s":[{"name":"ctx","op":"sig"},{"a":0,"op":"in","set":["VEHICLE_DRIVING"]},""" +
            """{"op":"const","v":1.5},{"name":"gyroMax","op":"sig"},{"a":3,"b":2,"f":">","op":"cmp"},{"a":1,"op":"not"},{"a":4,"b":5,"op":"and"}]}]"""
        assertEquals(expected, CanonicalJson.encode(JsonArray(exprs)))
    }

    @Test fun planHashIsPinned() {
        assertEquals(GOLDEN_HASH, compile("g1" to G1).plan!!.configHash)
    }

    private companion object { const val GOLDEN_HASH = "d57e69230cf7" }
}
