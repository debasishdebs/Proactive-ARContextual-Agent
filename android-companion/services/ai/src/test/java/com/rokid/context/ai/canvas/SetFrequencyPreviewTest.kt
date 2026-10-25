package com.rokid.context.ai.canvas

import com.rokid.context.ai.canvas.plan.PlanShape
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `out.set_frequency_preview` (Outcome): a preview of the capture rate, never a plan, never applied. */
class SetFrequencyPreviewTest {
    private val none = JsonObject(emptyMap())
    private val type = NodeTypes.byType(NodeTypes.OUT_SET_FREQUENCY_PREVIEW)!!
    private fun params(vararg p: Pair<String, Any>) = buildJsonObject { p.forEach { (k, v) -> if (v is Number) put(k, v) else put(k, v.toString()) } }
    private fun problems(p: JsonObject) = ParamCheck.problems("f", type, p)

    @Test fun registryEntryIsAPreviewOutcome() {
        assertEquals(Family.OUTCOME, type.family); assertEquals("Set frequency (preview)", type.label); assertEquals(PortType.PREVIEW, type.output)
        assertEquals(setOf(PortType.NUMBER), type.inputs); assertEquals(2, type.sinceSchema); assertEquals(0, type.cost.modelCalls); assertTrue(type.palette)
        assertNull(NodeTypes.resolve(NodeTypes.OUT_SET_FREQUENCY_PREVIEW, 1)); assertEquals(Domain.ASYNC, type.domain)
    }

    @Test fun defaultsLimitsAndWhatTheCapturePolicyTakes() {
        assertEquals(SetFrequencyPreview.Settings("REST", 4, 2), SetFrequencyPreview.settings(none))
        assertEquals(listOf("REST", "VEHICLE_PASSENGER", "VEHICLE_DRIVING", "FLIGHT"), SetFrequencyPreview.ROWS)
        assertEquals("the capture table takes 1..4 a minute", 1..4, SetFrequencyPreview.MIN_CAP..SetFrequencyPreview.MAX_CAP)
        assertEquals(emptyList<String>(), problems(none))
        assertTrue(problems(params("movingCapPerMin" to 5)).single().contains("between 1 and 4"))
        assertTrue(problems(params("stillCapPerMin" to 0)).single().contains("between 1 and 4"))
        assertTrue(problems(params("row" to "WALKING")).single().contains("one of REST"))
        assertTrue(problems(params("applyNow" to 1)).single().contains("unknown param"))
        assertEquals("a bad value falls back", SetFrequencyPreview.Settings(), SetFrequencyPreview.settings(params("row" to "WALKING", "movingCapPerMin" to 9, "stillCapPerMin" to "x")))
    }

    @Test fun theLabelPicksTheValueAndTheRowSaysWhatItIsToday() {
        val up = SetFrequencyPreview.preview("moving", SetFrequencyPreview.Settings("REST", 4, 1))!!
        assertEquals("REST", up["targetRow"]!!.jsonPrimitive.content); assertEquals("capPerMin", up["field"]!!.jsonPrimitive.content)
        assertEquals(4, up["value"]!!.jsonPrimitive.content.toInt()); assertEquals(2, up["today"]!!.jsonPrimitive.content.toInt()); assertEquals("raise", up["change"]!!.jsonPrimitive.content)
        val down = SetFrequencyPreview.preview("still", SetFrequencyPreview.Settings("REST", 4, 1))!!
        assertEquals(1, down["value"]!!.jsonPrimitive.content.toInt()); assertEquals("lower", down["change"]!!.jsonPrimitive.content)
        assertEquals("same", SetFrequencyPreview.preview("still", SetFrequencyPreview.Settings())!!["change"]!!.jsonPrimitive.content)
        val unchanged = SetFrequencyPreview.preview("moving", SetFrequencyPreview.Settings("VEHICLE_PASSENGER", 3, 1))!!
        assertEquals("a row that leaves the cap unchanged has no number today", JsonNull, unchanged["today"]); assertEquals("set", unchanged["change"]!!.jsonPrimitive.content)
        assertEquals(1, SetFrequencyPreview.today("FLIGHT"))
        assertNull(SetFrequencyPreview.preview("unknown", SetFrequencyPreview.Settings()))
    }

    @Test fun everyResultSaysItIsAPreviewAndNothingWasApplied() {
        val p = SetFrequencyPreview.preview("moving", SetFrequencyPreview.Settings())!!
        assertEquals("true", p["preview"]!!.jsonPrimitive.content); assertEquals("false", p["applied"]!!.jsonPrimitive.content)
        val line = (p["facts"] as JsonArray).jsonArray.single().jsonPrimitive.content
        assertTrue(line, line.contains("Preview only, nothing applied") && line.startsWith("moving: would set REST to 4 captures per minute"))
    }

    @Test fun anyGraphHoldingItNeverPublishes() {
        val n = PipelineNode("f", NodeTypes.OUT_SET_FREQUENCY_PREVIEW, "f", none)
        assertEquals(listOf("f"), PlanShape.tier3Nodes(Pipeline(2, "p", "P", listOf(n), emptyList())))
        val glasses = DefaultPipelines.defaultCapture()
        assertEquals(emptyList<String>(), PlanShape.tier3Nodes(glasses))
        assertEquals(listOf("f"), PlanShape.tier3Nodes(glasses.copy(nodes = glasses.nodes + n)))
    }

    @Test fun theNodeIsNotLiveOrAPlanNode() {
        // no capture-plan compile path knows it: the plan of a graph that holds it is the plan of the graph without it (the golden hash test of the starter pins this too)
        assertTrue(type.domain != Domain.RT_GLASSES && type.domain != Domain.RT_PHONE)
        assertEquals(null, type.promptKey); assertEquals(ExecutorKind.BUILTIN, type.executor)
    }
}
