package com.rokid.context.phone.ui.settings.flows

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rokid.context.ai.flow.Facet
import com.rokid.context.ai.flow.FlowNode
import com.rokid.context.ai.flow.NodeKind
import com.rokid.context.core.ui.theme.PcColors
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** Grid pitch: a node is [NODE_W] wide at the top of its cell; the gap below it carries the edge labels. */
private val CELL_W = 172.dp
private val CELL_H = 140.dp
private val NODE_W = 152.dp
private val CARD_H = 96.dp
private val PILL_H = 44.dp
private val LABEL_W = 136.dp
private val GUTTER = 16.dp

internal fun facetName(f: Facet) = when (f) { is Facet.Prompt -> "Prompt"; is Facet.Model -> "Model"; Facet.Retrieval -> "Retrieval"; is Facet.Custom -> "Custom" }

private fun height(n: FlowNode) = if (n.isAiStep) CARD_H else PILL_H

/**
 * One flow as a layered DAG. The grid is wider than the phone for wide layers, so it scrolls horizontally inside the
 * section; the page itself never grows. Edges are drawn on a Canvas under the nodes; labels sit at edge midpoints.
 */
@Composable
fun FlowSection(section: FlowSectionUi, onNode: (FlowNode) -> Unit, onLink: (String) -> Unit) {
    val flow = section.flow
    val layout = section.layout
    val byId = flow.nodes.associateBy { it.id }
    fun x(id: String): Dp = CELL_W * (layout.cells[id]?.column ?: 0)
    fun y(id: String): Dp = CELL_H * (layout.cells[id]?.layer ?: 0)
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("flow_section_${flow.id}")) {
        Text(flow.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = GUTTER))
        Text(flow.purpose, style = MaterialTheme.typography.bodyMedium, color = PcColors.InkSoft, modifier = Modifier.padding(horizontal = GUTTER, vertical = 4.dp))
        Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            Box(Modifier.padding(horizontal = GUTTER, vertical = 8.dp).size(CELL_W * layout.columns, CELL_H * (layout.layers.coerceAtLeast(1) - 1) + CARD_H)) {
                val edges = flow.edges.filter { it.from in byId && it.to in byId && it.from in layout.cells && it.to in layout.cells }
                val ink = PcColors.InkSoft
                Canvas(Modifier.matchParentSize()) {
                    val centre = (CELL_W - NODE_W) / 2 + NODE_W / 2
                    edges.forEach { e ->
                        val from = byId.getValue(e.from); val to = byId.getValue(e.to)
                        val start = Offset((x(e.from) + centre).toPx(), (y(e.from) + height(from)).toPx())
                        val end = Offset((x(e.to) + centre).toPx(), y(e.to).toPx())
                        val a = layout.cells.getValue(e.from); val b = layout.cells.getValue(e.to)
                        val path = Path().apply { moveTo(start.x, start.y) }
                        // Straight to the next layer in the same lane; a curve when it changes lane or skips layers.
                        val bend = (end.y - start.y) / 2
                        val c2 = if (a.column == b.column && b.layer - a.layer == 1) start else Offset(end.x, end.y - bend)
                        if (c2 == start) path.lineTo(end.x, end.y) else path.cubicTo(start.x, start.y + bend, c2.x, c2.y, end.x, end.y)
                        drawPath(path, ink, style = Stroke(width = 1.5.dp.toPx()))
                        val angle = atan2(end.y - c2.y, end.x - c2.x)
                        val head = 7.dp.toPx(); val spread = 0.45f
                        drawPath(Path().apply {
                            moveTo(end.x, end.y)
                            lineTo(end.x - head * cos(angle - spread), end.y - head * sin(angle - spread))
                            lineTo(end.x - head * cos(angle + spread), end.y - head * sin(angle + spread))
                            close()
                        }, ink)
                    }
                }
                edges.filter { it.label != null }.forEach { e ->
                    val centre = (CELL_W - NODE_W) / 2 + NODE_W / 2
                    val midX = (x(e.from) + x(e.to)) / 2 + centre
                    val midY = (y(e.from) + height(byId.getValue(e.from)) + y(e.to)) / 2
                    Text(
                        e.label!!, style = MaterialTheme.typography.labelSmall, color = PcColors.InkSoft, textAlign = TextAlign.Center, maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.offset(midX - LABEL_W / 2, midY - 12.dp).width(LABEL_W)
                            .background(PcColors.SurfaceSoft).testTag("flow_edge_label_${e.from}_${e.to}"),
                    )
                }
                flow.nodes.filter { it.id in layout.cells }.forEach { n ->
                    val at = Modifier.offset(x(n.id) + (CELL_W - NODE_W) / 2, y(n.id))
                    val ui = section.nodes[n.id]
                    if (n.isAiStep) StepCard(flow.id, n, ui?.lines.orEmpty(), at) { onNode(n) }
                    else Pill(flow.id, n, at, onClick = n.linkTo?.takeIf { n.kind == NodeKind.LINK }?.let { link -> { onLink(link) } })
                }
            }
        }
    }
}

/** An AI step: bold title (2 lines max) and one live line per facet. */
@Composable
private fun StepCard(flowId: String, node: FlowNode, lines: List<FacetLine>, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier.size(NODE_W, CARD_H).clip(shape).background(PcColors.Surface).border(1.5.dp, PcColors.Mint, shape)
            .clickable(onClick = onClick).testTag("flow_node_${flowId}_${node.id}").padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text(node.title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        lines.forEach { l ->
            Text(l.text, style = MaterialTheme.typography.labelSmall, color = PcColors.InkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("flow_line_${flowId}_${node.id}_${facetName(l.facet)}"))
        }
    }
}

/** INPUT, OUTPUT, LINK and facet-less steps: a muted pill; a LINK pill adds its flow to the view. */
@Composable
private fun Pill(flowId: String, node: FlowNode, modifier: Modifier, onClick: (() -> Unit)?) {
    val shape = RoundedCornerShape(22.dp)
    val (bg, fg) = if (node.kind == NodeKind.LINK) PcColors.ChipSky else PcColors.ChipSlate
    Box(
        modifier.size(NODE_W, PILL_H).clip(shape).background(bg)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .testTag("flow_pill_${flowId}_${node.id}").padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(node.title, style = MaterialTheme.typography.labelMedium, color = fg, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}
