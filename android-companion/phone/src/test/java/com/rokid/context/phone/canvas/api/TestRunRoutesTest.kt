package com.rokid.context.phone.canvas.api

import com.rokid.context.canvas.CanvasRunner
import com.rokid.context.canvas.Estimate
import com.rokid.context.canvas.ModelLabel
import com.rokid.context.canvas.window.PickerFrame
import com.rokid.context.phone.canvas.server.ApiHandler
import com.rokid.context.phone.canvas.server.CanvasAuth
import com.rokid.context.phone.canvas.server.CanvasRouter
import com.rokid.context.phone.canvas.server.HostPolicy
import com.rokid.context.phone.canvas.server.AssetSource
import com.rokid.context.phone.canvas.server.HttpRequest
import com.rokid.context.phone.canvas.server.HttpResponse
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The v2.1 Test run routes: flag + Developer gate, the run-start source switch, `e2e`, `runs/tap`, `runs/:id/stop`, fixed refusal tokens, dry-run only, no frame id in the e2e answer, and the router's token. */
class TestRunRoutesTest {
    private var dev = true
    private var flag = true
    private class Fake : TestRunPort {
        var facts: TestRunPort.Facts? = TestRunPort.Facts("hash1", hasOnlineVision = false, hasPyFn = false, endpointLabel = null)
        var frames = (0 until 120).map { PickerFrame("f$it", 1_000_000L + it * 60_000L, dwell = it % 10 == 0, nearDup = false, tombstoned = false, photoPresent = true) }
        var busy = false; var offline = false; var zone = false
        var keeps: List<Long>? = null
        var estimates = 0; var starts = mutableListOf<List<String>>(); var taps = 0; var tapId: String? = null; var stopped = mutableListOf<String>()
        var startBusy = false
        override suspend fun facts(pipelineId: String, version: Int?) = if (pipelineId == "nope") null else facts
        override suspend fun frames(fromMs: Long, toMs: Long) = frames
        override suspend fun traceWindow(traceId: String) = if (traceId == "t1") (1_000_000L to 1_000_000L + 3_600_000L) else null
        override suspend fun keeps(traceId: String) = keeps
        override fun busy() = busy
        override fun offlineOnly() = offline
        override suspend fun zoneHeldOrUnknown() = zone
        override suspend fun estimate(pipelineId: String, version: Int?, frameIds: List<String>): Estimate { estimates++; return Estimate.Ok(frameIds.size, 2, 3, listOf(ModelLabel("n1", "ep", "m")), "tok", null) }
        var asks = mutableListOf<TestRunPort.ReplayAsk?>(); var sourceAlive = true
        override suspend fun replaySourceAvailable(runId: String) = sourceAlive && runId == "src1"
        override suspend fun start(pipelineId: String, version: Int?, frameIds: List<String>, token: String, replay: TestRunPort.ReplayAsk?): String { if (startBusy) throw CanvasRunner.Busy(); starts += frameIds; asks += replay; return "run1" }
        override suspend fun determinism(replayRunId: String, sourceRunId: String) = if (replayRunId == "run1" && sourceRunId == "src1") kotlinx.serialization.json.buildJsonObject { put("line", "3/3 items identical") } else null
        override suspend fun startTap(pipelineId: String, version: Int?, maxMin: Int, maxFrames: Int, maxPhotosSent: Int, token: String): String? { taps++; return tapId }
        override suspend fun stopTap(runId: String): Boolean { stopped += runId; return runId == "run1" }
    }
    private val port = Fake()
    private fun routes() = TestRunRoutes({ dev }, { flag }, port)
    private fun req(path: String, body: String = "{}", method: String = "POST") = HttpRequest(method, path, emptyMap(), emptyMap(), body.toByteArray())
    private fun call(path: String, body: String = "{}", method: String = "POST"): HttpResponse? = runBlocking { routes().handle(req(path, body, method)) }
    private fun obj(r: HttpResponse?): JsonObject = Json.parseToJsonElement(String(r!!.body)).jsonObject
    private val win = """{"pipelineId":"p1","source":{"kind":"window","from":1000000,"to":4600000,"mode":"even","n":10}}"""

