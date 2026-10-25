package com.rokid.context.canvas.moment

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.rokid.context.canvas.marks.MarkArrival
import com.rokid.context.canvas.marks.MarkRecorder
import com.rokid.context.canvas.marks.VoiceMark
import com.rokid.context.core.model.MarkMessage
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.DaoFence
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.DeletionEpoch
import com.rokid.context.memory.moment.MomentType
import com.rokid.context.orchestration.forget.ForgetStartup
import com.rokid.context.work.OrchestrationScheduler
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The device defect of 2026-10-25: marks recorded BEFORE the build that can make mark Moments (no trigger fired for them) never got their Moments. The whole production path with real rows: the real
 * [MarkRecorder] (tap `onMark`, voice `applyVoice`) -> `canvas_label` -> [MomentCadenceWorker] -> [MomentTriggers] -> `catchUpMarks` -> a stored MARK Moment; and the schedule of that worker.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class CatchUpEndToEndTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val db get() = MemoryGraph.get(ctx).db
    private val hour = 3_600_000L

    @Before fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(ctx)
        MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx)); MomentTriggers.resetForTests(); SceneTriggers.resetForTests()
    }
    @After fun tearDown() { db.close(); MemoryGraph.resetForTests(null); MomentTriggers.resetForTests(); SceneTriggers.resetForTests() }

    @get:org.junit.Rule val pcLogShadow = com.rokid.context.PcLogToShadowRule()
    private fun lines() = org.robolectric.shadows.ShadowLog.getLogs().filter { it.tag == "PcMoments" }.map { it.msg }

    private val epoch = DeletionEpoch("inst", 2, 1_000)
    /** A recorder with NO trigger wired (as the build that recorded the device's marks had none). */
    private fun recorder(now: Long) = MarkRecorder(db.canvasSlice3Dao(), { DaoFence(0, epoch, { 0 }, { epoch }) }, { now })
    private fun tap(seq: Long) = MarkMessage(epoch = "e", seq = seq, atMs = 5, atElapsedMs = 5, listen = false, undo = null, deletionCounter = 2L)
    private suspend fun work() = TestListenableWorkerBuilder<MomentCadenceWorker>(ctx).build().doWork()

    @Test fun aTapMarkAndAVoiceMarkRecordedBeforeGetTheirMarkMomentsFromTheCadenceWorker() = runBlocking<Unit> {
        val now = System.currentTimeMillis(); val t = now - 13 * hour
        val rec = recorder(now)
        assertTrue(rec.onMark(tap(1), MarkArrival(t, mapped = true, receivedAtMs = t)))
        rec.onListenStart(t + 60_000); rec.applyVoice(listOf(VoiceMark.Mark(null)), glasses = true, nowMs = t + 63_000)
        assertEquals(setOf("mk:e:1", "vm:${t + 60_000}:g"), db.momentDao().labelIdsBetween(0, Long.MAX_VALUE).toSet())
        assertTrue("nothing built yet", db.momentDao().allMoments().isEmpty())
        assertEquals(ListenableWorker.Result.success(), work())
        val moments = db.momentDao().allMoments()
        assertEquals(setOf(MomentId.ofMs(t), MomentId.ofMs(t + 60_000)), moments.map { it.momentId }.toSet())
        assertTrue(moments.all { it.momentType == MomentType.MARK })
        assertEquals(setOf("tap", "voice"), moments.flatMap { db.momentDao().parts(it.momentId) }.map { it.code }.toSet())
        assertNotNull(db.canvasSlice3Dao().meta(MomentBuilder.KEY_CATCHUP))
    }

    @Test fun aRunThatFoundNoMarksDoesNotConsumeTheCatchUp() = runBlocking<Unit> {
        val now = System.currentTimeMillis(); val t = now - 13 * hour
        assertEquals(ListenableWorker.Result.success(), work()) // an earlier tick, nothing to catch up yet
        assertNull("nothing found: the key is not stored", db.canvasSlice3Dao().meta(MomentBuilder.KEY_CATCHUP))
        recorder(now).onMark(tap(1), MarkArrival(t, mapped = true, receivedAtMs = t))
        work()
        assertEquals(listOf(MomentId.ofMs(t)), db.momentDao().allMoments().map { it.momentId })
        assertNotNull(db.canvasSlice3Dao().meta(MomentBuilder.KEY_CATCHUP))
    }

    @Test fun aKeyStoredByTheFirstShippedCatchUpDoesNotBlockTheFixedOne() = runBlocking<Unit> {
        val now = System.currentTimeMillis(); val t = now - 13 * hour
        db.canvasSlice3Dao().putMeta("mark_moment_catchup_ms", (now - hour).toString()) // set by the first version of the catch-up when it found nothing
        recorder(now).onMark(tap(1), MarkArrival(t, mapped = true, receivedAtMs = t))
        work()
        assertEquals(listOf(MomentId.ofMs(t)), db.momentDao().allMoments().map { it.momentId })
    }

    @Test fun theCadenceIsScheduledByTheStartPathWhateverThePipelineStateAndOnlyOnce() = runBlocking<Unit> {
        val infos = { WorkManager.getInstance(ctx).getWorkInfosForUniqueWork(MomentCadenceWorker.NAME).get() }
        assertTrue(infos().isEmpty())
        ForgetStartup.run(ctx) {} // MainActivity runs it on every launch, before and whatever the pipeline flag says
        assertEquals(1, infos().size)
        ForgetStartup.run(ctx) {}; OrchestrationScheduler(ctx).ensureBackgroundWork()
        assertEquals(1, infos().size)
    }

    @Test fun theWorkerAndTheCatchUpLogCountsAndStateNamesOnly() = runBlocking<Unit> {
        val now = System.currentTimeMillis(); val t = now - 13 * hour
        work() // nothing to catch up yet
        recorder(now).onMark(tap(1), MarkArrival(t, mapped = true, receivedAtMs = t))
        work()
        work() // already done
        val l = lines()
        assertTrue(l.toString(), l.count { it.startsWith("cadence start: attempt=") } == 3)
        assertTrue(l.toString(), "catch-up found no mark seconds; will look again" in l)
        assertTrue(l.toString(), "catch-up done: scanned=1 stored=1 deleted=0 rejected=0" in l)
        assertTrue(l.toString(), "catch-up skipped: already done" in l)
        assertTrue(l.toString(), l.count { it == "cadence end: success" } == 3 && l.any { it.startsWith("cadence rebuilt: stored=") } && l.any { it.startsWith("cadence duration: under ") })
    }
}
