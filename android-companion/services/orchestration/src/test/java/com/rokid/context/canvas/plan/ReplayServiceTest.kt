package com.rokid.context.canvas.plan

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.capture.replay.ReplayCandidate
import com.rokid.context.capture.replay.ReplayFigures
import com.rokid.context.capture.replay.ReplayReport
import com.rokid.context.memory.db.CanvasReplayEntity
import com.rokid.context.memory.db.CanvasTraceEntity
import com.rokid.context.memory.db.DaoFence
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.DeletionEpoch
import com.rokid.context.memory.db.TraceSources
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ReplayServiceTest {
    private val db = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    private val dao = db.canvasSlice3Dao()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val dev = MutableStateFlow(true)
    private val thermal = MutableStateFlow(false)
    private var generation = 1L
    private val epoch = DeletionEpoch("install", 1, 0)
    private var now = 1_000L
    private val ids = AtomicInteger()

    @After fun close() { scope.cancel(); db.close() }

    private fun figures(h: String) = ReplayFigures(h, 3, 1.0, 2.1, 0.7, 3.0, 1.0, 0, 0.45, 3.7)
    private fun report(h: String = "0") = ReplayReport("EXACT", 3.0, figures(h), null, 0.0, 1, 0, 0, emptyMap(), false, listOf("replay is approximate"))

    /** A trace whose replay waits on [hold] (when given), honouring `tick` like the real stream does. */
    private inner class FakeTrace(val hold: CountDownLatch? = null, val fail: Boolean = false, hash: String? = "abc", rev: Int = 0) : ReplayTrace(hash, rev, 10_000, 20_000) {
        val entered = CountDownLatch(1)
        override fun replay(candidate: ReplayCandidate?, candidateFusion: Map<String, Double>?, tick: () -> Unit): ReplayReport {
            entered.countDown()
            if (hold != null) while (!hold.await(5, TimeUnit.MILLISECONDS)) tick()
            tick()
            if (fail) error("boom")
            return report(candidate?.configHash ?: "abc")
        }
    }

    private fun trace(id: String, t: FakeTrace, rev: Int = 0) {
        runBlocking { dao.acceptTraceFenced(CanvasTraceEntity(id, "IMU", "COMPLETE", "{}", generation, false, rev, null, epoch.installId, epoch.counter, true, 10_000, 20_000, 1), fence()) }
        loaded[id] = t
    }
    private val loaded = HashMap<String, ReplayTrace>()
    private fun fence() = DaoFence(generation, epoch, { generation }, { epoch })
    private fun service(dm: MutableStateFlow<Boolean> = dev) =
        ReplayService(dao, { id -> loaded[id] }, ::fence, scope, dm, thermal, { now }, { "rp_${ids.incrementAndGet()}" })

    private fun <T> wait(what: String, f: suspend () -> T?): T = runBlocking { withTimeout(10_000) { var v = f(); while (v == null) { delay(5); v = f() }; v } }
    private fun state(id: String) = runBlocking { dao.replay(id)!!.jobState }
    private fun awaitState(id: String, s: String) = wait("state $s") { if (state(id) == s) s else null }

    @Test fun doneIsCommittedThroughTheFencedDaoWithResult() {
        val t = FakeTrace(); trace("t1", t)
        val id = runBlocking { service().start(ReplayRequest(listOf("t1"))) }
        awaitState(id, "DONE")
        val row = runBlocking { dao.replay(id)!! }
        assertNotNull(row.resultJson); assertNotNull(row.finishedAt)
        assertTrue(row.resultJson!!.contains("\"estimate\":true")); assertTrue(row.resultJson!!.contains("replay is approximate"))
        assertEquals("abc", row.configHash); assertEquals("EXACT", row.replayClass)
    }

    @Test fun sourcesRecorded() {
        trace("t1", FakeTrace(rev = 2), rev = 2); trace("t2", FakeTrace(hash = "abc"))
        runBlocking { dao.bumpLabelRev(); dao.bumpLabelRev() }
        val id = runBlocking { service().start(ReplayRequest(listOf("t1", "t2"), ReplayCandidate("cand123"))) }
        awaitState(id, "DONE")
        val row = runBlocking { dao.replay(id)!! }
        val src = TraceSources.decode(row.sourcesJson)!!
        assertEquals(listOf("t1", "t2"), src.map { it.traceId }); assertEquals(2, src[0].revision)
        assertEquals(10_000L, src[0].fromMs); assertEquals(20_000L, src[0].toMs)
        assertEquals(2L, row.labelRev)
        assertEquals("COUNTERFACTUAL", row.replayClass); assertEquals("cand123", row.configHash)
        assertEquals(10_000L, row.fromMs); assertEquals(20_000L, row.toMs)
    }

    @Test fun oneJobAtATime() {
        val hold = CountDownLatch(1); val t = FakeTrace(hold); trace("t1", t); trace("t2", FakeTrace())
        val s = service()
        val first = runBlocking { s.start(ReplayRequest(listOf("t1"))) }
        assertTrue(t.entered.await(5, TimeUnit.SECONDS))
        assertEquals(first, s.runningId())
        assertTrue(runCatching { runBlocking { s.start(ReplayRequest(listOf("t2"))) } }.exceptionOrNull() is ReplayBusy)
        assertEquals("only the first job has a row", 1, runBlocking { dao.replays(10).size })
        hold.countDown(); awaitState(first, "DONE")
        wait("mutex free") { if (s.runningId() == null) 1 else null }
        val second = runBlocking { s.start(ReplayRequest(listOf("t2"))) }
        awaitState(second, "DONE")
    }

    @Test fun cancelledOnDeveloperModeOff() {
        val hold = CountDownLatch(1); val t = FakeTrace(hold); trace("t1", t)
        val s = service()
        val id = runBlocking { s.start(ReplayRequest(listOf("t1"))) }
        assertTrue(t.entered.await(5, TimeUnit.SECONDS))
        dev.value = false
        awaitState(id, "CANCELLED")
        assertNull(runBlocking { dao.replay(id)!!.resultJson })
        assertTrue(runCatching { runBlocking { s.start(ReplayRequest(listOf("t1"))) } }.exceptionOrNull() is ReplayDeveloperModeOff)
    }

    @Test fun cancelledOnThermalSevere() {
        val hold = CountDownLatch(1); val t = FakeTrace(hold); trace("t1", t)
        val id = runBlocking { service().start(ReplayRequest(listOf("t1"))) }
        assertTrue(t.entered.await(5, TimeUnit.SECONDS))
        thermal.value = true
        awaitState(id, "CANCELLED")
    }

    @Test fun cancelledOnShutdown() {
        val hold = CountDownLatch(1); val t = FakeTrace(hold); trace("t1", t)
        val s = service()
        val id = runBlocking { s.start(ReplayRequest(listOf("t1"))) }
        assertTrue(t.entered.await(5, TimeUnit.SECONDS))
        runBlocking { s.shutdown() }
        assertEquals("CANCELLED", state(id))                     // persisted when shutdown returns
        assertNull(s.runningId())
    }

    @Test fun abortedAfterRestart() {
        // A previous process left two rows RUNNING (and one finished); the new process marks exactly the RUNNING ones ABORTED.
        runBlocking {
            dao.insertReplay(CanvasReplayEntity("dead1", null, "{}", "EXACT", createdAt = 1))
            dao.insertReplay(CanvasReplayEntity("dead2", null, "{}", "COUNTERFACTUAL", createdAt = 2))
            dao.insertReplay(CanvasReplayEntity("done1", null, "{}", "EXACT", resultJson = "{}", jobState = "DONE", createdAt = 3))
        }
        now = 9_000
        assertEquals(2, runBlocking { service().recover() })
        assertEquals("ABORTED", state("dead1")); assertEquals("ABORTED", state("dead2")); assertEquals("DONE", state("done1"))
        assertEquals(9_000L, runBlocking { dao.replay("dead1")!!.finishedAt })
    }

    @Test fun aWipeDuringTheJobDropsTheResult() {
        val hold = CountDownLatch(1); val t = FakeTrace(hold); trace("t1", t)
        val id = runBlocking { service().start(ReplayRequest(listOf("t1"))) }
        assertTrue(t.entered.await(5, TimeUnit.SECONDS))
        generation++                                             // Delete all moved the in-process generation after the fence was captured
        hold.countDown()
        awaitState(id, "CANCELLED")
        assertNull(runBlocking { dao.replay(id)!!.resultJson })
    }

    @Test fun movedLabelsDropTheResult() {
        val hold = CountDownLatch(1); val t = FakeTrace(hold); trace("t1", t)
        val id = runBlocking { service().start(ReplayRequest(listOf("t1"))) }
        assertTrue(t.entered.await(5, TimeUnit.SECONDS))
        runBlocking { dao.bumpLabelRev() }
        hold.countDown()
        awaitState(id, "CANCELLED")
        assertNull(runBlocking { dao.replay(id)!!.resultJson })
    }

    @Test fun aFailedJobIsNeverDone() {
        trace("t1", FakeTrace(fail = true))
        val id = runBlocking { service().start(ReplayRequest(listOf("t1"))) }
        awaitState(id, "CANCELLED")
        assertNull(runBlocking { dao.replay(id)!!.resultJson })
    }

    @Test fun anUnknownTraceWritesNothingAndFreesTheMutex() {
        trace("t1", FakeTrace())
        val s = service()
        assertTrue(runCatching { runBlocking { s.start(ReplayRequest(listOf("nope"))) } }.exceptionOrNull() is ReplayUnknownTrace)
        assertEquals(0, runBlocking { dao.replays(10).size })
        awaitState(runBlocking { s.start(ReplayRequest(listOf("t1"))) }, "DONE")
    }

    @Test fun developerModeOffRefusesToStart() {
        trace("t1", FakeTrace()); dev.value = false
        assertTrue(runCatching { runBlocking { service().start(ReplayRequest(listOf("t1"))) } }.exceptionOrNull() is ReplayDeveloperModeOff)
        assertEquals(0, runBlocking { dao.replays(10).size })
    }
}
