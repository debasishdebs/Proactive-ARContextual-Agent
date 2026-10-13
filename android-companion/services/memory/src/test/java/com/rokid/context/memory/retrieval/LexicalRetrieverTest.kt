package com.rokid.context.memory.retrieval

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.MemoryRepository
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.MemoryEntity
import com.rokid.context.memory.db.MemoryTier
import com.rokid.context.memory.db.ReviewState
import android.database.Cursor
import android.database.CursorWrapper
import com.rokid.context.memory.db.RetrievalDao
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class LexicalRetrieverTest {
    private val db = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    private val memories = MemoryRepository(db.memoryDao())
    private val lexical = LexicalRetriever(db.retrievalDao())
    @After fun close() = db.close()

    private suspend fun mem(id: String, text: String, review: ReviewState = ReviewState.OK) =
        memories.add(MemoryEntity(id, null, text, "note", "", MemoryTier.SHORT, 0.8, review, false, 1, false, ""))

    @Test fun bm25RanksDenserShorterMatchesFirst() = runBlocking {
        mem("short", "milk milk carton")
        mem("long", "milk was mentioned once in a long note about the weekend trip to the hills with friends")
        mem("none", "keys on the hallway shelf")
        assertEquals(listOf("short", "long"), lexical.search("milk", 10).map { it.id })
    }

    @Test fun hindiQueryRanksTheDevanagariMemory() = runBlocking {
        mem("hi", "दूध फ्रिज में है")
        mem("en", "Milk is in the fridge")
        mem("mixed", "kal दूध खत्म हो गया")
        assertEquals(setOf("hi", "mixed"), lexical.search("दूध", 10).map { it.id }.toSet())
        assertEquals(listOf("en"), lexical.search("fridge", 10).map { it.id })
    }

    @Test fun longQuestionsStillMatch() = runBlocking {
        mem("m", "Milk carton nearly empty")
        val q = "can you please tell me where exactly did I leave the milk carton yesterday evening"
        assertEquals(listOf("m"), lexical.search(q, 10).map { it.id })
    }

    @Test fun reviewQueueAndInvalidatedMemoriesAreNeverRetrieved() = runBlocking {
        mem("ok", "milk"); mem("review", "milk", ReviewState.NEEDS_REVIEW); mem("gone", "milk", ReviewState.INVALIDATED)
        assertEquals(listOf("ok"), lexical.search("milk", 10).map { it.id })
        assertTrue(lexical.search("   ?!", 10).isEmpty())
    }

    @Test fun ftsSyntaxInUserTextNeverCrashesOrEscapesTheQuery() = runBlocking {
        mem("m", "milk carton")
        for (q in listOf("\"", "\"milk", "milk\" OR \"x", "milk*", "*", "-milk", "NOT milk", "milk AND", "OR", "NEAR(milk", "searchText:milk", "milk)", "'; DROP TABLE memory; --", "^milk", "milk\u0000")) {
            val ids = lexical.search(q, 10).map { it.id }
            assertTrue("$q -> $ids", ids.isEmpty() || ids == listOf("m"))
        }
        assertEquals(listOf("m"), lexical.search("\"milk\" OR NOT", 10).map { it.id })
        assertTrue(lexical.search("OR AND NOT", 10).isEmpty())
    }

    @Test fun theNewestBestMatchSurvivesMoreThanAThousandMatches() = runBlocking {
        // Every filler row matches "i" and "the"; an unranked candidate cut would keep only the oldest ones.
        repeat(1_500) { mem("f$it", "i think the weekend was fine number $it") }
        mem("milk", "milk carton is in the fridge")
        assertEquals("milk", lexical.search("where did i leave the milk", 10).first().id)
    }

    /** Bulk FTS-indexed memories (searchText given directly; the FTS triggers index it). */
    private fun bulk(n: Int, text: (Int) -> String) {
        val w = db.openHelper.writableDatabase
        w.beginTransaction()
        try {
            val st = w.compileStatement(
                "INSERT INTO memory (id, frameId, text, type, tags, tier, confidence, reviewState, trusted, createdAt, derived, searchText) " +
                    "VALUES (?, NULL, ?, 'note', '', 'SHORT', 0.8, 'OK', 0, 1, 0, ?)",
            )
            repeat(n) { val t = text(it); st.clearBindings(); st.bindString(1, "b${it.toString().padStart(5, '0')}"); st.bindString(2, t); st.bindString(3, t); st.executeInsert() }
            w.setTransactionSuccessful()
        } finally { w.endTransaction() }
    }

    private class SpyDao(private val real: RetrievalDao) : RetrievalDao by real {
        var opens = 0; var rowsRead = 0; var cursor: Cursor? = null; var onRow: (Int) -> Unit = {}
        override fun lexicalCursor(match: String, minConfidence: Double): Cursor {
            opens++
            return object : CursorWrapper(real.lexicalCursor(match, minConfidence)) {
                override fun moveToNext(): Boolean = super.moveToNext().also { if (it) { rowsRead++; onRow(rowsRead) } }
            }.also { cursor = it }
        }
    }

    private val words = listOf("milk", "carton", "fridge", "keys", "shelf", "weekend", "hills", "note", "blue", "bag")

    /** B2: the single cursor pass keeps exactly the top-k of a full BM25 sort (same score, then id tie-break). */
    @Test fun singlePassMatchesTheRankedResultOfAFullSort() = runBlocking {
        val rnd = java.util.Random(7)
        bulk(2_000) { i -> (0..(rnd.nextInt(12))).joinToString(" ") { words[rnd.nextInt(words.size)] } + " milk" + " x$i" }
        val match = com.rokid.context.memory.db.SearchNormalizer.toAnyMatchQuery("milk fridge keys")!!
        val all = db.retrievalDao().lexicalCursor(match, 0.0).use { c ->
            buildList { while (c.moveToNext()) add(Hit(c.getString(1), Bm25.score(Bm25.ints(c.getBlob(2))))) }
        }
        assertEquals(2_000, all.size)
        val reference = all.sortedWith(compareByDescending<Hit> { it.score }.thenBy { it.id }).take(20)
        assertEquals(reference, lexical.search("milk fridge keys", 20))
    }

    /** B2: one query opens the FTS cursor once, whatever the match count (no per-page doclist re-reads). */
    @Test fun theCursorIsOpenedOncePerQuery() = runBlocking {
        bulk(1_200) { "milk carton number $it" }
        val spy = SpyDao(db.retrievalDao())
        assertEquals(10, LexicalRetriever(spy).search("milk", 10).size)
        assertEquals(1, spy.opens)
        assertEquals(1_200, spy.rowsRead)
        assertTrue(spy.cursor!!.isClosed)
    }

    /** B2: cancelling a search mid-scan stops reading and closes the cursor. */
    @Test fun cancellationClosesTheCursor() = runBlocking {
        bulk(2_000) { "milk carton number $it" }
        val spy = SpyDao(db.retrievalDao())
        lateinit var job: Job
        spy.onRow = { if (it == 5) job.cancel() }
        job = launch(Dispatchers.IO, start = CoroutineStart.LAZY) { LexicalRetriever(spy).search("milk", 10) }
        job.start(); job.join()
        assertTrue(job.isCancelled)
        assertNotNull(spy.cursor); assertTrue(spy.cursor!!.isClosed)
        assertTrue("read ${spy.rowsRead}", spy.rowsRead < 2_000)
    }

    /** B2: 50 k matches of one common term rank well inside a generous JVM bound. */
    @Test fun fiftyThousandCommonTermMatchesRankWithinFiveSeconds() = runBlocking {
        bulk(50_000) { "the common word appears in memory $it" }
        val t0 = System.nanoTime()
        val hits = lexical.search("common", 10)
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertEquals(10, hits.size)
        assertTrue("took $ms ms", ms < 5_000)
    }
}
