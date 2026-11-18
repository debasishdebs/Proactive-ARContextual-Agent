package com.rokid.context.phone.canvas.api

import com.rokid.context.orchestration.proactive.Gate
import com.rokid.context.orchestration.proactive.ReplayCandidate
import com.rokid.context.orchestration.proactive.ReplayStep
import com.rokid.context.orchestration.proactive.ReplayStepResult
import com.rokid.context.orchestration.proactive.TriggerKind
import com.rokid.context.phone.canvas.server.HttpRequest
import com.rokid.context.phone.canvas.server.HttpResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy
import java.time.LocalDate

/** `POST /api/proactive/replay`: access is enforced by the route (Developer mode AND the default-OFF flag), validation, the 30 s deadline, read-only, cancellation. */
class ProactiveReplayRouteTest {
    private val dev = MutableStateFlow(true)
    private val flag = MutableStateFlow(true)
    private var calls = 0
    private var last: Triple<LocalDate, Int, List<ReplayStep>>? = null
    private var impl: suspend (LocalDate, Int, List<ReplayStep>) -> List<ReplayStepResult> = { d, s, e -> calls++; last = Triple(d, s, e); listOf(ReplayStepResult(1_000, TriggerKind.TICK, Gate.WOULD_SHOW, listOf(ReplayCandidate("f2:wd:Q1", "f2", 0.74, Gate.WOULD_SHOW, listOf("mem:m1"), "Golconda: last visit 12 Nov.")))) }
    private fun route(deadline: Long = 30_000) = ProactiveReplayRoute(dev, flag, { d, s, e -> impl(d, s, e) }, deadline)
    private fun req(body: String, method: String = "POST", path: String = "/api/proactive/replay") = HttpRequest(method, path, emptyMap(), emptyMap(), body.toByteArray())
    private fun post(body: String, r: ProactiveReplayRoute = route()): HttpResponse = runBlocking { r.handle(req(body))!! }
    private fun obj(r: HttpResponse): JsonObject = Json.parseToJsonElement(String(r.body)).jsonObject
    private val ok = """{"day":"2026-11-18"}"""

    @Test fun accessNeedsDeveloperModeAndTheFlagAndTouchesNothingOtherwise() {
        dev.value = false
        assertEquals(403, post(ok).status); assertEquals("developer_mode_off", obj(post(ok))["error"]!!.jsonPrimitive.content)
        dev.value = true; flag.value = false
        assertEquals("the default-OFF flag alone is not enough", 403, post(ok).status)
        assertEquals(0, calls)
        flag.value = true
        assertEquals(200, post(ok).status); assertEquals(1, calls)
    }

    @Test fun otherPathsAndMethodsAreNotThisRoute() = runBlocking {
        assertNull(route().handle(req(ok, path = "/api/proactive/other")))
        assertEquals(405, route().handle(req(ok, method = "GET"))!!.status)
    }

    @Test fun theAnswerCarriesStepsCandidatesIdsAndTemplateText() {
        val o = obj(post("""{"day":"2026-11-18","stepMin":30,"events":[{"atMs":5,"trigger":"place_arrival"}]}"""))
        assertEquals(30, last!!.second); assertEquals(LocalDate.of(2026, 11, 18), last!!.first); assertEquals(ReplayStep(5, TriggerKind.PLACE_ARRIVAL), last!!.third.single())
        assertEquals(1, o["wouldShow"]!!.jsonPrimitive.content.toInt())
        val c = o["steps"]!!.jsonArray[0].jsonObject["candidates"]!!.jsonArray[0].jsonObject
        assertEquals("WOULD_SHOW", c["verdict"]!!.jsonPrimitive.content); assertEquals("mem:m1", c["sourceIds"]!!.jsonArray[0].jsonPrimitive.content); assertTrue(c["text"]!!.jsonPrimitive.content.startsWith("Golconda"))
        assertTrue(o["notReplayed"]!!.jsonArray.map { it.jsonPrimitive.content }.containsAll(listOf("PAUSE", "ZONE", "WEAR", "DRIVING")))
    }

    @Test fun validation() {
        for (b in listOf("{}", """{"day":"yesterday"}""", """{"day":"2026-13-45"}""", """{"day":"2026-11-18","stepMin":4}""", """{"day":"2026-11-18","stepMin":61}""", """{"day":"2026-11-18","stepMin":"15"}""",
            """{"day":"2026-11-18","events":{}}""", """{"day":"2026-11-18","events":[{"atMs":1,"trigger":"nope"}]}""", """{"day":"2026-11-18","events":[{"trigger":"tick"}]}""", "[1]", "not json"))
            assertEquals(b, 400, post(b).status)
        assertEquals(400, post("""{"day":"2026-11-18","events":[${(1..21).joinToString(",") { """{"atMs":$it,"trigger":"tick"}""" }}]}""").status)
        assertEquals(400, runBlocking { route().handle(HttpRequest("POST", "/api/proactive/replay", emptyMap(), emptyMap(), ByteArray(0), bodyTooLarge = true))!!.status })
        assertEquals(0, calls)
    }

    @Test fun theDeadlineAnswersBusy() {
        impl = { _, _, _ -> delay(5_000); emptyList() }
        assertEquals(503, post(ok, route(deadline = 100)).status)
    }

    @Test fun oneReplayAtATime() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        impl = { _, _, _ -> gate.await(); emptyList() }
        val r = route()
        val first = async { r.handle(req(ok))!! }
        delay(100)
        assertEquals(409, r.handle(req(ok))!!.status)
        gate.complete(Unit); assertEquals(200, first.await().status)
    }

    @Test fun turningDeveloperModeOffCancelsAReplayInFlightAndShowsNothing() = runBlocking {
        val started = CompletableDeferred<Unit>()
        impl = { _, _, _ -> started.complete(Unit); delay(10_000); emptyList() }
        val r = route()
        val running = async { r.handle(req(ok))!! }
        started.await(); dev.value = false
        val res = running.await()
        assertEquals(403, res.status); assertFalse(String(res.body).contains("steps"))
    }

    @Test fun aFailureIsAFixedMessageWithNothingEchoed() {
        impl = { _, _, _ -> throw IllegalStateException("secret place name") }
        val r = post(ok)
        assertEquals(500, r.status); assertFalse(String(r.body).contains("secret"))
    }

    @Test fun theApiTreatsItAsReadOnlyWithTheDeadlineAndDenialHoldsThroughTheApi() = runBlocking {
        val backend = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(CanvasBackend::class.java)) { _, _, _ -> error("the backend is never reached") } as CanvasBackend
        val slow = CanvasApi(backend, callTimeoutMs = 100, extras = listOf(ApiExtra { r -> if (r.path == "/api/proactive/replay") { delay(1_500); HttpResponse.json(200, JsonObject(emptyMap())) } else null }))
        assertEquals("a read-only POST runs under the deadline", 503, slow.handle(req(ok)).status)
        flag.value = false
        val api = CanvasApi(backend, callTimeoutMs = 2_000, extras = listOf(ApiExtra(route()::handle)))
        assertEquals(403, api.handle(req(ok)).status)
    }

    @Test fun theRouteIsRegisteredWhenGivenAndAbsentOtherwise() = runBlocking {
        assertTrue(File("src/main/java/com/rokid/context/phone/canvas/CanvasExtras.kt").readText().contains("proactiveReplay?.let"))
        assertTrue(File("src/main/java/com/rokid/context/phone/canvas/api/CanvasApi.kt").readText().contains("\"/api/proactive/replay\""))
    }
}
