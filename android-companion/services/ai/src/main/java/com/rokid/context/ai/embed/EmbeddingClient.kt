package com.rokid.context.ai.embed

import com.rokid.context.ai.Endpoint
import com.rokid.context.ai.EndpointKind
import com.rokid.context.ai.Http
import com.rokid.context.ai.KeyTransport
import com.rokid.context.ai.ProviderConfigException
import com.rokid.context.ai.ProviderHttpException
import com.rokid.context.ai.ProviderResponseException
import com.rokid.context.model.provider.LlamaCppNativeProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** spec §4.1 embedding support per kind: Azure/OpenAI-compatible `/embeddings`, Ollama `/api/embed`, llama.cpp native. */
class EmbeddingClient(private val http: OkHttpClient = Http.modelClient(60)) {
    suspend fun embed(e: Endpoint, model: String, key: String?, inputs: List<String>, dimensions: Int? = null): List<FloatArray> {
        if (inputs.isEmpty()) return emptyList()
        if (e.kind == EndpointKind.LLAMACPP_LOCAL) return validated(nativeEmbed(e.baseUrl, inputs), inputs.size)
        val base = e.baseUrl.trimEnd('/')
        val (url, body) = when (e.kind) {
            EndpointKind.OLLAMA -> "$base/api/embed" to buildJsonObject { put("model", JsonPrimitive(model)); put("input", strings(inputs)) }
            EndpointKind.AZURE_OPENAI -> {
                if (key.isNullOrBlank()) throw ProviderConfigException("API key is missing")
                "$base/openai/deployments/${java.net.URLEncoder.encode(model, "UTF-8").replace("+", "%20")}/embeddings?api-version=${e.apiVersion ?: "2024-10-21"}" to
                    buildJsonObject { put("input", strings(inputs)); dimensions?.let { put("dimensions", JsonPrimitive(it)) } }
            }
            else -> "$base/embeddings" to buildJsonObject {
                put("model", JsonPrimitive(model)); put("input", strings(inputs)); dimensions?.let { put("dimensions", JsonPrimitive(it)) }
            }
        }
        val sent = key.takeIf { e.kind == EndpointKind.AZURE_OPENAI || e.kind == EndpointKind.OPENAI_COMPAT }
        KeyTransport.requireSecure(url, sent)
        val request = Request.Builder().url(url).post(body.toString().toRequestBody("application/json".toMediaType())).apply {
            when (e.kind) {
                EndpointKind.AZURE_OPENAI -> addHeader("api-key", sent!!)
                EndpointKind.OPENAI_COMPAT -> if (!sent.isNullOrBlank()) addHeader("Authorization", "Bearer $sent")
                else -> Unit
            }
        }.build()
        val text = withContext(Dispatchers.IO) {
            http.newCall(request).execute().use { r ->
                val t = r.body?.string().orEmpty()
                if (!r.isSuccessful) throw ProviderHttpException(r.code, "Embedding", t)
                t
            }
        }
        val json = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrElse { throw ProviderResponseException("Embedding reply wasn't JSON") }
        val raw: List<JsonArray> = try {
            (json["embeddings"] as? JsonArray)?.map { it as? JsonArray ?: throw ProviderResponseException("Embedding wasn't a list of numbers") }
                ?: (json["data"] as? JsonArray)?.let { data ->
                    val rows = data.map { it as? JsonObject ?: throw ProviderResponseException("Malformed embedding entry") }
                    val indexes = rows.map { (it["index"] as? JsonPrimitive)?.intOrNull ?: -1 }
                    // OpenAI-style replies carry an index; order by it, and refuse duplicates or gaps.
                    if (indexes.any { it < 0 } || indexes.toSet() != rows.indices.toSet()) throw ProviderResponseException("Embedding indexes don't match the inputs")
                    rows.sortedBy { (it["index"] as JsonPrimitive).intOrNull }.map { it["embedding"] as? JsonArray ?: throw ProviderResponseException("Embedding wasn't a list of numbers") }
                }
                ?: throw ProviderResponseException("No embedding in response")
        } catch (t: ProviderResponseException) { throw t } catch (_: RuntimeException) { throw ProviderResponseException("Malformed embedding response") }
        val vectors = raw.map { arr ->
            FloatArray(arr.size) { i ->
                val p = arr[i] as? JsonPrimitive
                if (p == null || p is JsonNull || p.isString) throw ProviderResponseException("Embedding contained a non-number")
                p.content.toFloatOrNull() ?: throw ProviderResponseException("Embedding contained a non-number")
            }
        }
        return validated(vectors, inputs.size)
    }

    /** The native call can't be interrupted, but the caller stops waiting after the same 60 s budget as HTTP. */
    private suspend fun nativeEmbed(path: String, inputs: List<String>): List<FloatArray> = try {
        withTimeout(NATIVE_TIMEOUT_MS) { coroutineScope { async(Dispatchers.Default) { LlamaCppNativeProvider.embed(path, inputs) }.await() } }
    } catch (_: TimeoutCancellationException) { throw java.net.SocketTimeoutException("Local embedding timed out") }

    /** A reply can never yield bad vectors: right count, one non-zero dimension, every value finite. */
    private fun validated(vectors: List<FloatArray>, expected: Int): List<FloatArray> {
        if (vectors.size != expected) throw ProviderResponseException("Expected $expected embeddings, got ${vectors.size}")
        val dim = vectors.first().size
        if (dim == 0) throw ProviderResponseException("Embedding was empty")
        if (vectors.any { it.size != dim }) throw ProviderResponseException("Embeddings differ in size")
        if (vectors.any { v -> v.any { !it.isFinite() } }) throw ProviderResponseException("Embedding contained a non-finite value")
        return vectors
    }

    /** Model revision when the backend exposes one (Ollama digest); null otherwise. */
    suspend fun revision(e: Endpoint, model: String): String? {
        if (e.kind != EndpointKind.OLLAMA) return null
        val request = Request.Builder().url("${e.baseUrl.trimEnd('/')}/api/tags").get().build()
        val text = withContext(Dispatchers.IO) {
            http.newCall(request).execute().use { r ->
                val t = r.body?.string().orEmpty()
                if (!r.isSuccessful) throw ProviderHttpException(r.code, "Embedding", t)
                t
            }
        }
        val models = runCatching { Json.parseToJsonElement(text).jsonObject["models"] as? JsonArray }.getOrNull()
            ?: throw ProviderResponseException("Model list wasn't understood")
        return models.map { it.jsonObject }.firstOrNull { (it["name"] as? JsonPrimitive)?.content in setOf(model, "$model:latest") }
            ?.let { (it["digest"] as? JsonPrimitive)?.content }
    }

    private fun strings(xs: List<String>) = JsonArray(xs.map { JsonPrimitive(it) })

    private companion object { const val NATIVE_TIMEOUT_MS = 60_000L }
}
