package com.rokid.context.ai.flow

/** A grid position in abstract units: [layer] is the row (0 = top), [column] the lane within the flow's grid. */
data class Cell(val layer: Int, val column: Int)

/** [columns] is the grid width (max column + 1); without side inputs it equals the widest layer. */
data class FlowLayout(val cells: Map<String, Cell>, val layers: Int, val columns: Int)

/**
 * Layered layout (agent flows spec section 3). Pure and deterministic: every tie is broken by declaration order.
 * 1. Longest-path layers from the primary INPUT (the first declared), over the nodes it reaches.
 * 2. Each other INPUT walks its chain of single successors to the first already-layered node (the join); the chain
 *    ends one layer above the join (the minimum over several layered successors), and a layer < 0 shifts everything down.
 * 3. Main nodes are ordered per layer by declaration, then one barycentre sweep down (predecessors) and one up
 *    (successors). Each side chain gets an outer lane on the side nearer its join's column (ties alternate, right first);
 *    chains whose layers do not overlap share a lane.
 */
object FlowLayouts {
    fun layout(flow: AgentFlow): FlowLayout {
        val ids = flow.nodes.map { it.id }
        if (ids.isEmpty()) return FlowLayout(emptyMap(), 0, 0)
        val order = ids.withIndex().associate { it.value to it.index }
        val edges = flow.edges.filter { it.from in order && it.to in order }
        val succ = ids.associateWith { id -> edges.filter { it.from == id }.map { it.to }.distinct() }
        val pred = ids.associateWith { id -> edges.filter { it.to == id }.map { it.from }.distinct() }
        val topo = topoOrder(ids, succ, pred)
        val inputs = flow.nodes.filter { it.kind == NodeKind.INPUT }.map { it.id }
        val primary = inputs.firstOrNull() ?: ids.first()
        val layer = mutableMapOf<String, Int>()

        // Pass 1: longest-path depth from the primary input.
        layer[primary] = 0
        for (n in topo) {
            val l = layer[n] ?: continue
            succ.getValue(n).forEach { s -> layer[s] = maxOf(layer[s] ?: Int.MIN_VALUE, l + 1) }
        }

        // Pass 2: side-input chains, aligned to end one layer above their join.
        val chains = mutableListOf<Pair<List<String>, String?>>() // chain nodes (input first), join id
        var pending = inputs.filter { it != primary && it !in layer }
        while (pending.isNotEmpty()) {
            val placed = pending.filter { input ->
                val chain = mutableListOf(input)
                var cur = input
                var joinLayer: Int? = null
                var join: String? = null
                while (true) {
                    val next = succ.getValue(cur)
                    val layered = next.filter { it in layer }
                    if (layered.isNotEmpty()) {
                        join = layered.minByOrNull { layer.getValue(it) }
                        joinLayer = layer.getValue(join!!)
                        break
                    }
                    val step = next.firstOrNull { it !in chain } ?: break
                    chain += step
                    cur = step
                }
                if (joinLayer == null) return@filter false
                chain.forEachIndexed { i, id -> layer[id] = joinLayer - (chain.size - i) }
                chains += chain to join
                true
            }
            pending = pending - placed.toSet()
            if (placed.isEmpty()) {
                // Chains that reach no layered node: laid out from the top, in declaration order.
                val input = pending.first()
                val chain = mutableListOf(input)
                var cur = input
                while (true) cur = succ.getValue(cur).firstOrNull { it !in chain && it !in layer }?.also { chain += it } ?: break
                chain.forEachIndexed { i, id -> layer[id] = i }
                chains += chain to null
                pending = pending - input
            }
        }

        // Anything left (reached only through a branch off a side chain): one below its layered predecessors.
        for (n in topo) if (n !in layer) layer[n] = pred.getValue(n).mapNotNull { layer[it] }.maxOrNull()?.plus(1) ?: 0
        val min = layer.values.min()
        if (min < 0) layer.keys.toList().forEach { layer[it] = layer.getValue(it) - min }
        // Safety net for odd graphs: keep every edge pointing down (a no-op for longest-path + aligned chains).
        for (n in topo) succ.getValue(n).forEach { s -> if (layer.getValue(s) <= layer.getValue(n)) layer[s] = layer.getValue(n) + 1 }

        // Pass 3: columns.
        val side = chains.flatMap { it.first }.toSet()
        val main = ids.filter { it !in side }
        val layers = layer.values.max() + 1
        val rows = (0 until layers).map { l -> main.filter { layer.getValue(it) == l }.toMutableList() }
        fun pos(id: String) = rows[layer.getValue(id)].indexOf(id).toDouble()
        fun sweep(range: IntProgression, neighbours: Map<String, List<String>>) {
            for (l in range) {
                val row = rows[l]
                val key = row.associateWith { id ->
                    val ns = neighbours.getValue(id).filter { it !in side }
                    if (ns.isEmpty()) pos(id) else ns.map(::pos).average()
                }
                val sorted = row.sortedWith(compareBy<String> { key.getValue(it) }.thenBy { row.indexOf(it) })
                row.clear(); row += sorted
            }
        }
        sweep(1 until layers, pred)
        sweep(layers - 2 downTo 0, succ)

        val mainWidth = rows.maxOf { it.size }
        val column = mutableMapOf<String, Int>()
        rows.forEach { row -> row.forEachIndexed { i, id -> column[id] = i } }
        val centre = (mainWidth - 1) / 2.0
        val rightLanes = mutableListOf<MutableList<IntRange>>()
        val leftLanes = mutableListOf<MutableList<IntRange>>()
        var nextTieRight = true
        for ((chain, join) in chains) {
            val span = chain.minOf { layer.getValue(it) }..chain.maxOf { layer.getValue(it) }
            val joinCol = join?.let { column[it] }?.toDouble() ?: centre
            val right = when {
                joinCol > centre -> true
                joinCol < centre -> false
                else -> nextTieRight.also { nextTieRight = !nextTieRight }
            }
            val lanes = if (right) rightLanes else leftLanes
            var lane = lanes.indexOfFirst { used -> used.none { it.first <= span.last && span.first <= it.last } }
            if (lane < 0) { lanes += mutableListOf<IntRange>(); lane = lanes.size - 1 }
            lanes[lane] += span
            val col = if (right) mainWidth + lane else -(lane + 1)
            chain.forEach { column[it] = col }
        }
        val shift = leftLanes.size
        val cells = ids.associateWith { Cell(layer.getValue(it), column.getValue(it) + shift) }
        return FlowLayout(cells, layers, mainWidth + leftLanes.size + rightLanes.size)
    }

    /** Kahn's algorithm, ready nodes taken in declaration order; nodes left in a cycle are appended in that order. */
    private fun topoOrder(ids: List<String>, succ: Map<String, List<String>>, pred: Map<String, List<String>>): List<String> {
        val indeg = ids.associateWith { pred.getValue(it).size }.toMutableMap()
        val out = mutableListOf<String>()
        val done = mutableSetOf<String>()
        while (out.size < ids.size) {
            val n = ids.firstOrNull { it !in done && indeg.getValue(it) == 0 } ?: ids.first { it !in done }
            done += n; out += n
            succ.getValue(n).forEach { indeg[it] = indeg.getValue(it) - 1 }
        }
        return out
    }
}
