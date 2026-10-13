package com.rokid.context.phone.ui.settings.flows

import com.rokid.context.ai.flow.AgentFlow
import com.rokid.context.ai.flow.Cell
import com.rokid.context.ai.flow.FlowEdge
import com.rokid.context.ai.flow.FlowLayout
import com.rokid.context.ai.flow.FlowNode
import kotlin.math.abs

/** A point in dp, relative to the top-left of a flow section's grid. */
data class Pt(val x: Float, val y: Float)

/** A rectangle in dp. [intersects] is strict: boxes that only share an edge do not overlap. */
data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val centre: Pt get() = Pt((left + right) / 2, (top + bottom) / 2)
    fun intersects(o: Box): Boolean = left < o.right - EPS && o.left < right - EPS && top < o.bottom - EPS && o.top < bottom - EPS
    private companion object { const val EPS = 0.01f }
}

/** One drawn edge: an axis-aligned polyline from the source's bottom centre to the target's top centre, and its label box. */
data class RoutedEdge(val edge: FlowEdge, val points: List<Pt>, val label: Box?)

/** [columns] can exceed the layout's when a skip-layer edge needs a detour lane; [width] and [height] are the grid size in dp. */
data class RoutedGraph(val edges: List<RoutedEdge>, val columns: Int, val width: Float, val height: Float)

/**
 * Edge routing for the flow graph (final review I1). Pure and deterministic, so it is unit-tested without Compose.
 * - An edge to the next layer goes straight down, or down to the gap below its source, across, and down.
 * - An edge that skips layers leaves its source through the gap below it, runs down a lane that has no node in the layers
 *   it skips, and comes back across in the gap above its target. Shorter spans pick first; each picks the free lane nearest
 *   its source (ties right). Two edges share a lane only when their skipped layers do not overlap. With no free lane, a
 *   detour lane is added right of the grid.
 * - Labels are placed in declaration order on their own edge's path, starting at the middle of its longest segment and
 *   walking outwards, at the first spot clear of every node, every placed label and the grid's bounds.
 */
object EdgeRouting {
    const val CELL_W = 172f
    const val CELL_H = 140f
    const val NODE_W = 152f
    const val CARD_H = 96f
    const val PILL_H = 44f
    const val LABEL_W = 136f
    const val LABEL_H = 32f
    private const val STEP = 2f

    fun nodeBox(layout: FlowLayout, node: FlowNode): Box {
        val c = layout.cells.getValue(node.id)
        val left = c.column * CELL_W + (CELL_W - NODE_W) / 2
        val top = c.layer * CELL_H
        return Box(left, top, left + NODE_W, top + if (node.isAiStep) CARD_H else PILL_H)
    }

    private fun laneX(column: Int) = column * CELL_W + CELL_W / 2
    /** Middle of the gap below [layer]: under the tallest node, above the next layer. */
    private fun gapY(layer: Int) = layer * CELL_H + (CARD_H + CELL_H) / 2

