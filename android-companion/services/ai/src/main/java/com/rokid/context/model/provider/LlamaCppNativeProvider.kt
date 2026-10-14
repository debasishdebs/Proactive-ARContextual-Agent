package com.rokid.context.model.provider

import com.rokid.context.ai.ProviderConfigException
import com.rokid.context.model.ModelProvider
import com.rokid.context.model.ModelReply
import com.rokid.context.model.ModelRequest
import com.rokid.context.model.WireFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * The native calls behind [LlamaCppNativeProvider]; a seam so the wrapper is testable on the JVM. The JNI never throws:
 * a failed load is a 0 handle and a failed embed or generate is null.
 */
internal interface LlamaNative {
    fun isAvailable(): Boolean
    /** With [forEmbedding] the embed context is created too; failing that (out of memory) is a failed load, a 0 handle. */
    fun load(modelPath: String, forEmbedding: Boolean): Long
    fun embed(handle: Long, text: String): FloatArray?
    fun generate(handle: Long, prompt: String): String?
    fun generateWithGrammar(handle: Long, prompt: String, grammar: String): String?
    fun release(handle: Long)
}

/**
 * Consecutive load failures per model file on the embed path (spec section 6): a failed load counts one, a load that
 * works resets it. In memory only: a fresh process starts at 0, and the page's "Couldn't load" row needs two in a row.
 */
class LocalLoadFailures {
    private val counts = ConcurrentHashMap<String, Int>()

    fun consecutive(modelPath: String): Int = counts[modelPath] ?: 0

    /** A replaced or deleted file starts over. */
    fun reset(modelPath: String) { counts.remove(modelPath) }

    internal fun failed(modelPath: String) { counts.merge(modelPath, 1, Int::plus) }
}

/**
 * Keeps one embed model loaded between calls and releases it after [idleMs] without use (spec section 6). Calls through
 * it run one at a time, process-wide. A model file replaced on disk (other size or time) is loaded afresh.
 * [schedule] runs a task after a delay and returns its cancel.
 */
internal class EmbedModelCache(
    private val idleMs: Long = 60_000,
    private val schedule: (Long, () -> Unit) -> (() -> Unit) = ::onIdleThread,
) {
    private val lock = Any()
    private var native: LlamaNative? = null
    private var key: String? = null
    private var handle = 0L
    private var cancelIdle: (() -> Unit)? = null
    private var generation = 0

    fun <T> use(native: LlamaNative, modelPath: String, open: () -> Long, block: (Long) -> T): T = synchronized(lock) {
        cancelIdle?.invoke(); cancelIdle = null; generation++
        val k = keyOf(modelPath)
        if (handle != 0L && (this.native !== native || key != k)) releaseLocked()
        if (handle == 0L) { handle = open(); this.native = native; key = k }
        try {
            block(handle)
        } catch (t: Throwable) {
            releaseLocked(); throw t
        } finally {
            if (handle != 0L) {
                val armed = generation
                cancelIdle = schedule(idleMs) { synchronized(lock) { if (armed == generation) releaseLocked() } }
            }
        }
    }

    private fun releaseLocked() {
        val h = handle
        handle = 0L; key = null
        if (h != 0L) native?.release(h)
        native = null
    }

    private companion object {
        fun keyOf(path: String) = File(path).let { "$path|${it.length()}|${it.lastModified()}" }

        private val idleThread by lazy {
            ScheduledThreadPoolExecutor(1) { r -> Thread(r, "llama-idle").apply { isDaemon = true } }.apply { removeOnCancelPolicy = true }
        }

        fun onIdleThread(delayMs: Long, task: () -> Unit): () -> Unit {
            val f = idleThread.schedule(task, delayMs, TimeUnit.MILLISECONDS)
            return { f.cancel(false) }
        }
    }
}

/**
 * On-device llama.cpp provider over `libllama_jni` (arm64-v8a only). Every failure is a [ProviderConfigException] with a
 * user-safe message, so retrieval falls back to lexical search and nothing crashes.
 */
