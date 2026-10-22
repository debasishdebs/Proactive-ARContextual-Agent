package com.rokid.context.work.frames

import android.content.Context
import android.os.BatteryManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.testing.WorkManagerTestInitHelper
import com.rokid.context.ai.AiGraph
import com.rokid.context.ai.InMemoryKeyVault
import com.rokid.context.ai.InMemorySeedState
import com.rokid.context.ai.ProviderBuilder
import com.rokid.context.ai.canvas.DefaultPipelines
import com.rokid.context.ai.canvas.PipelineJson
import com.rokid.context.canvas.FrameIntentSink
import com.rokid.context.canvas.LiveMode
import com.rokid.context.data.runtime.AppConfigStore
import com.rokid.context.data.runtime.QueueRuntimeStore
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.ProposalRepository
import com.rokid.context.memory.db.CanvasPipelineVersionEntity
import com.rokid.context.memory.db.CanvasPublishEntity
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.FrameEntity
import com.rokid.context.memory.db.FrameMetaEntity
import com.rokid.context.memory.db.FrameStateEntity
import com.rokid.context.memory.forget.ForgetService
import com.rokid.context.model.ModelImageInput
import com.rokid.context.model.ModelProvider
import com.rokid.context.orchestration.TestAi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.Collections

/** The real keyframe pipeline end to end through the canvas seam (spec section 5): the save tail is the live one, effects come after the save. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class LiveSeamIntegrationTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val db get() = MemoryGraph.get(ctx).db
    private val prompts = Collections.synchronizedList(mutableListOf<String>())
    private val labels = Collections.synchronizedList(mutableListOf<Triple<String, String, Boolean>>()) // frame, label, memories existed at the call
    @Volatile private var intentReply = """{"activity":"driving","confidence":0.8,"reason":"x"}"""
    @Volatile private var midCall: (suspend () -> Unit)? = null

    @Before fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(ctx)
        MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx))
        KeyframeGraph.resetForTests()
        val ai = AiGraph(MemoryGraph.get(ctx), InMemoryKeyVault(), InMemorySeedState(), ProviderBuilder { _, _, _ ->
            object : ModelProvider {
                override suspend fun generate(prompt: String): String {
                    prompts += prompt
                    midCall?.let { hook -> midCall = null; hook() }
                    if ("Look at the photos" in prompt) return intentReply
                    val ids = Regex("frame_id: (\\S+) ").findAll(prompt).map { it.groupValues[1] }.toList()
                    val facts = ids.joinToString(",") { """{"text":"A mug $it","type":"object","confidence":0.9,"tags":[],"source_frame_ids":["$it"]}""" }
                    val caps = ids.joinToString(",") { """{"frame_id":"$it","caption":"A photo"}""" }
                    return """{"facts":[$facts],"captions":[$caps]}"""
                }
                override suspend fun generateWithImages(prompt: String, images: List<ModelImageInput>) = generate(prompt)
            }
        }).also { AiGraph.resetForTests(it) }
        TestAi.bindAll(ai)
        AppConfigStore.setPipelineEnabled(ctx, true)
        QueueRuntimeStore.clearAll(ctx)
        shadowOf(ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager).setIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY, 80)
        KeyframeGraph.intentSink = FrameIntentSink { f, l, _, _ -> labels += Triple(f, l, runBlocking { db.memoryDao().all().any { it.frameId == f || it.text.endsWith(" $f") } }) }
    }

    @After fun tearDown() {
        KeyframeGraph.intentSink = FrameIntentSink.None
        KeyframeGraph.resetForTests(); QueueRuntimeStore.clearAll(ctx)
        AiGraph.resetForTests(null); db.close(); MemoryGraph.resetForTests(null)
    }

    private suspend fun seed(id: String) {
        val f = File(ctx.cacheDir, "$id.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val now = System.currentTimeMillis()
        db.frameDao().insertIgnore(FrameEntity(id, now - 1000, f.absolutePath, "rokid_glasses"))
        db.frameMetaDao().upsert(FrameMetaEntity(id, null, null, "STILL", false, null, null, null, null, null, null, null, null, null, now, routedAt = now))
        db.frameStateDao().insertIgnore(listOf(FrameStateEntity(id, "PENDING", 0, null, null, null, now)))
    }

    private suspend fun publishIntent(mode: LiveMode): Long {
        val p = DefaultPipelines.intentDraft("EXTRACT {{recent_context}} {{context_sources}}")
        val dao = db.canvasDao()
        dao.insertVersion(CanvasPipelineVersionEntity(p.id, 1, PipelineJson.encode(p), "h", "n", 1))
        return dao.insertPublish(CanvasPublishEntity(pipelineId = p.id, version = 1, hash = "h", publishedAt = 1, rolledBackAt = null, snapshotJson = "{}", kind = "interpreter", mode = mode.name))
    }

    private suspend fun waitExtracted(vararg ids: String) = withTimeout(20_000) { while (ids.any { db.frameStateDao().get(it)?.state != "EXTRACTED" }) delay(25) }
    private suspend fun facts() = db.memoryDao().all().filter { it.type != "caption" }.map { it.text }.sorted()

    @Test fun noPublishIsTheOldPathWithNoCanvasRows() = runBlocking<Unit> {
        for (i in 1..3) seed("g$i")
        KeyframeGraph.get(ctx).signal()
        waitExtracted("g1", "g2", "g3")
        assertEquals(listOf("A mug g1", "A mug g2", "A mug g3"), facts())
        assertEquals("one extraction call, no intent calls", 1, prompts.size)
        assertTrue(labels.isEmpty())
        for (i in 1..3) assertTrue(db.canvasDao().tracesForFrame("g$i").isEmpty())
    }

    @Test fun liveInterpreterSavesTheSameMemoriesAndCommitsAfterTheSave() = runBlocking<Unit> {
        for (i in 1..3) seed("g$i")
        val pid = publishIntent(LiveMode.LIVE)
        KeyframeGraph.get(ctx).signal()
        waitExtracted("g1", "g2", "g3")
        assertEquals(listOf("A mug g1", "A mug g2", "A mug g3"), facts())
        assertEquals(3, prompts.count { "Look at the photos" in it })
        withTimeout(10_000) { while (labels.size < 3) delay(25) }
        assertEquals(setOf("g1", "g2", "g3"), labels.map { it.first }.toSet())
        assertTrue("every label came after its memory was saved", labels.all { it.third })
        assertEquals(3, db.canvasDao().interpretedCount(pid))
        assertEquals(0, db.canvasDao().recentFallbacks(pid, 30))
    }

    @Test fun liveFallbackStillSavesTheMemories() = runBlocking<Unit> {
        // intent replies that are not JSON: every batch falls back to the plain extraction and the memories are saved as usual
        intentReply = "no"
        for (i in 1..3) seed("g$i")
        val pid = publishIntent(LiveMode.LIVE)
        KeyframeGraph.get(ctx).signal()
        waitExtracted("g1", "g2", "g3")
        assertEquals(listOf("A mug g1", "A mug g2", "A mug g3"), facts())
        withTimeout(10_000) { while (db.canvasDao().interpretedCount(pid) < 3) delay(25) }
        assertEquals(3, db.canvasDao().recentFallbacks(pid, 30))
        assertEquals("exactly one live extraction after the fallback", 1, prompts.count { "Look at the photos" !in it })
        assertTrue("labels of a fallen-back batch are discarded", labels.isEmpty())
    }

    @Test fun forgetMidBatchLeavesNoTraceAndNoLabelForTheForgottenFrame() = runBlocking<Unit> {
        for (i in 1..3) seed("k$i")
        val pid = publishIntent(LiveMode.LIVE)
        midCall = { ForgetService(db, ProposalRepository(db.proposalDao(), db.actionDao(), db)) { 1_000 }.forgetMoment("k2") }
        KeyframeGraph.get(ctx).signal()
        waitExtracted("k1", "k3")
        withTimeout(10_000) { while (labels.size < 2) delay(25) }
        assertTrue(db.canvasDao().tracesForFrame("k2").isEmpty())
        assertTrue(labels.none { it.first == "k2" })
        assertTrue(db.canvasDao().traces(pid, 1_000).none { it.frameId == "k2" })
        assertEquals(setOf("k1", "k3"), labels.map { it.first }.toSet())
    }

    @Test fun shadowModeKeepsTheLiveExtractionAndRecordsNothingBeforeTheFourthBatch() = runBlocking<Unit> {
        for (i in 1..3) seed("g$i")
        val pid = publishIntent(LiveMode.SHADOW)
        KeyframeGraph.get(ctx).signal()
        waitExtracted("g1", "g2", "g3")
        assertEquals(listOf("A mug g1", "A mug g2", "A mug g3"), facts())
        assertEquals(1, prompts.size)
        assertTrue(labels.isEmpty())
        assertTrue(db.canvasDao().traces(pid, 100).isEmpty())
    }
}
