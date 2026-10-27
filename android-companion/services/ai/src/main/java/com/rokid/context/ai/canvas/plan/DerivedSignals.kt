package com.rokid.context.ai.canvas.plan

import com.rokid.context.capture.plan.SignalCatalog
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** One user-defined signal: [name] (referenced as `d_<name>`) and its expression [graph], the same JSON as an `expr.guard` graph that ends in a number or a boolean. */
data class DerivedSignal(val name: String, val graph: JsonObject)

/** v0.9.7 A: the user's signal configuration. [disabled] catalogue signals (canonical names) are not available to guards; [derived] are the definitions. Empty = today's behaviour. */
data class SignalConfig(val disabled: Set<String> = emptySet(), val derived: List<DerivedSignal> = emptyList()) {
    companion object { const val MAX_DERIVED = 8 }
}

/** The config every compile reads, bound once at app start to the stored setting (like [WindowTolerance]). */
object SignalConfigs {
    @Volatile var provider: () -> SignalConfig = { SignalConfig() }
    /** Every configuration with derived signals the user has had (newest first, bounded): a frozen version is replayed against the one it was frozen under. */
    @Volatile var historyProvider: () -> List<SignalConfig> = { emptyList() }
    private val override = ThreadLocal<SignalConfig?>()
    fun current(): SignalConfig = override.get() ?: provider()
    /** Runs [block] (on this thread) with [cfg] as the configuration, so the pipeline validator inside a compile agrees with the compile's own. */
    fun <T> using(cfg: SignalConfig, block: () -> T): T { val before = override.get(); override.set(cfg); try { return block() } finally { override.set(before) } }
    fun history(): List<SignalConfig> = historyProvider()
}

/**
 * Derived signals by INLINING: a guard or value graph that names `d_<name>` gets the definition's steps appended (renamed `dv_<name>_<i>`), and the reference becomes an
 * ordinary `step` input. The expanded graph then goes through the one validator and lowering pass, so every limit (32 nodes, cost 64, 6 programs, 4 window terms, 8192 bytes)
 * holds for it and the glasses see only ordinary ops. Evaluation stays SHADOW/TRIAL: the nodes that use a derived signal are not liveable.
 */
object DerivedSignals {
    const val PREFIX = "d_"
    val NAME = Regex("^[a-z][a-z0-9_]{0,11}$")

    class Expanded(val graph: JsonElement?, val problems: List<ExprDiag>)

