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

/** Moment-lite: `input.moments` is the ASYNC root whose MOMENTS output only `py.fn` accepts; it is never published. */
class MomentNodeTypesTest {
    private val none = JsonObject(emptyMap())
    private fun moments() = PipelineNode("moments", NodeTypes.INPUT_MOMENTS, "Moments", none)
    private fun photos() = PipelineNode("photos", NodeTypes.INPUT_PHOTOS, "Photo", none)
    private fun fn(id: String = "fn") = PipelineNode(id, NodeTypes.PY_FN, id, buildJsonObject { put("source", "def run(x):\n    return {'n': 1}\n"); put("outSchema", "{\"type\":\"object\"}") })
    private fun pipe(nodes: List<PipelineNode>, edges: List<PipelineEdge>) = Pipeline(2, "p", "P", nodes, edges)
    private fun problems(p: Pipeline) = PipelineValidator.validate(p)

    @Test fun registryEntryIsAnAsyncPaletteRoot() {
        val t = NodeTypes.byType("input.moments")
        assertNotNull(t); t!!
        assertEquals(Domain.ASYNC, t.domain); assertEquals(Category.INPUTS, t.category); assertTrue(t.root); assertTrue("palette-visible like py.fn", t.palette)
        assertFalse(t.liveable); assertEquals(emptySet<PortType>(), t.inputs); assertEquals(PortType.MOMENTS, t.output); assertEquals(2, t.sinceSchema)
        assertNull("a v1 graph cannot hold it", NodeTypes.resolve("input.moments", 1))
    }

    @Test fun momentsFeedPyFnAndThePipelineIsValid() {
        assertEquals(emptyList<String>(), problems(pipe(listOf(moments(), fn()), listOf(PipelineEdge("moments", "fn")))))
        assertEquals("py.fn after py.fn still takes JSON", emptyList<String>(),
            problems(pipe(listOf(moments(), fn("a"), fn("b")), listOf(PipelineEdge("moments", "a"), PipelineEdge("a", "b")))))
    }

    @Test fun noOtherNodeAcceptsMoments() {
        for (type in listOf(NodeTypes.VISION_EXTRACT, NodeTypes.PROMPT, NodeTypes.CRITIC, NodeTypes.LOCAL_CRITIC, NodeTypes.SWITCH, NodeTypes.MEMORY_WRITE)) {
            val params = if (type == NodeTypes.PROMPT) buildJsonObject { put("schema", "{\"type\":\"object\"}") } else none
            val r = problems(pipe(listOf(moments(), PipelineNode("x", type, "x", params)), listOf(PipelineEdge("moments", "x"))))
            assertTrue("$type must refuse MOMENTS: $r", r.any { it.contains("MOMENTS not accepted by x") })
        }
    }

    @Test fun oneInputRootOnly() {
        val both = problems(pipe(listOf(moments(), photos(), fn()), listOf(PipelineEdge("moments", "fn"))))
        assertTrue(both.toString(), "exactly one input root (input.photos or input.moments) required" in both)
        val two = problems(pipe(listOf(moments(), PipelineNode("m2", NodeTypes.INPUT_MOMENTS, "M", none), fn()), listOf(PipelineEdge("moments", "fn"))))
        assertTrue("exactly one input root (input.photos or input.moments) required" in two)
        val none = problems(pipe(listOf(fn()), emptyList()))
        assertTrue("exactly one input.photos required" in none)
    }

    @Test fun theRootTakesNoIncomingEdge() {
        val r = problems(pipe(listOf(moments(), fn("a"), fn("b")), listOf(PipelineEdge("moments", "a"), PipelineEdge("a", "moments"))))
        assertTrue(r.toString(), r.any { it.contains("node moments: input must have no incoming edge") })
    }

    @Test fun neverPublishableBecauseItIsTier3() {
        assertEquals(listOf("moments"), PlanShape.tier3Nodes(pipe(listOf(moments()), emptyList())))
    }

    @Test fun photoGraphsAreUnchanged() {
        assertEquals(emptyList<String>(), problems(pipe(listOf(photos(), PipelineNode("e", NodeTypes.VISION_EXTRACT, "e", none)), listOf(PipelineEdge("photos", "e")))))
    }
}
