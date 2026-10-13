package com.rokid.context.phone.ui.settings.flows

import com.rokid.context.ai.flow.AgentFlow
import com.rokid.context.ai.flow.AgentFlows
import com.rokid.context.ai.flow.FlowLayouts
import com.rokid.context.ai.flow.flows.CaptureFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Final review I1: no edge runs through a node it does not touch, and every label sits on its own edge, clear of nodes and other labels. */
class EdgeRoutingTest {
    private fun routed(f: AgentFlow) = EdgeRouting.route(FlowLayouts.layout(f), f)
    private fun boxes(f: AgentFlow) = FlowLayouts.layout(f).let { l -> f.nodes.filter { it.id in l.cells }.associate { it.id to EdgeRouting.nodeBox(l, it) } }

    /** Axis-aligned segment [a]-[b] against the open interior of [r]. */
    private fun crosses(a: Pt, b: Pt, r: Box): Boolean {
        val e = 0.01f
        val (x0, x1) = minOf(a.x, b.x) to maxOf(a.x, b.x)
        val (y0, y1) = minOf(a.y, b.y) to maxOf(a.y, b.y)
        assertTrue("segment $a-$b is not axis-aligned", a.x == b.x || a.y == b.y)
        return x1 > r.left + e && x0 < r.right - e && y1 > r.top + e && y0 < r.bottom - e
    }

    private fun onPath(p: Pt, pts: List<Pt>) = pts.zipWithNext().any { (a, b) ->
        p.x in minOf(a.x, b.x) - 0.01f..maxOf(a.x, b.x) + 0.01f && p.y in minOf(a.y, b.y) - 0.01f..maxOf(a.y, b.y) + 0.01f
    }

    @Test fun noEdgeSegmentCrossesANodeItDoesNotTouch() {
        AgentFlows.all.forEach { f ->
            val nodes = boxes(f)
            routed(f).edges.forEach { r ->
                assertTrue(r.points.size >= 2)
                nodes.filterKeys { it != r.edge.from && it != r.edge.to }.forEach { (id, box) ->
                    r.points.zipWithNext().forEach { (a, b) -> assertFalse("${f.id}: ${r.edge.from}->${r.edge.to} crosses $id", crosses(a, b, box)) }
                }
            }
        }
    }

    @Test fun noLabelOverlapsANode() {
        AgentFlows.all.forEach { f ->
            val nodes = boxes(f)
            routed(f).edges.filter { it.edge.label != null }.forEach { r ->
                val label = assertNotNullAnd(r.label, "${f.id}: ${r.edge.from}->${r.edge.to} has no label box")
                nodes.forEach { (id, box) -> assertFalse("${f.id}: label of ${r.edge.from}->${r.edge.to} overlaps $id", label.intersects(box)) }
            }
        }
    }

    @Test fun noTwoLabelsOverlap() {
        AgentFlows.all.forEach { f ->
            val labels = routed(f).edges.mapNotNull { r -> r.label?.let { r.edge to it } }
            labels.forEachIndexed { i, (e, a) ->
                labels.drop(i + 1).forEach { (o, b) -> assertFalse("${f.id}: ${e.from}->${e.to} and ${o.from}->${o.to}", a.intersects(b)) }
            }
        }
    }

    @Test fun labelsSitOnTheirOwnEdgesAndInsideTheCanvas() {
        AgentFlows.all.forEach { f ->
            val g = routed(f)
            g.edges.forEach { r ->
                val l = r.label ?: return@forEach
                assertTrue("${f.id}: ${r.edge.from}->${r.edge.to}", onPath(l.centre, r.points))
                assertTrue(l.left >= 0f && l.top >= 0f && l.right <= g.width && l.bottom <= g.height)
            }
            g.edges.forEach { r -> r.points.forEach { p -> assertTrue(p.x in 0f..g.width && p.y in 0f..g.height) } }
        }
    }

    /**
     * The review's case: "trusted" (confidence->save) and "pass" (critic->save) skip layers, so they leave the main lane.
     * Each label sits on its own edge's path and covers no other edge, so neither reads as labelling save_review->reprocess.
     */
    @Test fun trustedAndPassLabelsSitOnTheirOwnEdges() {
        val g = routed(CaptureFlow)
        val nodes = boxes(CaptureFlow)
        val byEdge = g.edges.associateBy { it.edge.from to it.edge.to }
        val main = nodes.getValue("save_review")
        listOf("confidence" to "save", "critic" to "save").forEach { key ->
            val r = byEdge.getValue(key)
            val l = r.label!!
            assertTrue(onPath(l.centre, r.points))
            assertTrue("$key label is in the main lane", l.left >= main.right || l.right <= main.left)
            g.edges.filter { it !== r }.forEach { o ->
                o.points.zipWithNext().forEach { (a, b) -> assertFalse("$key label covers ${o.edge.from}->${o.edge.to}", crosses(a, b, l)) }
            }
        }
    }

    @Test fun routingIsDeterministic() {
        AgentFlows.all.forEach { f -> assertEquals(routed(f), routed(f)) }
    }

    private fun <T : Any> assertNotNullAnd(v: T?, msg: String): T { assertNotNull(msg, v); return v!! }
}
