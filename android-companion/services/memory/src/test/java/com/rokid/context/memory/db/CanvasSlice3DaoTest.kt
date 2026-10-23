package com.rokid.context.memory.db

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Slice 3a (Room v7): the fenced DAO writes. Every derived commit re-checks, inside its transaction, the generation, the persisted epoch, tombstones, source revisions and labelRev. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class CanvasSlice3DaoTest {
    private val db = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    private val dao = db.canvasSlice3Dao()
    @After fun close() = db.close()

    private var generation = 5L
    private var epoch = DeletionEpoch("install-a", 3, 1_000)
    private fun fence() = DaoFence(generation, epoch, { generation }, { epoch })

    private fun trace(id: String, revision: Int = 0, epoch: DeletionEpoch = this.epoch, parent: String? = null) =
        CanvasTraceEntity(id, "IMU", "COMPLETE", "{}", 5, false, revision, parent, epoch.installId, epoch.counter, true, 10_000, 20_000, 1)
    private fun label(id: String, atMs: Long = 5_000, frameId: String? = null) = CanvasLabelEntity(id, "MOMENT", "tap", "COMMUTING", atMs, frameId, null, "", 3, 1)
    private fun replay(id: String, labelRev: Long = 0, sweepId: String? = null, sources: List<TraceSource> = emptyList(), from: Long? = null, to: Long? = null) =
        CanvasReplayEntity(id, sweepId, "{}", "EXACT", null, false, TraceSources.encode(sources), labelRev, "RUNNING", from, to, 1)
    private fun src(id: String, rev: Int = 0) = TraceSource(id, rev, 10_000, 20_000)

    // --- labelRev ---
    @Test fun labelWriteBumpsLabelRev() = runBlocking {
        assertEquals(0L, dao.labelRev())
        assertTrue(dao.insertLabelFenced(label("l1"), fence()))
        assertEquals(1L, dao.labelRev())
        assertTrue(dao.correctLabel("l1", "WALKING_HOME"))
        assertEquals(2L, dao.labelRev())
        assertEquals("WALKING_HOME", dao.label("l1")!!.name)
        assertTrue(dao.deleteLabel("l1"))
        assertEquals(3L, dao.labelRev())
        assertFalse("nothing to delete: no bump", dao.deleteLabel("l1"))
        assertEquals(3L, dao.labelRev())
        assertEquals(3L, dao.bumpLabelRev() - 1)
    }

    @Test fun aRefusedLabelWriteDoesNotBumpLabelRev() = runBlocking {
        generation = 6 // a Delete all moved the in-process generation after the fence was captured
        val stale = DaoFence(5, epoch, { generation }, { epoch })
        assertFalse(dao.insertLabelFenced(label("l1"), stale))
        assertNull(dao.label("l1")); assertEquals(0L, dao.labelRev())
    }

    @Test fun aMarkBeforeTheLatestWipeIsDropped() = runBlocking {
        epoch = DeletionEpoch("install-a", 3, 8_000) // wiped at 8 000
        val f = fence()
        assertFalse("occurred before the wipe", dao.insertLabelFenced(label("old", atMs = 7_999), f))
        assertTrue("at the wipe instant or later is kept", dao.insertLabelFenced(label("new", atMs = 8_000), f))
        assertEquals(listOf("new"), dao.labels().map { it.labelId })
    }

    @Test fun aMovedPersistedEpochBlocksALabelEvenWithTheSameGeneration() = runBlocking {
        val f = fence()
        epoch = DeletionEpoch("install-a", 4, 2_000) // another process committed a Delete all
        assertFalse(dao.insertLabelFenced(label("l1", atMs = 9_000), f))
        assertEquals(0, dao.labels().size)
    }

    @Test fun labelCarriesNoTextColumn() = runBlocking {
        dao.insertLabelFenced(label("l1"), fence())
        val cols = db.openHelper.readableDatabase.query("PRAGMA table_info(canvas_label)").use { c -> buildList { while (c.moveToNext()) add(c.getString(1)) } }
        assertTrue(cols.none { it.equals("text", true) || it.equals("transcript", true) })
    }

    // --- replay commits ---
    @Test fun replayCommitSucceedsWhenEverythingHolds() = runBlocking {
        assertTrue(dao.acceptTraceFenced(trace("t1"), fence()))
        dao.insertReplay(replay("r1", sources = listOf(src("t1"))))
        assertTrue(dao.commitReplayFenced("r1", "{\"keeps\":3}", null, listOf(src("t1")), 0, fence(), 50))
        val r = dao.replay("r1")!!
        assertEquals("DONE", r.jobState); assertEquals("{\"keeps\":3}", r.resultJson); assertEquals(50L, r.finishedAt)
    }

    @Test fun replayCommitBlockedByTombstone() = runBlocking {
        dao.acceptTraceFenced(trace("t1"), fence())
        dao.insertReplay(replay("r1", sources = listOf(src("t1"))))
        dao.tombstoneTrace("t1", 30)
        assertFalse(dao.commitReplayFenced("r1", "{}", null, listOf(src("t1")), 0, fence(), 50))
        val r = dao.replay("r1")!!
        assertEquals("RUNNING", r.jobState); assertNull(r.resultJson)
    }

    @Test fun replayCommitBlockedWhenARewriteMovedTheRevision() = runBlocking {
        dao.acceptTraceFenced(trace("t1", revision = 1), fence())
        dao.insertReplay(replay("r1", sources = listOf(src("t1", 0))))
        assertFalse("the source was read at revision 0", dao.commitReplayFenced("r1", "{}", null, listOf(src("t1", 0)), 0, fence(), 50))
    }

    @Test fun replayCommitBlockedWhenASourceIsMissing() = runBlocking {
        dao.insertReplay(replay("r1", sources = listOf(src("ghost"))))
        assertFalse(dao.commitReplayFenced("r1", "{}", null, listOf(src("ghost")), 0, fence(), 50))
    }

    @Test fun replayCommitBlockedWhenLabelRevMoved() = runBlocking {
        dao.acceptTraceFenced(trace("t1"), fence())
        dao.insertReplay(replay("r1", labelRev = 0, sources = listOf(src("t1"))))
        dao.insertLabelFenced(label("l1"), fence()) // labelRev 0 -> 1 while the replay ran
        assertFalse(dao.commitReplayFenced("r1", "{}", null, listOf(src("t1")), 0, fence(), 50))
        assertEquals("RUNNING", dao.replay("r1")!!.jobState)
        assertTrue("recomputed under the new labelRev it commits", dao.commitReplayFenced("r1", "{}", null, listOf(src("t1")), 1, fence(), 50))
    }

    @Test fun replayCommitBlockedByWipeGenerationAndByPersistedEpoch() = runBlocking {
        dao.acceptTraceFenced(trace("t1"), fence())
        dao.insertReplay(replay("r1", sources = listOf(src("t1")))); dao.insertReplay(replay("r2", sources = listOf(src("t1"))))
        val f = fence()
        generation = 6
        assertFalse(dao.commitReplayFenced("r1", "{}", null, listOf(src("t1")), 0, f, 50))
        generation = 5; epoch = DeletionEpoch("install-b", 3, 1_000) // a re-pairing: another installId
        assertFalse(dao.commitReplayFenced("r2", "{}", null, listOf(src("t1")), 0, f, 50))
        assertEquals(listOf("RUNNING", "RUNNING"), listOf(dao.replay("r1")!!.jobState, dao.replay("r2")!!.jobState))
    }

    @Test fun anInvalidatedOrCancelledReplayCannotBeCommitted() = runBlocking {
        dao.acceptTraceFenced(trace("t1"), fence())
        dao.insertReplay(replay("r1", sources = listOf(src("t1")))); dao.insertReplay(replay("r2", sources = listOf(src("t1"))))
        dao.invalidateBySources(listOf("t1"), null, null, false)
        assertFalse(dao.commitReplayFenced("r1", "{}", null, listOf(src("t1")), 0, fence(), 50))
        dao.insertReplay(replay("r3", sources = listOf(src("t1"))))
        assertEquals(1, dao.cancelReplay("r3", 40))
        assertFalse(dao.commitReplayFenced("r3", "{}", null, listOf(src("t1")), 0, fence(), 50))
        assertEquals("CANCELLED", dao.replay("r3")!!.jobState)
    }

    @Test fun sweepCommitIsFencedLikeAReplay() = runBlocking {
        dao.acceptTraceFenced(trace("t1"), fence())
        dao.insertSweep(CanvasSweepEntity("s1", "{}", "[]", null, false, TraceSources.encode(listOf(src("t1"))), 0, "RUNNING", null, null, 1))
        dao.tombstoneTrace("t1", 30)
        assertFalse(dao.commitSweepFenced("s1", "{}", null, listOf(src("t1")), 0, fence(), 50))
        dao.acceptTraceFenced(trace("t2"), fence())
        dao.insertSweep(CanvasSweepEntity("s2", "{}", "[]", null, false, TraceSources.encode(listOf(src("t2"))), 0, "RUNNING", null, null, 1))
        assertTrue(dao.commitSweepFenced("s2", "{\"pareto\":[]}", null, listOf(src("t2")), 0, fence(), 50))
        assertEquals("DONE", dao.sweep("s2")!!.jobState)
    }

    // --- jobs ---
    @Test fun startupAbortsRunningJobs() = runBlocking {
        dao.insertReplay(replay("running")); dao.insertReplay(replay("done").copy(jobState = "DONE")); dao.insertReplay(replay("cancelled").copy(jobState = "CANCELLED"))
        dao.insertSweep(CanvasSweepEntity("srun", "{}", createdAt = 1)); dao.insertSweep(CanvasSweepEntity("sdone", "{}", jobState = "DONE", createdAt = 1))
        assertEquals(2, dao.markRunningAborted(99))
        assertEquals("ABORTED", dao.replay("running")!!.jobState); assertEquals(99L, dao.replay("running")!!.finishedAt)
        assertEquals("DONE", dao.replay("done")!!.jobState); assertEquals("CANCELLED", dao.replay("cancelled")!!.jobState)
        assertEquals("ABORTED", dao.sweep("srun")!!.jobState); assertEquals("DONE", dao.sweep("sdone")!!.jobState)
        assertEquals("idempotent", 0, dao.markRunningAborted(100))
    }

    // --- traces ---
    @Test fun aTraceFromAnOlderEpochOrAnotherInstallIsRejected() = runBlocking {
        assertFalse(dao.acceptTraceFenced(trace("old", epoch = DeletionEpoch("install-a", 2, 0)), fence()))
        assertFalse(dao.acceptTraceFenced(trace("other", epoch = DeletionEpoch("install-b", 3, 0)), fence()))
        assertNull(dao.trace("old")); assertNull(dao.trace("other"))
        assertTrue(dao.acceptTraceFenced(trace("now"), fence()))
    }

    @Test fun aLateChunkForATombstonedTraceIsRejectedEvenForAnIdNeverSeen() = runBlocking {
        dao.tombstoneTrace("never-seen", 10)
        assertFalse(dao.acceptTraceFenced(trace("never-seen"), fence()))
        assertTrue(dao.trace("never-seen")!!.tombstoned)
        dao.acceptTraceFenced(trace("t1"), fence())
        assertTrue(dao.appendEventsFenced("t1", listOf(CanvasTraceEventEntity("t1", 0, 1, "CTX", "{}")), fence()))
        dao.tombstoneTrace("t1", 11)
        assertEquals(0, dao.eventCount("t1"))
        assertFalse(dao.appendEventsFenced("t1", listOf(CanvasTraceEventEntity("t1", 1, 2, "CTX", "{}")), fence()))
        assertFalse(dao.acceptTraceFenced(trace("t1"), fence()))
        assertEquals(listOf<String>(), dao.liveTraces().map { it.traceId })
    }

    @Test fun aRewriteGetsANewIdAndTheOriginalStaysTombstoned() = runBlocking {
        dao.acceptTraceFenced(trace("t1"), fence())
        assertTrue(dao.acceptTraceFenced(trace("t1b", revision = 1, parent = "t1"), fence()))
        dao.tombstoneTrace("t1", 20)
        assertEquals(listOf("t1b"), dao.liveTraces().map { it.traceId })
        assertEquals("t1", dao.trace("t1b")!!.parentTraceId)
    }

    @Test fun eventsOfAnUnknownTraceAreRefused() = runBlocking {
        assertFalse(dao.appendEventsFenced("ghost", listOf(CanvasTraceEventEntity("ghost", 0, 1, "CTX", "{}")), fence()))
    }

    // --- invalidation ---
    @Test fun invalidateBySourcesDeletesTheResultsOfTheTraceAndNoOthers() = runBlocking {
        dao.insertReplay(replay("hit", sources = listOf(src("t1"))).copy(jobState = "DONE", resultJson = "{\"x\":1}"))
        dao.insertReplay(replay("miss", sources = listOf(src("t10"))).copy(jobState = "DONE", resultJson = "{\"x\":2}"))
        assertEquals(1, dao.invalidateBySources(listOf("t1"), null, null, false))
        assertTrue(dao.replay("hit")!!.invalidated); assertNull(dao.replay("hit")!!.resultJson)
        assertFalse(dao.replay("miss")!!.invalidated); assertNotNull(dao.replay("miss")!!.resultJson)
    }

    @Test fun invalidateBySourcesByTimeRangeAndCascadeToTheSweepsReplays() = runBlocking {
        dao.insertReplay(replay("in", from = 100, to = 200).copy(jobState = "DONE", resultJson = "{}"))
        dao.insertReplay(replay("out", from = 500, to = 600).copy(jobState = "DONE", resultJson = "{}"))
        dao.insertReplay(replay("unknown-range").copy(jobState = "DONE", resultJson = "{}"))
        dao.insertSweep(CanvasSweepEntity("s", "{}", resultJson = "{}", jobState = "DONE", fromMs = 250, toMs = 260, createdAt = 1))
        dao.insertReplay(replay("child", sweepId = "s", from = 900, to = 950).copy(jobState = "DONE", resultJson = "{}"))
        assertEquals(4, dao.invalidateBySources(emptyList(), 180, 300, false)) // in, the one with no range (fail closed), sweep s, then its child
        assertTrue(dao.replay("in")!!.invalidated); assertTrue(dao.sweep("s")!!.invalidated); assertTrue(dao.replay("child")!!.invalidated)
        assertFalse(dao.replay("out")!!.invalidated); assertTrue("no time range: any range Forget invalidates it", dao.replay("unknown-range")!!.invalidated)
        assertNull(dao.sweep("s")!!.resultJson); assertNull(dao.replay("child")!!.resultJson)
    }

    @Test fun invalidateByLabelRevOnlyHitsResultsComputedUnderAnotherRev() = runBlocking {
        dao.insertReplay(replay("old", labelRev = 0).copy(jobState = "DONE", resultJson = "{}"))
        dao.insertLabelFenced(label("l1"), fence()) // labelRev -> 1
        dao.insertReplay(replay("fresh", labelRev = 1).copy(jobState = "DONE", resultJson = "{}"))
        assertEquals(0, dao.invalidateBySources(emptyList(), null, null, false))
        assertEquals(1, dao.invalidateBySources(emptyList(), null, null, true))
        assertTrue(dao.replay("old")!!.invalidated); assertFalse(dao.replay("fresh")!!.invalidated)
    }

    // --- review fixes ---
    @Test fun replayAndSweepCommitCarryTheConfigHash() = runBlocking {
        dao.acceptTraceFenced(trace("t1"), fence())
        dao.insertReplay(replay("r1", sources = listOf(src("t1"))).copy(configHash = "c1"))
        assertFalse("another config's result must not land on this row", dao.commitReplayFenced("r1", "{}", "c2", listOf(src("t1")), 0, fence(), 50))
        assertFalse(dao.commitReplayFenced("r1", "{}", null, listOf(src("t1")), 0, fence(), 50))
        assertTrue(dao.commitReplayFenced("r1", "{}", "c1", listOf(src("t1")), 0, fence(), 50))
        dao.insertSweep(CanvasSweepEntity("s1", "{}", sourcesJson = TraceSources.encode(listOf(src("t1"))), createdAt = 1, configHash = "c1"))
        assertFalse(dao.commitSweepFenced("s1", "{}", "c9", listOf(src("t1")), 0, fence(), 50))
        assertTrue(dao.commitSweepFenced("s1", "{}", "c1", listOf(src("t1")), 0, fence(), 50))
    }

    @Test fun exactHashReplaysAreFoundByConfigHash() = runBlocking {
        dao.insertReplay(replay("a", from = 1, to = 2).copy(configHash = "c1", jobState = "DONE", resultJson = "{}", replayClass = "EXACT"))
        dao.insertReplay(replay("b").copy(configHash = "c1", jobState = "DONE", resultJson = "{}", replayClass = "COUNTERFACTUAL"))
        dao.insertReplay(replay("c").copy(configHash = "c2", jobState = "DONE", resultJson = "{}", replayClass = "EXACT"))
        dao.insertReplay(replay("d").copy(configHash = "c1", jobState = "DONE", replayClass = "EXACT").copy(invalidated = true))
        assertEquals(listOf("a"), dao.validExactReplays("c1").map { it.replayId })
    }

    @Test fun aCorruptSourcesColumnRefusesTheCommit() = runBlocking {
        dao.acceptTraceFenced(trace("t1"), fence())
        dao.insertReplay(replay("r1", sources = listOf(src("t1"))).copy(sourcesJson = "{not json"))
        assertFalse("unknown sources: fail closed", dao.commitReplayFenced("r1", "{}", null, listOf(src("t1")), 0, fence(), 50))
        assertNull(TraceSources.decode("{not json"))
        assertNull(TraceSources.decode("[{\"traceId\":\"a\"}]"))
    }

    @Test fun aDuplicateMarkIsIdempotentAndDoesNotBumpLabelRev() = runBlocking {
        assertTrue(dao.insertLabelFenced(label("l1"), fence()))
        assertEquals(1L, dao.labelRev())
        assertTrue("already recorded counts as stored", dao.insertLabelFenced(label("l1"), fence()))
        assertEquals("a re-sent mark must not refuse every in-flight replay commit", 1L, dao.labelRev())
        assertEquals(1, dao.labels().size)
    }

    @Test fun labelRangeDeleteAndRangeInvalidationShareInclusiveBounds() = runBlocking {
        listOf("a" to 10_100L, "b" to 10_200L, "c" to 10_300L).forEach { (id, at) -> dao.insertLabelFenced(label(id, atMs = at), fence()) }
        assertEquals("both ends inclusive", 2, dao.deleteLabelsBetween(10_100, 10_200))
        assertEquals(listOf("c"), dao.labels().map { it.labelId })
        dao.insertReplay(replay("edge", from = 10_300, to = 10_400).copy(jobState = "DONE", resultJson = "{}"))
        assertEquals(1, dao.invalidateBySources(emptyList(), 10_200, 10_300, false))
    }

    // --- group state, calibration, helper jobs ---
    @Test fun groupStateRoundTrip() = runBlocking {
        val s = CanvasGroupStateEntity("g1", 4, 77, "cfgHash", "capture_plan", "SENT", 10, mode = "TRIAL", phoneInstall = "install-a", sentAtMs = 9)
        dao.upsertGroupState(s)
        assertEquals(s, dao.groupState("g1"))
        dao.upsertGroupState(s.copy(gen = 5, state = "EFFECTIVE", updatedAt = 20))
        assertEquals("EFFECTIVE", dao.groupState("g1")!!.state); assertEquals(5L, dao.groupState("g1")!!.gen); assertEquals("install-a", dao.groupState("g1")!!.phoneInstall)
        assertEquals(1, dao.groupStates().size)
        assertEquals(1, dao.deleteGroupState("g1")); assertNull(dao.groupState("g1"))
    }

    @Test fun planPublishColumnsRoundTripAndOldPublishesHaveNulls() = runBlocking {
        val c = db.canvasDao()
        val old = c.insertPublish(CanvasPublishEntity(pipelineId = "p", version = 1, hash = "h", publishedAt = 1, rolledBackAt = null, snapshotJson = "{}"))
        val plan = c.insertPublish(CanvasPublishEntity(pipelineId = "p", version = 2, hash = "h2", publishedAt = 2, rolledBackAt = null, snapshotJson = "{}", kind = "plan", mode = "TRIAL",
            groupId = "g1", component = "glasses", planJson = "{\"v\":1}", configHash = "c1", gen = 3, state = "STAGED", trialEndsAt = 9_000))
        val rows = c.publishHistory(10).associateBy { it.id }
        assertNull(rows[old]!!.groupId); assertNull(rows[old]!!.gen); assertEquals("prompts", rows[old]!!.kind)
        assertEquals("g1", rows[plan]!!.groupId); assertEquals(3L, rows[plan]!!.gen); assertEquals(9_000L, rows[plan]!!.trialEndsAt); assertEquals("c1", rows[plan]!!.configHash)
    }

    @Test fun calibrationRoundTripAndVerifiedCount() = runBlocking {
        dao.insertCalibration(CanvasCalibrationEntity(configHash = "c1", settingsRev = 2, mode = "LIVE", phasesJson = "[]", createdAt = 1))
        dao.insertCalibration(CanvasCalibrationEntity(configHash = "c1", settingsRev = 2, mode = "LIVE", phasesJson = "[]", verified = true, artifactJson = "{}", createdAt = 2))
        assertEquals(2, dao.calibrations("c1").size); assertEquals(1, dao.verifiedCount("c1")); assertEquals(0, dao.verifiedCount("c2"))
    }

    @Test fun forgetOfAPhotoDeletesItsLabelsInvalidatesResultsAndVoidsHelperJobs() = runBlocking {
        dao.insertLabelFenced(label("lf", frameId = "f1"), fence()); dao.insertLabelFenced(label("lo", frameId = "f2"), fence()); dao.insertLabelFenced(label("lm"), fence())
        dao.insertReplay(replay("r", labelRev = dao.labelRev()).copy(jobState = "DONE", resultJson = "{}"))
        dao.insertHelperJob(CanvasHelperJobEntity("j1", "py", "LEASED", "h", 5, "[\"f1\",\"f3\"]", 1, 1)); dao.insertHelperJob(CanvasHelperJobEntity("j2", "py", "QUEUED", null, null, "[\"f2\"]", 1, 1))
        dao.deleteSliceOutputsForFrames(listOf("f1"), 77)
        assertEquals(listOf("lm", "lo"), dao.labels().map { it.labelId }.sorted())
        assertTrue(dao.replay("r")!!.invalidated); assertNull(dao.replay("r")!!.resultJson)
        assertEquals("VOIDED", dao.helperJob("j1")!!.state); assertEquals("[]", dao.helperJob("j1")!!.frameIdsJson); assertNull(dao.helperJob("j1")!!.leaseOwner)
        assertEquals("3b WP4: a Forget of any frame moves frame_forget_rev, which voids every in-flight job (fail closed), with its cleanup row", "VOIDED", dao.helperJob("j2")!!.state)
        assertEquals(listOf("j1", "j2"), dao.pendingCleanup(10).map { it.jobId })
    }

    @Test fun traceSourcesRoundTrip() {
        val l = listOf(TraceSource("a\"b", 2, 1, 9), TraceSource("c", 0, 3, 4))
        assertEquals(l, TraceSources.decode(TraceSources.encode(l)))
        assertTrue(TraceSources.encode(l).contains(TraceSources.needle("c")))
        assertFalse("a prefix id is not a match", TraceSources.encode(listOf(TraceSource("t10", 0, 1, 2))).contains(TraceSources.needle("t1")))
    }
}