    private fun str(e: JsonElement?): String? = (e as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun step(n: String, i: Int) = "dv_${n}_$i"

    /** Problem texts for a definition (empty = valid): name, count, parse and type check by the guard/value validator, no derived-in-derived. */
    fun validate(name: String, graph: JsonElement?, existing: SignalConfig): List<String> {
        val out = ArrayList<String>()
        if (!NAME.matches(name)) out += "name must match [a-z][a-z0-9_]{0,11}"
        if (existing.derived.count { it.name != name } >= SignalConfig.MAX_DERIVED) out += "at most ${SignalConfig.MAX_DERIVED} derived signals"
        if (graph !is JsonObject) return out + "definition must be an expression graph"
        val (guard, bad) = ExprProgram.parse(ExprRole.GUARD, graph)
        if (guard == null) return out + bad.map { it.toString() }
        var problems = guard.check().problems
        if (problems.isNotEmpty() && problems.all { it.code == ExprCode.EXPR_GUARD_NOT_BOOL }) problems = ExprProgram.parse(ExprRole.VALUE, graph).first!!.check().problems
        out += problems.map { it.toString() }
        if (names(graph).any { it.startsWith(PREFIX) }) out += "a derived signal cannot read another derived signal"
        return out
    }

    private fun names(graph: JsonObject): List<String> = (graph["steps"] as? JsonArray).orEmpty().flatMap { s ->
        val o = s as? JsonObject ?: return@flatMap emptyList<String>()
        (o["inputs"] as? JsonArray).orEmpty().mapNotNull { str((it as? JsonObject)?.get("sig")) } +
            (o["nodes"] as? JsonArray).orEmpty().mapNotNull { n -> (n as? JsonObject)?.takeIf { str(it["op"]) == "sig" || str(it["op"]) == "win" }?.let { str(it["name"]) } }
    }

    fun expand(graph: JsonElement?, cfg: SignalConfig): Expanded {
        if (graph !is JsonObject || (cfg.disabled.isEmpty() && cfg.derived.isEmpty())) return Expanded(graph, emptyList())
        val steps = graph["steps"] as? JsonArray ?: return Expanded(graph, emptyList())
        val problems = LinkedHashSet<ExprDiag>()
        val byName = cfg.derived.associateBy { it.name }
        val used = LinkedHashMap<String, DerivedSignal>()
        fun disabled(sig: String?) {
            if (sig != null && SignalCatalog.canonical(sig)?.let { it in cfg.disabled } == true) problems += ExprDiag(ExprCode.EXPR_UNKNOWN_SIGNAL, "signal $sig is disabled in Settings")
        }
        fun derivedOf(sig: String?): DerivedSignal? = sig?.takeIf { it.startsWith(PREFIX) }?.let { byName[it.removePrefix(PREFIX)] }
        fun outOf(d: DerivedSignal): String? = (d.graph["steps"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let { s -> str(s["name"]) } }.sorted().let { names ->
            str(d.graph["out"])?.let { o -> names.indexOf(o).takeIf { it >= 0 }?.let { step(d.name, it) } }
        }
        val rewritten = steps.map { s ->
            val o = s as? JsonObject ?: return@map s
            val outNode = str(o["out"])
            val extraInputs = ArrayList<JsonElement>()
            val inputs = (o["inputs"] as? JsonArray)?.map { i ->
                val io = i as? JsonObject ?: return@map i
                val sig = str(io["sig"])
                val d = derivedOf(sig)
                if (d == null) { disabled(sig); i }
                else { used[d.name] = d; val target = outOf(d) ?: return@map i; JsonObject(mapOf("name" to (io["name"] ?: JsonPrimitive("")), "step" to JsonPrimitive(target))) }
            }
            val nodes = (o["nodes"] as? JsonArray)?.filter { n ->
                val no = n as? JsonObject ?: return@filter true
                val op = str(no["op"]); val name = str(no["name"])
                val d = if (op == "sig") derivedOf(name) else null
                if (d == null) { if (op == "sig" || op == "win") disabled(name); true }
                else {
                    used[d.name] = d
                    val target = outOf(d)
                    val id = str(no["id"])
                    if (target == null || id == null) true
                    else if (id == outNode) { problems += ExprDiag(ExprCode.EXPR_UNKNOWN_REF, "a derived signal cannot be the output of a step: wire it through a node"); true }
                    else { extraInputs += JsonObject(mapOf("name" to JsonPrimitive(id), "step" to JsonPrimitive(target))); false }
                }
            }
            JsonObject(o + buildMap<String, JsonElement> {
                if (inputs != null || extraInputs.isNotEmpty()) put("inputs", JsonArray((inputs ?: emptyList()) + extraInputs))
                if (nodes != null) put("nodes", JsonArray(nodes))
            })
        }
        val appended = ArrayList<JsonElement>()
        for (d in used.values) {
            val defSteps = (d.graph["steps"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.sortedBy { str(it["name"]) }
            val rename = defSteps.mapIndexed { idx, s -> str(s["name"]) to step(d.name, idx) }.toMap()
            for ((idx, s) in defSteps.withIndex()) {
                val inputs = (s["inputs"] as? JsonArray)?.map { i ->
                    val io = i as? JsonObject ?: return@map i
                    disabled(str(io["sig"]))
                    str(io["step"])?.let { r -> rename[r]?.let { JsonObject(io + ("step" to JsonPrimitive(it))) } } ?: io
                }
                (s["nodes"] as? JsonArray)?.forEach { n -> (n as? JsonObject)?.let { no -> if (str(no["op"]) == "sig" || str(no["op"]) == "win") disabled(str(no["name"])) } }
                appended += JsonObject(s + buildMap<String, JsonElement> { put("name", JsonPrimitive(step(d.name, idx))); if (inputs != null) put("inputs", JsonArray(inputs)) })
            }
        }
        return Expanded(JsonObject(graph + ("steps" to JsonArray(rewritten + appended))), problems.toList())
    }
}
