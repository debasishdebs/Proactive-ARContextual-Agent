package com.rokid.context.ai.canvas

import com.rokid.context.ai.canvas.plan.PlanShape
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `agg.window`: the pure window rule (mean, min, max, count of one sensor feature over the last N items) and its registry entry. `gate.compare` is the adapter that lets a Condition read the number. */
class WindowAggregateTest {
    private val none = JsonObject(emptyMap())
    private fun params(vararg kv: Pair<String, Any>) = buildJsonObject { kv.forEach { (k, v) -> if (v is Int) put(k, v) else if (v is Double) put(k, v) else put(k, v as String) } }
    private fun scenes() = PipelineNode("scenes", NodeTypes.INPUT_SCENES, "Scenes", none)
    private fun moments() = PipelineNode("moments", NodeTypes.INPUT_MOMENTS, "Moments", none)
    private fun agg(p: JsonObject = none) = PipelineNode("agg", NodeTypes.AGG_WINDOW, "Window", p)
    private fun cmp(p: JsonObject = none) = PipelineNode("cmp", NodeTypes.COMPARE, "Compare", p)
    private fun pipe(nodes: List<PipelineNode>, edges: List<PipelineEdge>) = Pipeline(2, "p", "P", nodes, edges)

    // ---- the rule ----

    @Test fun defaultsAreSaneAndAbsentParamsMeanThem() {
        assertEquals(WindowAggregate.Settings("gyroMean", "mean", 10), WindowAggregate.settings(none))
        assertEquals(50, WindowAggregate.MAX_WINDOW)
        assertEquals(listOf("mean", "min", "max", "count"), WindowAggregate.OPS)
        assertEquals(listOf("seconds", "gyroMean", "gyroMax", "accVarMean", "accVarMax", "stepHzMean", "stepHzMax"), WindowAggregate.FEATURES)
        assertEquals(WindowAggregate.Settings("stepHzMax", "max", 3), WindowAggregate.settings(params("feature" to "stepHzMax", "op" to "max", "window" to 3)))
        assertEquals("a bad value falls back, the validator reports it", WindowAggregate.Settings(), WindowAggregate.settings(params("feature" to "nope", "op" to 5, "window" to 99)))
    }

    @Test fun slidingWindowMeanMinMaxCountOverTheLastNItems() {
        val v = listOf(1.0, 3.0, 5.0, 2.0)
        assertEquals(listOf(1.0, 2.0, 4.0, 3.5), WindowAggregate.over(v, "mean", 2))
        assertEquals(listOf(1.0, 1.0, 3.0, 2.0), WindowAggregate.over(v, "min", 2))
        assertEquals(listOf(1.0, 3.0, 5.0, 5.0), WindowAggregate.over(v, "max", 2))
        assertEquals(listOf(1.0, 2.0, 2.0, 2.0), WindowAggregate.over(v, "count", 2))
        assertEquals("a window larger than the list is the whole prefix", listOf(1.0, 2.0, 3.0, 2.75), WindowAggregate.over(v, "mean", 10))
        assertEquals("window 1 is the item itself", v, WindowAggregate.over(v, "mean", 1))
    }

    @Test fun anItemWithoutAValueIsSkippedAndAnEmptyWindowHasNoMeanButACountOfZero() {
        val v = listOf(null, 4.0, null, null)
        assertEquals(listOf(null, 4.0, 4.0, null), WindowAggregate.over(v, "mean", 2))
        assertEquals(listOf(null, 4.0, 4.0, null), WindowAggregate.over(v, "max", 2))
        assertEquals(listOf(0.0, 1.0, 1.0, 0.0), WindowAggregate.over(v, "count", 2))
        assertEquals("a value that is not a finite number counts as missing", listOf(null, 2.0), WindowAggregate.over(listOf(Double.NaN, 2.0), "mean", 5))
    }

    @Test fun theFeatureIsReadFromTheItemsSensorSummaryOnly() {
        val item = buildJsonObject { put("text", "gyroMean 9"); putJsonObject("sensor") { put("seconds", 3); put("gyroMean", 0.25); put("state", "WALKING") } }
        assertEquals(0.25, WindowAggregate.featureOf(item, "gyroMean")!!, 0.0)
        assertEquals(3.0, WindowAggregate.featureOf(item, "seconds")!!, 0.0)
        assertNull("absent feature", WindowAggregate.featureOf(item, "stepHzMax"))
        assertNull("an item with no sensor summary (a Moment today)", WindowAggregate.featureOf(buildJsonObject { put("itemType", "moment"); put("sensor", JsonNull) }, "gyroMean"))
        assertNull("a text value is not a number", WindowAggregate.featureOf(buildJsonObject { putJsonObject("sensor") { put("gyroMean", "0.2") } }, "gyroMean"))
        assertNull("not an object", WindowAggregate.featureOf(JsonPrimitive("x"), "gyroMean"))
    }

    // ---- registry and validation ----