    fun route(layout: FlowLayout, flow: AgentFlow): RoutedGraph {
        val byId = flow.nodes.associateBy { it.id }
        val edges = flow.edges.filter { it.from in byId && it.to in byId && it.from in layout.cells && it.to in layout.cells }
        val occupied = layout.cells.values.toSet()
        val boxes = flow.nodes.filter { it.id in layout.cells }.map { nodeBox(layout, it) }

        // Lanes for skip-layer edges: (column -> skipped-layer spans already using it).
        var columns = layout.columns
        val laneUse = mutableMapOf<Int, MutableList<IntRange>>()
        val lane = mutableMapOf<Int, Int>() // edge index -> lane column
        edges.withIndex()
            .filter { (_, e) -> layout.cells.getValue(e.to).layer - layout.cells.getValue(e.from).layer > 1 }
            .sortedWith(compareBy({ (_, e) -> layout.cells.getValue(e.to).layer - layout.cells.getValue(e.from).layer }, { it.index }))
            .forEach { (i, e) ->
                val a = layout.cells.getValue(e.from); val b = layout.cells.getValue(e.to)
                val skipped = (a.layer + 1) until b.layer
                fun free(c: Int) = skipped.none { Cell(it, c) in occupied } &&
                    laneUse[c].orEmpty().none { it.first <= skipped.last && skipped.first <= it.last }
                val pick = (0 until columns).filter(::free).minWithOrNull(compareBy({ abs(it - a.column) }, { if (it >= a.column) 0 else 1 }))
                    ?: columns++
                laneUse.getOrPut(pick) { mutableListOf() } += skipped
                lane[i] = pick
            }

        val width = columns * CELL_W
        val height = (layout.layers.coerceAtLeast(1) - 1) * CELL_H + CARD_H
        val placed = mutableListOf<Box>()
        val routed = edges.mapIndexed { i, e ->
            val a = layout.cells.getValue(e.from); val b = layout.cells.getValue(e.to)
            val src = nodeBox(layout, byId.getValue(e.from)); val dst = nodeBox(layout, byId.getValue(e.to))
            val start = Pt(src.centre.x, src.bottom); val end = Pt(dst.centre.x, dst.top)
            val pts = when (val l = lane[i]) {
                null -> listOf(start, Pt(start.x, gapY(a.layer)), Pt(end.x, gapY(a.layer)), end)
                else -> listOf(start, Pt(start.x, gapY(a.layer)), Pt(laneX(l), gapY(a.layer)), Pt(laneX(l), gapY(b.layer - 1)), Pt(end.x, gapY(b.layer - 1)), end)
            }.let(::simplify)
            val label = e.label?.let { place(pts, boxes, placed, width, height) }?.also { placed += it }
            RoutedEdge(e, pts, label)
        }
        return RoutedGraph(routed, columns, width, height)
    }

    /** Drops repeated points and the middle point of collinear runs. */
    private fun simplify(pts: List<Pt>): List<Pt> {
        val out = mutableListOf<Pt>()
        for (p in pts) {
            if (out.lastOrNull() == p) continue
            if (out.size >= 2) {
                val a = out[out.size - 2]; val b = out.last()
                if ((a.x == b.x && b.x == p.x) || (a.y == b.y && b.y == p.y)) out.removeAt(out.size - 1)
            }
            out += p
        }
        return out
    }

    private fun place(pts: List<Pt>, nodes: List<Box>, labels: List<Box>, width: Float, height: Float): Box {
        val segs = pts.zipWithNext()
        val lengths = segs.map { (a, b) -> abs(a.x - b.x) + abs(a.y - b.y) }
        val total = lengths.sum()
        val longest = lengths.indices.maxByOrNull { lengths[it] } ?: 0
        val mid = lengths.take(longest).sum() + lengths[longest] / 2
        fun at(d: Float): Pt {
            var rest = d.coerceIn(0f, total)
            segs.forEachIndexed { k, (a, b) ->
                if (rest <= lengths[k] || k == segs.lastIndex) {
                    val t = if (lengths[k] == 0f) 0f else rest / lengths[k]
                    return Pt(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
                }
                rest -= lengths[k]
            }
            return pts.last()
        }
        fun boxAt(p: Pt) = Box(p.x - LABEL_W / 2, p.y - LABEL_H / 2, p.x + LABEL_W / 2, p.y + LABEL_H / 2)
        val n = (total / STEP).toInt() + 1
        val candidates = sequence {
            yield(mid)
            for (k in 1..n) { yield(mid + k * STEP); yield(mid - k * STEP) }
        }.filter { it in 0f..total }
        val first = boxAt(at(mid))
        return candidates.map { boxAt(at(it)) }.firstOrNull { b ->
            b.left >= 0f && b.top >= 0f && b.right <= width && b.bottom <= height && nodes.none(b::intersects) && labels.none(b::intersects)
        } ?: first
    }
}
