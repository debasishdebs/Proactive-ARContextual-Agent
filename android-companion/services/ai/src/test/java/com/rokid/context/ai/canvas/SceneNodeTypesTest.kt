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

/** Scenes: `input.scenes` is an ASYNC input root next to `input.moments`; its SCENES output is read by the phone-side executor only and the node is never published to the glasses. */
class SceneNodeTypesTest {
    private val none = JsonObject(emptyMap())
    private fun scenes(id: String = "scenes") = PipelineNode(id, NodeTypes.INPUT_SCENES, "Scenes", none)
    private fun moments() = PipelineNode("moments", NodeTypes.INPUT_MOMENTS, "Moments", none)
    private fun photos() = PipelineNode("photos", NodeTypes.INPUT_PHOTOS, "Photo", none)
    private fun fn(id: String = "fn") = PipelineNode(id, NodeTypes.PY_FN, id, buildJsonObject { put("source", "def run(x):\n    return {'n': 1}\n"); put("outSchema", "{\"type\":\"object\"}") })
    private fun pipe(nodes: List<PipelineNode>, edges: List<PipelineEdge> = emptyList()) = Pipeline(2, "p", "P", nodes, edges)
    private fun problems(p: Pipeline) = PipelineValidator.validate(p)

    @Test fun registryEntryIsAnAsyncPaletteRootNextToMoments() {
        val t = NodeTypes.byType("input.scenes")
        assertNotNull(t); t!!
        assertEquals(Domain.ASYNC, t.domain); assertEquals(Category.INPUTS, t.category); assertTrue(t.root); assertTrue(t.palette)
        assertFalse(t.liveable); assertEquals(emptySet<PortType>(), t.inputs); assertEquals(PortType.SCENES, t.output); assertEquals(2, t.sinceSchema)
        assertEquals(ExecutorKind.BUILTIN, t.executor); assertEquals(0, t.cost.modelCalls)
        assertNull("a v1 graph cannot hold it", NodeTypes.resolve("input.scenes", 1))
    }

    @Test fun aScenesPipelineIsValid() {
        assertEquals(emptyList<String>(), problems(pipe(listOf(scenes()))))
    }

    @Test fun onlyPromptAndPythonAcceptScenes() {
        val prompt = buildJsonObject { put("schema", "{\"type\":\"object\"}") }
        for (type in listOf(NodeTypes.PROMPT, NodeTypes.PY_FN)) {
            val params = if (type == NodeTypes.PROMPT) prompt else buildJsonObject { put("source", "def run(x):\n    return {'n': 1}\n"); put("outSchema", "{\"type\":\"object\"}") }
            val r = problems(pipe(listOf(scenes(), PipelineNode("x", type, "x", params)), listOf(PipelineEdge("scenes", "x"))))
            assertEquals("$type takes SCENES", emptyList<String>(), r)
        }
        for (type in listOf(NodeTypes.VISION_EXTRACT, NodeTypes.CRITIC, NodeTypes.SWITCH, NodeTypes.MEMORY_WRITE)) {
            val r = problems(pipe(listOf(scenes(), PipelineNode("x", type, "x", none)), listOf(PipelineEdge("scenes", "x"))))
            assertTrue("$type must refuse SCENES: $r", r.any { it.contains("SCENES not accepted by x") })
        }
    }

    @Test fun oneInputRootOnlyAcrossPhotosMomentsAndScenes() {
        val msg = "exactly one input root (input.photos, input.moments or input.scenes) required"
        assertTrue(problems(pipe(listOf(scenes(), photos()))).toString(), msg in problems(pipe(listOf(scenes(), photos()))))
        assertTrue(msg in problems(pipe(listOf(scenes(), moments()))))
        assertTrue(msg in problems(pipe(listOf(scenes(), scenes("s2")))))
    }

    @Test fun theMomentsMessageIsUnchanged() {
        val two = problems(pipe(listOf(moments(), photos(), fn()), listOf(PipelineEdge("moments", "fn"))))
        assertTrue("exactly one input root (input.photos or input.moments) required" in two)
    }

    @Test fun theRootTakesNoIncomingEdge() {
        val r = problems(pipe(listOf(scenes(), fn()), listOf(PipelineEdge("fn", "scenes"))))
        assertTrue(r.toString(), r.any { it.contains("node scenes: input must have no incoming edge") })
    }

    @Test fun neverPublishableBecauseItIsTier3() {
        assertEquals(listOf("scenes"), PlanShape.tier3Nodes(pipe(listOf(scenes()))))
        assertFalse(PlanShape.hasAsyncHalf(pipe(listOf(scenes()))))
    }
}
