package com.rokid.context.ai.canvas.plan

import com.rokid.context.ai.canvas.Domain
import com.rokid.context.ai.canvas.NodeTypes
import com.rokid.context.ai.canvas.Pipeline

/**
 * How a full three-domain graph splits for publishing (S72, S25). The ASYNC half is the slice 2 pipeline: its hash is the slice 2 hash of [asyncProjection], so a tested
 * identity survives adding a capture half. A graph with no RT node is its own projection (the same object, so the same hash).
 */
object PlanShape {
    private fun domainOf(p: Pipeline, nodeType: String, node: com.rokid.context.ai.canvas.PipelineNode): Domain? =
        NodeTypes.resolve(nodeType, p.schema)?.let { NodeTypes.domainOf(node, it) }

    fun isRt(p: Pipeline, n: com.rokid.context.ai.canvas.PipelineNode): Boolean = domainOf(p, n.type, n).let { it == Domain.RT_GLASSES || it == Domain.RT_PHONE }

    /** True when the graph has a glasses or phone RT node (so it publishes as a `capture_plan`). */
    fun hasCaptureHalf(p: Pipeline): Boolean = p.nodes.any { isRt(p, it) }

    /** The ASYNC half: every node that is not RT, with the edges between them (the photos binding edge from `capture.image` goes). */
    fun asyncProjection(p: Pipeline): Pipeline {
        if (!hasCaptureHalf(p)) return p
        val keep = p.nodes.filterNot { isRt(p, it) }
        val ids = keep.map { it.id }.toSet()
        return p.copy(nodes = keep, edges = p.edges.filter { it.from in ids && it.to in ids })
    }

    /** True when the projection still has an ASYNC pipeline to publish (it has its photo input). */
    fun hasAsyncHalf(p: Pipeline): Boolean = asyncProjection(p).nodes.any { it.type == NodeTypes.INPUT_PHOTOS }

    /**
     * Gate 2 (S6): nodes of tier 3, which never publish. Ruling: tier 3 is generated or laptop-run code, that is a node type under `py.` or `tool.`, or one of the
     * REPLAY_ONLY domain. The catalogue has none yet; this keeps the gate real for the node types that will.
     */
    fun tier3Nodes(p: Pipeline): List<String> = p.nodes.filter { n ->
        n.type.startsWith("py.") || n.type.startsWith("tool.") || domainOf(p, n.type, n) == Domain.REPLAY_ONLY
    }.map { it.id }.sorted()
}

fun Pipeline.asyncProjection(): Pipeline = PlanShape.asyncProjection(this)
