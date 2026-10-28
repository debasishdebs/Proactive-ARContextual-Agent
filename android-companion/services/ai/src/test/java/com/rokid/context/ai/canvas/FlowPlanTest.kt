package com.rokid.context.ai.canvas

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.9.8 slice B: the Query and Nudge flows validate, compile to the built-in plans, and refuse what is not live yet. */
class FlowPlanTest {
    private val query = DefaultPipelines.defaultQuery()
    private val nudge = DefaultPipelines.defaultNudge()
    private fun with(p: Pipeline, id: String, params: JsonObject) = p.copy(nodes = p.nodes.map { if (it.id == id) it.copy(params = params) else it })
    private fun refused(p: Pipeline) = (FlowPlan.compile(p) as FlowPlan.Compiled.Refused).reasons
    private fun plan(p: Pipeline) = (FlowPlan.compile(p) as FlowPlan.Compiled.Ok).plan

    @Test fun theDefaultPipelinesAreValidAndCompileToTheBuiltInPlans() {
        assertEquals(emptyList<String>(), PipelineValidator.validate(query))
        assertEquals(emptyList<String>(), PipelineValidator.validate(nudge))
        assertEquals(FlowPlan.BUILT_IN_QUERY, plan(query))
        assertEquals(FlowPlan.BUILT_IN_NUDGE, plan(nudge))
        assertTrue(DefaultPipelines.all.containsAll(listOf(DefaultPipelines.DEFAULT_QUERY, DefaultPipelines.DEFAULT_NUDGE)))
    }

    @Test fun theHashSurvivesEncodeAndDecode() {
        for (p in listOf(query, nudge)) assertEquals(PipelineHash.of(p), PipelineHash.of(PipelineJson.decode(PipelineJson.encode(p)).getOrThrow()))
    }

    @Test fun retrievalParamsReachThePlan() {
        val p = with(query, "retrieve", buildJsonObject { put("memories", true); put("photos", false); put("topK", 3); put("mode", "LEXICAL") })
        val c = plan(p)
        assertEquals(false, c.photos); assertEquals(JsonPrimitive(3), c.rag["topK"]); assertEquals(JsonPrimitive("LEXICAL"), c.rag["mode"])
        assertEquals(listOf("node retrieve: topK must be between 1 and 20"), PipelineValidator.validate(with(query, "retrieve", buildJsonObject { put("topK", 99) })))
    }

    private fun nudgeWith(type: String, params: JsonObject = JsonObject(emptyMap())) =
        nudge.copy(nodes = nudge.nodes.map { if (it.id == "trigger") it.copy(type = type, params = params) else it })

    @Test fun aNudgeMayRootAtAnySlice3CTriggerAndCompilesIt() {
        for (t in listOf(NodeTypes.TRIGGER_MOMENT, NodeTypes.TRIGGER_SCENE, NodeTypes.TRIGGER_SIGNAL, NodeTypes.TRIGGER_SCHEDULE)) {
            val p = nudgeWith(t)
            assertEquals(emptyList<String>(), PipelineValidator.validate(p))
            val c = plan(p)
            assertEquals(t, c.trigger); assertEquals(TriggerParams.DEFAULT_PER_HOUR, c.maxPerHour)
            assertTrue("a trigger plan differs from the built-in one", c != FlowPlan.BUILT_IN_NUDGE)
        }
        assertEquals(NodeTypes.TRIGGER_SUMMARIZER, plan(nudge).trigger)
    }

    @Test fun theSignalTriggerCarriesItsAggregateAndComparison() {
        val s = plan(nudgeWith(NodeTypes.TRIGGER_SIGNAL, buildJsonObject { put("feature", "gyroMax"); put("op", "max"); put("window", 5); put("operator", ">"); put("cutoff", 1.5); put("maxPerHour", 2) })).signal!!
        assertEquals(WindowAggregate.Settings("gyroMax", "max", 5), s.aggregate); assertEquals(NumberCompare.Settings(">", 1.5), s.compare)
        assertEquals(2, plan(nudgeWith(NodeTypes.TRIGGER_SIGNAL, buildJsonObject { put("maxPerHour", 2) })).maxPerHour)
    }

    @Test fun triggerParamRangesAreRefused() {
        assertEquals(listOf("node trigger: maxPerHour must be between 1 and 12"), PipelineValidator.validate(nudgeWith(NodeTypes.TRIGGER_MOMENT, buildJsonObject { put("maxPerHour", 99) })))
        assertEquals(listOf("node trigger: maxPerHour must be between 1 and 12"), PipelineValidator.validate(nudgeWith(NodeTypes.TRIGGER_SCENE, buildJsonObject { put("maxPerHour", 0) })))
        assertTrue(PipelineValidator.validate(nudgeWith(NodeTypes.TRIGGER_SIGNAL, buildJsonObject { put("window", 51) })).isNotEmpty())
        assertTrue(PipelineValidator.validate(nudgeWith(NodeTypes.TRIGGER_MOMENT, buildJsonObject { put("cron", "* * * * *") })).isNotEmpty()) // not a param of this trigger
    }

