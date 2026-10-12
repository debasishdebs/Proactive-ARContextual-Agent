package com.rokid.context.model

import com.rokid.context.ai.ProviderSettings
import com.rokid.context.model.provider.LlamaCppNativeProvider
import com.rokid.context.model.provider.OllamaProvider
import com.rokid.context.model.provider.OpenAiCompatProvider

object ModelProviderResolver {
    fun primary(config: ProviderSettings): ModelProvider {
        return when (config.providerType.lowercase()) {
            "openai" -> OpenAiCompatProvider(
                endpoint = config.endpoint,
                apiKey = config.apiKey,
                deployment = config.deploymentOrModel,
            )

            "ollama" -> OllamaProvider(
                baseUrl = config.endpoint,
                model = config.deploymentOrModel,
            )

            "llama_cpp" -> LlamaCppNativeProvider(config.nativeModelPath)
            else -> OpenAiCompatProvider(
                endpoint = config.endpoint,
                apiKey = config.apiKey,
                deployment = config.deploymentOrModel,
            )
        }
    }

    fun localFallback(config: ProviderSettings): ModelProvider? {
        if (!config.localModeEnabled) return null

        if (LlamaCppNativeProvider.isNativeAvailable() && config.nativeModelPath.isNotBlank()) {
            return LlamaCppNativeProvider(config.nativeModelPath)
        }

        // Do not implicitly reinterpret a cloud endpoint as an Ollama endpoint.
        // Ollama should only be used when explicitly selected as provider.
        return if (config.providerType.equals("ollama", ignoreCase = true) &&
            (config.endpoint.startsWith("http://") || config.endpoint.startsWith("https://"))
        ) {
            OllamaProvider(
                baseUrl = config.endpoint,
                model = config.deploymentOrModel.ifBlank { "llama3.1:8b" },
            )
        } else {
            null
        }
    }
}