    @Test fun registryEntryIsTheFirstAggregate() {
        val t = NodeTypes.byType(NodeTypes.AGG_WINDOW)
        assertNotNull(t); t!!
        assertEquals(Family.AGGREGATE, t.family); assertEquals(Domain.ASYNC, t.domain); assertEquals(PortType.NUMBER, t.output)
        assertEquals(setOf(PortType.SCENES, PortType.MOMENTS), t.inputs); assertTrue(t.palette); assertEquals(2, t.sinceSchema)
        assertEquals(0, t.cost.modelCalls); assertNull(NodeTypes.resolve(NodeTypes.AGG_WINDOW, 1))
        assertEquals(listOf(NodeTypes.AGG_WINDOW), NodeTypes.all.filter { it.family == Family.AGGREGATE }.map { it.type })
    }

    @Test fun anAggregateOverScenesThenACompareValidates() {
        val p = pipe(listOf(scenes(), agg(), cmp()), listOf(PipelineEdge("scenes", "agg"), PipelineEdge("agg", "cmp")))
        assertEquals(emptyList<String>(), PipelineValidator.validate(p).filter { !it.contains("when") })
        assertEquals(listOf("yes", "no"), NodeTypes.byType(NodeTypes.COMPARE)!!.branches(none))
        assertEquals(emptyList<String>(), PipelineValidator.validate(pipe(listOf(moments(), agg()), listOf(PipelineEdge("moments", "agg")))))
    }

    @Test fun onlySceneAndMomentItemsFeedAnAggregateAndOnlyANumberFeedsACompare() {
        val photos = problemsOf(pipe(listOf(PipelineNode("photos", NodeTypes.INPUT_PHOTOS, "P", none), agg()), listOf(PipelineEdge("photos", "agg"))))
        assertTrue(photos.toString(), photos.any { it.contains("PHOTOS not accepted by agg") })
        val sc = problemsOf(pipe(listOf(scenes(), cmp()), listOf(PipelineEdge("scenes", "cmp"))))
        assertTrue(sc.toString(), sc.any { it.contains("SCENES not accepted by cmp") })
    }

    @Test fun paramsAreCheckedAndUnknownOnesRefused() {
        fun bad(p: JsonObject) = PipelineValidator.validate(pipe(listOf(scenes(), agg(p)), listOf(PipelineEdge("scenes", "agg"))))
        assertEquals(emptyList<String>(), bad(params("feature" to "stepHzMean", "op" to "count", "window" to 50)))
        assertTrue(bad(params("feature" to "other")).toString(), bad(params("feature" to "other")).any { it.contains("feature must be one of") })
        assertTrue(bad(params("op" to "median")).any { it.contains("op must be one of") })
        assertTrue(bad(params("window" to 0)).any { it.contains("window must be between 1 and 50") })
        assertTrue(bad(params("window" to 51)).any { it.contains("window must be between 1 and 50") })
        assertTrue(bad(params("windw" to 3)).any { it.contains("unknown param") })
    }

    @Test fun compareHasAnOperatorAndACutoffWithTheDocumentedDefaults() {
        val t = NodeTypes.byType(NodeTypes.COMPARE)!!
        assertEquals(Family.CONDITION, t.family); assertEquals(PortType.NUMBER, t.output); assertEquals(setOf(PortType.NUMBER), t.inputs); assertEquals(2, t.sinceSchema)
        assertEquals(listOf(">=", ">", "<=", "<"), t.params.specs.first { it.name == "operator" }.options)
        assertEquals(JsonPrimitive(">="), t.params.specs.first { it.name == "operator" }.default)
        assertEquals(JsonPrimitive(0.5), t.params.specs.first { it.name == "cutoff" }.default)
        fun bad(p: JsonObject) = PipelineValidator.validate(pipe(listOf(scenes(), agg(), cmp(p)), listOf(PipelineEdge("scenes", "agg"), PipelineEdge("agg", "cmp"))))
        assertEquals(emptyList<String>(), bad(params("operator" to "<", "cutoff" to 2.5)))
        assertTrue(bad(params("operator" to "==")).any { it.contains("operator must be one of") })
        assertTrue(bad(params("cutoff" to 2000000.0)).any { it.contains("cutoff must be between") })
        assertFalse(bad(params("operator" to "<")).any { it.contains("operator") })
    }

    @Test fun aWindowPipelineIsNeverPublishedNorAnInterpreter() {
        val p = pipe(listOf(scenes(), agg(), cmp()), listOf(PipelineEdge("scenes", "agg"), PipelineEdge("agg", "cmp")))
        assertEquals("the Scenes input is the tier-3 node that keeps it off the glasses", listOf("scenes"), PlanShape.tier3Nodes(p))
        assertFalse(PlanShape.hasAsyncHalf(p))
        assertTrue(InterpretableShape.problems(p).toString(), InterpretableShape.problems(p).isNotEmpty())
    }

    private fun problemsOf(p: Pipeline) = PipelineValidator.validate(p)
}
