package com.rokid.context.work

import android.content.Context
import android.os.BatteryManager
import android.util.Base64
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
import com.rokid.context.memory.db.ForgetTombstoneEntity
import com.rokid.context.memory.db.FrameEntity
import com.rokid.context.memory.db.MemoryTier
import com.rokid.context.memory.db.ReviewState
import com.rokid.context.model.ModelImageInput
import com.rokid.context.model.ModelProvider
import com.rokid.context.orchestration.PipelineRecords
import com.rokid.context.orchestration.TestAi
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

/** Phase 7 F2: reprocess payloads carry ids; the drain re-reads them, so forgotten text never reaches a model. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class QueueDrainReprocessTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val prompts = mutableListOf<String>()
    private val images = mutableListOf<String>()
    private var extract: () -> Unit = {}
    private var summarizeHook: () -> Unit = {}
    /** The facts reply for one VISION_EXTRACT call, given the images it carried (Base64). */
    private var factsFor: (List<String>) -> String = { facts }
    private var lastImages: List<String> = emptyList()
    private val facts = """{"facts":[{"text":"A red mug","type":"object","confidence":0.9,"tags":[]}]}"""
    private val summary = """{"summary":"Fruit bowl on the table","events":[],"entities":["fruit"],"confidence":0.9,"candidate_memory_type":"short_term"}"""

    @Before fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(ctx)
        MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx))
        val ai = AiGraph(MemoryGraph.get(ctx), InMemoryKeyVault(), InMemorySeedState(), ProviderBuilder { _, _, _ ->
            object : ModelProvider {
                override suspend fun generate(prompt: String): String {
                    prompts += prompt
                    return if ("BEGIN_DATA frames" in prompt) { extract(); factsFor(lastImages) } else { summarizeHook(); summary }
                }
                override suspend fun generateWithImages(prompt: String, images: List<ModelImageInput>): String {
                    lastImages = images.map { it.base64Data }
                    this@QueueDrainReprocessTest.images += lastImages
                    return try { generate(prompt) } finally { lastImages = emptyList() }
                }
            }
        }).also { AiGraph.resetForTests(it) }
        TestAi.bindAll(ai)
        AppConfigStore.setPipelineEnabled(ctx, true)
        QueueRuntimeStore.clearAll(ctx)
        shadowOf(ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager).setIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY, 80)
    }

    @After fun tearDown() {
        QueueRuntimeStore.clearAll(ctx)
        AiGraph.resetForTests(null); MemoryGraph.get(ctx).db.close(); MemoryGraph.resetForTests(null)
    }

    private fun drain() = runBlocking { TestListenableWorkerBuilder<QueueDrainWorker>(ctx).build().doWork() }
    private fun fact(id: String, text: String, state: ReviewState = ReviewState.OK, frameId: String? = null) = runBlocking {
        PipelineRecords.appendFact(ctx, id, frameId, text, "object", emptyList(), 0.6, MemoryTier.SHORT, state)
    }
    private fun enqueue(payload: String) = QueueRuntimeStore.enqueue(ctx, QueuePriority.PRIORITY_REPROCESS, payload, personaSnapshot = "p")

    @Test fun invalidatedSourceTextNeverSentToModel() {
        fact("rokid_mem_s_0", "Surviving apple note")
        fact("rokid_mem_s_1", "Forgotten secret diary")
        enqueue(QueuePayloads.reprocess("low", listOf("rokid_mem_s_0", "rokid_mem_s_1")))
        // Forget lands after the enqueue: the drain re-reads and drops it.
        runBlocking { MemoryGraph.get(ctx).db.forgetDao().invalidate(listOf("rokid_mem_s_1")) }
        drain()
        val p = prompts.single()
        assertTrue(p.contains("Surviving apple note"))
        assertFalse(p.contains("secret diary"))
    }

    @Test fun legacyTextPayloadSkipped() {
        fact("rokid_mem_s_0", "Milk carton")
        val job = enqueue("""{"kind":"priority_reprocess","reason":"low","source_context":"Milk carton","manual":false}""")
        drain()
        assertTrue(prompts.isEmpty())
        assertEquals(QueueRuntimeStore.STATE_FAILED, QueueRuntimeStore.recent(ctx, 10).single { it.id == job.id }.state)
        val trace = runBlocking { PipelineRecords.recentTraces(ctx, 10) }.single { it.summaryId == job.id }
        assertEquals("Skipped: sources changed", trace.note)
    }

    @Test fun noSurvivingSourceIsSkipped() {
        val job = enqueue(QueuePayloads.reprocess("low", listOf("gone")))
        drain()
        assertTrue(prompts.isEmpty())
        assertEquals(QueueRuntimeStore.STATE_FAILED, QueueRuntimeStore.recent(ctx, 10).single { it.id == job.id }.state)
    }

    @Test fun summaryHasEdgesAndIsDerived() {
        fact("rokid_mem_s_0", "Apples in a bowl")
        fact("rokid_mem_s_1", "Bananas in a bowl")
        val job = enqueue(QueuePayloads.reprocess("low", listOf("rokid_mem_s_0", "rokid_mem_s_1")))
        drain()
        val dao = MemoryGraph.get(ctx).db.forgetDao()
        val out = runBlocking { MemoryGraph.get(ctx).memories.all() }.single { it.text == "Fruit bowl on the table" }
        assertEquals("rokid_mem_${job.id.takeLast(12)}", out.id)
        assertTrue(out.derived)
        assertEquals(setOf("rokid_mem_s_0", "rokid_mem_s_1"), runBlocking { dao.edgesFrom(listOf(out.id)) }.map { it.sourceMemoryId }.toSet())
    }

    @Test fun tombstonedFrameNotSentAsImage() {
        runBlocking {
            listOf("f1" to byteArrayOf(1, 1, 1), "f2" to byteArrayOf(2, 2, 2)).forEachIndexed { i, (id, bytes) ->
                val jpg = File(ctx.filesDir, "rokid_archive/$id.jpg").apply { parentFile!!.mkdirs(); writeBytes(bytes) }.absolutePath
                PipelineRecords.appendFrame(ctx, FrameEntity(id, 10L + i, jpg, "rokid_glasses", "frame"))
            }
            MemoryGraph.get(ctx).db.forgetDao().insertTombstone(ForgetTombstoneEntity("f2", 1))
        }
        fact("rokid_mem_s_0", "Apples in a bowl", frameId = "f1")
        enqueue(QueuePayloads.reprocess("low", listOf("rokid_mem_s_0"), frameIds = listOf("f1", "f2")))
        drain()
        assertEquals(listOf(Base64.encodeToString(byteArrayOf(1, 1, 1), Base64.NO_WRAP)), images)
    }

    @Test fun tombstonedFramesAreNeverSummarized() {
        runBlocking {
            val jpg = File(ctx.filesDir, "rokid_archive/f9.jpg").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(9)) }.absolutePath
            PipelineRecords.appendFrame(ctx, FrameEntity("f9", 10, jpg, "rokid_glasses", "frame"))
            MemoryGraph.get(ctx).db.forgetDao().insertTombstone(ForgetTombstoneEntity("f9", 1))
            assertTrue(PipelineRecords.recentFrames(ctx, 10).isEmpty())
            assertTrue(PipelineRecords.allFrames(ctx).isEmpty())
            assertTrue(PipelineRecords.framesByIds(ctx, listOf("f9")).isEmpty())
        }
    }

    @Test fun reprocessPayloadsCarryNoText() {
        val p = QueuePayloads.reprocess("low", listOf("a", "b"), listOf("f1"))
        val o = QueuePayloads.parse(p)
        assertEquals(listOf("a", "b"), QueuePayloads.ids(o, "source_ids_csv"))
        assertEquals(listOf("f1"), QueuePayloads.ids(o, "frame_ids_csv"))
        assertFalse(o.containsKey("source_context"))
        assertEquals(listOf("m1"), QueuePayloads.ids(QueuePayloads.parse(QueuePayloads.criticReview(listOf("m1"))), "memory_ids_csv"))
    }

    private fun frames(vararg ids: String) = runBlocking {
        ids.forEachIndexed { i, id ->
            val jpg = File(ctx.filesDir, "rokid_archive/$id.jpg").apply { parentFile!!.mkdirs(); writeBytes(id.toByteArray()) }.absolutePath
            PipelineRecords.appendFrame(ctx, FrameEntity(id, 10L + i, jpg, "rokid_glasses", "frame"))
        }
    }
    private fun b64(id: String) = Base64.encodeToString(id.toByteArray(), Base64.NO_WRAP)
    private fun memories() = runBlocking { MemoryGraph.get(ctx).memories.all() }

    /**
     * Final review I1: each fact is stored on the frame whose photo the model saw for it (one VISION_EXTRACT call per
     * photo), so Forget moment on that photo removes it and Forget fact locks that photo.
     */
    @Test fun eachFactIsStoredOnTheFrameWhosePhotoProducedIt() {
        frames("p1", "p2", "p3", "p4")
        factsFor = { imgs ->
            val id = listOf("p1", "p2", "p3", "p4").single { b64(it) in imgs }
            """{"facts":[{"text":"Seen in $id","type":"object","confidence":0.9,"tags":[]}]}"""
        }
        QueueRuntimeStore.enqueue(ctx, QueuePriority.SUMMARIZER, QueuePayloads.batchedWrite("test", 4, manual = true, frameIdsCsv = "p1,p2,p3,p4"), personaSnapshot = "p")
        drain()
        val byText = memories().associate { it.text to it.frameId }
        assertTrue(byText.isNotEmpty())
        byText.forEach { (text, frameId) -> assertEquals("Seen in $frameId", text) }
        // each prompt lists only the frame whose photo it carries
        prompts.filter { "BEGIN_DATA frames" in it }.forEach { p -> assertEquals(1, Regex("id=p[0-9]").findAll(p).count()) }
        // Forget moment of the photo the first fact came from removes that fact
        assertTrue(runBlocking { com.rokid.context.orchestration.forget.ForgetActions.forgetMoment(ctx, "p1") })
        assertTrue(memories().none { it.text == "Seen in p1" })
    }

    /** Final review I1: a re-summarise over frames is fenced on them; a frame forgotten during the call writes nothing. */
    @Test fun reprocessIsFencedOnItsFrames() {
        frames("r1")
        fact("rokid_mem_s_0", "Apples in a bowl", frameId = "r1")
        enqueue(QueuePayloads.reprocess("low", listOf("rokid_mem_s_0"), frameIds = listOf("r1")))
        summarizeHook = { runBlocking { MemoryGraph.get(ctx).db.forgetDao().insertTombstone(ForgetTombstoneEntity("r1", 1)) } }
        drain()
        assertTrue(memories().none { it.text == "Fruit bowl on the table" })
    }

    /** Fix round 1 I6, final review I1: a photo forgotten during the calls drops its own facts; other photos' facts stay. */
    @Test fun aPhotoForgottenDuringTheCallsDropsOnlyItsFacts() {
        frames("b1", "b2")
        factsFor = { imgs ->
            val id = listOf("b1", "b2").single { b64(it) in imgs }
            """{"facts":[{"text":"Seen in $id","type":"object","confidence":0.9,"tags":[]}]}"""
        }
        QueueRuntimeStore.enqueue(ctx, QueuePriority.SUMMARIZER, QueuePayloads.batchedWrite("test", 2, manual = true, frameIdsCsv = "b1,b2"), personaSnapshot = "p")
        extract = { runBlocking { MemoryGraph.get(ctx).db.forgetDao().insertTombstone(ForgetTombstoneEntity("b1", 1)) } }
        drain()
        assertEquals(mapOf("Seen in b2" to "b2"), memories().associate { it.text to it.frameId })
    }
}
