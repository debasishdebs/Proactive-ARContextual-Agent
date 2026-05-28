package com.rokid.context.model.provider

import com.rokid.context.model.ModelProvider

class OllamaProvider : ModelProvider {
    override suspend fun generate(prompt: String): String = "local:$prompt"
}
