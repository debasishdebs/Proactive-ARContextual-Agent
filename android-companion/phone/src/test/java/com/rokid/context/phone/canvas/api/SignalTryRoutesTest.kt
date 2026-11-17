package com.rokid.context.phone.canvas.api

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.ai.canvas.plan.SignalConfig
import com.rokid.context.canvas.signal.SignalTryReader
import com.rokid.context.canvas.signal.ZoneStatus
import com.rokid.context.memory.db.CanvasTraceEntity
import com.rokid.context.memory.db.DaoFence
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.DeletionEpoch
import com.rokid.context.memory.db.FrameEntity
import com.rokid.context.memory.db.FrameMetaEntity
import com.rokid.context.memory.db.MomentEntity
import com.rokid.context.memory.db.MomentPartEntity
import com.rokid.context.memory.db.MomentPartKind
import com.rokid.context.memory.db.MomentSensorEntity
import com.rokid.context.memory.db.TraceClock
import com.rokid.context.memory.forget.lifecycle.Family
import com.rokid.context.memory.forget.lifecycle.Leases
import com.rokid.context.memory.forget.lifecycle.Ref
import com.rokid.context.memory.moment.MomentId
import com.rokid.context.memory.moment.MomentType
import com.rokid.context.orchestration.DataOpGate
import com.rokid.context.phone.canvas.server.HttpRequest
import com.rokid.context.phone.canvas.server.HttpResponse
import com.rokid.context.phone.ui.settings.capture.SignalConfigStore
import com.rokid.context.provenance.ProvenanceGate
import com.rokid.context.work.budget.PhoneThermal
import com.rokid.context.work.budget.ThermalStatusProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** v1.2 row 2 (T4): `POST /api/signals/try`. Backend access enforcement (Developer mode AND the flag), cancellation, limits, refusals, ids only, and that nothing is written. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class SignalTryRoutesTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val db = DatabaseFactory.inMemory(app)
    private val md = db.momentDao()
    private val s3 = db.canvasSlice3Dao()
    private val base = 1_761_000_000L
    private val epoch = DeletionEpoch("install-a", 3, 1_000)
    private fun fence() = DaoFence(5, epoch, { 5 }, { epoch })
    private val leases = Leases()
    private val gate = DataOpGate(waitMs = 100, leases = leases)
    private val dev = MutableStateFlow(true)
    private val flag = MutableStateFlow(true)
    private var thermal = PhoneThermal.NONE
    private var clock = 1_000_000L
    private var zone: (Long) -> ZoneStatus = { ZoneStatus.CLEAR }
    private var zoneCalls = 0
    private val prefs = app.getSharedPreferences("sigtry-test", 0).also { it.edit().clear().commit() }
    private val store = SignalConfigStore(prefs)
    private fun reader() = SignalTryReader(db, gate, ProvenanceGate(db), { zoneCalls++; zone(it) }, { 5L }, { base + 5_000 })
    private fun tryIt() = SignalTry(reader(), { store.config.value }, dev, flag, ThermalStatusProvider { thermal }, { clock })
    private fun routes(t: SignalTry = tryIt()) = SignalRoutes(store, t)
    @After fun close() = db.close()

    private val fast = """{"steps":[{"name":"fast","inputs":[{"name":"speed","sig":"gyroMax"}],"nodes":[{"id":"limit","op":"const","value":10},{"id":"over","op":"cmp","f":">"}],"wires":[["speed","over.a"],["limit","over.b"]],"out":"over"}],"out":"fast"}"""
    private val twice = """{"steps":[{"name":"twice","inputs":[{"name":"g","sig":"gyroMax"},{"name":"two","const":2}],"nodes":[{"id":"m","op":"arith","f":"*"}],"wires":[["g","m.a"],["two","m.b"]],"out":"m"}],"out":"twice"}"""
    private val unknownSig = """{"steps":[{"name":"a","inputs":[{"name":"x","sig":"nope"}],"nodes":[],"wires":[],"out":"x"}],"out":"a"}"""

    private fun id(i: Int) = MomentId.format(base + i)
    private fun row(i: Int) = MomentSensorEntity(id(i), base + i, "t1", 1, TraceClock.OFFSET_ESTIMATED, 40, i.toDouble(), 0.5, 0.6, 0.25, 0.125, 1.5, 0.75, "WALKING", "REST", 50, 40)
    private suspend fun seed(n: Int = 700, photos: Set<Int> = setOf(100, 200)) {
        s3.acceptTraceFenced(CanvasTraceEntity("t1", "IMU", "COMPLETE", "{}", 5, false, 1, null, epoch.installId, epoch.counter, true, base * 1000, (base + n) * 1000, 1, base * 1000, (base + n) * 1000, TraceClock.OFFSET_ESTIMATED, 40), fence())
        for (i in 0 until n) {
            md.replace(MomentEntity(id(i), 1, 1, "h", 1, MomentType.FRAME), if (i in photos) listOf(MomentPartEntity(id(i), MomentPartKind.FRAME, "secretframe$i", "r", (base + i) * 1000)) else emptyList())
            if (i in photos) { db.frameDao().insertIgnore(FrameEntity("secretframe$i", 1, "/data/secretpath$i.jpg", "rokid_glasses")); db.frameMetaDao().upsert(FrameMetaEntity("secretframe$i", null, null, "STILL", false, null, null, null, 640, 0.3f, null, null, null, "s1", 1)) }
            md.replaceSensor(id(i), listOf(row(i)), 1, "build")
        }
    }

    private fun req(body: String, method: String = "POST", path: String = "/api/signals/try") = HttpRequest(method, path, emptyMap(), emptyMap(), body.toByteArray())
    private fun post(body: String, r: SignalRoutes = routes()): HttpResponse = runBlocking { r.handle(req(body))!! }
    private fun body(graph: String, extra: String = "") = """{"graph":$graph$extra}"""
    private fun obj(r: HttpResponse): JsonObject = Json.parseToJsonElement(String(r.body)).jsonObject
    private fun err(r: HttpResponse) = obj(r)["error"]!!.jsonPrimitive.content

    // ---- access: enforced by the route, not the UI ----

    @Test fun developerModeOffAndTheFlagOffAreForbiddenAndTouchNothing() = runBlocking {
        seed()
        dev.value = false
        assertEquals(403, post(body(fast)).status); assertEquals("developer_mode_off", err(post(body(fast))))
        dev.value = true; flag.value = false
        assertEquals("a default-OFF flag alone is not enough", 403, post(body(fast)).status)
        assertEquals(0, zoneCalls); assertEquals(0, leases.openCount)
        flag.value = true
        assertEquals(200, post(body(fast)).status)
    }

    @Test fun theRouteIsReadOnlyInTheApiWithA30sDeadlineAndDenialHoldsThroughTheApi() = runBlocking {
        val backend = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(CanvasBackend::class.java)) { _, _, _ -> error("the backend is never reached") } as CanvasBackend
        val slow = CanvasApi(backend, callTimeoutMs = 100, extras = listOf(ApiExtra { r -> if (r.path == "/api/signals/try") { kotlinx.coroutines.delay(1_500); HttpResponse.json(200, JsonObject(emptyMap())) } else null }))
        assertEquals("a read-only POST runs under the deadline", 503, slow.handle(req("{}")).status)
        dev.value = false
        val api = CanvasApi(backend, callTimeoutMs = 2_000, extras = listOf(ApiExtra(routes()::handle)))
        assertEquals(403, api.handle(req(body(fast))).status)
    }

    @Test fun disablingWhileATryRunsCancelsItAndShowsNothing() = runBlocking {
        seed()
        zone = { dev.value = false; ZoneStatus.CLEAR }   // Developer mode goes off during the read
        val r = post(body(fast))
        assertEquals(403, r.status); assertFalse(String(r.body).contains("summary")); assertEquals(0, leases.openCount)
        dev.value = true; flag.value = true; clock += 10_000
        zone = { flag.value = false; ZoneStatus.CLEAR }
        assertEquals(403, post(body(fast)).status)
    }

    // ---- request validation ----

    @Test fun badBodiesAreTypedFourHundreds() {
        assertEquals("bad_request", err(post("not json"))); assertEquals(400, post("[1]").status)
        assertEquals(400, post("""{"seconds":600}""").status)
        assertEquals("bad_value", err(post(body(fast, ""","seconds":77"""))))
        assertEquals("bad_value", err(post(body(fast, ""","seconds":"600""""))))
        assertEquals("bad_value", err(post(body(fast, ""","seconds":3600"""))))
        val big = body(fast, ""","pad":"${"x".repeat(70_000)}"""")
        assertEquals("too_large", err(post(big)))
        assertEquals(405, runBlocking { routes().handle(req("{}", "GET"))!!.status })
    }

    @Test fun anInvalidDefinitionAnswersProblemsAndSignalChecksDoNotLeakTheGraph() = runBlocking {
        seed()
        val o = obj(post(body(unknownSig)))
        assertEquals(false, o["ok"]!!.jsonPrimitive.content.toBoolean()); assertTrue(o["problems"]!!.jsonArray.isNotEmpty()); assertEquals(0, zoneCalls)
    }

    // ---- the answer ----

    @Test fun aGuardOverTheLatestRecordingReportsCountsDenominatorsAndIdsOnly() = runBlocking {
        seed()
        val r = post(body(fast, ""","seconds":300"""))
        assertEquals(String(r.body), 200, r.status)
        val o = obj(r); val text = String(r.body)
        assertEquals("guard", o["role"]!!.jsonPrimitive.content); assertEquals("Latest imported recording", o["source"]!!.jsonPrimitive.content)
        assertEquals("Raw expression output", o["output"]!!.jsonPrimitive.content)
        val w = o["window"]!!.jsonObject
        assertEquals(base + 699, w["toS"]!!.jsonPrimitive.content.toLong()); assertEquals(300, w["seconds"]!!.jsonPrimitive.content.toInt()); assertEquals(300, w["covered"]!!.jsonPrimitive.content.toInt()); assertEquals(0, w["missing"]!!.jsonPrimitive.content.toInt())
        val sm = o["summary"]!!.jsonObject
        assertEquals(300, sm["valid"]!!.jsonPrimitive.content.toInt()); assertEquals(300, sm["denominator"]!!.jsonPrimitive.content.toInt())
        assertEquals(1.0, sm["trueFrac"]!!.jsonPrimitive.content.toDouble(), 0.0)   // gyroMax = i >= 400 > 10
        assertEquals(300, o["points"]!!.jsonArray.size); assertEquals(700, o["recording"]!!.jsonObject["coverageSeconds"]!!.jsonPrimitive.content.toInt())
        assertTrue("a Moment is listed by id and second only", o["moments"]!!.jsonArray.isEmpty())   // photos at 100 and 200 are before the window
        assertFalse(text.contains("secretframe")); assertFalse(text.contains("secretpath")); assertFalse(text.contains("\"frame"))
    }

    @Test fun momentsInTheWindowAreListedByIdAndSecond() = runBlocking {
        seed(photos = setOf(450, 500))
        val o = obj(post(body(fast, ""","seconds":600""")))
        assertEquals(listOf(id(450), id(500)), o["moments"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content })
        assertEquals(setOf("id", "s"), o["moments"]!!.jsonArray[0].jsonObject.keys)
    }

    @Test fun aValueIsRawMinMaxMeanWithNoAppliedField() = runBlocking {
        seed()
        val o = obj(post(body(twice, ""","seconds":300""")))
        assertEquals("value", o["role"]!!.jsonPrimitive.content)
        val sm = o["summary"]!!.jsonObject
        assertEquals(2.0 * 400, sm["min"]!!.jsonPrimitive.content.toDouble(), 0.0); assertEquals(2.0 * 699, sm["max"]!!.jsonPrimitive.content.toDouble(), 0.0)
        assertFalse(String(post(body(twice)).body).contains("applied"))
    }

    @Test fun unknownZoneHistoryIsHonestlyUnavailable() = runBlocking {
        seed()
        zone = { ZoneStatus.UNKNOWN }
        val o = obj(post(body(fast)))
        assertEquals(false, o["available"]!!.jsonPrimitive.content.toBoolean()); assertEquals("no_zone_history", o["reason"]!!.jsonPrimitive.content)
        assertEquals("Preview unavailable: no zone history.", o["message"]!!.jsonPrimitive.content); assertNull(o["summary"]); assertNull(o["points"])
        // production wiring (no history at all) is the same refusal
        val prod = SignalTry(SignalTryReader(db, gate, ProvenanceGate(db), generation = { 5L }, nowS = { base + 5_000 }), { store.config.value }, dev, flag, clock = { clock + 50_000 })
        assertEquals("no_zone_history", obj(post(body(fast), routes(prod)))["reason"]!!.jsonPrimitive.content)
    }

    @Test fun zoneHeldSecondsAreCountedAsExcludedNotEvaluated() = runBlocking {
        seed()
        zone = { s -> if (s >= base + 650) ZoneStatus.HELD else ZoneStatus.CLEAR }
        val w = obj(post(body(fast, ""","seconds":300""")))["window"]!!.jsonObject
        assertEquals(50, w["excludedZoneHeld"]!!.jsonPrimitive.content.toInt()); assertEquals(250, w["covered"]!!.jsonPrimitive.content.toInt()); assertEquals(50, w["missing"]!!.jsonPrimitive.content.toInt())
    }

    @Test fun noRecordingAnswersNoSensorData() {
        val o = obj(post(body(fast)))
        assertEquals("no_sensor_data", o["reason"]!!.jsonPrimitive.content); assertTrue(o["message"]!!.jsonPrimitive.content.startsWith("No recorded motion data yet"))
    }

    // ---- refusals ----

    @Test fun thermalSevereIsRefusedModerateIsNot() = runBlocking {
        seed()
        thermal = PhoneThermal.SEVERE; assertEquals(503, post(body(fast)).status)
        thermal = PhoneThermal.MODERATE; assertEquals(200, post(body(fast)).status)
    }

    @Test fun theSecondTryWithinTwoSecondsIsTooSoon() = runBlocking {
        seed()
        val r = routes()
        assertEquals(200, post(body(fast), r).status)
        assertEquals(429, post(body(fast), r).status); assertEquals("too_soon", err(post(body(fast), r)))
        clock += 2_000; assertEquals(200, post(body(fast), r).status)
    }

    @Test fun aTryWhileAnotherRunsIsBusy() = runBlocking {
        seed()
        val inside = CountDownLatch(1); val go = CountDownLatch(1)
        zone = { inside.countDown(); go.await(10, TimeUnit.SECONDS); ZoneStatus.CLEAR }
        val r = routes()
        val first = async(kotlinx.coroutines.Dispatchers.IO) { post(body(fast), r) }
        assertTrue(inside.await(10, TimeUnit.SECONDS))
        clock += 10_000
        assertEquals(409, post(body(fast), r).status)
        go.countDown()
        assertEquals(200, first.await().status)
    }

    @Test fun aForgetDuringTheReadShowsNothing() = runBlocking {
        seed()
        zone = { leases.revoke(listOf(Ref(Family.TRACE, "t1"))); ZoneStatus.CLEAR }
        val r = post(body(fast))
        assertEquals(409, r.status); assertEquals("forgotten", err(r)); assertFalse(String(r.body).contains("summary"))
    }

    @Test fun aRunningDeleteIsBusy() = runBlocking {
        seed()
        val inside = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val del = async { gate.delete { inside.complete(Unit); release.await() } }
        inside.await()
        assertEquals(409, post(body(fast)).status)
        release.complete(Unit); del.await()
    }

    @Test fun withoutTheReaderTheRouteIsAbsent() {
        assertEquals(404, runBlocking { SignalRoutes(store).handle(req(body(fast)))!!.status })
    }

    @Test fun theSignalListSaysWhetherThePreviewIsAvailable() {
        val r = routes()
        assertEquals(true, runBlocking { Json.parseToJsonElement(String(r.handle(req("", "GET", "/api/signals"))!!.body)).jsonObject["tryIt"]!!.jsonObject["available"]!!.jsonPrimitive.content.toBoolean() })
        flag.value = false
        assertEquals(false, runBlocking { Json.parseToJsonElement(String(r.handle(req("", "GET", "/api/signals"))!!.body)).jsonObject["tryIt"]!!.jsonObject["available"]!!.jsonPrimitive.content.toBoolean() })
    }
}
