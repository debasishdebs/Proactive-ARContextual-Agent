package com.rokid.context.work

import android.content.Context
import android.os.BatteryManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.rokid.context.ai.AiGraph
import com.rokid.context.data.runtime.AppConfigStore
import com.rokid.context.data.runtime.QueuePayloads
import com.rokid.context.data.runtime.QueueRuntimeStore
import com.rokid.context.domain.queue.QueuePriority
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.FrameEntity
import com.rokid.context.memory.db.MemoryTier
import com.rokid.context.memory.db.ReviewState
import com.rokid.context.orchestration.MemoryWipe
import com.rokid.context.orchestration.PipelineRecords
import com.rokid.context.orchestration.TestAi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/** Review I1: Delete all stops the pipeline: in-flight writes are fenced, the queue is emptied, the pipeline restarts. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class DeleteAllFenceTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    /** The model reply; a test may wipe from inside it, i.e. while the job waits on the AI. */
    private var reply: suspend (String) -> String = { "{}" }

    @Before fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(ctx)
        MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx))
        TestAi.bindAll(TestAi.graph(ctx) { p -> reply(p) })
        AppConfigStore.setPipelineEnabled(ctx, true)
        QueueRuntimeStore.clearAll(ctx)
        // Non-manual jobs (reprocess) defer on a low battery; Robolectric reports 0 %.
        shadowOf(ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager).setIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY, 80)
        runBlocking {
            val jpg = File(ctx.filesDir, "rokid_archive/f.jpg").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1, 2, 3)) }.absolutePath
            PipelineRecords.appendFrame(ctx, FrameEntity("f1", 10, jpg, "rokid_glasses", "frame"))
        }
    }

    @After fun tearDown() {
        QueueRuntimeStore.clearAll(ctx); AppConfigStore.setPipelineEnabled(ctx, true)
        AiGraph.resetForTests(null); MemoryGraph.get(ctx).db.close(); MemoryGraph.resetForTests(null)
    }

    private fun drain() = runBlocking { TestListenableWorkerBuilder<QueueDrainWorker>(ctx).build().doWork() }
    private fun memories() = runBlocking { MemoryGraph.get(ctx).memories.all() }

    @Test fun aSummaryJobClaimedBeforeTheWipeWritesNothingWhenItsReplyLandsAfter() {
        QueueRuntimeStore.enqueue(ctx, QueuePriority.SUMMARIZER, QueuePayloads.batchedWrite(source = "test", importedFrames = 1, manual = true), personaSnapshot = "p")
        reply = { MemoryWipe.deleteAll(ctx); """{"facts":[{"text":"Maybe a cat","type":"object","confidence":0.3,"tags":[],"source_frame_ids":["f1"]}],"captions":[{"frame_id":"f1","caption":"A cat"}]}""" }
        drain()
        assertTrue(memories().isEmpty())
        // The low-confidence path would queue a reprocess carrying the fact text: not after a wipe.
        assertTrue(QueueRuntimeStore.recent(ctx, 10).none { it.queue == QueuePriority.PRIORITY_REPROCESS })
    }

    @Test fun aReprocessJobAndAnExplicitWriteInFlightAcrossTheWipeWriteNothing() = runBlocking<Unit> {
        PipelineRecords.appendFact(ctx, "rokid_mem_m_0", null, "Milk carton", "object", emptyList(), 0.4, MemoryTier.SHORT, ReviewState.NEEDS_REVIEW)
        QueueRuntimeStore.enqueue(ctx, QueuePriority.PRIORITY_REPROCESS, QueuePayloads.reprocess(reason = "low", sourceIds = listOf("rokid_mem_m_0")), personaSnapshot = "p")
        reply = { MemoryWipe.deleteAll(ctx); """{"summary":"Milk in the fridge","entities":[],"confidence":0.9,"candidateMemoryType":"short_term"}""" }
        drain()
        assertTrue(memories().isEmpty())

        QueueRuntimeStore.enqueue(ctx, QueuePriority.EXPLICIT_WRITE, QueuePayloads.explicitWrite("remember my keys are on the shelf", "en", manual = true), personaSnapshot = "p")
        reply = { MemoryWipe.deleteAll(ctx); """{"facts":[{"text":"Keys on the shelf","type":"note","confidence":0.9,"tags":[]}]}""" }
        drain()
        assertTrue(memories().isEmpty())
    }

    @Test fun aQueuedAskAnsweredAcrossTheWipeSavesNoAsk() = runBlocking<Unit> {
        PipelineRecords.appendFact(ctx, "rokid_mem_k_0", "f1", "Keys on the shelf", "note", emptyList(), 0.9, MemoryTier.SHORT, ReviewState.OK)
        QueueRuntimeStore.enqueue(ctx, QueuePriority.READ, QueuePayloads.readContext("keys?", "en", manual = true), personaSnapshot = "p")
        reply = { MemoryWipe.deleteAll(ctx); """{"answer":"On the shelf.","sources":["rokid_mem_k_0"]}""" }
        drain()
        assertTrue(MemoryGraph.get(ctx).asks.recent(10).first().isEmpty())
    }

    @Test fun appendFactWithAStaleGenerationWritesNothing() = runBlocking<Unit> {
        val before = MemoryWipe.generation.get()
        MemoryWipe.deleteAll(ctx)
        assertFalse(PipelineRecords.appendFact(ctx, "rokid_mem_s_0", null, "Stale", "note", emptyList(), 0.9, MemoryTier.SHORT, ReviewState.OK, generation = before))
        assertTrue(PipelineRecords.appendFact(ctx, "rokid_mem_s_1", null, "Fresh", "note", emptyList(), 0.9, MemoryTier.SHORT, ReviewState.OK))
        assertEquals(listOf("rokid_mem_s_1"), memories().map { it.id })
    }

    @Test fun queuedJobsAreGoneAndThePipelineResumes() = runBlocking<Unit> {
        QueueRuntimeStore.enqueue(ctx, QueuePriority.EXPLICIT_WRITE, QueuePayloads.explicitWrite("remember the milk", "en", manual = true), personaSnapshot = "p")
        QueueRuntimeStore.enqueue(ctx, QueuePriority.PRIORITY_REPROCESS, QueuePayloads.reprocess(reason = "low", sourceIds = listOf("rokid_mem_m_0")), personaSnapshot = "p")
        OrchestrationScheduler(ctx).scheduleQueueDrain()
        MemoryWipe.deleteAll(ctx)
        assertTrue(QueueRuntimeStore.recent(ctx, 50).none { it.queue == QueuePriority.EXPLICIT_WRITE || it.queue == QueuePriority.PRIORITY_REPROCESS })
        val drain = WorkManager.getInstance(ctx).getWorkInfosForUniqueWork(OrchestrationScheduler.QUEUE_DRAIN_WORK).get()
        assertTrue("$drain", drain.any { it.state == WorkInfo.State.ENQUEUED })
    }

    @Test fun aPausedPipelineStaysPausedAfterTheWipe() = runBlocking<Unit> {
        OrchestrationScheduler(ctx).scheduleQueueDrain()
        AppConfigStore.setPipelineEnabled(ctx, false)
        MemoryWipe.deleteAll(ctx)
        val drain = WorkManager.getInstance(ctx).getWorkInfosForUniqueWork(OrchestrationScheduler.QUEUE_DRAIN_WORK).get()
        assertTrue("$drain", drain.none { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING })
    }
}
