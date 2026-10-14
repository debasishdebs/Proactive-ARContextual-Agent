package com.rokid.context.memory

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.FrameEntity
import com.rokid.context.memory.db.FrameVectorEntity
import com.rokid.context.memory.db.MemoryEntity
import com.rokid.context.memory.db.MemoryTier
import com.rokid.context.memory.db.ReviewState
import com.rokid.context.memory.db.SpaceState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Spec §F.2: a swap keeps the retired space's vectors for 7 days, with Roll back; image coverage is a wave-2 flag. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class EmbeddingSwapTest {
    private val db = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    private val dao = db.embeddingDao()
    private val clock = InMemoryRetiredClock()
    private var t = 1_000L
    private val day = 24 * 60 * 60 * 1000L
    private val memories = MemoryRepository(db.memoryDao())
    private val v = floatArrayOf(1f, 0f)
    @After fun close() = db.close()

    private fun spaces(requireImage: Boolean = false) = EmbeddingSpaces(dao, clock, { t }, requireImage)
    private suspend fun mem(id: String) = memories.add(MemoryEntity(id, null, "text $id", "note", "", MemoryTier.SHORT, 0.8, ReviewState.OK, false, 1, false, ""))
    private suspend fun EmbeddingSpaces.build(fp: String) = startBuilding(fp, "{}", 2, "[]", 10).also { put("m1", it.id, v) }
    private suspend fun frame(id: String) = db.frameDao().insertIgnore(FrameEntity(id, 1, "/p/$id", "glasses"))
    private suspend fun fvec(frame: String, space: String) = db.frameVectorDao().upsert(listOf(FrameVectorEntity(frame, space, byteArrayOf(1))))

    /** a ACTIVE (swapped in), then b swapped in: a RETIRED at t. */
    private suspend fun twoSpaces(s: EmbeddingSpaces = spaces()): Pair<String, String> {
        mem("m1")
        val a = s.build("fpA"); assertTrue(s.swapIfComplete(a.id))
        val b = s.build("fpB"); assertTrue(s.swapIfComplete(b.id))
        return a.id to b.id
    }

    @Test fun swapKeepsRetiredVectors() = runBlocking {
        mem("m1")
        val s = spaces()
        val a = s.build("fpA"); s.swapIfComplete(a.id); frame("f1"); fvec("f1", a.id)
        val b = s.build("fpB"); assertTrue(s.swapIfComplete(b.id))
        assertEquals(SpaceState.RETIRED, dao.space(a.id)!!.state)
        assertEquals(1, dao.vectors(a.id).size)
        assertEquals(1, db.frameVectorDao().countForSpace(a.id))
        assertEquals(t, clock.retiredAt(a.id))
    }

    @Test fun rollbackWithin7Days() = runBlocking {
        val (a, b) = twoSpaces()
        val s = spaces()
        t += 6 * day
        assertEquals(a, s.rollbackTarget()!!.id)
        assertTrue(s.rollback(a))
        assertEquals(SpaceState.ACTIVE, dao.space(a)!!.state)
        assertEquals(SpaceState.RETIRED, dao.space(b)!!.state)
        assertEquals(1, dao.vectors(b).size) // the one that served meanwhile is kept too
        assertEquals(a, s.queryable()!!.id)
        assertEquals(t, clock.retiredAt(b))
    }

    @Test fun rollbackRefusedAfter7Days() = runBlocking {
        val (a, b) = twoSpaces()
        val s = spaces()
        t += 7 * day + 1
        assertFalse(s.rollback(a))
        assertEquals(null, s.rollbackTarget())
        assertEquals(SpaceState.ACTIVE, dao.space(b)!!.state)
        assertFalse("only a RETIRED space can be rolled back to", s.rollback(b))
    }

    @Test fun purgeAfter7Days() = runBlocking {
        val (a, b) = twoSpaces()
        val s = spaces()
        fvec("fx", a)
        t += 6 * day; s.purgeRetired()
        assertEquals(1, dao.vectors(a).size)
        t += 2 * day; s.purgeRetired()
        assertTrue(dao.vectors(a).isEmpty())
        assertEquals(0, db.frameVectorDao().countForSpace(a))
        assertEquals(1, dao.vectors(b).size) // the ACTIVE space is never purged
        assertEquals(SpaceState.RETIRED, dao.space(a)!!.state)
    }

    @Test fun coverageIgnoresDeletedPhotos() = runBlocking {
        mem("m1")
        val s = spaces(requireImage = true)
        val a = s.build("fpA"); assertTrue(s.swapIfComplete(a.id)) // no photos at all: nothing to cover
        frame("kept"); fvec("kept", a.id); fvec("deleted", a.id) // "deleted" has a vector but no stored photo
        val b = s.build("fpB")
        assertFalse("a stored photo without an image vector blocks the swap", s.swapIfComplete(b.id))
        fvec("kept", b.id) // no vector is needed (or possible) for the deleted photo
        assertTrue(s.swapIfComplete(b.id))
    }

    @Test fun wave1SwapNeedsTextCoverageOnly() = runBlocking {
        mem("m1")
        val s = spaces() // requireImageCoverage defaults to false
        val a = s.build("fpA"); s.swapIfComplete(a.id)
        frame("f1"); fvec("f1", a.id)
        val b = s.build("fpB")
        assertTrue(s.swapIfComplete(b.id))
        assertEquals(b.id, s.queryable()!!.id)
    }
}
