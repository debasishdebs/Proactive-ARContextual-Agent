package com.rokid.context.memory.retrieval

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.MemoryRepository
import com.rokid.context.memory.db.DatabaseFactory
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
}
