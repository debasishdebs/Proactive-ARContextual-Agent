package com.rokid.context.ai.canvas.plan

import com.rokid.context.ai.canvas.NodeTypes
import com.rokid.context.ai.canvas.ParamCheck
import com.rokid.context.ai.canvas.PipelineNode
import com.rokid.context.ai.canvas.plan.ExprTestSupport.graph
import com.rokid.context.ai.canvas.plan.ExprTestSupport.guardNode
import com.rokid.context.ai.canvas.plan.ExprTestSupport.json
import com.rokid.context.ai.canvas.plan.ExprTestSupport.pipeline
import com.rokid.context.ai.canvas.plan.ExprTestSupport.step
import com.rokid.context.capture.plan.PlanJson
import com.rokid.context.capture.plan.PlanValidator
import com.rokid.context.capture.plan.SIG_SCHEMA_WIN
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 3c E2a: `expr.value` programs compile to the plan, validate, and can only tune an editable row. */
class ExprValuePlanTest {
    /** One step: a number program. */
    private fun num(v: String = "3") = graph(step("n", "", """{"id":"k","op":"const","value":$v}""", "", "k"), out = "n")

    private fun valueNode(id: String, g: String = num(), row: String? = "REST", field: String? = "capPerMin") = PipelineNode(
        id, NodeTypes.EXPR_VALUE, id,
        buildJsonObject { put("graph", json(g)); row?.let { put("row", JsonPrimitive(it)) }; field?.let { put("field", JsonPrimitive(it)) } },
    )

    private fun compile(vararg nodes: PipelineNode) = PlanCompiler.compile(pipeline(*nodes))

    @Test fun aValueCompilesToACatalogue2EntryWithItsTarget() {
        val r = compile(valueNode("v1"))
        assertEquals(r.problems.toString(), emptyList<String>(), r.problems)
        val plan = r.plan!!
        assertEquals(2, plan.planSchema); assertEquals(SIG_SCHEMA_WIN, plan.config.sigSchema)
        val e = plan.config.exprs.single()
        assertEquals(setOf("id", "role", "row", "field", "out", "s"), e.keys)
        assertEquals("value", (e["role"] as JsonPrimitive).content); assertEquals("REST", (e["row"] as JsonPrimitive).content); assertEquals("capPerMin", (e["field"] as JsonPrimitive).content)
        assertEquals(emptyList<String>(), PlanValidator.check(r.planJson!!).first)
        assertEquals(plan.configHash, PlanJson.configHash(PlanJson.decodeStrict(r.planJson!!).getOrThrow().config))
    }

    @Test fun theTargetDefaultsToTheRestCapAndEveryGapFieldIsNamed() {
        val d = compile(valueNode("v1", row = null, field = null))
        assertEquals(d.problems.toString(), emptyList<String>(), d.problems)
        for (f in listOf("settledGapS", "quietGapS", "walkGapS", "stillCapGapS")) {
            val r = compile(valueNode("v1", row = "FLIGHT", field = f))
            assertEquals(r.problems.toString(), emptyList<String>(), r.problems)
            assertTrue(r.planJson!!.contains("\"field\":\"$f\""))
        }
    }

    @Test fun WalkingRowValueRefusedTest() {
        for (row in listOf("WALKING", "UNKNOWN")) {
            val r = compile(valueNode("v1", row = row))
            assertNull(row, r.plan)
            assertTrue(r.problems.toString(), r.problems.any { "node v1:" in it })
        }
        // also straight through the lowering, whatever the param form allowed
        val l = ExprLowering.lowerAll(listOf(ExprLowering.Entry("v1", json(num()), ExprRole.VALUE, "WALKING", "capPerMin")))
        assertTrue(l.problems.toString(), l.problems.single().startsWith("node v1: EXPR_VALUE_TARGET: row WALKING is locked"))
        assertTrue(ExprLowering.lowerAll(listOf(ExprLowering.Entry("v1", json(num()), ExprRole.VALUE, "UNKNOWN", "capPerMin"))).problems.single().contains("row UNKNOWN is locked"))
        assertEquals(emptyList<String>(), ParamCheck.problems("v1", NodeTypes.byType(NodeTypes.EXPR_VALUE)!!, valueNode("v1").params))
        assertFalse(ParamCheck.problems("v1", NodeTypes.byType(NodeTypes.EXPR_VALUE)!!, valueNode("v1", row = "WALKING").params).isEmpty())
    }

