package com.rokid.context.ai.canvas.plan

import com.rokid.context.ai.canvas.Domain
import com.rokid.context.ai.canvas.ExecutorKind
import com.rokid.context.ai.canvas.NodeType
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

    /** The one fixed S6 refusal text (gate 2): every publish path answers exactly this when [tier3Nodes] is not empty. */
    const val S6_REFUSAL = "Gate 2: the graph has a tier-3 node, and generated or laptop-run code never publishes."

    /**
     * Gate 2 (S6): nodes of tier 3, which never publish. Ruling: tier 3 is generated or laptop-run code, that is a node type under `py.` or `tool.`, a type whose registry
     * entry runs on the laptop (`ExecutorKind.PYTHON`, `DRAFT`), or one of the REPLAY_ONLY domain. The registry lookup is the unresolved one (`byType`), so a schema-1 graph that
     * names a newer type is still caught. This is the ONE guard every publish path calls (S6: [S6_REFUSAL]).
     */
    fun tier3Nodes(p: Pipeline): List<String> = tier3Nodes(p, NodeTypes::byType)

    /** [tier3Nodes] over an injectable descriptor [lookup] (the unresolved `byType` in production), so the metadata rules are testable without a registered type. */
    fun tier3Nodes(p: Pipeline, lookup: (String) -> NodeType?): List<String> = p.nodes.filter { n ->
        val t = lookup(n.type)
        val resolved = t?.takeIf { it.sinceSchema <= p.schema }?.let { NodeTypes.domainOf(n, it) }
        n.type.startsWith("py.") || n.type.startsWith("tool.") || n.type == NodeTypes.INPUT_MOMENTS || n.type == NodeTypes.INPUT_SCENES || resolved == Domain.REPLAY_ONLY ||
            t?.executor == ExecutorKind.PYTHON || t?.executor == ExecutorKind.DRAFT || t?.domain == Domain.REPLAY_ONLY
    }.map { it.id }.sorted()
}

fun Pipeline.asyncProjection(): Pipeline = PlanShape.asyncProjection(this)
