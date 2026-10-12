package com.rokid.context.model

import com.rokid.context.model.provider.LocalProviderSelector
import com.rokid.context.model.provider.LocalRuntimeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class LocalProviderSelectorTest {
    @Test
    fun nativeFirstFallsBackToOllamaWhenNativeUnavailable() {
        val ollama = object : ModelProvider {
            override suspend fun generate(prompt: String): String = "ollama:$prompt"
        }

        val selection = LocalProviderSelector.select(
            mode = LocalRuntimeMode.NATIVE_FIRST,
            nativeModelPath = "/sdcard/model.gguf",
            ollamaProvider = ollama,
            nativeAvailable = false,
        )

        assertSame(ollama, selection.provider)
        assertEquals(LocalRuntimeMode.OLLAMA_ONLY, selection.resolvedMode)
    }

    @Test(expected = IllegalArgumentException::class)
    fun nativeOnlyThrowsWhenModelPathMissing() {
        val ollama = object : ModelProvider {
            override suspend fun generate(prompt: String): String = "ollama:$prompt"
        }

        LocalProviderSelector.select(
            mode = LocalRuntimeMode.NATIVE_ONLY,
            nativeModelPath = "",
            ollamaProvider = ollama,
            nativeAvailable = true,
        )
    }

    @Test
    fun nativeOnlyUsesNativeWhenReady() {
        val ollama = object : ModelProvider {
            override suspend fun generate(prompt: String): String = "ollama:$prompt"
        }
        val native = object : ModelProvider {
            override suspend fun generate(prompt: String): String = "native:$prompt"
        }

        val selection = LocalProviderSelector.select(
            mode = LocalRuntimeMode.NATIVE_ONLY,
            nativeModelPath = "/sdcard/model.gguf",
            ollamaProvider = ollama,
            nativeAvailable = true,
            nativeProviderFactory = { native },
        )

        assertSame(native, selection.provider)
        assertEquals(LocalRuntimeMode.NATIVE_ONLY, selection.resolvedMode)
    }
}
