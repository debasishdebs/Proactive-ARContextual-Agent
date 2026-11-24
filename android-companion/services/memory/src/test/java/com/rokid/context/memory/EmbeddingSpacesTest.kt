package com.rokid.context.memory

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.MemoryEntity
import com.rokid.context.memory.db.MemoryTier
import com.rokid.context.memory.db.ReviewState
import com.rokid.context.memory.db.SpaceState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class EmbeddingSpacesTest {
    private val db = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    private val spaces = EmbeddingSpaces(db.embeddingDao())
    private val memories = MemoryRepository(db.memoryDao())
    @After fun close() = db.close()

    private suspend fun mem(id: String, review: ReviewState = ReviewState.OK) =
        memories.add(MemoryEntity(id, null, "text $id", "note", "", MemoryTier.SHORT, 0.8, review, false, 1, false, ""))
    private val v = floatArrayOf(1f, 0f)
    private suspend fun start(fp: String) = spaces.startBuilding(fp, "{}", 2, "[]", 10)

    @Test fun swapHappensOnlyAtFullCoverageAndRetiresTheOldSpace() = runBlocking {
        mem("m1"); mem("m2"); mem("m3", ReviewState.INVALIDATED)
        val a = start("fpA"); spaces.put("m1", a.id, v); spaces.put("m2", a.id, v)
        assertTrue(spaces.swapIfComplete(a.id))
        val b = start("fpB"); spaces.put("m1", b.id, v)
        assertEquals(Coverage(1, 2), spaces.coverage(b.id))
        assertFalse(spaces.swapIfComplete(b.id))
        assertEquals(a.id, spaces.queryable()!!.id) // the old space keeps serving while B builds
        spaces.put("m2", b.id, v)
        assertTrue(spaces.swapIfComplete(b.id))
        assertEquals(b.id, spaces.queryable()!!.id)
        assertEquals(SpaceState.RETIRED, db.embeddingDao().space(a.id)!!.state)
        assertEquals(2, db.embeddingDao().vectors(a.id).size) // kept for the 7-day rollback
        assertEquals(1, db.embeddingDao().observeSpaces().first().count { it.state == SpaceState.ACTIVE })
    }

    @Test fun aNewMemoryDuringABuildIsMissingInBothSpaces() = runBlocking {
        mem("m1"); val a = start("fpA"); spaces.put("m1", a.id, v); spaces.swapIfComplete(a.id)
        val b = start("fpB"); spaces.put("m1", b.id, v)
        mem("m9")
        assertEquals(listOf("m9"), spaces.missing(a.id, 10).map { it.id })
        assertEquals(listOf("m9"), spaces.missing(b.id, 10).map { it.id })
    }

    /** Retention v3 (WP1): a SHORT fact past the Quick recall window lost its text vector on purpose. The backfill never re-embeds it, the coverage and the space swap do not count it; a LONG memory and a young SHORT fact still are. */
    @Test fun aDemotedFactIsNeverBackfilledCountedOrAllowedToBlockASwap() = runBlocking {
        fun at(id: String, tier: MemoryTier, createdAt: Long, type: String = "note") = MemoryEntity(id, null, "text $id", type, "", tier, 0.8, ReviewState.OK, false, createdAt, false, "")
        memories.add(at("old_short", MemoryTier.SHORT, 100)); memories.add(at("young_short", MemoryTier.SHORT, 900)); memories.add(at("old_long", MemoryTier.LONG, 100)); memories.add(at("old_short_caption", MemoryTier.SHORT, 100, "caption"))
        val a = start("fpD")
        spaces.recallSince = { 500L } // the window starts at 500: old_short is past it
        assertEquals(setOf("young_short", "old_long", "old_short_caption"), spaces.missing(a.id, 10).map { it.id }.toSet())
        assertEquals(Coverage(0, 3), spaces.coverage(a.id))
        spaces.put("young_short", a.id, v); spaces.put("old_long", a.id, v); spaces.put("old_short_caption", a.id, v)
        assertEquals(Coverage(3, 3), spaces.coverage(a.id))
        assertTrue("the demoted fact has no vector and the swap does not wait for one", spaces.swapIfComplete(a.id))
        spaces.recallSince = { 0L } // no window: the same fact is an ordinary missing one again
        assertEquals(listOf("old_short"), spaces.missing(a.id, 10).map { it.id })
    }

    @Test fun atMostOneBuildingAndSameFingerprintIsResumed() = runBlocking {
        val a = start("fpA")
        assertEquals(a.id, start("fpA").id)
        val b = start("fpB")
        assertNull(db.embeddingDao().space(a.id))
        assertEquals(b.id, spaces.building()!!.id)
    }

    @Test fun staleSpaceIsNeverQueriedAndCanBeRebuiltUnderTheSameFingerprint() = runBlocking {
        mem("m1"); val a = start("fpA"); spaces.put("m1", a.id, v); spaces.swapIfComplete(a.id)
        spaces.markStale(a.id)
        assertNull(spaces.queryable())
        assertEquals(IndexHealth.NEEDED, spaces.health().first())
        val again = start("fpA")
        assertNull(db.embeddingDao().space(a.id)) // the stale row (and its vectors) made way for the unique fingerprint
        assertEquals(IndexHealth.RUNNING, spaces.health().first())
        spaces.put("m1", again.id, v); spaces.swapIfComplete(again.id)
        assertEquals(IndexHealth.NONE, spaces.health().first())
    }
    @Test fun putRejectsAWrongDimensionAndDropsWritesToAGoneOrRetiredSpace() = runBlocking {
        mem("m1"); val a = start("fpA")
        assertThrows(IllegalArgumentException::class.java) { runBlocking { spaces.put("m1", a.id, floatArrayOf(1f, 0f, 0f)) } }
        assertTrue(db.embeddingDao().vectors(a.id).isEmpty())
        spaces.put("m1", a.id, v); assertTrue(spaces.swapIfComplete(a.id))
        val b = start("fpB"); spaces.put("m1", b.id, v); assertTrue(spaces.swapIfComplete(b.id))
        spaces.put("m1", a.id, v) // a late write from a worker that still holds the retired space
        assertEquals(1, db.embeddingDao().vectors(a.id).size) // only the vector it already had
        val c = start("fpC"); spaces.discard(c.id)
        spaces.put("m1", c.id, v) // a late write after discard
        assertTrue(db.embeddingDao().vectors(c.id).isEmpty())
    }

    @Test fun swapLeavesExactlyOneActiveEvenIfAnOlderServingSpaceLingers() = runBlocking {
        mem("m1")
        val a = start("fpA"); spaces.put("m1", a.id, v); assertTrue(spaces.swapIfComplete(a.id))
        val b = start("fpB"); spaces.put("m1", b.id, v)
        db.embeddingDao().setState(b.id, SpaceState.STALE.name) // two serving-class rows: a ACTIVE, b STALE
        val c = start("fpC"); spaces.put("m1", c.id, v); assertTrue(spaces.swapIfComplete(c.id))
        val states = db.embeddingDao().observeSpaces().first().associate { it.id to it.state }
        assertEquals(mapOf(a.id to SpaceState.RETIRED, b.id to SpaceState.RETIRED, c.id to SpaceState.ACTIVE), states)
        assertTrue(db.embeddingDao().vectors(a.id).isNotEmpty() && db.embeddingDao().vectors(b.id).isNotEmpty()) // retired spaces keep their vectors
    }

    @Test fun swapRefusesANonBuildingSpaceAndKeepsTheActiveOne() = runBlocking {
        mem("m1"); val a = start("fpA"); spaces.put("m1", a.id, v); assertTrue(spaces.swapIfComplete(a.id))
        assertFalse(spaces.swapIfComplete(a.id)) // already ACTIVE
        assertFalse(spaces.swapIfComplete("space_missing"))
        assertEquals(a.id, spaces.queryable()!!.id)
    }
}
