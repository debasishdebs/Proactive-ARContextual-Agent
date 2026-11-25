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
    /** A VISION_EXTRACT reply for the photo [p] labels (the provenance contract: source_frame_ids and a caption). */
    private fun vision(p: String, text: String, confidence: Double = 0.9, tags: String = "") =
        Regex("- frame_id: (\\S+) ").find(p)!!.groupValues[1].let { id ->
            """{"facts":[{"text":"$text","type":"object","confidence":$confidence,"tags":[$tags],"source_frame_ids":["$id"]}],"captions":[{"frame_id":"$id","caption":"A photo"}]}"""
        }

    @Test fun frameFactsAreStoredOnTheFrameTheModelSaw() {
        replies = { p ->
            vision(p, if ("frame_id: f_old" in p) "Keys by the door" else "Milk carton on the counter", tags = "\"kitchen\"")
        }
        run()
        assertEquals(mapOf("Keys by the door" to "f_old", "Milk carton on the counter" to "f_new"), saved().filter { it.type != "caption" }.associate { it.text to it.frameId })
        // each photo also gets a caption memory on its own frame
        assertEquals(setOf("f_old", "f_new"), saved().filter { it.type == "caption" }.map { it.frameId }.toSet())
        assertTrue(saved().all { it.reviewState == ReviewState.OK })
        assertEquals(2, prompts.size)
        prompts.forEach { p -> assertTrue(p.contains("BEGIN_DATA frames")); assertTrue(("frame_id: f_old" in p) != ("frame_id: f_new" in p)) }
    }

    /** v18 WP11a: every fact and caption of the legacy per-photo extraction carries the model that answered. */
    @Test fun everyExtractedMemoryIsStoredWithTheModelThatAnswered() {
        replies = { p -> vision(p, "Milk carton on the counter") }
        run()
        val dao = MemoryGraph.get(ctx).db.modelAttributionDao()
        assertTrue(saved().size >= 4)
        saved().forEach { assertEquals(it.id, "gpt", runBlocking { dao.get("MEMORY", it.id) }?.model) }
    }

    @Test fun lowConfidenceFactsTheCriticRejectsGoToTheReviewQueue() {
        replies = { p -> if ("verdict" in p) """{"verdict":"fail","reasons":["not visible"]}""" else vision(p, "Maybe a cat", 0.3) }
        run()
        assertTrue(saved().any { it.type != "caption" } && saved().filter { it.type != "caption" }.all { it.reviewState == ReviewState.NEEDS_REVIEW })
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
        replies = { p -> if ("verdict" in p) """{"verdict":"pass","reasons":[]}""" else vision(p, "A red mug", 0.5) }
        run()
        assertTrue(saved().isNotEmpty() && saved().all { it.reviewState == ReviewState.OK })
        assertTrue(QueueRuntimeStore.recent(ctx, 10).none { it.queue == QueuePriority.PRIORITY_REPROCESS })
    }
}
