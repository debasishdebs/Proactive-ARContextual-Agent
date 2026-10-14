package com.rokid.context.model.provider

import com.rokid.context.ai.ProviderConfigException
import com.rokid.context.model.ModelProvider
import com.rokid.context.model.ModelReply
import com.rokid.context.model.ModelRequest
import com.rokid.context.model.WireFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The native calls behind [LlamaCppNativeProvider]; a seam so the wrapper is testable on the JVM. The JNI never throws:
 * a failed load is a 0 handle and a failed embed or generate is null.
 */
internal interface LlamaNative {
    fun isAvailable(): Boolean
    fun load(modelPath: String): Long
    fun embed(handle: Long, text: String): FloatArray?
    fun generate(handle: Long, prompt: String): String?
    fun generateWithGrammar(handle: Long, prompt: String, grammar: String): String?
    fun release(handle: Long)
}

/**
 * On-device llama.cpp provider over `libllama_jni` (arm64-v8a only). Every failure is a [ProviderConfigException] with a
 * user-safe message, so retrieval falls back to lexical search and nothing crashes.
 */
class LlamaCppNativeProvider internal constructor(private val modelPath: String, private val native: LlamaNative) : ModelProvider {
    constructor(modelPath: String) : this(modelPath, Jni)

    override suspend fun generate(prompt: String): String = complete(ModelRequest(prompt)).text

    override suspend fun complete(request: ModelRequest): ModelReply = withContext(Dispatchers.Default) {
        withModel(native, modelPath) { handle ->
            val grammar = (request.format as? WireFormat.Grammar)?.gbnf
            val text = if (grammar == null) native.generate(handle, request.prompt) else native.generateWithGrammar(handle, request.prompt, grammar)
            ModelReply(text ?: throw ProviderConfigException(GENERATE_FAILED))
        }
    }

    companion object {
        private const val UNAVAILABLE = "On-device models need a 64-bit ARM phone."
        private const val LOAD_FAILED = "On-device model couldn't load"
        private const val EMBED_FAILED = "On-device model couldn't embed this text"
        private const val GENERATE_FAILED = "On-device model couldn't answer"

        private val nativeLoaded: Boolean = try { System.loadLibrary("llama_jni"); true } catch (_: Throwable) { false }

        fun isNativeAvailable(): Boolean = nativeLoaded

        /** One vector per text, in order: L2-normalised, pooled as the model's metadata says. One load and one release per call. */
        fun embed(modelPath: String, texts: List<String>): List<FloatArray> = embed(modelPath, texts, Jni)

        internal fun embed(modelPath: String, texts: List<String>, native: LlamaNative): List<FloatArray> =
            withModel(native, modelPath) { handle ->
                texts.map { text -> native.embed(handle, text)?.takeIf { it.isNotEmpty() } ?: throw ProviderConfigException(EMBED_FAILED) }
            }

        private inline fun <T> withModel(native: LlamaNative, modelPath: String, block: (Long) -> T): T {
            if (!native.isAvailable()) throw ProviderConfigException(UNAVAILABLE)
            if (modelPath.isBlank()) throw ProviderConfigException("Local model path is required for native inference")
            val handle = native.load(modelPath)
            if (handle == 0L) throw ProviderConfigException(LOAD_FAILED)
            try { return block(handle) } finally { native.release(handle) }
        }

        /** The real JNI (`Java_com_rokid_context_model_provider_LlamaCppNativeProvider_native...`). */
        private object Jni : LlamaNative {
            override fun isAvailable() = nativeLoaded
            override fun load(modelPath: String) = nativeLoadModel(modelPath)
            override fun embed(handle: Long, text: String) = nativeEmbed(handle, text)
            override fun generate(handle: Long, prompt: String) = nativeGenerate(handle, prompt)
            override fun generateWithGrammar(handle: Long, prompt: String, grammar: String) = nativeGenerateWithGrammar(handle, prompt, grammar)
            override fun release(handle: Long) = nativeReleaseModel(handle)
        }

        @JvmStatic private external fun nativeLoadModel(modelPath: String): Long
        @JvmStatic private external fun nativeGenerate(handle: Long, prompt: String): String?
        @JvmStatic private external fun nativeGenerateWithGrammar(handle: Long, prompt: String, grammar: String): String?
        @JvmStatic private external fun nativeEmbed(handle: Long, text: String): FloatArray?
        @JvmStatic private external fun nativeReleaseModel(handle: Long)
    }
}
