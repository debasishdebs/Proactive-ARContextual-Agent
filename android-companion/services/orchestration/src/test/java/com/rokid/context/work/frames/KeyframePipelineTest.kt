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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
        db.frameMetaDao().upsert(FrameMetaEntity(id, null, null, "STILL", dwell, null, null, null, null, null, null, null, null, null, ts))
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
        now += 5 * 60_000
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
        repeat(5) { assertEquals(0, failing.drainOnce()); now += 10 * 60_000L }
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
}
