package com.rokid.context.ai.canvas.plan

import com.rokid.context.ai.canvas.Domain
import com.rokid.context.ai.canvas.NodeType
import com.rokid.context.ai.canvas.NodeTypes
import com.rokid.context.ai.canvas.Pipeline
import com.rokid.context.ai.canvas.PipelineEdge
import com.rokid.context.ai.canvas.PipelineNode
import com.rokid.context.ai.canvas.PipelineValidator
import com.rokid.context.capture.plan.CapturePlan
import com.rokid.context.capture.plan.PLAN_MAX_BYTES
import com.rokid.context.capture.plan.ExprWindows
import com.rokid.context.capture.plan.SIG_SCHEMA
import com.rokid.context.capture.plan.SIG_SCHEMA_WIN
import com.rokid.context.capture.plan.planSchemaFor
import com.rokid.context.capture.plan.GateBlock
import com.rokid.context.capture.plan.PlanConfig
import com.rokid.context.capture.plan.PlanJson
import com.rokid.context.capture.plan.PlanMeta
import com.rokid.context.capture.plan.PlanResolver
import com.rokid.context.capture.plan.PlanRow
import com.rokid.context.capture.plan.PlanState
import com.rokid.context.capture.plan.PlanValidator
import com.rokid.context.capture.plan.TimerPhotoGaps
import com.rokid.context.core.model.CameraWake
import com.rokid.context.glasses.capture.session.BudgetTier
import com.rokid.context.glasses.capture.session.CaptureProfile
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

data class StaticCost(val modelCalls: Int, val rtOps: Int, val cpuClass: String)

/** [plan] and [planJson] are both null exactly when [problems] is not empty. [warnings] never block a plan. There is no clamped output. */
data class CompileResult(
    val plan: CapturePlan?, val planJson: String?, val problems: List<String>, val warnings: List<String>, val staticCost: StaticCost,
)

/**
 * Compiles the capture half of a pipeline (the RT_GLASSES and RT_PHONE graphs) into a [CapturePlan] (S8, S27, S68, S78, S84).
 * Pure and deterministic: nodes and edges are visited in id order, so the input order never changes the output. Every field the
 * plan carries is resolved: from the graph, else from [PlanResolver.defaults] (today's behaviour). The compiler only fills the
 * fixed plan schema, so it can never emit a sensing or detection key (S84). Problem texts are fixed and name node ids, type ids and bounds. Pipeline,
 * node and model titles are never echoed, but the validator texts the compiler forwards can echo an unknown param, row or column key
 * (truncated to 40 chars). The ASYNC half is ignored here (slice 2's interpreter runs it).
 */
object PlanCompiler {
    const val WARN_GAP = "timer photo gap below the settings profile has no effect"
    const val HUB_PARAMS = "hub fusion params arrive in slice 3a-LATER"
    const val NO_CAPTURE_HALF = "pipeline has no capture half"
    const val CTX_TO_POLICY = "ctx binding must feed policy.capture_rate"
    const val CTX_FROM_HUB = "ctx binding must be sent from hub.context"

    /** S68: the settings profile a plan row composes with (SETTLE wake, budget tier T0, the shortest gaps; `maxOf` makes lower plan gaps moot). */
    val SETTINGS_PROFILE: CaptureProfile = CaptureProfile.of(CameraWake.SETTLE, BudgetTier.T0)

    private val OP_NOT_ALLOWED = Regex("^node [a-z][a-z0-9_]*: op not allowed for feature ")
    /** The validator's release check runs against the head's own onset; the compiler checks it again after the thresholds are folded in. */
    private val RELEASE_PRE_FOLD = Regex("^node [a-z][a-z0-9_]*: releaseRadS must not exceed onsetRadS$")
    private val SENSING = Regex("^(sampleRate.*|sensor.*|imu.*Hz|tick.*|fusionCadence.*|evaluate.*|detector.*|classifier.*)$", RegexOption.IGNORE_CASE)

