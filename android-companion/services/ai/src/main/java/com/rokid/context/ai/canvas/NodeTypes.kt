package com.rokid.context.ai.canvas

import com.rokid.context.ai.contract.PromptKey
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

enum class PortType { PHOTOS, EXTRACTION, JSON, PREVIEW, IMU, MOTION_EVENT, CONTEXT, TRIGGER, VECTOR, TEXT, SIGNAL, MOMENTS }

/** Where a node runs (slice 3 section 2). A `signal.*` node's domain is its `params.domain`, per instance. */
enum class Domain { RT_GLASSES, RT_PHONE, ASYNC, REPLAY_ONLY }

/** Palette group. The first four are the slice 2 groups, kept for the v1 descriptors. */
enum class Category(val label: String) {
    INPUTS("Inputs"), AI("AI"), ROUTING("Routing"), CHECKS("Checks"),
    SENSORS("Sensors"), SIGNALS_HUB("Signals/Hub"), THRESHOLDS_FILTERS("Thresholds/Filters"), POLICY_FREQUENCY("Policy/Frequency"),
    ACTIONS_CAPTURE("Actions/Capture"), AI_CLASSIFY("AI/Classify"), MEMORY("Memory"), DATA("Data"), CODE("Code"),
}

enum class ExecutorKind { BUILTIN, EXPR, TABLE, PROMPT, EMBED, TOOL, EVENT, SIGNAL, PYTHON, DRAFT }

data class CostSpec(val modelCalls: Int = 0, val rtOps: Int = 0, val cpuClass: String = "LOW")

data class NodeType(
    val type: String,
    val label: String,
    val inputs: Set<PortType>,
    val output: PortType,
    val promptKey: PromptKey? = null,
    val branches: (JsonObject) -> List<String> = { emptyList() },
    val version: Int = 1,
    /** The first graph schema that may hold this type; a schema-1 node resolves only to a type with `sinceSchema == 1` (S41). */
    val sinceSchema: Int = 1,
    val domain: Domain = Domain.ASYNC,
    val category: Category = Category.AI,
    val executor: ExecutorKind = ExecutorKind.BUILTIN,
    val short: String = "",
    val palette: Boolean = true,
    val params: ParamSchema = ParamSchema.NONE,
    val cost: CostSpec = CostSpec(),
    val liveable: Boolean = true,
    /** The one root of its domain's graph (`imu.head_motion`, `hub.context`, `input.photos`). */
    val root: Boolean = false,
    /** Incoming edges may carry different port types (`policy.capture_rate` takes a trigger and a context). */
    val mixedIn: Boolean = false,
    /** The domain comes from `params.domain` (the `signal.*` nodes). */
    val domainFromParams: Boolean = false,
    /** A short line the palette shows under the label; empty for every type but the experiment node. */
    val hint: String = "",
    /** A composite (black-box) node's inner flow, shown by the editor when it expands; null for a plain node. Description only, never executed. */
    val inner: InnerFlow? = null,
)

object NodeTypes {
    const val INPUT_PHOTOS = "input.photos"
    const val VISION_EXTRACT = "ai.vision_extract"
    const val LOCAL_CRITIC = "gate.local_critic"
    const val CRITIC = "ai.critic"
    const val PROMPT = "ai.prompt"
    const val SWITCH = "route.switch"
    const val MEMORY_WRITE = "memory.write"

    const val EXPR_GUARD = "expr.guard"
    const val IMU_HEAD_MOTION = "imu.head_motion"
    const val IMU_SETTLE = "imu.settle"
    const val GATE_THRESHOLD = "gate.threshold"
    const val GATE_NOVELTY = "gate.novelty"
    const val GATE_RATE_CAP = "gate.rate_cap"
    const val HUB_CONTEXT = "hub.context"
    const val SIGNAL_IN = "signal.in"
    const val SIGNAL_OUT = "signal.out"
    const val POLICY_CAPTURE_RATE = "policy.capture_rate"
    const val CAPTURE_IMAGE = "capture.image"

    /** S6 tier 3: laptop-run Python. Registered so a graph can hold it, never published, never run on the phone and never in `Executors.all()` (its executor is the experiment map's, WP8). */
    const val PY_FN = "py.fn"

