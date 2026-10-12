package com.rokid.context.ai.embed

import com.rokid.context.ai.EndpointKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import java.security.MessageDigest

/**
 * Everything that decides which vector a text gets (spec §4.6 "embedding space identity"). [baseUrl] is already
 * normalised by UrlNormalizer (scheme + host lowercased, default port dropped, path case kept, no trailing slash).
 */
data class EmbeddingSpec(
    val endpointId: String,
    val kind: EndpointKind,
    val baseUrl: String,
    val apiVersion: String?,
    val model: String,
    val revision: String?,
    val dimensions: Int?,
    val dim: Int,
    val l2: Boolean,
    val docPrefix: String,
    val queryPrefix: String,
    val maxInputChars: Int,
) {
    /** Canonical JSON, keys sorted, no endpoint id, display name or key. */
    fun identityJson(): String = JsonObject(sortedMapOf<String, JsonElement>(
        "apiVersion" to (apiVersion?.let { JsonPrimitive(it) } ?: JsonNull), "baseUrl" to JsonPrimitive(baseUrl), "dim" to JsonPrimitive(dim),
        "dimensions" to (dimensions?.let { JsonPrimitive(it) } ?: JsonNull), "docPrefix" to JsonPrimitive(docPrefix), "kind" to JsonPrimitive(kind.name),
        "l2" to JsonPrimitive(l2), "maxInputChars" to JsonPrimitive(maxInputChars), "model" to JsonPrimitive(model),
        "queryPrefix" to JsonPrimitive(queryPrefix), "revision" to (revision?.let { JsonPrimitive(it) } ?: JsonNull),
    )).toString()

    val fingerprint: String get() = MessageDigest.getInstance("SHA-256").digest(identityJson().toByteArray()).joinToString("") { "%02x".format(it) }

    /** Stored in `embedding_space.configJson`: identity + the endpoint id to route through. */
    fun configJson(): String = JsonObject(Json.parseToJsonElement(identityJson()).jsonObject + ("endpointId" to JsonPrimitive(endpointId))).toString()

    companion object {
        fun fromConfig(json: String): EmbeddingSpec? = runCatching {
            val o = Json.parseToJsonElement(json).jsonObject
            fun s(k: String) = (o[k] as? JsonPrimitive)?.takeUnless { it == JsonNull }?.content
            EmbeddingSpec(
                endpointId = s("endpointId")!!, kind = EndpointKind.valueOf(s("kind")!!), baseUrl = s("baseUrl")!!, apiVersion = s("apiVersion"),
                model = s("model")!!, revision = s("revision"), dimensions = (o["dimensions"] as? JsonPrimitive)?.intOrNull,
                dim = (o["dim"] as JsonPrimitive).intOrNull!!, l2 = (o["l2"] as JsonPrimitive).booleanOrNull!!,
                docPrefix = s("docPrefix").orEmpty(), queryPrefix = s("queryPrefix").orEmpty(), maxInputChars = (o["maxInputChars"] as JsonPrimitive).intOrNull!!,
            )
        }.getOrNull()
    }
}

/** Document/query prefixes for embedding families that need them (spec §4.6 "input prefixes"). */
object PrefixRules {
    private val E5 = Regex("(^|[-_/:])e5([-_:.]|$)")

    fun forModel(model: String): Pair<String, String> {
        val m = model.lowercase()
        return when {
            "nomic-embed" in m -> "search_document: " to "search_query: "
            E5.containsMatchIn(m) -> "passage: " to "query: "
            else -> "" to ""
        }
    }
}
