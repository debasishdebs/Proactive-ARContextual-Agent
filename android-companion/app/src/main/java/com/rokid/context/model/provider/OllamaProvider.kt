package com.rokid.context.model.provider

import com.rokid.context.BuildConfig
import com.rokid.context.model.ModelProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class OllamaProvider(
    private val baseUrl: String = BuildConfig.OLLAMA_BASE_URL,
    private val model: String = BuildConfig.OLLAMA_MODEL,
    private val client: OkHttpClient = OkHttpClient(),
) : ModelProvider {
    override suspend fun generate(prompt: String): String = withContext(Dispatchers.IO) {
        require(baseUrl.isNotBlank()) { "OLLAMA_BASE_URL is missing" }
        val url = "${baseUrl.trimEnd('/')}/api/generate"

        val payload = buildJsonObject {
            put("model", JsonPrimitive(model))
            put("prompt", JsonPrimitive(prompt))
            put("stream", JsonPrimitive(false))
        }

        val request = Request.Builder()
            .url(url)
            .addHeader("Content-Type", "application/json")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                error("Ollama request failed: ${response.code} $body")
            }

            val json = Json.parseToJsonElement(body).jsonObject
            json["response"]?.jsonPrimitive?.content
                ?: error("Ollama response missing `response` text")
        }
    }
}