    /** The fixed chain (S78); thresholds may repeat, every other type once. */
    private val RANK = mapOf(
        NodeTypes.IMU_HEAD_MOTION to 0, NodeTypes.GATE_THRESHOLD to 1, NodeTypes.IMU_SETTLE to 2, NodeTypes.GATE_NOVELTY to 3,
        NodeTypes.POLICY_CAPTURE_RATE to 4, NodeTypes.GATE_RATE_CAP to 5, NodeTypes.CAPTURE_IMAGE to 6,
    )

    /** Per feature: the only operator, the plan field it folds into, and the bounds of the folded value (the S78 operator table). */
    private data class Fold(val op: String, val lo: Double, val hi: Double, val whole: Boolean, val why: String)
    private val FOLDS = mapOf(
        "gyroPeak" to Fold(">", 0.4, 1.0, false, "onset is strict >"),
        "boutMs" to Fold(">=", 100.0, 800.0, true, "bouts are inclusive >="),
        "boutRad" to Fold(">=", 0.1, 0.8, false, "bouts are inclusive >="),
    )

    fun compile(
        p: Pipeline, settingsRev: Long = 0, profile: CaptureProfile = SETTINGS_PROFILE,
    ): CompileResult {
        val nodes = p.nodes.sortedBy { it.id }
        val edges = p.edges.sortedWith(compareBy({ it.from }, { it.to }, { it.`when` ?: "" }))
        val byId = nodes.associateBy { it.id }
        fun type(n: PipelineNode): NodeType? = NodeTypes.resolve(n.type, p.schema)
        fun domain(n: PipelineNode): Domain? = type(n)?.let { NodeTypes.domainOf(n, it) }
        val glasses = nodes.filter { domain(it) == Domain.RT_GLASSES }
        val phone = nodes.filter { domain(it) == Domain.RT_PHONE }
        val cost = cost(glasses + phone, ::type)

        // 1. the compiler's own checks come first, because their texts replace the generic ones they overlap with
        val own = LinkedHashSet<String>()
        val flaggedEdges = HashSet<String>()
        val flaggedNodes = HashSet<String>()
        for (n in glasses.filter { it.type == NodeTypes.GATE_THRESHOLD }) operatorProblem(n)?.let { own += it }
        for (n in glasses + phone) {
            if (n.params.keys.any { SENSING.matches(it) }) own += "node ${n.id}: sensing and detection are not plan parameters"
        }
        if (phone.any { it.type == NodeTypes.HUB_CONTEXT && it.params.isNotEmpty() }) own += HUB_PARAMS
        topology(glasses, phone, edges, byId, own, flaggedEdges, flaggedNodes)

        // 2. the pipeline's own rules; the operator check, a port mismatch on an edge the chain check refused and an unreachable
        //    unbound signal are reported once, with the texts above
        val problems = LinkedHashSet<String>()
        problems += PipelineValidator.validate(p.copy(nodes = nodes, edges = edges)).filterNot { v ->
            OP_NOT_ALLOWED.containsMatchIn(v) || flaggedEdges.any { v.startsWith("edge $it: ") && v.contains(" not accepted by ") } ||
                flaggedNodes.any { v == "node $it: unreachable" } || RELEASE_PRE_FOLD.matches(v)
        }
        problems += own
        if (glasses.isEmpty() && problems.isEmpty()) problems += NO_CAPTURE_HALF
        if (problems.isNotEmpty()) return failed(problems, cost)

        // 3. nothing else to check before the fold

        // 4. fold
        val base = PlanResolver.identity()
        val chain = glasses.filter { it.type in RANK }
        val ctxBound = glasses.any { n -> n.type == NodeTypes.SIGNAL_IN && edges.any { it.from == n.id && byId[it.to]?.type == NodeTypes.POLICY_CAPTURE_RATE } }
        var g = base.glance
        var gate = base.gate
        val head = chain.firstOrNull { it.type == NodeTypes.IMU_HEAD_MOTION }
        head?.let { n ->
            g = g.copy(
                onsetRadS = num(n, "onsetRadS")?.toFloat() ?: g.onsetRadS, releaseRadS = num(n, "releaseRadS")?.toFloat() ?: g.releaseRadS,
                bridgeMs = int(n, "bridgeMs")?.toLong() ?: g.bridgeMs, minBoutMs = int(n, "minBoutMs")?.toLong() ?: g.minBoutMs,
                minBoutRad = num(n, "minBoutRad")?.toFloat() ?: g.minBoutRad,
            )
        }
        chain.firstOrNull { it.type == NodeTypes.IMU_SETTLE }?.let { n -> g = g.copy(settleMs = int(n, "settleMs")?.toLong() ?: g.settleMs) }
        chain.firstOrNull { it.type == NodeTypes.GATE_NOVELTY }?.let { n ->
            g = g.copy(noveltyWindowMs = int(n, "noveltyWindowMs")?.toLong() ?: g.noveltyWindowMs, noveltyMinRad = num(n, "noveltyMinRad")?.toFloat() ?: g.noveltyMinRad)
        }
        chain.firstOrNull { it.type == NodeTypes.GATE_RATE_CAP }?.let { n ->
            gate = gate.copy(bigChange = int(n, "bigChange") ?: gate.bigChange)
            g = g.copy(bigTurnRad = num(n, "bigTurnRad")?.toFloat() ?: g.bigTurnRad)
        }
        chain.firstOrNull { it.type == NodeTypes.CAPTURE_IMAGE }?.let { n ->
            gate = gate.copy(hamming = int(n, "hamming") ?: gate.hamming, ringCompare = str(n, "ringCompare") ?: gate.ringCompare)
        }

        // thresholds fold into glance.*; two on one feature must agree, and an explicit head param must agree too
        val seen = LinkedHashMap<String, Pair<PipelineNode, Double>>()
        for (n in chain.filter { it.type == NodeTypes.GATE_THRESHOLD }) {
            val feature = str(n, "feature") ?: "gyroPeak"
            val fold = FOLDS.getValue(feature)
            val v = num(n, "value") ?: 0.6
            if (v < fold.lo || v > fold.hi) { problems += "node ${n.id}: $feature threshold must be between ${fmt(fold.lo)} and ${fmt(fold.hi)}"; continue }
            if (fold.whole && v != Math.floor(v)) { problems += "node ${n.id}: $feature threshold must be a whole number"; continue }
            val first = seen[feature]
            if (first != null) {
                if (first.second != v) problems += "node ${n.id}: $feature threshold conflicts with node ${first.first.id}"
                continue
            }
            val own = head?.let { h -> num(h, ownParam(feature)) }
            if (own != null && own != v) { problems += "node ${n.id}: $feature threshold conflicts with ${ownParam(feature)} of node ${head.id}"; continue }
            seen[feature] = n to v
            g = when (feature) {
                "gyroPeak" -> g.copy(onsetRadS = v.toFloat())
                "boutMs" -> g.copy(minBoutMs = v.toLong())
                else -> g.copy(minBoutRad = v.toFloat())
            }
        }
        if (problems.isEmpty() && head != null && g.releaseRadS > g.onsetRadS) {
            problems += if (seen.containsKey("gyroPeak")) "node ${head.id}: releaseRadS must not exceed the gyroPeak threshold"
            else "node ${head.id}: releaseRadS must not exceed onsetRadS"
        }
        val warnings = LinkedHashSet<String>()
        val policy = chain.firstOrNull { it.type == NodeTypes.POLICY_CAPTURE_RATE }?.let { rows(it, base.policy, profile, warnings) } ?: base.policy
        // expression guards: two-level graphs lowered to the plan's step list (X2); the plan stays schema 1 without any
        val exprNodes = glasses.filter { it.type == NodeTypes.EXPR_GUARD }
        val lowered = ExprLowering.lower(exprNodes.map { it.id to it.params["graph"] })
        problems += lowered.problems
        warnings += lowered.warnings
        if (problems.isNotEmpty()) return failed(problems, cost)

        val config = PlanConfig(
            glance = g, policy = policy, gate = gate, ctxBound = ctxBound, phoneFusion = base.phoneFusion,
            exprs = lowered.exprs, sigSchema = if (lowered.exprs.isEmpty()) 0 else if (ExprWindows.needsSig2(lowered.exprs)) SIG_SCHEMA_WIN else SIG_SCHEMA,
        )
        val plan = CapturePlan(planSchema = planSchemaFor(config), configHash = PlanJson.configHash(config), config = config, meta = PlanMeta(p.name, settingsRev))
        val json = PlanJson.encode(plan)
        if (lowered.exprs.isNotEmpty() && json.toByteArray().size + lowered.stateBytes > PLAN_MAX_BYTES) {
            val bytes = json.toByteArray().size
            val withState = if (lowered.stateBytes > 0) " and ${lowered.stateBytes} bytes of window state" else ""
            return failed(linkedSetOf("node ${exprNodes.first().id}: ${ExprDiag(ExprCode.EXPR_BYTES, "plan is $bytes bytes with its expressions$withState (max $PLAN_MAX_BYTES)")}"), cost)
        }
        // the output must pass the plan validator, by construction; if it ever does not, nothing is emitted
        val (planProblems, _) = PlanValidator.check(json)
        if (planProblems.isNotEmpty()) return failed(planProblems.toCollection(LinkedHashSet()), cost)
        return CompileResult(plan, json, emptyList(), warnings.toList(), cost)
    }

