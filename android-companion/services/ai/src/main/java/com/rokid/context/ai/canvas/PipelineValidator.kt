package com.rokid.context.ai.canvas

import com.rokid.context.ai.Role
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * One validation problem: a fixed [text] naming node or edge ids only (never prompt text), plus the node ([nodeId]) or
 * edge ([edge]) it is about when there is one, so the editor can mark it.
 */
data class Problem(val text: String, val nodeId: String? = null, val edge: PipelineEdge? = null)

/** Problems are fixed texts naming node or edge ids only: never prompt text. Empty list = valid. */
object PipelineValidator {
    private val ID = Regex("^[a-z][a-z0-9_]{0,31}$")
    private const val MAX_NODES = 40
    private const val MAX_EDGES = 80
    private const val MAX_TEXT = 16_000
    /** WP8: at most this many `py.fn` nodes in one graph (each is its own laptop job and its own native tap). */
    const val MAX_PY_FN = 3

    /** The texts of [problems], unchanged. */
    fun validate(p: Pipeline): List<String> = problems(p).map { it.text }

    /** The same rules and texts as [validate], each with the node or edge it is about. */
    fun problems(p: Pipeline): List<Problem> {
        val out = mutableListOf<Problem>()
        operator fun MutableList<Problem>.plusAssign(text: String) { add(Problem(text)) }
        fun MutableList<Problem>.node(id: String, text: String) { add(Problem(text, nodeId = id)) }
        if (p.nodes.size > MAX_NODES) out += "too many nodes (max $MAX_NODES)"
        if (p.edges.size > MAX_EDGES) out += "too many edges (max $MAX_EDGES)"

        val byId = LinkedHashMap<String, PipelineNode>()
        val dup = mutableSetOf<String>()
        for (n in p.nodes) {
            if (!ID.matches(n.id)) out.node(n.id, "node ${shown(n.id)}: bad id")
            if (byId.containsKey(n.id)) { if (dup.add(n.id)) out.node(n.id, "duplicate node id ${shown(n.id)}") } else byId[n.id] = n
        }
        val types = byId.mapValues { NodeTypes.resolve(it.value.type, p.schema) }
        val domains = byId.mapNotNull { (id, n) -> types[id]?.let { t -> id to NodeTypes.domainOf(n, t) } }.toMap()
        byId.forEach { (id, n) -> if (types[id] == null) out.node(id, "node $id: unknown type") }

        // Edges
        val valid = mutableListOf<PipelineEdge>()
        for (e in p.edges) {
            val label = "edge ${shown(e.from)}->${shown(e.to)}"
            var ok = true
            if (e.from !in byId) { out.add(Problem("$label: unknown node ${shown(e.from)}", edge = e)); ok = false }
            if (e.to !in byId) { out.add(Problem("$label: unknown node ${shown(e.to)}", edge = e)); ok = false }
            if (!ok) continue
            valid += e
            val src = types[e.from] ?: continue
            val photosBinding = isPhotosBinding(e, byId)
            val crossing = domains[e.from] != null && domains[e.to] != null && domains[e.from] != domains[e.to]
            if (crossing && !photosBinding) { out.add(Problem("$label: crosses domains", edge = e)); continue }
            val branches = src.branches(byId.getValue(e.from).params)
            if (branches.isEmpty()) {
                if (e.`when` != null) out.add(Problem("$label: when not allowed", edge = e))
            } else if (e.`when` == null) {
                out.add(Problem("$label: when required (${branches.joinToString(", ")})", edge = e))
            } else if (e.`when` !in branches) {
                out.add(Problem("$label: unknown when ${shown(e.`when`)}", edge = e))
            }
            val dst = types[e.to] ?: continue
            if (!photosBinding && src.output !in dst.inputs) out.add(Problem("$label: ${src.output} not accepted by ${e.to}", edge = e))
        }

        // Roots: one per domain that has nodes (S34). Slice 2's single `input.photos` rule is kept for any graph with an ASYNC half or no RT half.
        val rtNodes = domains.values.any { it == Domain.RT_GLASSES || it == Domain.RT_PHONE }
        val inputs = byId.values.filter { it.type == NodeTypes.INPUT_PHOTOS }
        val asyncNodes = domains.values.any { it == Domain.ASYNC }
        if ((asyncNodes || !rtNodes) && inputs.size != 1) out += "exactly one input.photos required"
        val rootOf = HashMap<Domain, List<PipelineNode>>()
        for (d in listOf(Domain.RT_GLASSES, Domain.RT_PHONE)) {
            if (domains.none { it.value == d }) continue
            val rootType = NodeTypes.all.first { it.root && it.domain == d }.type
            val roots = byId.values.filter { it.type == rootType }
            rootOf[d] = roots
            if (roots.size != 1) out += "exactly one $rootType required"
        }
        rootOf[Domain.ASYNC] = inputs
        (inputs + rootOf.filterKeys { it != Domain.ASYNC }.values.flatten()).forEach { n ->
            val photosOnly = n.type == NodeTypes.INPUT_PHOTOS
            if (valid.any { it.to == n.id && !(photosOnly && isPhotosBinding(it, byId)) }) {
                out.node(n.id, if (photosOnly) "node ${n.id}: input must have no incoming edge" else "node ${n.id}: root must have no incoming edge")
            }
        }

        // Signal nodes: the domain is the instance's own param (S34), and a binding name is used once per side
        byId.forEach { (id, n) ->
            val t = types[id] ?: return@forEach
            if (!t.domainFromParams) return@forEach
            val raw = (n.params["domain"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val d = raw ?: t.domain.name
            val name = (n.params["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: "ctx"
            val ok = NodeTypes.SIGNAL_DOMAINS.any { it.name == d } && signalPlaceOk(n.type, name, d)
            if (!ok) out.node(id, "node $id: signal domain ${shown(d)} not allowed here")
        }
        byId.values.filter { types[it.id]?.domainFromParams == true }.groupBy { n -> n.type to ((n.params["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: "ctx") }
            .forEach { (key, nodes) -> nodes.drop(1).forEach { out.node(it.id, "node ${it.id}: binding ${shown(key.second)} used more than once") } }

        // Mixed incoming types
        byId.keys.forEach { id ->
            if (types[id]?.mixedIn == true) return@forEach
            val kinds = valid.filter { it.to == id && !isPhotosBinding(it, byId) }.mapNotNull { types[it.from]?.output }.toSet()
            if (kinds.size > 1) out.node(id, "node $id: incoming edges carry different types")
        }

        // Cycles (DFS; report each node a back edge returns to)
        val next = valid.groupBy({ it.from }, { it.to })
        val state = HashMap<String, Int>() // 1 = on stack, 2 = done
        val cyc = LinkedHashSet<String>()
        fun dfs(start: String) {
            val stack = ArrayDeque<Pair<String, Iterator<String>>>()
            state[start] = 1; stack.addLast(start to (next[start] ?: emptyList()).iterator())
            while (stack.isNotEmpty()) {
                val (id, it) = stack.last()
                if (it.hasNext()) {
                    val t = it.next()
                    when (state[t]) {
                        1 -> cyc += t
                        null -> { state[t] = 1; stack.addLast(t to (next[t] ?: emptyList()).iterator()) }
                    }
                } else { state[id] = 2; stack.removeLast() }
            }
        }
        byId.keys.forEach { if (state[it] == null) dfs(it) }
        cyc.forEach { out.node(it, "cycle through $it") }

        // Reachability, per domain: every node is reachable from its own root. A `signal.in` is a binding end and counts as a seed.
        for ((d, roots) in rootOf) {
            if (roots.size != 1) continue
            val seeds = listOf(roots[0].id) + byId.values.filter { types[it.id]?.domainFromParams == true && it.type == NodeTypes.SIGNAL_IN && domains[it.id] == d }.map { it.id }
            val seen = seeds.toMutableSet()
            val queue = ArrayDeque(seeds)
            while (queue.isNotEmpty()) (next[queue.removeFirst()] ?: emptyList()).forEach { if (domains[it] == d && seen.add(it)) queue.addLast(it) }
            byId.keys.filter { domains[it] == d && it !in seen }.forEach { out.node(it, "node $it: unreachable") }
        }

        // Params
        byId.forEach { (id, n) -> types[id]?.let { t -> paramProblems(id, n, t).forEach { out.node(id, it) } } }
        pyFnProblems(byId.values.filter { it.type == NodeTypes.PY_FN && types[it.id] != null }, valid, domains).forEach { out.node(it.first, it.second) }
        return out
    }

    /**
     * The one shape of graph a `py.fn` node may be in (WP8; what `PythonExecutor` supports): an ASYNC-only photo-experiment graph (a capture half has no executor for it and a
     * `py.fn` is never published), at most [MAX_PY_FN] such nodes, each with a non-blank `source`, an `outSchema` that is a JSON object (the schema-subset check itself runs when
     * the job is created) and exactly one incoming edge. Returns (nodeId, text) pairs.
     */
    private fun pyFnProblems(fns: List<PipelineNode>, edges: List<PipelineEdge>, domains: Map<String, Domain>): List<Pair<String, String>> {
        if (fns.isEmpty()) return emptyList()
        val out = mutableListOf<Pair<String, String>>()
        val capture = domains.values.any { it == Domain.RT_GLASSES || it == Domain.RT_PHONE }
        fns.forEachIndexed { i, n ->
            if (i >= MAX_PY_FN) out += n.id to "too many py.fn nodes (max $MAX_PY_FN)"
            val src = (n.params["source"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (src == null || src.isBlank()) out += n.id to "node ${n.id}: py.fn source required"
            val schema = (n.params["outSchema"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (schema == null || runCatching { Json.parseToJsonElement(schema) }.getOrNull() !is JsonObject) out += n.id to "node ${n.id}: py.fn outSchema must be a JSON object"
            if (edges.count { it.to == n.id } != 1) out += n.id to "node ${n.id}: py.fn takes exactly one input edge"
            if (capture) out += n.id to "node ${n.id}: py.fn runs only in a graph without a capture half"
        }
        return out
    }

    private fun paramProblems(id: String, n: PipelineNode, t: NodeType): List<String> {
        val out = mutableListOf<String>()
        val params = n.params
        out += ParamCheck.problems(id, t, params)
        if (n.type == NodeTypes.VISION_EXTRACT) {
            params["batch"]?.let { b ->
                val v = (b as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
                if (v == null || v !in 1..3) out += "node $id: batch must be 1..3"
            }
        }
        if (t.promptKey != null) params["prompt"]?.let { pr ->
            val o = pr as? JsonObject
            when ((o?.get("source") as? JsonPrimitive)?.takeIf { it.isString }?.content) {
                "live" -> Unit
                "draft" -> {
                    val tpl = (o["template"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                    if (tpl == null) out += "node $id: draft template required"
                    else if (tpl.length > MAX_TEXT) out += "node $id: template too long"
                    schemaProblem(id, o["schema"], required = false)?.let { out += it }
                }
                else -> out += "node $id: prompt source must be live or draft"
            }
        }
        if (n.type == NodeTypes.PROMPT) {
            val role = params["role"]
            if (role != null) {
                val r = (role as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { s -> Role.entries.firstOrNull { it.name == s } }
                if (r == null) out += "node $id: unknown role" else if (r == Role.EMBED) out += "node $id: role not allowed"
            }
            (params["template"] as? JsonPrimitive)?.content?.let { if (it.length > MAX_TEXT) out += "node $id: template too long" }
            schemaProblem(id, params["schema"], required = true)?.let { out += it }
            params["images"]?.let { v -> if ((v as? JsonPrimitive)?.takeIf { !it.isString }?.content !in listOf("true", "false")) out += "node $id: images must be true or false" }
        }
        if (n.type == NodeTypes.SWITCH) {
            val cases = NodeTypes.switchCases(params)
            val raw = (params["cases"] as? kotlinx.serialization.json.JsonArray)?.size ?: 0
            if (raw != cases.size || cases.size !in 1..12 || cases.toSet().size != cases.size ||
                cases.any { !ID.matches(it) || it == NodeTypes.OTHER }
            ) out += "node $id: cases must be 1..12 unique labels like ids"
        }
        return out
    }

    /** The one cross-domain edge: the capture node feeds the ASYNC half's `input.photos` (the `photos` binding). */
    private fun isPhotosBinding(e: PipelineEdge, byId: Map<String, PipelineNode>) =
        byId[e.from]?.type == NodeTypes.CAPTURE_IMAGE && byId[e.to]?.type == NodeTypes.INPUT_PHOTOS

    /** `ctx` is sent from RT_PHONE and received in RT_GLASSES; `intent` is received in RT_PHONE (it is sent by `event.hub_intent`). */
    private fun signalPlaceOk(type: String, name: String, domain: String) = when (type to name) {
        NodeTypes.SIGNAL_OUT to "ctx" -> domain == "RT_PHONE"
        NodeTypes.SIGNAL_IN to "ctx" -> domain == "RT_GLASSES"
        NodeTypes.SIGNAL_IN to "intent" -> domain == "RT_PHONE"
        else -> false
    }

    private fun schemaProblem(id: String, v: JsonElement?, required: Boolean): String? {
        if (v == null || v is JsonNull) return if (required) "node $id: schema required" else null
        val s = (v as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return "node $id: schema must be a JSON object"
        if (s.length > MAX_TEXT) return "node $id: schema too long"
        val parsed = runCatching { Json.parseToJsonElement(s) }.getOrNull()
        return if (parsed is JsonObject) null else "node $id: schema must be a JSON object"
    }

    /** Ids in messages are capped, so a hostile id can't make a long problem text. */
    private fun shown(id: String) = if (id.length > 40) id.take(40) + "..." else id
}
