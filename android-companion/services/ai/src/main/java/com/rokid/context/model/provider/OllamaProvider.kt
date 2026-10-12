package com.rokid.context.model.provider

import com.rokid.context.ai.ProviderConfigException
import com.rokid.context.ai.ProviderHttpException
import com.rokid.context.ai.ProviderResponseException
import com.rokid.context.ai.EnvDefaults
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
import java.util.concurrent.TimeUnit

class OllamaProvider(
    private val baseUrl: String = EnvDefaults.ollamaBaseUrl,
    private val model: String = EnvDefaults.ollamaModel,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .callTimeout(150, TimeUnit.SECONDS)
        .build(),
) : ModelProvider {
    override suspend fun generate(prompt: String): String = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank()) throw ProviderConfigException("OLLAMA_BASE_URL is missing")
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
                throw ProviderHttpException(response.code, "Ollama", body)
            }

            val json = Json.parseToJsonElement(body).jsonObject
            json["response"]?.jsonPrimitive?.content
                ?: throw ProviderResponseException("Ollama response missing text")
        }
    }
}
