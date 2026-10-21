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

    private suspend fun frame(id: String, ts: Long, state: String = "PENDING", attempts: Int = 0, updatedAt: Long = ts, dwell: Boolean = false) {
        db.frameDao().insertIgnore(FrameEntity(id, ts, "/x/$id.jpg", "rokid_glasses"))
        db.frameMetaDao().upsert(FrameMetaEntity(id, null, null, "STILL", dwell, null, null, null, null, null, null, null, null, null, ts, routedAt = ts))
        dao.insertIgnore(listOf(FrameStateEntity(id, state, attempts, null, null, null, updatedAt)))
    }

    @Test fun newestClaimableReturnsTheNewestOfALargeBacklog() = runBlocking {
        // 600 old rows whose updatedAt is OLDER than the three newest frames': an oldest-first scan of 500 never reaches them.
        dao.insertIgnore((1..600).map { FrameStateEntity("old$it", "PENDING", 0, null, null, null, it.toLong()) })
        frame("n1", 1_000_000); frame("n2", 1_000_100); frame("n3", 1_000_200)
        assertEquals(listOf("n3", "n2", "n1"), dao.newestClaimable(2_000_000, 0, Long.MIN_VALUE, 0, 3).map { it.frameId })
        assertEquals(listOf("n3", "n2"), dao.newestClaimable(2_000_000, 0, 1_000_100, 1, 10).map { it.frameId }) // sinceMs
    }

    @Test fun newestClaimableHonoursBackoffForgetAndAbandonment() = runBlocking {
        frame("p", 100); frame("f", 200, "FAILED", attempts = 1, updatedAt = 1_000) // 30 s backoff after attempt 1
        frame("f4", 300, "FAILED", attempts = 4, updatedAt = 1_000) // 4 min
        frame("fresh", 400, "EXTRACTING"); frame("gone", 500)
        dao.insertIgnore(listOf(FrameStateEntity("old", "EXTRACTING", 0, null, 1L, "c", 0)))
        db.openHelper.writableDatabase.execSQL("UPDATE frame_state SET claimedAt = 99000, claimId = 'live' WHERE frameId = 'fresh'")
        db.openHelper.writableDatabase.execSQL("INSERT INTO forget_tombstone(frameId, createdAt) VALUES ('gone', 1)")
        suspend fun ids(now: Long, ignore: Int = 0) = dao.newestClaimable(now, abandonBeforeMs = 50_000, sinceMs = Long.MIN_VALUE, ignoreBackoff = ignore, limit = 10).map { it.frameId }
        assertEquals(setOf("p", "old"), ids(now = 5_000).toSet()) // FAILED rows inside backoff, a live claim and the tombstoned frame are left out
        assertEquals(setOf("p", "f", "old"), ids(now = 31_001).toSet()) // f: 30 s over; f4 still waits 4 min
        assertEquals(setOf("p", "f", "f4", "old"), ids(now = 5_000, ignore = 1).toSet()) // an ask ignores backoff
        assertEquals(31_000L, dao.nextRetryAt())
        assertEquals(1, dao.liveClaimsSince(abandonBeforeMs = 50_000, sinceMs = 400))
        assertEquals(0, dao.liveClaimsSince(abandonBeforeMs = 50_000, sinceMs = 401))
    }

    @Test fun newestDwellClaimableFindsDwellKeyframesInAnyBacklog() = runBlocking {
        dao.insertIgnore((1..600).map { FrameStateEntity("old$it", "PENDING", 0, null, null, null, it.toLong()) })
        frame("d1", 100, dwell = true); frame("d2", 200, dwell = true); frame("plain", 300)
        assertEquals(listOf("d2", "d1"), dao.newestDwellClaimable(1_000, 0, 5).map { it.frameId })
    }

    // ---- wave 2 (Room v4) ----

    private suspend fun meta(id: String, routedAt: Long?, clusterId: String? = null, ts: Long = 1) {
        db.frameMetaDao().upsert(FrameMetaEntity(id, null, null, "STILL", false, null, null, null, null, null, null, clusterId, null, null, ts, routedAt))
    }

    private suspend fun cluster(id: String, state: String) = db.frameClusterDao().upsert(FrameClusterEntity(id, 1, null, null, "[]", null, state))

    @Test fun unroutedFrameNotClaimableForTenMinutes() = runBlocking {
        val t = 1_000_000L
        dao.insertIgnore(listOf(row("u").copy(updatedAt = t))); meta("u", routedAt = null)
        dao.insertIgnore(listOf(row("r").copy(updatedAt = t))); meta("r", routedAt = t)
        fun newest(now: Long) = runBlocking { dao.newestClaimable(now, 0, Long.MIN_VALUE, 0, 10).map { it.frameId }.toSet() }
        fun dwell(now: Long) = runBlocking { dao.newestDwellClaimable(now, 0, 10).map { it.frameId }.toSet() }
        assertEquals(setOf("r"), newest(t + 599_999)); assertEquals(setOf("r", "u"), newest(t + 600_000))
        assertEquals(setOf("r"), dao.recoverable(t + 599_999, 0, 10).map { it.frameId }.toSet()); assertEquals(setOf("r", "u"), dao.recoverable(t + 600_000, 0, 10).map { it.frameId }.toSet())
        db.openHelper.writableDatabase.execSQL("UPDATE frame_meta SET dwell = 1")
        assertEquals(setOf("r"), dwell(t + 599_999)); assertEquals(setOf("r", "u"), dwell(t + 600_000))
    }

    @Test fun legacyFrameWithoutMetaStillClaimable() = runBlocking {
        dao.insertIgnore(listOf(row("legacy").copy(updatedAt = 1_000)))
        assertEquals(listOf("legacy"), dao.newestClaimable(1_001, 0, Long.MIN_VALUE, 0, 10).map { it.frameId })
        assertEquals(listOf("legacy"), dao.recoverable(1_001, 0, 10).map { it.frameId })
    }

    @Test fun moveIsGuardedByTheFromState() = runBlocking {
        dao.insertIgnore(listOf(row("a"), row("b", "EXTRACTED")))
        assertEquals(1, dao.move(listOf("a", "b"), "PENDING", "IN_CLUSTER", null, 500))
        assertEquals("IN_CLUSTER", dao.get("a")!!.state); assertEquals(500L, dao.get("a")!!.updatedAt); assertEquals("EXTRACTED", dao.get("b")!!.state)
        assertEquals(1, dao.move(listOf("a"), "IN_CLUSTER", "NEAR_DUP", "why", 600)); assertEquals("why", dao.get("a")!!.lastError)
    }

    @Test fun settleClusterOnlyMovesInCluster() = runBlocking {
        dao.insertIgnore(listOf(row("in", "IN_CLUSTER"), row("done", "EXTRACTED"), row("pend"), row("other", "IN_CLUSTER")))
        meta("in", 1, "c1"); meta("done", 1, "c1"); meta("pend", 1, "c1"); meta("other", 1, "c2")
        assertEquals(1, dao.settleCluster("c1", "NEAR_DUP", "cluster_failed", 700))
        assertEquals("NEAR_DUP", dao.get("in")!!.state); assertEquals("cluster_failed", dao.get("in")!!.lastError)
        assertEquals("EXTRACTED", dao.get("done")!!.state); assertEquals("PENDING", dao.get("pend")!!.state); assertEquals("IN_CLUSTER", dao.get("other")!!.state)
    }

    @Test fun askClaimsInClusterAndUnrouted() = runBlocking {
        cluster("open", "OPEN"); cluster("closed", "CLOSED")
        dao.insertIgnore(listOf(row("unrouted"), row("routed"), row("inOpen", "IN_CLUSTER"), row("inClosed", "IN_CLUSTER"), row("done", "EXTRACTED"), row("noMeta", "IN_CLUSTER")))
        meta("unrouted", null); meta("routed", 5); meta("inOpen", 5, "open"); meta("inClosed", 5, "closed"); meta("done", 5)
        val got = dao.claimForAsk(listOf("unrouted", "routed", "inOpen", "inClosed", "done", "noMeta"), "ask", 1_000)
        assertEquals(setOf("unrouted", "routed", "inOpen", "inClosed"), got.map { it.frameId }.toSet())
        got.forEach { assertEquals("EXTRACTING", it.state); assertEquals("ask", it.claimId); assertEquals(1_000L, it.claimedAt) }
        assertEquals("EXTRACTED", dao.get("done")!!.state); assertEquals("IN_CLUSTER", dao.get("noMeta")!!.state)
        assertEquals(emptyList<FrameStateEntity>(), dao.claimForAsk(emptyList(), "x", 1))
    }

    @Test fun askSkipsSummarizingClusterMembers() = runBlocking {
        cluster("sum", "SUMMARIZING"); cluster("done", "SUMMARIZED"); cluster("failed", "FAILED")
        dao.insertIgnore(listOf(row("a", "IN_CLUSTER"), row("b", "IN_CLUSTER"), row("c", "IN_CLUSTER")))
        meta("a", 5, "sum"); meta("b", 5, "done"); meta("c", 5, "failed")
        assertEquals(emptyList<FrameStateEntity>(), dao.claimForAsk(listOf("a", "b", "c"), "ask", 1_000))
        listOf("a", "b", "c").forEach { assertEquals("IN_CLUSTER", dao.get(it)!!.state) }
    }

    @Test fun captionQueuedOldestFirst() = runBlocking {
        dao.insertIgnore(listOf(row("new", "CAPTION_QUEUED").copy(updatedAt = 300), row("old", "CAPTION_QUEUED").copy(updatedAt = 100), row("mid", "CAPTION_QUEUED").copy(updatedAt = 200), row("p")))
        assertEquals(listOf("old", "mid"), dao.captionQueued(2).map { it.frameId })
        val got = dao.claimCaptionQueued(listOf("old", "p", "new"), "cap", 1_000)
        assertEquals(setOf("old", "new"), got.map { it.frameId }.toSet())
        got.forEach { assertEquals("EXTRACTING", it.state); assertEquals("cap", it.claimId) }
        assertEquals("PENDING", dao.get("p")!!.state)
    }

    /** Task 9: the describe-before-delete marker keeps the live loop out of the night queue and survives the save. */
    @Test fun describeQueueIsTheNightJobsOwn() = runBlocking {
        val m = "describe_before_delete"
        dao.insertIgnore(listOf(
            row("live", "CAPTION_QUEUED").copy(updatedAt = 50), row("n2", "CAPTION_QUEUED").copy(updatedAt = 200, lastError = m),
            row("n1", "CAPTION_QUEUED").copy(updatedAt = 100, lastError = m),
        ))
        assertEquals(listOf("live"), dao.captionQueued(10).map { it.frameId }) // the live loop never takes a describe-before-delete photo
        assertEquals(listOf("n1", "n2", "live"), dao.describeQueued(1_000, 10).map { it.frameId }) // the night job: deadline order, then the rest
        val got = dao.claimCaptionQueued(listOf("n1"), "night", 1_000)
        assertEquals(1, got.size)
        assertEquals(1, dao.completeIfClaimed("n1", "night", "EXTRACTED", 2_000))
        assertEquals(m, dao.get("n1")!!.lastError) // the marker survives a successful save
        dao.insertIgnore(listOf(row("plain", "EXTRACTING", claimedAt = 1, claimId = "c").copy(lastError = "abandoned")))
        assertEquals(1, dao.completeIfClaimed("plain", "c", "EXTRACTED", 3_000)); assertNull(dao.get("plain")!!.lastError)
    }

    @Test fun queueForDescribeIsGuarded() = runBlocking {
        db.frameDao().insertIgnore(FrameEntity("cited", 1, "/x", "rokid_glasses"))
        db.memoryDao().insert(MemoryEntity("m", "cited", "t", "caption", "", MemoryTier.LONG, 1.0, ReviewState.OK, false, 1, false, "t"))
        dao.insertIgnore(listOf(
            row("ok", "EXTRACTED"), row("fin", "FAILED_FINAL", 5), row("pend", "PENDING"), row("cited", "EXTRACTED"), row("forgot", "EXTRACTED"),
            row("run", "EXTRACTING", 0, 1, "c"),
        ))
        db.forgetDao().insertTombstone(ForgetTombstoneEntity("forgot", 1))
        assertEquals(1, dao.queueForDescribe("ok", 777)); assertEquals(1, dao.queueForDescribe("fin", 778))
        for (id in listOf("pend", "cited", "forgot", "run", "nope")) assertEquals(id, 0, dao.queueForDescribe(id, 5))
        dao.get("ok")!!.let { assertEquals("CAPTION_QUEUED", it.state); assertEquals(0, it.attempts); assertEquals("describe_before_delete", it.lastError); assertEquals(777L, it.updatedAt) }
        assertEquals("CAPTION_QUEUED", dao.get("fin")!!.state)
        // an offline night call gives the claim back: a describe-before-delete photo returns to the night queue (never PENDING), an ordinary one to PENDING
        dao.claimCaptionQueued(listOf("ok"), "n", 1_000); dao.release("ok", "n", 1_100)
        assertEquals("CAPTION_QUEUED", dao.get("ok")!!.state); assertEquals("describe_before_delete", dao.get("ok")!!.lastError)
    }

    private val marker = "describe_before_delete"
    private val dayMs = 86_400_000L

    /** Fix I2: neither a failure, an abandoned claim, a release nor a process start takes a night photo out of the night queue or off its marker. */
    @Test fun markerSurvivesEveryExitAndTheLiveLoopNeverClaimsIt() = runBlocking {
        for (id in listOf("f", "ab", "rel", "start")) dao.insertIgnore(listOf(row(id, "CAPTION_QUEUED").copy(lastError = marker)))
        for (id in listOf("f", "ab", "rel", "start")) dao.claimCaptionQueued(listOf(id), "c$id", 1_000)
        assertEquals(1, dao.fail("f", "cf", "boom", 5_000))
        dao.get("f")!!.let { assertEquals("CAPTION_QUEUED", it.state); assertEquals(marker, it.lastError); assertEquals(1, it.attempts); assertEquals(5_000 + dayMs, it.claimedAt) }
        assertEquals(1, dao.release("rel", "crel", 6_000)); assertEquals("CAPTION_QUEUED", dao.get("rel")!!.state)
        assertEquals(2, dao.requeueAbandoned(20_000, 10_000)); // ab and start were claimed at 1_000: abandoned
        for (id in listOf("ab", "start")) dao.get(id)!!.let { assertEquals("CAPTION_QUEUED", it.state); assertEquals(marker, it.lastError); assertEquals(1, it.attempts) }
        // the live loop (claim, newestClaimable, newestDwellClaimable, recoverable) never sees a marker row, whatever state it is in
        dao.insertIgnore(listOf(row("stuckFailed", "FAILED", 1).copy(lastError = marker), row("stuckPending").copy(lastError = marker)))
        assertEquals(emptyList<FrameStateEntity>(), dao.newestClaimable(1_000_000, 0, Long.MIN_VALUE, 1, 10))
        assertEquals(emptyList<FrameStateEntity>(), dao.newestDwellClaimable(1_000_000, 0, 10))
        assertEquals(emptyList<FrameStateEntity>(), dao.recoverable(1_000_000, 0, 10))
        assertEquals(emptyList<FrameStateEntity>(), dao.claim(listOf("stuckFailed", "stuckPending"), "live", 1_000_000, 0))
        // process start: an EXTRACTING night row goes back to the night queue
        dao.claimCaptionQueued(listOf("rel"), "again", 7_000)
        assertEquals(1, dao.releaseAllExtracting(8_000, Long.MAX_VALUE)); assertEquals("CAPTION_QUEUED", dao.get("rel")!!.state)
    }

    /** Fix I1: 1, 2, 4, 8 days of cooldown, five failures park the photo; cooldown rows are skipped by the queue; Retry all leaves parked photos alone. */
    @Test fun failuresBackOffThenPark() = runBlocking {
        dao.insertIgnore(listOf(row("a", "CAPTION_QUEUED").copy(lastError = marker, updatedAt = 1), row("b", "CAPTION_QUEUED").copy(lastError = marker, updatedAt = 2)))
        var t = 1_000L
        val expected = listOf(1L, 2L, 4L, 8L)
        for ((i, d) in expected.withIndex()) {
            dao.claimCaptionQueued(listOf("a"), "c$i", t)
            assertEquals(1, dao.fail("a", "c$i", "boom", t))
            assertEquals(t + d * dayMs, dao.get("a")!!.claimedAt)
            assertEquals("cooldown: only b is offered", listOf("b"), dao.describeQueued(t + d * dayMs - 1, 10).map { it.frameId })
            t += d * dayMs
            assertEquals(setOf("a", "b"), dao.describeQueued(t, 10).map { it.frameId }.toSet())
        }
        dao.claimCaptionQueued(listOf("a"), "c5", t); assertEquals(1, dao.fail("a", "c5", "boom", t))
        dao.get("a")!!.let { assertEquals("FAILED_FINAL", it.state); assertEquals("describe_failed", it.lastError); assertEquals(5, it.attempts) }
        assertEquals(1, dao.describeFailed()); assertEquals(1, dao.describeWaiting())
        assertEquals(0, dao.retryAllFinal(t)); assertEquals("FAILED_FINAL", dao.get("a")!!.state)
        assertEquals(0, dao.queueForDescribe("a", t)) // parked: never queued by the night job on its own
        assertEquals(1, dao.retryDescribeFailed(t)); assertEquals(null, dao.get("a")!!.lastError); assertEquals(0, dao.get("a")!!.attempts)
        assertEquals(1, dao.queueForDescribe("a", t))
    }

    /** Fix I1: a describe that saves no memory is a failed attempt too; the attempts survive being queued again. */
    @Test fun noMemorySavedCountsAsAnAttempt() = runBlocking {
        dao.insertIgnore(listOf(row("a", "EXTRACTED").copy(lastError = marker, attempts = 4)))
        assertEquals(1, dao.noteUncitedDescribed(1_000)); dao.get("a")!!.let { assertEquals("FAILED_FINAL", it.state); assertEquals("describe_failed", it.lastError) }
        dao.insertIgnore(listOf(row("b", "EXTRACTED").copy(lastError = marker, attempts = 1)))
        assertEquals(1, dao.noteUncitedDescribed(1_000)); dao.get("b")!!.let { assertEquals("EXTRACTED", it.state); assertEquals(2, it.attempts); assertEquals(1_000 + 2 * dayMs, it.claimedAt) }
        assertEquals(0, dao.noteUncitedDescribed(2_000)) // already noted (claimedAt set)
        assertEquals(0, dao.queueForDescribe("b", 1_000)) // in its cooldown
        assertEquals(1, dao.queueForDescribe("b", 1_000 + 2 * dayMs)); assertEquals(2, dao.get("b")!!.attempts) // the count is kept
    }

    /** Fix round 2: a night claim a dead process never settled counts an attempt with a cooldown, so a photo that kills the process every night is parked. */
    @Test fun crashLoopOnANightPhotoIsBounded() = runBlocking {
        dao.insertIgnore(listOf(row("a", "CAPTION_QUEUED").copy(lastError = marker)))
        var t = 1_000L
        for ((i, d) in listOf(1L, 2L, 4L, 8L).withIndex()) {
            dao.claimCaptionQueued(listOf("a"), "c$i", t)
            assertEquals(1, dao.releaseAllExtracting(t + 10, Long.MAX_VALUE)) // the next process start
            dao.get("a")!!.let { assertEquals("CAPTION_QUEUED", it.state); assertEquals(i + 1, it.attempts); assertEquals(t + 10 + d * dayMs, it.claimedAt) }
            assertEquals(emptyList<FrameStateEntity>(), dao.describeQueued(t + d * dayMs, 10)) // still cooling down
            t += d * dayMs + 1_000
        }
        dao.claimCaptionQueued(listOf("a"), "c5", t); dao.releaseAllExtracting(t + 10, Long.MAX_VALUE)
        dao.get("a")!!.let { assertEquals("FAILED_FINAL", it.state); assertEquals("describe_failed", it.lastError) }
        // an abandoned claim (the 15-minute sweep) cools down the same way
        dao.insertIgnore(listOf(row("b", "CAPTION_QUEUED").copy(lastError = marker)))
        dao.claimCaptionQueued(listOf("b"), "cb", 1_000); assertEquals(1, dao.requeueAbandoned(100_000, 50_000))
        dao.get("b")!!.let { assertEquals(1, it.attempts); assertEquals(100_000 + dayMs, it.claimedAt) }
    }
}
