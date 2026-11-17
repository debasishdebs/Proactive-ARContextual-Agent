package com.rokid.context.memory.retrieval

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.Embedder
import com.rokid.context.memory.EmbeddingSpaces
import com.rokid.context.memory.MemoryRepository
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.FrameMetaEntity
import com.rokid.context.memory.db.MemoryEntity
import com.rokid.context.memory.db.MemoryTier
import com.rokid.context.memory.db.ReviewState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Retrieval-quality spec sections 4 and 7: the event-time window is applied in the lexical query and the vector scan BEFORE the pool cut, null means
 * today's behaviour, and a memory with no vector yet competes at its full lexical weight (R4).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class EventWindowRetrievalTest {
    private val db = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    private val memories = MemoryRepository(db.memoryDao())
    private val spaces = EmbeddingSpaces(db.embeddingDao())
    private val embedder = object : Embedder {
        override val fingerprint = "fp"
        override suspend fun embedDocuments(texts: List<String>) = texts.map { floatArrayOf(1f, 0f) }
        override suspend fun embedQuery(text: String): FloatArray = floatArrayOf(1f, 0f)
    }
    private val retriever = MemoryRetriever(
        LexicalRetriever(db.retrievalDao()), SemanticRetriever(spaces, BruteForceVectorIndex(db.embeddingDao())) { embedder }, db.memoryViewDao(),
    )
    private val day = 86_400_000L
    private val now = 100 * day
    @After fun close() = db.close()

    private suspend fun mem(id: String, text: String, at: Long, frame: String? = null) =
        memories.add(MemoryEntity(id, frame, text, "note", "", MemoryTier.SHORT, 0.8, ReviewState.OK, false, at, false, ""))

    private suspend fun space(vararg vectors: Pair<String, FloatArray>) {
        val s = spaces.startBuilding("fp", "{}", 2, "[]", 1)
        vectors.forEach { (id, v) -> spaces.put(id, s.id, v) }
        assertTrue(spaces.swapIfComplete(s.id))
    }

    private fun ids(r: RetrievalResult) = r.hits.map { it.memory.id }.toSet()

    @Test fun theWindowBoundsLexicalAndSemanticCandidatesByEventTime() = runBlocking {
        mem("d1", "milk carton", now - 3 * day); mem("d2", "milk bottle", now - 2 * day); mem("d3", "milk jug", now - day)
        space("d1" to floatArrayOf(1f, 0f), "d2" to floatArrayOf(1f, 0f), "d3" to floatArrayOf(1f, 0f))
        val w = EventWindow(now - 2 * day - 1, now - day - 1) // exactly d2
        for (mode in listOf(RetrievalMode.LEXICAL, RetrievalMode.SEMANTIC, RetrievalMode.HYBRID))
            assertEquals(mode.name, setOf("d2"), ids(retriever.retrieveWindowed("milk", RetrievalSettings(mode = mode), now, null, null, w)))
        assertEquals(setOf("d1", "d2", "d3"), ids(retriever.retrieve("milk", RetrievalSettings(mode = RetrievalMode.HYBRID), now)))
        // half-open: the upper bound itself is outside
        assertEquals(emptySet<String>(), ids(retriever.retrieveWindowed("milk", RetrievalSettings(mode = RetrievalMode.LEXICAL), now, null, null, EventWindow(now - 3 * day - 5, now - 3 * day))))
        assertEquals(setOf("d1"), ids(retriever.retrieveWindowed("milk", RetrievalSettings(mode = RetrievalMode.LEXICAL), now, null, null, EventWindow(now - 3 * day, now - 3 * day + 1))))
    }

    @Test fun theWindowUsesTheCaptureTimeNotTheWriteTime() = runBlocking {
        // written now, but the photo was taken 5 days ago
        db.frameMetaDao().upsert(FrameMetaEntity("f1", null, null, "UNKNOWN", false, null, null, null, null, null, null, null, null, null, now, capturedAtMs = now - 5 * day))
        mem("late", "milk carton", now, frame = "f1"); mem("plain", "milk bottle", now - 5 * day)
        val w = EventWindow(now - 5 * day - 1000, now - 5 * day + 1000)
        assertEquals(setOf("late", "plain"), ids(retriever.retrieveWindowed("milk", RetrievalSettings(mode = RetrievalMode.LEXICAL), now, null, null, w)))
        assertEquals(emptySet<String>(), ids(retriever.retrieveWindowed("milk", RetrievalSettings(mode = RetrievalMode.LEXICAL), now, null, null, EventWindow(now - 1000, now + 1000))))
        assertEquals(mapOf("late" to now - 5 * day, "plain" to now - 5 * day), db.retrievalDao().eventTimes(listOf("late", "plain")).associate { it.id to it.at })
    }

    @Test fun theWindowIsAppliedBeforeThePoolCut() = runBlocking {
        // 60 newer memories match "milk" better than the one old one; without the bound before the cut the old one never reaches the pool of 40
        repeat(60) { mem("n$it", "milk milk milk milk", now - day + it) }
        mem("old", "milk", now - 10 * day)
        val s = RetrievalSettings(mode = RetrievalMode.LEXICAL, topK = 8)
        assertTrue("old" !in ids(retriever.retrieve("milk", s, now)))
        assertEquals(setOf("old"), ids(retriever.retrieveWindowed("milk", s, now, null, null, EventWindow(now - 11 * day, now - 9 * day))))
    }

    @Test fun theDebugCutoffStillAppliesInsideAWindow() = runBlocking {
        mem("a", "milk", now - 2 * day); mem("b", "milk", now - day)
        val w = EventWindow(now - 3 * day, now)
        assertEquals(setOf("a"), ids(retriever.retrieveWindowed("milk", RetrievalSettings(mode = RetrievalMode.LEXICAL), now, null, now - day - 1, w)))
    }

    /** R4: after the index paused, new memories have no vector: "fresh" has the better lexical match and must beat the embedded "stale". */
    @Test fun aMemoryWithNoVectorYetCompetesAtFullLexicalWeight() = runBlocking {
        mem("stale", "milk", now - day); mem("noise", "carton", now - day)
        space("stale" to floatArrayOf(1f, 0f), "noise" to floatArrayOf(1f, 0f))
        mem("fresh", "milk milk milk milk milk", now - day) // written after the index paused: no vector
        val r = retriever.retrieve("milk", RetrievalSettings(mode = RetrievalMode.HYBRID, topK = 8, recencyBoost = false), now)
        assertEquals("fresh", r.hits.first().memory.id)
        assertEquals(1.0, r.hits.first().fused, 1e-9)
    }

    @Test fun withFullCoverageNothingChanges() = runBlocking {
        mem("a", "milk milk milk", now - day); mem("b", "milk", now - day)
        space("a" to floatArrayOf(1f, 0f), "b" to floatArrayOf(1f, 0f))
        val s = RetrievalSettings(mode = RetrievalMode.HYBRID, recencyBoost = false)
        val r = retriever.retrieve("milk", s, now)
        // an index that cannot say which ids have vectors scores exactly as before the fix
        val brute = BruteForceVectorIndex(db.embeddingDao())
        val old = MemoryRetriever(LexicalRetriever(db.retrievalDao()), SemanticRetriever(spaces, object : VectorIndex {
            override suspend fun search(spaceId: String, query: FloatArray, limit: Int, minConfidence: Double) = brute.search(spaceId, query, limit, minConfidence)
        }) { embedder }, db.memoryViewDao())
        assertEquals(old.retrieve("milk", s, now).hits.map { it.memory.id to it.fused }, r.hits.map { it.memory.id to it.fused })
    }
}
