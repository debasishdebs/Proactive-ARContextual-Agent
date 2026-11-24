package com.rokid.context.ai

import com.rokid.context.ai.embed.EmbeddingClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

data class TestResult(val ok: Boolean, val latencyMs: Long, val message: String)

/**
 * spec §4.1 Test: a one-token chat, or one embedding for embedding-only endpoints. Messages come from a fixed set
 * ("OK", "OK · N dimensions", [humanize]); the model's reply and provider error text are never shown.
 * Every Test is bounded: HTTP kinds run on a client derived from [client] with [connectTimeoutMs] / [callTimeoutMs]
 * (chat and embeddings), and llama.cpp runs in an owned scope that the caller stops waiting on after [callTimeoutMs].
 */
class EndpointTester(
    private val repo: EndpointRepository,
    private val providers: ProviderBuilder,
    client: OkHttpClient = Http.modelClient(),
    connectTimeoutMs: Long = 20_000,
    private val callTimeoutMs: Long = 60_000,
    /** Model privacy: Test of a remote endpoint is refused under Offline only ("nothing leaves"), whatever the test would send. */
    private val privacy: () -> com.rokid.context.core.net.ModelPrivacy = { com.rokid.context.core.net.ModelPrivacy.AUTO },
    /** The roaming data saver (Codex data-saver r1 P2-8): a Test is SMALL traffic, admitted by the endpoint's reach (refused when "Small requests on roaming" is off) and counted. Allows everything by default (tests). */
    private val dataGate: com.rokid.context.core.net.DataGate = com.rokid.context.core.net.DataGate.ALLOW_ALL,
) {
    private val testClient: OkHttpClient = client.newBuilder()
        .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(callTimeoutMs, TimeUnit.MILLISECONDS)
        .writeTimeout(callTimeoutMs, TimeUnit.MILLISECONDS)
        .callTimeout(callTimeoutMs, TimeUnit.MILLISECONDS)
        .build()
    private val embeddings = EmbeddingClient(testClient, nativeTimeoutMs = callTimeoutMs, privacy = privacy, dataGate = dataGate)

    /** Owns native chat work, so a timed-out, uninterruptible call keeps running here, one at a time, without blocking Test. */
    private val nativeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val nativeLock = Mutex()

    suspend fun test(endpointId: String, model: String): TestResult {
        val e = repo.byId(endpointId) ?: return TestResult(false, 0, "Endpoint not found")
        if (runCatching { OfflineOnlyGuard.require(privacy, e) }.isFailure) return TestResult(false, 0, OFFLINE_ONLY_MESSAGE)
        val verdict = try { dataGate.decide(com.rokid.context.core.net.DataKind.SMALL, EndpointReaches.of(e)) } catch (c: CancellationException) { throw c } catch (_: Exception) { com.rokid.context.core.net.DataDecision(false, "unknown_network") }
        if (!verdict.allowed) {
            try { dataGate.skipped(com.rokid.context.core.net.DataKind.SMALL, com.rokid.context.core.net.Skipped.BULK, verdict) } catch (_: Exception) { }
            return TestResult(false, 0, DATA_SAVER_MESSAGE)
        }
        val key = repo.keyFor(e)
        val start = System.nanoTime()
        return runCatching {
            if (Capability.CHAT !in e.capabilities && Capability.EMBEDDINGS in e.capabilities) {
                val dim = embeddings.embed(e, model, key, listOf("ok")).single().size
                TestResult(true, elapsed(start), "OK · $dim dimensions")
            } else {
                val counted = EndpointReaches.of(e) != com.rokid.context.core.net.EndpointReach.DEVICE && !EndpointReaches.isLoopback(e)
                val costly = counted && try { dataGate.costlyNow() } catch (_: Exception) { true }
                val tx = DataBytes.requestBytes(com.rokid.context.model.ModelRequest(PROMPT))
                val reply = try { chat(e, model, key) } catch (t: Throwable) { // the reply is never shown: model text is provider text
                    if (counted && !TrafficClass.provablyUnsent(t)) countTest(tx, 0, costly)
                    throw t
                }
                if (counted) countTest(tx, DataBytes.ENVELOPE + reply.toByteArray(Charsets.UTF_8).size, costly)
                TestResult(true, elapsed(start), "OK")
            }
        }.getOrElse { if (it is CancellationException) throw it; TestResult(false, elapsed(start), humanize(it)) }
    }

    private suspend fun chat(e: Endpoint, model: String, key: String?): String {
        val provider = providers.create(e, model, key, testClient)
        if (e.kind != EndpointKind.LLAMACPP_LOCAL) return provider.generate(PROMPT)
        val work = nativeScope.async { nativeLock.withLock { provider.generate(PROMPT) } }
        // A caller that stops waiting (timeout or cancellation) cancels the work: still queued = never runs.
        return try { withTimeout(callTimeoutMs) { work.await() } }
        catch (_: TimeoutCancellationException) { throw SocketTimeoutException("Local test timed out") }
        finally { work.cancel() }
    }

    private fun countTest(tx: Long, rx: Long, costlyAtStart: Boolean) {
        try { dataGate.sentAt(com.rokid.context.core.net.DataKind.SMALL, tx, rx, 0, costlyAtStart) } catch (_: Exception) { }
    }

    private fun elapsed(startNs: Long) = (System.nanoTime() - startNs) / 1_000_000

    companion object {
        private const val PROMPT = "Reply only with OK"
        const val DATA_SAVER_MESSAGE = "Data saver: not tested on this connection (small requests are off on roaming)"
        const val OFFLINE_ONLY_MESSAGE = "Offline only is on: remote endpoints are not tested"

        private const val MAX_CAUSES = 8

        /** The whole cause chain (at most [MAX_CAUSES] levels) decides the message, not just the top two (Phase 5 T1 carry). */
        fun humanize(t: Throwable): String {
            val chain = generateSequence(t) { it.cause }.take(MAX_CAUSES).toList()
            val code = chain.firstNotNullOfOrNull { (it as? ProviderHttpException)?.code }
            return when {
                code != null && code in 300..399 -> "Address redirects \u2014 enter the final address"
                code == 401 || code == 403 -> "Key rejected ($code)"
                code == 404 -> "Not found \u2014 check address and model (404)"
                code != null && code >= 500 -> "Server error ($code)"
                code != null -> "Request rejected ($code)"
                chain.any { it is ProviderConfigException } -> "Address or key missing"
                chain.any { it is java.net.UnknownServiceException } -> "Plain http isn't allowed for that address"
                chain.any { it is java.net.UnknownHostException || it is java.net.ConnectException } -> "Can't reach that address"
                chain.any { it is java.io.InterruptedIOException } -> "Timed out" // SocketTimeoutException included
                else -> "Test failed"
            }
        }
    }
}
