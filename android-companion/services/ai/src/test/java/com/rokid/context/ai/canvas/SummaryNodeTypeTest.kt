package com.rokid.context.ai.canvas

import com.rokid.context.ai.canvas.plan.PlanShape
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `proc.summary`: a Process node over text, Moments, Scenes or facts, whose SUMMARY output the Confidence check and Save memory (preview) take. */
class SummaryNodeTypeTest {
    private val none = JsonObject(emptyMap())
    private fun moments() = PipelineNode("moments", NodeTypes.INPUT_MOMENTS, "Moments", none)
    private fun scenes() = PipelineNode("scenes", NodeTypes.INPUT_SCENES, "Scenes", none)
    private fun photos() = PipelineNode("photos", NodeTypes.INPUT_PHOTOS, "Photo", none)
    private fun prompt() = PipelineNode("ask", NodeTypes.PROMPT, "Prompt", buildJsonObject { put("schema", "{\"type\":\"object\"}") })
    private fun summary() = PipelineNode("sum", "proc.summary", "Summary", none)
    private fun trust() = PipelineNode("trust", NodeTypes.LOCAL_CRITIC, "Confidence check", none)
    private fun write() = PipelineNode("write", NodeTypes.MEMORY_WRITE, "Save memory", none)
    private fun pipe(nodes: List<PipelineNode>, edges: List<PipelineEdge>) = Pipeline(2, "p", "P", nodes, edges)
    private fun problems(p: Pipeline) = PipelineValidator.validate(p)

    @Test fun registryEntryIsAnAsyncProcessNodeWithASummaryOutput() {
        val t = NodeTypes.byType("proc.summary")
        assertNotNull(t); t!!
        assertEquals("Summary", t.label)
        assertEquals(Family.PROCESS, t.family); assertEquals(Domain.ASYNC, t.domain); assertEquals(PortType.SUMMARY, t.output)
        assertEquals(setOf(PortType.JSON, PortType.EXTRACTION, PortType.MOMENTS, PortType.SCENES), t.inputs)
        assertTrue("in the palette", t.palette); assertFalse(t.root)
        assertEquals("no prompt of its own to edit: the text is fixed", null, t.promptKey)
        assertEquals(ParamSchema.NONE, t.params)
        assertNull("a v1 graph cannot hold it", NodeTypes.resolve("proc.summary", 1))
    }

    @Test fun theUsersChainMomentsPromptSummaryConfidenceCheckSaveValidates() {
        val chain = pipe(
            listOf(moments(), prompt(), summary(), trust(), write()),
            listOf(PipelineEdge("moments", "ask"), PipelineEdge("ask", "sum"), PipelineEdge("sum", "trust"), PipelineEdge("trust", "write", "trusted"), PipelineEdge("trust", "write", "unsure")),
        )
        assertEquals(emptyList<String>(), problems(chain))
    }

    @Test fun summaryTakesTextMomentsAndScenesAndSavesItsOwnOutput() {
        for (src in listOf(moments(), scenes())) {
            assertEquals(emptyList<String>(), problems(pipe(listOf(src, summary(), write()), listOf(PipelineEdge(src.id, "sum"), PipelineEdge("sum", "write")))))
        }
        assertEquals(emptyList<String>(), problems(pipe(listOf(scenes(), prompt(), summary()), listOf(PipelineEdge("scenes", "ask"), PipelineEdge("ask", "sum")))))
    }

    @Test fun photosAreNotSummarisedAndTheSummaryIsNotAFactSource() {
        val r = problems(pipe(listOf(photos(), summary()), listOf(PipelineEdge("photos", "sum"))))
        assertTrue(r.toString(), r.any { it.contains("PHOTOS not accepted by sum") })
        val critic = problems(pipe(listOf(scenes(), summary(), PipelineNode("c", NodeTypes.CRITIC, "Critic", none)), listOf(PipelineEdge("scenes", "sum"), PipelineEdge("sum", "c"))))
        assertTrue(critic.toString(), critic.any { it.contains("SUMMARY not accepted by c") })
    }

    @Test fun promptNowAcceptsMomentsButNoOtherOldNodeDoes() {
        assertTrue(PortType.MOMENTS in NodeTypes.byType(NodeTypes.PROMPT)!!.inputs)
        for (type in listOf(NodeTypes.VISION_EXTRACT, NodeTypes.CRITIC, NodeTypes.LOCAL_CRITIC, NodeTypes.SWITCH, NodeTypes.MEMORY_WRITE)) {
            assertFalse("$type", PortType.MOMENTS in NodeTypes.byType(type)!!.inputs)
        }
    }

    @Test fun onlyTheTwoDownstreamNodesGainedTheSummaryInput() {
        val takers = NodeTypes.all.filter { PortType.SUMMARY in it.inputs }.map { it.type }.toSet()
        assertEquals(setOf(NodeTypes.LOCAL_CRITIC, NodeTypes.MEMORY_WRITE), takers)
    }

    @Test fun anInterpreterNeverHoldsASummary() {
        val p = pipe(
            listOf(photos(), PipelineNode("e", NodeTypes.VISION_EXTRACT, "e", none), summary(), write()),
            listOf(PipelineEdge("photos", "e"), PipelineEdge("e", "sum"), PipelineEdge("sum", "write")),
        )
        assertEquals(emptyList<String>(), problems(p))
        assertTrue(InterpretableShape.problems(p).toString(), InterpretableShape.problems(p).contains("node sum: only checks, critic and Save can follow extraction"))
    }
}