    /** Moment-lite: the root of an experiment over Moments (a derived read model, nothing stored). ASYNC only, never published, never run on the phone's own nodes: only `py.fn` accepts its output. */
    const val INPUT_MOMENTS = "input.moments"

    const val OTHER = "other"

    /** The domains a `signal.*` node may run in. */
    val SIGNAL_DOMAINS = listOf(Domain.RT_GLASSES, Domain.RT_PHONE)

    /** The `cases` labels of a switch; non-string entries are ignored (the validator rejects them via the count). */
    fun switchCases(params: JsonObject): List<String> =
        (params["cases"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content } ?: emptyList()

    private val legacy: List<NodeType> = listOf(
        NodeType(INPUT_PHOTOS, "Photo", emptySet(), PortType.PHOTOS, category = Category.INPUTS, short = "photos", palette = false, root = true),
        NodeType(VISION_EXTRACT, "Facts from frames", setOf(PortType.PHOTOS, PortType.JSON), PortType.EXTRACTION, PromptKey.VISION_EXTRACT,
            executor = ExecutorKind.PROMPT, short = "extract"),
        NodeType(LOCAL_CRITIC, "Local trust check", setOf(PortType.EXTRACTION), PortType.EXTRACTION, branches = { listOf("trusted", "unsure") },
            category = Category.CHECKS, short = "trust", params = LocalTrust.schema, inner = LocalTrust.inner),
        NodeType(CRITIC, "Critic", setOf(PortType.EXTRACTION), PortType.EXTRACTION, PromptKey.CRITIC, branches = { listOf("pass", "fail") },
            executor = ExecutorKind.PROMPT, short = "critic"),
        NodeType(PROMPT, "Prompt", setOf(PortType.PHOTOS, PortType.JSON, PortType.EXTRACTION), PortType.JSON, executor = ExecutorKind.PROMPT, short = "prompt"),
        NodeType(SWITCH, "Switch", setOf(PortType.JSON), PortType.JSON, branches = { switchCases(it) + OTHER }, category = Category.ROUTING, short = "route"),
        NodeType(MEMORY_WRITE, "Save memory (preview)", setOf(PortType.EXTRACTION), PortType.PREVIEW, category = Category.MEMORY, executor = ExecutorKind.TOOL, short = "write"),
    )

    private val capture: List<NodeType> = listOf(
        NodeType(IMU_HEAD_MOTION, "Head movement", emptySet(), PortType.MOTION_EVENT, sinceSchema = 2, domain = Domain.RT_GLASSES,
            category = Category.SENSORS, short = "head", params = CaptureParams.headMotion, root = true, cost = CostSpec(rtOps = 1)),
        NodeType(IMU_SETTLE, "Settle", setOf(PortType.MOTION_EVENT), PortType.MOTION_EVENT, sinceSchema = 2, domain = Domain.RT_GLASSES,
            category = Category.SENSORS, short = "settle", params = CaptureParams.settle),
        NodeType(GATE_THRESHOLD, "Threshold", setOf(PortType.MOTION_EVENT), PortType.MOTION_EVENT, sinceSchema = 2, domain = Domain.RT_GLASSES,
            category = Category.THRESHOLDS_FILTERS, short = "thresh", params = CaptureParams.threshold),
        // E1 expression guard: the whole two-level graph is the one `graph` param (validated, no generated form: the editor is X5). SHADOW/TRIAL only.
        NodeType(EXPR_GUARD, "Guard (expression)", setOf(PortType.MOTION_EVENT), PortType.MOTION_EVENT, sinceSchema = 2, domain = Domain.RT_GLASSES,
            category = Category.THRESHOLDS_FILTERS, executor = ExecutorKind.EXPR, short = "guard", params = ExprParams.guard, liveable = false, cost = CostSpec(rtOps = 1)),
        NodeType(GATE_NOVELTY, "Novelty", setOf(PortType.MOTION_EVENT), PortType.TRIGGER, sinceSchema = 2, domain = Domain.RT_GLASSES,
            category = Category.THRESHOLDS_FILTERS, short = "novelty", params = CaptureParams.novelty),
        NodeType(HUB_CONTEXT, "Motion from context hub", emptySet(), PortType.CONTEXT, sinceSchema = 2, domain = Domain.RT_PHONE,
            category = Category.SIGNALS_HUB, short = "hub", params = CaptureParams.hub, root = true),
        NodeType(SIGNAL_IN, "Receive signal", emptySet(), PortType.CONTEXT, sinceSchema = 2, domain = Domain.RT_GLASSES,
            category = Category.SIGNALS_HUB, executor = ExecutorKind.SIGNAL, short = "sigin", params = CaptureParams.signalIn, domainFromParams = true),
        NodeType(SIGNAL_OUT, "Send signal", setOf(PortType.CONTEXT), PortType.SIGNAL, sinceSchema = 2, domain = Domain.RT_PHONE,
            category = Category.SIGNALS_HUB, executor = ExecutorKind.SIGNAL, short = "sigout", params = CaptureParams.signalOut, domainFromParams = true),
        NodeType(POLICY_CAPTURE_RATE, "Capture frequency by activity", setOf(PortType.MOTION_EVENT, PortType.TRIGGER, PortType.CONTEXT), PortType.TRIGGER,
            sinceSchema = 2, domain = Domain.RT_GLASSES, category = Category.POLICY_FREQUENCY, executor = ExecutorKind.TABLE, short = "rate",
            params = CaptureParams.captureRate, mixedIn = true),
        NodeType(GATE_RATE_CAP, "Rate cap", setOf(PortType.TRIGGER), PortType.TRIGGER, sinceSchema = 2, domain = Domain.RT_GLASSES,
            category = Category.THRESHOLDS_FILTERS, short = "cap", params = CaptureParams.rateCap),
        NodeType(CAPTURE_IMAGE, "Capture image", setOf(PortType.TRIGGER), PortType.PHOTOS, sinceSchema = 2, domain = Domain.RT_GLASSES,
            category = Category.ACTIONS_CAPTURE, short = "capture", params = CaptureParams.captureImage),
    )

    /** The python experiment node (S6 tier 3). In the palette since WP9 (the helper UI exists) but never liveable and never published; `entry` is the fixed function name `run`, not a param. */
    private val experiments: List<NodeType> = listOf(
        NodeType(INPUT_MOMENTS, "Moments", emptySet(), PortType.MOMENTS, sinceSchema = 2, domain = Domain.ASYNC, category = Category.INPUTS,
            short = "moments", liveable = false, root = true, cost = CostSpec(modelCalls = 0)),
        NodeType(PY_FN, "Python (experiment)", setOf(PortType.JSON, PortType.MOMENTS), PortType.JSON, sinceSchema = 2, domain = Domain.ASYNC, category = Category.CODE,
            executor = ExecutorKind.PYTHON, short = "pyfn", liveable = false, cost = CostSpec(modelCalls = 0),
            hint = "Needs the laptop helper and your confirm tap on the phone for each run; never published to the glasses.",
            params = ParamSchema(listOf(
                ParamSpec("source", ParamKind.TEXT, label = "Source", maxLen = 16000),
                ParamSpec("outSchema", ParamKind.TEXT, label = "Output schema", maxLen = 4000),
            ))),
    )

    val all: List<NodeType> = legacy + capture + experiments

    private val index = all.associateBy { it.type }

    /** The latest descriptor of [t]. */
    fun byType(t: String): NodeType? = index[t]

    /**
     * The descriptor a node of graph [schema] resolves to (S41): a schema-1 node resolves to version 1 of a slice 2 type, so every old
     * graph keeps validating as it did; a schema-2 graph resolves to the latest. Null for an unknown type, or for a type newer than [schema].
     */
    fun resolve(t: String, schema: Int): NodeType? = index[t]?.takeIf { it.sinceSchema <= schema }

    /** The domain of one node: its `params.domain` for a `signal.*` node (the type's default when absent), else its type's. */
    fun domainOf(n: PipelineNode, t: NodeType): Domain {
        if (!t.domainFromParams) return t.domain
        val raw = (n.params["domain"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return t.domain
        return Domain.entries.firstOrNull { it.name == raw } ?: t.domain
    }
}
