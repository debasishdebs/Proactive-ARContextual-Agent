package com.rokid.context.phone.canvas.api

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.canvas.plan.ReplayService
import com.rokid.context.canvas.plan.ReplayTrace
import com.rokid.context.capture.replay.ReplayCandidate
import com.rokid.context.capture.replay.ReplayFigures
import com.rokid.context.capture.replay.ReplayReport
import com.rokid.context.memory.db.CanvasTraceEntity
import com.rokid.context.memory.db.DaoFence
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.DeletionEpoch
import com.rokid.context.phone.canvas.server.HttpRequest
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class ReplayRoutesTest {
    private val db = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val dev = MutableStateFlow(true)
    private val epoch = DeletionEpoch("i", 1, 0)
    private val hold = CountDownLatch(1)
    @After fun close() { scope.cancel(); db.close() }

    private val seen = java.util.Collections.synchronizedList(ArrayList<ReplayCandidate?>())
    private fun fig() = ReplayFigures("abc", 3, 1.0, 2.1, 0.7, 3.0, 1.0, 0, 0.45, 3.7)
    private val trace = object : ReplayTrace("abc", 0, 10, 20) {
        override fun replay(candidate: ReplayCandidate?, candidateFusion: Map<String, Double>?, tick: () -> Unit): ReplayReport {
            seen += candidate
            while (!hold.await(5, TimeUnit.MILLISECONDS)) tick()
            return ReplayReport("EXACT", 3.0, fig(), null, 0.0, 1, 0, 0, emptyMap(), false, listOf("replay is approximate"))
        }
    }
    private val service = ReplayService(db.canvasSlice3Dao(), { if (it == "t1") trace else null }, { DaoFence(1, epoch, { 1 }, { epoch }) }, scope, dev)
    private val stored = ReplayCandidate("a1b2", gate = com.rokid.context.glasses.capture.keyframe.GateParams(hamming = 9, bigChange = 99))
    private val routes = ReplayRoutes(service) { h -> if (h == "a1b2") stored else if (h == "c0c0") throw com.rokid.context.canvas.plan.AmbiguousConfigHash() else null }

    private fun req(m: String, path: String, body: String = "") = HttpRequest(m, path, emptyMap(), emptyMap(), body.toByteArray())
    private fun call(m: String, path: String, body: String = "") = runBlocking { routes.handle(req(m, path, body)) }

    @Test fun startListGetAndTheOneJobRule() {
        runBlocking { db.canvasSlice3Dao().acceptTraceFenced(CanvasTraceEntity("t1", "IMU", "COMPLETE", "{}", 1, false, 0, null, "i", 1, true, 10, 20, 1), DaoFence(1, epoch, { 1 }, { epoch })) }
        val r = call("POST", "/api/replays", """{"traceIds":["t1"]}""")!!
        assertEquals(202, r.status)
        val id = Regex("\"replayId\":\"([^\"]+)\"").find(String(r.body))!!.groupValues[1]
        assertEquals(409, call("POST", "/api/replays", """{"traceIds":["t1"]}""")!!.status)
        assertTrue(String(call("GET", "/api/replays/$id")!!.body).contains("\"jobState\":\"RUNNING\""))
        hold.countDown()
        runBlocking { withTimeout(10_000) { while (service.get(id)!!.jobState != "DONE") delay(5) } }
        val done = String(call("GET", "/api/replays/$id")!!.body)
        assertTrue(done.contains("\"jobState\":\"DONE\"")); assertTrue(done.contains("\"estimate\":true")); assertTrue(done.contains("replay is approximate"))
        assertTrue(String(call("GET", "/api/replays")!!.body).contains(id))
    }

    @Test fun refusalsAndUnknownPaths() {
        assertNull(call("GET", "/api/frames"))
        assertEquals(400, call("POST", "/api/replays", "not json")!!.status)
        assertEquals(400, call("POST", "/api/replays", """{"traceIds":[]}""")!!.status)
        assertEquals(400, call("POST", "/api/replays", """{"traceIds":["t1"],"candidate":{"configHash":"XYZ"}}""")!!.status)
        assertEquals(404, call("POST", "/api/replays", """{"traceIds":["nope"]}""")!!.status)
        assertEquals(404, call("GET", "/api/replays/zzz")!!.status)
        dev.value = false
        assertEquals(403, call("POST", "/api/replays", """{"traceIds":["t1"]}""")!!.status)
    }

    @Test fun anAmbiguousHashIsATypedConflictNotAGuess() {
        runBlocking { db.canvasSlice3Dao().acceptTraceFenced(CanvasTraceEntity("t1", "IMU", "COMPLETE", "{}", 1, false, 0, null, "i", 1, true, 10, 20, 1), DaoFence(1, epoch, { 1 }, { epoch })) }
        val r = call("POST", "/api/replays", """{"traceIds":["t1"],"candidate":{"configHash":"c0c0"}}""")!!
        assertEquals(409, r.status)
        assertTrue("ambiguous_hash" in String(r.body))
    }

    @Test fun aCandidateIsTheStoredPlanOfItsHashNeverTheCallersFigures() {
        runBlocking { db.canvasSlice3Dao().acceptTraceFenced(CanvasTraceEntity("t1", "IMU", "COMPLETE", "{}", 1, false, 0, null, "i", 1, true, 10, 20, 1), DaoFence(1, epoch, { 1 }, { epoch })) }
        // A hash no stored plan has is refused, whatever gate the caller attaches: a label must be the hash of what was simulated.
        assertEquals(404, call("POST", "/api/replays", """{"traceIds":["t1"],"candidate":{"configHash":"ffff","gate":{"hamming":1}}}""")!!.status)
        assertTrue(seen.isEmpty())
        hold.countDown()
        val r = call("POST", "/api/replays", """{"traceIds":["t1"],"candidate":{"configHash":"a1b2","gate":{"hamming":1,"bigChange":1}}}""")!!
        assertEquals(202, r.status)
        val id = Regex("\"replayId\":\"([^\"]+)\"").find(String(r.body))!!.groupValues[1]
        runBlocking { withTimeout(10_000) { while (service.get(id)!!.jobState != "DONE") delay(5) } }
        assertEquals("the stored plan's candidate is what is simulated", listOf(stored), seen.toList())
        assertEquals(9, seen.single()!!.gate!!.hamming)
    }
}
