package com.rokid.context.ai.canvas

import com.rokid.context.ai.Role
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/**
 * The params of `rag.retrieve`: the source switches and windows, then every field of `RagParams.SCHEMA` (orchestration; a test there keeps the two
 * in step, this module cannot see it). No default is declared: an absent param means the flow's built-in value ([FlowPlan.BUILT_IN_QUERY], [FlowPlan.BUILT_IN_NUDGE]).
 */
object RagRetrieveParams {
    /** The names that are `RagParams` fields, in `RagParams.SCHEMA` order. */
    val ragNames = listOf("mode", "fusion", "semanticWeight", "rrfK", "topK", "poolSize", "minConfidence", "recencyBoost", "halfLifeDays", "maxChars", "maxRecent")

    private fun bool(name: String, label: String) = ParamSpec(name, ParamKind.BOOL, label = label)

    val schema = ParamSchema(listOf(
        bool("memories", "Memory store"), bool("photos", "Photo store"), bool("recent", "Recent window"), bool("history", "Nudge history"), bool("imageQuery", "Use the trigger's image"),
        ParamSpec("recentWindowMin", ParamKind.INT, label = "Recent window (min)", min = 1.0, max = 1440.0),
        ParamSpec("historyWindowMin", ParamKind.INT, label = "History window (min)", min = 1.0, max = 10080.0),
        ParamSpec("mode", ParamKind.ENUM, label = "Search", options = listOf("LEXICAL", "SEMANTIC", "HYBRID")),
        ParamSpec("fusion", ParamKind.ENUM, label = "Fusion", options = listOf("WEIGHTED", "RRF", "COMB_MNZ")),
        ParamSpec("semanticWeight", ParamKind.NUMBER, label = "Semantic weight", min = 0.0, max = 1.0),
        ParamSpec("rrfK", ParamKind.INT, label = "RRF k", min = 1.0, max = 200.0),
        ParamSpec("topK", ParamKind.INT, label = "Top k", min = 1.0, max = 20.0),
        ParamSpec("poolSize", ParamKind.INT, label = "Pool size", min = 1.0),
        ParamSpec("minConfidence", ParamKind.NUMBER, label = "Min confidence", min = 0.0, max = 1.0),
        bool("recencyBoost", "Recency boost"),
        ParamSpec("halfLifeDays", ParamKind.NUMBER, label = "Half-life (days)", min = 0.25, max = 30.0),
        ParamSpec("maxChars", ParamKind.INT, label = "Max characters", min = 0.0),
        ParamSpec("maxRecent", ParamKind.INT, label = "Most recent memories", min = 0.0),
    ))
}

/**
 * The params of the slice C Nudge triggers. Every one has `maxPerHour`, the per-pipeline rate limit (runs the trigger may start in a rolling hour; absent = [DEFAULT_PER_HOUR]).
 * `trigger.signal` embeds `agg.window` and `gate.compare` (their own schemas, one evaluation: the newest `window` Scenes' sensor feature, `op` over it, then the comparison) rather than
 * chaining the nodes, so the Nudge chain keeps one linear shape. `trigger.schedule` takes a [CronSpec].
 */
object TriggerParams {
    const val DEFAULT_PER_HOUR = 4
    const val MAX_PER_HOUR = 12
    private val rate = ParamSpec("maxPerHour", ParamKind.INT, label = "Most runs per hour", min = 1.0, max = MAX_PER_HOUR.toDouble(), step = 1.0, default = JsonPrimitive(DEFAULT_PER_HOUR))
    val moment = ParamSchema(listOf(rate))
    val scene = ParamSchema(listOf(rate))
    val signal = ParamSchema(WindowAggregate.schema.specs + NumberCompare.schema.specs + rate)
    val schedule = ParamSchema(listOf(ParamSpec("cron", ParamKind.TEXT, label = "Cron: minute hour day month weekday (at most every ${CronSpec.MIN_INTERVAL_MIN} min)", maxLen = CronSpec.MAX_LEN, default = JsonPrimitive(CronSpec.DEFAULT)), rate))
}

/**
 * What a published Query or Nudge pipeline means to the code that runs it: which sources the shared RAG core reads, with which params, and where the
 * result goes. The built-in flows are the constants [BUILT_IN_QUERY] and [BUILT_IN_NUDGE]; AnswerService and NudgeEngine run the same code for those
 * and for a compiled pipeline, so a default pipeline cannot differ from the built-in. The prompt is always the live ANSWER or NUDGE prompt.
 */
