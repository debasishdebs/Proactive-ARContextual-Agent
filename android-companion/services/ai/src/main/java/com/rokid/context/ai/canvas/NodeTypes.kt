package com.rokid.context.ai.canvas

import com.rokid.context.ai.contract.PromptKey
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

enum class PortType { PHOTOS, EXTRACTION, JSON, PREVIEW, IMU, MOTION_EVENT, CONTEXT, TRIGGER, VECTOR, TEXT, SIGNAL, MOMENTS, SCENES, SUMMARY, NUMBER }

/** Where a node runs (slice 3 section 2). A `signal.*` node's domain is its `params.domain`, per instance. */
enum class Domain { RT_GLASSES, RT_PHONE, ASYNC, REPLAY_ONLY }

/** Palette group. The first four are the slice 2 groups, kept for the v1 descriptors. */
enum class Category(val label: String) {
    INPUTS("Inputs"), AI("AI"), ROUTING("Routing"), CHECKS("Checks"),
    SENSORS("Sensors"), SIGNALS_HUB("Signals/Hub"), THRESHOLDS_FILTERS("Thresholds/Filters"), POLICY_FREQUENCY("Policy/Frequency"),
    ACTIONS_CAPTURE("Actions/Capture"), AI_CLASSIFY("AI/Classify"), MEMORY("Memory"), DATA("Data"), CODE("Code"),
}

/**
 * The generic flow every agentic build follows, whatever device runs it (specs/2026-10-24-generic-flow-families.md): the palette groups by these five.
 * A node's lane ([Domain]) stays its own attribute. The first [AGGREGATE] is `agg.window` (phone side); interval aggregates on the glasses come with 3c E2.
 */
enum class Family(val label: String) { SOURCE("Source"), AGGREGATE("Aggregate"), PROCESS("Process"), CONDITION("Condition"), OUTCOME("Outcome") }

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
    /** The palette group (generic flow family); served as `family` next to the old `category`, which stays for saved views and tests. */
    val family: Family = Family.PROCESS,
)

object NodeTypes {
    const val INPUT_PHOTOS = "input.photos"
    const val VISION_EXTRACT = "ai.vision_extract"
    const val LOCAL_CRITIC = "gate.local_critic"
    const val CRITIC = "ai.critic"
    const val PROMPT = "ai.prompt"
    const val SWITCH = "route.switch"
    const val MEMORY_WRITE = "memory.write"

    /** Process: one short summary per item, with a confidence (SUMMARY output); see [SummaryNode]. */
    const val SUMMARY = "proc.summary"

    /** Aggregate: mean, min, max or count of one sensor feature over the last N items of a Scenes or Moments input (NUMBER output); see [WindowAggregate]. */
    const val AGG_WINDOW = "agg.window"

    /** Condition: compares the NUMBER of a [AGG_WINDOW] with a cutoff and branches yes or no (the number passes through). */
    const val COMPARE = "gate.compare"

    const val EXPR_GUARD = "expr.guard"
    const val EXPR_VALUE = "expr.value"
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

    /** Scenes: an ASYNC input root next to `input.moments`, read by the phone-side scene executor (stored Scenes of a time window). Never published, no glasses plan; `ai.prompt` and `py.fn` accept its SCENES output (a run item per Scene with its visible text). */
    const val INPUT_SCENES = "input.scenes"

    const val OTHER = "other"

    /** The domains a `signal.*` node may run in. */
    val SIGNAL_DOMAINS = listOf(Domain.RT_GLASSES, Domain.RT_PHONE)

