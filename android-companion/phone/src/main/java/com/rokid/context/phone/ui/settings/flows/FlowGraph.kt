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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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

/** Grid pitch and node sizes come from [EdgeRouting], so drawing and the routing tests share one geometry. */
private val CELL_W = EdgeRouting.CELL_W.dp
private val CELL_H = EdgeRouting.CELL_H.dp
private val NODE_W = EdgeRouting.NODE_W.dp
private val CARD_H = EdgeRouting.CARD_H.dp
private val PILL_H = EdgeRouting.PILL_H.dp
private val LABEL_W = EdgeRouting.LABEL_W.dp
private val LABEL_H = EdgeRouting.LABEL_H.dp
private val GUTTER = 16.dp

internal fun facetName(f: Facet) = when (f) { is Facet.Prompt -> "Prompt"; is Facet.Model -> "Model"; Facet.Retrieval -> "Retrieval"; is Facet.Custom -> "Custom" }

/**
 * One flow as a layered DAG. The grid is wider than the phone for wide layers, so it scrolls horizontally inside the
 * section; the page itself never grows. Edges follow [EdgeRouting]: skip-layer edges run down a free lane instead of
 * through the nodes they skip, and each label sits on its own edge, clear of nodes and other labels.
 */
@Composable
fun FlowSection(section: FlowSectionUi, onNode: (FlowNode) -> Unit, onLink: (String) -> Unit) {
    val flow = section.flow
    val layout = section.layout
    val routed = remember(flow, layout) { EdgeRouting.route(layout, flow) }
    fun x(id: String): Dp = CELL_W * (layout.cells[id]?.column ?: 0)
    fun y(id: String): Dp = CELL_H * (layout.cells[id]?.layer ?: 0)
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("flow_section_${flow.id}")) {
        Text(flow.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = GUTTER))
        Text(flow.purpose, style = MaterialTheme.typography.bodyMedium, color = PcColors.InkSoft, modifier = Modifier.padding(horizontal = GUTTER, vertical = 4.dp))
        Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            Box(Modifier.padding(horizontal = GUTTER, vertical = 8.dp).size(routed.width.dp, routed.height.dp)) {
                val ink = PcColors.InkSoft
                Canvas(Modifier.matchParentSize()) {
                    routed.edges.forEach { r ->
                        val pts = r.points.map { Offset(it.x.dp.toPx(), it.y.dp.toPx()) }
                        drawPath(Path().apply { moveTo(pts[0].x, pts[0].y); pts.drop(1).forEach { lineTo(it.x, it.y) } }, ink, style = Stroke(width = 1.5.dp.toPx()))
                        val end = pts.last(); val before = pts[pts.size - 2]
                        val angle = atan2(end.y - before.y, end.x - before.x)
                        val head = 7.dp.toPx(); val spread = 0.45f
                        drawPath(Path().apply {
                            moveTo(end.x, end.y)
                            lineTo(end.x - head * cos(angle - spread), end.y - head * sin(angle - spread))
                            lineTo(end.x - head * cos(angle + spread), end.y - head * sin(angle + spread))
                            close()
                        }, ink)
                    }
                }
                flow.nodes.filter { it.id in layout.cells }.forEach { n ->
                    val at = Modifier.offset(x(n.id) + (CELL_W - NODE_W) / 2, y(n.id))
                    val ui = section.nodes[n.id]
                    if (n.isAiStep) StepCard(flow.id, n, ui?.lines.orEmpty(), at) { onNode(n) }
                    else Pill(flow.id, n, at, onClick = n.linkTo?.takeIf { n.kind == NodeKind.LINK }?.let { link -> { onLink(link) } })
                }
                routed.edges.forEach { r ->
                    val box = r.label ?: return@forEach
                    Box(Modifier.offset(box.left.dp, box.top.dp).size(LABEL_W, LABEL_H), contentAlignment = Alignment.Center) {
                        Text(
                            r.edge.label!!, style = MaterialTheme.typography.labelSmall, color = PcColors.InkSoft, textAlign = TextAlign.Center, maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.background(PcColors.SurfaceSoft).padding(horizontal = 4.dp).testTag("flow_edge_label_${r.edge.from}_${r.edge.to}"),
                        )
                    }
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