data class FlowPlan(
    val kind: Kind,
    val memories: Boolean, val photos: Boolean, val recent: Boolean, val history: Boolean, val imageQuery: Boolean,
    val recentWindowMin: Int, val historyWindowMin: Int,
    /** The `RagParams` fields the pipeline sets (names from [RagRetrieveParams.ragNames]); the rest stay unset. */
    val rag: JsonObject = JsonObject(emptyMap()),
    val target: String,
    /** The Nudge root (a `trigger.*` type); the built-in is the summarizer pass. A published flow runs only on its own trigger. */
    val trigger: String = NodeTypes.TRIGGER_SUMMARIZER,
    /** Runs per rolling hour the trigger may start; 0 = unlimited (the summarizer pass keeps its own 30 minute gap). */
    val maxPerHour: Int = 0,
    /** `trigger.signal`: what to measure and when it fires. */
    val signal: Signal? = null,
    /** `trigger.schedule`: the validated cron expression. */
    val cron: String? = null,
) {
    data class Signal(val aggregate: WindowAggregate.Settings, val compare: NumberCompare.Settings)

    enum class Kind(val component: String) { QUERY("query"), NUDGE("nudge") }

    /** A compiled plan, or why the pipeline is not live-able. The reasons are fixed texts naming node ids only. */
    sealed interface Compiled {
        data class Ok(val plan: FlowPlan) : Compiled
        data class Refused(val reasons: List<String>) : Compiled
    }

    companion object {
        val TARGETS = listOf("caller", "phone", "glasses", "both")

        /** Today's Ask: the memory store and the photo store, the user's retrieval settings, the answer to the caller. */
        val BUILT_IN_QUERY = FlowPlan(Kind.QUERY, memories = true, photos = true, recent = false, history = false, imageQuery = false, recentWindowMin = 360, historyWindowMin = 1440, target = "caller")

        /** Today's Nudge: OK memories of the last 6 h and the nudges of the last 24 h; its own counts bound it. The trigger's image, when it gives one, joins (as today). Shown by the verbosity setting (`both`). */
        val BUILT_IN_NUDGE = FlowPlan(Kind.NUDGE, memories = false, photos = false, recent = true, history = true, imageQuery = true, recentWindowMin = 360, historyWindowMin = 1440, target = "both")

        fun isFlowType(type: String) = type == NodeTypes.INPUT_UTTERANCE || type.startsWith("trigger.") || type == NodeTypes.RAG_RETRIEVE || type == NodeTypes.GATE_NUDGE || type == NodeTypes.OUT_DELIVER || type == NodeTypes.EMBED_TEXT

        fun isFlow(p: Pipeline) = p.nodes.any { isFlowType(it.type) }

        fun kindOf(p: Pipeline): Kind? = when {
            p.nodes.any { it.type == NodeTypes.INPUT_UTTERANCE } -> Kind.QUERY
            p.nodes.any { it.type.startsWith("trigger.") } -> Kind.NUDGE
            else -> null
        }

        /** The ordered node types of each flow; `embed.text` is the one optional step. */
        private fun expected(kind: Kind, root: String): List<String> = when (kind) {
            Kind.QUERY -> listOf(NodeTypes.INPUT_UTTERANCE, NodeTypes.EMBED_TEXT, NodeTypes.RAG_RETRIEVE, NodeTypes.PROMPT, NodeTypes.OUT_DELIVER)
            Kind.NUDGE -> listOf(root, NodeTypes.RAG_RETRIEVE, NodeTypes.PROMPT, NodeTypes.GATE_NUDGE, NodeTypes.OUT_DELIVER)
        }

        /**
         * The shape rules a flow graph adds to the generic ones: one linear chain of exactly the nodes of its kind, the live prompt of its role.
         * Called only for a graph that [isFlow]; [PipelineValidator] adds the texts to the generic problems. Fixed texts, node ids only.
         */
        fun shapeProblems(p: Pipeline): List<Problem> {
            val kind = kindOf(p) ?: return listOf(Problem("flow needs an input.utterance or a trigger"))
            val roots = p.nodes.filter { it.type == NodeTypes.INPUT_UTTERANCE || it.type.startsWith("trigger.") }
            if (roots.size != 1) return emptyList() // the generic root rule already says so
            val out = mutableListOf<Problem>()
            val want = expected(kind, roots[0].type).toMutableList()
            if (kind == Kind.QUERY && p.nodes.none { it.type == NodeTypes.EMBED_TEXT }) want.remove(NodeTypes.EMBED_TEXT)
            // walk the chain from the root; every node must be on it, once, in order
            val outs = p.edges.groupBy { it.from }
            val ins = p.edges.groupBy { it.to }
            val byId = p.nodes.associateBy { it.id }
            val chain = mutableListOf(roots[0])
            val seen = mutableSetOf(roots[0].id)
            while (true) {
                val next = outs[chain.last().id].orEmpty()
                if (next.size != 1 || next[0].`when` != null) break
                val n = byId[next[0].to] ?: break
                if (!seen.add(n.id)) break
                chain += n
            }
            for (n in p.nodes) if (n.id !in seen) out += Problem("node ${n.id}: not on the ${kind.name.lowercase()} chain", nodeId = n.id)
            if (out.isEmpty() && chain.map { it.type } != want) out += Problem("flow must be ${want.joinToString(" -> ")}")
            for (n in p.nodes) if (ins[n.id].orEmpty().size > 1 || outs[n.id].orEmpty().size > 1) out += Problem("node ${n.id}: one edge in and one out", nodeId = n.id)
            val prompt = p.nodes.firstOrNull { it.type == NodeTypes.PROMPT }
            if (prompt != null) {
                val role = (prompt.params["role"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                val need = if (kind == Kind.QUERY) Role.ANSWER.name else Role.NUDGE.name
                if (role != need) out += Problem("node ${prompt.id}: role must be $need", nodeId = prompt.id)
                if ("template" in prompt.params || "schema" in prompt.params) out += Problem("node ${prompt.id}: a flow takes its prompt text from Settings, not from the node", nodeId = prompt.id)
                if (prompt.promptSource() != PromptSource.Live) out += Problem("node ${prompt.id}: a flow uses the live prompt", nodeId = prompt.id)
            }
            roots[0].takeIf { it.type == NodeTypes.TRIGGER_SCHEDULE }?.let { t ->
                val cron = (t.params["cron"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: CronSpec.DEFAULT
                (CronSpec.parseOrReason(cron) as? CronSpec.Companion.Parsed.Bad)?.let { out += Problem("node ${t.id}: ${it.reason}", nodeId = t.id) }
            }
            return out
        }

        /** [shapeProblems] plus what the callers do not route (a delivery target that means nothing for the flow). A refused pipeline can still be saved and edited. */
        fun compile(p: Pipeline): Compiled {
            if (!isFlow(p)) return Compiled.Refused(listOf("not a Query or Nudge pipeline"))
            val shape = PipelineValidator.problems(p).map { it.text }
            if (shape.isNotEmpty()) return Compiled.Refused(shape)
            val kind = requireNotNull(kindOf(p))
            val base = if (kind == Kind.QUERY) BUILT_IN_QUERY else BUILT_IN_NUDGE
            val reasons = mutableListOf<String>()
            val rag = p.nodes.first { it.type == NodeTypes.RAG_RETRIEVE }.params
            val deliver = p.nodes.first { it.type == NodeTypes.OUT_DELIVER }
            val target = (deliver.params["target"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: base.target
            if (kind == Kind.NUDGE && target == "caller") reasons += "node ${deliver.id}: a Nudge has no caller; deliver to phone, glasses or both"
            fun flag(name: String, d: Boolean) = (rag[name] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: d
            fun int(name: String, d: Int) = (rag[name] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull ?: d
            val out = base.copy(
                memories = flag("memories", base.memories), photos = flag("photos", base.photos), recent = flag("recent", base.recent), history = flag("history", base.history),
                imageQuery = flag("imageQuery", base.imageQuery), recentWindowMin = int("recentWindowMin", base.recentWindowMin), historyWindowMin = int("historyWindowMin", base.historyWindowMin),
                rag = JsonObject(rag.filterKeys { it in RagRetrieveParams.ragNames }), target = target,
            )
            val root = p.nodes.first { it.type.startsWith("trigger.") || it.type == NodeTypes.INPUT_UTTERANCE }
            val withTrigger = if (kind == Kind.NUDGE && root.type != NodeTypes.TRIGGER_SUMMARIZER) {
                val perHour = (root.params["maxPerHour"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull ?: TriggerParams.DEFAULT_PER_HOUR
                out.copy(
                    trigger = root.type, maxPerHour = perHour,
                    signal = if (root.type == NodeTypes.TRIGGER_SIGNAL) Signal(WindowAggregate.settings(root.params), NumberCompare.settings(root.params)) else null,
                    cron = if (root.type == NodeTypes.TRIGGER_SCHEDULE) (root.params["cron"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: CronSpec.DEFAULT else null,
                )
            } else out
            val retrieve = p.nodes.first { it.type == NodeTypes.RAG_RETRIEVE }
            if (if (kind == Kind.QUERY) !out.memories && !out.photos else !out.recent && !out.history) reasons += "node ${retrieve.id}: choose at least one source"
            // what the flow's own code does not read is refused, never silently ignored
            if (kind == Kind.NUDGE && (out.memories || out.photos)) reasons += "node ${retrieve.id}: a Nudge reads recent memories and nudge history; memory and photo stores are for a Query"
            if (kind == Kind.QUERY && out.imageQuery) reasons += "node ${retrieve.id}: a Query has no image to search with"
            if (reasons.isNotEmpty()) return Compiled.Refused(reasons)
            return Compiled.Ok(withTrigger)
        }
    }
}
