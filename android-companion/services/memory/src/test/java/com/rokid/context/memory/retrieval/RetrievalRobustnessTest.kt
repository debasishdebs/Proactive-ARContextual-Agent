package com.rokid.context.memory.retrieval

import android.database.sqlite.SQLiteException
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.EmbeddingSpaces
import com.rokid.context.memory.Embedder
import com.rokid.context.memory.MemoryRepository
import com.rokid.context.memory.SpaceEmbedderProvider
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.MemoryEntity
import com.rokid.context.memory.db.MemoryTier
import com.rokid.context.memory.db.ReviewState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.cancellation.CancellationException

/** Fix round 1: off-Main work, R-51 fallback on every semantic failure, unusable query vectors, minConfidence before the pool cut. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RetrievalRobustnessTest {
    private val db = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    private val memories = MemoryRepository(db.memoryDao())
    private val spaces = EmbeddingSpaces(db.embeddingDao())
    private val onMain = CopyOnWriteArrayList<Boolean>()
    private var queryVector = floatArrayOf(1f, 0f)
    private val embedder = object : Embedder {
        override val fingerprint = "fp"
        override suspend fun embedDocuments(texts: List<String>) = texts.map { floatArrayOf(1f, 0f) }
        override suspend fun embedQuery(text: String): FloatArray { onMain += Looper.getMainLooper().isCurrentThread; return queryVector }
    }
    private var provider = SpaceEmbedderProvider { onMain += Looper.getMainLooper().isCurrentThread; embedder }
    private var index: VectorIndex = BruteForceVectorIndex(db.embeddingDao())
    private fun retriever() = MemoryRetriever(LexicalRetriever(db.retrievalDao()), SemanticRetriever(spaces, index, provider), db.memoryViewDao())
    private val now = 100 * 86_400_000L
    @After fun close() = db.close()

    private suspend fun mem(id: String, text: String, conf: Double = 0.8) =
        memories.add(MemoryEntity(id, null, text, "note", "", MemoryTier.SHORT, conf, ReviewState.OK, false, now, false, ""))

    private suspend fun activeSpace(vararg vectors: Pair<String, FloatArray>) {
        val s = spaces.startBuilding("fp", "{}", 2, "[]", 1)
        vectors.forEach { (id, v) -> spaces.put(id, s.id, v) }
        assertTrue(spaces.swapIfComplete(s.id))
    }

    private suspend fun milkWithSpace() { mem("milk", "milk carton"); activeSpace("milk" to floatArrayOf(1f, 0f)) }

    private suspend fun assertLexicalFallback(mode: RetrievalMode = RetrievalMode.SEMANTIC) {
        val r = retriever().retrieve("milk", RetrievalSettings(mode = mode), now)
        assertEquals(RetrievalMode.LEXICAL, r.modeUsed); assertTrue(r.semanticUnavailable)
        assertEquals(listOf("milk"), r.hits.map { it.memory.id })
    }

    @Test fun retrieveCalledOnMainDoesItsWorkOffMain() = runBlocking {
        assertTrue(Looper.getMainLooper().isCurrentThread) // Robolectric runs the test (and this runBlocking) on Main
        milkWithSpace()
        assertEquals(RetrievalMode.HYBRID, retriever().retrieve("milk", RetrievalSettings(), now).modeUsed)
        assertEquals(listOf(false, false), onMain.toList()) // forSpace (vault/Keystore read) and embedQuery
    }

    @Test fun embedderLookupFailureFallsBackToLexical() = runBlocking {
        milkWithSpace()
        provider = SpaceEmbedderProvider { throw IllegalStateException("keystore unavailable") }
        assertLexicalFallback(); assertLexicalFallback(RetrievalMode.HYBRID)
    }

    @Test fun indexFailureFallsBackToLexical() = runBlocking {
        milkWithSpace()
        index = object : VectorIndex {
            override suspend fun search(spaceId: String, query: FloatArray, limit: Int, minConfidence: Double): List<Hit> = throw SQLiteException("disk I/O")
        }
        assertLexicalFallback(); assertLexicalFallback(RetrievalMode.HYBRID)
    }

    @Test fun cancellationIsNeverTurnedIntoAFallback() = runBlocking {
        milkWithSpace()
        provider = SpaceEmbedderProvider { throw CancellationException("cancelled") }
        try { retriever().retrieve("milk", RetrievalSettings(mode = RetrievalMode.SEMANTIC), now); fail("swallowed") } catch (_: CancellationException) {}
    }

    @Test fun zeroOrNonFiniteQueryVectorFallsBackToLexical() = runBlocking {
        milkWithSpace()
        queryVector = floatArrayOf(0f, 0f); assertLexicalFallback()
        queryVector = floatArrayOf(Float.NaN, 1f); assertLexicalFallback()
        queryVector = floatArrayOf(Float.POSITIVE_INFINITY, 0f); assertLexicalFallback()
    }

    @Test fun lexicalMinConfidenceAppliesBeforeThePoolCut() = runBlocking {
        // 45 denser low-confidence matches outrank the one confident memory and would fill the pool of 40.
        repeat(45) { mem("weak$it", "milk milk", conf = 0.2) }
        mem("strong", "milk and a long tail of other words in this note", conf = 0.9)
        val r = retriever().retrieve("milk", RetrievalSettings(mode = RetrievalMode.LEXICAL, topK = 1, minConfidence = 0.5), now)
        assertEquals(listOf("strong"), r.hits.map { it.memory.id })
    }

    @Test fun semanticMinConfidenceAppliesBeforeThePoolCut() = runBlocking {
        repeat(45) { mem("weak$it", "weak note $it", conf = 0.2) }
        mem("strong", "strong note", conf = 0.9)
        activeSpace(*(Array(45) { "weak$it" to floatArrayOf(1f, 0f) } + ("strong" to floatArrayOf(0.6f, 0.8f))))
        val r = retriever().retrieve("anything", RetrievalSettings(mode = RetrievalMode.SEMANTIC, topK = 1, minConfidence = 0.5), now)
        assertEquals(RetrievalMode.SEMANTIC, r.modeUsed); assertEquals(listOf("strong"), r.hits.map { it.memory.id })
    }
}
