package com.rokid.context.model.provider

import com.rokid.context.ai.ProviderConfigException
import com.rokid.context.model.ModelProvider
import com.rokid.context.model.ModelReply
import com.rokid.context.model.ModelRequest
import com.rokid.context.model.WireFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * On-device llama.cpp provider. The JNI bridge (`libllama_jni`) is not packaged in v1 builds, so every call fails with
 * a [ProviderConfigException] until it is; the API shape (grammar-constrained generation, embeddings) is final.
 */
class LlamaCppNativeProvider(private val modelPath: String) : ModelProvider {
    override suspend fun generate(prompt: String): String = complete(ModelRequest(prompt)).text

    override suspend fun complete(request: ModelRequest): ModelReply = withContext(Dispatchers.Default) {
        requireRuntime(modelPath)
        val handle = nativeLoadModel(modelPath)
        try {
            val grammar = (request.format as? WireFormat.Grammar)?.gbnf
            ModelReply(if (grammar == null) nativeGenerate(handle, request.prompt) else nativeGenerateWithGrammar(handle, request.prompt, grammar))
        } finally {
            nativeReleaseModel(handle)
        }
    }

    companion object {
        private var nativeLoaded = false

        init {
            nativeLoaded = try { System.loadLibrary("llama_jni"); true } catch (_: Throwable) { false }
        }

        fun isNativeAvailable(): Boolean = nativeLoaded

        private fun requireRuntime(modelPath: String) {
            if (!isNativeAvailable()) throw ProviderConfigException("llama.cpp native runtime not available in current APK build")
            if (modelPath.isBlank()) throw ProviderConfigException("Local model path is required for native inference")
        }

        /** One vector per text, in order (Task 9 embeddings). */
        fun embed(modelPath: String, texts: List<String>): List<FloatArray> {
            requireRuntime(modelPath)
            val handle = nativeLoadModel(modelPath)
            try { return texts.map { nativeEmbed(handle, it) } } finally { nativeReleaseModel(handle) }
        }

        @JvmStatic private external fun nativeLoadModel(modelPath: String): Long
        @JvmStatic private external fun nativeGenerate(handle: Long, prompt: String): String
        @JvmStatic private external fun nativeGenerateWithGrammar(handle: Long, prompt: String, grammar: String): String
        @JvmStatic private external fun nativeEmbed(handle: Long, text: String): FloatArray
        @JvmStatic private external fun nativeReleaseModel(handle: Long)
    }
}