    @Test fun theScheduleFloorAndCronAreValidated() {
        fun problems(cron: String) = PipelineValidator.validate(nudgeWith(NodeTypes.TRIGGER_SCHEDULE, buildJsonObject { put("cron", cron) }))
        assertEquals(emptyList<String>(), problems("0 9 * * *"))
        assertEquals(emptyList<String>(), problems("*/15 * * * *"))
        assertEquals(emptyList<String>(), problems("30 8 * * 1-5"))
        assertEquals(listOf("node trigger: cron: runs more often than every 15 minutes"), problems("*/5 * * * *"))
        assertEquals(listOf("node trigger: cron: runs more often than every 15 minutes"), problems("0,10 9 * * *"))
        assertEquals(listOf("node trigger: cron: five fields (minute hour day month weekday)"), problems("0 9 * *"))
        assertEquals(listOf("node trigger: cron: hour field is not valid"), problems("0 25 * * *"))
        assertEquals(listOf("node trigger: cron: minute field is not valid"), problems("x 9 * * *"))
        // the problem text never carries the expression
        assertTrue(problems("secret 9 * * *").none { it.contains("secret") })
        assertEquals("0 9 * * *", plan(nudgeWith(NodeTypes.TRIGGER_SCHEDULE)).cron)
    }

    @Test fun aTriggerIsTheOneRootOfANudgeFlow() {
        val two = nudge.copy(nodes = nudge.nodes + PipelineNode("t2", NodeTypes.TRIGGER_MOMENT, "Moment", JsonObject(emptyMap())), edges = nudge.edges + PipelineEdge("t2", "retrieve"))
        assertTrue(PipelineValidator.validate(two).isNotEmpty())
        val mixed = nudge.copy(nodes = nudge.nodes + PipelineNode("u", NodeTypes.INPUT_UTTERANCE, "Utterance", JsonObject(emptyMap())), edges = nudge.edges + PipelineEdge("u", "retrieve"))
        assertTrue(PipelineValidator.validate(mixed).isNotEmpty())
    }

    @Test fun deliveryTargetsAreAllLiveExceptCallerForANudge() {
        for (t in listOf("caller", "phone", "glasses", "both")) assertEquals(t, plan(with(query, "deliver", buildJsonObject { put("target", t) })).target)
        for (t in listOf("phone", "glasses", "both")) assertEquals(t, plan(with(nudge, "deliver", buildJsonObject { put("target", t) })).target)
        assertEquals(listOf("node deliver: a Nudge has no caller; deliver to phone, glasses or both"), refused(with(nudge, "deliver", buildJsonObject { put("target", "caller") })))
        assertEquals(listOf("node retrieve: choose at least one source"), refused(with(query, "retrieve", buildJsonObject { put("memories", false); put("photos", false) })))
    }

    @Test fun theShapeIsFixed() {
        // a draft prompt, the wrong role, a missing gate, an extra node, and a photo node in a flow are all refused
        assertTrue(PipelineValidator.validate(with(query, "answer", buildJsonObject { put("role", "ANSWER"); putJsonObject("prompt") { put("source", "draft"); put("template", "x") } })).contains("node answer: a flow uses the live prompt"))
        assertTrue(PipelineValidator.validate(with(nudge, "nudge", buildJsonObject { put("role", "ANSWER"); putJsonObject("prompt") { put("source", "live") } })).contains("node nudge: role must be NUDGE"))
        val noGate = nudge.copy(nodes = nudge.nodes.filter { it.id != "gates" }, edges = nudge.edges.filter { it.from != "gates" && it.to != "gates" } + PipelineEdge("nudge", "deliver"))
        assertTrue(PipelineValidator.validate(noGate).any { it.startsWith("flow must be") })
        val extra = query.copy(nodes = query.nodes + PipelineNode("photos", NodeTypes.INPUT_PHOTOS, "Photo", JsonObject(emptyMap())))
        assertTrue(PipelineValidator.validate(extra).any { it.contains("exactly one input root") })
        val sideStep = query.copy(nodes = query.nodes + PipelineNode("gates", NodeTypes.GATE_NUDGE, "g", JsonObject(emptyMap())), edges = query.edges + PipelineEdge("answer", "gates"))
        assertTrue(PipelineValidator.validate(sideStep).isNotEmpty())
    }

    @Test fun theEmbedStepIsOptional() {
        val bare = query.copy(nodes = query.nodes.filter { it.id != "embed" }, edges = listOf(PipelineEdge("utterance", "retrieve")) + query.edges.filter { it.from != "utterance" && it.from != "embed" })
        assertEquals(emptyList<String>(), PipelineValidator.validate(bare))
        assertEquals(FlowPlan.BUILT_IN_QUERY, plan(bare))
    }

    @Test fun theFlowTypesAreSchema2Only() {
        for (t in listOf(NodeTypes.INPUT_UTTERANCE, NodeTypes.RAG_RETRIEVE, NodeTypes.OUT_DELIVER, NodeTypes.GATE_NUDGE, NodeTypes.TRIGGER_SCHEDULE)) {
            assertEquals(null, NodeTypes.resolve(t, 1)); assertTrue(NodeTypes.resolve(t, 2) != null)
        }
        assertEquals(true, NodeTypes.byType(NodeTypes.TRIGGER_SCHEDULE)!!.liveable)
    }

    @Test fun unreadSettingsAndNodeTextAreRefusedNotIgnored() {
        assertTrue(PipelineValidator.validate(with(query, "answer", buildJsonObject { put("role", "ANSWER"); put("template", "x"); putJsonObject("prompt") { put("source", "live") } }))
            .contains("node answer: a flow takes its prompt text from Settings, not from the node"))
        assertEquals(listOf("node retrieve: a Nudge reads recent memories and nudge history; memory and photo stores are for a Query"),
            refused(with(nudge, "retrieve", buildJsonObject { put("recent", true); put("memories", true) })))
        assertEquals(listOf("node retrieve: a Query has no image to search with"), refused(with(query, "retrieve", buildJsonObject { put("memories", true); put("imageQuery", true) })))
        assertEquals(true, plan(with(query, "retrieve", buildJsonObject { put("memories", true); put("recent", true); put("recentWindowMin", 30) })).recent)
    }
}
