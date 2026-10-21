package com.rokid.context.canvas

import com.rokid.context.ai.canvas.Pipeline
import com.rokid.context.ai.canvas.PipelineEdge
import com.rokid.context.ai.canvas.PipelineNode

/** The item-flow rules shared by [CanvasRunner] and [PipelineInterpreter]: topological order, the edge `when` rule, the merge conflict. */
internal object ItemFlow {
    class Out(val value: CanvasValue, val branch: String?)

    class Gathered(val inputs: LinkedHashMap<String, CanvasValue>, val conflicts: Set<String>)

    /**
     * What a node receives: for each incoming edge, the items its source produced, except those whose branch differs from the edge's `when`.
     * An item that arrives on two edges is a conflict: it is removed from the inputs and listed in [Gathered.conflicts].
     */
    fun gather(incoming: List<PipelineEdge>, outputs: Map<String, Map<String, Out>>): Gathered {
        val inputs = LinkedHashMap<String, CanvasValue>()
        val conflicts = LinkedHashSet<String>()
        for (e in incoming) {
            for ((id, o) in outputs[e.from].orEmpty()) {
                if (e.`when` != null && e.`when` != o.branch) continue
                if (inputs.containsKey(id) || id in conflicts) { inputs.remove(id); conflicts += id } else inputs[id] = o.value
            }
        }
        return Gathered(inputs, conflicts)
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
