package com.rokid.context.work.frames.recall

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.testing.WorkManagerTestInitHelper
import com.rokid.context.ai.AiGraph
import com.rokid.context.ai.InMemoryKeyVault
import com.rokid.context.ai.InMemorySeedState
import com.rokid.context.ai.ProviderBuilder
import com.rokid.context.data.runtime.AppConfigStore
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.FrameClusterEntity
import com.rokid.context.memory.db.FrameEntity
import com.rokid.context.memory.db.FrameMetaEntity
import com.rokid.context.memory.db.FrameStateEntity
import com.rokid.context.memory.db.FrameVectorEntity
import com.rokid.context.memory.retrieval.VectorCodec
import com.rokid.context.model.CallClass
import com.rokid.context.model.CallGate
import com.rokid.context.model.ModelImageInput
import com.rokid.context.model.ModelProvider
import com.rokid.context.orchestration.MemoryWipe
import com.rokid.context.orchestration.TestAi
import com.rokid.context.work.budget.CallBudget
import com.rokid.context.work.frames.ExtractNowOutcome
import com.rokid.context.work.frames.KeyframeGraph
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.util.Collections

/** Wave 3 Task 2: describe on demand, through the real pipeline claim and the no-claim path, with a fake provider and real (tiny) photo files. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class DescribeOnDemandTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val db get() = MemoryGraph.get(ctx).db
    private val prompts = Collections.synchronizedList(mutableListOf<String>())
    private val classes = Collections.synchronizedList(mutableListOf<CallClass>())
    @Volatile private var midCall: (suspend () -> Unit)? = null
    private val now = System.currentTimeMillis()

    private val gate = object : CallGate {
        override suspend fun <T> withPermit(callClass: CallClass, block: suspend () -> T): T { classes += callClass; return block() }
    }

    @Before fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(ctx)
        MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx))
        KeyframeGraph.resetForTests()
        val ai = AiGraph(MemoryGraph.get(ctx), InMemoryKeyVault(), InMemorySeedState(), ProviderBuilder { _, _, _ ->
            object : ModelProvider {
                override suspend fun generate(prompt: String): String {
                    prompts += prompt
                    midCall?.let { hook -> midCall = null; hook() }
                    val ids = Regex("frame_id: (\\S+) ").findAll(prompt).map { it.groupValues[1] }.toList()
                    val facts = ids.joinToString(",") { """{"text":"A mug $it","type":"object","confidence":0.9,"tags":[],"source_frame_ids":["$it"]}""" }
                    val caps = ids.joinToString(",") { """{"frame_id":"$it","caption":"A photo of $it"}""" }
                    return """{"facts":[$facts],"captions":[$caps]}"""
                }
                override suspend fun generateWithImages(prompt: String, images: List<ModelImageInput>) = generate(prompt)
            }
        }, gate = gate).also { AiGraph.resetForTests(it) }
        TestAi.bindAll(ai)
        AppConfigStore.setPipelineEnabled(ctx, true)
    }

    @After fun tearDown() { KeyframeGraph.resetForTests(); AiGraph.resetForTests(null); db.close(); MemoryGraph.resetForTests(null) }

    private suspend fun seed(
        id: String, state: String, salience: Float? = 0.7f, cluster: String? = null, ts: Long = now - 10_000, file: Boolean = true,
        lastError: String? = null, claimedAt: Long? = null, createdAt: Long = ts,
    ) {
        if (file) {
            val f = File(ctx.cacheDir, "$id.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            db.frameDao().insertIgnore(FrameEntity(id, ts, f.absolutePath, "rokid_glasses"))
        }
        db.frameMetaDao().upsert(FrameMetaEntity(id, null, null, "STILL", false, null, null, null, null, salience, null, cluster, null, null, createdAt, routedAt = createdAt))
        db.frameStateDao().insertIgnore(listOf(FrameStateEntity(id, state, 0, lastError, claimedAt, claimedAt?.let { "other" }, now - 5_000)))
    }

    private suspend fun cluster(id: String, state: String, reps: String = "[]") =
        // a live SUMMARIZING claim (taken after this process started: the pipeline's start-up release leaves it alone)
        db.frameClusterDao().upsert(FrameClusterEntity(id, now - 100_000, now - 90_000, "gap", reps, null, state = state, updatedAt = now - 90_000,
            claimId = if (state == "SUMMARIZING") "other" else null, claimedAt = if (state == "SUMMARIZING") now else null))

    private val describe get() = KeyframeGraph.get(ctx).describe
    private suspend fun memoryIds() = db.memoryDao().all().map { it.id }
    private suspend fun stateOf(id: String) = db.frameStateDao().get(id)!!.state
    private fun photoIdsSent() = prompts.flatMap { p -> Regex("frame_id: (\\S+) ").findAll(p).map { it.groupValues[1] }.toList() }
    private suspend fun skippedDue() = db.forgetDao().skippedPhotosDue(0, 0, Long.MAX_VALUE / 2, 0, 50)

    private fun custom(extractNow: suspend (List<String>) -> Map<String, ExtractNowOutcome>, spaceId: String? = null) =
        DescribeOnDemand(ctx, KeyframeGraph.get(ctx).pipeline, db, { now }, extractNow = extractNow, activeSpaceId = { spaceId })

    @Test fun pendingFrameGoesThroughPipelineClaim() = runBlocking {
        seed("d1", "PENDING")
        val r = describe.describePhotos(listOf("d1"))
        assertEquals(DescribeResult.Saved(memories = 2, frames = 1), r) // one fact and one caption
        assertEquals("EXTRACTED", stateOf("d1"))
        assertTrue(memoryIds().all { it.startsWith("rokid_kf_") }); assertTrue(memoryIds().none { it.startsWith("rokid_desc_") })
    }

    @Test fun extractedFrameDescribedAgainWithDescIds() = runBlocking {
        seed("d1", "EXTRACTED")
        val r = describe.describePhotos(listOf("d1"))
        assertEquals(DescribeResult.Saved(2, 1), r)
        val re = Regex("rokid_desc_[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}_(c)?[0-9]+")
        assertEquals(2, memoryIds().size); assertTrue(memoryIds().all { re.matches(it) })
        assertEquals("EXTRACTED", stateOf("d1"))
    }

    @Test fun nearDupDescribedMovesToExtracted() = runBlocking {
        seed("nd", "NEAR_DUP", salience = 0.3f, lastError = "near_dup"); seed("other", "NEAR_DUP", salience = 0.3f, lastError = "near_dup")
        assertEquals(listOf("nd", "other"), skippedDue().sorted()) // a real skipped photo by the wave-2 query (scored low)
        assertTrue(describe.describePhotos(listOf("nd")) is DescribeResult.Saved)
        assertEquals("EXTRACTED", stateOf("nd")); assertEquals("described_on_demand", db.frameStateDao().get("nd")!!.lastError)
        assertEquals(listOf("other"), skippedDue())
        assertEquals("NEAR_DUP", stateOf("other"))
    }

    @Test fun failedAndFailedFinalAndClusteredMoveToExtracted() = runBlocking {
        seed("a", "FAILED", lastError = "x"); seed("b", "FAILED_FINAL", lastError = "x"); seed("c", "CLUSTERED", salience = 0.3f)
        assertTrue(describe.describePhotos(listOf("a", "b", "c")) is DescribeResult.Saved)
        listOf("a", "b", "c").forEach { assertEquals(it, "EXTRACTED", stateOf(it)) }
    }

    @Test fun summarizingClusterMemberUsesDirectPath() = runBlocking {
        cluster("c1", "SUMMARIZING"); seed("m1", "IN_CLUSTER", salience = 0.3f, cluster = "c1")
        val r = describe.describePhotos(listOf("m1"))
        assertEquals(DescribeResult.Saved(2, 1), r)
        assertTrue(memoryIds().all { it.startsWith("rokid_desc_") })
        assertEquals("the summarizing cluster owns the state; settlement moves it", "IN_CLUSTER", stateOf("m1"))
    }

    @Test fun describedSummarizingMemberSurvivesSettlementAndRetention() = runBlocking {
        cluster("c1", "SUMMARIZING"); seed("m1", "IN_CLUSTER", salience = 0.3f, cluster = "c1"); seed("m2", "IN_CLUSTER", salience = 0.3f, cluster = "c1")
        assertTrue(describe.describePhotos(listOf("m1")) is DescribeResult.Saved)
        // the cluster call finishes: settlement moves its members to CLUSTERED, the cluster is SUMMARIZED
        db.frameStateDao().settleCluster("c1", "CLUSTERED", null, now)
        cluster("c1", "SUMMARIZED")
        assertEquals("CLUSTERED", stateOf("m1"))
        assertEquals("only the undescribed member is a skipped photo", listOf("m2"), skippedDue())
        assertTrue("m1 follows the photo window", "m1" in db.forgetDao().linkedPhotosDue(0, 0, Long.MAX_VALUE / 2, 0, 0, 0, 50))
    }

    @Test fun extractingFrameIsBusy() = runBlocking {
        seed("e1", "EXTRACTING", claimedAt = now - 1_000)
        assertEquals(DescribeResult.Busy, describe.describePhotos(listOf("e1")))
        assertTrue(prompts.isEmpty()); assertEquals("EXTRACTING", stateOf("e1"))
    }

    @Test fun expiredFrameIsExpired() = runBlocking {
        seed("x1", "EXTRACTED", file = false)
        assertEquals(DescribeResult.Expired, describe.describePhotos(listOf("x1")))
        assertTrue(prompts.isEmpty())
    }

    @Test fun wipeDuringCallSavesNothing() = runBlocking {
        seed("w1", "PENDING"); seed("w2", "EXTRACTED")
        midCall = { MemoryWipe.generation.incrementAndGet() }
        assertEquals(DescribeResult.Wiped, describe.describePhotos(listOf("w1")))
        assertTrue(memoryIds().isEmpty()); assertEquals("the claim was given back", "PENDING", stateOf("w1"))
        midCall = { MemoryWipe.generation.incrementAndGet() }
        assertEquals(DescribeResult.Wiped, describe.describePhotos(listOf("w2")))
        assertTrue(memoryIds().isEmpty()); assertEquals("EXTRACTED", stateOf("w2"))
    }

    @Test fun usesUserCallClass() = runBlocking {
        seed("u1", "PENDING"); seed("u2", "EXTRACTED")
        describe.describePhotos(listOf("u1")); describe.describePhotos(listOf("u2"))
        assertTrue(classes.isNotEmpty()); assertEquals(setOf(CallClass.USER), classes.toSet())
    }

    @Test fun atMostThreePhotos() = runBlocking {
        for (i in 1..5) seed("p$i", "EXTRACTED")
        val r = describe.describePhotos((1..5).map { "p$it" })
        assertEquals(DescribeResult.Saved(6, 3), r)
        assertEquals(setOf("p1", "p2", "p3"), photoIdsSent().toSet())
    }

    @Test fun idsNeverReviewPrefix() = runBlocking {
        seed("i1", "PENDING"); seed("i2", "EXTRACTED")
        describe.describePhotos(listOf("i1")); describe.describePhotos(listOf("i2"))
        assertEquals(4, memoryIds().size)
        assertTrue(memoryIds().all { it.startsWith("rokid_") && !it.startsWith("rokid_review_") })
    }

    @Test fun oneAtATime() = runBlocking {
        seed("o1", "EXTRACTED"); seed("o2", "EXTRACTED")
        val release = CompletableDeferred<Unit>()
        midCall = { release.await() }
        val first = async { describe.describePhotos(listOf("o1")) }
        withTimeout(10_000) { while (prompts.isEmpty()) delay(10) }
        val second = async { describe.describePhotos(listOf("o2")) }
        delay(400)
        assertEquals("the second call waits for the first", 1, prompts.size)
        release.complete(Unit)
        assertTrue(first.await() is DescribeResult.Saved); assertTrue(second.await() is DescribeResult.Saved)
        assertEquals(2, prompts.size)
    }

    // --- scenes ---------------------------------------------------------------------------------------------------------

    @Test fun sceneUsesStoredReps() = runBlocking {
        cluster("c1", "SUMMARIZED", reps = """["r1","r2"]""")
        seed("r1", "CLUSTERED", cluster = "c1"); seed("r2", "CLUSTERED", cluster = "c1"); seed("x3", "CLUSTERED", cluster = "c1")
        assertTrue(describe.describeScene("c1") is DescribeResult.Saved)
        assertEquals(setOf("r1", "r2"), photoIdsSent().toSet())
    }

    @Test fun sceneWithoutRepsUsesSceneRulesMedoid() = runBlocking {
        cluster("c1", "SUMMARIZED")
        val vecs = mapOf("A" to floatArrayOf(1f, 0f), "B" to floatArrayOf(0.8f, 0.6f), "C" to floatArrayOf(0.5f, 0.866f), "D" to floatArrayOf(0f, 1f))
        var t = now - 100_000
        for ((id, v) in vecs) { seed(id, "CLUSTERED", cluster = "c1", ts = t, createdAt = t); db.frameVectorDao().upsert(listOf(FrameVectorEntity(id, "sp", VectorCodec.encode(v)))); t += 1_000 }
        val r = custom({ pipelineless() }, spaceId = "sp").describeScene("c1")
        assertTrue(r is DescribeResult.Saved)
        // medoid B (max mean cosine), then the most different (D), then the first of the rest (A); C is left out
        assertEquals(setOf("B", "D", "A"), photoIdsSent().toSet())
    }

    @Test fun sceneWithoutVectorsTakesTheNewestMembers() = runBlocking {
        cluster("c1", "SUMMARIZED")
        for (i in 1..5) seed("n$i", "CLUSTERED", cluster = "c1", ts = now - 100_000 + i * 1_000L, createdAt = now - 100_000 + (6 - i) * 1_000L)
        assertTrue(describe.describeScene("c1") is DescribeResult.Saved)
        assertEquals("newest by frame.ts, not by receipt", setOf("n5", "n4", "n3"), photoIdsSent().toSet())
    }

    @Test fun unknownSceneIsExpired() = runBlocking {
        assertEquals(DescribeResult.Expired, describe.describeScene("nope"))
    }

    // --- extractNow outcomes (S5) -----------------------------------------------------------------------------------------

    private fun pipelineless(): Map<String, ExtractNowOutcome> = error("no claimable frame expected in this test")

    @Test fun lostClaimRereadsState() = runBlocking {
        seed("l1", "PENDING"); seed("l2", "PENDING"); seed("l3", "PENDING")
        val d = custom({ ids ->
            // between our classification and our claim: someone else claims l1, a night job parks l2 as NEAR_DUP; l3 is simply not claimed
            db.frameStateDao().move(listOf("l1"), "PENDING", "EXTRACTING", null, now)
            db.frameStateDao().move(listOf("l2"), "PENDING", "NEAR_DUP", null, now)
            ids.associateWith { ExtractNowOutcome.LostClaim }
        })
        val r = d.describePhotos(listOf("l1", "l2", "l3"))
        assertEquals(DescribeResult.Saved(2, 1), r)
        assertEquals("only l2 (nobody's) is described; l1 is held and l3 is the pipeline's", setOf("l2"), photoIdsSent().toSet())
        assertEquals("EXTRACTING", stateOf("l1")); assertEquals("PENDING", stateOf("l3"))
    }

    @Test fun staleClaimAfterTheCallIsNeverASecondCall() = runBlocking {
        seed("s1", "PENDING")
        val d = custom({ ids -> ids.associateWith { ExtractNowOutcome.Stale } })
        assertEquals(DescribeResult.Busy, d.describePhotos(listOf("s1")))
        assertTrue("no second paid call", prompts.isEmpty()); assertEquals("PENDING", stateOf("s1")); assertTrue(memoryIds().isEmpty())
    }

    @Test fun forgetOfAnotherPhotoInTheSameBatchCostsOneCallAndSavesNothing() = runBlocking {
        seed("a", "PENDING"); seed("b", "PENDING")
        midCall = { db.frameStateDao().deleteFor(listOf("b")) } // Forget of b lands while the model answers: its claim CAS fails and rolls the batch back
        assertEquals(DescribeResult.Busy, describe.describePhotos(listOf("a", "b")))
        assertEquals("exactly one paid call, never rerouted to the no-claim path", 1, prompts.size)
        assertTrue(memoryIds().isEmpty()); assertEquals("PENDING", stateOf("a")) // given back with no attempt; the pipeline owns it again
    }

    @Test fun forgetDuringTheCallSavesNothingOnEitherPath() = runBlocking {
        val forget = com.rokid.context.memory.forget.ForgetService(db, MemoryGraph.get(ctx).proposals)
        seed("d1", "EXTRACTED"); db.frameVectorDao().upsert(listOf(FrameVectorEntity("d1", "sp", VectorCodec.encode(floatArrayOf(1f, 0f)))))
        midCall = { forget.forgetMoment("d1") }
        describe.describePhotos(listOf("d1"))
        assertTrue("no memory", memoryIds().isEmpty()); assertTrue("no provenance", db.frameSourceDao().memoriesForFrame("d1").isEmpty())
        assertTrue("no vector", db.frameVectorDao().forFrames("sp", listOf("d1")).isEmpty()); assertNull(db.frameStateDao().get("d1"))
        assertEquals(listOf("d1"), db.forgetDao().tombstoned(listOf("d1")))
        seed("d2", "PENDING")
        midCall = { forget.forgetMoment("d2") }
        describe.describePhotos(listOf("d2"))
        assertTrue(memoryIds().isEmpty()); assertTrue(db.frameSourceDao().memoriesForFrame("d2").isEmpty())
    }

    @Test fun describeNeverWakesTheNudgePaths() = runBlocking {
        fun nudgeEnqueued() = androidx.work.WorkManager.getInstance(ctx).getWorkInfosForUniqueWork(com.rokid.context.orchestration.nudge.NudgeTrigger.WORK).get().isNotEmpty()
        seed("n1", "PENDING"); seed("n2", "EXTRACTED")
        assertTrue(describe.describePhotos(listOf("n1")) is DescribeResult.Saved); assertTrue(describe.describePhotos(listOf("n2")) is DescribeResult.Saved)
        assertFalse("no nudge backstop for a describe (either path)", nudgeEnqueued())
    }

    @Test fun failedExtractionNotRetriedDirect() = runBlocking {
        seed("f1", "PENDING")
        val d = custom({ ids -> ids.associateWith { ExtractNowOutcome.Failed("offline") } })
        assertEquals(DescribeResult.Unavailable, d.describePhotos(listOf("f1")))
        assertTrue("no second, uncounted model call", prompts.isEmpty())
    }

    @Test fun wipedDistinctFromStaleClaim() = runBlocking {
        seed("w1", "PENDING")
        assertEquals(DescribeResult.Wiped, custom({ ids -> ids.associateWith { ExtractNowOutcome.Wiped } }).describePhotos(listOf("w1")))
        assertTrue(prompts.isEmpty())
        // a frame this call never claimed that is still the pipeline's (PENDING) is not described behind its back
        assertEquals(DescribeResult.Busy, custom({ ids -> ids.associateWith { ExtractNowOutcome.LostClaim } }).describePhotos(listOf("w1")))
        assertTrue(prompts.isEmpty())
    }

    @Test fun unreadablePhotoIsExpired() = runBlocking {
        seed("g1", "PENDING")
        val d = custom({ ids -> ids.associateWith { ExtractNowOutcome.Failed("unreadable") } })
        assertEquals(DescribeResult.Expired, d.describePhotos(listOf("g1")))
    }

    // --- ids (S6) -----------------------------------------------------------------------------------------------------------

    @Test fun collidingShortPrefixDoesNotOverwrite() = runBlocking {
        seed("k1", "EXTRACTED")
        val uuids = ArrayDeque(listOf("aaaaaaaa-0000-4000-8000-000000000001", "aaaaaaaa-1111-4111-8111-111111111111"))
        val d = DescribeOnDemand(ctx, KeyframeGraph.get(ctx).pipeline, db, { now }, idSource = { uuids.removeFirst() }, activeSpaceId = { null })
        assertTrue(d.describePhotos(listOf("k1")) is DescribeResult.Saved); assertTrue(d.describePhotos(listOf("k1")) is DescribeResult.Saved)
        val ids = memoryIds()
        assertEquals("two describes, a fact and a caption each, none overwritten", 4, ids.toSet().size)
        assertTrue(ids.any { it.startsWith("rokid_desc_aaaaaaaa-0000-") } && ids.any { it.startsWith("rokid_desc_aaaaaaaa-1111-") })
    }

    @Test fun describeWritesFactProvenance() = runBlocking {
        seed("s1", "EXTRACTED")
        describe.describePhotos(listOf("s1"))
        val rows = db.frameSourceDao().memoriesForFrame("s1")
        assertEquals(2, rows.size)
    }
}
