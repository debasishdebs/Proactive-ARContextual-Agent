package com.rokid.context.orchestration.forget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.rokid.context.ai.contract.ExtractOutput
import com.rokid.context.ai.contract.FrameCaption
import com.rokid.context.data.runtime.AppConfigStore
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.MemorySettings
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.FrameClusterEntity
import com.rokid.context.memory.db.FrameEntity
import com.rokid.context.memory.db.FrameMetaEntity
import com.rokid.context.memory.db.FrameSourceEntity
import com.rokid.context.memory.db.FrameStateEntity
import com.rokid.context.memory.db.MemoryEntity
import com.rokid.context.memory.db.MemoryTier
import com.rokid.context.memory.db.ReviewState
import com.rokid.context.memory.forget.ForgetService
import com.rokid.context.model.CallClass
import com.rokid.context.orchestration.ExtractSave
import com.rokid.context.orchestration.ExtractSaveResult
import com.rokid.context.orchestration.IndexMaintenance
import com.rokid.context.orchestration.MemoryWipe
import com.rokid.context.work.OrchestrationScheduler
import com.rokid.context.work.frames.ExtractOutcome
import com.rokid.context.work.frames.KeyframePipeline
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * Wave 2 Task 9: the night job (describe-before-delete, then the backfill) and the end-to-end proof that flips
 * `DESCRIBE_BEFORE_DELETE_READY`: a photo is never deleted undescribed, and a described one only at its deadline + 3 days.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class NightIndexWorkerTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val graph get() = MemoryGraph.get(ctx)
    private val db get() = graph.db
    private val hour = 3_600_000L
    private val day = 24 * hour
    private val now = 1_793_000_000_000L
    /** The pipeline's and the night job's clock: a test moves it to run several nights. */
    private var clockMs = now
    private val since = now - 30 * day // the per-kind clock was stamped long ago: no upgrade protection
    private val calls = mutableListOf<Pair<List<String>, CallClass>>()
    private var claimN = 0

    @Before fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(ctx)
        MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx))
        AppConfigStore.setPipelineEnabled(ctx, false) // isolation from Delete all's resume
    }
    @After fun tearDown() { AppConfigStore.setPipelineEnabled(ctx, true); db.close(); MemoryGraph.resetForTests(null) }

    private fun settings(photo: Int = 7) = MemorySettings(photoDays = photo, perKindSince = since)

    private suspend fun photo(
        id: String, ts: Long, state: String = "EXTRACTED", salience: Float? = null, dwell: Boolean = false, clusterId: String? = null,
        lastError: String? = null, linked: Boolean = false,
    ): File {
        val dir = File(ctx.filesDir, "rokid_archive").apply { mkdirs() }
        val jpg = File(dir, "$id.jpg").apply { writeBytes(byteArrayOf(1)) }
        File(dir, "$id.meta.json").writeText("{}")
        db.frameDao().insertIgnore(FrameEntity(id, ts, jpg.absolutePath, "rokid_glasses"))
        db.frameMetaDao().upsert(FrameMetaEntity(id, null, null, "STILL", dwell, null, null, null, 640, salience, null, clusterId, null, "sp", ts))
        db.frameStateDao().insertIgnore(listOf(FrameStateEntity(id, state, 0, lastError, null, null, ts)))
        if (linked) {
            db.memoryDao().insert(MemoryEntity("m_$id", id, "t", "caption", "", MemoryTier.LONG, 1.0, ReviewState.OK, false, ts, false, "t"))
            db.frameSourceDao().insertAll(listOf(FrameSourceEntity("m_$id", id, clusterId, "FACT")))
        }
        return jpg
    }

    private suspend fun cluster(id: String, state: String) = db.frameClusterDao().upsert(FrameClusterEntity(id, 1, 2, "gap", "[]", null, state = state))
    private suspend fun state(id: String) = db.frameStateDao().get(id)!!
    private suspend fun cited(id: String) = db.forgetDao().citedCount(listOf(id)) > 0

    /** The real save path (claim CAS, caption memory, frame_source) with a fake model: every photo gets a caption. */
    private fun describing(): suspend (List<String>, String, CallClass) -> ExtractOutcome = { ids, claim, cls ->
        calls += ids to cls
        when (val r = ExtractSave.saveExtraction(
            ctx, "rokid_kf_${claim}_", ExtractOutput(emptyList(), captions = ids.map { FrameCaption(it, "caption of $it") }, provenance = true),
            ids.toSet(), ids.associateWith { claim }, MemoryTier.SHORT, ReviewState.OK, nowMs = clockMs,
        )) {
            is ExtractSaveResult.Saved -> ExtractOutcome.Saved(r.memories)
            else -> ExtractOutcome.Stale()
        }
    }

    private fun pipeline(extractor: suspend (List<String>, String, CallClass) -> ExtractOutcome = describing()) =
        KeyframePipeline(db, extractor, { clockMs }, null, newClaimId = { "c${claimN++}" }, pollMs = 50)

    private fun night(
        p: KeyframePipeline = pipeline(), s: MemorySettings = settings(), gate: () -> Boolean = { true },
        maintenance: suspend () -> IndexMaintenance.Outcome? = { null }, described: MutableList<Int>? = null,
    ) = NightIndex(db, p, { s }, gate, maintenance, { clockMs }, { described?.add(it) }, drainMs = 30_000L)

    /** One retention pass at [at] deleting the listed files like the worker does. */
    private fun retention(at: Long, ready: Boolean, s: MemorySettings = settings()) = runBlocking {
        Retention(graph, ForgetService(db, graph.proposals) { at }, describeBeforeDeleteReady = ready).run(s, at) { r -> FrameSweep.delete(r.framesToDelete) }
    }

    // --- the night job ---------------------------------------------------------------------------------------------------

    @Test fun describesOnlyUnlinkedNonSkippedDuePhotos() = runBlocking {
        db.frameClusterDao().upsert(FrameClusterEntity("cSum", 1, 2, "gap", "[\"rep\"]", null, state = "SUMMARIZED")); cluster("cOpen", "CLOSED")
        val dueTs = now - 6 * day - 12 * hour // due in 12 h
        photo("due", dueTs)
        photo("dueFailedFinal", dueTs, "FAILED_FINAL", lastError = "cluster_failed")
        photo("dueKeyMoment", dueTs, "NEAR_DUP", salience = 0.8f)
        photo("linked", dueTs, linked = true)
        photo("skipped", dueTs, "NEAR_DUP", salience = 0.1f)
        photo("shed", dueTs, "NEAR_DUP", salience = 0.0f, lastError = "shed_backlog")
        photo("notDue", now - 2 * day)
        photo("dwellNotDue", now - day, dwell = true)
        photo("inCluster", dueTs, "IN_CLUSTER", salience = 0.9f, clusterId = "cOpen")
        photo("unsettled", dueTs, "CLUSTERED", salience = 0.9f, clusterId = "cOpen")
        photo("pending", dueTs, "PENDING")
        photo("rep", dueTs, "CLUSTERED", salience = 0.1f, clusterId = "cSum") // scored low but a representative (repFrameIds), unlinked: not skipped, so described
        val ids = mutableListOf<Int>()
        val r = night(described = ids).run()
        assertEquals(setOf("due", "dueFailedFinal", "dueKeyMoment", "rep"), calls.flatMap { it.first }.toSet())
        assertEquals(4, r.queued); assertEquals(4, r.described); assertEquals(0, r.left); assertEquals(listOf(4), ids)
        for (id in listOf("due", "dueFailedFinal", "dueKeyMoment", "rep")) {
            assertTrue("$id described", cited(id)); assertEquals("EXTRACTED", state(id).state)
            assertEquals("the marker survives the save", "describe_before_delete", state(id).lastError)
        }
        for (id in listOf("linked", "skipped", "shed", "notDue", "dwellNotDue")) assertFalse("$id untouched", calls.any { id in it.first })
        for (id in listOf("inCluster", "unsettled", "pending")) assertFalse("$id untouched", calls.any { id in it.first })
        assertEquals("IN_CLUSTER", state("inCluster").state); assertEquals("PENDING", state("pending").state)
        assertEquals("CLUSTERED", state("unsettled").state)
    }

    @Test fun ordinarySkippedNeverDescribed() = runBlocking {
        cluster("cSum", "SUMMARIZED")
        val old = now - 20 * day
        photo("dup", old, "NEAR_DUP", salience = 0.2f)
        photo("shed", old, "NEAR_DUP", salience = 0.0f, lastError = "shed_backlog")
        photo("member", old, "CLUSTERED", salience = 0.1f, clusterId = "cSum")
        photo("expiredKey", old, "NEAR_DUP", salience = 0.9f, lastError = "caption_expired")
        night().run()
        assertTrue(calls.isEmpty())
        assertEquals("NEAR_DUP", state("dup").state); assertEquals("CLUSTERED", state("member").state)
    }

    @Test fun usesBackfillClass() = runBlocking {
        photo("a", now - 7 * day); photo("b", now - 7 * day)
        night().run()
        assertTrue(calls.isNotEmpty()); assertEquals(setOf(CallClass.BACKFILL), calls.map { it.second }.toSet())
    }

    @Test fun queueIsInDeadlineOrder() = runBlocking {
        photo("late", now - 6 * day - 20 * hour); photo("early", now - 9 * day); photo("mid", now - 8 * day)
        night().run()
        assertEquals(listOf("early", "mid", "late"), calls.flatMap { it.first })
    }

    @Test fun failureAndOfflineLeaveThePhotoUndescribed() = runBlocking {
        photo("a", now - 7 * day)
        val failing = night(pipeline { ids, _, cls -> calls += ids to cls; ExtractOutcome.Failed("INVALID_OUTPUT") })
        val r = failing.run()
        assertEquals(0, r.described); assertEquals(1, r.left)
        assertFalse(cited("a")); assertEquals("CAPTION_QUEUED", state("a").state); assertEquals(1, state("a").attempts) // the night queue keeps it: never FAILED, never the live loop's
        // offline: the claim goes back (no attempt) to the night queue, not to the live loop
        photo("b", now - 7 * day)
        val offline = night(pipeline { ids, _, cls -> calls += ids to cls; ExtractOutcome.Failed("offline", countsAttempt = false) })
        offline.run()
        assertFalse(cited("b")); assertEquals("CAPTION_QUEUED", state("b").state); assertEquals(0, state("b").attempts)
        assertEquals("describe_before_delete", state("b").lastError)
    }

    @Test fun budgetRefusalLeavesThePhotoNeverDeleted() = runBlocking {
        photo("a", now - 7 * day)
        night(pipeline { ids, _, cls -> calls += ids to cls; throw com.rokid.context.ai.ModelCallException("budget", retryable = true) }).run()
        assertFalse(cited("a"))
        val s = retention(now + 3 * 365 * day, ready = true)
        assertEquals(0, s.deletedUndescribed)
        assertNotNull(db.forgetDao().frame("a"))
    }

    @Test fun closedGateDescribesNothing() = runBlocking {
        photo("a", now - 7 * day)
        var ran = false
        val r = night(gate = { false }, maintenance = { ran = true; null }).run()
        assertTrue(calls.isEmpty()); assertFalse(ran); assertEquals("EXTRACTED", state("a").state); assertEquals(0, r.queued)
    }

    @Test fun noDescribeDuringACaptureBurst() = runBlocking {
        photo("a", now - 7 * day)
        // a keyframe arrived 30 s ago: the live path owns the budget
        db.frameDao().insertIgnore(FrameEntity("fresh", now - 30_000, "/x/fresh.jpg", "rokid_glasses"))
        db.frameMetaDao().upsert(FrameMetaEntity("fresh", null, null, "STILL", false, null, null, null, 640, 0.9f, null, null, null, "sp", now - 30_000))
        db.frameStateDao().insertIgnore(listOf(FrameStateEntity("fresh", "PENDING", 0, null, null, null, now - 30_000)))
        night().run()
        assertTrue(calls.isEmpty()); assertEquals("CAPTION_QUEUED", state("a").state) // queued, waiting for the burst to end
        assertFalse(cited("a"))
    }

    @Test fun runsBackfillAfterDescribe() = runBlocking {
        photo("a", now - 7 * day)
        val order = mutableListOf<String>()
        val p = pipeline { ids, c, cls -> order += "describe"; describing()(ids, c, cls) }
        val r = night(p, maintenance = { order += "backfill"; IndexMaintenance.Outcome.DONE }).run()
        assertEquals(listOf("describe", "backfill"), order); assertEquals("DONE", r.backfill)
    }

    @Test fun backfillFailureDoesNotLoseTheDescribe() = runBlocking {
        photo("a", now - 7 * day)
        val r = night(maintenance = { error("embedder down") }).run()
        assertEquals(1, r.described); assertEquals("failed", r.backfill); assertTrue(cited("a"))
    }

    @Test fun nothingExpiresMeansNothingDescribed() = runBlocking {
        photo("a", now - 400 * day)
        night(s = settings(photo = MemorySettings.FOREVER)).run()
        assertTrue(calls.isEmpty())
        night(s = MemorySettings(photoDays = 7, perKindSince = 0L)).run() // retention has not stamped its clock
        assertTrue(calls.isEmpty())
    }

    @Test fun preExistingPhotosAreNotDescribedWhileProtected() = runBlocking {
        val st = MemorySettings(photoDays = 1, perKindSince = now - 2 * day) // protection runs to since + 7 d = now + 5 d
        photo("trip", now - 40 * day)
        night(s = st).run()
        assertTrue(calls.isEmpty())
    }

    @Test fun constraintsChargingAndIdle() {
        OrchestrationScheduler(ctx).scheduleCritic(); OrchestrationScheduler(ctx).scheduleCritic()
        val infos = WorkManager.getInstance(ctx).getWorkInfosForUniqueWork(NightIndexWorker.NAME).get()
        assertEquals(1, infos.size)
        val c = infos.single().constraints
        assertTrue(c.requiresCharging()); assertTrue(c.requiresDeviceIdle())
        assertEquals(24 * hour, infos.single().periodicityInfo!!.repeatIntervalMillis)
        // a pause cancels it (it makes AI calls), resume ensures it again
        OrchestrationScheduler(ctx).pausePipeline()
        assertTrue(WorkManager.getInstance(ctx).getWorkInfosForUniqueWork(NightIndexWorker.NAME).get().all { it.state.isFinished })
        OrchestrationScheduler(ctx).ensureNightIndex()
        assertTrue(WorkManager.getInstance(ctx).getWorkInfosForUniqueWork(NightIndexWorker.NAME).get().any { !it.state.isFinished })
    }

    @Test fun diagnosticsCountersAreRecorded() {
        val before = com.rokid.context.data.runtime.PipelineStatsStore.describedBeforeDelete(ctx)
        com.rokid.context.data.runtime.PipelineStatsStore.addDescribedBeforeDelete(ctx, 3)
        com.rokid.context.data.runtime.PipelineStatsStore.addDescribedBeforeDelete(ctx, 0)
        assertEquals(before + 3, com.rokid.context.data.runtime.PipelineStatsStore.describedBeforeDelete(ctx))
    }

    /** The flip: the end-to-end tests below are what let this be true. */
    @Test fun describeBeforeDeleteIsShipped() { assertTrue(Retention.DESCRIBE_BEFORE_DELETE_READY) }

    // --- end to end: the proof that unlocks DESCRIBE_BEFORE_DELETE_READY --------------------------------------------------

    @Test fun dueUndescribedPhotoIsDeletedOnlyAfterDescribeAndTheGrace() = runBlocking {
        val due = now - 6 * day - 12 * hour // deadline = now + 12 h
        val jpg = photo("p", due)
        // before the describe: never deleted, however late the retention job is
        for (at in listOf(now, now + 2 * day, now + 4 * day, now + 400 * day)) {
            assertEquals(0, retention(at, ready = true).deletedUndescribed)
            assertTrue("kept at +${(at - now) / day} d", jpg.exists() && db.forgetDao().frame("p") != null)
        }
        night().run()
        assertTrue(cited("p"))
        // described: kept through the deadline and the grace, deleted after
        retention(now + 12 * hour + 1, ready = true); assertTrue(jpg.exists())
        retention(now + 12 * hour + 3 * day - hour, ready = true); assertTrue("inside the grace", jpg.exists())
        retention(now + 12 * hour + 3 * day + hour, ready = false); assertTrue("not shipped: the flag still gates it", jpg.exists())
        val s = retention(now + 12 * hour + 3 * day + hour, ready = true)
        assertFalse(jpg.exists()); assertNull(db.forgetDao().frame("p")); assertEquals(0, s.deletedUndescribed)
        // the caption outlives the photo, with its provenance to the frame
        assertEquals(1, db.memoryDao().count()); assertEquals(1, db.frameSourceDao().memoriesForFrame("p").size)
    }

    @Test fun offlineOrFailingDescribeSurvivesIndefinitely() = runBlocking {
        val jpg = photo("p", now - 7 * day, "FAILED_FINAL", lastError = "timeout") // the live loop gave up on it
        night(pipeline { ids, _, cls -> calls += ids to cls; ExtractOutcome.Failed("offline", countsAttempt = false) }).run() // phone offline tonight
        assertEquals("CAPTION_QUEUED", state("p").state)
        for (at in listOf(now + 4 * day, now + 40 * day, now + 400 * day, now + 4000 * day)) {
            assertEquals(0, retention(at, ready = true).deletedUndescribed)
            assertTrue("kept at +${(at - now) / day} d", jpg.exists() && db.forgetDao().frame("p") != null)
        }
        night(pipeline { ids, _, cls -> calls += ids to cls; ExtractOutcome.Failed("timeout") }).run() // online but failing: an attempt, then a cooldown
        assertEquals("CAPTION_QUEUED", state("p").state)
        assertEquals(0, retention(now + 400 * day, ready = true).deletedUndescribed); assertTrue(jpg.exists())
        clockMs = now + 2 * day // the cooldown (1 day) is over; the next night that works describes it
        val r = night().run()
        assertEquals(1, r.described); assertTrue(cited("p"))
    }

    @Test fun clusterRepUnsummarisedClusterAndDwellFrameAreNeverDescribedAndDeletedEarly() = runBlocking {
        cluster("cOpen", "CLOSED"); cluster("cFailed", "FAILED"); cluster("cSum", "SUMMARIZED")
        val old = now - 20 * day
        val open = photo("open", old, "IN_CLUSTER", salience = 0.9f, clusterId = "cOpen")
        val retried = photo("retried", old, "CLUSTERED", salience = 0.9f, clusterId = "cFailed")
        val rep = photo("rep", now - 6 * day - 12 * hour, "CLUSTERED", salience = 0.1f, clusterId = "cSum")
        db.memoryDao().insert(MemoryEntity("m_sum", "rep", "t", "cluster_summary", "", MemoryTier.LONG, 1.0, ReviewState.OK, false, now, false, "t"))
        db.frameSourceDao().insertAll(listOf(FrameSourceEntity("m_sum", "rep", "cSum", "CLUSTER_REP"))) // the summary cites it as a representative
        val dwell = photo("dwell", now - day, dwell = true, linked = true)
        night().run()
        assertTrue(calls.isEmpty())
        retention(now + 11 * hour, ready = true) // nothing is due yet
        assertTrue("open", open.exists()); assertTrue("retried", retried.exists()); assertTrue("rep", rep.exists()); assertTrue("dwell", dwell.exists())
        retention(now + 400 * day, ready = true)
        assertTrue("never deleted undescribed", open.exists() && retried.exists())
        assertFalse("a cited rep follows the ordinary linked rule", rep.exists())
    }

    @Test fun forgetAfterDescribeKeepsTheFenceAndNothingIsRedescribed() = runBlocking {
        val jpg = photo("p", now - 7 * day)
        night().run()
        assertTrue(cited("p"))
        val id = db.frameSourceDao().memoriesForFrame("p").single()
        val r = ForgetService(db, graph.proposals) { now }.forgetFact(id)
        FrameSweep.delete(r.framesToDelete)
        assertEquals(listOf("p"), db.forgetDao().tombstoned(listOf("p")))
        calls.clear()
        night().run()
        assertTrue("a forgotten photo is never described again", calls.isEmpty())
        assertFalse(jpg.exists())
    }

    @Test fun memoryForgottenBetweenSelectionAndExpiryKeepsThePhoto() = runBlocking {
        val jpg = photo("p", now - 7 * day, linked = true)
        // retention selected it as linked; the memory is forgotten before the expiry transaction
        db.forgetDao().deleteFrameSourcesFor(listOf("m_p")); db.forgetDao().deleteMemories(listOf("m_p"))
        val r = ForgetService(db, graph.proposals) { now }.expirePhotos(listOf("p"), requireLinked = true)
        assertTrue(r.framesToDelete.isEmpty()); assertNotNull(db.forgetDao().frame("p")); assertTrue(jpg.exists())
    }

    @Test fun deleteAllInTheMiddleOfARetentionPass() = runBlocking {
        val files = (0 until 450).map { photo("l$it", now - 30 * day, linked = true) } // two chunks of 200 and a rest
        var chunks = 0
        val svc = ForgetService(db, graph.proposals) { now }
        Retention(graph, svc, describeBeforeDeleteReady = true).run(settings(), now) { r ->
            FrameSweep.delete(r.framesToDelete)
            if (++chunks == 1) MemoryWipe.deleteAll(ctx) // Delete all lands between two chunks
        }
        assertEquals(0, db.frameDao().count()); assertEquals(0, db.memoryDao().count())
        assertTrue(files.none { it.exists() })
        // nothing came back, and a further pass is a no-op
        retention(now + day, ready = true)
        assertEquals(0, db.frameDao().count())
    }

    @Test fun deleteAllInTheMiddleOfTheNightDescribe() = runBlocking {
        val jpg = photo("p", now - 7 * day); photo("q", now - 7 * day)
        val wiping = pipeline { ids, claim, cls ->
            calls += ids to cls
            val generation = MemoryWipe.generation.get()
            MemoryWipe.deleteAll(ctx) // the wipe lands after the model answered, before the save
            when (ExtractSave.saveExtraction(
                ctx, "rokid_kf_${claim}_", ExtractOutput(emptyList(), captions = ids.map { FrameCaption(it, "c") }, provenance = true),
                ids.toSet(), ids.associateWith { claim }, MemoryTier.SHORT, ReviewState.OK, generation = generation,
            )) {
                is ExtractSaveResult.Saved -> ExtractOutcome.Saved(emptyList())
                else -> ExtractOutcome.Stale()
            }
        }
        val r = night(wiping).run()
        assertEquals(0, db.memoryDao().count()); assertEquals(0, db.frameDao().count()); assertFalse(jpg.exists())
        assertEquals(0, r.described)
        assertEquals(0, retention(now + 400 * day, ready = true).deletedUndescribed)
    }

    /** Task 8 carry: the manual Diagnostics purge never touches a wave-1+ keyframe (it has a frame_meta row), whatever its file name. */
    @Test fun purgeNonPhotoAndSyntheticSkipsKeyframes() = runBlocking {
        val keyframe = photo("synthetic_kf", now - day) // named like a synthetic frame, but a keyframe
        val dir = File(ctx.filesDir, "rokid_archive")
        val legacy = File(dir, "synthetic_legacy.jpg").apply { writeBytes(byteArrayOf(1)) }
        db.frameDao().insertIgnore(FrameEntity("legacy", now - day, legacy.absolutePath, "rokid_glasses"))
        val r = com.rokid.context.orchestration.PipelineRecords.purgeNonPhotoAndSynthetic(ctx)
        assertNotNull(db.forgetDao().frame("synthetic_kf")); assertTrue(keyframe.exists())
        assertNull(db.forgetDao().frame("legacy")); assertFalse(legacy.exists())
        assertEquals(1, r.dropped)
    }

    // --- fix round 1: bounded retries (I1), no leak to the live loop (I2), grace from the describe (I3), key moments (I4) ---

    private suspend fun nights(n: Int, extractor: suspend (List<String>, String, CallClass) -> ExtractOutcome) = repeat(n) {
        night(pipeline(extractor)).run(); clockMs += day
    }

    private val failing: suspend (List<String>, String, CallClass) -> ExtractOutcome = { ids, _, cls -> calls += ids to cls; ExtractOutcome.Failed("INVALID_OUTPUT") }

    @Test fun aPhotoThatFailsEveryNightCostsAtMostFiveCallsThenIsParked() = runBlocking {
        val jpg = photo("bad", now - 7 * day)
        nights(40, failing)
        assertEquals("1, 2, 4, 8 days of cooldown then parked: 5 calls in 40 nights", 5, calls.size)
        assertEquals(setOf(CallClass.BACKFILL), calls.map { it.second }.toSet())
        state("bad").let { assertEquals("FAILED_FINAL", it.state); assertEquals("describe_failed", it.lastError) }
        assertEquals(1, db.frameStateDao().describeFailed())
        assertEquals(0, retention(now + 4000 * day, ready = true).deletedUndescribed); assertTrue("never deleted", jpg.exists())
        // a manual retry puts it back in the night queue's reach
        db.frameStateDao().retryDescribeFailed(clockMs); calls.clear()
        night().run(); assertTrue(cited("bad"))
    }

    @Test fun aDescribeThatSavesNothingIsBoundedToo() = runBlocking {
        photo("empty", now - 7 * day)
        val saveNothing: suspend (List<String>, String, CallClass) -> ExtractOutcome = { ids, claim, cls ->
            calls += ids to cls
            ids.forEach { check(db.frameStateDao().completeIfClaimed(it, claim, "EXTRACTED", clockMs) == 1) }
            ExtractOutcome.Saved(emptyList())
        }
        nights(40, saveNothing)
        assertEquals(5, calls.size); assertEquals("describe_failed", state("empty").lastError); assertFalse(cited("empty"))
    }

    @Test fun tenBadPhotosAtTheFrontDoNotBlockOneHundredGoodOnes() = runBlocking {
        val bad = (0 until 10).map { "bad$it" }.toSet()
        bad.forEach { photo(it, now - 40 * day) } // the oldest deadlines come first
        (0 until 100).forEach { photo("good$it", now - 7 * day) }
        val selective: suspend (List<String>, String, CallClass) -> ExtractOutcome = { ids, claim, cls ->
            if (ids.any { it in bad }) { calls += ids to cls; ExtractOutcome.Failed("INVALID_OUTPUT") } else describing()(ids, claim, cls)
        }
        nights(4, selective)
        assertEquals("every good photo described within four nights", 100, (0 until 100).count { cited("good$it") })
        assertTrue(calls.all { it.second == CallClass.BACKFILL })
    }

    @Test fun perNightCallCapDrainsAThousandPhotosOverSeveralNights() = runBlocking {
        (0 until 400).forEach { photo("t$it", now - 7 * day - it * hour) }
        night(pipeline(), s = settings()).run()
        assertEquals("one night = at most 60 calls (3 photos each)", 60, calls.size)
        val firstNight = (0 until 400).count { cited("t$it") }
        assertEquals(180, firstNight)
        clockMs += day; night().run(); clockMs += day; night().run()
        assertEquals(400, (0 until 400).count { cited("t$it") })
    }

    @Test fun failedBatchesDoNotStopTheNightWhenASuccessFollows() = runBlocking {
        (0 until 18).forEach { photo("p$it", now - 7 * day - it * hour) } // six batches of three
        var n = 0
        val pattern = listOf(false, false, true, false, false, true) // F F S F F S: four failures, never three in a row
        val flaky: suspend (List<String>, String, CallClass) -> ExtractOutcome = { ids, claim, cls ->
            if (pattern[n++]) describing()(ids, claim, cls) else { calls += ids to cls; ExtractOutcome.Failed("INVALID_OUTPUT") }
        }
        night(pipeline(flaky)).run()
        assertEquals("a success resets the consecutive-failure count: all six batches ran", 6, n)
        assertEquals(6, (0 until 18).count { cited("p$it") })
    }

    /** I2 (a): a failed night photo goes back to the night queue with its marker; the live loop never claims it and makes no EXTRACT call. */
    @Test fun failedNightPhotoIsNeverClaimedByTheLiveLoop() = runBlocking {
        photo("a", now - 7 * day)
        night(pipeline(failing)).run()
        state("a").let { assertEquals("CAPTION_QUEUED", it.state); assertEquals("describe_before_delete", it.lastError) }
        val live = pipeline { ids, _, cls -> calls += ids to cls; ExtractOutcome.Failed("x") }
        calls.clear()
        assertEquals(0, live.drainOnce()); assertTrue("no EXTRACT call for a night photo", calls.isEmpty())
        clockMs += 400 * day
        assertEquals(0, live.drainOnce()); assertTrue(calls.isEmpty())
    }

    /** I2 (b): a drain cancelled mid-call (idle ends, the 10-minute cap) leaves the row in the night queue. */
    @Test fun cancelledDrainLeavesTheRowInTheNightQueue() = runBlocking {
        photo("a", now - 7 * day)
        val cancelling = pipeline { ids, _, cls -> calls += ids to cls; throw kotlinx.coroutines.CancellationException("idle ended") }
        try { night(cancelling).run(); error("expected the cancellation") } catch (_: kotlinx.coroutines.CancellationException) {}
        state("a").let { assertEquals("CAPTION_QUEUED", it.state); assertEquals("describe_before_delete", it.lastError); assertEquals(0, it.attempts) }
        calls.clear()
        val live = pipeline { ids, _, cls -> calls += ids to cls; ExtractOutcome.Failed("x") }
        assertEquals(0, live.drainOnce()); assertTrue(calls.isEmpty())
    }

    /** I2 (c): an abandoned night claim (process death) and the process-start release also go back to the night queue. */
    @Test fun abandonedAndReleasedNightClaimsGoBackToTheNightQueue() = runBlocking {
        photo("a", now - 7 * day); photo("b", now - 7 * day)
        db.frameStateDao().queueForDescribe("a", clockMs); db.frameStateDao().queueForDescribe("b", clockMs)
        db.frameStateDao().claimCaptionQueued(listOf("a", "b"), "dead", clockMs - 20 * 60_000L)
        val live = pipeline { ids, _, cls -> calls += ids to cls; ExtractOutcome.Failed("x") }
        assertEquals(2, live.recover()) // the abandoned claims: recovery requeues them, with their marker
        for (id in listOf("a", "b")) state(id).let { assertEquals("CAPTION_QUEUED", it.state); assertEquals("describe_before_delete", it.lastError) }
        assertEquals(0, live.drainOnce()); assertTrue(calls.isEmpty())
        db.frameStateDao().claimCaptionQueued(listOf("a"), "dead2", clockMs)
        db.frameStateDao().releaseAllExtracting(clockMs)
        assertEquals("CAPTION_QUEUED", state("a").state)
        assertEquals(0, live.drainOnce()); assertTrue(calls.isEmpty())
    }

    /** I3: described tonight after a 20-day backlog: kept 3 more days from the describe, then deleted. */
    @Test fun lateDescribedPhotoKeepsItsThreeDaysFromTheDescribe() = runBlocking {
        val jpg = photo("late", now - 27 * day) // due 20 days ago
        night().run()
        assertTrue(cited("late"))
        retention(now + hour, ready = true); assertTrue("deleted within hours of its caption", jpg.exists())
        retention(now + 3 * day - hour, ready = true); assertTrue(jpg.exists())
        retention(now + 3 * day + hour, ready = false); assertTrue("the flag still gates it", jpg.exists())
        retention(now + 3 * day + hour, ready = true); assertFalse(jpg.exists())
        assertEquals(1, db.memoryDao().count())
    }

    /** I4: a key moment waiting in the live caption queue is never converted to a skipped photo; an ordinary frame still is. */
    @Test fun keyMomentsNeverBecomeSkippedThroughCaptionExpiry() = runBlocking {
        val key = photo("key", now - 20 * day, "CAPTION_QUEUED", salience = 0.9f)
        val dwell = photo("dwell", now - 20 * day, "CAPTION_QUEUED", dwell = true)
        val plain = photo("plain", now - 20 * day, "CAPTION_QUEUED", salience = 0.1f)
        retention(now + 40 * day, ready = true)
        assertEquals("CAPTION_QUEUED", state("key").state); assertEquals("CAPTION_QUEUED", state("dwell").state)
        assertEquals("NEAR_DUP", state("plain").state)
        retention(now + 400 * day, ready = true)
        assertTrue(key.exists() && dwell.exists()); assertFalse(plain.exists())
        // the night drain describes them (live caption queue rows, BACKFILL), nothing deletes them before
        night().run()
        assertTrue(cited("key") && cited("dwell")); assertTrue(calls.all { it.second == CallClass.BACKFILL })
    }

    @Test fun everyNightDescribePathUsesBackfill() = runBlocking {
        photo("a", now - 7 * day); photo("b", now - 7 * day); photo("c", now - 7 * day, "CAPTION_QUEUED", salience = 0.9f)
        nights(2, failing)
        night(pipeline { ids, _, cls -> calls += ids to cls; ExtractOutcome.Failed("offline", countsAttempt = false) }).run()
        clockMs += 5 * day; night().run()
        assertTrue(calls.isNotEmpty()); assertEquals(setOf(CallClass.BACKFILL), calls.map { it.second }.toSet())
    }

    // --- final review I1: wave-1 shed / near-dup photos were never scored (salience NULL): not "skipped", described first ---------

    /**
     * The ~800-photo trip as wave 1 left it: shed (`shed_backlog`) and dhash near-dup frames in NEAR_DUP with NO salience. Installed at day 0:
     * nothing is deleted at day 0 or day 7 undescribed; the night job describes them (5 nights at 60 batches of 3); each goes at
     * max(deadline, describedAt) + 3 days; the ones never described survive.
     */
    @Test fun legacyUnscoredTripPhotosAreDescribedBeforeAnyDelete() = runBlocking {
        val install = now
        val s = MemorySettings(photoDays = 7, perKindSince = install)
        val jpgs = (0 until 800).map { i ->
            photo("t$i", install - 3 * day - i * 60_000L, "NEAR_DUP", salience = null, lastError = if (i % 2 == 0) "shed_backlog" else null)
        }
        assertEquals("day 0", 0, retention(install, ready = true, s = s).deletedUndescribed)
        for (at in listOf(install + 7 * day + hour, install + 30 * day, install + 400 * day)) {
            retention(at, ready = true, s = s) // before any describe, however late
            assertTrue("kept undescribed at +${(at - install) / day} d", jpgs.all { it.exists() } && db.forgetDao().frame("t0") != null)
        }
        clockMs = install + 6 * day
        val described = mutableListOf<Int>()
        repeat(5) { night(s = s, described = described).run(); clockMs += day }
        assertEquals("all 800 described within five nights (60 batches of 3 each)", 800, (0 until 800).count { cited("t$it") })
        // night 1 ran at +6 d (deadline +7 d), night 5 at +10 d: deletion at max(deadline, describedAt) + 3 d
        retention(install + 9 * day, ready = true, s = s); assertTrue("inside every grace", jpgs.all { it.exists() })
        retention(install + 10 * day + hour, ready = true, s = s)
        assertFalse("night-1 photo is gone", jpgs[0].exists()); assertTrue("night-5 photo waits for its own 3 days", jpgs[799].exists())
        retention(install + 14 * day, ready = true, s = s)
        assertTrue("every described photo is gone", jpgs.none { it.exists() })
        assertEquals("800 captions outlive their photos", 800, db.memoryDao().count())
    }

    /** An unscored legacy photo the night job cannot describe (offline, failing) is never deleted. */
    @Test fun legacyUnscoredPhotoThatCannotBeDescribedSurvives() = runBlocking {
        val s = MemorySettings(photoDays = 7, perKindSince = now - 30 * day)
        val jpg = photo("legacy", now - 20 * day, "NEAR_DUP", salience = null, lastError = "shed_backlog")
        night(pipeline { ids, _, cls -> calls += ids to cls; ExtractOutcome.Failed("offline", countsAttempt = false) }, s = s).run()
        for (at in listOf(now, now + 40 * day, now + 4000 * day)) {
            assertEquals(0, retention(at, ready = true, s = s).deletedUndescribed)
            assertTrue("kept at +${(at - now) / day} d", jpg.exists() && db.forgetDao().frame("legacy") != null)
        }
    }

    /** A real salience below 0.5 recorded by this wave's scene indexer still makes a skipped photo (1 day), described never. */
    @Test fun scoredLowSalienceNearDupIsStillAnOrdinarySkippedPhoto() = runBlocking {
        val s = MemorySettings(photoDays = 7, perKindSince = now - 30 * day)
        val scored = photo("scored", now - 20 * day, "NEAR_DUP", salience = 0.0f)
        val unscored = photo("unscored", now - 20 * day, "NEAR_DUP", salience = null)
        retention(now, ready = true, s = s)
        assertFalse("scored low salience goes at the skipped window", scored.exists()); assertTrue("unscored is kept", unscored.exists())
        night(s = s).run()
        assertEquals(listOf("unscored"), calls.flatMap { it.first })
    }
}
