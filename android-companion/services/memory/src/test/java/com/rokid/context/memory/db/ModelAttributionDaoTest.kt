package com.rokid.context.memory.db

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ModelAttributionDaoTest {
    private val db = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext<Context>())
    private val dao = db.modelAttributionDao()
    @After fun close() = db.close()

    @Test fun recordReplacesTheRowOfAnItemAndNullClearsIt() = runBlocking {
        dao.record(AttributionKind.MEMORY, "m1", ModelAttribution("model-a", null, "2"), 10)
        dao.record(AttributionKind.MEMORY, "m1", ModelAttribution("model-b", "v9", "3"), 20) // a re-run replaces it
        assertEquals(1, dao.count())
        val got = dao.get(AttributionKind.MEMORY, "m1")!!
        assertEquals("model-b", got.model); assertEquals("v9", got.modelVersion); assertEquals("3", got.promptRev); assertEquals(20L, got.recordedAt)
        dao.record(AttributionKind.MEMORY, "m1", null, 30) // the text was rewritten without a known model: the old name must not stay
        assertNull(dao.get(AttributionKind.MEMORY, "m1"))
        dao.record(AttributionKind.ANSWER, "a1", null, 30); assertEquals(0, dao.count())
    }

    @Test fun kindsAreSeparateKeysAndTheOfLookupsFilterByKind() = runBlocking {
        dao.record(AttributionKind.MEMORY, "x", ModelAttribution("m"), 1); dao.record(AttributionKind.ANSWER, "x", ModelAttribution("n"), 1)
        dao.record(AttributionKind.DERIVED, AttributionKind.derivedRef("MOMENT", "68f", "SUMMARY"), ModelAttribution("o"), 1)
        assertEquals(3, dao.count()); assertEquals(1, dao.countOf(AttributionKind.ANSWER))
        assertEquals(listOf("m"), dao.ofRefs(AttributionKind.MEMORY, listOf("x", "y")).map { it.model })
        assertEquals(listOf("o"), dao.ofPrefix(AttributionKind.DERIVED, "MOMENT:68f:").map { it.model })
        assertEquals("MOMENT:68f:SUMMARY", AttributionKind.derivedRef("MOMENT", "68f", "SUMMARY"))
    }

    @Test fun blankModelIsNoAttributionAndLongNamesAreBounded() {
        assertNull(ModelAttribution.of(null)); assertNull(ModelAttribution.of("  "))
        assertEquals("m", ModelAttribution.of(" m ", 4)!!.model); assertEquals("4", ModelAttribution.of("m", 4)!!.promptRev)
        assertEquals(120, ModelAttribution("x".repeat(500)).row("MEMORY", "i", 1).model.length)
    }

    @Test fun deleteForAndDeleteAll() = runBlocking {
        (1..3).forEach { dao.record(AttributionKind.MEMORY, "m$it", ModelAttribution("a"), 1) }
        dao.record(AttributionKind.ANSWER, "m1", ModelAttribution("a"), 1)
        assertEquals(2, dao.deleteFor(AttributionKind.MEMORY, listOf("m1", "m2", "zz")))
        assertEquals(2, dao.count()); assertNotNull(dao.get(AttributionKind.ANSWER, "m1"))
        assertEquals(2, dao.deleteAll())
    }

    @Test fun deleteGoneKeepsOnlyRowsWhoseItemExists() = runBlocking {
        db.memoryDao().insert(MemoryEntity("m1", null, "t", "note", "", MemoryTier.LONG, 0.8, ReviewState.OK, false, 1, false, "t"))
        db.askDao().upsert(AskEntity("a1", 1, "q", "ans", "[]", null, "test", false)); db.askDao().upsert(AskEntity("a2", 1, "q", "x", "[]", null, "test", true))
        db.objectDao().writeDerivedTx(ObjectDerivedEntity("MOMENT", "mo1", "SUMMARY", "s", null, 1, null, 1), "build")
        listOf("m1", "m-gone").forEach { dao.record(AttributionKind.MEMORY, it, ModelAttribution("a"), 1) }
        listOf("a1", "a2", "a-gone").forEach { dao.record(AttributionKind.ANSWER, it, ModelAttribution("a"), 1) } // a2 is redacted: its text is gone
        listOf("MOMENT:mo1:SUMMARY", "MOMENT:mo1:CAPTION").forEach { dao.record(AttributionKind.DERIVED, it, ModelAttribution("a"), 1) }
        assertEquals(4, dao.deleteGone())
        assertEquals(setOf("MEMORY|m1", "ANSWER|a1", "DERIVED|MOMENT:mo1:SUMMARY"), listOf("MEMORY", "ANSWER", "DERIVED").flatMap { k -> dao.ofPrefix(k, "") }.map { it.kind + "|" + it.refId }.toSet())
    }

    @Test fun derivedWriteRecordsTheModelInTheSameTransactionAndARewriteWithoutOneClearsIt() = runBlocking {
        val row = ObjectDerivedEntity("MOMENT", "mo1", "SUMMARY", "s", null, 1, null, 5)
        db.objectDao().writeDerivedTx(row, "build", ModelAttribution("model-s", null, null))
        assertEquals("model-s", dao.get(AttributionKind.DERIVED, "MOMENT:mo1:SUMMARY")!!.model)
        db.objectDao().writeDerivedTx(row.copy(value = "t"), "build", null)
        assertNull(dao.get(AttributionKind.DERIVED, "MOMENT:mo1:SUMMARY"))
    }

    @Test fun deletingTheItemDeletesTheRow() = runBlocking {
        db.memoryDao().insert(MemoryEntity("m1", null, "t", "note", "", MemoryTier.LONG, 0.8, ReviewState.OK, false, 1, false, "t"))
        db.memoryDao().insert(MemoryEntity("m2", null, "t", "note", "", MemoryTier.LONG, 0.8, ReviewState.OK, false, 1, false, "t"))
        listOf("m1", "m2").forEach { dao.record(AttributionKind.MEMORY, it, ModelAttribution("a"), 1) }
        db.forgetDao().deleteMemories(listOf("m1")); assertNull(dao.get(AttributionKind.MEMORY, "m1"))
        com.rokid.context.memory.MemoryRepository(db.memoryDao()).deleteByIds(listOf("m2")); assertNull(dao.get(AttributionKind.MEMORY, "m2"))
        db.askDao().upsert(AskEntity("a1", 1, "q", "ans", "[]", null, "test", false)); dao.record(AttributionKind.ANSWER, "a1", ModelAttribution("a"), 1)
        db.forgetDao().redactAsk("a1", "x"); assertNull(dao.get(AttributionKind.ANSWER, "a1"))
        db.objectDao().writeDerivedTx(ObjectDerivedEntity("SCENE", "s1", "CAPTION", "c", null, 1, null, 1), "build", ModelAttribution("a"))
        db.objectDao().removeObjects("SCENE", listOf("s1")); assertEquals(0, dao.count())
        assertTrue(dao.count() == 0)
    }
}
