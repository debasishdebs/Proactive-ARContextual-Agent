package com.rokid.context.ai.canvas

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

data class Pipeline(val schema: Int, val id: String, val name: String, val nodes: List<PipelineNode>, val edges: List<PipelineEdge>)

data class PipelineNode(val id: String, val type: String, val title: String, val params: JsonObject)

data class PipelineEdge(val from: String, val to: String, val `when`: String? = null)

sealed interface PromptSource {
    data object Live : PromptSource
    data class Draft(val template: String, val schema: String?) : PromptSource
}

/** The node's `prompt` param, or null when it has none (or it is malformed; the validator reports that). */
fun PipelineNode.promptSource(): PromptSource? {
    val p = runCatching { params["prompt"]?.jsonObject }.getOrNull() ?: return null
    return when ((p["source"] as? JsonPrimitive)?.contentOrNull) {
        "live" -> PromptSource.Live
        "draft" -> {
            val template = (p["template"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
            PromptSource.Draft(template, (p["schema"] as? JsonPrimitive)?.takeIf { it.isString }?.content)
        }
        else -> null
    }
}
