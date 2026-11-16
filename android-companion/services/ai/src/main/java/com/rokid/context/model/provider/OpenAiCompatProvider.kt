package com.rokid.context.model.provider

import com.rokid.context.ai.EnvDefaults
import com.rokid.context.ai.Http
import com.rokid.context.ai.KeyTransport
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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class OpenAiCompatProvider(
    private val endpoint: String = EnvDefaults.azureEndpoint,
    private val apiKey: String = "",
    private val deployment: String = EnvDefaults.azureDeployment,
    private val apiVersion: String = "2024-10-21",
    private val style: ApiStyle = ApiStyle.AZURE,
    private val client: OkHttpClient = Http.modelClient(),
    /** Test seam: runs after the payload is built, just before fence admission. */
    internal val beforeAdmit: () -> Unit = {},
) : ModelProvider {
    enum class ApiStyle { AZURE, OPENAI }

    override suspend fun generate(prompt: String): String = complete(ModelRequest(prompt)).text

    override suspend fun generateWithImages(prompt: String, images: List<ModelImageInput>): String = complete(ModelRequest(prompt, images)).text

    override suspend fun complete(request: ModelRequest): ModelReply = withContext(Dispatchers.IO) {
        if (endpoint.isBlank()) throw ProviderConfigException("AZURE_KG_ENDPOINT is missing")
        if (style == ApiStyle.AZURE && apiKey.isBlank()) throw ProviderConfigException("API key is missing")
        val payload = buildJsonObject {
            put("messages", buildJsonArray { add(userMessage(request)) })
            put("temperature", JsonPrimitive(request.temperature ?: DEFAULT_TEMPERATURE))
            put("max_completion_tokens", JsonPrimitive(request.maxTokens ?: ModelRequest.DEFAULT_MAX_TOKENS))
            val f = request.format
            if (f is WireFormat.JsonSchema) {
                put("response_format", buildJsonObject {
                    put("type", JsonPrimitive("json_schema"))
                    put("json_schema", buildJsonObject {
                        put("name", JsonPrimitive(f.name))
                        put("strict", JsonPrimitive(true))
                        put("schema", f.schema)
                    })
                })
            }
            if (style == ApiStyle.OPENAI) put("model", JsonPrimitive(deployment))
        }
        KeyTransport.requireSecure(endpoint, apiKey)
        val base = endpoint.trimEnd('/')
        val url = when (style) {
            ApiStyle.AZURE -> "$base/openai/deployments/$deployment/chat/completions?api-version=$apiVersion"
            ApiStyle.OPENAI -> "$base/chat/completions"
        }
        val http = Request.Builder().url(url)
            .apply { if (style == ApiStyle.AZURE) addHeader("api-key", apiKey) else if (apiKey.isNotBlank()) addHeader("Authorization", "Bearer $apiKey") }
            .addHeader("Content-Type", "application/json")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        executeFenced(client, http, beforeAdmit).use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw ProviderHttpException(response.code, "Azure OpenAI", body)
            val json = Json.parseToJsonElement(body).jsonObject
            val choice = json["choices"]?.jsonArray?.firstOrNull()?.jsonObject
            val content = choice?.get("message")?.jsonObject?.get("content")
                ?: throw ProviderResponseException("Azure OpenAI response missing message content")
            // OBS-R3-1: a refusal comes as `content: null` (+ `refusal`); JsonNull.content is the text "null", which
            // parses as JSON. It is no reply at all: empty text, so RoleRunner rejects it as "not JSON" and retries.
            val text = if (content is JsonNull) "" else runCatching { content.jsonPrimitive.content }.getOrElse {
                content.jsonArray.joinToString("\n") { part ->
                    val o = part.jsonObject
                    o["text"]?.jsonPrimitive?.content ?: o["refusal"]?.jsonPrimitive?.content ?: ""
                }.trim().ifBlank { throw ProviderResponseException("Azure OpenAI response contained empty content parts") }
            }
            val usage = json["usage"] as? JsonObject
            val cut = (choice?.get("finish_reason") as? JsonPrimitive)?.takeIf { it.isString }?.content == "length"
            ModelReply(text, usage?.get("prompt_tokens")?.jsonPrimitive?.intOrNull, usage?.get("completion_tokens")?.jsonPrimitive?.intOrNull, truncated = cut)
        }
    }

    private fun userMessage(request: ModelRequest): JsonObject = buildJsonObject {
        put("role", JsonPrimitive("user"))
        if (request.images.isEmpty()) {
            put("content", JsonPrimitive(request.prompt))
        } else {
            put("content", buildJsonArray {
                add(buildJsonObject { put("type", JsonPrimitive("text")); put("text", JsonPrimitive(request.prompt)) })
                request.images.forEach { image ->
                    add(buildJsonObject {
                        put("type", JsonPrimitive("image_url"))
                        put("image_url", buildJsonObject {
                            put("url", JsonPrimitive("data:${image.mimeType};base64,${image.base64Data}"))
                            put("detail", JsonPrimitive(image.detail))
                        })
                    })
                }
            })
        }
    }

    private companion object {
        const val DEFAULT_TEMPERATURE = 0.2
    }
}
