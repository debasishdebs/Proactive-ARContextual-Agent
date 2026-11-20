package com.rokid.context.model.provider

import com.rokid.context.ai.EnvDefaults
import com.rokid.context.ai.Http
import com.rokid.context.ai.ProviderConfigException
import com.rokid.context.ai.ProviderHttpException
import com.rokid.context.ai.ProviderResponseException
import com.rokid.context.model.ModelImageInput
import com.rokid.context.model.ModelProvider
import com.rokid.context.model.ModelReply
import com.rokid.context.model.ModelRequest
import com.rokid.context.model.WireFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class OllamaProvider(
    private val baseUrl: String = EnvDefaults.ollamaBaseUrl,
    private val model: String = EnvDefaults.ollamaModel,
    private val client: OkHttpClient = Http.modelClient(150),
    /** Test seam: runs after the payload is built, just before fence admission. */
    internal val beforeAdmit: () -> Unit = {},
) : ModelProvider {
    override suspend fun generate(prompt: String): String = complete(ModelRequest(prompt)).text

    override suspend fun generateWithImages(prompt: String, images: List<ModelImageInput>): String = complete(ModelRequest(prompt, images)).text

    override suspend fun complete(request: ModelRequest): ModelReply = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank()) throw ProviderConfigException("OLLAMA_BASE_URL is missing")
        val payload = buildJsonObject {
            put("model", JsonPrimitive(model))
            put("prompt", JsonPrimitive(request.prompt))
            put("stream", JsonPrimitive(false))
            if (request.images.isNotEmpty()) put("images", JsonArray(request.images.map { JsonPrimitive(it.base64Data) }))
            val f = request.format
            if (f is WireFormat.OllamaSchema) put("format", f.schema)
            put("options", buildJsonObject {
                request.temperature?.let { put("temperature", JsonPrimitive(it)) }
                request.maxTokens?.let { put("num_predict", JsonPrimitive(it)) }
            })
        }
        val http = Request.Builder().url("${baseUrl.trimEnd('/')}/api/generate")
            .addHeader("Content-Type", "application/json")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        executeFenced(client, http, beforeAdmit).use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw ProviderHttpException(response.code, "Ollama", body)
            ProviderResponseException.parsing("Ollama") {
                val json = Json.parseToJsonElement(body).jsonObject
                val text = json["response"]?.jsonPrimitive?.content ?: throw ProviderResponseException("Ollama response missing text")
                ModelReply(text, json["prompt_eval_count"]?.jsonPrimitive?.intOrNull, json["eval_count"]?.jsonPrimitive?.intOrNull)
            }
        }
    }
}
