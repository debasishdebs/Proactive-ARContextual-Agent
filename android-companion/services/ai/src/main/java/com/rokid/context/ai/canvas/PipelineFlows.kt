package com.rokid.context.ai.canvas

import com.rokid.context.ai.flow.AgentFlow
import com.rokid.context.ai.flow.Facet
import com.rokid.context.ai.flow.FlowEdge
import com.rokid.context.ai.flow.FlowLayout
import com.rokid.context.ai.flow.FlowLayouts
import com.rokid.context.ai.flow.FlowNode
import com.rokid.context.ai.flow.NodeKind
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object PipelineFlows {
    fun toAgentFlow(p: Pipeline): AgentFlow = AgentFlow(
        id = p.id, title = p.name, purpose = "Experiment pipeline",
        nodes = p.nodes.map { n ->
            val key = NodeTypes.byType(n.type)?.promptKey
            when {
                n.type == NodeTypes.INPUT_PHOTOS -> FlowNode(n.id, n.title, NodeKind.INPUT)
                n.type == NodeTypes.MEMORY_WRITE -> FlowNode(n.id, n.title, NodeKind.OUTPUT)
                n.type == NodeTypes.PROMPT -> FlowNode(n.id, n.title, NodeKind.STEP, listOf(Facet.Custom("canvas.prompt")))
                key != null -> FlowNode(n.id, n.title, NodeKind.STEP, listOf(Facet.Prompt(key), Facet.Model(requireNotNull(key.role))))
                else -> FlowNode(n.id, n.title, NodeKind.STEP)
            }
        },
        edges = p.edges.map { FlowEdge(it.from, it.to, it.`when`) },
    )

    fun layout(p: Pipeline): FlowLayout = FlowLayouts.layout(toAgentFlow(p))

    fun layoutJson(p: Pipeline): JsonObject {
        val l = layout(p)
        return buildJsonObject {
            put("layers", l.layers)
            put("columns", l.columns)
            put("cells", JsonObject(l.cells.mapValues { (_, c) -> buildJsonObject { put("layer", c.layer); put("column", c.column) } }))
        }
    }
}