    @Test fun flagAndDeveloperModeGateEveryRouteAndTouchNothing() {
        for (f in listOf(false, true)) for (d in listOf(false, true)) {
            if (f && d) continue
            flag = f; dev = d
            for ((p, b) in listOf("/api/runs/estimate" to win, "/api/runs" to win, "/api/e2e" to win, "/api/runs/tap" to """{"pipelineId":"p1"}""", "/api/runs/run1/stop" to "")) {
                val r = call(p, b)!!
                assertEquals("$p dev=$d flag=$f", 403, r.status); assertEquals("developer_mode_off", obj(r)["error"]!!.jsonPrimitive.content)
            }
        }
        assertEquals(0, port.estimates + port.starts.size + port.taps + port.stopped.size)
    }

    @Test fun aBodyWithoutSourceIsNotOurs() {
        assertNull(call("/api/runs/estimate", """{"pipelineId":"p1","frameIds":["a"]}"""))
        assertNull(call("/api/runs", """{"pipelineId":"p1","frameIds":["a"],"confirmToken":"x"}"""))
        assertNull(call("/api/runs/estimate", "not json"))
        assertNull(call("/api/pipelines"))
        assertNull(call("/api/runs/abc", method = "DELETE"))
        assertEquals("even with the flag off a plain run is untouched", 0, port.estimates)
        flag = false
        assertNull(call("/api/runs/estimate", """{"pipelineId":"p1","frameIds":["a"]}"""))
    }

    @Test fun windowEstimatePicksDeterministicallyAndSaysWhatLeaves() {
        val a = obj(call("/api/runs/estimate", win)); val b = obj(call("/api/runs/estimate", win))
        assertEquals(a, b)
        assertEquals("10", a["picked"]!!.jsonPrimitive.content); assertEquals("60", a["of"]!!.jsonPrimitive.content); assertEquals("picked 10 of 60", a["note"]!!.jsonPrimitive.content)
        assertEquals("tok", a["confirmToken"]!!.jsonPrimitive.content)
        assertEquals("0", a["photosLeave"]!!.jsonObject["count"]!!.jsonPrimitive.content)
        port.facts = port.facts!!.copy(hasOnlineVision = true, endpointLabel = "Azure")
        val c = obj(call("/api/runs/estimate", win))
        assertEquals("10", c["photosLeave"]!!.jsonObject["count"]!!.jsonPrimitive.content); assertEquals("Azure", c["photosLeave"]!!.jsonObject["endpoint"]!!.jsonPrimitive.content)
    }

    @Test fun startUsesTheSamePickAsTheEstimateAndIsADryRunStart() {
        val est = obj(call("/api/runs/estimate", win))
        val r = call("/api/runs", win.dropLast(1) + ""","confirmToken":"tok"}""")!!
        assertEquals(200, r.status); assertEquals("run1", obj(r)["runId"]!!.jsonPrimitive.content)
        assertEquals(10, port.starts.single().size)
        assertEquals(est["picked"], obj(r)["picked"])
        assertEquals("a start needs the token", 400, call("/api/runs", win)!!.status)
        port.startBusy = true
        assertEquals(409, call("/api/runs", win.dropLast(1) + ""","confirmToken":"tok"}""")!!.status)
    }

    @Test fun traceSourceUsesTheTraceWindowAndUnknownTraceIs404() {
        val ok = """{"pipelineId":"p1","source":{"kind":"trace","traceId":"t1","mode":"dwell","n":5}}"""
        assertEquals("5", obj(call("/api/runs/estimate", ok))["picked"]!!.jsonPrimitive.content)
        assertEquals(404, call("/api/runs/estimate", ok.replace("t1", "zz"))!!.status)
        assertEquals(404, call("/api/runs/estimate", win.replace("p1", "nope"))!!.status)
    }

    @Test fun policyRefusalsAreFixedTokensAndRefuseBeforeAnyRead() {
        fun reason(b: String = win): String = obj(call("/api/runs/estimate", b))["problems"]!!.jsonArray.single().jsonPrimitive.content
        port.zone = true; assertEquals("zone_held", reason()); port.zone = false
        port.facts = port.facts!!.copy(hasOnlineVision = true); port.offline = true; assertEquals("offline_only", reason()); port.offline = false
        assertEquals("bad_count", reason(win.replace("\"n\":10", "\"n\":51")))
        assertEquals("bad_window", reason(win.replace("\"to\":4600000", "\"to\":1000000")))
        assertEquals("window_too_long", reason(win.replace("\"to\":4600000", "\"to\":99999999999")))
        port.busy = true; assertEquals(409, call("/api/runs/estimate", win)!!.status); port.busy = false
        assertEquals("a refusal never prices or starts anything", 0, port.estimates + port.starts.size)
        port.frames = emptyList(); assertEquals("no_frames", reason())
    }

