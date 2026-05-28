package com.rokid.context.model.provider

import com.rokid.context.model.ModelProvider

class OpenAiCompatProvider : ModelProvider {
    override suspend fun generate(prompt: String): String = "cloud:$prompt"
}
