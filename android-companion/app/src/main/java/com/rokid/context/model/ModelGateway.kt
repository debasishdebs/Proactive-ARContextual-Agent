package com.rokid.context.model

data class ModelImageInput(
    val mimeType: String,
    val base64Data: String,
    val detail: String = "low",
)

interface ModelProvider {
    suspend fun generate(prompt: String): String

    suspend fun generateWithImages(prompt: String, images: List<ModelImageInput>): String {
        return generate(prompt)
    }
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
