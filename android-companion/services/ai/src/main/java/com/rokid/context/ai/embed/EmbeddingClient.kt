package com.rokid.context.ai.embed

import com.rokid.context.ai.DataSaverBlockedException
import com.rokid.context.ai.Endpoint
import com.rokid.context.core.net.DataDecision
import com.rokid.context.core.net.DataKind
import com.rokid.context.core.net.DataReservation
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
    /** How often a running remote batch re-asks the data saver about the current network (tests shorten it). */
    private val watchMs: Long = 1_000L,
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
        val admitted = admit(e, kind)
        val sent = key.takeIf { e.kind == EndpointKind.AZURE_OPENAI || e.kind == EndpointKind.OPENAI_COMPAT }
        KeyTransport.requireSecure(url, sent)
        val request = Request.Builder().url(url).post(body.toString().toRequestBody("application/json".toMediaType())).apply {
            when (e.kind) {
                EndpointKind.AZURE_OPENAI -> addHeader("api-key", sent!!)
                EndpointKind.OPENAI_COMPAT -> if (!sent.isNullOrBlank()) addHeader("Authorization", "Bearer $sent")
                else -> Unit
            }
        }.build()
        val reply = roundTrip(e, kind, request, counted, admitted.reason == DataPolicy.R_LAN)
        if (!reply.ok) throw ProviderHttpException(reply.code, "Embedding", reply.text)
        val text = reply.text
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
        val admitted = admit(e, DataKind.SMALL)
        val counted = reach(e) != EndpointReach.DEVICE && !com.rokid.context.ai.EndpointReaches.isLoopback(e)
        val request = Request.Builder().url("${e.baseUrl.trimEnd('/')}/api/tags").get().build()
        val reply = roundTrip(e, DataKind.SMALL, request, counted, admitted.reason == DataPolicy.R_LAN)
        if (reply.code == 404) return null // an older server without /api/tags: no revision to report
        if (!reply.ok) throw ProviderHttpException(reply.code, "Embedding", reply.text)
        val text = reply.text
        val models = runCatching { Json.parseToJsonElement(text).jsonObject["models"] as? JsonArray }.getOrNull()
            ?: throw ProviderResponseException("Model list wasn't understood")
        return models.map { it.jsonObject }.firstOrNull { (it["name"] as? JsonPrimitive)?.content in setOf(model, "$model:latest") }
            ?.let { (it["digest"] as? JsonPrimitive)?.content }
    }

    private class Reply(val code: Int, val ok: Boolean, val text: String)

    /**
     * Codex data-saver r2 P1-6: the ONE remote round trip of the embedding client, on the same terms as a model call. While the network is costly a durable RESERVATION is taken before a byte leaves
     * (a commit that fails, or an untrusted counter, refuses the send), the transport asks the data saver again right before the connect (the reservation excluded), the call runs on the cancellable transport
     * ([executeFenced]: cancelling the coroutine aborts the socket) and a watcher re-asks every second so a network that turns costly under a running batch aborts it. Bytes are settled in `finally`.
     */
    private suspend fun roundTrip(e: Endpoint, kind: DataKind, request: Request, counted: Boolean, lanFree: Boolean): Reply {
        val tx = (request.body?.contentLength() ?: 0L).coerceAtLeast(0)
        // Codex r3 P1-2: a call the policy allowed for FREE (LAN on Wi-Fi or Ethernet, [DataPolicy.R_LAN]) takes no reservation and counts nothing: unreadable counters or a failed commit never block it.
        val costlyAtStart = counted && !lanFree && safeCostly()
        // Codex r3 P1-1: costly at ANY sample (admission, pre-connect guard, watcher ticks, settle) counts the bytes as costly, so a FREE admission that ends up on a costly network is not recorded as zero.
        val sawCostly = java.util.concurrent.atomic.AtomicBoolean(costlyAtStart)
        val lanNow = java.util.concurrent.atomic.AtomicBoolean(lanFree)
        val sample: (DataDecision?) -> Unit = { d ->
            if (d != null) lanNow.set(d.reason == DataPolicy.R_LAN) // null = reuse the latest decision (the settlement asks nothing new)
            if (counted && !lanNow.get() && safeCostly()) sawCostly.set(true)
        }
        // Codex r3 P1-3: the durable commit runs on an IO thread, never on the caller's.
        val reservation = if (costlyAtStart) com.rokid.context.ai.datasaver.reserveOwned(dataGate, kind, tx + com.rokid.context.ai.DataBytes.ENVELOPE, 0) else null // Codex r4 P2-4: a cancellation during the commit releases the token
        if (reservation?.failed == true) throw DataSaverBlockedException(DataPolicy.R_UNREADABLE, kind, Skipped.BULK).also { it.counted = false }
        var rx = 0L
        var unsent = false
        val guard = com.rokid.context.model.provider.TransportGuard {
            val d = decideNow(e, kind, reservation)
            sample(d)
            if (!d.allowed) throw com.rokid.context.ai.PreConnectBlockedException(d.reason, kind, Skipped.BULK)
        }
        try {
            return if (counted) watching(e, kind, reservation, sample) { transport(request, guard) { rx = it } } else transport(request, null) { rx = it }
        } catch (t: Throwable) {
            unsent = t is com.rokid.context.ai.PreConnectBlockedException || com.rokid.context.ai.TrafficClass.provablyUnsent(t) // a stall, timeout, cancel or abort keeps the request counted
            throw t
        } finally {
            val sentTx = if (unsent) 0L else tx
            val sentRx = if (unsent) 0L else rx
            try {
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                    sample(null) // the last sample, at settlement
                    if (reservation != null) dataGate.settle(reservation, sentTx, sentRx, 0)
                    else if (counted && !unsent) dataGate.sentAt(kind, sentTx, sentRx, 0, sawCostly.get())
                }
            } catch (c: kotlin.coroutines.cancellation.CancellationException) { throw c } catch (_: Throwable) { }
        }
    }

    private suspend fun transport(request: Request, guard: com.rokid.context.model.provider.TransportGuard?, rxOut: (Long) -> Unit): Reply =
        withContext(if (guard != null) Dispatchers.IO + guard else Dispatchers.IO) {
            com.rokid.context.model.provider.executeFenced(http, request).use { r ->
                val t = r.body?.string().orEmpty()
                rxOut(t.toByteArray(Charsets.UTF_8).size.toLong())
                Reply(r.code, r.isSuccessful, t)
            }
        }

    private fun decideNow(e: Endpoint, kind: DataKind, own: DataReservation?): DataDecision =
        try { dataGate.decideExcluding(kind, reach(e), own) } catch (c: kotlin.coroutines.cancellation.CancellationException) { throw c } catch (_: Throwable) { DataDecision(false, DataPolicy.R_UNKNOWN) }

    /** Runs [block] and re-asks the data saver about the CURRENT network every second; when it says no (own reservation excluded) the call is cancelled and fails as a counted [DataSaverBlockedException]. */
    private suspend fun <T> watching(e: Endpoint, kind: DataKind, own: DataReservation?, sample: (DataDecision?) -> Unit, block: suspend () -> T): T = kotlinx.coroutines.coroutineScope {
        val abort = java.util.concurrent.atomic.AtomicReference<DataDecision?>(null)
        val work = async { try { block() } catch (t: Throwable) { if (abort.get() != null && t !is kotlin.coroutines.cancellation.CancellationException) throw kotlin.coroutines.cancellation.CancellationException("data saver abort") else throw t } }
        val watch = launch {
            while (true) {
                kotlinx.coroutines.delay(watchMs)
                val d = decideNow(e, kind, own)
                sample(d)
                if (!d.allowed) { abort.set(d); work.cancel(); return@launch }
            }
        }
        try { work.await() }
        catch (c: kotlin.coroutines.cancellation.CancellationException) {
            val d = abort.get()
            if (d != null && isActive) throw DataSaverBlockedException(d.reason, kind, Skipped.BULK).also { it.counted = true }
            throw c
        } finally { watch.cancel() }
    }

    private fun reach(e: Endpoint): EndpointReach = com.rokid.context.ai.EndpointReaches.of(e)
    private fun safeCostly(): Boolean = try { dataGate.costlyNow() } catch (_: Exception) { true }

    /** The data saver's decision for this endpoint and kind; a refusal is counted once and thrown as the same exception the router uses (retryable, nothing was sent). A gate that throws refuses (fail closed). */
    private fun admit(e: Endpoint, kind: DataKind): DataDecision {
        val d = try { dataGate.decide(kind, reach(e)) } catch (c: kotlin.coroutines.cancellation.CancellationException) { throw c } catch (_: Exception) { DataDecision(false, DataPolicy.R_UNKNOWN) }
        if (d.allowed) return d
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
