package com.rokid.context.model.provider

import com.rokid.context.BuildConfig
import com.rokid.context.model.ModelProvider

object LocalProviderSelector {
    data class Selection(
        val provider: ModelProvider,
        val resolvedMode: LocalRuntimeMode,
        val reason: String,
    )

    /**
     * Prefer native on-device llama.cpp runtime when available and configured.
     * Fallback to Ollama HTTP provider when native runtime is unavailable.
     */
    fun select(
        mode: LocalRuntimeMode = LocalRuntimeMode.from(BuildConfig.LOCAL_RUNTIME_MODE),
        nativeModelPath: String? = BuildConfig.LOCAL_NATIVE_MODEL_PATH,
        ollamaProvider: ModelProvider,
        nativeAvailable: Boolean = LlamaCppNativeProvider.isNativeAvailable(),
        nativeProviderFactory: (String) -> ModelProvider = { LlamaCppNativeProvider(it) },
    ): Selection {
        val path = nativeModelPath.orEmpty()
        val nativeReady = path.isNotBlank() && nativeAvailable

        return when (mode) {
            LocalRuntimeMode.NATIVE_FIRST -> {
                if (nativeReady) {
                    Selection(
                        provider = nativeProviderFactory(path),
                        resolvedMode = LocalRuntimeMode.NATIVE_FIRST,
                        reason = "Native llama.cpp runtime selected",
                    )
                } else {
                    Selection(
                        provider = ollamaProvider,
                        resolvedMode = LocalRuntimeMode.OLLAMA_ONLY,
                        reason = "Native runtime unavailable or model path missing; falling back to Ollama",
                    )
                }
            }

            LocalRuntimeMode.NATIVE_ONLY -> {
                require(nativeReady) {
                    "Native-only mode selected but runtime unavailable or LOCAL_NATIVE_MODEL_PATH missing"
                }
                Selection(
                    provider = nativeProviderFactory(path),
                    resolvedMode = LocalRuntimeMode.NATIVE_ONLY,
                    reason = "Native-only mode active",
                )
            }

            LocalRuntimeMode.OLLAMA_ONLY -> {
                Selection(
                    provider = ollamaProvider,
                    resolvedMode = LocalRuntimeMode.OLLAMA_ONLY,
                    reason = "Ollama-only mode active",
                )
            }
        }
    }
}
