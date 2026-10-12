package com.rokid.context.model

import com.rokid.context.model.provider.LocalProviderSelector
import com.rokid.context.model.provider.OllamaProvider
import com.rokid.context.model.provider.OpenAiCompatProvider

object ModelGatewayFactory {
    data class Bootstrap(
        val gateway: ModelGateway,
        val localSelection: LocalProviderSelector.Selection,
    )

    fun bootstrap(): Bootstrap {
        val cloud = OpenAiCompatProvider()
        val ollama = OllamaProvider()
        val localSelection = LocalProviderSelector.select(ollamaProvider = ollama)
        return Bootstrap(
            gateway = ModelGateway(cloudProvider = cloud, localProvider = localSelection.provider),
            localSelection = localSelection,
        )
    }
}
