package com.rokid.context.ai.canvas

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * `proc.summary` (Process family): one short summary per input item, with the model's own confidence in it. The text is FIXED (the node has no params), it goes through
 * the same text path as `ai.prompt` (the user's SUMMARIZE binding), and the item is data, never instructions. The output port is SUMMARY; at run time the value is an
 * Extraction holding one fact of type [FACT_TYPE], which is what the Confidence check and Save memory (preview) already read, so neither executor changed.
 */
object SummaryNode {
    const val FACT_TYPE = "summary"
    const val MAX_LEN = 400

    /** The one placeholder is `{{context_sources}}`: the upstream item inside a data block. */
    const val TEMPLATE = "Summarise the data below in at most two short plain sentences, using only what it says. " +
        "Also give a confidence between 0 and 1 for how well the data supports your summary (low when it is empty or unclear). " +
        "Reply with JSON only: {\"summary\": <text>, \"confidence\": <number>}.\n\n{{context_sources}}"

    val schema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("summary") { put("type", "string"); put("minLength", 1); put("maxLength", MAX_LEN) }
            putJsonObject("confidence") { put("type", "number"); put("minimum", 0); put("maximum", 1) }
        }
        put("required", JsonArray(listOf(JsonPrimitive("summary"), JsonPrimitive("confidence"))))
        put("additionalProperties", false)
    }
}
