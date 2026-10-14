package com.rokid.context.model

import kotlinx.serialization.json.JsonObject

data class ModelImageInput(
    val mimeType: String,
    val base64Data: String,
    val detail: String = "low",
)

/** How a provider should constrain decoding (spec §4.4). [None] = free text (the instructions fallback is in the prompt). */
sealed interface WireFormat {
    data object None : WireFormat
    /** Azure / OpenAI-compatible `response_format: {type: json_schema, strict: true}`. */
    data class JsonSchema(val name: String, val schema: JsonObject) : WireFormat
    /** Ollama `format: <schema>`. */
    data class OllamaSchema(val schema: JsonObject) : WireFormat
    /** llama.cpp GBNF grammar. */
    data class Grammar(val gbnf: String) : WireFormat
}

/** One model call. Null [temperature]/[maxTokens] = the binding's value, else the provider default. */
data class ModelRequest(
    val prompt: String,
    val images: List<ModelImageInput> = emptyList(),
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    val format: WireFormat = WireFormat.None,
) {
    companion object {
        /**
         * The cap a provider sends when neither the request nor the binding sets one. OBS-R3-1: reasoning deployments
         * (the gpt-5.x build default) count reasoning tokens against `max_completion_tokens`, so the old 500 could
         * be spent before any JSON was written. It is a cap, not a reservation.
         */
        const val DEFAULT_MAX_TOKENS = 4_096
        /** The largest cap Settings accepts for a binding (and the most a cut-reply retry asks for). */
        const val MAX_TOKENS_LIMIT = 8_192
    }
}

/** [truncated]: the provider said it stopped at the token cap (OpenAI `finish_reason: "length"`). */
data class ModelReply(val text: String, val promptTokens: Int? = null, val completionTokens: Int? = null, val truncated: Boolean = false)

interface ModelProvider {
    suspend fun generate(prompt: String): String

    suspend fun generateWithImages(prompt: String, images: List<ModelImageInput>): String {
        return generate(prompt)
    }

    /** Providers override this to honour options, formats and usage; the default keeps old/fake providers working. */
    suspend fun complete(request: ModelRequest): ModelReply =
        ModelReply(if (request.images.isEmpty()) generate(request.prompt) else generateWithImages(request.prompt, request.images))
}

class ModelGateway(
    private val cloudProvider: ModelProvider,
    private val localProvider: ModelProvider,
) {
    suspend fun generate(prompt: String, preferLocal: Boolean, localHealthy: Boolean): String {
        val provider = if (preferLocal && localHealthy) localProvider else cloudProvider
        return provider.generate(prompt)
    }
}
