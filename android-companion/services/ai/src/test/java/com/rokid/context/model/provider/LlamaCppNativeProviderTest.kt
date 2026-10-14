package com.rokid.context.model.provider

import com.rokid.context.ai.ProviderConfigException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LlamaCppNativeProviderTest {
    /** A scripted native layer; counts loads and releases so every handle is shown to be freed. */
    private class FakeNative(
        private val available: Boolean = true,
        private val handle: Long = 7L,
        private val vector: FloatArray? = floatArrayOf(0.6f, 0.8f),
        private val text: String? = "ok",
    ) : LlamaNative {
        var loads = 0
        val released = mutableListOf<Long>()
        override fun isAvailable() = available
        override fun load(modelPath: String): Long { loads++; return handle }
        override fun embed(handle: Long, text: String) = vector
        override fun generate(handle: Long, prompt: String) = text
        override fun generateWithGrammar(handle: Long, prompt: String, grammar: String) = text
        override fun release(handle: Long) { released += handle }
    }

    private fun failure(block: () -> Unit): Throwable = runCatching(block).exceptionOrNull() ?: error("expected a failure")

    @Test fun loadFailureThrowsConfigNotCrash() = runTest {
        // Native library absent: typed config failure, nothing loaded.
        val absent = FakeNative(available = false)
        assertTrue(failure { LlamaCppNativeProvider.embed("/m.gguf", listOf("a"), absent) } is ProviderConfigException)
        assertEquals(0, absent.loads)

        // Library present but the model can't load (corrupt file, out of memory): a 0 handle.
        val corrupt = FakeNative(handle = 0L)
        val e = failure { LlamaCppNativeProvider.embed("/m.gguf", listOf("a"), corrupt) }
        assertTrue(e is ProviderConfigException); assertEquals("On-device model couldn't load", e.message)
        assertTrue("a 0 handle is never released", corrupt.released.isEmpty())
        val g = runCatching { LlamaCppNativeProvider("/m.gguf", corrupt).generate("q") }.exceptionOrNull()
        assertTrue(g is ProviderConfigException); assertEquals("On-device model couldn't load", g!!.message)

        // Loaded, but embedding fails natively (null): typed failure and the handle is still released.
        val noVector = FakeNative(vector = null)
        assertTrue(failure { LlamaCppNativeProvider.embed("/m.gguf", listOf("a"), noVector) } is ProviderConfigException)
        assertEquals(listOf(7L), noVector.released)
        val empty = FakeNative(vector = FloatArray(0))
        assertTrue(failure { LlamaCppNativeProvider.embed("/m.gguf", listOf("a"), empty) } is ProviderConfigException)
        assertEquals(listOf(7L), empty.released)

        // Generation failure (null) is typed too, and released.
        val noText = FakeNative(text = null)
        assertTrue(runCatching { LlamaCppNativeProvider("/m.gguf", noText).generate("q") }.exceptionOrNull() is ProviderConfigException)
        assertEquals(listOf(7L), noText.released)

        // Happy path: one load per batch, one release, vectors in order.
        val ok = FakeNative()
        val out = LlamaCppNativeProvider.embed("/m.gguf", listOf("a", "b"), ok)
        assertEquals(2, out.size); assertEquals(1, ok.loads); assertEquals(listOf(7L), ok.released)
        assertEquals("ok", LlamaCppNativeProvider("/m.gguf", ok).generate("q"))
    }

    @Test fun nativeUnavailableMessageIsUserSafe() {
        val e = failure { LlamaCppNativeProvider.embed("/m.gguf", listOf("a"), FakeNative(available = false)) }
        assertEquals("On-device models need a 64-bit ARM phone.", e.message)
        listOf("llama", "jni", "apk", "native", "/m.gguf").forEach { assertFalse("leaks '$it'", e.message!!.lowercase().contains(it)) }
        // The real JVM test runtime has no libllama_jni: the default path is unavailable and typed, never a link error.
        assertFalse(LlamaCppNativeProvider.isNativeAvailable())
        val real = failure { LlamaCppNativeProvider.embed("/m.gguf", listOf("a")) }
        assertTrue(real is ProviderConfigException); assertEquals("On-device models need a 64-bit ARM phone.", real.message)
    }
}
