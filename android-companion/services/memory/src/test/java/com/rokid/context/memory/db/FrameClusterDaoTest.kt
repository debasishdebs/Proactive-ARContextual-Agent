package com.rokid.context.memory.db

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class FrameClusterDaoTest {
    private val db = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    private val dao = db.frameClusterDao()
    @After fun close() = db.close()

    private fun cluster(id: String, state: String = "CLOSED", closedAt: Long? = 100, attempts: Int = 0, claimedAt: Long? = null, claimId: String? = null, updatedAt: Long = 0) =
        FrameClusterEntity(id, 1, closedAt, "gap", "[]", null, state, attempts, claimId, claimedAt, null, updatedAt)

    @Test fun openFindsTheNewestOpenCluster() = runBlocking {
        dao.upsert(cluster("a", "OPEN", closedAt = null).copy(openedAt = 1)); dao.upsert(cluster("b", "OPEN", closedAt = null).copy(openedAt = 9)); dao.upsert(cluster("c"))
        assertEquals("b", dao.open()!!.clusterId)
        assertEquals("c", dao.get("c")!!.clusterId); assertNull(dao.get("zz"))
    }

    @Test fun claimCasAndStaleClaim() = runBlocking {
        dao.upsert(cluster("a"))
        assertEquals(listOf("a"), dao.claim(listOf("a"), "old", 1_000, 0).map { it.clusterId })
        assertEquals("SUMMARIZING", dao.get("a")!!.state)
        assertEquals(listOf("a"), dao.claim(listOf("a"), "new", 400_000, 300_000).map { it.clusterId }) // the old claim went stale and was retaken
        assertEquals(0, dao.completeIfClaimed("a", "old", "rokid_cl_old_0", 400_001))
        assertEquals("SUMMARIZING", dao.get("a")!!.state)
        assertEquals(1, dao.completeIfClaimed("a", "new", "rokid_cl_new_0", 400_002))
        val r = dao.get("a")!!
        assertEquals("SUMMARIZED", r.state); assertEquals("rokid_cl_new_0", r.memoryId); assertNull(r.claimId); assertNull(r.claimedAt)
        assertEquals(0, dao.completeIfClaimed("a", "new", "rokid_cl_new_0", 400_003))
    }

    @Test fun claimTakesOnlyClaimableRows() = runBlocking {
        dao.upsert(cluster("open", "OPEN", closedAt = null)); dao.upsert(cluster("closed")); dao.upsert(cluster("done", "SUMMARIZED")); dao.upsert(cluster("final", "FAILED_FINAL"))
        dao.upsert(cluster("live", "SUMMARIZING", claimedAt = 9_000, claimId = "x"))
        assertEquals(listOf("closed"), dao.claim(listOf("open", "closed", "done", "final", "live"), "c", 10_000, 5_000).map { it.clusterId })
        assertEquals("x", dao.get("live")!!.claimId)
    }

    @Test fun abandonedClaimCountsAttempt() = runBlocking {
        dao.upsert(cluster("old", "SUMMARIZING", claimedAt = 1_000, claimId = "y")); dao.upsert(cluster("fresh", "SUMMARIZING", claimedAt = 9_000, claimId = "z"))
        assertEquals(setOf("old"), dao.claimable(10_000, 5_000, 10).map { it.clusterId }.toSet())
        val got = dao.claim(listOf("old", "fresh"), "c1", 10_000, 5_000)
        assertEquals(listOf("old"), got.map { it.clusterId }); assertEquals("c1", got[0].claimId); assertEquals(1, dao.get("old")!!.attempts)
        assertEquals("z", dao.get("fresh")!!.claimId); assertEquals(0, dao.get("fresh")!!.attempts)
        // a fifth abandonment ends in FAILED_FINAL and is not claimed
        dao.upsert(cluster("last", "SUMMARIZING", attempts = 4, claimedAt = 1_000, claimId = "q"))
        assertEquals(emptyList<String>(), dao.claim(listOf("last"), "c2", 10_000, 5_000).map { it.clusterId })
        assertEquals("FAILED_FINAL", dao.get("last")!!.state); assertEquals(5, dao.get("last")!!.attempts)
        assertEquals(0, dao.requeueAbandoned(10_000, 5_000))
    }

    @Test fun requeueAbandonedMovesStaleSummarizingBack() = runBlocking {
        dao.upsert(cluster("old", "SUMMARIZING", claimedAt = 1_000, claimId = "y")); dao.upsert(cluster("fresh", "SUMMARIZING", claimedAt = 9_000, claimId = "z"))
        assertEquals(1, dao.requeueAbandoned(10_000, 5_000))
        assertEquals("CLOSED", dao.get("old")!!.state); assertEquals(1, dao.get("old")!!.attempts); assertEquals("abandoned", dao.get("old")!!.lastError); assertNull(dao.get("old")!!.claimId)
        assertEquals("SUMMARIZING", dao.get("fresh")!!.state)
    }

    @Test fun fifthFailureIsFinal() = runBlocking {
        dao.upsert(cluster("a"))
        repeat(4) { i ->
            dao.claim(listOf("a"), "c$i", 1_000L * i, 0)
            assertEquals(1, dao.fail("a", "c$i", "boom", 1_000L * i))
            assertEquals("FAILED", dao.get("a")!!.state)
        }
        dao.claim(listOf("a"), "c4", 9_000, 0)
        assertEquals(0, dao.fail("a", "other", "boom", 9_000)) // not the claim holder
        assertEquals(1, dao.fail("a", "c4", "boom", 9_000))
        val r = dao.get("a")!!
        assertEquals("FAILED_FINAL", r.state); assertEquals(5, r.attempts); assertEquals("boom", r.lastError); assertNull(r.claimId)
        assertEquals(emptyList<String>(), dao.claimable(10_000_000, 0, 10).map { it.clusterId })
    }

    @Test fun releaseGivesBackWithoutAnAttempt() = runBlocking {
        dao.upsert(cluster("a")); dao.claim(listOf("a"), "c", 1_000, 0)
        assertEquals(0, dao.release("a", "other", 2_000))
        assertEquals(1, dao.release("a", "c", 2_000))
        val r = dao.get("a")!!; assertEquals("CLOSED", r.state); assertEquals(0, r.attempts); assertNull(r.claimId)
    }

    @Test fun releaseAtStartOnlyOldClaims() = runBlocking {
        dao.upsert(cluster("old", "SUMMARIZING", claimedAt = 1_000, claimId = "y")); dao.upsert(cluster("thisProcess", "SUMMARIZING", claimedAt = 9_000, claimId = "z"))
        dao.upsert(cluster("closed")); dao.upsert(cluster("done", "SUMMARIZED"))
        assertEquals(1, dao.releaseAllSummarizing(10_000, claimedBeforeMs = 5_000))
        assertEquals("CLOSED", dao.get("old")!!.state); assertEquals(0, dao.get("old")!!.attempts); assertNull(dao.get("old")!!.claimId)
        assertEquals("SUMMARIZING", dao.get("thisProcess")!!.state); assertEquals("SUMMARIZED", dao.get("done")!!.state)
    }

    @Test fun failedBackoffRespected() = runBlocking {
        // FAILED after 1 attempt waits 30 s, after 3 attempts 2 min, and never more than 30 min.
        dao.upsert(cluster("one", "FAILED", attempts = 1, updatedAt = 100_000)); dao.upsert(cluster("three", "FAILED", attempts = 3, updatedAt = 100_000))
        dao.upsert(cluster("many", "FAILED", attempts = 4, updatedAt = 100_000))
        fun ids(now: Long) = runBlocking { dao.claimable(now, 0, 10).map { it.clusterId }.toSet() }
        assertEquals(emptySet<String>(), ids(120_000))
        assertEquals(setOf("one"), ids(130_000))
        assertEquals(setOf("one", "three"), ids(100_000 + 120_000))
        assertEquals(setOf("one", "three"), ids(100_000 + 239_999)) // attempts 4 = 4 min
        assertEquals(setOf("one", "three", "many"), ids(100_000 + 240_000))
        dao.upsert(cluster("capped", "FAILED", attempts = 30, updatedAt = 100_000)) // huge shift is capped at 30 min
        assertEquals(false, "capped" in ids(100_000 + 1_799_999)); assertEquals(true, "capped" in ids(100_000 + 1_800_000))
    }

    @Test fun claimableIsOldestClosedFirstAndLimited() = runBlocking {
        dao.upsert(cluster("b", closedAt = 200)); dao.upsert(cluster("a", closedAt = 100)); dao.upsert(cluster("c", closedAt = 300))
        assertEquals(listOf("a", "b"), dao.claimable(1_000, 0, 2).map { it.clusterId })
    }

    @Test fun countsByState() = runBlocking {
        dao.upsert(cluster("a")); dao.upsert(cluster("b")); dao.upsert(cluster("c", "OPEN", closedAt = null))
        assertEquals(mapOf("CLOSED" to 2, "OPEN" to 1), dao.counts().first())
    }
}
