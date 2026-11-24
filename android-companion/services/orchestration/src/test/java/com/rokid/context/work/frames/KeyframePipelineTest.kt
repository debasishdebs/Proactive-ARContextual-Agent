package com.rokid.context.work.frames

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.FrameEntity
import com.rokid.context.memory.db.FrameMetaEntity
import com.rokid.context.memory.db.FrameStateEntity
import com.rokid.context.model.CallClass
import com.rokid.context.work.budget.CallBudget
import com.rokid.context.work.budget.PhoneThermal
import com.rokid.context.work.budget.PhonePowerPolicy
import com.rokid.context.work.budget.ThermalStatusProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import com.rokid.context.work.frames.scene.SceneIndexer
import com.rokid.context.work.frames.scene.WatchListStore
import com.rokid.context.work.frames.scene.WatchVectors
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class KeyframePipelineTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val db get() = MemoryGraph.get(ctx).db
    private var now = 1_793_000_000_000L
    private val calls = mutableListOf<Pair<List<String>, CallClass>>()
    private class Crash : Error()

    @Before fun setUp() { MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx)) }
    @After fun tearDown() { db.close(); MemoryGraph.resetForTests(null) }

    private suspend fun seed(id: String, ts: Long, dwell: Boolean = false, state: String = "PENDING") {
        db.frameDao().insertIgnore(FrameEntity(id, ts, "/x/$id.jpg", "rokid_glasses"))
        db.frameMetaDao().upsert(FrameMetaEntity(id, null, null, "STILL", dwell, null, null, null, null, null, null, null, null, null, ts, routedAt = ts))
        db.frameStateDao().insertIgnore(listOf(FrameStateEntity(id, state, 0, null, null, null, ts)))
    }

    /** Completes the claimed frames like ExtractSave's CAS does. */
    private fun completing(after: suspend (List<String>) -> Unit = {}): suspend (List<String>, String, CallClass) -> ExtractOutcome = { ids, claim, cls ->
        calls += ids to cls
        after(ids)
        ids.forEach { check(db.frameStateDao().completeIfClaimed(it, claim, "EXTRACTED", now) == 1) }
        ExtractOutcome.Saved(emptyList())
    }

    private fun pipeline(
        extractor: suspend (List<String>, String, CallClass) -> ExtractOutcome = completing(),
        power: PhonePowerPolicy? = null, claim: () -> String = { "claim-${calls.size}" },
    ) = KeyframePipeline(db, extractor, { now }, power, newClaimId = claim, pollMs = 50)

    private suspend fun state(id: String) = db.frameStateDao().get(id)!!.state

    @Test fun dwellFirstThenNewest() = runBlocking {
        for (i in 1..5) seed("f$i", i * 1000L, dwell = i == 1 || i == 2)
        assertEquals(3, pipeline().drainOnce())
        // dwell keyframes first (newest of them first), then the newest of the rest
        assertEquals(listOf("f2", "f1", "f5"), calls.single().first)
    }

    @Test fun backlogOver40ShedsOldestNonDwellBeyondTheNewest20() = runBlocking {
        for (i in 1..50) seed("f$i", i * 1000L, dwell = i == 3 || i == 10)
        assertEquals(3, pipeline().drainOnce())
        // shed: everything non-dwell older than the newest 20 (f31..f50), oldest first being f1; the dwell frames and the newest 20 stay
        assertEquals("NEAR_DUP", state("f1")); assertEquals("shed_backlog", db.frameStateDao().get("f1")!!.lastError)
        assertEquals("NEAR_DUP", state("f30")); assertEquals("NEAR_DUP", state("f4"))
        assertEquals("EXTRACTED", state("f3")); assertEquals("EXTRACTED", state("f10")) // dwell first, never shed
        assertEquals("EXTRACTED", state("f50")); assertEquals("PENDING", state("f31"))
        assertEquals(listOf("f10", "f3", "f50"), calls.single().first)
        assertEquals(28, db.frameStateDao().shedCount().first()) // 30 older frames minus the 2 dwell ones
        assertEquals(19, db.frameStateDao().pendingCount()) // f31..f49
    }

    @Test fun backlogOf40OrLessIsNotShed() = runBlocking {
        for (i in 1..40) seed("f$i", i * 1000L)
        pipeline().drainOnce()
        assertEquals(0, db.frameStateDao().shedCount().first())
        assertEquals("PENDING", state("f1"))
    }

    @Test fun maxThreePerCall() = runBlocking {
        for (i in 1..7) seed("f$i", i * 1000L)
        val p = pipeline()
        assertEquals(listOf(3, 3, 1, 0), List(4) { p.drainOnce() })
        assertEquals(listOf(3, 3, 1), calls.map { it.first.size })
        assertEquals(listOf("f7", "f6", "f5", "f4", "f3", "f2", "f1"), calls.flatMap { it.first })
    }

    @Test fun aPhotoQueuedByAForgetIsDescribedAloneAndTheRestBatchAsBefore() = runBlocking {
        // Forget lifecycle F1 / F5 (forgetV2): the survivor of a mixed memory goes back to PENDING with the solo marker; no other photo shares its call
        com.rokid.context.memory.forget.lifecycle.ForgetV2.testOverride = true
        try {
            for (i in 1..5) seed("f$i", i * 1000L)
            db.openHelper.writableDatabase.execSQL("UPDATE frame_state SET lastError = 'solo_rebuild' WHERE frameId = 'f4'")
            val p = pipeline()
            assertEquals(1, p.drainOnce())
            assertEquals(listOf("f4"), calls.single().first)
            assertEquals(3, p.drainOnce())
            assertEquals(listOf("f5", "f3", "f2"), calls[1].first)
        } finally { com.rokid.context.memory.forget.lifecycle.ForgetV2.testOverride = null }
    }

    @Test fun aSoloMarkerLeftByForgetV2IsIgnoredWhenTheFlagIsOffAgain() = runBlocking {
        com.rokid.context.memory.forget.lifecycle.ForgetV2.testOverride = false
        try {
            for (i in 1..5) seed("f$i", i * 1000L)
            db.openHelper.writableDatabase.execSQL("UPDATE frame_state SET lastError = 'solo_rebuild' WHERE frameId = 'f4'")
            val p = pipeline()
            assertEquals(3, p.drainOnce())
            assertEquals("v1 batching: the marker changes nothing", listOf("f5", "f4", "f3"), calls.single().first)
        } finally { com.rokid.context.memory.forget.lifecycle.ForgetV2.testOverride = null }
    }

    @Test fun backlogDrainsInOrderUnderBudget() = runBlocking {
        // Real time, a short period: the third batch has to wait for a token (burst 2).
        val budget = CallBudget(System::currentTimeMillis, { 200L })
        val times = mutableListOf<Long>()
        val p = pipeline(completing().let { inner -> { ids, c, cls -> budget.withPermit(CallClass.EXTRACT) { times += System.currentTimeMillis(); inner(ids, c, cls) } } })
        for (i in 1..9) seed("f$i", i * 1000L)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            p.start(scope)
            withTimeout(10_000) { while (db.frameStateDao().get("f1")!!.state != "EXTRACTED") delay(20) }
        } finally { scope.cancel() }
        assertEquals((9 downTo 1).map { "f$it" }, calls.flatMap { it.first })
        assertEquals(3, times.size)
        assertTrue("third batch waited for a token", times[2] - times[0] >= 150)
    }

    @Test fun killMidBacklogRecoversAll() = runBlocking {
        for (i in 1..5) seed("f$i", i * 1000L)
        var n = 0
        val crashing = pipeline({ ids, c, cls -> if (++n == 2) throw Crash() else completing()(ids, c, cls) })
        assertEquals(3, crashing.drainOnce())
        try { crashing.drainOnce(); error("expected the crash") } catch (_: Crash) {}
        // the process died between claim and commit: the two older frames are stuck EXTRACTING
        assertEquals(mapOf("EXTRACTED" to 3, "EXTRACTING" to 2), db.frameStateDao().counts().first())
        val restarted = pipeline()
        now += 60_000
        assertEquals("a live claim is not abandoned yet", 0, restarted.recover())
        now += FrameRecovery.CLAIM_TIMEOUT_MS
        assertEquals(2, restarted.recover())
        assertEquals(2, restarted.drainOnce())
        assertEquals(setOf("EXTRACTED"), (1..5).map { state("f$it") }.toSet())
        assertEquals(listOf(1, 1), listOf("f1", "f2").map { db.frameStateDao().get(it)!!.attempts }) // the abandoned claim cost an attempt
    }

    @Test fun offline25hNoLoss() = runBlocking {
        for (i in 1..4) seed("f$i", i * 1000L)
        val offline = pipeline({ _, _, _ -> ExtractOutcome.Failed("offline", countsAttempt = false) })
        repeat(6) { assertEquals(0, offline.drainOnce()) }
        // no attempt was used, nothing is FAILED_FINAL, and every frame is claimable again
        for (i in 1..4) { assertEquals("PENDING", state("f$i")); assertEquals(0, db.frameStateDao().get("f$i")!!.attempts) }
        now += 25 * 3_600_000L // the 24 h queue jobs are long gone; frame_state is the backlog
        val online = pipeline()
        assertEquals(3, online.drainOnce()); assertEquals(1, online.drainOnce())
        assertEquals(setOf("EXTRACTED"), (1..4).map { state("f$it") }.toSet())
    }

    @Test fun failuresCountAttemptsUntilFinal() = runBlocking {
        seed("f1", 1000)
        val failing = pipeline({ ids, _, cls -> calls += ids to cls; ExtractOutcome.Failed("INVALID_OUTPUT") })
        repeat(5) { assertEquals(0, failing.drainOnce()); now += 20 * 60_000L }
        assertEquals("FAILED_FINAL", state("f1"))
        assertEquals(5, db.frameStateDao().get("f1")!!.attempts)
        assertEquals(0, failing.drainOnce()) // final frames are not retried automatically
        assertEquals(5, calls.size)
        assertEquals(1, db.frameStateDao().retryAllFinal(now)); assertEquals("PENDING", state("f1"))
    }

    @Test fun failedFramesWaitTheirBackoff() = runBlocking {
        seed("f1", 1000)
        val p = pipeline({ ids, _, cls -> calls += ids to cls; ExtractOutcome.Failed("REJECTED") })
        p.drainOnce(); assertEquals("FAILED", state("f1"))
        now += 10_000; p.drainOnce(); assertEquals(1, calls.size) // 30 s not over
        now += 25_000; p.drainOnce(); assertEquals(2, calls.size)
    }

    @Test fun unreadableFramesAreFinal() = runBlocking {
        seed("f1", 1000); seed("f2", 2000)
        val p = pipeline({ ids, c, _ ->
            db.frameStateDao().completeIfClaimed("f2", c, "EXTRACTED", now)
            ExtractOutcome.Saved(emptyList(), unreadable = setOf("f1")).also { calls += ids to CallClass.EXTRACT }
        })
        assertEquals(1, p.drainOnce())
        assertEquals("FAILED_FINAL", state("f1")); assertEquals("EXTRACTED", state("f2"))
    }

    @Test fun askExtractsNewestFirst() = runBlocking {
        seed("a", now - 200_000); seed("b", now - 55_000, dwell = true); seed("c", now - 40_000)
        seed("d", now - 30_000); seed("e", now - 10_000); seed("f", now - 5_000)
        val p = pipeline()
        assertEquals(3, p.extractForAsk())
        assertEquals(listOf("f", "e", "d") to CallClass.USER, calls.single())
        assertEquals(2, p.extractForAsk()) // c, b; "a" is older than 60 s
        assertEquals(listOf("c", "b") to CallClass.USER, calls[1])
        assertEquals("PENDING", state("a"))
    }

    @Test fun powerPolicyGatesBackgroundButNotAsk() = runBlocking {
        for (i in 1..9) seed("f$i", now - i * 1000L)
        var thermal = PhoneThermal.SEVERE
        val power = PhonePowerPolicy({ now }, { false }, ThermalStatusProvider { thermal })
        val p = pipeline(power = power)
        assertEquals(0, p.drainOnce()); assertTrue(calls.isEmpty()) // SEVERE: all background work paused
        assertEquals(3, p.extractForAsk()) // user asks always run
        thermal = PhoneThermal.MODERATE
        now += 3 * 60_000
        assertEquals("SEVERE latches until below MODERATE for 2 min", 0, p.drainOnce())
        thermal = PhoneThermal.LIGHT
        power.mayRunBackground(com.rokid.context.work.budget.BackgroundWork.EXTRACTION) // starts the 2 min below MODERATE
        now += 2 * 60_000 + 1
        power.mayRunBackground(com.rokid.context.work.budget.BackgroundWork.EXTRACTION) // released
        thermal = PhoneThermal.MODERATE
        assertEquals("recovered; MODERATE allows one extraction per 30 s", 3, p.drainOnce())
        assertEquals(0, p.drainOnce())
        now += 31_000
        assertEquals(3, p.drainOnce())
    }

    @Test fun claimedFramesAreNotTakenTwice() = runBlocking {
        seed("f1", 1000)
        val dao = db.frameStateDao()
        assertEquals(1, dao.claim(listOf("f1"), "pipeline", now, now - 300_000).size)
        // the old worker's claim (same DAO call) gets nothing while the pipeline's claim is live
        assertEquals(0, dao.claim(listOf("f1"), "worker", now + 1000, now - 300_000 + 1000).size)
        assertEquals(0, pipeline().drainOnce())
    }

    // ---- review fixes (fixP) ----

    private fun modelFailure(kind: com.rokid.context.ai.FailureKind, cause: Throwable? = null, retryable: Boolean = kind == com.rokid.context.ai.FailureKind.TRANSIENT) =
        com.rokid.context.ai.ModelCallException("boom", retryable, cause, kind)

    @Test fun i1OfflineWalksTheWholeCauseChain() {
        // VISION_EXTRACT falling back to SUMMARIZE wraps the failure in a second ModelCallException
        val wrapped = modelFailure(com.rokid.context.ai.FailureKind.TRANSIENT, modelFailure(com.rokid.context.ai.FailureKind.TRANSIENT, java.net.UnknownHostException("h")))
        val o = ExtractOutcome.classify(wrapped)
        assertEquals("offline", o.error); assertEquals(false, o.countsAttempt)
        assertEquals(false, ExtractOutcome.classify(java.io.IOException("x")).countsAttempt)
    }

    @Test fun i1TimeoutIsAnAttemptNotOffline() {
        for (t in listOf<Throwable>(
            modelFailure(com.rokid.context.ai.FailureKind.TRANSIENT, java.net.SocketTimeoutException("slow")),
            modelFailure(com.rokid.context.ai.FailureKind.TRANSIENT, modelFailure(com.rokid.context.ai.FailureKind.TRANSIENT, java.io.InterruptedIOException("timeout"))),
            java.net.SocketTimeoutException("slow"),
        )) {
            val o = ExtractOutcome.classify(t)
            assertEquals("timeout", o.error); assertEquals(true, o.countsAttempt)
            assertEquals(com.rokid.context.orchestration.RetryPolicy.MAX_ATTEMPTS, o.maxAttempts)
        }
    }

    @Test fun i1NoValidatedNetworkIsOfflineForTransientFailures() {
        val http = modelFailure(com.rokid.context.ai.FailureKind.TRANSIENT, IllegalStateException("p request failed: 503 x"))
        assertEquals(true, ExtractOutcome.classify(http, online = true).countsAttempt)
        assertEquals(false, ExtractOutcome.classify(http, online = false).countsAttempt)
        // a frame's own failure is not the network's, whatever ConnectivityManager says
        assertEquals(true, ExtractOutcome.classify(modelFailure(com.rokid.context.ai.FailureKind.REJECTED, retryable = false), online = false).countsAttempt)
    }

    @Test fun i1SettingsFailuresNeverCountAndOthersUseTheShortSchedule() {
        assertEquals(false, ExtractOutcome.classify(modelFailure(com.rokid.context.ai.FailureKind.NOT_SET, retryable = false)).countsAttempt)
        assertEquals(false, ExtractOutcome.classify(modelFailure(com.rokid.context.ai.FailureKind.TRANSIENT, modelFailure(com.rokid.context.ai.FailureKind.AUTH, retryable = false))).countsAttempt)
        val o = ExtractOutcome.classify(modelFailure(com.rokid.context.ai.FailureKind.REJECTED, retryable = false))
        assertEquals(5, o.maxAttempts)
    }

    @Test fun i1TransientFailuresFollowTodaysRetrySchedule() = runBlocking {
        seed("f1", 1000)
        val failing = pipeline({ ids, _, cls -> calls += ids to cls; ExtractOutcome.classify(modelFailure(com.rokid.context.ai.FailureKind.TRANSIENT, IllegalStateException("429"))) })
        var waited = 0L
        for (attempt in 1..8) {
            assertEquals(0, failing.drainOnce())
            val row = db.frameStateDao().get("f1")!!
            assertEquals(attempt, row.attempts)
            assertEquals(if (attempt < 8) "FAILED" else "FAILED_FINAL", row.state)
            if (attempt < 8) {
                val gap = KeyframePipeline.failureBackoffMs(attempt)
                assertEquals("not due 1 ms early", 0, failing.drainOnce()); assertEquals(attempt, calls.size)
                now += gap - 1; assertEquals(0, failing.drainOnce()); assertEquals(attempt, calls.size)
                now += 1; waited += gap
            }
        }
        assertEquals(8, calls.size)
        assertTrue("the schedule spans about an hour", waited in 55 * 60_000L..65 * 60_000L)
    }

    @Test fun i2StaleSaveGivesTheRestOfTheBatchBackWithNoAttempt() = runBlocking {
        for (i in 1..3) seed("f$i", i * 1000L)
        val dao = db.frameStateDao()
        val p = pipeline({ ids, claim, cls ->
            calls += ids to cls
            // the user forgot / another worker retook f2 mid-call: its claim no longer holds
            dao.release("f2", claim, now); dao.claim(listOf("f2"), "other", now, now - 1)
            ExtractOutcome.Stale()
        })
        assertEquals(0, p.drainOnce())
        for (id in listOf("f1", "f3")) { assertEquals("PENDING", state(id)); assertEquals(0, dao.get(id)!!.attempts); assertEquals(null, dao.get(id)!!.claimId) }
        assertEquals("EXTRACTING", state("f2")); assertEquals("other", dao.get("f2")!!.claimId) // the stale frame is left alone
    }

    @Test fun i3UnseenFramesTakeANormalFailureNotFinal() = runBlocking {
        seed("f1", 1000); seed("f2", 2000)
        val p = pipeline({ ids, c, _ ->
            calls += ids to CallClass.EXTRACT
            db.frameStateDao().completeIfClaimed("f2", c, "EXTRACTED", now)
            ExtractOutcome.Saved(emptyList(), unseen = setOf("f1"))
        })
        assertEquals(1, p.drainOnce())
        val f1 = db.frameStateDao().get("f1")!!
        assertEquals("FAILED", f1.state); assertEquals(1, f1.attempts) // retried after its backoff
        assertEquals("EXTRACTED", state("f2"))
    }

    @Test fun i4AskFindsTheNewestFramesBehindAHugeBacklog() = runBlocking {
        db.frameStateDao().insertIgnore((1..650).map { FrameStateEntity("old$it", "PENDING", 0, null, null, null, now - 3_600_000L + it) })
        seed("n1", now - 50_000); seed("n2", now - 30_000); seed("n3", now - 10_000); seed("n4", now - 5_000)
        val p = pipeline()
        assertEquals(3, p.extractForAsk())
        assertEquals(listOf("n4", "n3", "n2") to CallClass.USER, calls.single())
        assertEquals("PENDING", state("old1"))
    }

    @Test fun i4BackgroundPickIsNewestFirstWhateverTheBacklogSize() = runBlocking {
        db.frameStateDao().insertIgnore((1..650).map { FrameStateEntity("old$it", "PENDING", 0, null, null, null, now - 3_600_000L + it) })
        seed("n1", now - 3_000); seed("n2", now - 2_000); seed("d", now - 1_000_000, dwell = true)
        pipeline().drainOnce()
        assertEquals(listOf("d", "n2", "n1"), calls.single().first)
    }

    @Test fun i4AskWaitsForTheLoopsLiveClaimsInItsWindow() = runBlocking {
        for (i in 1..3) seed("f$i", now - i * 1000L)
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val loop = pipeline({ ids, c, cls -> release.await(); completing()(ids, c, cls) })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            loop.start(scope)
            withTimeout(5_000) { while (db.frameStateDao().get("f1")!!.state != "EXTRACTING") delay(10) } // the loop holds all three, waiting (for a budget token)
            val ask = scope.async { loop.extractForAsk() }
            delay(300)
            assertTrue("the ask waits for the live claims", ask.isActive)
            release.complete(Unit)
            withTimeout(5_000) { ask.await() }
            assertEquals(setOf("EXTRACTED"), (1..3).map { state("f$it") }.toSet())
            assertEquals(1, calls.size) // no second call for the same frames
        } finally { scope.cancel() }
    }

    @Test fun drainUntilIdleExtractsTheWholeBacklogInTheCallersCoroutine() = runBlocking {
        for (i in 1..7) seed("f$i", i * 1000L)
        assertEquals(7, pipeline().drainUntilIdle(10_000))
        assertEquals(3, calls.size)
        assertEquals(setOf("EXTRACTED"), (1..7).map { state("f$it") }.toSet())
    }

    @Test fun drainUntilIdleStopsWhenBlockedByBattery() = runBlocking {
        seed("f1", 1000)
        val p = KeyframePipeline(db, completing(), { now }, deferred = { true }, pollMs = 50, deferPollMs = 50)
        assertEquals(0, p.drainUntilIdle(10_000)); assertTrue(calls.isEmpty())
        assertEquals("PENDING", state("f1"))
    }

    @Test fun failedFramesRetryAfterTheirBackoffEvenWhenQuiet() = runBlocking {
        seed("f1", 1000)
        val p = pipeline({ ids, _, cls -> calls += ids to cls; ExtractOutcome.Failed("REJECTED") })
        p.drainOnce()
        assertEquals(0, p.drainUntilIdle(200)) // the next retry is 30 s away: not worth holding the worker
        assertEquals(1, calls.size)
    }

    // ---- wave 2 Task 6: routing before any claim ----

    /** A frame the scene router has not seen (routedAt NULL), as the ingest leaves it when the indexer is active. */
    private suspend fun seedUnrouted(id: String, ts: Long, dwell: Boolean = false) {
        db.frameDao().insertIgnore(FrameEntity(id, ts, "/x/$id.jpg", "rokid_glasses"))
        db.frameMetaDao().upsert(FrameMetaEntity(id, null, null, "STILL", dwell, null, null, null, null, null, null, null, null, null, ts, routedAt = null))
        db.frameStateDao().insertIgnore(listOf(FrameStateEntity(id, "PENDING", 0, null, null, null, ts)))
    }

    /** No IMAGE-capable space: every frame routes as in wave 1 (PENDING, per photo). */
    private fun noImageSpaceIndexer() = SceneIndexer(
        db, { emptyList() }, { null }, WatchVectors(WatchListStore(ctx.getSharedPreferences("kp_watch", Context.MODE_PRIVATE))), null,
        { emptyMap() }, { now },
    )

    @Test fun routesBeforeClaim() = runBlocking {
        seedUnrouted("a", now - 5_000)
        val plain = KeyframePipeline(db, completing(), { now }, null, pollMs = 50)
        assertEquals("an unrouted frame is the router's for 10 min", 0, plain.drainOnce())
        val routed = KeyframePipeline(db, completing(), { now }, null, pollMs = 50, indexer = noImageSpaceIndexer())
        assertEquals(1, routed.drainOnce())
        assertTrue(db.frameMetaDao().get("a")!!.routedAt != null)
        assertEquals("EXTRACTED", state("a"))
    }

    @Test fun dwellStillFirst() = runBlocking {
        for (i in 1..4) seedUnrouted("f$i", now - 10_000 + i * 1000L, dwell = i == 1)
        KeyframePipeline(db, completing(), { now }, null, pollMs = 50, indexer = noImageSpaceIndexer()).drainOnce()
        assertEquals("f1", calls.single().first.first()) // the dwell keyframe leads, then the newest
        assertEquals(listOf("f1", "f4", "f3"), calls.single().first)
    }

    @Test fun waveOneBehaviourWithoutImageSpace() = runBlocking {
        for (i in 1..5) seedUnrouted("f$i", now - 10_000 + i * 1000L)
        val p = KeyframePipeline(db, completing(), { now }, null, pollMs = 50, indexer = noImageSpaceIndexer())
        assertEquals(listOf(3, 2), listOf(p.drainOnce(), p.drainOnce()))
        assertEquals(listOf(3, 2), calls.map { it.first.size })
        assertEquals(null, db.frameClusterDao().open())
        assertEquals(setOf("EXTRACTED"), (1..5).map { state("f$it") }.toSet())
    }

    @Test fun shedSalientGoesToCaptionQueue() = runBlocking {
        for (i in 1..50) seed("f$i", i * 1000L)
        db.frameMetaDao().markRouted("f2", 0.6f, null, null, null, null, 1L)
        db.frameMetaDao().markRouted("f3", 0.4f, null, null, null, null, 1L)
        pipeline().drainOnce()
        assertEquals("CAPTION_QUEUED", state("f2")); assertEquals("shed_backlog", db.frameStateDao().get("f2")!!.lastError)
        assertEquals("NEAR_DUP", state("f3")); assertEquals("NEAR_DUP", state("f1"))
        assertEquals(29, db.frameStateDao().shedCount().first()) // 30 older frames, minus the queued f2
    }

    // --- wave 3 Task 2 (S5): extractNow's typed outcome per frame --------------------------------------------------------

    @Test fun extractNowTypesEachFramesOutcome() = runBlocking {
        seed("a", 1000); seed("b", 2000); seed("held", 3000, state = "EXTRACTING"); seed("done", 4000, state = "EXTRACTED")
        val saved = pipeline({ ids, claim, cls ->
            calls += ids to cls
            ids.forEach { check(db.frameStateDao().completeIfClaimed(it, claim, "EXTRACTED", now) == 1) }
            ExtractOutcome.Saved(listOf(com.rokid.context.orchestration.SavedMemory("m1", listOf("a"), 0)))
        })
        val r = saved.extractNow(listOf("a", "b", "held"))
        assertEquals(mapOf("a" to ExtractNowOutcome.Extracted(1), "b" to ExtractNowOutcome.ExtractedEmpty, "held" to ExtractNowOutcome.LostClaim), r)
        assertEquals(listOf(listOf("a", "b") to CallClass.USER), calls.map { it.first.sorted() to it.second })
        assertEquals("EXTRACTING", state("held")) // someone else's claim is untouched
        assertEquals(mapOf("done" to ExtractNowOutcome.LostClaim), saved.extractNow(listOf("done"))) // not claimable: nothing runs
        assertEquals(1, calls.size)
    }

    @Test fun extractNowFailureWipeAndStaleAreDistinct() = runBlocking {
        seed("f", 1000); seed("w", 2000); seed("s", 3000)
        assertEquals(mapOf("f" to ExtractNowOutcome.Failed("offline")), pipeline({ _, _, _ -> ExtractOutcome.Failed("offline", countsAttempt = false) }).extractNow(listOf("f")))
        assertEquals("PENDING", state("f")) // given back, no attempt
        assertEquals(mapOf("w" to ExtractNowOutcome.Wiped), pipeline({ _, _, _ -> ExtractOutcome.Wiped() }).extractNow(listOf("w")))
        assertEquals("PENDING", state("w"))
        assertEquals(mapOf("s" to ExtractNowOutcome.Stale), pipeline({ _, _, _ -> ExtractOutcome.Stale() }).extractNow(listOf("s")))
        assertEquals(mapOf("s" to ExtractNowOutcome.Failed("unreadable")), pipeline({ _, _, _ -> ExtractOutcome.Failed("unreadable", unreadable = setOf("s")) }).extractNow(listOf("s")))
    }

    @Test fun extractNowNeverOffersToNudges() = runBlocking {
        seed("a", 1000); seed("b", 2000)
        val offered = mutableListOf<List<String>>()
        val p = KeyframePipeline(db, completing().let { inner -> { ids, c, cls -> inner(ids, c, cls); ExtractOutcome.Saved(listOf(com.rokid.context.orchestration.SavedMemory("m", ids, 0))) } },
            { now }, null, onExtracted = { frames, _ -> offered += frames.map { it.frameId } }, newClaimId = { "c" }, pollMs = 50)
        p.extractNow(listOf("a")); assertTrue("describe is not offered to the nudge evaluator", offered.isEmpty())
        p.drainOnce(); assertEquals("the ordinary path still is", listOf(listOf("b")), offered)
    }

    // ---- roaming data saver ----

    @Test fun aDataSaverBlockClassifiesAsParkedWithNoAttemptThroughTheWholeCauseChain() {
        val block = com.rokid.context.ai.DataSaverBlockedException("wifi_only")
        listOf<Throwable>(block, modelFailure(com.rokid.context.ai.FailureKind.TRANSIENT, block)).forEach {
            val o = ExtractOutcome.classify(it)
            assertEquals("data_saver", o.error); assertEquals(false, o.countsAttempt)
        }
        // even with the phone reporting offline: it is the saver, not the network
        assertEquals("data_saver", ExtractOutcome.classify(block, online = false).error)
    }

    @Test fun aDataSaverBlockParksTheLoopWithoutAnAttemptAndTheOpenSignalResumesIt() = runBlocking {
        for (i in 1..3) seed("f$i", i * 1000L)
        var blocked = true
        val p = pipeline({ ids, claim, cls ->
            calls += ids to cls
            if (blocked) ExtractOutcome.classify(com.rokid.context.ai.DataSaverBlockedException("roaming")) else completing()(ids, claim, cls)
        })
        assertEquals(0, p.drainOnce()); assertEquals(1, calls.size)
        for (i in 1..3) { assertEquals("PENDING", state("f$i")); assertEquals(0, db.frameStateDao().get("f$i")!!.attempts) }
        // Wi-Fi (the policy opens): the same signal a returning network sends wakes it at once
        blocked = false
        p.onNetworkReturn()
        assertEquals(3, p.drainOnce())
        assertEquals(setOf("EXTRACTED"), (1..3).map { state("f$it") }.toSet())
    }


    /** Slice 2 on virtual time: a daily cap parks the loop exactly like a hold (no attempt, frames parked, nothing shed) and the next local day, announced by the open signal, drains it. */
    @Test fun aDailyCapParksThePhotosAndTheNextDayWakesThemAllWithoutAnAttemptOrAShed() = runBlocking {
        val store = object : com.rokid.context.core.net.CounterStore {
            val m = mutableMapOf<String, Long>()
            override fun getLong(key: String, default: Long) = m[key] ?: default
            override fun putLongs(values: Map<String, Long>) { m.putAll(values) }
        }
        val day = 86_400_000L
        now = 5 * day + 10 * 3_600_000L // 10:00 on day 5 (UTC stands in for the local zone)
        val ledger = com.rokid.context.core.net.MobileDataLedger(store) { Math.floorDiv(now, day) }
        val limit = com.rokid.context.core.net.DailyLimit(10L * 1024 * 1024, 3)
        fun capReached() = com.rokid.context.core.net.DailyCaps.reached(limit, ledger.today().usage)
        for (i in 1..9) seed("f$i", i * 1000L)
        val sent = mutableListOf<List<String>>()
        val p = KeyframePipeline(db, { ids, claim, cls ->
            calls += ids to cls
            if (capReached()) ExtractOutcome.classify(com.rokid.context.ai.DataSaverBlockedException("daily_limit"))
            else { sent += ids; ledger.add(com.rokid.context.core.net.DataKind.PHOTO, 100_000L * ids.size, ids.size); completing()(ids, claim, cls) }
        }, { now }, null, newClaimId = { "claim-${calls.size}" }, pollMs = 50, dataSaverBlocked = { capReached() })
        var guard = 0
        while (guard++ < 20 && p.drainOnce() > 0) { }
        val described = (1..9).count { state("f$it") == "EXTRACTED" }
        assertTrue("some went out under the cap: $described", described in 1..8)
        assertTrue("the cap stopped the rest: ${sent.sumOf { it.size }} photos sent", sent.sumOf { it.size } >= 3)
        for (i in 1..9) if (state("f$i") == "PENDING") assertEquals("a cap block never uses an attempt", 0, db.frameStateDao().get("f$i")!!.attempts)
        assertEquals("every waiting photo is parked and none is shed", 9 - described, db.frameStateDao().dataSaverParkedCount())
        assertEquals(0, (1..9).count { state("f$it") == "NEAR_DUP" })
        val sentBefore = sent.sumOf { it.size }
        // later the same day: still parked, nothing goes
        now += 31 * 60_000L; p.drainOnce()
        assertEquals(sentBefore, sent.sumOf { it.size })
        // local midnight passes: today's counters are zero, the graph's open signal wakes the loop
        now = 6 * day + 1_000L
        assertEquals(0L, ledger.today().photos)
        p.onNetworkReturn()
        guard = 0
        while (guard++ < 40 && p.drainOnce() > 0) { }
        // the new day has its own cap of 3: the rest drain over the following days, every one of them exactly once and never shed
        assertTrue("the next day sent more", sent.sumOf { it.size } > sentBefore)
        assertEquals(sent.sumOf { it.size }, sent.flatten().toSet().size)
        assertEquals(0, (1..9).count { state("f$it") == "NEAR_DUP" })
    }

    @Test fun framesParkedByTheDataSaverAreNeverShedNotEvenOnTheFirstDrainAfterTheGateOpens() = runBlocking {
        for (i in 1..300) seed("f$i", i * 1000L)
        var blocked = true
        val order = mutableListOf<String>()
        val p = KeyframePipeline(db, { ids, claim, cls ->
            calls += ids to cls
            if (blocked) ExtractOutcome.classify(com.rokid.context.ai.DataSaverBlockedException("roaming"))
            else { order += ids; completing()(ids, claim, cls) }
        }, { now }, null, newClaimId = { "claim-${calls.size}" }, pollMs = 50, dataSaverBlocked = { blocked })
        p.drainOnce(); p.onNetworkReturn(); p.drainOnce()          // parked, plus a network-return wake
        now += 31 * 60_000L; p.drainOnce()                          // and the 30-minute recheck
        assertEquals(300, db.frameStateDao().pendingCount()); assertEquals(300, db.frameStateDao().dataSaverParkedCount())
        // the gate opens: the very first drain must not thin the backlog, and everything is then described
        blocked = false; p.onNetworkReturn()
        assertEquals(3, p.drainOnce())
        assertEquals(0, (1..300).count { state("f$it") == "NEAR_DUP" })
        var guard = 0
        while (p.drainOnce() > 0 && guard++ < 200) { }
        assertEquals(setOf("EXTRACTED"), (1..300).map { state("f$it") }.toSet())
        assertEquals("every parked frame was sent exactly once", 300, order.toSet().size)
        assertEquals(0, db.frameStateDao().dataSaverParkedCount())
    }

    @Test fun framesThatArrivedWhileParkedAreMarkedWhenTheGateOpensAndOrdinaryBacklogStillSheds() = runBlocking {
        for (i in 1..100) seed("f$i", i * 1000L)
        val p = KeyframePipeline(db, completing(), { now }, null, newClaimId = { "claim-${calls.size}" }, pollMs = 50, dataSaverBlocked = { false })
        p.parkForDataSaver()
        assertEquals(100, db.frameStateDao().dataSaverParkedCount())
        for (i in 101..200) seed("g$i", i * 1000L) // ordinary frames, never parked
        p.drainOnce()
        assertTrue("ordinary frames are still thinned", (101..200).any { state("g$it") == "NEAR_DUP" })
        assertEquals("parked frames are not", 0, (1..100).count { state("f$it") == "NEAR_DUP" })
    }

    private fun blockedOnce(blocked: () -> Boolean, wasBlocked: () -> Boolean, handled: (Long) -> Unit = {}) =
        KeyframePipeline(db, completing(), { now }, null, newClaimId = { "claim-${calls.size}" }, pollMs = 50, dataSaverBlocked = blocked, dataSaverBlockedSince = { if (wasBlocked()) 777L else 0L }, dataSaverHandled = handled)

    @Test fun afterAProcessRestartTheFirstUnblockedDrainParksTheUntaggedBacklogBeforeSheddingAndHandlesTheMark() = runBlocking {
        for (i in 1..300) seed("f$i", i * 1000L) // never tagged: the old process died before its next blocked drain
        var handled = 0
        val p = blockedOnce(blocked = { false }, wasBlocked = { handled == 0 }, handled = { since -> assertEquals("the value read before parking is passed on", 777L, since); handled++ })
        assertEquals(3, p.drainOnce())
        assertEquals("nothing was shed", 0, (1..300).count { state("f$it") == "NEAR_DUP" })
        assertEquals(1, handled)
        assertEquals(297, db.frameStateDao().dataSaverParkedCount())
    }

    @Test fun anOpeningNoticedInsideTheDrainStillParksBeforeSheddingNoRaceWithTheListener() = runBlocking {
        for (i in 1..120) seed("f$i", i * 1000L)
        var open = false
        // dataSaverBlocked() is where the gate refreshes: the same call that notices the opening answers "not blocked"; the persisted mark is still set
        val p = blockedOnce(blocked = { open = true; false }, wasBlocked = { open })
        p.drainOnce()
        assertEquals(0, (1..120).count { state("f$it") == "NEAR_DUP" })
    }

    /** Codex data saver r1 P1-3: a backlog longer than the old 300 cap keeps EVERY parked frame (retention would delete a shed, undescribed photo). */
    @Test fun everyParkedFrameIsNeverShedEvenBeyondThreeHundred() = runBlocking {
        for (i in 1..500) seed("f$i", i * 1000L)
        db.frameStateDao().parkPendingForDataSaver()
        assertEquals(500, db.frameStateDao().dataSaverParkedCount())
        assertEquals("the shed threshold ignores every parked frame", 0, db.frameStateDao().unprotectedPendingCount())
        for (i in 1..60) seed("l$i", (1000 + i) * 1000L) // 60 ordinary frames: above the threshold, so shedding runs
        val p = blockedOnce(blocked = { false }, wasBlocked = { false })
        p.drainOnce()
        assertEquals("not one parked frame is shed", 0, (1..500).count { state("f$it") == "NEAR_DUP" || state("f$it") == "CAPTION_QUEUED" })
        assertTrue("ordinary frames are still thinned", (1..60).any { state("l$it") == "NEAR_DUP" })
    }

    @Test fun liveFramesAreNotThinnedJustBecauseParkedOnesWait() = runBlocking {
        for (i in 1..100) seed("p$i", i * 1000L)
        db.frameStateDao().parkPendingForDataSaver()
        for (i in 1..30) seed("l$i", (1000 + i) * 1000L) // live frames, newer
        assertEquals("only the 30 live frames count toward the threshold", 30, db.frameStateDao().unprotectedPendingCount())
        blockedOnce(blocked = { false }, wasBlocked = { false }).drainOnce()
        assertEquals(0, (1..30).count { state("l$it") == "NEAR_DUP" })
    }

    @Test fun timeoutAndAbandonedFramesAreParkedButSoloRebuildAndDescribeBeforeDeleteAreNot() = runBlocking {
        seed("a", 1000); seed("b", 2000); seed("c", 3000); seed("d", 4000)
        val dao = db.frameStateDao()
        db.openHelper.writableDatabase.execSQL("UPDATE frame_state SET lastError = 'timeout' WHERE frameId = 'a'")
        db.openHelper.writableDatabase.execSQL("UPDATE frame_state SET lastError = 'abandoned' WHERE frameId = 'b'")
        db.openHelper.writableDatabase.execSQL("UPDATE frame_state SET lastError = 'solo_rebuild' WHERE frameId = 'c'")
        db.openHelper.writableDatabase.execSQL("UPDATE frame_state SET lastError = 'describe_before_delete' WHERE frameId = 'd'")
        assertEquals(2, dao.parkPendingForDataSaver())
        assertEquals("data_saver", dao.get("a")!!.lastError); assertEquals("data_saver", dao.get("b")!!.lastError)
        assertEquals("solo_rebuild", dao.get("c")!!.lastError); assertEquals("describe_before_delete", dao.get("d")!!.lastError)
    }

    @Test fun aRefusalInsideAWifiBlipParksNothingAndWaitsOnlyThirtySeconds() = runBlocking {
        for (i in 1..50) seed("f$i", i * 1000L)
        // the router refused (the raw decision), but the debounced "held back" says no: a reconnect blip
        val p = KeyframePipeline(db, { ids, _, cls -> calls += ids to cls; ExtractOutcome.classify(com.rokid.context.ai.DataSaverBlockedException("unknown_network")) }, { now }, null,
            newClaimId = { "claim-${calls.size}" }, pollMs = 50, dataSaverBlocked = { false })
        p.drainOnce()
        assertEquals(0, db.frameStateDao().dataSaverParkedCount())
        for (i in 1..50) assertEquals(0, db.frameStateDao().get("f$i")!!.attempts)
    }

    /** P2-e: settle() after the cap. A reachable but always failing on-device/LAN fallback parks a frame CAP times without an attempt; the next failure is an ordinary one. */
    @Test fun aFrameWhoseFallbackKeepsFailingIsParkedCapTimesThenCountsAnAttempt() = runBlocking {
        com.rokid.context.work.FallbackParkCap.resetForTests()
        seed("f1", 1000L)
        val p = KeyframePipeline(db, { ids, _, cls -> calls += ids to cls; ExtractOutcome.classify(com.rokid.context.ai.OfflineOnlyRefusedException(fallbackKind = com.rokid.context.ai.FailureKind.TRANSIENT)) }, { now }, null,
            newClaimId = { "claim-${calls.size}" }, pollMs = 50, dataSaverBlocked = { true })
        repeat(com.rokid.context.work.FallbackParkCap.CAP) { p.drainOnce(); now += 2 * 60 * 60_000L }
        assertEquals("parked CAP times: no attempt used", 0, db.frameStateDao().get("f1")!!.attempts)
        assertEquals("PENDING", state("f1"))
        p.drainOnce(); now += 2 * 60 * 60_000L
        assertEquals("the next one is an ordinary transient failure", 1, db.frameStateDao().get("f1")!!.attempts)
        assertEquals("fallback_unavailable", db.frameStateDao().get("f1")!!.lastError)
        com.rokid.context.work.FallbackParkCap.resetForTests()
    }
}
