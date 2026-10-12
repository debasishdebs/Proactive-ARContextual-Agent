package com.rokid.context.model

import com.rokid.context.ai.EnvDefaults
import com.rokid.context.ai.ProviderSettings
import com.rokid.context.model.provider.LlamaCppNativeProvider
import com.rokid.context.model.provider.OllamaProvider
import com.rokid.context.model.provider.OpenAiCompatProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelProviderResolverTest {
    private fun s(type: String, endpoint: String = "https://x.example", local: Boolean = false) =
        ProviderSettings(providerType = type, endpoint = endpoint, apiKey = "k", deploymentOrModel = "m", nativeModelPath = "", localModeEnabled = local)

    @Test fun picksProviderByType() {
        assertTrue(ModelProviderResolver.primary(s("openai")) is OpenAiCompatProvider)
        assertTrue(ModelProviderResolver.primary(s("OLLAMA")) is OllamaProvider)
        assertTrue(ModelProviderResolver.primary(s("llama_cpp")) is LlamaCppNativeProvider)
        assertTrue(ModelProviderResolver.primary(s("unknown")) is OpenAiCompatProvider)
    }

    @Test fun noLocalFallbackWhenLocalModeOff() = assertNull(ModelProviderResolver.localFallback(s("ollama", local = false)))

    @Test fun ollamaFallbackOnlyWhenExplicitlyOllama() {
        assertTrue(ModelProviderResolver.localFallback(s("ollama", "http://10.0.2.2:11434", local = true)) is OllamaProvider)
        assertNull(ModelProviderResolver.localFallback(s("openai", local = true)))
    }

    @Test fun envDefaultsMatchGradleDefaultsUntilInitialised() {
        assertEquals("gpt-5.4", EnvDefaults.azureDeployment)
        assertEquals("http://10.0.2.2:11434", EnvDefaults.ollamaBaseUrl)
        assertEquals("llama3.1:8b", EnvDefaults.ollamaModel)
        assertEquals("native_first", EnvDefaults.localRuntimeMode)
    }
}
