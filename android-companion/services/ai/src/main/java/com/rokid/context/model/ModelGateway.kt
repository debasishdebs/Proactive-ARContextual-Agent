package com.rokid.context.model

import kotlinx.serialization.json.JsonObject

/**
 * [ref]: the id of the stored photo (frame) these bytes were read from. The router refuses to send an image without one (it re-checks every ref is still
 * live right before the upload, [PhotoLiveness]); in memory only.
 */
data class ModelImageInput(
    val mimeType: String,
    val base64Data: String,
    val detail: String = "low",
    val ref: String? = null,
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
    /** The answer is meaningless without the images (image intent): never drop them for a text-only endpoint or retry as text. */
    val requireImages: Boolean = false,
    /**
     * The stored photos this request is about, beyond the [images] refs (a photo whose bytes were dropped for this endpoint but whose metadata is still in
     * the prompt). The router re-checks the union of these and the image refs with [liveness] inside every provider call's permit.
     */
    val photoIds: Set<String> = emptySet(),
    /** Who says whether the photos are still live (and holds the upload slot a delete waits for). Required whenever a photo is attached: absent = refused. */
    val liveness: PhotoLiveness? = null,
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

    /**
     * False for a provider that cannot put images on the wire (the on-device llama.cpp runtime): the router then fails a photo-requiring call closed
     * ([com.rokid.context.ai.ImagesUnsupportedException]) and never marks a reply as having delivered photos. A new provider that cannot send images MUST
     * override this.
     */
    val acceptsImages: Boolean get() = true

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