    @Test fun badBodiesAre400() {
        for (b in listOf("""{"pipelineId":"p1","source":{"kind":"window","from":1,"to":9,"mode":"x","n":1}}""", """{"pipelineId":"p1","source":{"kind":"zzz","mode":"even","n":1}}""",
            """{"pipelineId":"../x","source":{"kind":"window","from":1,"to":9,"mode":"even","n":1}}""", """{"pipelineId":"p1","source":"window"}""", """{"pipelineId":"p1","source":{"kind":"window","from":"a","to":9,"mode":"even","n":1}}"""))
            assertEquals(b, 400, call("/api/runs/estimate", b)!!.status)
        assertEquals(400, call("/api/e2e", "[1]")!!.status)
        assertEquals(400, runBlocking { routes().handle(HttpRequest("POST", "/api/e2e", emptyMap(), emptyMap(), ByteArray(0), bodyTooLarge = true))!!.status })
        assertEquals(405, call("/api/e2e", method = "GET")!!.status)
    }

    @Test fun e2eReturnsCountsOnlyAndNeverAFrameIdAndStartsOnlyWithAToken() {
        val body = """{"pipelineId":"p1","source":{"kind":"trace","traceId":"t1","mode":"even","n":10}}"""
        var r = obj(call("/api/e2e", body)); assertEquals("not_available", r["glassesLane"]!!.jsonPrimitive.content); assertFalse(r.containsKey("runId"))
        port.keeps = listOf(1_000_000L, 1_060_500L, 2_530_000L)
        r = obj(call("/api/e2e", body)); assertEquals("counts", r["glassesLane"]!!.jsonPrimitive.content)
        val j = r["join"]!!.jsonObject
        assertEquals("2", j["matched"]!!.jsonPrimitive.content); assertEquals("1", j["notStored"]!!.jsonPrimitive.content)
        assertFalse("no frame id in the answer", String(call("/api/e2e", body)!!.body).contains("\"f1\""))
        assertEquals(0, port.starts.size)
        r = obj(call("/api/e2e", body.dropLast(1) + ""","confirmToken":"tok"}""")); assertEquals("run1", r["runId"]!!.jsonPrimitive.content); assertEquals(1, port.starts.size)
        assertEquals("no_trace", obj(call("/api/e2e", win))["glassesLane"]!!.jsonPrimitive.content)
    }

    @Test fun tapIsBoundedFailsClosedAndIs501WithoutADriver() {
        fun tap(b: String) = call("/api/runs/tap", b)!!
        assertEquals(501, tap("""{"pipelineId":"p1","confirmToken":"t"}""").status)
        assertEquals("the bounds were checked first, the driver asked once", 1, port.taps)
        assertEquals(422, tap("""{"pipelineId":"p1","maxMin":16,"confirmToken":"t"}""").status)
        assertEquals(422, tap("""{"pipelineId":"p1","maxFrames":51,"confirmToken":"t"}""").status)
        assertEquals(1, port.taps)
        port.facts = port.facts!!.copy(hasPyFn = true); assertEquals("tap_py_fn", obj(tap("""{"pipelineId":"p1","confirmToken":"t"}"""))["problems"]!!.jsonArray.single().jsonPrimitive.content)
        port.facts = port.facts!!.copy(hasPyFn = false, hasOnlineVision = true); assertEquals("tap_no_photo_budget", obj(tap("""{"pipelineId":"p1","confirmToken":"t"}"""))["problems"]!!.jsonArray.single().jsonPrimitive.content)
        port.offline = true; assertEquals("offline_only", obj(tap("""{"pipelineId":"p1","maxPhotosSent":2,"confirmToken":"t"}"""))["problems"]!!.jsonArray.single().jsonPrimitive.content); port.offline = false
        port.zone = true; assertEquals("zone_held", obj(tap("""{"pipelineId":"p1","maxPhotosSent":2,"confirmToken":"t"}"""))["problems"]!!.jsonArray.single().jsonPrimitive.content); port.zone = false
        port.busy = true; assertEquals(409, tap("""{"pipelineId":"p1","maxPhotosSent":2,"confirmToken":"t"}""").status); port.busy = false
        port.facts = port.facts!!.copy(hasOnlineVision = false); port.tapId = "run1"
        val ok = obj(tap("""{"pipelineId":"p1","confirmToken":"t"}"""))
        assertEquals("run1", ok["runId"]!!.jsonPrimitive.content); assertEquals("5", ok["maxMin"]!!.jsonPrimitive.content); assertEquals("0", ok["maxPhotosSent"]!!.jsonPrimitive.content)
        assertEquals(400, tap("""{"pipelineId":"p1"}""").status)
    }

