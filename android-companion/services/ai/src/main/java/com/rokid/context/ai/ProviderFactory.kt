package com.rokid.context.ai

import com.rokid.context.model.ModelProvider
import com.rokid.context.model.provider.LlamaCppNativeProvider
import com.rokid.context.model.provider.OllamaProvider
import com.rokid.context.model.provider.OpenAiCompatProvider

fun interface ProviderBuilder { fun create(endpoint: Endpoint, model: String, apiKey: String?): ModelProvider }

object ProviderFactory : ProviderBuilder {
    override fun create(endpoint: Endpoint, model: String, apiKey: String?): ModelProvider = when (endpoint.kind) {
        EndpointKind.AZURE_OPENAI -> OpenAiCompatProvider(endpoint.baseUrl, apiKey.orEmpty(), model, endpoint.apiVersion ?: "2024-10-21", style = OpenAiCompatProvider.ApiStyle.AZURE)
        EndpointKind.OPENAI_COMPAT -> OpenAiCompatProvider(endpoint.baseUrl, apiKey.orEmpty(), model, endpoint.apiVersion ?: "", style = OpenAiCompatProvider.ApiStyle.OPENAI)
        EndpointKind.OLLAMA -> OllamaProvider(endpoint.baseUrl, model)
        EndpointKind.LLAMACPP_LOCAL -> LlamaCppNativeProvider(endpoint.baseUrl)
    }
}
