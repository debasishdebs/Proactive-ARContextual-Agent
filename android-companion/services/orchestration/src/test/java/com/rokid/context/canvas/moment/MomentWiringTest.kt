package com.rokid.context.canvas.moment

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.rokid.context.canvas.marks.MarkArrival
import com.rokid.context.canvas.marks.MarkRecorder
import com.rokid.context.core.model.MarkMessage
import com.rokid.context.canvas.moment.MomentFixture.Companion.MS
import com.rokid.context.canvas.moment.MomentFixture.Companion.SEC
import com.rokid.context.memory.FrameRows
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.DaoFence
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.DeletionEpoch
import com.rokid.context.memory.db.FrameEntity
import com.rokid.context.memory.db.FrameMetaEntity
import com.rokid.context.memory.db.FrameStateEntity
import com.rokid.context.orchestration.PipelineRecords
import com.rokid.context.work.OrchestrationScheduler
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/** The production call sites of the Moment builder trigger: the frame commit (PipelineRecords), the mark recorder, the cadence worker and its schedule. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class MomentWiringTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val db get() = MemoryGraph.get(ctx).db

    @Before fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(ctx)
        MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx)); MomentTriggers.resetForTests()
    }
    @After fun tearDown() { db.close(); MemoryGraph.resetForTests(null); MomentTriggers.resetForTests() }

    private fun jpg(name: String) = File(ctx.filesDir, name).apply { writeBytes(byteArrayOf(1)) }.absolutePath
    private fun rows(id: String, captured: Long?) = FrameRows(
        FrameMetaEntity(id, "w_$id", null, "STILL", false, null, null, null, null, null, null, null, null, null, 1, capturedAtMs = captured),
        FrameStateEntity(id, "PENDING", 0, null, null, null, 1),
    )

    @Test fun aFrameCommittedByTheAppProducesItsStoredMoment() = runBlocking<Unit> {
        assertTrue(PipelineRecords.appendFrame(ctx, FrameEntity("fa", MS + 5, jpg("a.jpg"), "rokid_glasses", payload = "p"), rows("fa", MS + 5)))
        MomentTriggers.get(ctx).flush()
        assertEquals(listOf(MomentId.format(SEC)), db.momentDao().allMoments().map { it.momentId })
        assertEquals(listOf("fa"), db.momentDao().parts(MomentId.format(SEC)).map { it.refId })
    }

    @Test fun aFrameWithoutCaptureEvidenceIsStoredButNeverTriggersAMoment() = runBlocking<Unit> {
        assertTrue(PipelineRecords.appendFrame(ctx, FrameEntity("fu", MS + 5, jpg("u.jpg"), "rokid_glasses", payload = "p"), rows("fu", null)))
        MomentTriggers.get(ctx).flush()
        assertEquals(0, MomentTriggers.get(ctx).builds); assertTrue(db.momentDao().allMoments().isEmpty())
    }

    @Test fun aStoredMarkTriggersItsSecondOnceAndADuplicateOrDroppedMarkDoesNot() = runBlocking<Unit> {
        val seen = ArrayList<Long>(); val epoch = DeletionEpoch("inst", 2, 1_000)
        val rec = MarkRecorder(db.canvasSlice3Dao(), { DaoFence(0, epoch, { 0 }, { epoch }) }, { MS }, onRecorded = { seen += it })
        fun mark(seq: Long, counter: Long = 2L) = MarkMessage(epoch = "e", seq = seq, atMs = 5, atElapsedMs = 5, listen = false, undo = null, deletionCounter = counter)
        assertTrue(rec.onMark(mark(1L), MarkArrival(MS + 500, mapped = true, receivedAtMs = MS + 500)))
        assertTrue(rec.onMark(mark(1L), MarkArrival(MS + 500, mapped = true, receivedAtMs = MS + 500))) // double delivery
        assertTrue(rec.onMark(mark(2L, counter = 1L), MarkArrival(MS + 900, mapped = true, receivedAtMs = MS + 900))) // older deletion epoch: dropped
        assertEquals(listOf(MS + 500), seen)
    }

    @Test fun theCadenceWorkerRebuildsTheRecentHourAndIsScheduledOnce() = runBlocking<Unit> {
        val now = System.currentTimeMillis()
        val fx = MomentFixture(db); fx.frame("w", now - 60_000)
        assertEquals(androidx.work.ListenableWorker.Result.success(), TestListenableWorkerBuilder<MomentCadenceWorker>(ctx).build().doWork())
        assertEquals(listOf(MomentId.ofMs(now - 60_000)), db.momentDao().allMoments().map { it.momentId })
        OrchestrationScheduler(ctx).scheduleCritic(); OrchestrationScheduler(ctx).scheduleCritic()
        assertEquals(1, WorkManager.getInstance(ctx).getWorkInfosForUniqueWork(MomentCadenceWorker.NAME).get().size)
    }
}
