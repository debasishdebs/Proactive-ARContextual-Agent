package com.rokid.context.memory.db

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class MemoryDaoTest {
    private val db = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    @After fun close() = db.close()

    private fun mem(id: String, text: String, at: Long, state: ReviewState = ReviewState.OK) = MemoryEntity(
        id = id, frameId = null, text = text, type = "short_term", tags = "", tier = MemoryTier.SHORT, confidence = 0.8,
        reviewState = state, trusted = false, createdAt = at, derived = false, searchText = SearchNormalizer.normalize(text),
    )

    @Test fun devanagariWordIsFound() = runTest {
        db.memoryDao().insert(mem("m1", "आज सुबह दूध खत्म हो गया।", 1))
        db.memoryDao().insert(mem("m2", "Keys on the hallway shelf", 2))
        assertEquals(listOf("m1"), db.memoryDao().search(SearchNormalizer.toMatchQuery("दूध")!!, 10).map { it.id })
    }

    @Test fun englishWordInsideSentenceIsFound() = runTest {
        db.memoryDao().insert(mem("m1", "Milk carton on the kitchen shelf", 1))
        db.memoryDao().insert(mem("m2", "Keys on the hallway shelf", 2))
        suspend fun q(t: String) = db.memoryDao().search(SearchNormalizer.toMatchQuery(t)!!, 10).map { it.id }
        assertEquals(listOf("m1"), q("kitchen"))
        assertEquals(listOf("m2"), q("hallway"))
        assertEquals(emptyList<String>(), q("bread"))
    }

    @Test fun mixedEnglishHindiQueryIsFound() = runTest {
        db.memoryDao().insert(mem("m1", "Shopping list: दूध and bread", 1))
        db.memoryDao().insert(mem("m2", "Shopping list: दूध and eggs", 2))
        assertEquals(listOf("m1"), db.memoryDao().search(SearchNormalizer.toMatchQuery("दूध bread")!!, 10).map { it.id })
    }

    @Test fun emojiAndPunctuationAreIgnored() = runTest {
        db.memoryDao().insert(mem("m1", "Milk carton nearly empty", 1))
        assertEquals(listOf("m1"), db.memoryDao().search(SearchNormalizer.toMatchQuery("🥛 milk!")!!, 10).map { it.id })
    }

    @Test fun invalidatedMemoriesAreNotSearchable() = runTest {
        db.memoryDao().insert(mem("m1", "milk", 1, ReviewState.INVALIDATED))
        assertTrue(db.memoryDao().search(SearchNormalizer.toMatchQuery("milk")!!, 10).isEmpty())
    }

    @Test fun ftsFollowsUpdatesAndDeletes() = runTest {
        db.memoryDao().insert(mem("m1", "milk", 1))
        db.memoryDao().insert(mem("m1", "eggs", 1)) // REPLACE
        assertTrue(db.memoryDao().search("\"milk*\"", 10).isEmpty())
        assertEquals(1, db.memoryDao().search("\"eggs*\"", 10).size)
        db.memoryDao().deleteByIds(listOf("m1"))
        assertTrue(db.memoryDao().search("\"eggs*\"", 10).isEmpty())
    }

    @Test fun replacedThenDeletedMemoryLeavesNoStaleFtsEntry() = runTest {
        db.memoryDao().insert(mem("m1", "milk", 1))
        db.memoryDao().insert(mem("m1", "eggs", 1))
        db.memoryDao().deleteByIds(listOf("m1"))
        db.memoryDao().insert(mem("m2", "bread", 2))
        assertTrue(db.memoryDao().search("\"milk*\"", 10).isEmpty())
        assertEquals(listOf("m2"), db.memoryDao().search("\"bread*\"", 10).map { it.id })
    }

    @Test fun recentAndBetweenAreNewestFirst() = runTest {
        listOf(mem("a", "x", 10), mem("b", "y", 30), mem("c", "z", 20)).forEach { db.memoryDao().insert(it) }
        assertEquals(listOf("b", "c", "a"), db.memoryDao().recent(10).map { it.id })
        assertEquals(listOf("c", "a"), db.memoryDao().between(10, 25).map { it.id })
    }

    @Test fun frameInsertIgnoresDuplicatesAndTraceUpserts() = runTest {
        val f = FrameEntity(id = "f1", ts = 1, path = "/p.jpg", source = "rokid_glasses", payload = "x")
        assertTrue(db.frameDao().insertIgnore(f) > 0L)
        assertEquals(-1L, db.frameDao().insertIgnore(f.copy(payload = "dup")))
        assertEquals("x", db.frameDao().byIds(listOf("f1")).single().payload)
        db.traceDao().upsert(SummaryTraceEntity("s1", "QUEUED", 1, "n"))
        db.traceDao().upsert(SummaryTraceEntity("s1", "TRUSTED", 2, "n2"))
        assertEquals(listOf("TRUSTED"), db.traceDao().recent(10).map { it.state })
    }
}
