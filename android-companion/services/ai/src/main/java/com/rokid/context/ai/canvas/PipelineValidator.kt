package com.rokid.context.ai.canvas

import com.rokid.context.ai.Role
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** Problems are fixed texts naming node or edge ids only: never prompt text. Empty list = valid. */
object PipelineValidator {
    private val ID = Regex("^[a-z][a-z0-9_]{0,31}$")
    private const val MAX_NODES = 40
    private const val MAX_EDGES = 80
    private const val MAX_TEXT = 16_000

    fun validate(p: Pipeline): List<String> {
        val out = mutableListOf<String>()
        if (p.nodes.size > MAX_NODES) out += "too many nodes (max $MAX_NODES)"
        if (p.edges.size > MAX_EDGES) out += "too many edges (max $MAX_EDGES)"

        val byId = LinkedHashMap<String, PipelineNode>()
        val dup = mutableSetOf<String>()
        for (n in p.nodes) {
            if (!ID.matches(n.id)) out += "node ${shown(n.id)}: bad id"
            if (byId.containsKey(n.id)) { if (dup.add(n.id)) out += "duplicate node id ${shown(n.id)}" } else byId[n.id] = n
        }
        val types = byId.mapValues { NodeTypes.byType(it.value.type) }
        byId.forEach { (id, n) -> if (types[id] == null) out += "node $id: unknown type" }

        // Edges
        val valid = mutableListOf<PipelineEdge>()
        for (e in p.edges) {
            val label = "edge ${shown(e.from)}->${shown(e.to)}"
            var ok = true
            if (e.from !in byId) { out += "$label: unknown node ${shown(e.from)}"; ok = false }
            if (e.to !in byId) { out += "$label: unknown node ${shown(e.to)}"; ok = false }
            if (!ok) continue
            valid += e
            val src = types[e.from] ?: continue
            val branches = src.branches(byId.getValue(e.from).params)
            if (branches.isEmpty()) {
                if (e.`when` != null) out += "$label: when not allowed"
            } else if (e.`when` == null) {
                out += "$label: when required (${branches.joinToString(", ")})"
            } else if (e.`when` !in branches) {
                out += "$label: unknown when ${shown(e.`when`)}"
            }
            val dst = types[e.to] ?: continue
            if (src.output !in dst.inputs) out += "$label: ${src.output} not accepted by ${e.to}"
        }

        // Input
        val inputs = byId.values.filter { it.type == NodeTypes.INPUT_PHOTOS }
        if (inputs.size != 1) out += "exactly one input.photos required"
        inputs.forEach { n -> if (valid.any { it.to == n.id }) out += "node ${n.id}: input must have no incoming edge" }

        // Mixed incoming types
        byId.keys.forEach { id ->
            val kinds = valid.filter { it.to == id }.mapNotNull { types[it.from]?.output }.toSet()
            if (kinds.size > 1) out += "node $id: incoming edges carry different types"
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
        cyc.forEach { out += "cycle through $it" }

        // Reachability
        if (inputs.size == 1) {
            val seen = mutableSetOf(inputs[0].id)
            val queue = ArrayDeque(listOf(inputs[0].id))
            while (queue.isNotEmpty()) (next[queue.removeFirst()] ?: emptyList()).forEach { if (seen.add(it)) queue.addLast(it) }
            byId.keys.filter { it !in seen }.forEach { out += "node $it: unreachable" }
        }

        // Params
        byId.forEach { (id, n) -> types[id]?.let { out += paramProblems(id, n, it) } }
        return out
    }

    private fun paramProblems(id: String, n: PipelineNode, t: NodeType): List<String> {
        val out = mutableListOf<String>()
        val params = n.params
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
