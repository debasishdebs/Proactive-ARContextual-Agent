package com.rokid.context.phone.canvas.api

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.canvas.plan.ReplayTrace
import com.rokid.context.canvas.plan.SweepService
import com.rokid.context.capture.replay.ReplayCandidate
import com.rokid.context.capture.replay.ReplayFigures
import com.rokid.context.capture.replay.ReplayReport
import com.rokid.context.core.model.context.ContextState
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class SweepRoutesTest {
    private val db = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val dev = MutableStateFlow(true)
    private val epoch = DeletionEpoch("i", 1, 0)
    @After fun close() { scope.cancel(); db.close() }

    private fun fig(opens: Int) = ReplayFigures("h", opens, opens / 10.0, opens * 0.7, opens * 0.07, opens * 0.5, opens * 0.05, 0, 0.45, 3.7)
    private val trace = object : ReplayTrace("abc", 0, 10, 20) {
        override fun replay(candidate: ReplayCandidate?, candidateFusion: Map<String, Double>?, tick: () -> Unit): ReplayReport {
            val gap = candidate?.table?.get(ContextState.REST)?.minSettledCheckMs?.takeIf { it > 0 } ?: 10_000L
            return ReplayReport("COUNTERFACTUAL", 10.0, fig(20), candidate?.let { fig((600_000L / gap).toInt()) }, 0.0, 1, 0, 0, emptyMap(), false, listOf("replay is approximate"))
        }
    }
    private val service = SweepService(db.canvasSlice3Dao(), { if (it == "t1") trace else null }, { DaoFence(1, epoch, { 1 }, { epoch }) }, scope, dev)
    private val routes = SweepRoutes(service)

    private fun req(m: String, path: String, body: String = "", query: Map<String, String> = emptyMap()) = HttpRequest(m, path, query, emptyMap(), body.toByteArray())
    private fun call(m: String, path: String, body: String = "", query: Map<String, String> = emptyMap()) = runBlocking { routes.handle(req(m, path, body, query)) }
    private fun json(r: com.rokid.context.phone.canvas.server.HttpResponse) = Json.parseToJsonElement(String(r.body)).jsonObject

    private fun startDone(body: String): String {
        runBlocking { db.canvasSlice3Dao().acceptTraceFenced(CanvasTraceEntity("t1", "IMU", "COMPLETE", "{}", 1, false, 0, null, "i", 1, true, 10, 20, 1), DaoFence(1, epoch, { 1 }, { epoch })) }
        val r = call("POST", "/api/sweeps", body)!!
        assertEquals(String(r.body), 202, r.status)
        val id = json(r)["sweepId"]!!.jsonPrimitive.content
        runBlocking { withTimeout(10_000) { while (service.get(id)!!.jobState != "DONE") delay(5) } }
        return id
    }

    private val gaps = """{"traceIds":["t1"],"mode":"grid","params":[{"path":"policy.REST.timerPhotoGapsMs.settledMs","values":[30000,120000,300000]}]}"""

    @Test fun startListGetAndSortTheTable() {
        val id = startDone(gaps)
        assertTrue(String(call("GET", "/api/sweeps")!!.body).contains(id))
        fun hours(sort: String, dir: String) = json(call("GET", "/api/sweeps/$id", query = mapOf("sort" to sort, "dir" to dir))!!)["result"]!!.jsonObject["points"]!!.jsonArray
            .map { it.jsonObject["estimatedHoursFromFull"]!!.jsonPrimitive.doubleOrNull!! }
        val asc = hours("hours", "asc"); val desc = hours("hours", "desc")
        assertEquals(3, asc.size); assertEquals(asc.sorted(), asc); assertEquals(asc.reversed(), desc)
        assertTrue(asc.first() < asc.last())
        val byIndex = json(call("GET", "/api/sweeps/$id")!!)["result"]!!.jsonObject["points"]!!.jsonArray.map { it.jsonObject["index"]!!.jsonPrimitive.content.toInt() }
        assertEquals(listOf(0, 1, 2), byIndex)
        assertEquals(true, json(call("GET", "/api/sweeps/$id")!!)["result"]!!.jsonObject["estimate"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(400, call("GET", "/api/sweeps/$id", query = mapOf("sort" to "nope"))!!.status)
    }

    @Test fun rangesAndRandomAreAccepted() {
        startDone("""{"traceIds":["t1"],"mode":"random","samples":4,"seed":3,"params":[{"path":"policy.REST.timerPhotoGapsMs.settledMs","min":10000,"max":60000,"step":10000}]}""")
        assertEquals(202, call("POST", "/api/sweeps", """{"traceIds":["t1"],"mode":"grid","params":[{"path":"gate.hamming","min":6,"max":8,"step":1}],"base":{"gate":{"bigChange":20}}}""")!!.status)
    }

    @Test fun refusalsAreFixedTexts() {
        assertNull(call("GET", "/api/replays"))
        assertEquals(400, call("POST", "/api/sweeps", "not json")!!.status)
        val sensing = call("POST", "/api/sweeps", """{"traceIds":["t1"],"mode":"grid","params":[{"path":"glance.sampleRateHz","values":[100]}]}""")!!
        assertEquals(400, sensing.status); assertTrue(String(sensing.body).contains("sampleRateHz: sensing and detection are not plan parameters"))
        val big = call("POST", "/api/sweeps", """{"traceIds":["t1"],"mode":"grid","params":[{"path":"policy.REST.timerPhotoGapsMs.settledMs","values":[300001]}]}""")!!
        assertEquals(400, big.status); assertTrue(String(big.body).contains("timer photo gaps must be 0"))
        assertEquals(400, call("POST", "/api/sweeps", """{"traceIds":[],"mode":"grid","params":[]}""")!!.status)
        assertEquals(400, call("POST", "/api/sweeps", """{"traceIds":["t1"],"mode":"sideways","params":[]}""")!!.status)
        assertEquals(404, call("POST", "/api/sweeps", gaps.replace("t1", "nope"))!!.status)
        assertEquals(404, call("GET", "/api/sweeps/zzz")!!.status)
        dev.value = false
        assertEquals(403, call("POST", "/api/sweeps", gaps)!!.status)
    }
}