    /** The `cases` labels of a switch; non-string entries are ignored (the validator rejects them via the count). */
    fun switchCases(params: JsonObject): List<String> =
        (params["cases"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content } ?: emptyList()

    private val legacy: List<NodeType> = listOf(
        NodeType(INPUT_PHOTOS, "Photo", emptySet(), PortType.PHOTOS, category = Category.INPUTS, short = "photos", palette = false, root = true, params = PhotoSettings.schema, family = Family.SOURCE),
        NodeType(VISION_EXTRACT, "Facts from frames", setOf(PortType.PHOTOS, PortType.JSON), PortType.EXTRACTION, PromptKey.VISION_EXTRACT,
            executor = ExecutorKind.PROMPT, short = "extract", inner = ExtractComposite.inner, family = Family.PROCESS),
        NodeType(LOCAL_CRITIC, "Local trust check", setOf(PortType.EXTRACTION, PortType.SUMMARY), PortType.EXTRACTION, branches = { listOf("trusted", "unsure") },
            category = Category.CHECKS, short = "trust", params = LocalTrust.schema, inner = LocalTrust.inner, family = Family.CONDITION),
        NodeType(CRITIC, "Critic", setOf(PortType.EXTRACTION), PortType.EXTRACTION, PromptKey.CRITIC, branches = { listOf("pass", "fail") },
            executor = ExecutorKind.PROMPT, short = "critic", inner = CriticComposite.inner, family = Family.PROCESS),
        NodeType(PROMPT, "Prompt", setOf(PortType.PHOTOS, PortType.JSON, PortType.EXTRACTION, PortType.SCENES, PortType.MOMENTS), PortType.JSON, executor = ExecutorKind.PROMPT, short = "prompt", family = Family.PROCESS),
        // new in v0.9.4: a schema-2 type (a v1 graph never holds it), no params and no prompt of its own; its executor is the phone-side text path of `ai.prompt`
        NodeType(SUMMARY, "Summary", setOf(PortType.JSON, PortType.EXTRACTION, PortType.MOMENTS, PortType.SCENES), PortType.SUMMARY, sinceSchema = 2, executor = ExecutorKind.PROMPT,
            short = "summary", family = Family.PROCESS),
        // v0.9.4, phone-side, deterministic, no model: the first Aggregate, and the Condition that reads its number
        NodeType(AGG_WINDOW, "Window aggregate", setOf(PortType.SCENES, PortType.MOMENTS), PortType.NUMBER, sinceSchema = 2, category = Category.DATA, short = "window",
            params = WindowAggregate.schema, family = Family.AGGREGATE),
        NodeType(COMPARE, "Compare number", setOf(PortType.NUMBER), PortType.NUMBER, branches = { listOf("yes", "no") }, sinceSchema = 2, category = Category.CHECKS, short = "compare",
            params = NumberCompare.schema, family = Family.CONDITION),
        NodeType(SWITCH, "Switch", setOf(PortType.JSON), PortType.JSON, branches = { switchCases(it) + OTHER }, category = Category.ROUTING, short = "route", family = Family.CONDITION),
        NodeType(MEMORY_WRITE, "Save memory (preview)", setOf(PortType.EXTRACTION, PortType.SUMMARY), PortType.PREVIEW, category = Category.MEMORY, executor = ExecutorKind.TOOL, short = "write", inner = MemoryWriteComposite.inner, family = Family.OUTCOME),
    )

    private val capture: List<NodeType> = listOf(
        NodeType(IMU_HEAD_MOTION, "Head movement", emptySet(), PortType.MOTION_EVENT, sinceSchema = 2, domain = Domain.RT_GLASSES,
            category = Category.SENSORS, short = "head", params = CaptureParams.headMotion, root = true, cost = CostSpec(rtOps = 1), family = Family.SOURCE),
        NodeType(IMU_SETTLE, "Settle", setOf(PortType.MOTION_EVENT), PortType.MOTION_EVENT, sinceSchema = 2, domain = Domain.RT_GLASSES,
            category = Category.SENSORS, short = "settle", params = CaptureParams.settle, family = Family.SOURCE),
        NodeType(GATE_THRESHOLD, "Threshold", setOf(PortType.MOTION_EVENT), PortType.MOTION_EVENT, sinceSchema = 2, domain = Domain.RT_GLASSES,
            category = Category.THRESHOLDS_FILTERS, short = "thresh", params = CaptureParams.threshold, family = Family.CONDITION),
        // E1 expression guard: the whole two-level graph is the one `graph` param (validated, no generated form: the editor is X5). SHADOW/TRIAL only.
        NodeType(EXPR_GUARD, "Guard (expression)", setOf(PortType.MOTION_EVENT), PortType.MOTION_EVENT, sinceSchema = 2, domain = Domain.RT_GLASSES,
            category = Category.THRESHOLDS_FILTERS, executor = ExecutorKind.EXPR, short = "guard", params = ExprParams.guard, liveable = false, cost = CostSpec(rtOps = 1), family = Family.CONDITION),
        // E2a expression value: tunes the capture cap or a gap of ONE editable row from a number program (clamped; an error or a warming window keeps the row default). SHADOW/TRIAL only.
        NodeType(EXPR_VALUE, "Value (expression)", setOf(PortType.MOTION_EVENT), PortType.MOTION_EVENT, sinceSchema = 2, domain = Domain.RT_GLASSES,
            category = Category.POLICY_FREQUENCY, executor = ExecutorKind.EXPR, short = "value", params = ExprParams.value, liveable = false, cost = CostSpec(rtOps = 1), family = Family.CONDITION),
        NodeType(GATE_NOVELTY, "Novelty", setOf(PortType.MOTION_EVENT), PortType.TRIGGER, sinceSchema = 2, domain = Domain.RT_GLASSES,
            category = Category.THRESHOLDS_FILTERS, short = "novelty", params = CaptureParams.novelty, family = Family.CONDITION),
        NodeType(HUB_CONTEXT, "Motion from context hub", emptySet(), PortType.CONTEXT, sinceSchema = 2, domain = Domain.RT_PHONE,
            category = Category.SIGNALS_HUB, short = "hub", params = CaptureParams.hub, root = true, family = Family.SOURCE),
        NodeType(SIGNAL_IN, "Receive signal", emptySet(), PortType.CONTEXT, sinceSchema = 2, domain = Domain.RT_GLASSES,
            category = Category.SIGNALS_HUB, executor = ExecutorKind.SIGNAL, short = "sigin", params = CaptureParams.signalIn, domainFromParams = true, family = Family.SOURCE),
        NodeType(SIGNAL_OUT, "Send signal", setOf(PortType.CONTEXT), PortType.SIGNAL, sinceSchema = 2, domain = Domain.RT_PHONE,
            category = Category.SIGNALS_HUB, executor = ExecutorKind.SIGNAL, short = "sigout", params = CaptureParams.signalOut, domainFromParams = true, family = Family.OUTCOME),
        NodeType(POLICY_CAPTURE_RATE, "Capture frequency by activity", setOf(PortType.MOTION_EVENT, PortType.TRIGGER, PortType.CONTEXT), PortType.TRIGGER,
            sinceSchema = 2, domain = Domain.RT_GLASSES, category = Category.POLICY_FREQUENCY, executor = ExecutorKind.TABLE, short = "rate",
            params = CaptureParams.captureRate, mixedIn = true, family = Family.OUTCOME),
        NodeType(GATE_RATE_CAP, "Rate cap", setOf(PortType.TRIGGER), PortType.TRIGGER, sinceSchema = 2, domain = Domain.RT_GLASSES,
            category = Category.THRESHOLDS_FILTERS, short = "cap", params = CaptureParams.rateCap, family = Family.CONDITION),
        NodeType(CAPTURE_IMAGE, "Capture image", setOf(PortType.TRIGGER), PortType.PHOTOS, sinceSchema = 2, domain = Domain.RT_GLASSES,
            category = Category.ACTIONS_CAPTURE, short = "capture", params = CaptureParams.captureImage, family = Family.OUTCOME),
    )

    /** The python experiment node (S6 tier 3). In the palette since WP9 (the helper UI exists) but never liveable and never published; `entry` is the fixed function name `run`, not a param. */
    private val experiments: List<NodeType> = listOf(
        NodeType(INPUT_MOMENTS, "Moments", emptySet(), PortType.MOMENTS, sinceSchema = 2, domain = Domain.ASYNC, category = Category.INPUTS,
            short = "moments", liveable = false, root = true, cost = CostSpec(modelCalls = 0), family = Family.SOURCE),
        NodeType(INPUT_SCENES, "Scenes", emptySet(), PortType.SCENES, sinceSchema = 2, domain = Domain.ASYNC, category = Category.INPUTS,
            short = "scenes", liveable = false, root = true, cost = CostSpec(modelCalls = 0), family = Family.SOURCE),
        NodeType(PY_FN, "Python (experiment)", setOf(PortType.JSON, PortType.MOMENTS, PortType.SCENES), PortType.JSON, sinceSchema = 2, domain = Domain.ASYNC, category = Category.CODE,
            executor = ExecutorKind.PYTHON, short = "pyfn", liveable = false, cost = CostSpec(modelCalls = 0),
            hint = "Needs the laptop helper and your confirm tap on the phone for each run; never published to the glasses.",
            params = ParamSchema(listOf(
                ParamSpec("source", ParamKind.TEXT, label = "Source", maxLen = 16000),
                ParamSpec("outSchema", ParamKind.TEXT, label = "Output schema", maxLen = 4000),
            )), family = Family.PROCESS),
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
