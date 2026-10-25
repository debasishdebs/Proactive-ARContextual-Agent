package com.rokid.context.ai.canvas

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `proc.motion_model` (Process): the heuristic, its tunable params and where it sits in a graph. */
class MotionModelTest {
    private val none = JsonObject(emptyMap())
    private fun params(vararg p: Pair<String, Any>) = buildJsonObject { p.forEach { (k, v) -> when (v) { is Number -> put(k, v); else -> put(k, v.toString()) } } }
    private fun s(t: Double, h: Double = 0.0) = MotionModel.Settings(threshold = t, hysteresis = h)
    private val type = NodeTypes.byType(NodeTypes.PROC_MOTION_MODEL)!!
    private fun problems(p: JsonObject) = ParamCheck.problems("m", type, p)

    // ---- the rule ----

    @Test fun movingWhenTheNumberIsAtLeastTheThreshold() {
        assertEquals(listOf(false, true, true), MotionModel.decide(listOf(0.29, 0.3, 0.9), s(0.3)))
        assertEquals("a larger threshold needs more motion", listOf(false, false, true), MotionModel.decide(listOf(0.29, 0.3, 0.9), s(0.5)))
    }

    @Test fun hysteresisKeepsItMovingUntilTheNumberFallsBelowThresholdMinusHysteresis() {
        val v = listOf(0.1, 0.35, 0.25, 0.19, 0.25, 0.35)
        assertEquals(listOf(false, true, false, false, false, true), MotionModel.decide(v, s(0.3)))
        assertEquals("with hysteresis 0.1: stays moving at 0.25, ends below 0.2, restarts only at the threshold", listOf(false, true, true, false, false, true), MotionModel.decide(v, s(0.3, 0.1)))
        assertEquals("exactly at threshold minus hysteresis still counts", listOf(true, true), MotionModel.decide(listOf(0.3, 0.2), s(0.3, 0.1)).map { it!! })
    }

    @Test fun aMissingNumberDecidesNothingAndKeepsTheState() {
        assertEquals(listOf(true, null, true, false), MotionModel.decide(listOf(0.5, null, 0.25, 0.1), s(0.3, 0.1)))
        assertEquals("the state starts still", listOf(false), MotionModel.decide(listOf(0.25), s(0.3, 0.1)))
    }

    @Test fun sameInputSameOutput() {
        val v = listOf(0.1, 0.4, 0.2, 0.9, 0.0)
        assertEquals(MotionModel.decide(v, s(0.3, 0.1)), MotionModel.decide(v, s(0.3, 0.1)))
    }

    @Test fun theNumberIsTheWatchScoreOrTheAggregateValue() {
        assertEquals(0.4, MotionModel.numberOf(buildJsonObject { put("itemType", "watch"); put("score", 0.4); put("value", 9.0) })!!, 0.0)
        assertEquals(0.7, MotionModel.numberOf(buildJsonObject { put("value", 0.7); put("score", 9.0) })!!, 0.0)
        assertNull(MotionModel.numberOf(buildJsonObject { put("itemType", "watch") }))
        assertNull(MotionModel.numberOf(buildJsonObject { put("value", "x") }))
        assertNull(MotionModel.numberOf(JsonPrimitive(1)))
    }

    // ---- params ----

    @Test fun defaultsAndFallbacks() {
        assertEquals(MotionModel.Settings("heuristic", 0.3, 0.0), MotionModel.settings(none))
        assertEquals(MotionModel.Settings("heuristic", 0.6, 0.2), MotionModel.settings(params("threshold" to 0.6, "hysteresis" to 0.2)))
        assertEquals("a bad value falls back, the validator reports it", MotionModel.Settings(), MotionModel.settings(params("kind" to "ml", "threshold" to -1, "hysteresis" to "x")))
        assertEquals("a hysteresis above the threshold is capped at it", 0.3, MotionModel.settings(params("threshold" to 0.3, "hysteresis" to 5)).hysteresis, 0.0)
        assertEquals(listOf("heuristic"), MotionModel.KINDS)
    }

