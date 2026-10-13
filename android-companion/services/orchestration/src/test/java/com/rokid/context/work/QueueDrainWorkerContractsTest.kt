package com.rokid.context.work

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.rokid.context.ai.AiGraph
import com.rokid.context.data.runtime.QueuePayloads
import com.rokid.context.data.runtime.QueueRuntimeStore
import com.rokid.context.domain.queue.QueuePriority
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.FrameEntity
import com.rokid.context.memory.db.MemoryTier
import com.rokid.context.memory.db.ReviewState
import com.rokid.context.orchestration.PipelineRecords
import com.rokid.context.orchestration.TestAi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class QueueDrainWorkerContractsTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val prompts = mutableListOf<String>()
    private var replies: (String) -> String = { "{}" }

    @Before fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(ctx)
        MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx))
        TestAi.bindAll(TestAi.graph(ctx) { p -> prompts += p; replies(p) })
        runBlocking {
            val jpg = File(ctx.filesDir, "f.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }.absolutePath
            PipelineRecords.appendFrame(ctx, FrameEntity("f_old", 10, jpg, "rokid_glasses", "old"))
            PipelineRecords.appendFrame(ctx, FrameEntity("f_new", 20, jpg, "rokid_glasses", "new"))
        }
        QueueRuntimeStore.enqueue(ctx, QueuePriority.SUMMARIZER, QueuePayloads.batchedWrite(source = "test", importedFrames = 2, manual = true), personaSnapshot = "p")
    }

    @After fun tearDown() { AiGraph.resetForTests(null); MemoryGraph.get(ctx).db.close(); MemoryGraph.resetForTests(null) }

    private fun run() = runBlocking { TestListenableWorkerBuilder<QueueDrainWorker>(ctx).build().doWork() }
    private fun saved() = runBlocking { MemoryGraph.get(ctx).memories.recent(20) }

    /** Final review I1: one VISION_EXTRACT call per photo; each fact is stored on the frame that call showed. */
    @Test fun frameFactsAreStoredOnTheFrameTheModelSaw() {
        replies = { p ->
            val text = if ("id=f_old" in p) "Keys by the door" else "Milk carton on the counter"
            """{"facts":[{"text":"$text","type":"object","confidence":0.9,"tags":["kitchen"]}]}"""
        }
        run()
        assertEquals(mapOf("Keys by the door" to "f_old", "Milk carton on the counter" to "f_new"), saved().associate { it.text to it.frameId })
        assertTrue(saved().all { it.reviewState == ReviewState.OK })
        assertEquals(2, prompts.size)
        prompts.forEach { p -> assertTrue(p.contains("BEGIN_DATA frames")); assertTrue(("id=f_old" in p) != ("id=f_new" in p)) }
    }

    @Test fun lowConfidenceFactsTheCriticRejectsGoToTheReviewQueue() {
        replies = { p -> if ("verdict" in p) """{"verdict":"fail","reasons":["not visible"]}""" else """{"facts":[{"text":"Maybe a cat","type":"object","confidence":0.3,"tags":[]}]}""" }
        run()
        assertTrue(saved().isNotEmpty() && saved().all { it.reviewState == ReviewState.NEEDS_REVIEW })
        assertTrue(QueueRuntimeStore.recent(ctx, 10).any { it.queue == QueuePriority.PRIORITY_REPROCESS })
    }

    /** Fix M5: a READ job run again after a late cancellation keeps one Ask row (deterministic id, insert-ignore). */
    @Test fun aReRunOfAReadJobKeepsOneAsk() {
        QueueRuntimeStore.markSucceeded(ctx, QueueRuntimeStore.recent(ctx, 10).single().id)
        runBlocking { PipelineRecords.appendFact(ctx, "rokid_mem_m_0", "f_new", "Milk carton nearly empty", "object", emptyList(), 0.9, MemoryTier.SHORT, ReviewState.OK) }
        val job = QueueRuntimeStore.enqueue(ctx, QueuePriority.READ, QueuePayloads.readContext("milk?", "en", manual = true), personaSnapshot = "p")
        replies = { """{"answer":"Milk is almost empty.","sources":["rokid_mem_m_0"]}""" }
        run()
        QueueRuntimeStore.markPostponed(ctx, job.id, "cancelled; will retry", delayMs = 0, nowEpochMs = System.currentTimeMillis() - 60_000)
        run()
        assertEquals(QueueRuntimeStore.STATE_SUCCEEDED, QueueRuntimeStore.recent(ctx, 10).single { it.id == job.id }.state)
        assertEquals(1, runBlocking { MemoryGraph.get(ctx).asks.recent(10).first() }.size)
    }

    @Test fun lowConfidenceFactsTheCriticPassesAreTrusted() {
        replies = { p -> if ("verdict" in p) """{"verdict":"pass","reasons":[]}""" else """{"facts":[{"text":"A red mug","type":"object","confidence":0.5,"tags":[]}]}""" }
        run()
        assertTrue(saved().isNotEmpty() && saved().all { it.reviewState == ReviewState.OK })
        assertTrue(QueueRuntimeStore.recent(ctx, 10).none { it.queue == QueuePriority.PRIORITY_REPROCESS })
    }
}
