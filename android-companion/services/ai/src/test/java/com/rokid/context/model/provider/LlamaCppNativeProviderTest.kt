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
        /** The JNI contract: an embed load also creates the embed context, and a failure there (out of memory) is a 0 handle. */
        private val embedContextFails: Boolean = false,
    ) : LlamaNative {
        var loads = 0
        val loadedForEmbedding = mutableListOf<Boolean>()
        val released = mutableListOf<Long>()
        val normalizeSeen = mutableListOf<Boolean>()
        override fun isAvailable() = available
        override fun load(modelPath: String, forEmbedding: Boolean): Long {
            loads++; loadedForEmbedding += forEmbedding
            return if (forEmbedding && embedContextFails) 0L else handle
        }
        override fun embed(handle: Long, text: String, normalize: Boolean) = vector.also { normalizeSeen += normalize }
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

    @Test fun normalizeFlagPassedThrough() {
        val bge = FakeNative(); LlamaCppNativeProvider.embed("/m.gguf", listOf("a", "b"), bge)
        assertEquals(listOf(true, true), bge.normalizeSeen) // the default stays L2-normalised
        val nomic = FakeNative(); LlamaCppNativeProvider.embed("/m.gguf", listOf("a"), nomic, normalize = false)
        assertEquals(listOf(false), nomic.normalizeSeen)
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

    /** Spec section 6: the page asks for a re-download after two consecutive load failures of one model file. */
    @Test fun consecutiveLoadFailuresAreCountedPerModelAndResetOnSuccess() {
        val counter = LocalLoadFailures()
        val corrupt = FakeNative(handle = 0L)
        repeat(2) { failure { LlamaCppNativeProvider.embed("/a.gguf", listOf("x"), corrupt, counter) } }
        failure { LlamaCppNativeProvider.embed("/b.gguf", listOf("x"), FakeNative(available = false), counter) }
        assertEquals(2, counter.consecutive("/a.gguf"))
        assertEquals(1, counter.consecutive("/b.gguf"))

        // A load that works resets that model only; an embed failure after a good load is not a load failure.
        LlamaCppNativeProvider.embed("/a.gguf", listOf("x"), FakeNative(), counter)
        assertEquals(0, counter.consecutive("/a.gguf"))
        failure { LlamaCppNativeProvider.embed("/a.gguf", listOf("x"), FakeNative(vector = null), counter) }
        assertEquals(0, counter.consecutive("/a.gguf"))
        assertEquals(1, counter.consecutive("/b.gguf"))

        counter.reset("/b.gguf")
        assertEquals(0, counter.consecutive("/b.gguf"))
    }

    /** Spec section 6: loaded on first use, kept between calls, released after the idle delay; a failed embed frees it at once. */
    @Test fun embedModelStaysLoadedUntilIdle() {
        val timers = mutableListOf<Pair<Long, () -> Unit>>()
        val cache = EmbedModelCache(idleMs = 60_000) { delay, task -> timers += delay to task; { timers.removeAll { it.second === task } } }
        val ok = FakeNative()
        LlamaCppNativeProvider.embed("/m.gguf", listOf("a"), ok, cache = cache)
        LlamaCppNativeProvider.embed("/m.gguf", listOf("b", "c"), ok, cache = cache)
        assertEquals("one load for both calls", 1, ok.loads)
        assertTrue("still loaded between calls", ok.released.isEmpty())
        assertEquals("one idle timer armed, 60 s", listOf(60_000L), timers.map { it.first })

        timers.single().second()
        assertEquals("released when idle", listOf(7L), ok.released)
        LlamaCppNativeProvider.embed("/m.gguf", listOf("d"), ok, cache = cache)
        assertEquals("loaded again on next use", 2, ok.loads)

        // Another model file replaces the loaded one.
        LlamaCppNativeProvider.embed("/other.gguf", listOf("e"), ok, cache = cache)
        assertEquals(3, ok.loads); assertEquals(listOf(7L, 7L), ok.released)

        // A native embed failure releases the model now rather than keep a possibly broken context.
        val broken = FakeNative(vector = null)
        assertTrue(failure { LlamaCppNativeProvider.embed("/m.gguf", listOf("x"), broken, cache = cache) } is ProviderConfigException)
        assertEquals(listOf(7L), broken.released)
    }

    /** Final review I2: out of memory while creating the embed context is a load failure, so the page's banner and row show. */
    @Test fun embedContextCreationFailureCountsAsALoadFailure() {
        val counter = LocalLoadFailures()
        val oom = FakeNative(embedContextFails = true)
        repeat(2) {
            val e = failure { LlamaCppNativeProvider.embed("/a.gguf", listOf("x"), oom, counter) }
            assertEquals("On-device model couldn't load", e.message)
        }
        assertEquals("two in a row: the page asks for a re-download", 2, counter.consecutive("/a.gguf"))
        assertEquals("the embed path loads with its context", listOf(true, true), oom.loadedForEmbedding)
        assertTrue("a 0 handle is never released", oom.released.isEmpty())

        // Through the idle cache too.
        val cache = EmbedModelCache(idleMs = 60_000) { _, _ -> {} }
        failure { LlamaCppNativeProvider.embed("/c.gguf", listOf("x"), oom, counter, cache) }
        assertEquals(1, counter.consecutive("/c.gguf"))

        // Generation never needs the embed context.
        val gen = FakeNative(embedContextFails = true)
        kotlinx.coroutines.runBlocking { assertEquals("ok", LlamaCppNativeProvider("/a.gguf", gen).generate("q")) }
        assertEquals(listOf(false), gen.loadedForEmbedding)
    }
}
