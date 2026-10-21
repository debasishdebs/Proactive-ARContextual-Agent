package com.rokid.context.ai.canvas

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InterpretableShapeTest {
    private val none = JsonObject(emptyMap())
    private val schema = "{\"type\":\"object\"}"
    private fun photos() = PipelineNode("photos", NodeTypes.INPUT_PHOTOS, "Photo", none)
    private fun extract(id: String = "extract", batch: Int? = null) =
        PipelineNode(id, NodeTypes.VISION_EXTRACT, id, if (batch == null) none else buildJsonObject { put("batch", batch) })
    private fun prompt(id: String) = PipelineNode(id, NodeTypes.PROMPT, id, buildJsonObject { put("schema", schema); put("template", "T") })
    private fun sw(vararg cases: String) = PipelineNode("sw", NodeTypes.SWITCH, "sw", buildJsonObject { put("field", "a"); put("cases", JsonArray(cases.map { JsonPrimitive(it) })) })
    private fun pipe(nodes: List<PipelineNode>, edges: List<PipelineEdge>) = Pipeline(1, "p", "P", nodes, edges)
    private fun e(a: String, b: String, w: String? = null) = PipelineEdge(a, b, w)
    private fun assertHas(p: Pipeline, text: String) { val r = InterpretableShape.problems(p); assertTrue("expected '$text' in $r", text in r) }
    private val seed = "Extract {{recent_context}}"

    @Test fun intentDraftIsInterpretable() = assertEquals(emptyList<String>(), InterpretableShape.problems(DefaultPipelines.intentDraft(seed)))

    @Test fun liveExtractIsInterpretable() = assertEquals(emptyList<String>(), InterpretableShape.problems(DefaultPipelines.liveExtract()))

    @Test fun criticDraftBlocked() {
        val live = DefaultPipelines.liveExtract()
        val p = live.copy(nodes = live.nodes.map {
            if (it.id == "critic") it.copy(params = buildJsonObject { putJsonObject("prompt") { put("source", "draft"); put("template", "C"); put("schema", schema) } }) else it
        })
        assertHas(p, "node critic: the critic must use the Live prompt")
    }

    @Test fun promptAfterExtractBlocked() {
        val p = pipe(listOf(photos(), extract(), prompt("late")), listOf(e("photos", "extract"), e("extract", "late")))
        assertHas(p, "node late: only checks, critic and Save can follow extraction")
    }

    @Test fun pathWithoutExtractBlocked() {
        val p = pipe(listOf(photos(), prompt("intent")), listOf(e("photos", "intent")))
        assertHas(p, "photo path through intent reaches no extraction")
    }

    @Test fun twoExtractsOnOnePathBlocked() {
        val p = pipe(listOf(photos(), extract(), prompt("mid"), extract("second")), listOf(e("photos", "extract"), e("extract", "mid"), e("mid", "second")))
        assertHas(p, "photo path through second reaches more than one extraction")
    }

    @Test fun tooManyPromptNodes() {
        val p = pipe(
            listOf(photos(), prompt("p1"), prompt("p2"), prompt("p3"), prompt("p4"), extract()),
            listOf(e("photos", "p1"), e("p1", "p2"), e("p2", "p3"), e("p3", "p4"), e("p4", "extract")),
        )
        assertHas(p, "at most 3 prompt nodes")
        assertTrue("at most 3 prompt nodes" !in InterpretableShape.problems(p.copy(nodes = p.nodes.filter { it.id != "p4" }, edges = listOf(e("photos", "p1"), e("p1", "p2"), e("p2", "p3"), e("p3", "extract")))))
    }

    @Test fun invalidPipelineBlocked() {
        val p = pipe(listOf(photos(), extract()), listOf(e("photos", "extract"), e("photos", "ghost")))
        assertEquals(listOf("Fix the pipeline's problems first"), InterpretableShape.problems(p))
    }

    @Test fun parallelExtractorsBlocked() {
        val p = pipe(listOf(photos(), extract("e1"), extract("e2")), listOf(e("photos", "e1"), e("photos", "e2")))
        assertHas(p, "node photos: only a switch can send photos down two paths")
    }

    @Test fun missingOtherBlocked() {
        val full = DefaultPipelines.intentDraft(seed)
        val p = full.copy(nodes = full.nodes.filter { it.id != "extract_other" }, edges = full.edges.filter { it.from != "extract_other" && it.to != "extract_other" })
        assertEquals(emptyList<String>(), PipelineValidator.validate(p))
        assertHas(p, "node route: branch other isn't connected")
    }

    @Test fun everyBranchWiredPasses() {
        val p = pipe(
            listOf(photos(), prompt("intent"), sw("a"), extract("ea"), extract("eo")),
            listOf(e("photos", "intent"), e("intent", "sw"), e("sw", "ea", "a"), e("sw", "eo", "other")),
        )
        assertEquals(emptyList<String>(), InterpretableShape.problems(p))
        val unwired = p.copy(nodes = p.nodes.filter { it.id != "ea" }, edges = p.edges.filter { it.to != "ea" })
        assertHas(unwired, "node sw: branch a isn't connected")
    }

    @Test fun rangeIntentDraft() = assertEquals(4.0 to 6.0, InterpretableShape.callRange(DefaultPipelines.intentDraft(seed)))

    @Test fun batchOneExtractorIsThreeX() {
        val p = pipe(listOf(photos(), extract(batch = 1)), listOf(e("photos", "extract")))
        assertEquals(3.0 to 3.0, InterpretableShape.callRange(p))
        assertEquals(1.0 to 1.0, InterpretableShape.callRange(pipe(listOf(photos(), extract(batch = 3)), listOf(e("photos", "extract")))))
    }

    @Test fun mixedLabelsMax() {
        val p = pipe(
            listOf(photos(), prompt("intent"), sw("a"), extract("big", 3), extract("small", 1)),
            listOf(e("photos", "intent"), e("intent", "sw"), e("sw", "big", "a"), e("sw", "small", "other")),
        )
        // min: 3 photos on the batch-3 path = 3 + 1; max: 3 photos on the batch-1 path = 3 + 3 (2 + 1 split gives 3 + 3 too)
        assertEquals(4.0 to 6.0, InterpretableShape.callRange(p))
        assertEquals(0.0 to 0.0, InterpretableShape.callRange(pipe(listOf(photos()), emptyList())))
    }

    @Test fun preSaveNodesOrder() {
        val ids = InterpretableShape.preSaveNodes(DefaultPipelines.intentDraft(seed)).map { it.id }
        assertEquals(listOf("photos", "intent", "route"), ids.take(3))
        assertEquals(9, ids.size)
        assertTrue(ids.drop(3).all { it.startsWith("extract_") })
        assertEquals(listOf("photos", "extract"), InterpretableShape.preSaveNodes(DefaultPipelines.liveExtract()).map { it.id })
    }

    @Test fun warningsWhenNoTrustNode() {
        val minimal = pipe(listOf(photos(), extract()), listOf(e("photos", "extract")))
        assertEquals(listOf("Live always runs the confidence check and the critic after extraction"), InterpretableShape.warnings(minimal))
        assertEquals(emptyList<String>(), InterpretableShape.warnings(DefaultPipelines.liveExtract()))
    }
}
