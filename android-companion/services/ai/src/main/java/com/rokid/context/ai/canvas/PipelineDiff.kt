package com.rokid.context.ai.canvas

import com.rokid.context.prompt.LineDiff
import kotlinx.serialization.json.JsonPrimitive

/** What changed between two pipeline versions, by node id. The prompt line diff reuses [LineDiff]. */
object PipelineDiff {
    data class NodeChange(val nodeId: String, val kind: String /* added|removed|changed */, val params: List<String> /* changed param names, sorted */)
    data class PromptDiff(val nodeId: String, val part: String /* template|schema */, val lines: List<LineDiff.Line>)
    data class Result(
        val nodes: List<NodeChange>,
        val edgesAdded: List<PipelineEdge>,
        val edgesRemoved: List<PipelineEdge>,
        val prompts: List<PromptDiff>,
        val renamed: Boolean,
    )

    /** A node's own type or title changing shows as "@type" / "@title", so it can't collide with a param name. */
    fun of(a: Pipeline, b: Pipeline): Result {
        val an = a.nodes.associateBy { it.id }
        val bn = b.nodes.associateBy { it.id }
        val nodes = mutableListOf<NodeChange>()
        val prompts = mutableListOf<PromptDiff>()
        (an.keys + bn.keys).sorted().forEach { id ->
            val x = an[id]; val y = bn[id]
            when {
                x == null -> nodes += NodeChange(id, "added", emptyList())
                y == null -> nodes += NodeChange(id, "removed", emptyList())
                else -> {
                    val changed = (x.params.keys + y.params.keys).filter { x.params[it] != y.params[it] }.sorted().toMutableList()
                    if (x.type != y.type) changed += "@type"
                    if (x.title != y.title) changed += "@title"
                    if (changed.isNotEmpty()) nodes += NodeChange(id, "changed", changed.sorted())
                    val (tx, sx) = promptParts(x); val (ty, sy) = promptParts(y)
                    if (tx != ty) prompts += PromptDiff(id, "template", LineDiff.diff(tx, ty))
                    if (sx != sy) prompts += PromptDiff(id, "schema", LineDiff.diff(sx, sy))
                }
            }
        }
        val ae = a.edges.toSet(); val be = b.edges.toSet()
        return Result(nodes, b.edges.filter { it !in ae }.distinct(), a.edges.filter { it !in be }.distinct(), prompts, a.name != b.name)
    }

    /** (template, schema) of the node's prompt; "" when it has none, one fixed line when it uses the Live prompt. */
    private fun promptParts(n: PipelineNode): Pair<String, String> {
        if (n.type == NodeTypes.PROMPT) return str(n, "template") to str(n, "schema")
        return when (val s = n.promptSource()) {
            null -> "" to ""
            PromptSource.Live -> LIVE_PROMPT to ""
            is PromptSource.Draft -> s.template to s.schema.orEmpty()
        }
    }

    private fun str(n: PipelineNode, key: String) = (n.params[key] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()

    const val LIVE_PROMPT = "(the Live prompt)"
}
