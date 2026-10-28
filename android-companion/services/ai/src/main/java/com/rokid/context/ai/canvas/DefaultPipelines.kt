package com.rokid.context.ai.canvas

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** The built-in pipelines, seeded read-only at first open. All pass [PipelineValidator]. */
object DefaultPipelines {
    const val LIVE_EXTRACT = "live-extract"
    const val INTENT_DRAFT = "intent-draft"
    val INTENT_LABELS = listOf("sitting_idle", "workout", "computer_work", "driving", "walking", "other")
    const val DEFAULT_CAPTURE = "default-capture"
    const val STARTER = "starter-capture-extract"
    const val STARTER_MOMENT_SUMMARY = "starter-moment-summary"
    const val STARTER_WATCH_MOTION = "starter-watch-motion"
    const val DEFAULT_QUERY = "default-query"
    const val DEFAULT_NUDGE = "default-nudge"
    val all: List<String> = listOf(LIVE_EXTRACT, INTENT_DRAFT, DEFAULT_CAPTURE, STARTER, STARTER_MOMENT_SUMMARY, STARTER_WATCH_MOTION, DEFAULT_QUERY, DEFAULT_NUDGE)

    private val none = JsonObject(emptyMap())
    private val livePrompt = buildJsonObject { putJsonObject("prompt") { put("source", "live") } }
    private fun liveBatch3() = buildJsonObject { put("batch", 3); putJsonObject("prompt") { put("source", "live") } }

    fun liveExtract(): Pipeline = Pipeline(
        PipelineJson.SCHEMA, LIVE_EXTRACT, "Live extraction",
        nodes = listOf(
            PipelineNode("photos", NodeTypes.INPUT_PHOTOS, "Photo", none),
            PipelineNode("extract", NodeTypes.VISION_EXTRACT, "Facts from frames", liveBatch3()),
            PipelineNode("trust", NodeTypes.LOCAL_CRITIC, "Confidence check", none),
            PipelineNode("critic", NodeTypes.CRITIC, "Critic", livePrompt),
            PipelineNode("write", NodeTypes.MEMORY_WRITE, "Save memory", none),
        ),
        edges = listOf(
            PipelineEdge("photos", "extract"),
            PipelineEdge("extract", "trust"),
            PipelineEdge("trust", "write", "trusted"),
            PipelineEdge("trust", "critic", "unsure"),
            PipelineEdge("critic", "write", "pass"),
            PipelineEdge("critic", "write", "fail"),
        ),
    )

    private val intentTemplate =
        "Look at the photos and decide what the wearer is doing. Choose exactly one activity label from: " +
            INTENT_LABELS.joinToString(", ") + ". " +
            "Also give a confidence between 0 and 1, and a short reason of at most 120 characters. " +
            "Reply with JSON only: {\"activity\": <label>, \"confidence\": <number>, \"reason\": <text>}.\n\n{{recent_context}}"

