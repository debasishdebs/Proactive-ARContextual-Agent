package com.rokid.context.ai.canvas

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlin.math.ceil

/**
 * Whether a pipeline can be published as an interpreter (spec section 5, S28): before extraction only photo input, prompts
 * and switches; every photo path reaches exactly one extraction; only checks, the critic and Save follow it.
 * Problem texts are fixed and name node ids only.
 */
object InterpretableShape {
    const val MAX_PROMPT_NODES = 3

    private val PRE = setOf(NodeTypes.INPUT_PHOTOS, NodeTypes.PROMPT, NodeTypes.SWITCH)
    private val POST = setOf(NodeTypes.LOCAL_CRITIC, NodeTypes.CRITIC, NodeTypes.MEMORY_WRITE)

    private fun next(p: Pipeline): Map<String, List<PipelineEdge>> = p.edges.groupBy { it.from }

    /** Empty means the pipeline can be published as an interpreter. */
    fun problems(p: Pipeline): List<String> {
        if (PipelineValidator.validate(p).isNotEmpty()) return listOf("Fix the pipeline's problems first")
        val out = LinkedHashSet<String>()
        val byId = p.nodes.associateBy { it.id }
        val out1 = next(p)

        // before extraction: the nodes reachable from the input without passing an extractor
        val input = p.nodes.first { it.type == NodeTypes.INPUT_PHOTOS }
        val pre = linkedSetOf(input.id)
        val queue = ArrayDeque(listOf(input.id))
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            if (byId.getValue(id).type == NodeTypes.VISION_EXTRACT) continue
            out1[id].orEmpty().forEach { if (pre.add(it.to)) queue.addLast(it.to) }
        }
        pre.forEach { id ->
            val n = byId.getValue(id)
            if (n.type !in PRE && n.type != NodeTypes.VISION_EXTRACT) out += "node $id: ${n.type} can't run before extraction"
        }

        // after an extractor: only checks, the critic and Save
        val post = linkedSetOf<String>()
        val q2 = ArrayDeque<String>()
        p.nodes.filter { it.type == NodeTypes.VISION_EXTRACT && it.id in pre }.forEach { e -> out1[e.id].orEmpty().forEach { if (post.add(it.to)) q2.addLast(it.to) } }
        while (q2.isNotEmpty()) out1[q2.removeFirst()].orEmpty().forEach { if (post.add(it.to)) q2.addLast(it.to) }
        post.forEach { id ->
            val n = byId.getValue(id)
            if (n.type !in POST && n.type != NodeTypes.VISION_EXTRACT) out += "node $id: only checks, critic and Save can follow extraction"
        }
        p.nodes.filter { it.type == NodeTypes.CRITIC && it.promptSource() is PromptSource.Draft }
            .forEach { out += "node ${it.id}: the critic must use the Live prompt" }
        if (p.nodes.count { it.type == NodeTypes.PROMPT } > MAX_PROMPT_NODES) out += "at most $MAX_PROMPT_NODES prompt nodes"

        // exclusive and complete routing (S28)
        p.nodes.filter { it.type in PRE_OR_EXTRACT_SINGLE }.forEach { n ->
            if (out1[n.id].orEmpty().size > 1) out += "node ${n.id}: only a switch can send photos down two paths"
        }
        p.nodes.filter { it.type == NodeTypes.SWITCH }.forEach { n ->
            val branches = NodeTypes.byType(n.type)?.branches?.invoke(n.params).orEmpty()
            val edges = out1[n.id].orEmpty()
            branches.forEach { b ->
                val c = edges.count { it.`when` == b }
                if (c == 0) out += "node ${n.id}: branch $b isn't connected"
                else if (c > 1) out += "node ${n.id}: branch $b goes to more than one node"
            }
        }

