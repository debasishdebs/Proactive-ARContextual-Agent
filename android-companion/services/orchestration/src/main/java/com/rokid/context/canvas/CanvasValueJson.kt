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

    /** The [CanvasValue.Extraction] of a stored `kind: "extraction"` value, or null for any other kind or text that does not decode (read back by a canvas Save). */
    fun decodeExtraction(text: String?): CanvasValue.Extraction? = runCatching {
        val o = kotlinx.serialization.json.Json.parseToJsonElement(text ?: return null) as? JsonObject ?: return null
        if ((o["kind"] as? JsonPrimitive)?.content != "extraction") return null
        fun str(e: kotlinx.serialization.json.JsonElement?) = (e as? JsonPrimitive)?.takeIf { it.isString }?.content
        CanvasValue.Extraction(
            ((o["facts"] as? JsonArray) ?: JsonArray(emptyList())).mapNotNull { e ->
                val f = e as? JsonObject ?: return@mapNotNull null
                com.rokid.context.ai.contract.Fact(
                    str(f["text"]) ?: return@mapNotNull null, str(f["type"]).orEmpty(), (f["confidence"] as? JsonPrimitive)?.content?.toDoubleOrNull() ?: 0.0, emptyList(),
                    ((f["source_frame_ids"] as? JsonArray) ?: JsonArray(emptyList())).mapNotNull { str(it) },
                )
            },
            ((o["captions"] as? JsonArray) ?: JsonArray(emptyList())).mapNotNull { e ->
                val c = e as? JsonObject ?: return@mapNotNull null
                com.rokid.context.ai.contract.FrameCaption(str(c["frame_id"]) ?: return@mapNotNull null, str(c["caption"]) ?: return@mapNotNull null)
            },
            (o["trusted"] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toBooleanStrictOrNull(),
            str(o["note"]),
        )
    }.getOrNull()

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
