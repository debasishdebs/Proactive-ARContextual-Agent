package com.rokid.context.model.provider

import com.rokid.context.BuildConfig
import com.rokid.context.model.ModelImageInput
import com.rokid.context.model.ModelProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class OpenAiCompatProvider(
    private val endpoint: String = BuildConfig.AZURE_KG_ENDPOINT,
    private val apiKey: String = BuildConfig.AZURE_KG_API_KEY,
    private val deployment: String = BuildConfig.AZURE_KG_DEPLOYMENT,
    private val apiVersion: String = "2024-10-21",
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(75, TimeUnit.SECONDS)
        .writeTimeout(75, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .build(),
) : ModelProvider {
    override suspend fun generate(prompt: String): String = withContext(Dispatchers.IO) {
        requestChatCompletion(
            buildJsonObject {
                put("messages", buildJsonArray {
                    add(buildJsonObject {
                        put("role", JsonPrimitive("user"))
                        put("content", JsonPrimitive(prompt))
                    })
                })
                put("temperature", JsonPrimitive(0.2))
                put("max_completion_tokens", JsonPrimitive(500))
            },
        )
    }

    override suspend fun generateWithImages(prompt: String, images: List<ModelImageInput>): String = withContext(Dispatchers.IO) {
        if (images.isEmpty()) return@withContext generate(prompt)
        requestChatCompletion(
            buildJsonObject {
                put("messages", buildJsonArray {
                    add(buildJsonObject {
                        put("role", JsonPrimitive("user"))
                        put("content", buildJsonArray {
                            add(buildJsonObject {
                                put("type", JsonPrimitive("text"))
                                put("text", JsonPrimitive(prompt))
                            })
                            images.forEach { image ->
                                add(buildJsonObject {
                                    put("type", JsonPrimitive("image_url"))
                                    put("image_url", buildJsonObject {
                                        put("url", JsonPrimitive("data:${image.mimeType};base64,${image.base64Data}"))
                                        put("detail", JsonPrimitive(image.detail))
                                    })
                                })
                            }
                        })
                    })
                })
                put("temperature", JsonPrimitive(0.2))
                put("max_completion_tokens", JsonPrimitive(500))
            },
        )
    }

    private fun requestChatCompletion(payload: kotlinx.serialization.json.JsonObject): String {
        require(endpoint.isNotBlank()) { "AZURE_KG_ENDPOINT is missing" }
        require(apiKey.isNotBlank()) { "AZURE_KG_API_KEY is missing" }

        val normalized = endpoint.trimEnd('/')
        val url = "$normalized/openai/deployments/$deployment/chat/completions?api-version=$apiVersion"
        val request = Request.Builder()
            .url(url)
            .addHeader("api-key", apiKey)
            .addHeader("Content-Type", "application/json")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                error("Azure OpenAI request failed: ${response.code} $body")
            }

            val json = Json.parseToJsonElement(body).jsonObject
            val content = json["choices"]
                ?.jsonArray
                ?.firstOrNull()
                ?.jsonObject
                ?.get("message")
                ?.jsonObject
                ?.get("content")
                ?: error("Azure OpenAI response missing message content")

            return runCatching { content.jsonPrimitive.content }.getOrElse {
                val merged = content.jsonArray.joinToString(separator = "\n") { part ->
                    val obj = part.jsonObject
                    obj["text"]?.jsonPrimitive?.content
                        ?: obj["refusal"]?.jsonPrimitive?.content
                        ?: ""
                }.trim()
                if (merged.isBlank()) error("Azure OpenAI response contained empty content parts")
                merged
            }
        }
    }
}
