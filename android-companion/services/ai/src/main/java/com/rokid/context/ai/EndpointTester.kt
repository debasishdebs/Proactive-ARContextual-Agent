package com.rokid.context.ai


data class TestResult(val ok: Boolean, val latencyMs: Long, val message: String)

/** spec §4.1 Test: a one-token chat, or one embedding for embedding-only endpoints. Messages are short and human. */
class EndpointTester(
    private val repo: EndpointRepository,
    private val providers: ProviderBuilder,
    private val embeddings: com.rokid.context.ai.embed.EmbeddingClient = com.rokid.context.ai.embed.EmbeddingClient(),
) {

    suspend fun test(endpointId: String, model: String): TestResult {
        val e = repo.byId(endpointId) ?: return TestResult(false, 0, "Endpoint not found")
        val key = repo.keyFor(e)
        val start = System.nanoTime()
        return runCatching {
            if (Capability.CHAT !in e.capabilities && Capability.EMBEDDINGS in e.capabilities) {
                val dim = embeddings.embed(e, model, key, listOf("ok")).single().size
                TestResult(true, elapsed(start), "OK · $dim dimensions")
            } else {
                val out = providers.create(e, model, key).generate("Reply only with OK")
                TestResult(true, elapsed(start), "OK · ${out.trim().take(20)}")
            }
        }.getOrElse { TestResult(false, elapsed(start), humanize(it)) }
    }

    private fun elapsed(startNs: Long) = (System.nanoTime() - startNs) / 1_000_000

    companion object {
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
