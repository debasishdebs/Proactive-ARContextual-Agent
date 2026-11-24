package com.rokid.context.memory.db

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Fix round 1 I5: a vector computed before a Forget never lands for a missing or INVALIDATED memory. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class EmbeddingFenceTest {
    private val db = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    @After fun close() = db.close()

    /** Codex v3 r1 P2-10: the recall eligibility is re-checked at insertion, so a vector selected before the demotion is not restored for a demoted SHORT fact; captions and fresh facts are unaffected. */
    @Test fun putCheckedRefusesAVectorForADemotedFactButNotForOtherRows() = runBlocking {
        val e = db.embeddingDao()
        e.startBuilding(EmbeddingSpaceEntity("s1", "fp", "{}", 1, SpaceState.BUILDING, "{}", 1))
        db.memoryDao().insert(MemoryEntity("oldShort", null, "t", "note", "", MemoryTier.SHORT, 0.8, ReviewState.OK, false, 10, false, "t"))
        db.memoryDao().insert(MemoryEntity("newShort", null, "t", "note", "", MemoryTier.SHORT, 0.8, ReviewState.OK, false, 1_000, false, "t"))
        db.memoryDao().insert(MemoryEntity("oldCap", null, "t", "caption", "", MemoryTier.LONG, 0.8, ReviewState.OK, false, 10, false, "t"))
        assertFalse("past the window: refused", e.putChecked(MemoryVectorEntity("oldShort", "s1", byteArrayOf(1)), 1, recallSince = 500))
        assertTrue(e.putChecked(MemoryVectorEntity("newShort", "s1", byteArrayOf(1)), 1, recallSince = 500))
        assertTrue("a LONG caption is never demoted", e.putChecked(MemoryVectorEntity("oldCap", "s1", byteArrayOf(1)), 1, recallSince = 500))
        assertTrue("no recall window (0): as before", e.putChecked(MemoryVectorEntity("oldShort", "s1", byteArrayOf(1)), 1))
    }

    @Test fun putCheckedRequiresALiveMemory() = runBlocking {
        val e = db.embeddingDao()
        e.startBuilding(EmbeddingSpaceEntity("s1", "fp", "{}", 1, SpaceState.BUILDING, "{}", 1))
        db.memoryDao().insert(MemoryEntity("ok", null, "t", "note", "", MemoryTier.SHORT, 0.8, ReviewState.OK, false, 1, false, "t"))
        db.memoryDao().insert(MemoryEntity("hid", null, "t", "note", "", MemoryTier.SHORT, 0.8, ReviewState.INVALIDATED, false, 1, false, "t"))
        assertTrue(e.putChecked(MemoryVectorEntity("ok", "s1", byteArrayOf(1)), 1))
        assertFalse(e.putChecked(MemoryVectorEntity("hid", "s1", byteArrayOf(1)), 1))
        assertFalse(e.putChecked(MemoryVectorEntity("gone", "s1", byteArrayOf(1)), 1))
        assertTrue(e.vectors("s1").map { it.memoryId } == listOf("ok"))
    }
}
