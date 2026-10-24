package com.rokid.context.ai.canvas.plan

import com.rokid.context.ai.canvas.plan.ExprTestSupport.FAST
import com.rokid.context.ai.canvas.plan.ExprTestSupport.G1
import com.rokid.context.ai.canvas.plan.ExprTestSupport.codes
import com.rokid.context.ai.canvas.plan.ExprTestSupport.graph
import com.rokid.context.ai.canvas.plan.ExprTestSupport.step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The two-level shape: outer named steps wired box to box, inner catalogue nodes per step. */
class ExprProgramTest {
    @Test fun validProgramHasNoProblems() = assertEquals(emptyList<ExprCode>(), codes(G1))

    @Test fun stepReferenceOrderIsFree() {
        // `final` is listed before the steps it reads; the order of the list is not the order of evaluation
        val c = ExprProgram.parse(ExprRole.GUARD, ExprTestSupport.json(G1)).first!!.check()
        assertEquals(emptyList<ExprDiag>(), c.problems)
        assertEquals(listOf("drive", "fast", "final"), c.stepOrder)
    }

    @Test fun loopAcrossStepsIsRefused() {
        val a = step("a", """{"name":"x","step":"b"}""", """{"id":"n","op":"not"}""", """["x","n.a"]""", "n")
        val b = step("b", """{"name":"y","step":"a"}""", """{"id":"n","op":"not"}""", """["y","n.a"]""", "n")
        assertTrue(ExprCode.EXPR_LOOP in codes(graph(a, b, out = "a")))
    }

    @Test fun stepReadingItselfIsALoop() {
        val a = step("a", """{"name":"x","step":"a"}""", """{"id":"n","op":"not"}""", """["x","n.a"]""", "n")
        assertTrue(ExprCode.EXPR_LOOP in codes(graph(a, out = "a")))
    }

    @Test fun loopInsideAStepIsRefused() {
        val s = step("a", "", """{"id":"p","op":"not"},{"id":"q","op":"not"}""", """["p","q.a"],["q","p.a"]""", "q")
        assertTrue(ExprCode.EXPR_LOOP in codes(graph(s, out = "a")))
    }

    @Test fun sameStepNameTwiceIsRefused() =
        assertTrue(ExprCode.EXPR_DUP_NAME in codes(graph(FAST, FAST, out = "fast")))

    @Test fun stepNamePatternIsEnforced() {
        for (bad in listOf("Fast", "1a", "a-b", "", "a".repeat(25))) {
            assertTrue("name '$bad'", ExprCode.EXPR_BAD_NAME in codes(graph(FAST.replace("\"name\":\"fast\"", "\"name\":\"$bad\""), out = bad)))
        }
        assertEquals(emptyList<ExprCode>(), codes(graph(FAST.replace("\"name\":\"fast\"", "\"name\":\"${"a".repeat(24)}\""), out = "a".repeat(24))))
    }

    @Test fun innerNodeOfAnotherStepIsRefused() {
        // step `drive2` wires from `k`, a node that only exists in step `fast`
        val s = step("drive2", "", """{"id":"n","op":"not"}""", """["k","n.a"]""", "n")
        val c = codes(graph(FAST, s, out = "drive2"))
        assertTrue(c.toString(), ExprCode.EXPR_FOREIGN_NODE in c)
    }

    @Test fun unknownReferencesAreRefused() {
        val s = step("a", """{"name":"x","step":"nope"}""", """{"id":"n","op":"not"}""", """["x","n.a"]""", "n")
        assertTrue(ExprCode.EXPR_UNKNOWN_REF in codes(graph(s, out = "a")))
        assertTrue(ExprCode.EXPR_UNKNOWN_REF in codes(graph(FAST, out = "ghost")))
    }

    @Test fun unknownSignalIsRefusedAndAliasIsAccepted() {
        assertTrue(ExprCode.EXPR_UNKNOWN_SIGNAL in codes(graph(FAST.replace("gyroMax", "gyroMaxx"), out = "fast")))
        assertEquals(emptyList<ExprCode>(), codes(graph(FAST.replace("gyroMax", "headSpeed"), out = "fast")))
    }

