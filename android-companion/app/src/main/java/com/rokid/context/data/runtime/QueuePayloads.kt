package com.rokid.context.data.runtime

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object QueuePayloads {
    private val json = Json { ignoreUnknownKeys = true }

    fun explicitWrite(rawInput: String, inputLanguage: String, manual: Boolean): String {
        return buildJsonObject {
            put("kind", JsonPrimitive("write_explicit"))
            put("raw_input", JsonPrimitive(rawInput))
            put("input_language", JsonPrimitive(inputLanguage))
            put("manual", JsonPrimitive(manual))
        }.toString()
    }

    fun batchedWrite(
        source: String,
        importedFrames: Int,
        manual: Boolean,
        frameIdsCsv: String = "",
    ): String {
        return buildJsonObject {
            put("kind", JsonPrimitive("write_batched"))
            put("source", JsonPrimitive(source))
            put("imported_frames", JsonPrimitive(importedFrames))
            put("manual", JsonPrimitive(manual))
            if (frameIdsCsv.isNotBlank()) {
                put("frame_ids_csv", JsonPrimitive(frameIdsCsv))
            }
        }.toString()
    }

    fun readContext(query: String, inputLanguage: String, manual: Boolean): String {
        return buildJsonObject {
            put("kind", JsonPrimitive("read_context"))
            put("query", JsonPrimitive(query))
            put("input_language", JsonPrimitive(inputLanguage))
            put("manual", JsonPrimitive(manual))
        }.toString()
    }

    fun reprocess(reason: String, sourceContext: String): String {
        return buildJsonObject {
            put("kind", JsonPrimitive("priority_reprocess"))
            put("reason", JsonPrimitive(reason))
            put("source_context", JsonPrimitive(sourceContext))
            put("manual", JsonPrimitive(false))
        }.toString()
    }

    fun frameIngest(source: String): String {
        return buildJsonObject {
            put("kind", JsonPrimitive("frame_ingest"))
            put("source", JsonPrimitive(source))
            put("manual", JsonPrimitive(false))
        }.toString()
    }

    fun parse(payload: String): JsonObject {
        return runCatching {
            json.parseToJsonElement(payload).jsonObject
        }.getOrElse { buildJsonObject { } }
    }

    fun string(obj: JsonObject, key: String, default: String = ""): String {
        return obj[key]?.jsonPrimitive?.content ?: default
    }

    fun bool(obj: JsonObject, key: String, default: Boolean = false): Boolean {
        val raw = runCatching { obj[key]?.jsonPrimitive?.content }.getOrNull() ?: return default
        return when (raw.lowercase()) {
            "true" -> true
            "false" -> false
            else -> default
        }
    }

    fun int(obj: JsonObject, key: String, default: Int = 0): Int {
        val raw = runCatching { obj[key]?.jsonPrimitive?.content }.getOrNull() ?: return default
        return raw.toIntOrNull() ?: default
    }
}
