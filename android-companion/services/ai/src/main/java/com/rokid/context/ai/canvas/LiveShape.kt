package com.rokid.context.ai.canvas

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

object LiveShape {
    /** Same node ids, types and edges (`when` included) as the live-extract pipeline, and batch 3; only prompt params may differ. */
    fun matches(p: Pipeline): Boolean {
        val live = DefaultPipelines.liveExtract()
        fun nodeKey(n: PipelineNode) = n.id to n.type
        fun edgeKey(e: PipelineEdge) = Triple(e.from, e.to, e.`when`)
        if (p.nodes.size != live.nodes.size || p.edges.size != live.edges.size) return false
        if (p.nodes.map(::nodeKey).toSet() != live.nodes.map(::nodeKey).toSet()) return false
        if (p.edges.map(::edgeKey).toSet() != live.edges.map(::edgeKey).toSet()) return false
        val extract = p.nodes.first { it.id == "extract" }
        val batch = extract.params["batch"] ?: return true
        return (batch as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull == 3
    }

    fun draftPromptNodes(p: Pipeline): List<PipelineNode> = p.nodes.filter { it.promptSource() is PromptSource.Draft }
}
