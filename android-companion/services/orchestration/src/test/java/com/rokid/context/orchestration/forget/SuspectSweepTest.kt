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
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.ForgetTombstoneEntity
import com.rokid.context.memory.db.FrameEntity
import com.rokid.context.memory.db.FrameMetaEntity
import com.rokid.context.memory.db.FrameSourceEntity
import com.rokid.context.memory.db.FrameStateEntity
import com.rokid.context.memory.db.MemoryEntity
import com.rokid.context.memory.db.MemorySourceEntity
import com.rokid.context.memory.db.MemoryTier
import com.rokid.context.memory.db.PauseIntervalEntity
import com.rokid.context.memory.db.ReviewState
import com.rokid.context.memory.forget.ForgetService
import com.rokid.context.model.CallClass
import com.rokid.context.orchestration.ExtractSave
import com.rokid.context.orchestration.ExtractSaveResult
import com.rokid.context.work.OrchestrationScheduler
import com.rokid.context.work.frames.ExtractOutcome
import com.rokid.context.work.frames.KeyframePipeline
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Roadmap row 8: the suspect-description night sweep (marked suspect: expired photo -> description removed, stored photo -> re-described, never a forgotten, paused or zone photo). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SuspectSweepTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val graph get() = MemoryGraph.get(ctx)
    private val db get() = graph.db
    private val day = 24 * 3_600_000L
    private val now = 1_793_000_000_000L // before the text-retry fix constant (1_794_085_517_000): every row below is "pre-fix"
    private var clockMs = now
    private var claimN = 0
    private val calls = mutableListOf<Pair<List<String>, CallClass>>()
    private var zone = false
    private var gate = true

    @Before fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(ctx)
        MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx))
        AppConfigStore.setPipelineEnabled(ctx, false)
    }
    @After fun tearDown() { AppConfigStore.setPipelineEnabled(ctx, true); db.close(); MemoryGraph.resetForTests(null) }

    /** A stored photo (frame row, meta, EXTRACTED) with its pre-fix, delivery-unknown description; [marked] = the user's mark. */
    private suspend fun suspect(id: String, ts: Long = now - 3 * day, marked: Boolean = true, photoStored: Boolean = true) {
        if (photoStored) {
            db.frameDao().insertIgnore(FrameEntity(id, ts, "/x/$id.jpg", "rokid_glasses"))
            db.frameMetaDao().upsert(FrameMetaEntity(id, null, null, "STILL", false, null, null, null, 640, 0.9f, null, null, null, "sp", ts))
            db.frameStateDao().insertIgnore(listOf(FrameStateEntity(id, "EXTRACTED", 0, null, null, null, ts)))
        }
        db.memoryDao().insert(MemoryEntity("old_$id", id, "old text of $id", "caption", "", MemoryTier.LONG, 1.0, ReviewState.OK, false, ts, false, "old", reviewFlaggedAt = if (marked) 5L else null))
        db.frameSourceDao().insertAll(listOf(FrameSourceEntity("old_$id", id, null, "FACT")))
    }

    private fun describing(): suspend (List<String>, String, CallClass) -> ExtractOutcome = { ids, claim, cls ->
        calls += ids to cls
        when (val r = ExtractSave.saveExtraction(
            ctx, "rokid_kf_${claim}_", ExtractOutput(emptyList(), captions = ids.map { FrameCaption(it, "new caption of $it") }, provenance = true, imagesDelivered = true),
            ids.toSet(), ids.associateWith { claim }, MemoryTier.SHORT, ReviewState.OK, nowMs = clockMs,
        )) {
            is ExtractSaveResult.Saved -> ExtractOutcome.Saved(r.memories)
            else -> ExtractOutcome.Stale()
        }
    }
    private fun pipeline(extractor: suspend (List<String>, String, CallClass) -> ExtractOutcome = describing()) =
        KeyframePipeline(db, extractor, { clockMs }, null, newClaimId = { "c${claimN++}" }, pollMs = 50)
    private val removed = mutableListOf<List<String>>()
    private fun sweep(p: KeyframePipeline = pipeline()) = SuspectSweep(
        db, p, bulkAllowed = { gate }, zoneBlocked = { zone },
        remove = { ids -> removed += ids; ForgetService(db, graph.proposals) { clockMs }.removeDescriptions(ids).forgotten.size },
        clock = { clockMs }, drainMs = 30_000L,
    )
    private suspend fun exists(id: String) = db.forgetDao().memories(listOf(id)).isNotEmpty()

    @Test fun expiredPhotoRemovesTheDescriptionAndRebuildsItsDerived() = runBlocking {
        suspect("gone", photoStored = false)
        suspect("kept", photoStored = false, marked = false) // unmarked: untouched
        db.memoryDao().insert(MemoryEntity("sum", null, "summary", "summary", "", MemoryTier.LONG, 1.0, ReviewState.OK, false, now, true, "s", imageDelivered = false))
        db.forgetDao().insertEdges(listOf(MemorySourceEntity("sum", "old_gone")))
        val o = sweep().run()
        assertEquals(1, o.removedExpired)
        assertFalse(exists("old_gone")); assertTrue(exists("old_kept"))
        assertEquals(ReviewState.INVALIDATED, db.forgetDao().memories(listOf("sum")).single().reviewState) // its summary is rebuilt by the re-summarise job
        assertTrue(calls.isEmpty())
    }

    @Test fun storedPhotoIsReDescribedThenTheSuspectIsRemoved() = runBlocking {
        suspect("a")
        val o = sweep().run()
        assertEquals(listOf(listOf("a") to CallClass.BACKFILL), calls)
        assertEquals(1, o.described); assertEquals(1, o.replaced); assertFalse(o.failed)
        assertFalse(exists("old_a"))
        assertNotNull(db.frameDao().byIds(listOf("a")).singleOrNull()) // the photo is never touched
        assertEquals(1, db.forgetDao().describedWithProof("a", "none"))
        // idempotent: a second run finds nothing to do and makes no call
        calls.clear()
        val again = sweep().run()
        assertTrue(calls.isEmpty()); assertEquals(0, again.queued); assertEquals(0, again.replaced); assertEquals(0, again.left)
    }

    @Test fun unmarkedSuspectIsNeverReDescribed() = runBlocking {
        suspect("a", marked = false)
        sweep().run()
        assertTrue(calls.isEmpty()); assertTrue(exists("old_a"))
    }

    @Test fun forgottenPhotoIsNeverReDescribed() = runBlocking {
        suspect("a")
        db.forgetDao().insertTombstone(ForgetTombstoneEntity("a", now))
        val o = sweep().run()
        assertTrue(calls.isEmpty()); assertEquals(0, o.queued); assertTrue(exists("old_a"))
        assertEquals("EXTRACTED", db.frameStateDao().get("a")!!.state)
    }

    @Test fun photoTakenInAPauseOrZoneIntervalIsNeverReDescribed() = runBlocking {
        suspect("paused", ts = now - 3 * day); suspect("zoned", ts = now - 2 * day)
        db.pauseDao().insertInterval(PauseIntervalEntity("manual1", "PHONE", now - 3 * day - 1_000, now - 3 * day + 1_000))
        db.pauseDao().insertInterval(PauseIntervalEntity("zone:z1", "PHONE", now - 2 * day - 1_000, null))
        val o = sweep().run()
        assertTrue(calls.isEmpty()); assertEquals(0, o.queued)
        assertTrue(exists("old_paused")); assertTrue(exists("old_zoned"))
    }

    @Test fun heldZoneFenceAndClosedBatteryGateDescribeNothingButExpiredStillGoes() = runBlocking {
        suspect("a"); suspect("gone", photoStored = false)
        zone = true
        var o = sweep().run()
        assertTrue(calls.isEmpty()); assertFalse(o.gate); assertEquals(1, o.removedExpired); assertTrue(exists("old_a"))
        zone = false; gate = false
        o = sweep().run()
        assertTrue(calls.isEmpty()); assertFalse(o.gate); assertTrue(exists("old_a")); assertFalse(o.failed)
        gate = true
        o = sweep().run()
        assertEquals(1, calls.size); assertFalse(exists("old_a"))
    }

    @Test fun aZoneThatClosesWhileTheCallWaitsForItsPermitBlocksTheUpload() = runBlocking {
        suspect("a")
        val base = object : com.rokid.context.model.PhotoLiveness {
            override suspend fun <T> holdUploadSlot(block: suspend () -> T): T = block()
            override suspend fun gone(ids: Set<String>) = com.rokid.context.model.GonePhotos(emptySet()) // the photo itself is live
            override suspend fun goneMemories(ids: Set<String>): Set<String> = emptySet()
            override suspend fun goneMarks(ids: Set<String>): Set<String> = emptySet()
        }
        var goneAtDispatch: Set<String>? = null
        var goneBeforeWait: Set<String>? = null
        val waiting = pipeline { ids, _, cls ->
            calls += ids to cls
            // what KeyframeExtract builds for the router, asked before and after the model-permit wait (the sweep's own pre-check saw no zone)
            val live = com.rokid.context.work.frames.DispatchFence.wrap(base, kotlin.coroutines.coroutineContext, null)
            goneBeforeWait = live.gone(ids.toSet()).ids
            zone = true // the zone closes while the call waits for its permit
            goneAtDispatch = live.gone(ids.toSet()).ids
            ExtractOutcome.Failed("INVALID_OUTPUT")
        }
        sweep(waiting).run()
        assertEquals(emptySet<String>(), goneBeforeWait)
        assertEquals("the dispatch-time check sees the closed zone: the router drops the call, nothing uploads", setOf("a"), goneAtDispatch)
    }

    @Test fun aFailedDescribeKeepsTheSuspectQueuedAndAskedAgainNextRun() = runBlocking {
        suspect("a")
        val failing = pipeline { ids, _, cls -> calls += ids to cls; ExtractOutcome.Failed("INVALID_OUTPUT") }
        val o = sweep(failing).run()
        assertTrue(o.failed); assertEquals(0, o.described); assertEquals(0, o.replaced)
        assertTrue(exists("old_a"))
        assertEquals("CAPTION_QUEUED", db.frameStateDao().get("a")!!.state); assertEquals(1, db.frameStateDao().get("a")!!.attempts)
        // the next night (a fresh pipeline) describes it from the same queue and replaces the suspect
        calls.clear(); clockMs += day // past the photo's failure cooldown
        val ok = sweep().run()
        assertFalse(ok.failed); assertEquals(1, ok.replaced); assertFalse(exists("old_a"))
        assertEquals(listOf(listOf("a")), calls.map { it.first })
    }

    @Test fun anOfflineFailureDoesNotCountAnAttempt() = runBlocking {
        suspect("a")
        val offline = pipeline { ids, _, cls -> calls += ids to cls; ExtractOutcome.Failed("offline", countsAttempt = false) }
        val o = sweep(offline).run()
        assertTrue(o.failed); assertEquals(0, db.frameStateDao().get("a")!!.attempts); assertTrue(exists("old_a"))
    }

    @Test fun schedulerKeepsOneUniqueChargingJobAndAPauseCancelsIt() {
        OrchestrationScheduler(ctx).scheduleCritic(); OrchestrationScheduler(ctx).scheduleCritic()
        val infos = WorkManager.getInstance(ctx).getWorkInfosForUniqueWork(SuspectSweepWorker.NAME).get()
        assertEquals(1, infos.size)
        val c = infos.single().constraints
        assertTrue(c.requiresCharging()); assertTrue(c.requiresBatteryNotLow())
        assertEquals(24 * 3_600_000L, infos.single().periodicityInfo!!.repeatIntervalMillis)
        OrchestrationScheduler(ctx).pausePipeline()
        assertTrue(WorkManager.getInstance(ctx).getWorkInfosForUniqueWork(SuspectSweepWorker.NAME).get().all { it.state.isFinished })
    }
}
