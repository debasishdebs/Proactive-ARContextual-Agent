package com.rokid.context.ai.embed

import com.rokid.context.ai.DataSaverBlockedException
import com.rokid.context.ai.Endpoint
import com.rokid.context.core.net.DataDecision
import com.rokid.context.core.net.DataKind
import com.rokid.context.core.net.DataPolicy
import com.rokid.context.core.net.EndpointReach
import com.rokid.context.core.net.Skipped
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** spec §4.1 embedding support per kind: Azure/OpenAI-compatible `/embeddings`, Ollama `/api/embed`, llama.cpp native. */
class EmbeddingClient(
    private val http: OkHttpClient = Http.modelClient(60),
    private val nativeTimeoutMs: Long = 60_000,
    private val native: (String, List<String>, Boolean) -> List<FloatArray> = { path, texts, normalize -> LlamaCppNativeProvider.embed(path, texts, normalize) },
    /** Model privacy, read at every call: under OFFLINE_ONLY a REMOTE endpoint is refused before anything is sent (the on-device model and the user's own network still work). A reader that throws counts as OFFLINE_ONLY. */
    private val privacy: () -> com.rokid.context.core.net.ModelPrivacy = { com.rokid.context.core.net.ModelPrivacy.AUTO },
    /**
     * The roaming data saver (Codex data-saver r1 P1-2): every remote batch is admitted by its ACTUAL endpoint reach and traffic kind (one or two short texts = SMALL, a batch = BULK_OTHER) and its request and
     * response bytes are counted. On-device and loopback endpoints are exempt, like everywhere else. Allows everything by default (tests, tools).
     */
    private val dataGate: com.rokid.context.core.net.DataGate = com.rokid.context.core.net.DataGate.ALLOW_ALL,
) {
    /** Owns native work so a timed-out, uninterruptible call keeps running here without blocking or overlapping anyone. */
    private val nativeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val nativeLock = Mutex()

    suspend fun embed(e: Endpoint, model: String, key: String?, inputs: List<String>, dimensions: Int? = null, normalize: Boolean = true): List<FloatArray> {
        if (inputs.isEmpty()) return emptyList()
        com.rokid.context.ai.OfflineOnlyGuard.require(privacy, e)
        if (e.kind == EndpointKind.LLAMACPP_LOCAL) return validated(nativeEmbed(e.baseUrl, inputs, normalize), inputs.size)
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
        val kind = if (inputs.size <= SMALL_MAX_TEXTS && inputs.sumOf { it.length } <= SMALL_MAX_CHARS) DataKind.SMALL else DataKind.BULK_OTHER
        val counted = reach(e) != EndpointReach.DEVICE && !com.rokid.context.ai.EndpointReaches.isLoopback(e)
        admit(e, kind)
        val sent = key.takeIf { e.kind == EndpointKind.AZURE_OPENAI || e.kind == EndpointKind.OPENAI_COMPAT }
        KeyTransport.requireSecure(url, sent)
        val request = Request.Builder().url(url).post(body.toString().toRequestBody("application/json".toMediaType())).apply {
            when (e.kind) {
                EndpointKind.AZURE_OPENAI -> addHeader("api-key", sent!!)
                EndpointKind.OPENAI_COMPAT -> if (!sent.isNullOrBlank()) addHeader("Authorization", "Bearer $sent")
                else -> Unit
            }
        }.build()
        val txBytes = (request.body?.contentLength() ?: 0L).coerceAtLeast(0)
        val costlyAtStart = counted && safeCostly()
        var rxBytes = 0L
        var count = true
        val text = try {
            withContext(Dispatchers.IO) {
                http.newCall(request).execute().use { r ->
                    val t = r.body?.string().orEmpty()
                    rxBytes = t.toByteArray(Charsets.UTF_8).size.toLong()
                    if (!r.isSuccessful) throw ProviderHttpException(r.code, "Embedding", t)
                    t
                }
            }
        } catch (t: Throwable) {
            if (com.rokid.context.ai.TrafficClass.provablyUnsent(t)) count = false // a stall, timeout or cancel keeps the request counted
            throw t
        } finally {
            if (count && counted) try { dataGate.sentAt(kind, txBytes, rxBytes, 0, costlyAtStart) } catch (c: kotlin.coroutines.cancellation.CancellationException) { throw c } catch (_: Throwable) { }
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

    /**
     * [NATIVE_BATCH] texts per native call, each call timed on its own, so a 64-text reindex batch can't overrun the
     * limit by size alone and a query waits behind at most one small call (final review I1).
     */
    private suspend fun nativeEmbed(path: String, inputs: List<String>, normalize: Boolean): List<FloatArray> =
        inputs.chunked(NATIVE_BATCH).flatMap { nativeCall(path, it, normalize) }

    /** The caller stops waiting after [nativeTimeoutMs]; native calls run one at a time, and a call still queued when the caller gives up is cancelled and never runs. */
    private suspend fun nativeCall(path: String, inputs: List<String>, normalize: Boolean): List<FloatArray> {
        val work = nativeScope.async { nativeLock.withLock { native(path, inputs, normalize) } }
        return try { withTimeout(nativeTimeoutMs) { work.await() } }
        catch (_: TimeoutCancellationException) { throw java.net.SocketTimeoutException("Local embedding timed out") }
        finally { work.cancel() } // timeout or caller cancellation: a call still queued never runs
    }

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
        com.rokid.context.ai.OfflineOnlyGuard.require(privacy, e)
        admit(e, DataKind.SMALL)
        val counted = reach(e) != EndpointReach.DEVICE && !com.rokid.context.ai.EndpointReaches.isLoopback(e)
        val costlyAtStart = counted && safeCostly()
        val request = Request.Builder().url("${e.baseUrl.trimEnd('/')}/api/tags").get().build()
        val text = withContext(Dispatchers.IO) {
            http.newCall(request).execute().use { r ->
                val t = r.body?.string().orEmpty()
                if (counted) try { dataGate.sentAt(DataKind.SMALL, 300, t.toByteArray(Charsets.UTF_8).size.toLong(), 0, costlyAtStart) } catch (_: Exception) { }
                if (r.code == 404) return@use null // an older server without /api/tags: no revision to report
                if (!r.isSuccessful) throw ProviderHttpException(r.code, "Embedding", t)
                t
            }
        } ?: return null
        val models = runCatching { Json.parseToJsonElement(text).jsonObject["models"] as? JsonArray }.getOrNull()
            ?: throw ProviderResponseException("Model list wasn't understood")
        return models.map { it.jsonObject }.firstOrNull { (it["name"] as? JsonPrimitive)?.content in setOf(model, "$model:latest") }
            ?.let { (it["digest"] as? JsonPrimitive)?.content }
    }

    private fun reach(e: Endpoint): EndpointReach = com.rokid.context.ai.EndpointReaches.of(e)
    private fun safeCostly(): Boolean = try { dataGate.costlyNow() } catch (_: Exception) { true }

    /** The data saver's decision for this endpoint and kind; a refusal is counted once and thrown as the same exception the router uses (retryable, nothing was sent). A gate that throws refuses (fail closed). */
    private fun admit(e: Endpoint, kind: DataKind) {
        val d = try { dataGate.decide(kind, reach(e)) } catch (c: kotlin.coroutines.cancellation.CancellationException) { throw c } catch (_: Exception) { DataDecision(false, DataPolicy.R_UNKNOWN) }
        if (d.allowed) return
        try { dataGate.skipped(kind, Skipped.BULK, d) } catch (_: Exception) { }
        throw DataSaverBlockedException(d.reason, kind, Skipped.BULK).also { it.counted = true }
    }

    private fun strings(xs: List<String>) = JsonArray(xs.map { JsonPrimitive(it) })

    companion object {
        /** Texts per on-device call: 8 of up to 2,000 chars (about 512 tokens each) on bge-small stay well inside 60 s. */
        const val NATIVE_BATCH = 8
        /** A query, a canary or an Endpoint Test: at most this many short texts is SMALL traffic; anything bigger is a BULK_OTHER batch. */
        const val SMALL_MAX_TEXTS = 2
        const val SMALL_MAX_CHARS = 4_000
    }
}
