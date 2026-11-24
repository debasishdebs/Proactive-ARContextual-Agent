package com.rokid.context.memory.retrieval

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.Embedder
import com.rokid.context.memory.EmbeddingSpaces
import com.rokid.context.memory.MemoryRepository
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.Demotion
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
 * Retention v3 WP3 golden world. Quick recall = 48 h. "demoted" = SHORT, older than the window, and no text vector in the active space.
 *   in      SHORT, 1 h old, vector                      -> in the window: default retrieval, unchanged
 *   old-vec SHORT, 10 d old, STILL has its vector       -> retention has not demoted it yet: unchanged (no behaviour change before WP1 runs)
 *   dem     SHORT, 10 d old, no vector                  -> demoted: out of default retrieval, in time-window questions, provenance and detail
 *   dem2    SHORT, 20 d old, no vector (cited by a nudge)-> same; a nudge / answer / Moment reads it by id, which never filters
 *   cap     LONG caption, 11 d old, no vector           -> LONG is never demoted
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class DemotedRetrievalTest {
    private val db = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    private val memories = MemoryRepository(db.memoryDao())
    private val spaces = EmbeddingSpaces(db.embeddingDao())
    private val embedder = object : Embedder {
        override val fingerprint = "fp"
        override suspend fun embedDocuments(texts: List<String>) = texts.map { floatArrayOf(1f, 0f) }
        override suspend fun embedQuery(text: String): FloatArray = floatArrayOf(1f, 0f)
    }
    private val hour = 3_600_000L
    private val day = 24 * hour
    private val now = 100 * day
    private val recall = RecallWindow.hours { 48 }
    private val retriever = MemoryRetriever(
        LexicalRetriever(db.retrievalDao()), SemanticRetriever(spaces, BruteForceVectorIndex(db.embeddingDao())) { embedder }, db.memoryViewDao(), recall,
    )
    private val plain = MemoryRetriever(LexicalRetriever(db.retrievalDao()), SemanticRetriever(spaces, BruteForceVectorIndex(db.embeddingDao())) { embedder }, db.memoryViewDao())
    @After fun close() = db.close()

    private suspend fun mem(id: String, text: String, at: Long, tier: MemoryTier = MemoryTier.SHORT, type: String = "object") =
        memories.add(MemoryEntity(id, null, text, type, "", tier, 0.8, ReviewState.OK, false, at, false, ""))

    private suspend fun world(withSpace: Boolean = true) {
        mem("in", "walrus on the pier", now - hour)
        mem("old-vec", "walrus statue", now - 10 * day)
        mem("dem", "walrus mural", now - 10 * day)
        mem("dem2", "walrus keychain", now - 20 * day)
        mem("cap", "walrus photo caption", now - 11 * day, MemoryTier.LONG, "caption")
        if (withSpace) { // an ACTIVE space written directly: a real swap needs full coverage, and the demoted facts have no vector on purpose
            db.openHelper.writableDatabase.execSQL("INSERT INTO embedding_space (id, fingerprint, configJson, dim, state, canaryJson, createdAt) VALUES ('sp1', 'fp', '{}', 2, 'ACTIVE', '[]', 1)")
            listOf("in", "old-vec").forEach { spaces.put(it, "sp1", floatArrayOf(1f, 0f)) }
        }
    }

    private fun ids(r: RetrievalResult) = r.hits.map { it.memory.id }.toSet()
    private val s = RetrievalSettings(mode = RetrievalMode.HYBRID, topK = 20)

    @Test fun defaultRetrievalDropsOnlyTheDemotedFacts() = runBlocking {
        world()
        for (mode in listOf(RetrievalMode.LEXICAL, RetrievalMode.SEMANTIC, RetrievalMode.HYBRID)) {
            val r = ids(retriever.retrieve("walrus", s.copy(mode = mode), now))
            assertTrue(mode.name, "dem" !in r && "dem2" !in r)
            assertTrue(mode.name, "in" in r && "old-vec" in r)
            if (mode != RetrievalMode.SEMANTIC) assertTrue(mode.name, "cap" in r) // a caption is LONG: never demoted (SEMANTIC: it has no vector, so it is not a semantic candidate, as before)
        }
    }

    @Test fun noRecallWindowIsTodaysBehaviourByteForByte() = runBlocking {
        world()
        assertEquals(setOf("in", "old-vec", "dem", "dem2", "cap"), ids(plain.retrieve("walrus", s.copy(mode = RetrievalMode.LEXICAL), now)))
        // and inside the window nothing differs between the two retrievers
        val a = retriever.retrieve("pier", s, now).hits.map { it.memory.id to it.score }
        val b = plain.retrieve("pier", s, now).hits.map { it.memory.id to it.score }
        assertEquals(b, a)
    }

    @Test fun aTimeWindowQuestionReachesDemotedFacts() = runBlocking {
        world()
        val w = EventWindow(now - 11 * day, now - 9 * day) // "what did I see around 10 days ago"
        val r = ids(retriever.retrieveWindowed("walrus", s.copy(mode = RetrievalMode.LEXICAL), now, null, null, w))
        assertEquals(setOf("old-vec", "dem", "cap"), r)
        assertEquals(setOf("dem2"), ids(retriever.retrieveWindowed("walrus", s.copy(mode = RetrievalMode.HYBRID), now, null, null, EventWindow(now - 21 * day, now - 19 * day))))
    }

    @Test fun citedAndProvenanceReadsByIdNeverFilter() = runBlocking {
        world()
        // a nudge / answer / card / Moment cites ids: byIds is the read path of provenance, and the demoted fact is still there
        assertEquals(setOf("dem", "dem2"), db.memoryViewDao().byIds(listOf("dem", "dem2", "gone")).map { it.id }.toSet())
        assertEquals(setOf("dem", "dem2"), db.retrievalDao().demotedAmong(listOf("in", "old-vec", "dem", "dem2", "cap"), now - 48 * hour).toSet())
        assertEquals(2, db.retrievalDao().countDemoted(now - 48 * hour))
        // a hub "today" list leaves them out, the NO_WINDOW sentinel leaves everything in
        assertEquals(listOf("in", "old-vec", "cap"), db.retrievalDao().recentVisible(10, Long.MAX_VALUE, now - 48 * hour).map { it.id })
        assertEquals(5, db.retrievalDao().recentVisible(10, Long.MAX_VALUE, Demotion.NO_WINDOW).size)
    }

    @Test fun theWindowSettingMovesTheLine() = runBlocking {
        world()
        val wide = MemoryRetriever(LexicalRetriever(db.retrievalDao()), SemanticRetriever(spaces, BruteForceVectorIndex(db.embeddingDao())) { embedder }, db.memoryViewDao(), RecallWindow.hours { 24 * 15 })
        assertEquals(setOf("in", "old-vec", "dem", "cap"), ids(wide.retrieve("walrus", s.copy(mode = RetrievalMode.LEXICAL), now))) // dem2 (20 d) is the only one past 15 d
    }

    @Test fun withoutAnActiveSpaceNothingIsDemoted() = runBlocking {
        world(withSpace = false) // lexical-only use: no vectors exist, so "no vector" says nothing
        assertEquals(setOf("in", "old-vec", "dem", "dem2", "cap"), ids(retriever.retrieve("walrus", s.copy(mode = RetrievalMode.LEXICAL), now)))
        assertEquals(0, db.retrievalDao().countDemoted(now - 48 * hour))
    }

    @Test fun theAsOfCutoffStillAppliesToTheDefaultPool() = runBlocking {
        world()
        val r = ids(retriever.retrieve("walrus", s.copy(mode = RetrievalMode.LEXICAL), now, null, now - 5 * day))
        assertEquals(setOf("cap", "old-vec"), r) // created at or before 5 days ago, and not demoted
    }
}