    private fun failed(problems: Set<String>, cost: StaticCost) = CompileResult(null, null, problems.toList(), emptyList(), cost)

    private fun ownParam(feature: String) = when (feature) { "gyroPeak" -> "onsetRadS"; "boutMs" -> "minBoutMs"; else -> "minBoutRad" }

    /** S78: the operator text for a threshold whose op does not match its feature; null when fine or when the feature itself is invalid. */
    private fun operatorProblem(n: PipelineNode): String? {
        val feature = str(n, "feature") ?: "gyroPeak"
        val fold = FOLDS[feature] ?: return null
        val op = str(n, "op") ?: ">"
        if (op != ">" && op != ">=") return null
        return if (op == fold.op) null else "$op is not supported for $feature: ${fold.why}"
    }

    private fun topology(
        glasses: List<PipelineNode>, phone: List<PipelineNode>, edges: List<PipelineEdge>, byId: Map<String, PipelineNode>,
        out: MutableSet<String>, flaggedEdges: MutableSet<String>, flaggedNodes: MutableSet<String>,
    ) {
        val ids = glasses.map { it.id }.toSet()
        val chainEdges = edges.filter { byId[it.from]?.type in RANK && byId[it.to]?.type in RANK && it.from in ids && it.to in ids }
        fun rank(id: String) = RANK.getValue(byId.getValue(id).type)
        fun flag(e: PipelineEdge) {
            out += "unsupported wrapper order: ${byId.getValue(e.from).type} before ${byId.getValue(e.to).type}"
            flaggedEdges += "${e.from}->${e.to}"
        }
        for (e in chainEdges) {
            val ra = rank(e.from); val rb = rank(e.to)
            if (ra > rb || (ra == rb && byId.getValue(e.from).type != NodeTypes.GATE_THRESHOLD)) flag(e)
        }
        // a branch: a second way out of, or into, one chain node (the edge to the nearest later node, and from the nearest earlier one, is the chain's)
        for (e in chainEdges) {
            val keepOut = chainEdges.filter { it.from == e.from }.minWith(compareBy({ rank(it.to) }, { it.to }))
            val keepIn = chainEdges.filter { it.to == e.to }.maxWith(compareBy({ rank(it.from) }, { it.from }))
            if (keepOut != e || keepIn != e) flag(e)
        }
        // a second node of any non-threshold chain type is a branch of the fixed chain
        for ((type, group) in glasses.filter { it.type in RANK && it.type != NodeTypes.GATE_THRESHOLD }.groupBy { it.type }) {
            if (group.size > 1) { out += "unsupported wrapper order: $type before $type"; group.drop(1).forEach { flaggedNodes += it.id } }
        }
        // a policy that is fed by ctx must sit on the chain when the chain has other nodes
        val onChain = chainEdges.flatMap { listOf(it.from, it.to) }.toSet()
        if (onChain.isNotEmpty()) {
            for (n in glasses.filter { it.type == NodeTypes.POLICY_CAPTURE_RATE && it.id !in onChain }) {
                if (edges.any { it.to == n.id && byId[it.from]?.type == NodeTypes.SIGNAL_IN }) out += "unsupported wrapper order: ${n.type} is not on the chain"
            }
        }
        // the ctx binding: signal.in ctx feeds policy.capture_rate only, and signal.out ctx is sent from hub.context
        for (n in glasses.filter { it.type == NodeTypes.SIGNAL_IN }) {
            if ((str(n, "name") ?: "ctx") != "ctx") continue
            val targets = edges.filter { it.from == n.id }.mapNotNull { byId[it.to] }
            if (targets.size != 1 || targets.any { it.type != NodeTypes.POLICY_CAPTURE_RATE }) { out += CTX_TO_POLICY; flaggedNodes += n.id }
        }
        for (n in phone.filter { it.type == NodeTypes.SIGNAL_OUT }) {
            if ((str(n, "name") ?: "ctx") != "ctx") continue
            if (edges.none { it.to == n.id && byId[it.from]?.type == NodeTypes.HUB_CONTEXT }) { out += CTX_FROM_HUB; flaggedNodes += n.id }
        }
    }

