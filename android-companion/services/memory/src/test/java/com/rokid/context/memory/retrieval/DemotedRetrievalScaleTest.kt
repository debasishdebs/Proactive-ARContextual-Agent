package com.rokid.context.memory.retrieval

import android.content.ContentValues
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.db.DatabaseFactory
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Retention v3 WP3 latency / size guard (laptop half, same pattern as ActivityPaginationPerfTest): a synthetic 100,000-fact database where 98% of the facts are demoted
 * (SHORT, old, no vector). It PRINTS the numbers (grep `DEMOTE-PERF`) and asserts the structure of the bound (what a default search can return, and that it is not slower than
 * the unfiltered scan) with generous time limits, so it is not flaky. Real phone CPU / SQLCipher are not measured here.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class DemotedRetrievalScaleTest {
    private val db = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    private val hour = 3_600_000L
    private val day = 24 * hour
    private val now = 400 * day
    private val total = 100_000
    private val recent = 2_000 // inside the Quick recall window, with vectors
    @After fun close() = db.close()

    private fun out(s: String) = println("DEMOTE-PERF $s")
    private inline fun <T> timed(block: () -> T): Pair<T, Double> { val t = System.nanoTime(); val r = block(); return r to (System.nanoTime() - t) / 1e6 }

    @Test fun demotionBoundsTheDefaultPoolsOnAHundredThousandFacts() = runBlocking<Unit> {
        val w = db.openHelper.writableDatabase
        val spaceId = "sp1" // an ACTIVE space written directly: a real swap needs full coverage, and 98% of these facts are demoted on purpose
        w.beginTransaction()
        try {
            w.insert("embedding_space", 0, ContentValues().apply { put("id", spaceId); put("fingerprint", "fp"); put("configJson", "{}"); put("dim", 2); put("state", "ACTIVE"); put("canaryJson", "[]"); put("createdAt", 1L) })
            for (n in 0 until total) {
                val inWindow = n >= total - recent
                val at = if (inWindow) now - (total - n) * 1000L else now - 3 * day - n * 60_000L // the rest: 3+ days old, spread over ~70 days
                val text = "scale fact $n token" + if (n % 2 == 0) " quartz" else ""
                w.insert("memory", 0, ContentValues().apply {
                    put("id", "m%06d".format(n)); put("text", text); put("type", "object"); put("tags", ""); put("tier", "SHORT"); put("confidence", 0.8)
                    put("reviewState", "OK"); put("trusted", 0); put("createdAt", at); put("derived", 0); put("searchText", text)
                })
                if (inWindow) w.insert("memory_vector", 0, ContentValues().apply { put("memoryId", "m%06d".format(n)); put("spaceId", spaceId); put("blob", ByteArray(8)) })
            }
            w.setTransactionSuccessful()
        } finally { w.endTransaction() }
        val since = now - 48 * hour
        val dao = db.retrievalDao()
        assertEquals(total - recent, dao.countDemoted(since))

        val lexical = LexicalRetriever(dao)
        suspend fun run(recallSince: Long?) = timed { runBlocking { lexical.search("quartz", 40, 0.0, null, null, recallSince) } }
        run(since); run(null) // warm
        val (kept, keptMs) = run(since)
        val (all, allMs) = run(null)
        out("100,000 facts, ${total - recent} demoted: default lexical search with demotion ${"%.0f".format(keptMs)} ms, without ${"%.0f".format(allMs)} ms")
        assertEquals(40, kept.size)
        assertTrue("default pool holds only in-window facts", kept.all { it.id.substring(1).toInt() >= total - recent })
        assertEquals(40, all.size)
        // a time-window question still reaches the demoted facts, bounded by its window
        val win = EventWindow(now - 5 * day, now - 4 * day)
        val (hits, winMs) = timed { runBlocking { lexical.search("quartz", 40, 0.0, null, win, since) } }
        out("window question over 1 day reaches demoted facts: ${hits.size} hits in ${"%.0f".format(winMs)} ms")
        assertEquals(40, hits.size)
        assertTrue(hits.all { it.id.substring(1).toInt() < total - recent })

        // the vector scan reads only vectors that exist: demoted facts have none, so it is bounded by the window, not by history
        val (_, scanMs) = timed { runBlocking { BruteForceVectorIndex(db.embeddingDao()).search(spaceId, floatArrayOf(1f, 0f), 40) } }
        out("vector scan rows=${db.embeddingDao().vectorCount(spaceId)} ${"%.0f".format(scanMs)} ms")
        assertEquals(recent, db.embeddingDao().vectorCount(spaceId))
        assertTrue("lexical default search stays interactive: $keptMs ms", keptMs < 10_000)
        assertTrue("demotion must not make the default search slower: kept $keptMs ms vs all $allMs ms", keptMs < allMs * 3 + 500)
    }
}
