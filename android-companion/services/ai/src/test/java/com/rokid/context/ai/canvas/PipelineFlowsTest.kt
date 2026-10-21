package com.rokid.context.ai.canvas

import com.rokid.context.ai.Role
import com.rokid.context.ai.contract.PromptKey
import com.rokid.context.ai.flow.Facet
import com.rokid.context.ai.flow.NodeKind
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PipelineFlowsTest {
    private val live = DefaultPipelines.liveExtract()
    private val intent = DefaultPipelines.intentDraft("S")

    @Test fun kindsMapped() {
        val f = PipelineFlows.toAgentFlow(live)
        assertEquals("live-extract", f.id); assertEquals("Live extraction", f.title); assertEquals("Experiment pipeline", f.purpose)
        val n = f.nodes.associateBy { it.id }
        assertEquals(NodeKind.INPUT, n.getValue("photos").kind)
        assertEquals(NodeKind.OUTPUT, n.getValue("write").kind)
        assertEquals(listOf(Facet.Prompt(PromptKey.VISION_EXTRACT), Facet.Model(Role.VISION_EXTRACT)), n.getValue("extract").facets)
        assertEquals(listOf(Facet.Prompt(PromptKey.CRITIC), Facet.Model(Role.CRITIC)), n.getValue("critic").facets)
        assertEquals(NodeKind.STEP, n.getValue("trust").kind)
        assertTrue(n.getValue("trust").facets.isEmpty())
        val g = PipelineFlows.toAgentFlow(intent).nodes.associateBy { it.id }
        assertEquals(listOf<Facet>(Facet.Custom("canvas.prompt")), g.getValue("intent").facets)
        assertTrue(g.getValue("route").facets.isEmpty())
    }

    @Test fun edgeLabelsFromWhen() {
        val f = PipelineFlows.toAgentFlow(live)
        assertEquals(live.edges.map { Triple(it.from, it.to, it.`when`) }, f.edges.map { Triple(it.from, it.to, it.label) })
    }

    @Test fun layoutCoversEveryNode() {
        for (p in listOf(live, intent)) {
            val l = PipelineFlows.layout(p)
            assertEquals(p.nodes.map { it.id }.toSet(), l.cells.keys)
            assertTrue(l.layers > 0 && l.columns > 0)
        }
    }

    @Test fun layoutJsonShape() {
        val j = PipelineFlows.layoutJson(live)
        val l = PipelineFlows.layout(live)
        assertEquals(l.layers.toString(), j["layers"]!!.jsonPrimitive.content)
        assertEquals(l.columns.toString(), j["columns"]!!.jsonPrimitive.content)
        val cells = j["cells"]!!.jsonObject
        assertEquals(live.nodes.map { it.id }.toSet(), cells.keys)
        val c = cells.getValue("extract").jsonObject
        assertEquals(l.cells.getValue("extract").layer.toString(), c["layer"]!!.jsonPrimitive.content)
        assertEquals(l.cells.getValue("extract").column.toString(), c["column"]!!.jsonPrimitive.content)
    }
}