    @Test fun wrongTypeIsRefused() {
        // a comparison of a bool, and `and` of a number
        val s = step("a", """{"name":"x","sig":"ctx"}""", """{"id":"c","op":"cmp","f":">"},{"id":"k","op":"const","value":1}""", """["x","c.a"],["k","c.b"]""", "c")
        assertTrue(ExprCode.EXPR_WRONG_TYPE in codes(graph(s, out = "a")))
        val t = step("a", """{"name":"x","sig":"gyroMax"}""", """{"id":"n","op":"not"}""", """["x","n.a"]""", "n")
        assertTrue(ExprCode.EXPR_WRONG_TYPE in codes(graph(t, out = "a")))
        val u = step("a", """{"name":"x","sig":"gyroMax"}""", """{"id":"i","op":"in","set":["REST"]}""", """["x","i.a"]""", "i")
        assertTrue(ExprCode.EXPR_WRONG_TYPE in codes(graph(u, out = "a")))
        val v = step("a", """{"name":"x","sig":"ctx"}""", """{"id":"i","op":"in","set":["FLYING"]}""", """["x","i.a"]""", "i")
        assertTrue(ExprCode.EXPR_BAD_PARAM in codes(graph(v, out = "a")))
    }

    @Test fun guardOutputMustBeBoolean() {
        val s = step("a", """{"name":"x","sig":"gyroMax"}""", """{"id":"k","op":"const","value":2},{"id":"m","op":"arith","f":"*"}""", """["x","m.a"],["k","m.b"]""", "m")
        assertEquals(listOf(ExprCode.EXPR_GUARD_NOT_BOOL), codes(graph(s, out = "a")))
        // the same graph is a fine VALUE (role-ready model)
        assertEquals(emptyList<ExprCode>(), codes(graph(s, out = "a"), ExprRole.VALUE))
    }

    @Test fun portsMustBeWiredExactlyOnce() {
        val missing = step("a", """{"name":"x","sig":"gyroMax"}""", """{"id":"c","op":"cmp","f":">"}""", """["x","c.a"]""", "c")
        assertTrue(ExprCode.EXPR_PORT in codes(graph(missing, out = "a")))
        val twice = step("a", """{"name":"x","sig":"gyroMax"}""", """{"id":"c","op":"cmp","f":">"}""", """["x","c.a"],["x","c.a"],["x","c.b"]""", "c")
        assertTrue(ExprCode.EXPR_PORT in codes(graph(twice, out = "a")))
    }

    @Test fun malformedShapeIsRefusedNotThrown() {
        for (bad in listOf("[]", """{"steps":{}}""", """{"steps":[],"out":"a"}""", """{"steps":[{"name":"a"}],"out":"a"}""", """{"steps":[],"out":"a","extra":1}""")) {
            assertTrue(bad, codes(bad).isNotEmpty())
        }
    }

    private fun notChain(n: Int): String {
        val nodes = (0 until n).joinToString(",") { """{"id":"n$it","op":"not"}""" }
        val wires = listOf("""["b","n0.a"]""") + (1 until n).map { """["n${it - 1}","n$it.a"]""" }
        return graph(step("a", """{"name":"b","const":true}""", nodes, wires.joinToString(","), "n${n - 1}"), out = "a")
    }

    @Test fun thirtyThreeNodesIsTooMany() {
        // the bool constant input counts as a node: 1 + 31 = 32 is the limit
        assertEquals(emptyList<ExprCode>(), codes(notChain(31)))
        assertTrue(ExprCode.EXPR_TOO_MANY_NODES in codes(notChain(32)))
    }

    @Test fun constantsStayDataAndNeedFiniteValues() {
        val s = step("a", "", """{"id":"k","op":"const","value":"x"}""", "", "k")
        assertTrue(codes(graph(s, out = "a")).isNotEmpty())
    }
}
