package com.rokid.context.data.runtime

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A queued job whose inputs changed under it (a forgotten source, an old text payload). Never retried. */
open class StalePayloadException : IllegalStateException("stale payload")

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

    /**
     * PRIORITY_REPROCESS v2: ids only, never memory or frame text (spec §6.1.1, R-77). The drain re-reads the memories
     * when it runs and drops forgotten (missing or INVALIDATED) ones and tombstoned frames, so text forgotten after the
     * enqueue never reaches a model. [nudge] false (a describe on demand): the drain does not wake the nudge engine after the re-summary.
     */
    fun reprocess(reason: String, sourceIds: List<String>, frameIds: List<String> = emptyList(), nudge: Boolean = true): String {
        return buildJsonObject {
            put("kind", JsonPrimitive(KIND_REPROCESS))
            put("v", JsonPrimitive(2))
            put("reason", JsonPrimitive(reason))
            put("source_ids_csv", JsonPrimitive(csv(sourceIds)))
            put("frame_ids_csv", JsonPrimitive(csv(frameIds)))
            put("manual", JsonPrimitive(false))
            if (!nudge) put("nudge", JsonPrimitive(false)) // additive: absent (an older build's job) = true, the drain wakes the nudge engine after it
        }.toString()
    }

    /** 👎 review (Phase 7): CRITIC over the cited memories, ids only. [dedupeKey] = the ask id (one job per ask). */
    fun criticReview(memoryIds: List<String>, dedupeKey: String = ""): String {
        return buildJsonObject {
            put("kind", JsonPrimitive(KIND_CRITIC_REVIEW))
            put("memory_ids_csv", JsonPrimitive(csv(memoryIds)))
            if (dedupeKey.isNotEmpty()) put("dedupe", JsonPrimitive(dedupeKey))
            put("manual", JsonPrimitive(false))
        }.toString()
    }

    /** The ids of a `*_csv` field; blanks dropped, order kept, duplicates removed. */
    fun ids(obj: JsonObject, key: String): List<String> = string(obj, key).split(",").map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    private fun csv(ids: List<String>) = ids.map { it.trim() }.filter { it.isNotEmpty() && "," !in it }.distinct().joinToString(",")

    const val KIND_REPROCESS = "priority_reprocess"
    const val KIND_CRITIC_REVIEW = "critic_review"

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
