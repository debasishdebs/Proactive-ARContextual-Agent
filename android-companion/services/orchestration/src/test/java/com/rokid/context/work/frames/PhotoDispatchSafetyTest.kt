package com.rokid.context.work.frames

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.testing.WorkManagerTestInitHelper
import com.rokid.context.ai.AiGraph
import com.rokid.context.ai.InMemoryKeyVault
import com.rokid.context.ai.InMemorySeedState
import com.rokid.context.ai.ProviderBuilder
import com.rokid.context.ai.ProviderHttpException
import com.rokid.context.data.runtime.AppConfigStore
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.FrameEntity
import com.rokid.context.memory.db.FrameMetaEntity
import com.rokid.context.memory.db.FrameStateEntity
import com.rokid.context.memory.forget.ForgetService
import com.rokid.context.model.CallClass
import com.rokid.context.model.CallGate
import com.rokid.context.model.ModelProvider
import com.rokid.context.model.ModelReply
import com.rokid.context.model.ModelRequest
import com.rokid.context.orchestration.TestAi
import com.rokid.context.work.frames.recall.DescribeResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.util.Collections

/**
 * Codex r9 P1-1 and P1-2, end to end through describe on demand (the pipeline claim and KeyframeExtract) with a fake provider and real photo files:
 * a Forget that completes while the call waits for its permit uploads nothing; an HTTP 400 on the photo call is never retried as text and writes nothing.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PhotoDispatchSafetyTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val db get() = MemoryGraph.get(ctx).db
    private val requests = Collections.synchronizedList(mutableListOf<ModelRequest>())
    @Volatile private var beforePermit: (suspend () -> Unit)? = null
    @Volatile private var refuseImages = false
    private val now = System.currentTimeMillis()

    private val gate = object : CallGate {
        override suspend fun <T> withPermit(callClass: CallClass, block: suspend () -> T): T {
            beforePermit?.let { hook -> beforePermit = null; hook() } // the Forget completes while this call waits for its permit
            return block()
        }
    }

    @Before fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(ctx)
        MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx))
        KeyframeGraph.resetForTests()
        val ai = AiGraph(MemoryGraph.get(ctx), InMemoryKeyVault(), InMemorySeedState(), ProviderBuilder { _, _, _ ->
            object : ModelProvider {
                override suspend fun generate(prompt: String): String = error("unused")
                override suspend fun complete(request: ModelRequest): ModelReply {
                    requests += request
                    if (refuseImages && request.images.isNotEmpty()) throw ProviderHttpException(400, "Azure OpenAI", "{\"error\":\"content\"}")
                    val ids = Regex("frame_id: (\\S+) ").findAll(request.prompt).map { it.groupValues[1] }.toList().distinct()
                    val facts = ids.joinToString(",") { """{"text":"A mug $it","type":"object","confidence":0.9,"tags":[],"source_frame_ids":["$it"]}""" }
                    val caps = ids.joinToString(",") { """{"frame_id":"$it","caption":"A photo of $it"}""" }
                    return ModelReply("""{"facts":[$facts],"captions":[$caps]}""")
                }
            }
        }, gate = gate).also { AiGraph.resetForTests(it) }
        TestAi.bindAll(ai)
        AppConfigStore.setPipelineEnabled(ctx, true)
    }

    @After fun tearDown() { KeyframeGraph.resetForTests(); AiGraph.resetForTests(null); db.close(); MemoryGraph.resetForTests(null) }

    private suspend fun seed(id: String, state: String) {
        val f = File(ctx.cacheDir, "$id.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        db.frameDao().insertIgnore(FrameEntity(id, now - 10_000, f.absolutePath, "rokid_glasses"))
        db.frameMetaDao().upsert(FrameMetaEntity(id, null, null, "STILL", false, null, null, null, null, 0.7f, null, null, null, null, now - 10_000, routedAt = now - 10_000))
        db.frameStateDao().insertIgnore(listOf(FrameStateEntity(id, state, 0, null, null, null, now - 5_000)))
    }

    private val describe get() = KeyframeGraph.get(ctx).describe
    private suspend fun memoryIds() = db.memoryDao().all().map { it.id }

    @Test fun forgetCompletingDuringThePermitWaitUploadsNothing() = runBlocking {
        val forget = ForgetService(db, MemoryGraph.get(ctx).proposals)
        for ((id, state) in listOf("p1" to "PENDING", "e1" to "EXTRACTED")) { // the pipeline claim path and the no-claim path
            seed(id, state)
            requests.clear()
            beforePermit = { forget.forgetMoment(id) }
            describe.describePhotos(listOf(id))
            assertTrue("no request carried the photo ($id)", requests.none { it.images.isNotEmpty() })
            assertTrue("no request at all reached the provider ($id)", requests.isEmpty())
            assertTrue(memoryIds().isEmpty()); assertTrue(db.frameSourceDao().memoriesForFrame(id).isEmpty())
            assertEquals(listOf(id), db.forgetDao().tombstoned(listOf(id)))
        }
    }

    @Test fun aLiveBatchIsStillSentWithItsPhotos() = runBlocking {
        seed("l1", "PENDING")
        assertEquals(DescribeResult.Saved(2, 1), describe.describePhotos(listOf("l1")))
        assertTrue(requests.isNotEmpty() && requests.all { it.images.isNotEmpty() })
    }

    @Test fun http400OnThePhotoCallIsNeverRetriedAsTextAndWritesNothing() = runBlocking {
        refuseImages = true
        seed("r1", "PENDING")
        assertEquals(DescribeResult.Unavailable, describe.describePhotos(listOf("r1")))
        assertTrue("some photo call was made", requests.isNotEmpty())
        assertTrue("never a text-only retry", requests.all { it.images.isNotEmpty() && it.requireImages })
        assertTrue("zero memories", memoryIds().isEmpty())
        assertTrue("not described: no citing memory, so retention can never treat it as described", db.frameSourceDao().memoriesForFrame("r1").isEmpty())
        val st = db.frameStateDao().get("r1")!!
        assertNotEquals("EXTRACTED", st.state)
        assertTrue("still queued for a retry with its backoff: ${st.state}", st.state in setOf("PENDING", "FAILED"))
    }
}
