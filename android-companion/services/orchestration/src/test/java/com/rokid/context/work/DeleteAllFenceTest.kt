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
import kotlinx.coroutines.async
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/** Review I1: Delete all stops the pipeline: in-flight writes are fenced, the queue is emptied, the pipeline restarts. */
@OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
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
        runBlocking { wipes.forEach { runCatching { kotlinx.coroutines.withTimeout(40_000) { it.await() } } } }
        quiesce()
        QueueRuntimeStore.clearAll(ctx); AppConfigStore.setPipelineEnabled(ctx, true)
        AiGraph.resetForTests(null); MemoryGraph.get(ctx).db.close(); MemoryGraph.resetForTests(null)
    }

    private fun drain() = runBlocking { TestListenableWorkerBuilder<QueueDrainWorker>(ctx).build().doWork() }

    /**
     * A wipe resumes the pipeline, and the workers it starts (frame ingest of f1, queue drain) run on their own coroutines: one can still be inside a model call,
     * holding the upload slot, when the test goes on or the next test asks for a Delete all. If its reply is a wiping lambda, that Delete all waits on the slot
     * the worker itself holds, for the whole 30 s, and the next test's Delete all times out with UploadInProgress. Cancel the workers, then wait until the
     * process gate is idle (a Delete all on it gets in only when no upload runs), so none outlives the step.
     */
    private fun quiesce() {
        WorkManager.getInstance(ctx).cancelAllWork().result.get()
        runBlocking { repeat(3) { if (runCatching { com.rokid.context.orchestration.DataOpGate.shared.delete { } }.isSuccess) return@runBlocking } }
    }

    /**
     * Codex r13 P2-5: a photo/memory call holds the upload slot, so a Delete all started from inside the provider would wait on that same call. The wipe
     * runs in its OWN coroutine: started while the reply is in flight, it waits until the upload ends, then really completes ([awaitWipe]).
     */
    private var wipe: kotlinx.coroutines.Deferred<Int>? = null
    /** Every wipe this test started: tearDown waits for them all, so none outlives the test's database. */
    private val wipes = java.util.Collections.synchronizedList(mutableListOf<kotlinx.coroutines.Deferred<Int>>())
    private suspend fun wipeWhileInFlight() {
        if (wipe != null) return // one wipe per test (a later call in the same job must not start a second one)
        val w = kotlinx.coroutines.GlobalScope.async(kotlinx.coroutines.Dispatchers.IO) { MemoryWipe.deleteAll(ctx) }
        wipe = w; wipes += w
        // the delete now waits for this upload (or, for a call that holds no upload slot, it already ran)
        kotlinx.coroutines.withTimeout(10_000) { while (!com.rokid.context.orchestration.DataOpGate.shared.busy() && !w.isCompleted) kotlinx.coroutines.delay(5) }
    }
    private fun awaitWipe(before: Long) = runBlocking {
        kotlinx.coroutines.withTimeout(20_000) { checkNotNull(wipe).await() }
        assertTrue("the wipe completed", MemoryWipe.generation.get() > before)
    }
    private fun memories() = runBlocking { MemoryGraph.get(ctx).memories.all() }

    @Test fun aSummaryJobClaimedBeforeTheWipeWritesNothingWhenItsReplyLandsAfter() {
        QueueRuntimeStore.enqueue(ctx, QueuePriority.SUMMARIZER, QueuePayloads.batchedWrite(source = "test", importedFrames = 1, manual = true), personaSnapshot = "p")
        val before = MemoryWipe.generation.get()
        reply = { wipeWhileInFlight(); """{"facts":[{"text":"Maybe a cat","type":"object","confidence":0.3,"tags":[],"source_frame_ids":["f1"]}],"captions":[{"frame_id":"f1","caption":"A cat"}]}""" }
        drain()
        awaitWipe(before)
        assertTrue(memories().isEmpty())
        // The low-confidence path would queue a reprocess carrying the fact text: not after a wipe.
        assertTrue(QueueRuntimeStore.recent(ctx, 10).none { it.queue == QueuePriority.PRIORITY_REPROCESS })
    }

    @Test fun aReprocessJobAndAnExplicitWriteInFlightAcrossTheWipeWriteNothing() = runBlocking<Unit> {
        PipelineRecords.appendFact(ctx, "rokid_mem_m_0", null, "Milk carton", "object", emptyList(), 0.4, MemoryTier.SHORT, ReviewState.NEEDS_REVIEW)
        QueueRuntimeStore.enqueue(ctx, QueuePriority.PRIORITY_REPROCESS, QueuePayloads.reprocess(reason = "low", sourceIds = listOf("rokid_mem_m_0")), personaSnapshot = "p")
        // codex r12: this call carries its source memory, so it holds the upload slot and a real Delete all waits for it to end; the wipe that lands
        // while the reply is in flight is simulated by moving the generation (what Delete all commits), and the summary must still not be written
        reply = { MemoryWipe.generation.incrementAndGet(); """{"summary":"Milk in the fridge","entities":[],"confidence":0.9,"candidateMemoryType":"short_term"}""" }
        drain()
        assertEquals("no summary written across the wipe", listOf("rokid_mem_m_0"), memories().map { it.id })
        MemoryWipe.deleteAll(ctx)
        assertTrue(memories().isEmpty())
        quiesce() // the wipe resumed the pipeline: no background worker may call the wiping reply below from inside its own upload slot

        QueueRuntimeStore.enqueue(ctx, QueuePriority.EXPLICIT_WRITE, QueuePayloads.explicitWrite("remember my keys are on the shelf", "en", manual = true), personaSnapshot = "p")
        // this call holds the upload slot too: the wipe runs in its own coroutine (a Delete all called inside the reply would wait 30 s on the very slot it
        // is called from, then give up with UploadInProgress, and the test would pass without any wipe)
        val beforeWrite = MemoryWipe.generation.get()
        reply = { wipeWhileInFlight(); """{"facts":[{"text":"Keys on the shelf","type":"note","confidence":0.9,"tags":[]}]}""" }
        drain()
        awaitWipe(beforeWrite)
        assertTrue(memories().isEmpty())
    }

    @Test fun aQueuedAskAnsweredAcrossTheWipeSavesNoAsk() = runBlocking<Unit> {
        PipelineRecords.appendFact(ctx, "rokid_mem_k_0", "f1", "Keys on the shelf", "note", emptyList(), 0.9, MemoryTier.SHORT, ReviewState.OK)
        QueueRuntimeStore.enqueue(ctx, QueuePriority.READ, QueuePayloads.readContext("keys?", "en", manual = true), personaSnapshot = "p")
        val before = MemoryWipe.generation.get()
        reply = { wipeWhileInFlight(); """{"answer":"On the shelf.","sources":["rokid_mem_k_0"]}""" }
        drain()
        awaitWipe(before)
        assertTrue("no Ask row: the fence drops it, or the completed wipe removed it", MemoryGraph.get(ctx).asks.recent(10).first().isEmpty())
        assertTrue("the wipe really ran", memories().isEmpty())
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
