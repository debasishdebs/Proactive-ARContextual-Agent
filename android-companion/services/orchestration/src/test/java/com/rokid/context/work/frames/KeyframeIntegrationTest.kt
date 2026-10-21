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
    @Volatile private var confidence = 0.9
    /** Runs inside the first provider call (a Forget or Delete all landing mid-call). */
    @Volatile private var midCall: (suspend () -> Unit)? = null

    /** Records the CallClass of every model call; [stallFrom] = the n-th call (1-based) and later ones never get a permit (an ask's cap lands there). */
    private class RecordingGate(private val inner: com.rokid.context.model.CallGate) : com.rokid.context.model.CallGate {
        val classes = Collections.synchronizedList(mutableListOf<CallClass>())
        @Volatile var stallFrom = Int.MAX_VALUE
        override suspend fun <T> withPermit(callClass: CallClass, block: suspend () -> T): T {
            classes += callClass
            if (classes.size >= stallFrom) kotlinx.coroutines.awaitCancellation()
            return inner.withPermit(callClass, block)
        }
    }
    private lateinit var recorder: RecordingGate

    @Before fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(ctx)
        MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx))
        KeyframeGraph.resetForTests()
        val budget = CallBudget(System::currentTimeMillis, { 150L })
        AiGraph.gate = budget
        recorder = RecordingGate(budget)
        val ai = AiGraph(MemoryGraph.get(ctx), InMemoryKeyVault(), InMemorySeedState(), ProviderBuilder { _, _, _ ->
            object : ModelProvider {
                override suspend fun generate(prompt: String): String {
                    prompts += prompt; callTimes += System.currentTimeMillis()
                    midCall?.let { hook -> midCall = null; hook() }
                    val ids = Regex("frame_id: (\\S+) ").findAll(prompt).map { it.groupValues[1] }.toList()
                    val facts = ids.joinToString(",") { """{"text":"A mug $it","type":"object","confidence":$confidence,"tags":[],"source_frame_ids":["$it"]}""" }
                    val caps = ids.joinToString(",") { """{"frame_id":"$it","caption":"A photo"}""" }
                    return """{"facts":[$facts],"captions":[$caps]}"""
                }
                override suspend fun generateWithImages(prompt: String, images: List<ModelImageInput>) = generate(prompt)
            }
        }, gate = recorder).also { AiGraph.resetForTests(it) }
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
        db.frameMetaDao().upsert(FrameMetaEntity(id, null, null, "STILL", false, null, null, null, null, null, null, null, null, null, updatedAt, routedAt = updatedAt))
        db.frameStateDao().insertIgnore(listOf(FrameStateEntity(id, state, 0, null, claimedAt, claimedAt?.let { "pipeline" }, updatedAt)))
    }

    private fun legacyJob(vararg ids: String, manual: Boolean = false) {
        QueueRuntimeStore.enqueue(ctx, QueuePriority.SUMMARIZER, QueuePayloads.batchedWrite("test", ids.size, manual = manual, frameIdsCsv = ids.joinToString(",")), personaSnapshot = "p")
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
        // the real budget spaces the calls: burst 2, then one token per 150 ms
        val t = callTimes.sorted()
        assertTrue("third call waited for a token (${t[2] - t[0]} ms)", t[2] - t[0] >= 100)
        withTimeout(5_000) { while ((handle.stats.value.counts["EXTRACTED"] ?: 0) != 7) delay(25) }
        assertEquals(0, handle.stats.value.backlog)
    }

    private suspend fun waitExtracted(vararg ids: String) =
        withTimeout(15_000) { while (ids.any { db.frameStateDao().get(it)?.state != "EXTRACTED" }) delay(25) }

    private fun nudgeEnqueued() = androidx.work.WorkManager.getInstance(ctx).getWorkInfosForUniqueWork(com.rokid.context.orchestration.nudge.NudgeTrigger.WORK).get().isNotEmpty()

    /** Review C1: the pipeline owns the frame (no dwell: Fixed mode or old glasses), yet the nudge backstop still gets scheduled. */
    @Test fun c1PipelineSavesStillScheduleTheNudgeBackstop() = runBlocking {
        seed("n1", "PENDING", System.currentTimeMillis()) // frame_meta.dwell = false
        assertTrue(!nudgeEnqueued())
        KeyframeGraph.get(ctx).signal()
        waitExtracted("n1")
        withTimeout(10_000) { while (!nudgeEnqueued()) delay(25) }
        assertTrue("NudgeWorker enqueued for a trusted pipeline save", nudgeEnqueued())
    }

    /** Review I6: low confidence -> NEEDS_REVIEW memories and a PRIORITY_REPROCESS job, as the legacy path did. */
    @Test fun i6LowConfidencePipelineSaveQueuesTheReprocess() = runBlocking {
        confidence = 0.3
        seed("r1", "PENDING", System.currentTimeMillis())
        KeyframeGraph.get(ctx).signal()
        waitExtracted("r1")
        // the save comes before the critic now, so the follow-up (the reprocess job) lands a moment later
        withTimeout(10_000) { while ((QueueRuntimeStore.snapshot(ctx).pendingByQueue[QueuePriority.PRIORITY_REPROCESS] ?: 0) == 0) delay(25) }
        val facts = memories()
        assertEquals(1, facts.size)
        assertEquals(com.rokid.context.memory.db.ReviewState.NEEDS_REVIEW, facts.single().reviewState)
        assertEquals(1, QueueRuntimeStore.snapshot(ctx).pendingByQueue[QueuePriority.PRIORITY_REPROCESS])
        assertTrue("no nudge backstop for a review-pending save", !nudgeEnqueued())
    }

    /** Review I2 (pipeline): a photo forgotten mid-call leaves the other two PENDING with no attempt used, then they are extracted. */
    @Test fun i2ForgettingOnePhotoMidCallKeepsTheOthersWithoutAnAttempt() = runBlocking {
        val now = System.currentTimeMillis()
        for (i in 1..3) seed("k$i", "PENDING", now)
        midCall = { db.frameStateDao().deleteFor(listOf("k2")) } // Delete/forget lands while the model is answering
        KeyframeGraph.get(ctx).signal()
        waitExtracted("k1", "k3")
        assertEquals(0, db.frameStateDao().get("k1")!!.attempts); assertEquals(0, db.frameStateDao().get("k3")!!.attempts)
        assertEquals(null, db.frameStateDao().get("k2"))
        assertEquals("the first call was dropped, the rest went in a second call", 2, prompts.size)
    }

    /** Review I2 (legacy job). */
    @Test fun i2LegacyJobReleasesTheBatchOnAStaleSave() = runBlocking {
        val old = System.currentTimeMillis() - 10 * 60_000L
        for (i in 1..3) seed("l$i", "PENDING", old)
        midCall = { db.frameStateDao().deleteFor(listOf("l2")) }
        legacyJob("l1", "l2", "l3")
        for (id in listOf("l1", "l3")) { assertEquals("PENDING", db.frameStateDao().get(id)!!.state); assertEquals(0, db.frameStateDao().get(id)!!.attempts); assertEquals(null, db.frameStateDao().get(id)!!.claimId) }
        assertEquals(0, memories().size)
    }

    /** Review M8: the legacy backstop leaves a frame alone for 2 minutes after the pipeline last touched it (manual runs do not wait). */
    @Test fun legacyBackstopWaitsTwoMinutesButManualRunsDoNot() = runBlocking {
        val now = System.currentTimeMillis()
        seed("w1", "PENDING", now - 30_000)
        legacyJob("w1")
        assertTrue("inside the grace: the pipeline's", prompts.isEmpty()); assertEquals("PENDING", db.frameStateDao().get("w1")!!.state)
        legacyJob("w1", manual = true)
        assertEquals(1, prompts.size); assertEquals("EXTRACTED", db.frameStateDao().get("w1")!!.state)
        seed("w2", "PENDING", now - 3 * 60_000)
        legacyJob("w2")
        assertEquals("past the grace", 2, prompts.size); assertEquals("EXTRACTED", db.frameStateDao().get("w2")!!.state)
    }

    /** Final review minor 5: the backstop leaves a FAILED frame inside its own backoff alone (manual runs do not wait). */
    @Test fun backstopRespectsAFailedFramesBackoff() = runBlocking {
        val now = System.currentTimeMillis()
        seed("b1", "FAILED", now - 3 * 60_000); seed("b2", "FAILED", now - 5 * 60_000)
        db.openHelper.writableDatabase.execSQL("UPDATE frame_state SET attempts = 4") // 4th failure: backoff 4 min, past the 2 min grace for both
        legacyJob("b1", "b2")
        assertEquals("b1 still waits", "FAILED", db.frameStateDao().get("b1")!!.state)
        assertEquals("b2 is past its backoff", "EXTRACTED", db.frameStateDao().get("b2")!!.state)
        legacyJob("b1", manual = true)
        assertEquals("EXTRACTED", db.frameStateDao().get("b1")!!.state)
    }

    /** Review M3: FRAME_INGEST makes no AI call, so heat and a low battery never defer it. */
    @Test fun m3FrameIngestIsNotDeferredByHeatOrBattery() = runBlocking {
        KeyframeGraph.thermal = com.rokid.context.work.budget.ThermalStatusProvider { com.rokid.context.work.budget.PhoneThermal.SEVERE }
        shadowOf(ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager).setIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY, 10)
        try {
            QueueRuntimeStore.enqueue(ctx, QueuePriority.FRAME_INGEST, QueuePayloads.frameIngest(source = "test"), personaSnapshot = "p")
            TestListenableWorkerBuilder<QueueDrainWorker>(ctx).build().doWork()
            val traces = com.rokid.context.orchestration.PipelineRecords.recentTraces(ctx, 20)
            assertTrue("ingest ran: ${traces.map { it.state }}", traces.any { it.state == "FRAME_INGEST_DONE" })
            assertTrue(traces.none { it.state == "DEFERRED" })
        } finally { KeyframeGraph.thermal = com.rokid.context.work.budget.ThermalStatusProvider { com.rokid.context.work.budget.PhoneThermal.NONE } }
    }

    /** Review M5: the fast-path evaluation pauses under Battery Saver and at thermal SEVERE. */
    @Test fun m5LiveNudgesPauseUnderBatterySaverAndSevereHeat() = runBlocking {
        val handle = KeyframeGraph.get(ctx)
        assertTrue("normal: the engine runs (no HUD here)", handle.evaluateNudge().reason != "Paused")
        shadowOf(ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).setIsPowerSaveMode(true)
        assertEquals("Paused", handle.evaluateNudge().reason)
        shadowOf(ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).setIsPowerSaveMode(false)
        KeyframeGraph.thermal = com.rokid.context.work.budget.ThermalStatusProvider { com.rokid.context.work.budget.PhoneThermal.SEVERE }
        try { assertEquals("Paused", handle.evaluateNudge().reason) }
        finally { KeyframeGraph.thermal = com.rokid.context.work.budget.ThermalStatusProvider { com.rokid.context.work.budget.PhoneThermal.NONE } }
    }

    /** Review M6: a queued READ ask that is no longer fresh does not extract the last minute's frames as USER. */
    @Test fun m6StaleQueuedAskDoesNotExtractFirstButAFreshOneDoes() = runBlocking {
        seed("q1", "PENDING", System.currentTimeMillis())
        try { com.rokid.context.orchestration.AnswerService.forApp(ctx, extractFirst = false).answerOrThrow("what is on the shelf", "queue") } catch (_: Exception) {}
        assertEquals("PENDING", db.frameStateDao().get("q1")!!.state); assertTrue(prompts.isEmpty())
        try { com.rokid.context.orchestration.AnswerService.forApp(ctx).answerOrThrow("what is on the shelf", "queue") } catch (_: Exception) {}
        assertEquals("EXTRACTED", db.frameStateDao().get("q1")!!.state)
    }

    /** Review M1: start() is idempotent and registers one network callback. */
    @Test fun m1StartTwiceWatchesTheNetworkOnce() {
        val handle = KeyframeGraph.get(ctx)
        handle.start(); handle.start(); handle.signal(); handle.signal()
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        assertEquals(1, shadowOf(cm).networkCallbacks.size)
    }

    /** Review I5: the extraction holds a bounded PARTIAL wake lock and gives it back, whatever the block does. */
    @Test fun i5ExtractionHoldsAWakeLockAndReleasesIt() = runBlocking {
        fun latest() = org.robolectric.shadows.ShadowPowerManager.getLatestWakeLock()
        val lock = ExtractWakeLock(ctx, timeoutMs = 60_000)
        lock.hold { assertTrue(latest().isHeld) }
        assertTrue(!latest().isHeld)
        try { lock.hold { error("boom") } } catch (_: IllegalStateException) {}
        assertTrue("released after a failure", !latest().isHeld)
    }

    /** Review M2: the memory id prefix carries the whole claim id. */
    @Test fun m2FactPrefixUsesTheFullClaimId() {
        val a = "0123456789ab-aaaa-4aaa-8aaa-aaaaaaaaaaaa"; val b = "0123456789ab-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        assertTrue(KeyframeHandle.factPrefix(a) != KeyframeHandle.factPrefix(b))
        assertTrue(KeyframeHandle.factPrefix(a).contains(a))
        assertTrue("survives purgeNonRokidMemories, is no rokid_review_ id", KeyframeHandle.factPrefix(a).startsWith("rokid_") && !KeyframeHandle.factPrefix(a).startsWith("rokid_review_"))
    }

    /** Final review I2: an ask's extraction runs its model critic as USER too, so it never queues behind the EXTRACT backlog. */
    @Test fun i2AsksCriticRunsAsUser() = runBlocking {
        confidence = 0.3
        seed("a1", "PENDING", System.currentTimeMillis())
        assertEquals(1, KeyframeGraph.get(ctx).pipeline.extractForAsk())
        assertTrue("vision and critic both ran", recorder.classes.size >= 2)
        assertEquals("no call fell back to EXTRACT", setOf(CallClass.USER), recorder.classes.toSet())
    }

    /** Final review I2: when the cap lands while the critic waits for its permit, the paid extraction is already saved. */
    @Test fun i2ExtractionIsSavedBeforeTheCriticSoACapCannotLoseIt() = runBlocking {
        confidence = 0.3
        recorder.stallFrom = 2 // the critic never gets a permit
        seed("a2", "PENDING", System.currentTimeMillis())
        assertEquals(null, kotlinx.coroutines.withTimeoutOrNull(1_500) { KeyframeGraph.get(ctx).pipeline.extractForAsk() })
        assertEquals("EXTRACTED", db.frameStateDao().get("a2")!!.state)
        val m = memories().single()
        assertEquals(com.rokid.context.memory.db.ReviewState.NEEDS_REVIEW, m.reviewState)
        assertTrue(m.id.startsWith("rokid_kf_"))
        assertEquals("the review follow-up still ran", 1, QueueRuntimeStore.snapshot(ctx).pendingByQueue[QueuePriority.PRIORITY_REPROCESS])
    }
}
