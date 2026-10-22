package com.rokid.context.canvas.plan

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.capture.plan.PlanJson
import com.rokid.context.capture.plan.PlanResolver
import com.rokid.context.capture.replay.ReplayCandidate
import com.rokid.context.capture.replay.ReplayFigures
import com.rokid.context.capture.replay.ReplayReport
import com.rokid.context.capture.sweep.SweepMode
import com.rokid.context.capture.sweep.SweepParam
import com.rokid.context.capture.sweep.SweepSpec
import com.rokid.context.core.model.context.ContextState
import com.rokid.context.memory.db.CanvasLabelEntity
import com.rokid.context.memory.db.CanvasSweepEntity
import com.rokid.context.memory.db.CanvasTraceEntity
import com.rokid.context.memory.db.DaoFence
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.DeletionEpoch
import com.rokid.context.memory.db.TraceSources
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SweepServiceTest {
    private val db = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    private val dao = db.canvasSlice3Dao()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val dev = MutableStateFlow(true)
    private val thermal = MutableStateFlow(false)
    private var generation = 1L
    private val epoch = DeletionEpoch("install", 1, 0)
    private var now = 1_000L
    private val ids = AtomicInteger()
    private val base = PlanResolver.defaults()
    private val gate = kotlinx.coroutines.sync.Mutex()

    @After fun close() { scope.cancel(); db.close() }

    private fun fig(opens: Int, minutes: Double = 10.0, unobs: Int = 0) =
        ReplayFigures("h", opens, opens / minutes, opens * 0.7, opens * 0.7 / minutes, opens * 0.5, opens * 0.5 / minutes, unobs, 0.45, 3.7)

    /** A trace whose candidate opens fall as the REST timer photo gap grows (60 s / gap per minute). [hold] makes it wait, honouring `tick` like the real stream. */
    private inner class FakeTrace(val hold: CountDownLatch? = null, val fail: Boolean = false, rev: Int = 0) : ReplayTrace("abc", rev, 10_000, 20_000) {
        val entered = CountDownLatch(1)
        val seen: MutableList<ReplayCandidate?> = Collections.synchronizedList(ArrayList())
        override fun replay(candidate: ReplayCandidate?, candidateFusion: Map<String, Double>?, tick: () -> Unit): ReplayReport {
            seen += candidate
            entered.countDown()
            if (hold != null) while (!hold.await(5, TimeUnit.MILLISECONDS)) tick()
            tick()
            if (fail) error("boom")
            val gap = candidate?.table?.get(ContextState.REST)?.minSettledCheckMs?.takeIf { it > 0 } ?: 10_000L
            return ReplayReport("COUNTERFACTUAL", 10.0, fig(20), candidate?.let { fig((600_000L / gap).toInt(), unobs = 1) }, 5.0, 1, 0, 0, emptyMap(), false, listOf("replay is approximate"))
        }
    }

    private val loaded = HashMap<String, ReplayTrace>()
    private fun trace(id: String, t: ReplayTrace, rev: Int = 0) {
        runBlocking { dao.acceptTraceFenced(CanvasTraceEntity(id, "IMU", "COMPLETE", "{}", generation, false, rev, null, epoch.installId, epoch.counter, true, 10_000, 20_000, 1), fence()) }
        loaded[id] = t
    }
    private fun fence() = DaoFence(generation, epoch, { generation }, { epoch })
    private fun service() = SweepService(dao, { id -> loaded[id] }, ::fence, scope, dev, thermal, { now }, { "sw_${ids.incrementAndGet()}" }, jobGate = gate)

    private fun gaps(vararg ms: Long) = SweepSpec(SweepMode.GRID, listOf(SweepParam("policy.REST.timerPhotoGapsMs.settledMs", ms.map { JsonPrimitive(it) })))
    private fun <T> wait(f: suspend () -> T?): T = runBlocking { withTimeout(10_000) { var v = f(); while (v == null) { delay(5); v = f() }; v } }
    private fun state(id: String) = runBlocking { dao.sweep(id)!!.jobState }
    private fun awaitState(id: String, s: String) = wait { if (state(id) == s) s else null }
    private fun row(id: String) = runBlocking { dao.sweep(id)!! }
    private fun result(id: String) = Json.parseToJsonElement(row(id).resultJson!!).jsonObject

    @Test fun doneIsCommittedWithEveryPointAndTheObjectives() {
        val t = FakeTrace(); trace("t1", t)
        val id = runBlocking { service().start(SweepRequest(listOf("t1"), gaps(0, 30_000, 300_000), base)) }
        awaitState(id, "DONE")
        val r = result(id)
        assertTrue(r["estimate"]!!.jsonPrimitive.content == "true")
        val pts = r["points"]!!.jsonArray
        assertEquals(3, pts.size)
        val p = pts[2].jsonObject
        assertEquals(300_000L, p["params"]!!.jsonObject["policy.REST.timerPhotoGapsMs.settledMs"]!!.jsonPrimitive.content.toLong())
        assertEquals(2.0 / 10.0, p["opensPerMin"]!!.jsonPrimitive.doubleOrNull!!, 1e-9)           // 600 s / 300 s = 2 opens in 10 min
        assertEquals(1.0 / 10.0, p["capturesPerMin"]!!.jsonPrimitive.doubleOrNull!!, 1e-9)         // the fake keeps half of them
        assertEquals(50.0, p["unobservedOpensPct"]!!.jsonPrimitive.doubleOrNull!!, 1e-9)
        assertNotNull(p["estimatedPctPerMin"]); assertNotNull(p["estimatedHoursFromFull"]); assertEquals(12, p["configHash"]!!.jsonPrimitive.content.length)
        assertEquals(20.0 / 10.0, r["baseline"]!!.jsonObject["opensPerMin"]!!.jsonPrimitive.doubleOrNull!!, 1e-9)
        assertTrue(r["warnings"]!!.jsonArray.any { it.jsonPrimitive.content.startsWith("replay is approximate") })
        assertEquals(PlanJson.configHash(base), row(id).configHash)
        assertEquals("[]", row(id).splitDaysJson)                                          // no holdout in core: "[]"
    }

    /** The sweep replays a 300 s gap: the candidate that reaches the replay carries the table with 300 s. */
    @Test fun sweepGapUpTo300s() {
        val t = FakeTrace(); trace("t1", t)
        val id = runBlocking { service().start(SweepRequest(listOf("t1"), gaps(5_000, 300_000), base)) }
        awaitState(id, "DONE")
        val gapsSeen = t.seen.filterNotNull().map { it.table!![ContextState.REST]!!.minSettledCheckMs }
        assertEquals(listOf(5_000L, 300_000L), gapsSeen)
        assertTrue("one pass per point", t.seen.size == 2)
    }

    @Test fun sweepSensingKeyRefused() {
        trace("t1", FakeTrace())
        val spec = SweepSpec(SweepMode.GRID, listOf(SweepParam("glance.sampleRateHz", listOf(JsonPrimitive(100)))))
        val e = runCatching { runBlocking { service().start(SweepRequest(listOf("t1"), spec, base)) } }.exceptionOrNull()
        assertTrue(e is IllegalArgumentException); assertEquals("sampleRateHz: sensing and detection are not plan parameters", e!!.message)
        assertEquals("nothing is written for a refused request", 0, runBlocking { dao.sweeps(10).size })
        awaitState(runBlocking { service().start(SweepRequest(listOf("t1"), gaps(30_000), base)) }, "DONE")    // and the one-job gate was released
    }

    @Test fun sourcesAndLabelRevAreRecordedAtStart() {
        trace("t1", FakeTrace(rev = 2), rev = 2); trace("t2", FakeTrace())
        runBlocking { dao.bumpLabelRev(); dao.bumpLabelRev(); dao.bumpLabelRev() }
        val id = runBlocking { service().start(SweepRequest(listOf("t1", "t2"), gaps(30_000), base, paramsJson = """{"mode":"grid"}""")) }
        awaitState(id, "DONE")
        val r = row(id)
        val src = TraceSources.decode(r.sourcesJson)!!
        assertEquals(listOf("t1", "t2"), src.map { it.traceId }); assertEquals(2, src[0].revision)
        assertEquals(10_000L, src[0].fromMs); assertEquals(20_000L, src[0].toMs)
        assertEquals(3L, r.labelRev); assertEquals(10_000L, r.fromMs); assertEquals(20_000L, r.toMs)
        assertEquals("""{"mode":"grid"}""", r.paramsJson)
    }

    /** A label write moves labelRev: the sweep that read the old labels is invalidated and its result deleted (S75). */
    @Test fun labelDeleteInvalidatesSweep() {
        trace("t1", FakeTrace())
        runBlocking { dao.insertLabelFenced(CanvasLabelEntity("l1", "MOMENT", "tap", "COMMUTING", 15_000, null, null, "", 3, 1), fence()) }
        val id = runBlocking { service().start(SweepRequest(listOf("t1"), gaps(30_000), base)) }
        awaitState(id, "DONE")
        assertNotNull(row(id).resultJson); assertFalse(row(id).invalidated)
        runBlocking { dao.deleteLabel("l1"); dao.invalidateBySources(emptyList(), null, null, labelRevChanged = true) }
        assertTrue(row(id).invalidated); assertNull(row(id).resultJson)
    }

    @Test fun labelsMovedDuringTheRunDropTheResult() {
        val hold = CountDownLatch(1); val t = FakeTrace(hold); trace("t1", t)
        val id = runBlocking { service().start(SweepRequest(listOf("t1"), gaps(30_000), base)) }
        assertTrue(t.entered.await(5, TimeUnit.SECONDS))
        runBlocking { dao.bumpLabelRev() }
        hold.countDown()
        awaitState(id, "CANCELLED")
        assertNull(row(id).resultJson)
    }

    @Test fun aTombstonedSourceDuringTheRunDropsTheResult() {
        val hold = CountDownLatch(1); val t = FakeTrace(hold); trace("t1", t)
        val id = runBlocking { service().start(SweepRequest(listOf("t1"), gaps(30_000), base)) }
        assertTrue(t.entered.await(5, TimeUnit.SECONDS))
        runBlocking { dao.tombstoneTrace("t1", now) }
        hold.countDown()
        awaitState(id, "CANCELLED")
        assertNull(row(id).resultJson)
    }

    @Test fun aSweepAndAReplayShareTheOneJobGate() {
        val hold = CountDownLatch(1); val t = FakeTrace(hold); trace("t1", t); trace("t2", FakeTrace())
        val s = service()
        val replay = ReplayService(dao, { id -> loaded[id] }, ::fence, scope, dev, thermal, { now }, { "rp_${ids.incrementAndGet()}" }, jobGate = gate)
        val first = runBlocking { s.start(SweepRequest(listOf("t1"), gaps(30_000), base)) }
        assertTrue(t.entered.await(5, TimeUnit.SECONDS))
        assertEquals(first, s.runningId())
        assertTrue(runCatching { runBlocking { s.start(SweepRequest(listOf("t2"), gaps(30_000), base)) } }.exceptionOrNull() is ReplayBusy)
        assertTrue(runCatching { runBlocking { replay.start(ReplayRequest(listOf("t2"))) } }.exceptionOrNull() is ReplayBusy)
        assertEquals(1, runBlocking { dao.sweeps(10).size }); assertEquals(0, runBlocking { dao.replays(10).size })
        hold.countDown(); awaitState(first, "DONE")
        wait { if (s.runningId() == null) 1 else null }
        val rid = runBlocking { replay.start(ReplayRequest(listOf("t2"))) }
        wait { if (runBlocking { dao.replay(rid)!!.jobState } == "DONE") 1 else null }
        wait { if (gate.isLocked) null else 1 }
        awaitState(runBlocking { s.start(SweepRequest(listOf("t2"), gaps(30_000), base)) }, "DONE")
    }

    @Test fun cancelledOnDeveloperModeOffThermalSevereAndShutdown() {
        for (stop in listOf<() -> Unit>({ dev.value = false }, { thermal.value = true })) {
            dev.value = true; thermal.value = false
            val hold = CountDownLatch(1); val t = FakeTrace(hold); trace("t1", t)
            val id = runBlocking { service().start(SweepRequest(listOf("t1"), gaps(30_000), base)) }
            assertTrue(t.entered.await(5, TimeUnit.SECONDS))
            stop()
            awaitState(id, "CANCELLED")
            assertNull(row(id).resultJson)
            wait { if (gate.isLocked) null else 1 }
        }
        dev.value = true; thermal.value = false
        val hold = CountDownLatch(1); val t = FakeTrace(hold); trace("t1", t)
        val s = service()
        val id = runBlocking { s.start(SweepRequest(listOf("t1"), gaps(30_000), base)) }
        assertTrue(t.entered.await(5, TimeUnit.SECONDS))
        runBlocking { s.shutdown() }
        assertEquals("CANCELLED", state(id)); assertNull(s.runningId())
    }

    @Test fun abortedAfterRestart() {
        runBlocking {
            dao.insertSweep(CanvasSweepEntity("dead1", "{}", createdAt = 1))
            dao.insertSweep(CanvasSweepEntity("done1", "{}", resultJson = "{}", jobState = "DONE", createdAt = 3))
        }
        now = 9_000
        assertEquals(1, runBlocking { service().recover() })
        assertEquals("ABORTED", state("dead1")); assertEquals("DONE", state("done1"))
    }

    @Test fun aFailedPointNeverMakesADoneSweep() {
        trace("t1", FakeTrace(fail = true))
        val id = runBlocking { service().start(SweepRequest(listOf("t1"), gaps(30_000), base)) }
        awaitState(id, "CANCELLED")
        assertNull(row(id).resultJson)
    }

    @Test fun anUnknownTraceAndDeveloperModeOffWriteNothing() {
        trace("t1", FakeTrace())
        assertTrue(runCatching { runBlocking { service().start(SweepRequest(listOf("nope"), gaps(30_000), base)) } }.exceptionOrNull() is ReplayUnknownTrace)
        dev.value = false
        assertTrue(runCatching { runBlocking { service().start(SweepRequest(listOf("t1"), gaps(30_000), base)) } }.exceptionOrNull() is ReplayDeveloperModeOff)
        assertEquals(0, runBlocking { dao.sweeps(10).size })
    }

    /** S76: a sweep makes no model call, by construction: nothing in its constructor can reach a model. */
    @Test fun constructorHasNoModelDependency() {
        val forbidden = listOf("RoleRunner", "ModelGateway", "com.rokid.context.model.", "com.rokid.context.ai.run.", "Endpoint", "KeyVault")
        val ctors = SweepService::class.java.declaredConstructors
        assertTrue(ctors.isNotEmpty())
        ctors.forEach { c ->
            c.genericParameterTypes.map { it.typeName }.forEach { n -> forbidden.forEach { f -> assertFalse("constructor takes $n", n.contains(f)) } }
        }
        SweepService::class.java.declaredFields.forEach { f -> forbidden.forEach { x -> assertFalse("field ${f.name}: ${f.genericType.typeName}", f.genericType.typeName.contains(x)) } }
    }
}
