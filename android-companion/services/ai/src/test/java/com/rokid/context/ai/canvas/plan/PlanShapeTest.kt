package com.rokid.context.ai.canvas.plan

import com.rokid.context.ai.canvas.NodeTypes
import com.rokid.context.ai.canvas.Pipeline
import com.rokid.context.ai.canvas.PipelineEdge
import com.rokid.context.ai.canvas.PipelineHash
import com.rokid.context.ai.canvas.PipelineNode
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The ASYNC projection of a full graph (S72) keeps slice 2's tested identity; gate 2's tier-3 rule. */
class PlanShapeTest {
    private fun node(id: String, type: String) = PipelineNode(id, type, id, JsonObject(emptyMap()))
    private val asyncNodes = listOf(node("photos", NodeTypes.INPUT_PHOTOS), node("extract", NodeTypes.VISION_EXTRACT), node("save", NodeTypes.MEMORY_WRITE))
    private val asyncEdges = listOf(PipelineEdge("photos", "extract"), PipelineEdge("extract", "save"))
    private val rtNodes = listOf(node("head", NodeTypes.IMU_HEAD_MOTION), node("settle", NodeTypes.IMU_SETTLE), node("cam", NodeTypes.CAPTURE_IMAGE), node("hub", NodeTypes.HUB_CONTEXT))
    private val rtEdges = listOf(PipelineEdge("head", "settle"), PipelineEdge("settle", "cam"))
    private val asyncOnly = Pipeline(2, "p", "P", asyncNodes, asyncEdges)
    private val full = Pipeline(2, "p", "P", asyncNodes + rtNodes, asyncEdges + rtEdges + PipelineEdge("cam", "photos"))

    @Test fun anAsyncOnlyGraphIsItsOwnProjection() {
        assertSame(asyncOnly, PlanShape.asyncProjection(asyncOnly))
        assertFalse(PlanShape.hasCaptureHalf(asyncOnly)); assertTrue(PlanShape.hasAsyncHalf(asyncOnly))
    }

    @Test fun theProjectionOfAFullGraphHasTheHashOfTheAsyncOnlyGraph() {
        val proj = full.asyncProjection()
        assertEquals(asyncNodes.map { it.id }, proj.nodes.map { it.id })
        assertEquals(asyncEdges, proj.edges)                                         // the photos binding edge from capture.image is not an ASYNC edge
        assertEquals(PipelineHash.of(asyncOnly), PipelineHash.of(proj))
        assertTrue(PlanShape.hasCaptureHalf(full) && PlanShape.hasAsyncHalf(full))
    }

    @Test fun aCaptureOnlyGraphHasNoAsyncHalf() {
        val rt = Pipeline(2, "p", "P", rtNodes, rtEdges)
        assertTrue(PlanShape.asyncProjection(rt).nodes.isEmpty()); assertFalse(PlanShape.hasAsyncHalf(rt))
    }

    @Test fun tier3IsPyOrToolNodes() {
        assertEquals(emptyList<String>(), PlanShape.tier3Nodes(full))
        val t3 = full.copy(nodes = full.nodes + node("z", "py.fn") + node("a", "tool.draft"))
        assertEquals(listOf("a", "z"), PlanShape.tier3Nodes(t3))
    }
}