        // paths: how many extractors does each root-to-end path meet? (0, 1, 2 = two or more), by DP over the DAG
        val order = topological(p)
        val counts = HashMap<String, Set<Int>>()
        order.forEach { id ->
            val incoming = p.edges.filter { it.to == id }.map { it.from }
            val base: Set<Int> = if (byId.getValue(id).type == NodeTypes.INPUT_PHOTOS) setOf(0) else incoming.flatMap { counts[it].orEmpty() }.toSet()
            val here = if (byId.getValue(id).type == NodeTypes.VISION_EXTRACT) base.map { minOf(it + 1, 2) }.toSet() else base
            counts[id] = here
            if (byId.getValue(id).type == NodeTypes.VISION_EXTRACT && base.any { it >= 1 }) out += "photo path through $id reaches more than one extraction"
            if (out1[id].isNullOrEmpty() && 0 in here) out += "photo path through $id reaches no extraction"
        }
        return out.toList()
    }

    /** Nodes that must not fan out: everything before or at extraction except a switch. */
    private val PRE_OR_EXTRACT_SINGLE = setOf(NodeTypes.INPUT_PHOTOS, NodeTypes.PROMPT, NodeTypes.VISION_EXTRACT)

    fun warnings(p: Pipeline): List<String> {
        val hasTrust = p.nodes.any { it.type == NodeTypes.LOCAL_CRITIC }
        val hasCritic = p.nodes.any { it.type == NodeTypes.CRITIC }
        return if (hasTrust && hasCritic) emptyList() else listOf("Live always runs the confidence check and the critic after extraction")
    }

    /** What the interpreter runs, in topological order: input, prompts, switches and extractors. */
    fun preSaveNodes(p: Pipeline): List<PipelineNode> {
        val byId = p.nodes.associateBy { it.id }
        return topological(p).mapNotNull(byId::get).filter { it.type in PRE || it.type == NodeTypes.VISION_EXTRACT }
    }

    /**
     * Model calls for a batch of [photos] photos relative to Live extraction (1 call per batch of 3), as (min, max),
     * rounded to 1 decimal. A photo's prompt calls are the prompt nodes on its path; extract calls are, per extractor,
     * ceil(photos routed there / its batch size). The minimum puts every photo on the cheapest path, the maximum spreads
     * them over the most expensive distinct paths. A pipeline that isn't [problems]-free gives (0.0, 0.0).
     */
    fun callRange(p: Pipeline, photos: Int = 3): Pair<Double, Double> {
        if (problems(p).isNotEmpty()) return 0.0 to 0.0
        val n = photos.coerceIn(1, 12)
        val byId = p.nodes.associateBy { it.id }
        val out1 = next(p)
        // path classes (prompts on the path, extractor id), by memoised walk from each node to the extractors
        val memo = HashMap<String, Set<Pair<Int, String>>>()
        fun classes(id: String): Set<Pair<Int, String>> = memo.getOrPut(id) {
            val node = byId.getValue(id)
            if (node.type == NodeTypes.VISION_EXTRACT) return@getOrPut setOf(0 to id)
            val add = if (node.type == NodeTypes.PROMPT) 1 else 0
            out1[id].orEmpty().flatMap { classes(it.to) }.map { (c, e) -> (c + add) to e }.toSet()
        }
        val all = classes(p.nodes.first { it.type == NodeTypes.INPUT_PHOTOS }.id).toList()
        fun batch(e: String) = ((byId.getValue(e).params["batch"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull ?: 1).coerceIn(1, 3)
        fun cost(assign: List<Pair<Int, String>>): Int =
            assign.sumOf { it.first } + assign.groupBy { it.second }.values.sumOf { g -> ceil(g.size.toDouble() / batch(g.first().second)).toInt() }
        val min = all.minOf { cost(List(n) { _ -> it }) }
        var max = 0
        fun spread(from: Int, chosen: List<Pair<Int, String>>) {
            if (chosen.size == n) { max = maxOf(max, cost(chosen)); return }
            for (i in from until all.size) spread(i, chosen + all[i])
        }
        spread(0, emptyList())
        val base = ceil(n / 3.0)
        fun r(v: Double) = Math.round(v / base * 10.0) / 10.0
        return r(min.toDouble()) to r(max.toDouble())
    }

    /** Kahn's algorithm; ties keep the node list order. Nodes on a cycle are left out. */
    private fun topological(p: Pipeline): List<String> {
        val indeg = p.nodes.associate { it.id to 0 }.toMutableMap()
        p.edges.forEach { if (it.to in indeg && it.from in indeg) indeg[it.to] = indeg.getValue(it.to) + 1 }
        val out1 = next(p)
        val ready = ArrayDeque(p.nodes.map { it.id }.filter { indeg.getValue(it) == 0 })
        val order = mutableListOf<String>()
        while (ready.isNotEmpty()) {
            val id = ready.removeFirst(); order += id
            out1[id].orEmpty().forEach { e -> if (e.to in indeg) { indeg[e.to] = indeg.getValue(e.to) - 1; if (indeg.getValue(e.to) == 0) ready.addLast(e.to) } }
        }
        return order
    }
}