    private fun withToken(extra: String) = win.dropLast(1) + ""","confirmToken":"tok",$extra}"""

    @Test fun replayAndSeedReachThePortAndAVanishedSourceIsRefusedBeforeAnyStart() {
        assertEquals(200, call("/api/runs", withToken(""""replay":{"fromRunId":"src1","onMiss":"live"},"modelSeed":7"""))!!.status)
        assertEquals(TestRunPort.ReplayAsk("src1", true, 7), port.asks.single())
        port.asks.clear()
        call("/api/runs", withToken(""""replay":{"fromRunId":"src1"}"""))
        assertEquals("the default miss is an item error, never a live call", TestRunPort.ReplayAsk("src1", false, null), port.asks.single())
        port.asks.clear()
        call("/api/runs", withToken(""""modelSeed":9"""))
        assertEquals(TestRunPort.ReplayAsk(null, false, 9), port.asks.single())
        port.asks.clear()
        call("/api/runs", win.dropLast(1) + ""","confirmToken":"tok"}""")
        assertNull("a plain test run asks for neither", port.asks.single())
        val n = port.starts.size
        port.sourceAlive = false
        val r = call("/api/runs", withToken(""""replay":{"fromRunId":"src1"}"""))!!
        assertEquals(422, r.status); assertEquals("replay_source_gone", obj(r)["problems"]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals("nothing started", n, port.starts.size)
        port.sourceAlive = true
        for (b in listOf(""""replay":{"fromRunId":"src1","onMiss":"maybe"}""", """"replay":{}""", """"replay":"src1"""", """"modelSeed":0""", """"modelSeed":"7""""))
            assertEquals(b, 400, call("/api/runs", withToken(b))!!.status)
        val e = obj(call("/api/e2e", withToken(""""replay":{"fromRunId":"src1"}"""))); assertEquals("run1", e["runId"]!!.jsonPrimitive.content)
    }

    @Test fun determinismIsAGatedReadOfTwoRuns() {
        fun get(p: String, q: Map<String, String>) = runBlocking { routes().handle(HttpRequest("GET", p, q, emptyMap(), ByteArray(0))) }!!
        assertEquals("3/3 items identical", obj(get("/api/runs/run1/determinism", mapOf("against" to "src1")))["line"]!!.jsonPrimitive.content)
        assertEquals(404, get("/api/runs/run2/determinism", mapOf("against" to "src1")).status)
        assertEquals(400, get("/api/runs/run1/determinism", emptyMap()).status)
        assertEquals(405, call("/api/runs/run1/determinism", "{}")!!.status)
        flag = false
        assertEquals(403, get("/api/runs/run1/determinism", mapOf("against" to "src1")).status)
    }

    @Test fun stopEndsATapAndUnknownIs404() {
        assertEquals(200, call("/api/runs/run1/stop", "")!!.status)
        assertEquals(404, call("/api/runs/other/stop", "")!!.status)
        assertEquals(405, call("/api/runs/run1/stop", method = "GET")!!.status)
        assertNull(call("/api/runs/run1/cancel", ""))
    }

    @Test fun theRouterNeedsTheBearerTokenBeforeAnyOfThese() {
        val auth = CanvasAuth(clock = { 1_000_000L })
        val router = CanvasRouter(auth, HostPolicy(port = { 8765 }, lanIp = { null }), ApiHandler { r -> runBlocking { routes().handle(r) } ?: HttpResponse.error(404, "not_found", "Not found.") }, AssetSource { null }) { }
        fun go(path: String, token: String?) = router.handle(HttpRequest("POST", path, emptyMap(), buildMap { put("host", "127.0.0.1:8765"); token?.let { put("authorization", "Bearer $it") } }, win.toByteArray()))
        for (p in listOf("/api/e2e", "/api/runs/tap", "/api/runs/estimate", "/api/runs/x/stop")) assertEquals(p, 401, go(p, null).status)
        assertEquals(0, port.estimates)
        assertEquals(200, go("/api/runs/estimate", auth.newWebViewToken()).status)
        assertTrue(port.estimates == 1)
    }
}
