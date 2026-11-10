package com.rokid.context.canvas

import com.rokid.context.ai.canvas.Pipeline
import com.rokid.context.ai.canvas.PipelineEdge
import com.rokid.context.ai.canvas.PipelineNode

/** The item-flow rules shared by [CanvasRunner] and [PipelineInterpreter]: topological order, the edge `when` rule, the merge conflict. */
internal object ItemFlow {
    /** [sources] (codex r17): what this value was derived from, along the edges that actually produced it (never another branch's rows). */
    class Out(val value: CanvasValue, val branch: String?, val sources: com.rokid.context.model.PayloadSources = com.rokid.context.model.PayloadSources.NONE)

    /** [sources]: per input, the [Out.sources] of the one edge that delivered it. */
    class Gathered(val inputs: LinkedHashMap<String, CanvasValue>, val conflicts: Set<String>, val sources: Map<String, com.rokid.context.model.PayloadSources> = emptyMap())

    /**
     * What a node receives: for each incoming edge, the items its source produced, except those whose branch differs from the edge's `when`.
     * An item that arrives on two edges is a conflict: it is removed from the inputs and listed in [Gathered.conflicts].
     */
    fun gather(incoming: List<PipelineEdge>, outputs: Map<String, Map<String, Out>>): Gathered {
        val inputs = LinkedHashMap<String, CanvasValue>()
        val sources = HashMap<String, com.rokid.context.model.PayloadSources>()
        val conflicts = LinkedHashSet<String>()
        for (e in incoming) {
            for ((id, o) in outputs[e.from].orEmpty()) {
                if (e.`when` != null && e.`when` != o.branch) continue
                if (inputs.containsKey(id) || id in conflicts) { inputs.remove(id); sources.remove(id); conflicts += id } else { inputs[id] = o.value; sources[id] = o.sources }
            }
        }
        return Gathered(inputs, conflicts, sources)
    }

    /** Kahn's algorithm; ties go to the node that comes first in the JSON. */
    fun topological(p: Pipeline): List<PipelineNode> {
        val remaining = p.nodes.toMutableList()
        val done = HashSet<String>()
        val out = ArrayList<PipelineNode>()
        while (remaining.isNotEmpty()) {
            val next = remaining.firstOrNull { n -> p.edges.none { it.to == n.id && it.from !in done } } ?: remaining.first()
            remaining.remove(next); done += next.id; out += next
        }
        return out
    }
}