    @Test fun onlyTheCapAndTheGapsAreFields() {
        for (f in listOf("pause", "forceSettle", "nudges", "settledMs", "reservedMs", "veto", "")) {
            val l = ExprLowering.lowerAll(listOf(ExprLowering.Entry("v1", json(num()), ExprRole.VALUE, "REST", f)))
            assertTrue("'$f': ${l.problems}", l.problems.single().contains("EXPR_VALUE_TARGET: field must be one of capPerMin, settledGapS, quietGapS, walkGapS, stillCapGapS"))
        }
        assertFalse(ParamCheck.problems("v1", NodeTypes.byType(NodeTypes.EXPR_VALUE)!!, valueNode("v1", field = "pause").params).isEmpty())
    }

    @Test fun aValueMustEndInANumberAndTwoCannotTuneOneField() {
        val bool = graph(step("b", "", """{"id":"k","op":"const","value":true}""", "", "k"), out = "b")
        assertEquals(listOf(ExprCode.EXPR_WRONG_TYPE), ExprTestSupport.codes(bool, ExprRole.VALUE))
        val dup = compile(valueNode("v1"), valueNode("v2"))
        assertNull(dup.plan); assertTrue(dup.problems.toString(), dup.problems.any { it.startsWith("node v2:") && "REST.capPerMin is tuned by another value program" in it })
        val ok = compile(valueNode("v1"), valueNode("v2", field = "quietGapS"), valueNode("v3", row = "FLIGHT"))
        assertEquals(ok.problems.toString(), emptyList<String>(), ok.problems)
    }

    @Test fun guardsAndValuesShareTheProgramAndNodeLimits() {
        // 3 guards + 3 values = the 6 programs; a seventh is refused
        val six = compile(*(1..3).map { guardNode("g$it", ExprTestSupport.G_NOCTX) }.toTypedArray(), valueNode("v1"), valueNode("v2", field = "quietGapS"), valueNode("v3", row = "FLIGHT"))
        assertEquals(six.problems.toString(), emptyList<String>(), six.problems)
        val seven = compile(*(1..3).map { guardNode("g$it", ExprTestSupport.G_NOCTX) }.toTypedArray(), valueNode("v1"), valueNode("v2", field = "quietGapS"), valueNode("v3", row = "FLIGHT"), valueNode("v4", row = "VEHICLE_PASSENGER"))
        assertNull(seven.plan); assertTrue(seven.problems.any { "EXPR_TOO_MANY_PROGRAMS" in it })
    }

    @Test fun aGuardOnlyPlanIsStillCatalogue1() {
        val r = compile(guardNode("g1", ExprTestSupport.G_NOCTX))
        assertEquals(1, r.plan!!.config.sigSchema)
    }

    @Test fun theValueTypeIsNeverLiveable() {
        val t = NodeTypes.byType(NodeTypes.EXPR_VALUE)!!
        assertFalse("LIVE stays refused, as for guards", t.liveable)
        assertFalse(NodeTypes.byType(NodeTypes.EXPR_GUARD)!!.liveable)
        assertEquals(setOf("graph", "row", "field"), t.params.specs.map { it.name }.toSet())
        assertEquals(listOf("REST", "VEHICLE_PASSENGER", "VEHICLE_DRIVING", "FLIGHT"), t.params.specs.single { it.name == "row" }.options)
    }

    @Test fun aValueOverAWindowCarriesTheTermAndItsBytes() {
        val w = graph(step("m", "", """{"id":"w","op":"win","name":"stepHz","agg":"mean","sec":60}""", "", "w"), out = "m")
        val r = compile(valueNode("v1", g = w))
        assertEquals(r.problems.toString(), emptyList<String>(), r.problems)
        val lowered = ExprLowering.lowerAll(listOf(ExprLowering.Entry("v1", json(w), ExprRole.VALUE, "REST", "capPerMin")))
        assertEquals(1, lowered.windowTerms); assertEquals(240, lowered.stateBytes)
        assertNotNull((r.plan!!.config.exprs.single()["s"] as JsonArray).map { it as JsonObject }.singleOrNull { (it["op"] as JsonPrimitive).content == "win" })
    }
}
