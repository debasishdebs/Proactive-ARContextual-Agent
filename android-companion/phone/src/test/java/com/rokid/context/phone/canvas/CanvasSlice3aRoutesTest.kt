package com.rokid.context.phone.canvas

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.canvas.plan.ReplayService
import com.rokid.context.canvas.trace.CollectSession
import com.rokid.context.memory.db.CanvasTraceEntity
import com.rokid.context.memory.db.DaoFence
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.DeletionEpoch
import com.rokid.context.memory.db.DeletionEpochStore
import com.rokid.context.phone.canvas.api.CanvasApi
import com.rokid.context.phone.canvas.api.CanvasBackend
import com.rokid.context.phone.canvas.server.HttpRequest
import com.rokid.context.phone.hub.plan.PlanPublishPort
import com.rokid.context.phone.hub.plan.PlanSendResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

/** Task 22: the slice 3a route groups are registered in [CanvasApi] through [CanvasExtras], and the trace and collect routes. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class CanvasSlice3aRoutesTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val db = DatabaseFactory.inMemory(ctx)
    private val dao = db.canvasSlice3Dao()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val dev = MutableStateFlow(true)
    private val prefs = ctx.getSharedPreferences("collect_routes_test", Context.MODE_PRIVATE).also { it.edit().clear().commit() }
    private val epochPrefs = ctx.getSharedPreferences("epoch_routes_test", Context.MODE_PRIVATE).also { it.edit().clear().commit() }
    private val epoch = DeletionEpochStore(epochPrefs)
    private var now = 1_000_000L
    private val collect = CollectSession(prefs, epoch) { now }
    private val replay = ReplayService(dao, { null }, { DaoFence(0, DeletionEpoch("i", 1, 0), { 0 }, { DeletionEpoch("i", 1, 0) }) }, scope, dev)
    private val shadowCalls = mutableListOf<String>()
    private val port = object : PlanPublishPort { override suspend fun sendShadow(planJson: String, configHash: String): PlanSendResult { shadowCalls += configHash; return PlanSendResult(1, true) } }
    private var startups = 0

    /** A backend that fails any call: the slice 3a routes must never reach it. */
    private val backend = Proxy.newProxyInstance(CanvasBackend::class.java.classLoader, arrayOf(CanvasBackend::class.java)) { _, m, _ -> error("backend called: ${m.name}") } as CanvasBackend
    private val extras = CanvasExtras.build(dao, replay, dev, startup = { startups++ }, sender = { port }, collect = { collect })
    private val api = CanvasApi(backend, extras = extras.extras)

    @After fun close() { scope.cancel(); db.close(); prefs.edit().clear().commit(); epochPrefs.edit().clear().commit() }

    private fun call(m: String, path: String, body: String = "") = api.handle(HttpRequest(m, path, emptyMap(), emptyMap(), body.toByteArray()))
    private fun json(r: com.rokid.context.phone.canvas.server.HttpResponse) = Json.parseToJsonElement(String(r.body))

    @Test fun routesRegistered() {
        assertEquals(200, call("GET", "/api/traces").status)
        assertEquals(200, call("GET", "/api/replays").status)
        assertEquals(200, call("GET", "/api/collect").status)
        assertEquals("the compile route answers (400: no pipeline), it is not a 404 or 405", 400, call("POST", "/api/plans/compile", "{}").status)
        assertEquals(404, call("GET", "/api/replays/rp_missing").status)
        assertEquals(404, call("GET", "/api/traces/trace-none").status)
        // an unrelated path still reaches the built-in router, not an extra
        val unknown = call("GET", "/api/nothing-here")
        assertEquals(404, unknown.status)
    }

    @Test fun startupHookRuns() { runBlocking { extras.start() }; assertEquals(1, startups) }

    @Test fun collectStartAndStopMoveTheHubsSession() {
        assertFalse(collect.active())
        val r = call("POST", "/api/collect/start", """{"minutes":5}""")
        assertEquals(200, r.status); assertTrue(collect.active())
        assertEquals(now + 5 * 60_000L, json(r).jsonObject["untilMs"]!!.jsonPrimitive.content.toLong())
        assertEquals(400, call("POST", "/api/collect/start", """{"minutes":0}""").status)
        assertEquals(400, call("POST", "/api/collect/start", """{"minutes":"x"}""").status)
        assertTrue("a refused start leaves the session", collect.active())
        assertEquals(200, call("POST", "/api/collect/stop").status); assertFalse(collect.active())
    }

    @Test fun developerModeOffRefusesTracesAndCollect() {
        dev.value = false
        assertEquals(403, call("GET", "/api/traces").status)
        assertEquals(403, call("POST", "/api/collect/start", "{}").status)
        assertFalse(collect.active())
    }

    @Test fun tracesListAndGetHideTombstonedOnes() = runBlocking {
        val t = CanvasTraceEntity(traceId = "trace-aaaa-0001", source = "IMU", status = "COMPLETE", manifestJson = "{}", wipeGen = 0, installId = "i", deletionCounter = 1, startedAtMs = 5, endedAtMs = 9, createdAt = 10)
        dao.acceptTraceFenced(t, DaoFence(0, DeletionEpoch("i", 1, 0), { 0 }, { DeletionEpoch("i", 1, 0) }))
        val list = json(call("GET", "/api/traces")).jsonArray
        assertEquals(listOf("trace-aaaa-0001"), list.map { it.jsonObject["traceId"]!!.jsonPrimitive.content })
        assertFalse("no manifest and no events on the list", list[0].jsonObject.containsKey("manifestJson"))
        assertEquals(200, call("GET", "/api/traces/trace-aaaa-0001").status)
        dao.tombstoneTrace("trace-aaaa-0001", 20)
        assertEquals(0, json(call("GET", "/api/traces")).jsonArray.size)
        assertEquals(404, call("GET", "/api/traces/trace-aaaa-0001").status)
    }

    @Test fun replayStartIsRefusedForAnUnknownTraceAndInDeveloperModeOff() {
        assertEquals(404, call("POST", "/api/replays", """{"traceIds":["trace-none"]}""").status)
        dev.value = false
        assertEquals(403, call("POST", "/api/replays", """{"traceIds":["trace-none"]}""").status)
    }
}