    /**
     * One row per state, always all of them. A state with no row is today's row. In a present row an absent cell takes the identity
     * row's value and only an explicit JSON null is blank: a null cap is no cap and a null gap is 0 = inherit (S68).
     */
    private fun rows(n: PipelineNode, base: Map<PlanState, PlanRow>, profile: CaptureProfile, warnings: MutableSet<String>): Map<PlanState, PlanRow> {
        val table = n.params["rows"] as? JsonObject
        return PlanState.entries.associateWith { s ->
            val row = table?.get(s.name) as? JsonObject ?: return@associateWith base.getValue(s)
            val b = base.getValue(s)
            fun cell(k: String): JsonPrimitive? = (row[k] as? JsonPrimitive)?.takeIf { !it.isString }
            fun gap(k: String, d: Long): Long = if (k !in row) d else if (row[k] is JsonNull) 0L else (cell(k)?.intOrNull ?: 0) * 1_000L
            fun flag(k: String, d: Boolean) = cell(k)?.takeIf { it !is JsonNull }?.booleanOrNull ?: d
            val g0 = b.timerPhotoGapsMs
            val gaps = TimerPhotoGaps(gap("settledGapS", g0.settledMs), gap("quietGapS", g0.quietMs), gap("walkGapS", g0.walkMs), gap("stillCapGapS", g0.stillCapMs))
            // S68: the settings profile wins when it is longer; a plan gap below it has no effect
            if (gaps.settledMs.below(profile.settledCheckMs) || gaps.quietMs.below(profile.quietCheckMs) ||
                gaps.walkMs.below(profile.walkCheckMs ?: profile.settledCheckMs) || gaps.stillCapMs.below(profile.stillCapMs)
            ) warnings += WARN_GAP
            val cap = if ("capPerMin" !in row) b.capPerMin else cell("capPerMin")?.takeIf { it !is JsonNull }?.intOrNull
            PlanRow(capPerMin = cap, timerPhotoGapsMs = gaps, forceSettle = flag("forceSettle", b.forceSettle), nudges = flag("nudges", b.nudges))
        }
    }

    private fun Long.below(limit: Long) = this != 0L && this < limit

    private fun cost(nodes: List<PipelineNode>, type: (PipelineNode) -> NodeType?): StaticCost {
        val specs = nodes.mapNotNull { type(it)?.cost }
        val order = listOf("LOW", "MEDIUM", "HIGH")
        return StaticCost(specs.sumOf { it.modelCalls }, specs.sumOf { it.rtOps }, specs.maxOfOrNull { order.indexOf(it.cpuClass) }?.let { order.getOrElse(it) { "LOW" } } ?: "LOW")
    }

    private fun num(n: PipelineNode, k: String): Double? = (n.params[k] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
    private fun int(n: PipelineNode, k: String): Int? = (n.params[k] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
    private fun str(n: PipelineNode, k: String): String? = (n.params[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun fmt(d: Double) = if (d == Math.floor(d)) d.toLong().toString() else d.toString()
}