    private val intentSchema = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("activity") { put("enum", JsonArray(INTENT_LABELS.map { JsonPrimitive(it) })) }
            putJsonObject("confidence") { put("type", "number"); put("minimum", 0); put("maximum", 1) }
            putJsonObject("reason") { put("type", "string"); put("maxLength", 120) }
        }
        put("required", JsonArray(listOf(JsonPrimitive("activity"), JsonPrimitive("confidence"))))
        put("additionalProperties", false)
    }.toString()

    fun intentDraft(seedExtractTemplate: String): Pipeline {
        val cases = INTENT_LABELS.take(5)
        val nodes = mutableListOf(
            PipelineNode("photos", NodeTypes.INPUT_PHOTOS, "Photo", none),
            PipelineNode("intent", NodeTypes.PROMPT, "Intent", buildJsonObject {
                put("role", "VISION_EXTRACT"); put("name", "intent"); put("images", true)
                put("template", intentTemplate); put("schema", intentSchema)
            }),
            PipelineNode("route", NodeTypes.SWITCH, "Route by activity", buildJsonObject {
                put("field", "activity"); put("cases", JsonArray(cases.map { JsonPrimitive(it) }))
            }),
        )
        val edges = mutableListOf(PipelineEdge("photos", "intent"), PipelineEdge("intent", "route"))
        for (l in cases) {
            nodes += PipelineNode("extract_$l", NodeTypes.VISION_EXTRACT, "Facts: $l", buildJsonObject {
                put("batch", 3)
                putJsonObject("prompt") { put("source", "draft"); put("template", seedExtractTemplate); put("schema", JsonPrimitive(null as String?)) }
            })
            edges += PipelineEdge("route", "extract_$l", l)
        }
        nodes += PipelineNode("extract_other", NodeTypes.VISION_EXTRACT, "Facts: other", liveBatch3())
        edges += PipelineEdge("route", "extract_other", NodeTypes.OTHER)
        nodes += PipelineNode("write", NodeTypes.MEMORY_WRITE, "Save memory", none)
        (cases + NodeTypes.OTHER).forEach { edges += PipelineEdge("extract_$it", "write") }
        return Pipeline(PipelineJson.SCHEMA, INTENT_DRAFT, "Intent, then extract", nodes, edges)
    }

    /**
     * The glasses-side capture the settings run today, as a pipeline: the identity plan's nodes at their defaults, wired as the identity
     * plan. It compiles to the identity plan (hash f5782e0d8610). Existing as a built-in activates nothing: only a publish activates a plan.
     */
    fun defaultCapture(): Pipeline {
        fun signal(name: String, domain: String) = buildJsonObject { put("name", name); put("domain", domain) }
        val nodes = listOf(
            PipelineNode("head", NodeTypes.IMU_HEAD_MOTION, "Head movement", none),
            PipelineNode("thresh", NodeTypes.GATE_THRESHOLD, "Movement threshold", buildJsonObject { put("feature", "gyroPeak"); put("op", ">"); put("value", 0.6) }),
            PipelineNode("settle", NodeTypes.IMU_SETTLE, "Settle", none),
            PipelineNode("novel", NodeTypes.GATE_NOVELTY, "Novelty", none),
            PipelineNode("rate", NodeTypes.POLICY_CAPTURE_RATE, "Capture rate", none),
            PipelineNode("cap", NodeTypes.GATE_RATE_CAP, "Rate cap", none),
            PipelineNode("cam", NodeTypes.CAPTURE_IMAGE, "Take photo", none),
            PipelineNode("cin", NodeTypes.SIGNAL_IN, "Context in", signal("ctx", "RT_GLASSES")),
            PipelineNode("hub", NodeTypes.HUB_CONTEXT, "Phone context", none),
            PipelineNode("cout", NodeTypes.SIGNAL_OUT, "Context out", signal("ctx", "RT_PHONE")),
        )
        val edges = listOf("head" to "thresh", "thresh" to "settle", "settle" to "novel", "novel" to "rate", "cin" to "rate", "rate" to "cap", "cap" to "cam", "hub" to "cout")
            .map { PipelineEdge(it.first, it.second) }
        return Pipeline(PipelineJson.SCHEMA_V2, DEFAULT_CAPTURE, "Default capture", nodes, edges)
    }

    /**
     * The starter: both halves of the generic flow in one pipeline, so one drawing shows the glasses lane (the Default capture nodes, wires and params untouched, so its
     * plan hash stays f5782e0d8610) and the phone flow (the live extraction), joined by the one binding that exists: the captured image is the Photo input.
     * Existing as a built-in activates nothing, and it is read-only like the others: clone it to edit.
     */
    fun starter(): Pipeline {
        val glasses = defaultCapture()
        val phone = liveExtract()
        return Pipeline(
            PipelineJson.SCHEMA_V2, STARTER, "Starter: capture and extract",
            glasses.nodes + phone.nodes, glasses.edges + phone.edges + PipelineEdge("cam", "photos"),
        )
    }

    private const val MOMENT_TEMPLATE = "Describe in at most three plain sentences what happened in this Moment, using only the data below. " +
        "Reply with JSON only: {\"description\": <text>}.\n\n{{context_sources}}"

    private val momentSchema = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") { putJsonObject("description") { put("type", "string"); put("maxLength", 600) } }
        put("required", JsonArray(listOf(JsonPrimitive("description"))))
        put("additionalProperties", false)
    }.toString()

    /**
     * The user's chain next to the glasses lane: the Default capture nodes and wires untouched (plan hash f5782e0d8610) and, on the phone, Moments -> Prompt -> Summary ->
     * Confidence check -> Save memory (preview). A Confidence check has two outcomes and both go to Save, which shows the review state of each row. The Moments input is tier 3
     * (never published), so this is a read-only picture and a run template: clone it, pick Moments on the New run page. It is a separate built-in because a pipeline has ONE input root,
     * so it cannot be added to `starter-capture-extract` (whose Photo input and wiring stay exactly as they were).
     */
    fun starterMomentSummary(): Pipeline {
        val glasses = defaultCapture()
        val nodes = listOf(
            PipelineNode("moments", NodeTypes.INPUT_MOMENTS, "Moments", none),
            PipelineNode("ask", NodeTypes.PROMPT, "Prompt", buildJsonObject { put("name", "moment"); put("template", MOMENT_TEMPLATE); put("schema", momentSchema) }),
            PipelineNode("sum", NodeTypes.SUMMARY, "Summary", none),
            PipelineNode("trust", NodeTypes.LOCAL_CRITIC, "Confidence check", none),
            PipelineNode("write", NodeTypes.MEMORY_WRITE, "Save memory", none),
        )
        val edges = listOf(
            PipelineEdge("moments", "ask"), PipelineEdge("ask", "sum"), PipelineEdge("sum", "trust"),
            PipelineEdge("trust", "write", "trusted"), PipelineEdge("trust", "write", "unsure"),
        )
        return Pipeline(PipelineJson.SCHEMA_V2, STARTER_MOMENT_SUMMARY, "Starter: moments to summary", glasses.nodes + nodes, glasses.edges + edges)
    }

    /**
     * The watch branch of the generic flow next to the glasses lane: `Motion on watch -> Window aggregate (max of the last 5 values) -> Motion model -> Set frequency (preview)`, and
     * the Default capture nodes and wires untouched (plan hash f5782e0d8610). Everything on the watch and phone side is a read-only picture and a run template: the watch values come from
     * the phone's context hub (run it over a time window), the last node only PREVIEWS the capture rate, and the whole graph can never publish (the watch lane and the preview Outcome
     * are tier 3), so the glasses keep the capture they have. Existing as a built-in activates nothing; clone it to edit.
     */
    fun starterWatchMotion(): Pipeline {
        val glasses = defaultCapture()
        val nodes = listOf(
            PipelineNode("watch", NodeTypes.SRC_WATCH_MOTION, "Motion on watch", none),
            PipelineNode("win", NodeTypes.AGG_WINDOW, "Max over the last 5 values", buildJsonObject { put("feature", "watchMotion"); put("op", "max"); put("window", 5) }),
            PipelineNode("model", NodeTypes.PROC_MOTION_MODEL, "Motion model", none),
            PipelineNode("freq", NodeTypes.OUT_SET_FREQUENCY_PREVIEW, "Set frequency (preview)", none),
        )
        val edges = listOf(PipelineEdge("watch", "win"), PipelineEdge("win", "model"), PipelineEdge("model", "freq"))
        return Pipeline(PipelineJson.SCHEMA_V2, STARTER_WATCH_MOTION, "Starter: watch motion to capture rate", glasses.nodes + nodes, glasses.edges + edges)
    }

    private fun flowPrompt(role: String) = buildJsonObject { put("role", role); putJsonObject("prompt") { put("source", "live") } }

    /** Ask as it is today: utterance, embed, the memory and photo stores with the user's retrieval settings, the live ANSWER prompt, the answer to the caller. Compiles to [FlowPlan.BUILT_IN_QUERY]. */
    fun defaultQuery(): Pipeline = Pipeline(
        PipelineJson.SCHEMA_V2, DEFAULT_QUERY, "Default Query (Ask)",
        nodes = listOf(
            PipelineNode("utterance", NodeTypes.INPUT_UTTERANCE, "Utterance", none),
            PipelineNode("embed", NodeTypes.EMBED_TEXT, "Embed text", none),
            PipelineNode("retrieve", NodeTypes.RAG_RETRIEVE, "Memories and photos", buildJsonObject { put("memories", true); put("photos", true) }),
            PipelineNode("answer", NodeTypes.PROMPT, "Answer", flowPrompt("ANSWER")),
            PipelineNode("deliver", NodeTypes.OUT_DELIVER, "Answer to the caller", buildJsonObject { put("target", "caller") }),
        ),
        edges = listOf(PipelineEdge("utterance", "embed"), PipelineEdge("embed", "retrieve"), PipelineEdge("retrieve", "answer"), PipelineEdge("answer", "deliver")),
    )

    /** Nudge as it is today: after a summarizer pass, the last 6 h of memories and the last 24 h of nudges, the live NUDGE prompt, the gates, shown by the verbosity setting. Compiles to [FlowPlan.BUILT_IN_NUDGE]. */
    fun defaultNudge(): Pipeline = Pipeline(
        PipelineJson.SCHEMA_V2, DEFAULT_NUDGE, "Default Nudge",
        nodes = listOf(
            PipelineNode("trigger", NodeTypes.TRIGGER_SUMMARIZER, "After summarizer pass", none),
            PipelineNode("retrieve", NodeTypes.RAG_RETRIEVE, "Recent memories and nudges", buildJsonObject {
                put("recent", true); put("history", true); put("recentWindowMin", 360); put("historyWindowMin", 1440)
            }),
            PipelineNode("nudge", NodeTypes.PROMPT, "Nudge", flowPrompt("NUDGE")),
            PipelineNode("gates", NodeTypes.GATE_NUDGE, "Nudge gates", none),
            PipelineNode("deliver", NodeTypes.OUT_DELIVER, "Show the nudge", buildJsonObject { put("target", "both") }),
        ),
        edges = listOf(PipelineEdge("trigger", "retrieve"), PipelineEdge("retrieve", "nudge"), PipelineEdge("nudge", "gates"), PipelineEdge("gates", "deliver")),
    )
}
