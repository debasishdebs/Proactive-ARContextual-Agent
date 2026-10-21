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
}
