package com.rokid.context.ai.canvas

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** The built-in pipelines, seeded read-only at first open. Both pass [PipelineValidator]. */
object DefaultPipelines {
    const val LIVE_EXTRACT = "live-extract"
    const val INTENT_DRAFT = "intent-draft"
    val INTENT_LABELS = listOf("sitting_idle", "workout", "computer_work", "driving", "walking", "other")
    val all: List<String> = listOf(LIVE_EXTRACT, INTENT_DRAFT)

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
}
