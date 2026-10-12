package com.rokid.context.ai.embed

import com.rokid.context.memory.retrieval.VectorCodec
import com.rokid.context.memory.retrieval.VectorMath
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64

/** spec §4.6 canary check: three fixed texts (English, Hindi, mixed) re-embedded to catch a model silently changed behind a name. */
object Canaries {
    val TEXTS = listOf("Milk carton nearly empty in the fridge", "दरवाज़े के पास चाबियाँ रखी हैं", "Meeting with Priya at 3 pm कल")
    const val MIN_COSINE = 0.995

    fun encode(vectors: List<FloatArray>): String = JsonArray(vectors.map { JsonPrimitive(Base64.getEncoder().encodeToString(VectorCodec.encode(it))) }).toString()

    fun decode(json: String): List<FloatArray> = runCatching {
        Json.parseToJsonElement(json).jsonArray.map { VectorCodec.decode(Base64.getDecoder().decode(it.jsonPrimitive.content)) }
    }.getOrDefault(emptyList())

    fun stale(stored: List<FloatArray>, fresh: List<FloatArray>): Boolean =
        stored.isEmpty() || stored.size != fresh.size || stored.zip(fresh).any { (a, b) -> a.size != b.size || VectorMath.cosine(a, b) < MIN_COSINE }
}
