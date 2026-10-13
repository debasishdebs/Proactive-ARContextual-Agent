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
) {
    private val testClient: OkHttpClient = client.newBuilder()
        .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(callTimeoutMs, TimeUnit.MILLISECONDS)
        .writeTimeout(callTimeoutMs, TimeUnit.MILLISECONDS)
        .callTimeout(callTimeoutMs, TimeUnit.MILLISECONDS)
        .build()
    private val embeddings = EmbeddingClient(testClient, nativeTimeoutMs = callTimeoutMs)

    /** Owns native chat work, so a timed-out, uninterruptible call keeps running here, one at a time, without blocking Test. */
    private val nativeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val nativeLock = Mutex()

    suspend fun test(endpointId: String, model: String): TestResult {
        val e = repo.byId(endpointId) ?: return TestResult(false, 0, "Endpoint not found")
        val key = repo.keyFor(e)
        val start = System.nanoTime()
        return runCatching {
            if (Capability.CHAT !in e.capabilities && Capability.EMBEDDINGS in e.capabilities) {
                val dim = embeddings.embed(e, model, key, listOf("ok")).single().size
                TestResult(true, elapsed(start), "OK · $dim dimensions")
            } else {
                chat(e, model, key) // the reply is never shown: model text is provider text
                TestResult(true, elapsed(start), "OK")
            }
        }.getOrElse { if (it is CancellationException) throw it; TestResult(false, elapsed(start), humanize(it)) }
    }

    private suspend fun chat(e: Endpoint, model: String, key: String?): String {
        val provider = providers.create(e, model, key, testClient)
        if (e.kind != EndpointKind.LLAMACPP_LOCAL) return provider.generate(PROMPT)
        val work = nativeScope.async { nativeLock.withLock { provider.generate(PROMPT) } }
        return try { withTimeout(callTimeoutMs) { work.await() } }
        catch (_: TimeoutCancellationException) { throw SocketTimeoutException("Local test timed out") }
    }

    private fun elapsed(startNs: Long) = (System.nanoTime() - startNs) / 1_000_000

    companion object {
        private const val PROMPT = "Reply only with OK"

        fun humanize(t: Throwable): String {
            val code = (t as? ProviderHttpException)?.code ?: (t.cause as? ProviderHttpException)?.code
            return when {
                code != null && code in 300..399 -> "Address redirects — enter the final address"
                code == 401 || code == 403 -> "Key rejected ($code)"
                code == 404 -> "Not found — check address and model (404)"
                code != null && code >= 500 -> "Server error ($code)"
                code != null -> "Request rejected ($code)"
                t is ProviderConfigException -> "Address or key missing"
                t is java.net.UnknownServiceException -> "Plain http isn't allowed for that address"
                t is java.net.UnknownHostException || t is java.net.ConnectException -> "Can't reach that address"
                t is java.net.SocketTimeoutException || t is java.io.InterruptedIOException -> "Timed out"
                else -> "Test failed"
            }
        }
    }
}
