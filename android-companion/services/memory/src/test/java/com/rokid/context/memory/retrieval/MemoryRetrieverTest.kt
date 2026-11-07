package com.rokid.context.memory.retrieval

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.EmbeddingSpaces
import com.rokid.context.memory.Embedder
import com.rokid.context.memory.MemoryRepository
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.MemoryEntity
import com.rokid.context.memory.db.MemoryTier
import com.rokid.context.memory.db.ReviewState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class MemoryRetrieverTest {
    private val db = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    private val memories = MemoryRepository(db.memoryDao())
    private val spaces = EmbeddingSpaces(db.embeddingDao())
    private var queryVector: FloatArray? = floatArrayOf(0f, 1f)
    private val embedder = object : Embedder {
        override val fingerprint = "fp"
        override suspend fun embedDocuments(texts: List<String>) = texts.map { floatArrayOf(1f, 0f) }
        override suspend fun embedQuery(text: String): FloatArray = queryVector ?: throw java.io.IOException("offline")
    }
    private val retriever = MemoryRetriever(
        LexicalRetriever(db.retrievalDao()),
        SemanticRetriever(spaces, BruteForceVectorIndex(db.embeddingDao())) { embedder },
        db.memoryViewDao(),
    )
    private val day = 86_400_000L
    private val now = 100 * day
    @After fun close() = db.close()

    private suspend fun mem(id: String, text: String, at: Long = now, conf: Double = 0.8) =
        memories.add(MemoryEntity(id, null, text, "note", "", MemoryTier.SHORT, conf, ReviewState.OK, false, at, false, ""))

    private suspend fun activeSpace(vararg vectors: Pair<String, FloatArray>) {
        val s = spaces.startBuilding("fp", "{}", 2, "[]", 1)
        vectors.forEach { (id, v) -> spaces.put(id, s.id, v) }
        assertTrue(spaces.swapIfComplete(s.id))
    }

    @Test fun modesUseTheirRetrievers() = runBlocking {
        mem("milk", "milk carton"); mem("keys", "house keys")
        activeSpace("milk" to floatArrayOf(1f, 0f), "keys" to floatArrayOf(0f, 1f))
        val lexical = retriever.retrieve("milk", RetrievalSettings(mode = RetrievalMode.LEXICAL), now)
        assertEquals(listOf("milk"), lexical.hits.map { it.memory.id }); assertEquals(RetrievalMode.LEXICAL, lexical.modeUsed)
        val semantic = retriever.retrieve("milk", RetrievalSettings(mode = RetrievalMode.SEMANTIC), now) // query vector points at "keys"
        assertEquals("keys", semantic.hits.first().memory.id); assertEquals(RetrievalMode.SEMANTIC, semantic.modeUsed)
        val hybrid = retriever.retrieve("milk", RetrievalSettings(mode = RetrievalMode.HYBRID, fusion = FusionStrategy.Rrf()), now)
        assertEquals(setOf("milk", "keys"), hybrid.hits.map { it.memory.id }.toSet()); assertEquals(RetrievalMode.HYBRID, hybrid.modeUsed)
        assertTrue(hybrid.hits.first { it.memory.id == "milk" }.lexical!! > 0)
    }

    @Test fun semanticFailureFallsBackToLexical() = runBlocking {
        mem("milk", "milk carton"); activeSpace("milk" to floatArrayOf(1f, 0f))
        queryVector = null
        val r = retriever.retrieve("milk", RetrievalSettings(mode = RetrievalMode.SEMANTIC), now)
        assertEquals(RetrievalMode.LEXICAL, r.modeUsed); assertTrue(r.semanticUnavailable)
        assertEquals(listOf("milk"), r.hits.map { it.memory.id })
        queryVector = floatArrayOf(1f, 0f, 0f) // wrong dimension for the space: also unavailable, never mixed
        assertTrue(retriever.retrieve("milk", RetrievalSettings(mode = RetrievalMode.HYBRID), now).semanticUnavailable)
    }

    @Test fun noActiveSpaceMeansLexicalOnly() = runBlocking {
        mem("milk", "milk carton")
        spaces.startBuilding("fp", "{}", 2, "[]", 1) // BUILDING is never read
        val r = retriever.retrieve("milk", RetrievalSettings(mode = RetrievalMode.HYBRID), now)
        assertEquals(RetrievalMode.LEXICAL, r.modeUsed); assertTrue(r.semanticUnavailable)
    }

    /** 2026-11-08: semantic was off on 200 of 200 live queries with no reason anywhere; every degrade now names its cause. */
    @Test fun everyDegradeSaysWhy() = runBlocking {
        mem("milk", "milk carton")
        val none = retriever.retrieve("milk", RetrievalSettings(mode = RetrievalMode.HYBRID), now)
        assertTrue(none.semanticUnavailable); assertEquals(SemanticOff.NO_INDEX, none.semanticOff)
        assertTrue(none.semanticOff!!.text, none.semanticOff!!.text.contains("Embeddings"))
        val building = spaces.startBuilding("fp", "{}", 2, "[]", 1)
        assertEquals(SemanticOff.INDEX_BUILDING, retriever.retrieve("milk", RetrievalSettings(mode = RetrievalMode.SEMANTIC), now).semanticOff)
        spaces.put("milk", building.id, floatArrayOf(1f, 0f)); assertTrue(spaces.swapIfComplete(building.id))
        queryVector = null
        assertEquals(SemanticOff.QUERY_FAILED, retriever.retrieve("milk", RetrievalSettings(mode = RetrievalMode.HYBRID), now).semanticOff)
        queryVector = floatArrayOf(1f, 0f)
        val ok = retriever.retrieve("milk", RetrievalSettings(mode = RetrievalMode.HYBRID), now)
        assertFalse(ok.semanticUnavailable); assertEquals(null, ok.semanticOff)
        assertEquals("lexical is a choice, not a degrade", null, retriever.retrieve("milk", RetrievalSettings(mode = RetrievalMode.LEXICAL), now).semanticOff)
        val moved = MemoryRetriever(LexicalRetriever(db.retrievalDao()), SemanticRetriever(spaces, BruteForceVectorIndex(db.embeddingDao())) { null }, db.memoryViewDao())
        assertEquals(SemanticOff.MODEL_CHANGED, moved.retrieve("milk", RetrievalSettings(mode = RetrievalMode.HYBRID), now).semanticOff)
        spaces.markStale(spaces.current()!!.id)
        assertEquals(SemanticOff.INDEX_STALE, retriever.retrieve("milk", RetrievalSettings(mode = RetrievalMode.HYBRID), now).semanticOff)
    }

    @Test fun tuningTopKMinConfidenceAndRecency() = runBlocking {
        mem("old", "milk", at = now - 30 * day); mem("new", "milk", at = now); mem("weak", "milk", conf = 0.2)
        val r = retriever.retrieve("milk", RetrievalSettings(mode = RetrievalMode.LEXICAL, minConfidence = 0.5), now)
        assertEquals(listOf("new", "old"), r.hits.map { it.memory.id })
        assertEquals(1, retriever.retrieve("milk", RetrievalSettings(mode = RetrievalMode.LEXICAL, topK = 1), now).hits.size)
        val flat = retriever.retrieve("milk", RetrievalSettings(mode = RetrievalMode.LEXICAL, recencyBoost = false, minConfidence = 0.5), now)
        assertEquals(flat.hits[0].fused, flat.hits[0].score, 0.0)
        assertEquals(0.75, MemoryRetriever.recency(now - 3 * day, now, RetrievalSettings()), 1e-9)
        assertFalse(r.semanticUnavailable)
    }

    @Test fun poolOverrideGoesPastTheTopKClampOfTwenty() = runBlocking {
        (0 until 30).forEach { mem("m$it", "milk", at = now - it * day) }
        val s = RetrievalSettings(mode = RetrievalMode.LEXICAL, topK = 50)
        assertEquals(20, retriever.retrieve("milk", s, now).hits.size) // UI settings stay clamped
        val wide = retriever.retrieve("milk", s, now, poolOverride = 60)
        assertEquals(30, wide.hits.size)
        assertEquals("m24", wide.hits[24].memory.id) // ranked 25th by recency
    }

    @Test fun poolOverrideIsBoundedAt200() = runBlocking {
        (0 until 250).forEach { mem("n$it", "milk $it", at = now - it * 60_000L) }
        val s = RetrievalSettings(mode = RetrievalMode.LEXICAL, topK = 8)
        assertEquals(200, retriever.retrieve("milk", s, now, poolOverride = 9_999).hits.size)
        assertEquals(MemoryRetriever.MAX_POOL_OVERRIDE, 200)
    }

    // ---- notAfterMs (S34): the time bound applies BEFORE every limit ----

    @Test fun manyNewerMatchesDontHideOlder() = runBlocking {
        mem("old", "milk carton old", at = 10 * day)
        repeat(60) { mem("new$it", "milk milk milk carton $it", at = 90 * day) } // denser: they fill the lexical pool of 40 on their own
        val unbound = retriever.retrieve("milk", RetrievalSettings(mode = RetrievalMode.LEXICAL), now)
        assertFalse("old" in unbound.hits.map { it.memory.id })           // today's behaviour: crowded out
        val bound = retriever.retrieve("milk", RetrievalSettings(mode = RetrievalMode.LEXICAL), now, notAfterMs = 20 * day)
        assertEquals(listOf("old"), bound.hits.map { it.memory.id })
    }

    @Test fun notAfterAppliedBeforeLimits() = runBlocking {
        mem("old", "milk carton old", at = 10 * day)
        repeat(60) { mem("new$it", "milk carton $it", at = 90 * day) }
        // The query vector (0,1) points at the new memories; the old one is orthogonal and falls outside the semantic candidate pool of 40.
        activeSpace("old" to floatArrayOf(1f, 0f), *(0 until 60).map { "new$it" to floatArrayOf(0f, 1f) }.toTypedArray())
        val s = RetrievalSettings(mode = RetrievalMode.SEMANTIC, topK = 1)
        assertEquals(1, retriever.retrieve("milk", s, now).hits.size)
        assertFalse("old" == retriever.retrieve("milk", s, now).hits.single().memory.id)
        val bound = retriever.retrieve("milk", s, now, notAfterMs = 20 * day)
        assertEquals(listOf("old"), bound.hits.map { it.memory.id })
        assertEquals(RetrievalMode.SEMANTIC, bound.modeUsed)
        // topK too: with 3 old and 60 new, the bounded top-2 are old ones.
        mem("old2", "milk carton old two", at = 11 * day); mem("old3", "milk carton old three", at = 12 * day)
        assertTrue(retriever.retrieve("milk", RetrievalSettings(mode = RetrievalMode.LEXICAL, topK = 2), now, notAfterMs = 20 * day).hits.all { it.memory.createdAt <= 20 * day })
    }

    @Test fun notAfterIsInclusiveAndNullIsToday() = runBlocking {
        mem("a", "milk carton a", at = 10 * day); mem("b", "milk carton b", at = 20 * day); mem("c", "milk carton c", at = 21 * day)
        val s = RetrievalSettings(mode = RetrievalMode.LEXICAL)
        assertEquals(setOf("a", "b"), retriever.retrieve("milk", s, now, notAfterMs = 20 * day).hits.map { it.memory.id }.toSet())
        assertEquals(setOf("a", "b", "c"), retriever.retrieve("milk", s, now, notAfterMs = null).hits.map { it.memory.id }.toSet())
    }

    @Test fun fallbackPoolBounded() = runBlocking {
        repeat(5) { mem("old$it", "x $it", at = (10 + it) * day) }
        repeat(40) { mem("new$it", "y $it", at = 90 * day + it) }
        assertEquals(30, memories.recent(30, null).size)
        assertTrue(memories.recent(30, null).all { it.id.startsWith("new") })
        val bound = memories.recent(30, 20 * day)
        assertEquals(setOf("old0", "old1", "old2", "old3", "old4"), bound.map { it.id }.toSet())
        assertEquals(memories.recent(30).map { it.id }, memories.recent(30, null).map { it.id })
    }
}
