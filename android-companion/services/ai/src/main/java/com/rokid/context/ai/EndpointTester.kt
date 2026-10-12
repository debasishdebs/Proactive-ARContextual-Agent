package com.rokid.context.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

data class TestResult(val ok: Boolean, val latencyMs: Long, val message: String)

/** spec §4.1 Test: a one-token chat, or one embedding for embedding-only endpoints. Messages are short and human. */
class EndpointTester(private val repo: EndpointRepository, private val providers: ProviderBuilder) {
    private val http = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()

    suspend fun test(endpointId: String, model: String): TestResult {
        val e = repo.byId(endpointId) ?: return TestResult(false, 0, "Endpoint not found")
        val key = repo.keyFor(e)
        val start = System.nanoTime()
        return runCatching {
            if (Capability.CHAT !in e.capabilities && Capability.EMBEDDINGS in e.capabilities) {
                val dim = embedProbe(e, model, key)
                TestResult(true, elapsed(start), "OK · $dim dimensions")
            } else {
                val out = providers.create(e, model, key).generate("Reply only with OK")
                TestResult(true, elapsed(start), "OK · ${out.trim().take(20)}")
            }
        }.getOrElse { TestResult(false, elapsed(start), humanize(it)) }
    }

    private fun elapsed(startNs: Long) = (System.nanoTime() - startNs) / 1_000_000

    private suspend fun embedProbe(e: Endpoint, model: String, key: String?): Int = withContext(Dispatchers.IO) {
        val (url, body) = when (e.kind) {
            EndpointKind.OLLAMA -> "${e.baseUrl}/api/embed" to """{"model":"$model","input":["ok"]}"""
            EndpointKind.AZURE_OPENAI -> "${e.baseUrl}/openai/deployments/$model/embeddings?api-version=${e.apiVersion ?: "2024-10-21"}" to """{"input":["ok"]}"""
            EndpointKind.OPENAI_COMPAT -> "${e.baseUrl}/embeddings" to """{"model":"$model","input":["ok"]}"""
            EndpointKind.LLAMACPP_LOCAL -> throw ProviderConfigException("On-device embeddings need the llama.cpp runtime")
        }
        KeyTransport.requireSecure(url, key)
        val req = Request.Builder().url(url).post(body.toRequestBody("application/json".toMediaType())).apply {
            when (e.kind) {
                EndpointKind.AZURE_OPENAI -> key?.let { addHeader("api-key", it) }
                EndpointKind.OPENAI_COMPAT -> key?.let { addHeader("Authorization", "Bearer $it") }
                else -> Unit
            }
        }.build()
        http.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw ProviderHttpException(r.code, "Embedding", "")
            val json = Json.parseToJsonElement(text).jsonObject
            val vec = (json["embeddings"]?.jsonArray?.firstOrNull() as? JsonArray)
                ?: json["data"]?.jsonArray?.firstOrNull()?.jsonObject?.get("embedding")?.jsonArray
                ?: throw ProviderResponseException("No embedding in response")
            vec.size
        }
    }

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
