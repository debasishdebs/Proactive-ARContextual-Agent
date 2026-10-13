package com.rokid.context.ai.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FlowLayoutTest {
    private fun flow(nodes: List<FlowNode>, vararg edges: Pair<String, String>) =
        AgentFlow("t", "T", "test", nodes, edges.map { FlowEdge(it.first, it.second) })
    private fun input(id: String) = FlowNode(id, id, NodeKind.INPUT)
    private fun node(id: String) = FlowNode(id, id, NodeKind.STEP)

    @Test fun linearChainIsOneColumn() {
        val l = FlowLayouts.layout(flow(listOf(input("a"), node("b"), node("c")), "a" to "b", "b" to "c"))
        assertEquals(mapOf("a" to Cell(0, 0), "b" to Cell(1, 0), "c" to Cell(2, 0)), l.cells)
        assertEquals(3, l.layers)
        assertEquals(1, l.columns)
    }

    @Test fun branchPutsSiblingsInOneLayerTwoColumns() {
        val l = FlowLayouts.layout(flow(listOf(input("a"), node("b"), node("c")), "a" to "b", "a" to "c"))
        assertEquals(Cell(1, 0), l.cells["b"])
        assertEquals(Cell(1, 1), l.cells["c"])
        assertEquals(2, l.layers)
        assertEquals(2, l.columns)
    }

    @Test fun sideInputSitsOneLayerAboveItsTarget() {
        val l = FlowLayouts.layout(flow(listOf(input("a"), node("b"), node("c"), input("s")), "a" to "b", "b" to "c", "s" to "c"))
        assertEquals(2, l.cells.getValue("c").layer)
        assertEquals(1, l.cells.getValue("s").layer)
        assertEquals(0, l.cells.getValue("a").layer)
    }

    @Test fun sideInputAboveALayerZeroTargetShiftsEverythingDown() {
        val l = FlowLayouts.layout(flow(listOf(input("a"), node("b"), input("s")), "a" to "b", "s" to "a"))
        assertEquals(0, l.cells.getValue("s").layer)
        assertEquals(1, l.cells.getValue("a").layer)
        assertEquals(2, l.cells.getValue("b").layer)
        assertEquals(3, l.layers)
    }

    @Test fun twoNodeSideChainEndsOneLayerAboveItsJoin() {
        val l = FlowLayouts.layout(AgentFlows.byId(AgentFlows.CAPTURE)!!)
        val save = l.cells.getValue("save").layer
        assertEquals(save - 1, l.cells.getValue("remember").layer)
        assertEquals(save - 2, l.cells.getValue("remember_in").layer)
        assertEquals(l.cells.getValue("memory").layer - 1, l.cells.getValue("critic_review").layer)
        assertEquals(save - 1, l.cells.getValue("resummarize").layer)
        assertEquals(l.cells.getValue("remember").column, l.cells.getValue("remember_in").column)
    }

    @Test fun sideChainsSitOutsideTheMainColumns() {
        val l = FlowLayouts.layout(AgentFlows.byId(AgentFlows.CAPTURE)!!)
        val side = setOf("remember_in", "remember", "thumbs_in", "critic_review", "forget_in", "resummarize")
        val sideCols = l.cells.filterKeys { it in side }.values.map { it.column }.toSet()
        val mainCols = l.cells.filterKeys { it !in side }.values.map { it.column }.toSet()
        assertTrue("side lanes $sideCols overlap main columns $mainCols", sideCols.intersect(mainCols).isEmpty())
        assertNotEquals(l.cells.getValue("remember").column, l.cells.getValue("resummarize").column)
    }

    @Test fun noTwoNodesShareACell() {
        AgentFlows.all.forEach { f ->
            val l = FlowLayouts.layout(f)
            assertEquals("${f.id}: every node placed", f.nodes.map { it.id }.toSet(), l.cells.keys)
            assertEquals("${f.id}: shared cell", l.cells.size, l.cells.values.toSet().size)
            l.cells.values.forEach { c -> assertTrue("${f.id}: $c out of bounds", c.layer in 0 until l.layers && c.column in 0 until l.columns) }
        }
    }

    @Test fun layerStrictlyIncreasesAlongEveryEdge() {
        AgentFlows.all.forEach { f ->
            val l = FlowLayouts.layout(f)
            f.edges.forEach { e ->
                assertTrue("${f.id}: ${e.from}->${e.to}", l.cells.getValue(e.from).layer < l.cells.getValue(e.to).layer)
            }
        }
        val capture = FlowLayouts.layout(AgentFlows.byId(AgentFlows.CAPTURE)!!)
        assertTrue(capture.cells.getValue("reprocess").layer < capture.cells.getValue("save").layer)
    }

    @Test fun layoutIsDeterministic() {
        AgentFlows.all.forEach { f -> assertEquals(FlowLayouts.layout(f), FlowLayouts.layout(f.copy())) }
    }
}