    @Test fun validatedRanges() {
        assertEquals(emptyList<String>(), problems(none))
        assertEquals(emptyList<String>(), problems(params("kind" to "heuristic", "threshold" to 1000, "hysteresis" to 0)))
        assertTrue(problems(params("threshold" to -0.1)).single().contains("threshold must be between 0 and 1000"))
        assertTrue(problems(params("threshold" to 1000.5)).single().contains("between 0 and 1000"))
        assertTrue(problems(params("hysteresis" to -1)).single().contains("hysteresis must be between"))
        assertTrue(problems(params("kind" to "ml")).single().contains("one of heuristic"))
        assertTrue(problems(params("speed" to 1)).single().contains("unknown param"))
        assertTrue(problems(params("threshold" to 0.3, "hysteresis" to 0.5)).single().contains("hysteresis must not exceed threshold"))
        assertEquals(emptyList<String>(), problems(params("threshold" to 0.3, "hysteresis" to 0.3)))
    }

    // ---- registry ----

    @Test fun registryEntryIsAPhoneSideProcessWithADeclaredSchema() {
        assertEquals(Family.PROCESS, type.family); assertEquals(Domain.ASYNC, type.domain); assertEquals(PortType.NUMBER, type.output)
        assertEquals(setOf(PortType.NUMBER, PortType.WATCH), type.inputs); assertEquals(2, type.sinceSchema); assertEquals(0, type.cost.modelCalls)
        assertEquals(listOf("kind", "threshold", "hysteresis"), type.params.specs.map { it.name })
        assertEquals(listOf(JsonPrimitive("heuristic"), JsonPrimitive(0.3), JsonPrimitive(0.0)), type.params.specs.map { it.default })
        assertNull(NodeTypes.resolve(NodeTypes.PROC_MOTION_MODEL, 1)); assertTrue(type.palette)
    }

    @Test fun theSwitchReadsItsLabelAndCompareReadsItsValue() {
        assertTrue(PortType.NUMBER in NodeTypes.byType(NodeTypes.SWITCH)!!.inputs)
        assertTrue(PortType.NUMBER in NodeTypes.byType(NodeTypes.COMPARE)!!.inputs)
        assertEquals(listOf("moving", "still"), MotionModel.LABELS)
    }

    @Test fun innerFlowExposesAllThreeParamsAtTheirBoxes() {
        val f = type.inner; assertNotNull(f); f!!
        assertEquals(setOf("kind", "threshold", "hysteresis"), f.exposed.map { it.param }.toSet())
        assertEquals(listOf("moving", "still"), f.nodes.filter { it.kind == "branch" }.map { it.id })
    }

    private fun n(id: String, t: String, p: JsonObject = none) = PipelineNode(id, t, id, p)
    private fun pipe(vararg nodes: PipelineNode, edges: List<PipelineEdge>) = Pipeline(2, "p", "P", nodes.toList(), edges)

    @Test fun watchToAggregateToModelToSwitchValidates() {
        val chain = pipe(n("w", NodeTypes.SRC_WATCH_MOTION), n("a", NodeTypes.AGG_WINDOW), n("m", NodeTypes.PROC_MOTION_MODEL),
            n("s", NodeTypes.SWITCH, buildJsonObject { put("field", "label"); put("cases", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("moving"), JsonPrimitive("still")))) }),
            n("c", NodeTypes.COMPARE), edges = listOf(PipelineEdge("w", "a"), PipelineEdge("a", "m"), PipelineEdge("m", "s"), PipelineEdge("m", "c")))
        assertEquals(emptyList<String>(), PipelineValidator.validate(chain))
        val direct = pipe(n("w", NodeTypes.SRC_WATCH_MOTION), n("m", NodeTypes.PROC_MOTION_MODEL), n("c", NodeTypes.COMPARE), edges = listOf(PipelineEdge("w", "m"), PipelineEdge("m", "c")))
        assertEquals("the model also reads the watch values directly", emptyList<String>(), PipelineValidator.validate(direct))
    }
}
