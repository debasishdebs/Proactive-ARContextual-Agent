package com.rokid.context.memory.db

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class FrameStateDaoTest {
    private val db = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    private val dao = db.frameStateDao()
    @After fun close() = db.close()

    private fun row(id: String, state: String = "PENDING", attempts: Int = 0, claimedAt: Long? = null, claimId: String? = null) =
        FrameStateEntity(id, state, attempts, null, claimedAt, claimId, 0)

    @Test fun claimTakesPendingAndAbandoned() = runBlocking {
        dao.insertIgnore(listOf(
            row("p"), row("f", "FAILED", 2), row("fin", "FAILED_FINAL", 5), row("done", "EXTRACTED"),
            row("fresh", "EXTRACTING", 0, claimedAt = 9_000, claimId = "x"), row("old", "EXTRACTING", 0, claimedAt = 1_000, claimId = "y"),
        ))
        val got = dao.claim(listOf("p", "f", "fin", "done", "fresh", "old"), "c1", nowMs = 10_000, abandonBeforeMs = 5_000)
        assertEquals(setOf("p", "f", "old"), got.map { it.frameId }.toSet())
        got.forEach { assertEquals("EXTRACTING", it.state); assertEquals("c1", it.claimId); assertEquals(10_000L, it.claimedAt) }
        assertEquals("x", dao.get("fresh")!!.claimId)
        assertEquals("FAILED_FINAL", dao.get("fin")!!.state)
        assertEquals(1, dao.get("old")!!.attempts) // an abandoned claim counts as an attempt
        assertEquals(0, dao.get("p")!!.attempts)
    }

    @Test fun casRejectsStaleClaim() = runBlocking {
        dao.insertIgnore(listOf(row("a")))
        dao.claim(listOf("a"), "old", 1_000, 0)
        dao.claim(listOf("a"), "new", 400_000, 300_000) // the old claim went stale and was retaken
        assertEquals(0, dao.completeIfClaimed("a", "old", "EXTRACTED", 400_001))
        assertEquals("EXTRACTING", dao.get("a")!!.state)
        assertEquals(1, dao.completeIfClaimed("a", "new", "EXTRACTED", 400_002))
        assertEquals("EXTRACTED", dao.get("a")!!.state); assertNull(dao.get("a")!!.claimId)
        assertEquals(0, dao.completeIfClaimed("a", "new", "EXTRACTED", 400_003))
    }

    @Test fun fifthFailureIsFinal() = runBlocking {
        dao.insertIgnore(listOf(row("a")))
        repeat(4) { i ->
            dao.claim(listOf("a"), "c$i", 1_000L * i, 0)
            assertEquals(1, dao.fail("a", "c$i", "boom", 1_000L * i))
            assertEquals("FAILED", dao.get("a")!!.state)
        }
        dao.claim(listOf("a"), "c4", 9_000, 0)
        assertEquals(1, dao.fail("a", "c4", "boom", 9_000))
        val r = dao.get("a")!!
        assertEquals("FAILED_FINAL", r.state); assertEquals(5, r.attempts); assertEquals("boom", r.lastError)
        assertEquals(emptyList<FrameStateEntity>(), dao.recoverable(10_000, 0, 10))
    }

    @Test fun retryAllFinalResets() = runBlocking {
        dao.insertIgnore(listOf(row("a", "FAILED_FINAL", 5), row("b", "FAILED", 2), row("c", "FAILED_FINAL", 5)))
        assertEquals(2, dao.retryAllFinal(7))
        assertEquals("PENDING", dao.get("a")!!.state); assertEquals(0, dao.get("a")!!.attempts)
        assertEquals("FAILED", dao.get("b")!!.state)
        assertEquals(setOf("a", "b", "c"), dao.recoverable(8, 0, 10).map { it.frameId }.toSet())
    }
}