class LlamaCppNativeProvider internal constructor(private val modelPath: String, private val native: LlamaNative) : ModelProvider {
    constructor(modelPath: String) : this(modelPath, Jni)

    override suspend fun generate(prompt: String): String = complete(ModelRequest(prompt)).text

    override suspend fun complete(request: ModelRequest): ModelReply = withContext(Dispatchers.Default) {
        withModel(native, modelPath, forEmbedding = false) { handle ->
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

        /** The process-wide load-failure counts of the real embed path, read by Settings > AI > On-device models. */
        val loadFailures = LocalLoadFailures()

        private val embedModels = EmbedModelCache()

        /** One vector per text, in order: L2-normalised, pooled as the model's metadata says. The model stays loaded until 60 s idle. */
        fun embed(modelPath: String, texts: List<String>): List<FloatArray> = embed(modelPath, texts, Jni, loadFailures, embedModels)

        /** Without a [cache], one load and one release per call. */
        internal fun embed(
            modelPath: String, texts: List<String>, native: LlamaNative, failures: LocalLoadFailures? = null, cache: EmbedModelCache? = null,
        ): List<FloatArray> {
            val block = { handle: Long ->
                texts.map { text -> native.embed(handle, text)?.takeIf { it.isNotEmpty() } ?: throw ProviderConfigException(EMBED_FAILED) }
            }
            return if (cache == null) withModel(native, modelPath, forEmbedding = true, failures, block)
            else cache.use(native, modelPath, { open(native, modelPath, forEmbedding = true, failures) }, block)
        }

        private inline fun <T> withModel(native: LlamaNative, modelPath: String, forEmbedding: Boolean, failures: LocalLoadFailures? = null, block: (Long) -> T): T {
            val handle = open(native, modelPath, forEmbedding, failures)
            try { return block(handle) } finally { native.release(handle) }
        }

        /**
         * A loaded handle, never 0. [failures] counts a failed load (no runtime, or a 0 handle, which on the embed path
         * includes an embed context that couldn't be created) and is reset by one that works.
         */
        private fun open(native: LlamaNative, modelPath: String, forEmbedding: Boolean, failures: LocalLoadFailures?): Long {
            if (!native.isAvailable()) { failures?.failed(modelPath); throw ProviderConfigException(UNAVAILABLE) }
            if (modelPath.isBlank()) throw ProviderConfigException("Local model path is required for native inference")
            val handle = native.load(modelPath, forEmbedding)
            if (handle == 0L) { failures?.failed(modelPath); throw ProviderConfigException(LOAD_FAILED) }
            failures?.reset(modelPath)
            return handle
        }

        /** The real JNI (`Java_com_rokid_context_model_provider_LlamaCppNativeProvider_native...`). */
        private object Jni : LlamaNative {
            override fun isAvailable() = nativeLoaded
            override fun load(modelPath: String, forEmbedding: Boolean) = nativeLoadModel(modelPath, forEmbedding)
            override fun embed(handle: Long, text: String) = nativeEmbed(handle, text)
            override fun generate(handle: Long, prompt: String) = nativeGenerate(handle, prompt)
            override fun generateWithGrammar(handle: Long, prompt: String, grammar: String) = nativeGenerateWithGrammar(handle, prompt, grammar)
            override fun release(handle: Long) = nativeReleaseModel(handle)
        }

        @JvmStatic private external fun nativeLoadModel(modelPath: String, forEmbedding: Boolean): Long
        @JvmStatic private external fun nativeGenerate(handle: Long, prompt: String): String?
        @JvmStatic private external fun nativeGenerateWithGrammar(handle: Long, prompt: String, grammar: String): String?
        @JvmStatic private external fun nativeEmbed(handle: Long, text: String): FloatArray?
        @JvmStatic private external fun nativeReleaseModel(handle: Long)
    }
}
