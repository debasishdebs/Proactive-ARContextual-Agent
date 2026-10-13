package com.rokid.context.ai

import com.rokid.context.model.ModelProvider
import com.rokid.context.model.provider.LlamaCppNativeProvider
import com.rokid.context.model.provider.OllamaProvider
import com.rokid.context.model.provider.OpenAiCompatProvider
import okhttp3.OkHttpClient

fun interface ProviderBuilder {
    fun create(endpoint: Endpoint, model: String, apiKey: String?): ModelProvider

    /** The same provider over [client] (Test's bounded client). Builders that make no HTTP calls of their own may ignore it. */
    fun create(endpoint: Endpoint, model: String, apiKey: String?, client: OkHttpClient): ModelProvider = create(endpoint, model, apiKey)
}

object ProviderFactory : ProviderBuilder {
    override fun create(endpoint: Endpoint, model: String, apiKey: String?): ModelProvider = build(endpoint, model, apiKey, null)

    override fun create(endpoint: Endpoint, model: String, apiKey: String?, client: OkHttpClient): ModelProvider = build(endpoint, model, apiKey, client)

    /** [client] null = each provider's own production client. */
    private fun build(endpoint: Endpoint, model: String, apiKey: String?, client: OkHttpClient?): ModelProvider = when (endpoint.kind) {
        EndpointKind.AZURE_OPENAI -> OpenAiCompatProvider(endpoint.baseUrl, apiKey.orEmpty(), model, endpoint.apiVersion ?: "2024-10-21", OpenAiCompatProvider.ApiStyle.AZURE, client ?: Http.modelClient())
        EndpointKind.OPENAI_COMPAT -> OpenAiCompatProvider(endpoint.baseUrl, apiKey.orEmpty(), model, endpoint.apiVersion ?: "", OpenAiCompatProvider.ApiStyle.OPENAI, client ?: Http.modelClient())
        EndpointKind.OLLAMA -> OllamaProvider(endpoint.baseUrl, model, client ?: Http.modelClient(150))
        EndpointKind.LLAMACPP_LOCAL -> LlamaCppNativeProvider(endpoint.baseUrl) // bounded by the caller (EndpointTester's owned scope)
    }
}
