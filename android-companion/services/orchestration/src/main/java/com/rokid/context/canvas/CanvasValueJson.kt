package com.rokid.context.canvas

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The stored form of a [CanvasValue]: a fixed key order, so the same value always encodes to the same text. */
object CanvasValueJson {
    fun encode(v: CanvasValue): String = toJson(v).toString()

    fun branchOf(r: NodeResult): String? = r.branch

    internal fun toJson(v: CanvasValue): JsonObject = when (v) {
        CanvasValue.Photo -> buildJsonObject { put("kind", "photo") }
        is CanvasValue.Extraction -> buildJsonObject {
            put("kind", "extraction")
            put("facts", JsonArray(v.facts.map { f ->
                buildJsonObject {
                    put("text", f.text); put("type", f.type); put("confidence", f.confidence)
                    put("source_frame_ids", JsonArray(f.sourceFrameIds.map { JsonPrimitive(it) }))
                }
            }))
            put("captions", JsonArray(v.captions.map { c -> buildJsonObject { put("frame_id", c.frameId); put("caption", c.caption) } }))
            put("trusted", v.trusted?.let { JsonPrimitive(it) } ?: JsonNull)
            put("note", v.note?.let { JsonPrimitive(it) } ?: JsonNull)
        }
        is CanvasValue.Json -> buildJsonObject { put("kind", "json"); put("value", v.value) }
        is CanvasValue.Preview -> buildJsonObject {
            put("kind", "preview")
            put("rows", JsonArray(v.rows.map { r ->
                buildJsonObject { put("kind", r.kind); put("text", r.text); put("type", r.type); put("tier", r.tier); put("review", r.review) }
            }))
        }
    }
}
