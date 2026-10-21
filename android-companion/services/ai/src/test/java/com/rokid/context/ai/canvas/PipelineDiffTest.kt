package com.rokid.context.ai.canvas

import com.rokid.context.prompt.LineDiff
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PipelineDiffTest {
    private val live = DefaultPipelines.liveExtract()
    private fun draft(template: String, schema: String? = "{}") = buildJsonObject {
        put("batch", 3); putJsonObject("prompt") { put("source", "draft"); put("template", template); if (schema != null) put("schema", schema) }
    }
    private fun withExtract(params: JsonObject) = live.copy(nodes = live.nodes.map { if (it.id == "extract") it.copy(params = params) else it })

    @Test fun identicalIsEmpty() {
        val r = PipelineDiff.of(live, live)
        assertEquals(PipelineDiff.Result(emptyList(), emptyList(), emptyList(), emptyList(), false), r)
    }

    @Test fun addedRemovedChangedNodes() {
        val b = live.copy(
            nodes = live.nodes.filter { it.id != "critic" }.map { if (it.id == "extract") it.copy(params = buildJsonObject { put("batch", 1); putJsonObject("prompt") { put("source", "live") } }) else it } +
                PipelineNode("extra", NodeTypes.VISION_EXTRACT, "Extra", JsonObject(emptyMap())),
        )
        val r = PipelineDiff.of(live, b)
        assertEquals(
            listOf(PipelineDiff.NodeChange("critic", "removed", emptyList()), PipelineDiff.NodeChange("extra", "added", emptyList()), PipelineDiff.NodeChange("extract", "changed", listOf("batch"))),
            r.nodes,
        )
        assertTrue(r.prompts.isEmpty())
    }

    @Test fun edgesAddedRemoved() {
        val b = live.copy(edges = live.edges.filter { it != PipelineEdge("critic", "write", "fail") } + PipelineEdge("photos", "trust"))
        val r = PipelineDiff.of(live, b)
        assertEquals(listOf(PipelineEdge("photos", "trust")), r.edgesAdded)
        assertEquals(listOf(PipelineEdge("critic", "write", "fail")), r.edgesRemoved)
    }

    @Test fun promptTemplateLineDiff() {
        val r = PipelineDiff.of(withExtract(draft("a\nb")), withExtract(draft("a\nc")))
        val d = r.prompts.single { it.part == "template" }
        assertEquals("extract", d.nodeId)
        assertEquals(listOf(LineDiff.Line(LineDiff.Kind.SAME, "a"), LineDiff.Line(LineDiff.Kind.REMOVED, "b"), LineDiff.Line(LineDiff.Kind.ADDED, "c")), d.lines)
        assertTrue(r.prompts.none { it.part == "schema" })
        // Live to draft shows the draft's lines against one fixed Live line
        val live2draft = PipelineDiff.of(live, withExtract(draft("a")))
        assertEquals(listOf(LineDiff.Line(LineDiff.Kind.REMOVED, PipelineDiff.LIVE_PROMPT), LineDiff.Line(LineDiff.Kind.ADDED, "a")), live2draft.prompts.single { it.part == "template" }.lines)
    }

    @Test fun schemaDiff() {
        val r = PipelineDiff.of(withExtract(draft("a", "{\"x\":1}")), withExtract(draft("a", "{\"x\":2}")))
        val d = r.prompts.single()
        assertEquals("schema", d.part)
        assertEquals(listOf(LineDiff.Line(LineDiff.Kind.REMOVED, "{\"x\":1}"), LineDiff.Line(LineDiff.Kind.ADDED, "{\"x\":2}")), d.lines)
        // an ai.prompt node's own template and schema count too
        fun p(t: String) = Pipeline(1, "p", "P", listOf(PipelineNode("i", NodeTypes.PROMPT, "i", buildJsonObject { put("template", t); put("schema", "{}") })), emptyList())
        assertEquals(listOf("template"), PipelineDiff.of(p("one"), p("two")).prompts.map { it.part })
    }

    @Test fun renameDetected() {
        assertTrue(PipelineDiff.of(live, live.copy(name = "Other")).renamed)
        assertTrue(!PipelineDiff.of(live, live.copy(id = "zzz")).renamed)
    }
}
