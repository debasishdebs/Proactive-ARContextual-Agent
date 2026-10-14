package com.rokid.context.work.frames

import android.content.Context
import android.os.BatteryManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.rokid.context.ai.AiGraph
import com.rokid.context.ai.InMemoryKeyVault
import com.rokid.context.ai.InMemorySeedState
import com.rokid.context.ai.ProviderBuilder
import com.rokid.context.data.runtime.AppConfigStore
import com.rokid.context.data.runtime.QueuePayloads
import com.rokid.context.data.runtime.QueueRuntimeStore
import com.rokid.context.domain.queue.QueuePriority
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.FrameEntity
import com.rokid.context.memory.db.FrameMetaEntity
import com.rokid.context.memory.db.FrameStateEntity
import com.rokid.context.model.CallClass
import com.rokid.context.model.ModelImageInput
import com.rokid.context.model.ModelProvider
import com.rokid.context.orchestration.TestAi
import com.rokid.context.work.QueueDrainWorker
import com.rokid.context.work.budget.CallBudget
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

/** The legacy job path and the pipeline share frame_state claims; ingest -> pipeline -> fake model -> memory under the budget. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class KeyframeIntegrationTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val db get() = MemoryGraph.get(ctx).db
    private val prompts = Collections.synchronizedList(mutableListOf<String>())
    private val callTimes = Collections.synchronizedList(mutableListOf<Long>())

    @Before fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(ctx)
        MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx))
        KeyframeGraph.resetForTests()
        val budget = CallBudget(System::currentTimeMillis, { 150L })
        AiGraph.gate = budget
        val ai = AiGraph(MemoryGraph.get(ctx), InMemoryKeyVault(), InMemorySeedState(), ProviderBuilder { _, _, _ ->
            object : ModelProvider {
                override suspend fun generate(prompt: String): String {
                    prompts += prompt; callTimes += System.currentTimeMillis()
                    val ids = Regex("frame_id: (\\S+) ").findAll(prompt).map { it.groupValues[1] }.toList()
                    val facts = ids.joinToString(",") { """{"text":"A mug $it","type":"object","confidence":0.9,"tags":[],"source_frame_ids":["$it"]}""" }
                    val caps = ids.joinToString(",") { """{"frame_id":"$it","caption":"A photo"}""" }
                    return """{"facts":[$facts],"captions":[$caps]}"""
                }
                override suspend fun generateWithImages(prompt: String, images: List<ModelImageInput>) = generate(prompt)
            }
        }, gate = budget).also { AiGraph.resetForTests(it) }
        TestAi.bindAll(ai)
        AppConfigStore.setPipelineEnabled(ctx, true)
        QueueRuntimeStore.clearAll(ctx)
        shadowOf(ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager).setIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY, 80)
    }

    @After fun tearDown() {
        KeyframeGraph.resetForTests(); AiGraph.gate = com.rokid.context.model.PassThroughGate
        QueueRuntimeStore.clearAll(ctx)
        AiGraph.resetForTests(null); db.close(); MemoryGraph.resetForTests(null)
    }

    private suspend fun seed(id: String, state: String, updatedAt: Long, claimedAt: Long? = null) {
        val f = File(ctx.cacheDir, "$id.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        db.frameDao().insertIgnore(FrameEntity(id, System.currentTimeMillis() - 1000, f.absolutePath, "rokid_glasses"))
        db.frameMetaDao().upsert(FrameMetaEntity(id, null, null, "STILL", false, null, null, null, null, null, null, null, null, null, updatedAt))
        db.frameStateDao().insertIgnore(listOf(FrameStateEntity(id, state, 0, null, claimedAt, claimedAt?.let { "pipeline" }, updatedAt)))
    }

    private fun legacyJob(vararg ids: String) {
        QueueRuntimeStore.enqueue(ctx, QueuePriority.SUMMARIZER, QueuePayloads.batchedWrite("test", ids.size, manual = false, frameIdsCsv = ids.joinToString(",")), personaSnapshot = "p")
        runBlocking { TestListenableWorkerBuilder<QueueDrainWorker>(ctx).build().doWork() }
    }

    private suspend fun memories() = db.memoryDao().all().filter { it.type != "caption" }

    @Test fun legacyJobAndPipelineDoNotDoubleExtract() = runBlocking {
        val old = System.currentTimeMillis() - 10 * 60_000L
        // the pipeline holds a live claim on f1, f2 is already EXTRACTED, f3 is NEAR_DUP
        seed("f1", "EXTRACTING", old, claimedAt = System.currentTimeMillis() - 5_000)
        seed("f2", "EXTRACTED", old); seed("f3", "NEAR_DUP", old)
        legacyJob("f1", "f2", "f3")
        assertTrue("no model call for frames the pipeline owns or finished", prompts.isEmpty())
        assertEquals("EXTRACTING", db.frameStateDao().get("f1")!!.state)
        // f4 is idle PENDING (a live legacy 24 h job): the legacy path claims it, so the pipeline cannot take it
        seed("f4", "PENDING", old)
        legacyJob("f4")
        assertEquals(1, prompts.size)
        assertEquals("EXTRACTED", db.frameStateDao().get("f4")!!.state)
        assertEquals(0, KeyframePipeline(db, { _, _, _ -> error("must not run") }, { System.currentTimeMillis() }).drainOnce())
        legacyJob("f4") // a second run of the same job extracts nothing more
        assertEquals(1, prompts.size)
        assertEquals(1, memories().size)
    }

    @Test fun ingestToMemoryUnderBudget() = runBlocking {
        val now = System.currentTimeMillis()
        for (i in 1..7) seed("g$i", "PENDING", now)
        val handle = KeyframeGraph.get(ctx)
        handle.signal()
        withTimeout(15_000) { while ((1..7).any { db.frameStateDao().get("g$it")!!.state != "EXTRACTED" }) delay(25) }
        assertEquals(7, memories().size)
        assertEquals("3 + 3 + 1 frames, one call each", 3, prompts.size)
        assertEquals(3, (AiGraph.gate as CallBudget).stats.value.callsLastHour[CallClass.EXTRACT])
        withTimeout(5_000) { while ((handle.stats.value.counts["EXTRACTED"] ?: 0) != 7) delay(25) }
        assertEquals(0, handle.stats.value.backlog)
    }
}
