package com.rokid.context.phone.canvas.api

import com.rokid.context.canvas.CanvasPublisher
import com.rokid.context.canvas.CanvasRepository
import com.rokid.context.canvas.CanvasRunner
import com.rokid.context.canvas.AbStarted
import com.rokid.context.canvas.AskDebug
import com.rokid.context.canvas.Estimate
import com.rokid.context.canvas.TokenEstimate
import com.rokid.context.canvas.LiveMode
import com.rokid.context.canvas.ModelLabel
import com.rokid.context.ai.contract.PromptKey
import com.rokid.context.phone.canvas.server.HttpRequest
import com.rokid.context.phone.canvas.server.HttpResponse
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeBackend : CanvasBackend {
    val calls = mutableListOf<String>()
    var saveResult: CanvasRepository.SaveResult = CanvasRepository.SaveResult.Saved(4, listOf("p"))
    var publishOutcome: CanvasPublisher.Outcome = CanvasPublisher.Outcome.Published(7, listOf(PromptKey.CRITIC))
    var rollbackOutcome: CanvasPublisher.Outcome = CanvasPublisher.Outcome.Published(7, listOf(PromptKey.CRITIC))
    var estimateResult: Estimate = Estimate.Ok(2, 3, 5, listOf(ModelLabel("extract", "az", "gpt")), "tok1")
    var startThrows: Exception? = null
    var deleteResult = true
    var cancelResult = true
    var known = true
    var image: ByteArray? = byteArrayOf(1, 2, 3)
    var delayMs = 0L
    var lastFrames: List<Any?>? = null
    var lastEstimateIds: List<String> = emptyList()
    var lastScore: List<Any>? = null

    private suspend fun enter(name: String) { calls += name; if (delayMs > 0) delay(delayMs) }
    private fun <T> known(v: T): T = if (known) v else throw NoSuchElementException("x")

    override suspend fun session(token: String) = buildJsonObject { enter("session"); put("lan", false); put("sawToken", token) }
    override suspend fun logout(token: String) { enter("logout") }
    override fun nodeTypes() = JsonArray(listOf(buildJsonObject { put("type", "input.photos") }))
    override suspend fun pipelines(): JsonArray { enter("pipelines"); return JsonArray(listOf(buildJsonObject { put("id", "live-extract") })) }
    override suspend fun pipeline(id: String): JsonObject? { enter("pipeline"); return if (known) buildJsonObject { put("id", id) } else null }
    override suspend fun duplicate(from: String, name: String): String { enter("duplicate:$from:$name"); return known("p-new") }
    override suspend fun saveDraft(id: String, json: String, rev: Int): CanvasRepository.SaveResult { enter("save:$rev:${json.length}"); known(0); return saveResult }
    override suspend fun deletePipeline(id: String): Boolean { enter("deletePipeline"); known(0); return deleteResult }
    var freezeThrows: Exception? = null
    var lastFreezeRev: Int? = -1
    override suspend fun freeze(id: String, note: String, draftRev: Int?): JsonObject { enter("freeze:$note"); known(0); lastFreezeRev = draftRev; freezeThrows?.let { throw it }; return buildJsonObject { put("version", 2); put("hash", "h") } }
    var lastValidate: String? = null
    override suspend fun validate(json: String): JsonObject { enter("validate"); lastValidate = json; return buildJsonObject { put("problems", JsonArray(emptyList())); put("layout", JsonNull) } }
    var lastDiff: List<String>? = null
    override suspend fun diff(id: String, a: String, b: String): JsonObject? { enter("diff"); lastDiff = listOf(id, a, b); return if (known) buildJsonObject { put("nodes", JsonArray(emptyList())) } else null }
    override suspend fun publish(id: String, version: Int): CanvasPublisher.Outcome { enter("publish:$version"); known(0); return publishOutcome }
    override suspend fun activePublish() = buildJsonObject { enter("activePublish"); put("active", false) }
    var lastRollbackExpected: Long? = -1
    override suspend fun rollback(expectedId: Long?): CanvasPublisher.Outcome { enter("rollback"); lastRollbackExpected = expectedId; return rollbackOutcome }
    var lastInterpreterPublish: Triple<String, Int, LiveMode>? = null
    override suspend fun publishInterpreter(id: String, version: Int, mode: LiveMode): CanvasPublisher.Outcome { enter("publishInterpreter:$mode"); known(0); lastInterpreterPublish = Triple(id, version, mode); return publishOutcome }
    var estimateAbResult: Estimate = Estimate.Ok(2, 6, 10, listOf(ModelLabel("extract", "az", "gpt")), "tokAB", TokenEstimate(900, 120, 4))
    var lastAb: List<Any?>? = null
    override suspend fun estimateAb(a: PipelineRef, b: PipelineRef, frameIds: List<String>): Estimate { enter("estimateAb"); known(0); lastAb = listOf(a, b, frameIds); return estimateAbResult }
    var abThrows: Exception? = null
    override suspend fun startAb(a: PipelineRef, b: PipelineRef, frameIds: List<String>, token: String): AbStarted { enter("startAb:$token"); known(0); abThrows?.let { throw it }; lastAb = listOf(a, b, frameIds); return AbStarted("g1", "ra", "rb") }
    var askRedacted = false
    var replayResult: AskDebug.Replay = AskDebug.Replay.Ok("In the fridge", listOf("m1"), 321, 120, 12, "gpt")
    var lastReplay: Pair<String, String>? = null
    var replayDelayMs = 0L
    override suspend fun asks(): JsonArray { enter("asks"); return JsonArray(listOf(buildJsonObject { put("id", "a1") })) }
    override suspend fun ask(id: String): JsonObject? { enter("ask"); if (askRedacted) throw IllegalStateException("redacted"); return if (known) buildJsonObject { put("askId", id); put("composedPrompt", "SECRET PROMPT") } else null }
    override suspend fun replayAsk(id: String, template: String): AskDebug.Replay { enter("replay"); if (replayDelayMs > 0) delay(replayDelayMs); lastReplay = id to template; return replayResult }
    var lastHistoryLimit = -1
    override suspend fun publishHistory(limit: Int): JsonArray { enter("history"); lastHistoryLimit = limit; return JsonArray(listOf(buildJsonObject { put("id", 1); put("kind", "interpreter") })) }
    var lastLiveLimit = -1
    override suspend fun live(limit: Int): JsonObject { enter("live"); lastLiveLimit = limit; return buildJsonObject { put("active", false); put("frames", JsonArray(emptyList())) } }
    override suspend fun checkPrompt(nodeType: String, template: String, schema: String?): List<String> { enter("check:$nodeType"); return listOf("bad") }
    override suspend fun frames(afterMs: Long?, beforeMs: Long, limit: Int, dwellOnly: Boolean): JsonArray { enter("frames"); lastFrames = listOf(afterMs, beforeMs, limit, dwellOnly); return JsonArray(emptyList()) }
    override suspend fun frame(id: String): JsonObject? { enter("frame"); return if (known) buildJsonObject { put("id", id) } else null }
    override suspend fun frameImage(id: String, thumb: Boolean): ByteArray? { enter("image:$thumb"); return image }
    override suspend fun estimate(pipelineId: String, version: Int?, frameIds: List<String>): Estimate { enter("estimate:$version"); known(0); lastEstimateIds = frameIds; return estimateResult }
    override suspend fun startRun(pipelineId: String, version: Int?, frameIds: List<String>, token: String): String { enter("start:$token"); startThrows?.let { throw it }; return known("run1") }
    override suspend fun runs() = JsonArray(listOf(buildJsonObject { enter("runs"); put("runId", "run1") }))
    override suspend fun run(id: String): JsonObject? { enter("run"); return if (known) buildJsonObject { put("runId", id) } else null }
    override suspend fun cancelRun(id: String): Boolean { enter("cancel"); return cancelResult }
    override suspend fun deleteRun(id: String): Boolean { enter("deleteRun"); return deleteResult }
    override suspend fun score(runId: String, frameId: String, nodeId: String, score: Int, note: String) { enter("score"); known(0); lastScore = listOf(runId, frameId, nodeId, score, note) }
    override suspend fun compare(a: String, b: String): JsonObject? { enter("compare"); return if (known) buildJsonObject { put("frames", JsonArray(emptyList())) } else null }
}

class CanvasApiTest {
    private val be = FakeBackend()
    private val api = CanvasApi(be, callTimeoutMs = 2_000)

    private fun call(method: String, path: String, body: String? = null, query: Map<String, String> = emptyMap()): HttpResponse =
        api.handle(HttpRequest(method, path, query, mapOf("authorization" to "Bearer abc"), body?.toByteArray() ?: ByteArray(0)))

    private fun HttpResponse.obj() = Json.parseToJsonElement(String(body)).jsonObject
    private fun HttpResponse.err() = obj()["error"]!!.jsonPrimitive.content

    @Test fun deeplyNestedJsonIs400NotAStackOverflow() {
        val deep = "{\"json\":" + "{\"a\":".repeat(20_000) + "1" + "}".repeat(20_000) + ",\"draftRev\":0}"
        val r = call("PUT", "/api/pipelines/p1", deep)
        assertEquals(400, r.status)
        assertEquals("bad_request", r.err())
        assertFalse(be.calls.any { it.startsWith("save") })
    }

    @Test fun anErrorFromTheBackendIsAFixed500() {
        val boom = object : CanvasBackend by be {
            override suspend fun pipelines(): JsonArray = throw StackOverflowError()
        }
        val r = CanvasApi(boom, callTimeoutMs = 2_000).handle(HttpRequest("GET", "/api/pipelines", emptyMap(), mapOf("authorization" to "Bearer abc"), ByteArray(0)))
        assertEquals(500, r.status)
        assertEquals("internal", r.err())
    }

    @Test fun sessionAndLogout() {
        val r = call("GET", "/api/session")
        assertEquals(200, r.status)
        assertEquals("abc", r.obj()["sawToken"]!!.jsonPrimitive.content) // the fake echoes; the real backend does not
        assertEquals(200, call("POST", "/api/logout").status)
        assertTrue("logout" in be.calls)
    }

    @Test fun nodeTypesAndPipelines() {
        assertEquals(200, call("GET", "/api/node-types").status)
        val r = call("GET", "/api/pipelines")
        assertEquals("live-extract", Json.parseToJsonElement(String(r.body)).jsonArray[0].jsonObject["id"]!!.jsonPrimitive.content)
    }

    @Test fun pipelineGetAndNotFound() {
        assertEquals(200, call("GET", "/api/pipelines/p-1").status)
        be.known = false
        assertEquals(404, call("GET", "/api/pipelines/p-1").status)
        assertEquals("not_found", call("GET", "/api/pipelines/p-1").err())
    }

    @Test fun duplicate() {
        val r = call("POST", "/api/pipelines", """{"from":"live-extract@2","name":"Copy"}""")
        assertEquals(200, r.status)
        assertEquals("p-new", r.obj()["id"]!!.jsonPrimitive.content)
        assertTrue("duplicate:live-extract@2:Copy" in be.calls)
        assertEquals(400, call("POST", "/api/pipelines", """{"from":"a b","name":"x"}""").status)
        assertEquals(400, call("POST", "/api/pipelines", """{"from":"a","name":""}""").status)
        assertEquals(400, call("POST", "/api/pipelines", "not json").status)
        be.known = false
        assertEquals(404, call("POST", "/api/pipelines", """{"from":"a","name":"x"}""").status)
    }

    @Test fun saveDraftMapsResults() {
        val ok = call("PUT", "/api/pipelines/p-1", """{"json":"{}","draftRev":3}""")
        assertEquals(200, ok.status)
        assertEquals(4, ok.obj()["draftRev"]!!.jsonPrimitive.int)
        assertEquals("p", ok.obj()["problems"]!!.jsonArray[0].jsonPrimitive.content)
        // json may also be an object
        assertEquals(200, call("PUT", "/api/pipelines/p-1", """{"json":{"a":1},"draftRev":3}""").status)
        be.saveResult = CanvasRepository.SaveResult.Conflict
        assertEquals(409, call("PUT", "/api/pipelines/p-1", """{"json":"{}","draftRev":3}""").status)
        assertEquals("conflict", call("PUT", "/api/pipelines/p-1", """{"json":"{}","draftRev":3}""").err())
        be.saveResult = CanvasRepository.SaveResult.ReadOnly
        assertEquals(403, call("PUT", "/api/pipelines/p-1", """{"json":"{}","draftRev":3}""").status)
        assertEquals("forbidden", call("PUT", "/api/pipelines/p-1", """{"json":"{}","draftRev":3}""").err())
        be.saveResult = CanvasRepository.SaveResult.Unreadable("secret reason")
        val bad = call("PUT", "/api/pipelines/p-1", """{"json":"{}","draftRev":3}""")
        assertEquals(400, bad.status)
        assertEquals("bad_request", bad.err())
        assertEquals(400, call("PUT", "/api/pipelines/p-1", """{"json":"{}"}""").status)
        assertEquals(400, call("PUT", "/api/pipelines/p-1", """{"json":"{}","draftRev":-1}""").status)
    }

    @Test fun saveDraftErrorIsFixedText() {
        be.saveResult = CanvasRepository.SaveResult.Unreadable("secret reason")
        val bad = call("PUT", "/api/pipelines/p-1", """{"json":"{}","draftRev":3}""")
        assertEquals(400, bad.status)
        assertEquals("Pipeline JSON is not valid.", bad.obj()["message"]!!.jsonPrimitive.content)
        assertFalse(bad.body.toString(Charsets.UTF_8).contains("secret reason"))
    }

    @Test fun deletePipeline() {
        assertEquals(200, call("DELETE", "/api/pipelines/p-1").status)
        be.deleteResult = false
        assertEquals(409, call("DELETE", "/api/pipelines/p-1").status)
        be.known = false
        assertEquals(404, call("DELETE", "/api/pipelines/p-1").status)
    }

    @Test fun freeze() {
        val r = call("POST", "/api/pipelines/p-1/freeze", """{"note":"first"}""")
        assertEquals(2, r.obj()["version"]!!.jsonPrimitive.int)
        assertTrue("freeze:first" in be.calls)
        assertEquals(200, call("POST", "/api/pipelines/p-1/freeze").status) // note optional
        assertEquals(400, call("POST", "/api/pipelines/p-1/freeze", """{"note":"${"x".repeat(81)}"}""").status)
        assertEquals(200, call("POST", "/api/pipelines/p-1/freeze", """{"note":"${"x".repeat(80)}"}""").status)
    }

    @Test fun freezeInvalidPipeline() {
        val api2 = CanvasApi(object : CanvasBackend by be {
            override suspend fun freeze(id: String, note: String, draftRev: Int?): JsonObject = throw IllegalArgumentException("invalid_pipeline")
        })
        val r = api2.handle(HttpRequest("POST", "/api/pipelines/p-1/freeze", emptyMap(), emptyMap(), ByteArray(0)))
        assertEquals(422, r.status)
        assertEquals("invalid_pipeline", r.err())
    }

    @Test fun publishAndRollback() {
        val ok = call("POST", "/api/pipelines/p-1/versions/2/publish")
        assertEquals(200, ok.status)
        assertEquals("CRITIC", ok.obj()["keys"]!!.jsonArray[0].jsonPrimitive.content)
        be.publishOutcome = CanvasPublisher.Outcome.Blocked(listOf("Run first"))
        val blocked = call("POST", "/api/pipelines/p-1/versions/2/publish")
        assertEquals(409, blocked.status)
        assertEquals("publish_blocked", blocked.err())
        assertEquals("Run first", blocked.obj()["problems"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals(400, call("POST", "/api/pipelines/p-1/versions/x/publish").status)
        assertEquals(200, call("GET", "/api/publish").status)
        assertEquals(200, call("POST", "/api/publish/rollback").status)
        be.rollbackOutcome = CanvasPublisher.Outcome.Blocked(listOf("edited"))
        val rb = call("POST", "/api/publish/rollback")
        assertEquals(409, rb.status)
        assertEquals("rollback_blocked", rb.err())
    }

    @Test fun checkPrompt() {
        val r = call("POST", "/api/check-prompt", """{"nodeType":"ai.critic","template":"t","schema":null}""")
        assertEquals(200, r.status)
        assertEquals("bad", r.obj()["problems"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals(400, call("POST", "/api/check-prompt", """{"template":"t"}""").status)
    }

    @Test fun framesAndFrame() {
        assertEquals(200, call("GET", "/api/frames", query = mapOf("before" to "99", "limit" to "10", "dwell" to "true")).status)
        assertEquals(listOf<Any?>(null, 99L, 10, true), be.lastFrames)
        assertEquals(200, call("GET", "/api/frames/f_1").status)
        be.known = false
        assertEquals(404, call("GET", "/api/frames/f_1").status)
        assertEquals(400, call("GET", "/api/frames", query = mapOf("before" to "zzz")).status)
    }

    @Test fun frameImages() {
        val thumb = call("GET", "/api/frames/f_1/thumb")
        assertEquals(200, thumb.status)
        assertEquals("image/jpeg", thumb.contentType)
        assertEquals(listOf<Byte>(1, 2, 3), thumb.body.toList())
        assertTrue("image:true" in be.calls)
        assertEquals(200, call("GET", "/api/frames/f_1/image").status)
        assertTrue("image:false" in be.calls)
        be.image = null
        val gone = call("GET", "/api/frames/f_1/image")
        assertEquals(404, gone.status)
        assertEquals("not_found", gone.err())
        assertEquals(404, call("GET", "/api/frames/f_1/other").status)
    }

    @Test fun estimate() {
        val r = call("POST", "/api/runs/estimate", """{"pipelineId":"p-1","version":2,"frameIds":["a","b"]}""")
        assertEquals(200, r.status)
        val o = r.obj()
        assertEquals(3, o["nominal"]!!.jsonPrimitive.int)
        assertEquals(5, o["max"]!!.jsonPrimitive.int)
        assertEquals(2, o["photos"]!!.jsonPrimitive.int)
        assertEquals("az", o["models"]!!.jsonArray[0].jsonObject["endpoint"]!!.jsonPrimitive.content)
        assertEquals("tok1", o["confirmToken"]!!.jsonPrimitive.content)
        be.estimateResult = Estimate.Refused(listOf("no photos"))
        val refused = call("POST", "/api/runs/estimate", """{"pipelineId":"p-1","frameIds":[]}""")
        assertEquals(422, refused.status)
        assertEquals("refused", refused.err())
        assertEquals("no photos", refused.obj()["problems"]!!.jsonArray[0].jsonPrimitive.content)
        be.known = false
        assertEquals(404, call("POST", "/api/runs/estimate", """{"pipelineId":"p-1","frameIds":["a"]}""").status)
    }

    @Test fun startRun() {
        val r = call("POST", "/api/runs", """{"pipelineId":"p-1","frameIds":["a"],"confirmToken":"tok1"}""")
        assertEquals(200, r.status)
        assertEquals("run1", r.obj()["runId"]!!.jsonPrimitive.content)
        be.startThrows = CanvasRunner.Busy()
        val busy = call("POST", "/api/runs", """{"pipelineId":"p-1","frameIds":["a"],"confirmToken":"tok1"}""")
        assertEquals(409, busy.status)
        assertEquals("busy", busy.err())
        be.startThrows = IllegalArgumentException("bad_token")
        val bad = call("POST", "/api/runs", """{"pipelineId":"p-1","frameIds":["a"],"confirmToken":"tok1"}""")
        assertEquals(400, bad.status)
        assertEquals("bad_request", bad.err())
        assertEquals(400, call("POST", "/api/runs", """{"pipelineId":"p-1","frameIds":["a"]}""").status)
    }

    @Test fun runsListDetailCancelDelete() {
        assertEquals(200, call("GET", "/api/runs").status)
        assertEquals(200, call("GET", "/api/runs/run1").status)
        assertEquals(200, call("POST", "/api/runs/run1/cancel").status)
        be.cancelResult = false
        assertEquals(404, call("POST", "/api/runs/run1/cancel").status)
        assertEquals(200, call("DELETE", "/api/runs/run1").status)
        be.deleteResult = false
        assertEquals(404, call("DELETE", "/api/runs/run1").status)
        be.known = false
        assertEquals(404, call("GET", "/api/runs/run1").status)
    }

    @Test fun busyOnDeleteActiveRun() {
        val api2 = CanvasApi(object : CanvasBackend by be {
            override suspend fun deleteRun(id: String): Boolean = throw CanvasRunner.Busy()
        })
        val r = api2.handle(HttpRequest("DELETE", "/api/runs/run1", emptyMap(), emptyMap(), ByteArray(0)))
        assertEquals(409, r.status)
        assertEquals("busy", r.err())
    }

    @Test fun scores() {
        val r = call("PUT", "/api/runs/run1/scores", """{"frameId":"f_1","nodeId":"extract","score":-1,"note":"meh"}""")
        assertEquals(200, r.status)
        assertEquals(listOf<Any>("run1", "f_1", "extract", -1, "meh"), be.lastScore)
        be.known = false
        assertEquals(404, call("PUT", "/api/runs/run1/scores", """{"frameId":"f_1","nodeId":"extract","score":1}""").status)
    }

    @Test fun scoreRange() {
        for (s in listOf(2, -2, 99)) assertEquals(400, call("PUT", "/api/runs/run1/scores", """{"frameId":"f","nodeId":"n","score":$s,"note":""}""").status)
        assertEquals(400, call("PUT", "/api/runs/run1/scores", """{"frameId":"f","nodeId":"n","score":0.5}""").status)
        assertEquals(400, call("PUT", "/api/runs/run1/scores", """{"frameId":"f","nodeId":"n","score":"1"}""").status)
        assertEquals(400, call("PUT", "/api/runs/run1/scores", """{"frameId":"f","nodeId":"n","score":1,"note":"${"x".repeat(281)}"}""").status)
        assertEquals(200, call("PUT", "/api/runs/run1/scores", """{"frameId":"f","nodeId":"n","score":0,"note":"${"x".repeat(280)}"}""").status)
        assertEquals(400, call("PUT", "/api/runs/run1/scores", """{"frameId":"f f","nodeId":"n","score":1}""").status)
    }

    @Test fun compare() {
        assertEquals(200, call("GET", "/api/compare", query = mapOf("a" to "r1", "b" to "r2")).status)
        assertEquals(400, call("GET", "/api/compare", query = mapOf("a" to "r1")).status)
        assertEquals(400, call("GET", "/api/compare", query = mapOf("a" to "r 1", "b" to "r2")).status)
        be.known = false
        assertEquals(404, call("GET", "/api/compare", query = mapOf("a" to "r1", "b" to "r2")).status)
    }

    @Test fun idValidation() {
        for (bad in listOf("a%20b", "a.b", "x".repeat(65), "a b", "a;b", "a'b")) {
            val r = call("GET", "/api/pipelines/$bad")
            assertEquals("path $bad", 400, r.status)
            assertEquals("bad_request", r.err())
        }
        assertEquals(400, call("GET", "/api/frames/" + "y".repeat(65)).status)
        assertEquals(400, call("GET", "/api/runs/a.b").status)
        assertEquals(200, call("GET", "/api/pipelines/" + "z".repeat(64)).status)
        assertEquals(200, call("GET", "/api/pipelines/a:b_c-D1").status)
    }

    @Test fun limitClamped() {
        call("GET", "/api/frames", query = mapOf("limit" to "9999"))
        assertEquals(120, be.lastFrames!![2])
        call("GET", "/api/frames", query = mapOf("limit" to "0"))
        assertEquals(1, be.lastFrames!![2])
        call("GET", "/api/frames", query = mapOf("limit" to "-5"))
        assertEquals(1, be.lastFrames!![2])
        call("GET", "/api/frames")
        assertEquals(60, be.lastFrames!![2])
        val ids = (1..80).joinToString(",") { "\"f$it\"" }
        assertEquals(400, call("POST", "/api/runs/estimate", """{"pipelineId":"p","frameIds":[$ids]}""").status) // no longer truncated
        val long = "q".repeat(65)
        assertEquals(400, call("POST", "/api/runs/estimate", """{"pipelineId":"p","frameIds":["$long"]}""").status)
    }

    @Test fun methodAndPathErrors() {
        assertEquals(405, call("DELETE", "/api/session").status)
        assertEquals(404, call("GET", "/api/nope").status)
        assertEquals(404, call("GET", "/api/pipelines/p-1/versions/2").status)
    }

    @Test fun timeoutIs503() {
        be.delayMs = 1_500
        val slow = CanvasApi(be, callTimeoutMs = 100)
        val r = slow.handle(HttpRequest("GET", "/api/pipelines", emptyMap(), emptyMap(), ByteArray(0)))
        assertEquals(503, r.status)
        assertEquals("busy", Json.parseToJsonElement(String(r.body)).jsonObject["error"]!!.jsonPrimitive.content)
    }

    @Test fun mutationSurvivesRequestDeadline() {
        var finished = false
        val slow = CanvasApi(object : CanvasBackend by be {
            override suspend fun publish(id: String, version: Int): CanvasPublisher.Outcome {
                delay(400) // longer than the 100 ms deadline
                finished = true
                return CanvasPublisher.Outcome.Published(9, emptyList())
            }
        }, callTimeoutMs = 100)
        val r = slow.handle(HttpRequest("POST", "/api/pipelines/p-1/versions/1/publish", emptyMap(), emptyMap(), ByteArray(0)))
        assertTrue(finished)
        assertEquals(200, r.status)
    }

    @Test fun errorsNeverEchoInput() {
        val r = call("PUT", "/api/pipelines/p-1", """{"json":"{}","draftRev":"SECRETVALUE"}""")
        assertFalse(String(r.body).contains("SECRETVALUE"))
    }

    // ---- slice 2, Task 9: validate, diff, freeze rev, day filter, 50-photo cap ----

    @Test fun validateEchoesRev() {
        val r = call("POST", "/api/validate", """{"json":{"id":"p"},"rev":7}""")
        assertEquals(200, r.status)
        assertEquals(7, r.obj()["rev"]!!.jsonPrimitive.int)
        assertEquals("""{"id":"p"}""", be.lastValidate)
        assertTrue("rev" !in call("POST", "/api/validate", """{"json":"{}"}""").obj())
        assertEquals(400, call("POST", "/api/validate", """{"json":"{}","rev":"x"}""").status)
        assertEquals(400, call("POST", "/api/validate", """{"json":"{}","rev":-1}""").status)
        assertEquals(400, call("POST", "/api/validate", "not json").status)
        assertEquals(400, call("POST", "/api/validate", """{"rev":1}""").status)
    }

    @Test fun validateSavesNothingAndNeedsNoDraftRev() {
        be.calls.clear()
        assertEquals(200, call("POST", "/api/validate", """{"json":"{}"}""").status)
        assertEquals(listOf("validate"), be.calls)
    }

    @Test fun diffRoute() {
        val r = call("GET", "/api/pipelines/p-1/diff", query = mapOf("a" to "1", "b" to "draft"))
        assertEquals(200, r.status)
        assertEquals(listOf("p-1", "1", "draft"), be.lastDiff)
        assertEquals(200, call("GET", "/api/pipelines/p-1/diff", query = mapOf("a" to "1", "b" to "2")).status)
        assertEquals(400, call("GET", "/api/pipelines/p-1/diff", query = mapOf("a" to "x", "b" to "2")).status)
        assertEquals(400, call("GET", "/api/pipelines/p-1/diff", query = mapOf("a" to "1")).status)
        assertEquals(400, call("GET", "/api/pipelines/p-1/diff", query = mapOf("a" to "1", "b" to "../x")).status)
        be.known = false
        assertEquals(404, call("GET", "/api/pipelines/p-1/diff", query = mapOf("a" to "1", "b" to "2")).status)
    }

    @Test fun freezeStaleRevIs409() {
        be.freezeThrows = CanvasRepository.DraftChanged()
        val r = call("POST", "/api/pipelines/p-1/freeze", """{"draftRev":3}""")
        assertEquals(409, r.status)
        assertEquals("conflict", r.err())
        assertEquals("The draft changed. Save again.", r.obj()["message"]!!.jsonPrimitive.content)
        assertEquals(3, be.lastFreezeRev)
        be.freezeThrows = null
        assertEquals(200, call("POST", "/api/pipelines/p-1/freeze", """{"note":"n"}""").status)
        assertEquals(null, be.lastFreezeRev)
        assertEquals(400, call("POST", "/api/pipelines/p-1/freeze", """{"draftRev":"x"}""").status)
        assertEquals(400, call("POST", "/api/pipelines/p-1/freeze", """{"draftRev":-2}""").status)
    }

    @Test fun frameIdsOver50Is400() {
        val ids = (1..51).joinToString(",") { "\"f$it\"" }
        val r = call("POST", "/api/runs/estimate", """{"pipelineId":"p","frameIds":[$ids]}""")
        assertEquals(400, r.status)
        assertEquals("bad_request", r.err())
        assertEquals("At most 50 photos", r.obj()["message"]!!.jsonPrimitive.content)
        assertEquals(400, call("POST", "/api/runs", """{"pipelineId":"p","frameIds":[$ids],"confirmToken":"t"}""").status)
        val ok = (1..50).joinToString(",") { "\"f$it\"" }
        assertEquals(200, call("POST", "/api/runs/estimate", """{"pipelineId":"p","frameIds":[$ok]}""").status)
        assertEquals(50, be.lastEstimateIds.size)
    }

    @Test fun dayFilterParsed() {
        val zone = java.time.ZoneId.of("Asia/Kolkata")
        val za = CanvasApi(be, callTimeoutMs = 2_000, zone = { zone })
        fun get(q: Map<String, String>) = za.handle(HttpRequest("GET", "/api/frames", q, mapOf("authorization" to "Bearer abc"), ByteArray(0)))
        val start = java.time.LocalDate.of(2026, 10, 21).atStartOfDay(zone).toInstant().toEpochMilli()
        val next = java.time.LocalDate.of(2026, 10, 22).atStartOfDay(zone).toInstant().toEpochMilli()
        assertEquals(200, get(mapOf("day" to "2026-10-21")).status)
        assertEquals(listOf<Any?>(start, next, 60, false), be.lastFrames)
        // an earlier `before` wins; a later one is capped at the next midnight
        get(mapOf("day" to "2026-10-21", "before" to (start + 1000).toString()))
        assertEquals(listOf<Any?>(start, start + 1000, 60, false), be.lastFrames)
        get(mapOf("day" to "2026-10-21", "before" to (next + 5000).toString()))
        assertEquals(listOf<Any?>(start, next, 60, false), be.lastFrames)
        // no day: today's behaviour
        get(mapOf("before" to "99"))
        assertEquals(listOf<Any?>(null, 99L, 60, false), be.lastFrames)
    }

    @Test fun dayFilterBadIs400() {
        for (bad in listOf("2026-13-01", "2026-02-30", "21-10-2026", "2026-10-2", "yesterday", "", "2026-10-21T00:00", " 2026-10-21")) {
            val r = call("GET", "/api/frames", query = mapOf("day" to bad))
            assertEquals("day=[$bad]", 400, r.status)
            assertEquals("bad_request", r.err())
        }
    }

    // ---- slice 2, Task 9 (commit 2): interpreter publish, rollback by expected id, history, live ----

    @Test fun publishModeAbsentIsPrompts() {
        assertEquals(200, call("POST", "/api/pipelines/p-1/versions/2/publish").status)
        assertEquals(200, call("POST", "/api/pipelines/p-1/versions/2/publish", """{}""").status)
        assertEquals(200, call("POST", "/api/pipelines/p-1/versions/2/publish", """{"mode":"prompts"}""").status)
        assertEquals(200, call("POST", "/api/pipelines/p-1/versions/2/publish", """{"mode":null}""").status)
        assertEquals(listOf("publish:2", "publish:2", "publish:2", "publish:2"), be.calls.filter { it.startsWith("publish") })
        assertEquals("prompts", call("POST", "/api/pipelines/p-1/versions/2/publish").obj()["kind"]!!.jsonPrimitive.content)
    }

    @Test fun publishShadowAndLiveGoThroughTheInterpreter() {
        val sh = call("POST", "/api/pipelines/p-1/versions/3/publish", """{"mode":"shadow"}""")
        assertEquals(200, sh.status)
        assertEquals(Triple("p-1", 3, LiveMode.SHADOW), be.lastInterpreterPublish)
        assertEquals("interpreter", sh.obj()["kind"]!!.jsonPrimitive.content)
        assertEquals("SHADOW", sh.obj()["mode"]!!.jsonPrimitive.content)
        assertEquals(7, sh.obj()["publishId"]!!.jsonPrimitive.int)
        assertEquals(200, call("POST", "/api/pipelines/p-1/versions/3/publish", """{"mode":"live"}""").status)
        assertEquals(LiveMode.LIVE, be.lastInterpreterPublish!!.third)
        assertTrue("publish:3" !in be.calls)
    }

    @Test fun publishBlockedAndBadModeMapToFixedErrors() {
        be.publishOutcome = CanvasPublisher.Outcome.Blocked(listOf("Run this version first: intent have no successful run"))
        val b = call("POST", "/api/pipelines/p-1/versions/3/publish", """{"mode":"live"}""")
        assertEquals(409, b.status)
        assertEquals("publish_blocked", b.err())
        assertEquals("Run this version first: intent have no successful run", b.obj()["problems"]!!.jsonArray[0].jsonPrimitive.content)
        for (bad in listOf("""{"mode":"later"}""", """{"mode":3}""", """{"mode":"LIVE "}""", "not json")) assertEquals(bad, 400, call("POST", "/api/pipelines/p-1/versions/3/publish", bad).status)
        be.known = false
        assertEquals(404, call("POST", "/api/pipelines/p-1/versions/3/publish", """{"mode":"shadow"}""").status)
    }

    @Test fun rollbackPassesTheExpectedPublishId() {
        assertEquals(200, call("POST", "/api/publish/rollback").status)
        assertEquals(null, be.lastRollbackExpected)
        assertEquals(200, call("POST", "/api/publish/rollback", """{"expectedId":42}""").status)
        assertEquals(42L, be.lastRollbackExpected)
        assertEquals(200, call("POST", "/api/publish/rollback", """{"expectedId":null}""").status)
        assertEquals(null, be.lastRollbackExpected)
        for (bad in listOf("""{"expectedId":"x"}""", """{"expectedId":-1}""", """{"expectedId":1.5}""", "nope")) assertEquals(bad, 400, call("POST", "/api/publish/rollback", bad).status)
        be.rollbackOutcome = CanvasPublisher.Outcome.Blocked(listOf("That publish was already rolled back."))
        val r = call("POST", "/api/publish/rollback", """{"expectedId":9}""")
        assertEquals(409, r.status)
        assertEquals("rollback_blocked", r.err())
    }

    @Test fun historyListsRows() {
        val r = call("GET", "/api/publish/history")
        assertEquals(200, r.status)
        assertEquals("interpreter", Json.parseToJsonElement(String(r.body)).jsonArray[0].jsonObject["kind"]!!.jsonPrimitive.content)
        assertEquals(50, be.lastHistoryLimit)
        call("GET", "/api/publish/history", query = mapOf("limit" to "9999")); assertEquals(50, be.lastHistoryLimit)
        call("GET", "/api/publish/history", query = mapOf("limit" to "5")); assertEquals(5, be.lastHistoryLimit)
        call("GET", "/api/publish/history", query = mapOf("limit" to "0")); assertEquals(1, be.lastHistoryLimit)
        assertEquals(400, call("GET", "/api/publish/history", query = mapOf("limit" to "x")).status)
        assertEquals(405, call("POST", "/api/publish/history").status)
    }

    @Test fun liveRoute() {
        assertEquals(200, call("GET", "/api/live").status)
        assertEquals(50, be.lastLiveLimit)
        call("GET", "/api/live", query = mapOf("limit" to "9999")); assertEquals(100, be.lastLiveLimit)
        call("GET", "/api/live", query = mapOf("limit" to "7")); assertEquals(7, be.lastLiveLimit)
        assertEquals(400, call("GET", "/api/live", query = mapOf("limit" to "x")).status)
    }

    // ---- slice 2, Task 9 (commit 3): A/B, estimate tokens, Ask debug ----

    @Test fun estimateCarriesTokens() {
        be.estimateResult = Estimate.Ok(2, 3, 5, listOf(ModelLabel("extract", "az", "gpt")), "tok1", TokenEstimate(1200, 90, 7))
        val t = call("POST", "/api/runs/estimate", """{"pipelineId":"p-1","frameIds":["a"]}""").obj()["tokens"]!!.jsonObject
        assertEquals(1200, t["promptTokens"]!!.jsonPrimitive.int); assertEquals(90, t["completionTokens"]!!.jsonPrimitive.int); assertEquals(7, t["basedOn"]!!.jsonPrimitive.int)
        be.estimateResult = Estimate.Ok(2, 3, 5, emptyList(), "tok1", null)
        assertEquals("null", call("POST", "/api/runs/estimate", """{"pipelineId":"p-1","frameIds":["a"]}""").obj()["tokens"].toString())
    }

    @Test fun estimateAbRoute() {
        val r = call("POST", "/api/runs/estimate-ab", """{"a":{"pipelineId":"p-1","version":2},"b":{"pipelineId":"p-2"},"frameIds":["f1","f2"]}""")
        assertEquals(200, r.status)
        assertEquals(listOf<Any?>(PipelineRef("p-1", 2), PipelineRef("p-2", null), listOf("f1", "f2")), be.lastAb)
        val o = r.obj()
        assertEquals("tokAB", o["confirmToken"]!!.jsonPrimitive.content); assertEquals(6, o["nominal"]!!.jsonPrimitive.int); assertEquals(10, o["max"]!!.jsonPrimitive.int)
        assertEquals(900, o["tokens"]!!.jsonObject["promptTokens"]!!.jsonPrimitive.int)
        be.estimateAbResult = Estimate.Refused(listOf("over the limit"))
        val refused = call("POST", "/api/runs/estimate-ab", """{"a":{"pipelineId":"p-1"},"b":{"pipelineId":"p-2"},"frameIds":["f1"]}""")
        assertEquals(422, refused.status); assertEquals("refused", refused.err())
        assertEquals("over the limit", refused.obj()["problems"]!!.jsonArray[0].jsonPrimitive.content)
        for (bad in listOf(
            """{"b":{"pipelineId":"p-2"},"frameIds":["f1"]}""", """{"a":"p-1","b":{"pipelineId":"p-2"},"frameIds":["f1"]}""", """{"a":{"pipelineId":"a b"},"b":{"pipelineId":"p-2"},"frameIds":["f1"]}""",
            """{"a":{"pipelineId":"p-1","version":0},"b":{"pipelineId":"p-2"},"frameIds":["f1"]}""", """{"a":{"pipelineId":"p-1"},"b":{"pipelineId":"p-2"}}""",
        )) assertEquals(bad, 400, call("POST", "/api/runs/estimate-ab", bad).status)
        be.known = false
        assertEquals(404, call("POST", "/api/runs/estimate-ab", """{"a":{"pipelineId":"p-1"},"b":{"pipelineId":"p-2"},"frameIds":["f1"]}""").status)
    }

    @Test fun abResponseHasBothIds() {
        val r = call("POST", "/api/runs/ab", """{"a":{"pipelineId":"p-1"},"b":{"pipelineId":"p-2","version":3},"frameIds":["f1"],"confirmToken":"tokAB"}""")
        assertEquals(200, r.status)
        assertEquals(setOf("group", "runA", "runB"), r.obj().keys)
        assertEquals("g1", r.obj()["group"]!!.jsonPrimitive.content); assertEquals("ra", r.obj()["runA"]!!.jsonPrimitive.content); assertEquals("rb", r.obj()["runB"]!!.jsonPrimitive.content)
        assertTrue("startAb:tokAB" in be.calls)
        be.abThrows = CanvasRunner.Busy()
        assertEquals(409, call("POST", "/api/runs/ab", """{"a":{"pipelineId":"p-1"},"b":{"pipelineId":"p-2"},"frameIds":["f1"],"confirmToken":"t"}""").status)
        be.abThrows = IllegalArgumentException("bad_token")
        assertEquals(400, call("POST", "/api/runs/ab", """{"a":{"pipelineId":"p-1"},"b":{"pipelineId":"p-2"},"frameIds":["f1"],"confirmToken":"t"}""").status)
        be.abThrows = null
        assertEquals(400, call("POST", "/api/runs/ab", """{"a":{"pipelineId":"p-1"},"b":{"pipelineId":"p-2"},"frameIds":["f1"]}""").status) // no token
    }

    @Test fun asksList() {
        val r = call("GET", "/api/asks")
        assertEquals(200, r.status)
        assertEquals("a1", Json.parseToJsonElement(String(r.body)).jsonArray[0].jsonObject["id"]!!.jsonPrimitive.content)
    }

    @Test fun askViewAndErrors() {
        val r = call("GET", "/api/asks/a1")
        assertEquals(200, r.status)
        assertEquals("SECRET PROMPT", r.obj()["composedPrompt"]!!.jsonPrimitive.content)
        be.known = false
        assertEquals(404, call("GET", "/api/asks/a1").status)
        be.known = true
        assertEquals(400, call("GET", "/api/asks/a.b").status)
    }

    @Test fun replayRedactedIs409() {
        be.askRedacted = true
        val v = call("GET", "/api/asks/a1")
        assertEquals(409, v.status); assertEquals("refused", v.err()); assertEquals("This ask was redacted", v.obj()["message"]!!.jsonPrimitive.content)
        be.replayResult = AskDebug.Replay.Failed("redacted")
        val r = call("POST", "/api/asks/a1/replay", """{"template":"T"}""")
        assertEquals(409, r.status); assertEquals("refused", r.err()); assertEquals("This ask was redacted", r.obj()["message"]!!.jsonPrimitive.content)
    }

    @Test fun replayHappyPathAndInputChecks() {
        val r = call("POST", "/api/asks/a1/replay", """{"template":"T {{question}}"}""")
        assertEquals(200, r.status)
        assertEquals("a1" to "T {{question}}", be.lastReplay)
        val o = r.obj()
        assertEquals("In the fridge", o["answer"]!!.jsonPrimitive.content); assertEquals("m1", o["sources"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals(321, o["latencyMs"]!!.jsonPrimitive.int); assertEquals(120, o["promptTokens"]!!.jsonPrimitive.int); assertEquals(12, o["completionTokens"]!!.jsonPrimitive.int); assertEquals("gpt", o["model"]!!.jsonPrimitive.content)
        assertEquals(400, call("POST", "/api/asks/a1/replay", """{}""").status)
        assertEquals(400, call("POST", "/api/asks/a1/replay", """{"template":${"\"" + "x".repeat(70_000) + "\""}}""").status)
        assertEquals(400, call("POST", "/api/asks/a.b/replay", """{"template":"T"}""").status)
    }

    @Test fun replayFailureWordsMapToFixedTexts() {
        val expected = mapOf(
            "not_found" to (404 to "not_found"),
            "incompatible" to (422 to "refused"),
            "forgotten" to (409 to "refused"),
            "AUTH" to (502 to "model_failed"), "REJECTED" to (502 to "model_failed"), "INVALID_OUTPUT" to (502 to "model_failed"),
            "TRANSIENT" to (502 to "model_failed"), "NOT_SET" to (502 to "model_failed"),
            "SocketTimeoutException" to (502 to "model_failed"),
        )
        val texts = mutableSetOf<String>()
        for ((word, st) in expected) {
            be.replayResult = AskDebug.Replay.Failed(word)
            val r = call("POST", "/api/asks/a1/replay", """{"template":"T"}""")
            assertEquals(word, st.first, r.status); assertEquals(word, st.second, r.err())
            val msg = r.obj()["message"]!!.jsonPrimitive.content
            assertFalse("a kind or class name must not be echoed: $word", word.any { it.isUpperCase() } && msg.contains(word))
            texts += msg
        }
        assertTrue("distinct fixed texts", texts.size >= 6)
        // an unknown provider word never reaches the client
        be.replayResult = AskDebug.Replay.Failed("secret provider text")
        val r = call("POST", "/api/asks/a1/replay", """{"template":"T"}""")
        assertEquals(502, r.status); assertFalse(String(r.body).contains("secret provider text"))
    }

    @Test fun replayHasADeadline() {
        be.replayDelayMs = 5_000
        val fast = CanvasApi(be, callTimeoutMs = 2_000, mutationDeadlineMs = 200)
        val r = fast.handle(HttpRequest("POST", "/api/asks/a1/replay", emptyMap(), mapOf("authorization" to "Bearer abc"), """{"template":"T"}""".toByteArray()))
        assertEquals(503, r.status); assertEquals("busy", r.err())
    }

    @Test fun replayRunsToCompletionWhenTheCallerIsCancelled() {
        // a mutation is NonCancellable: the same 2 s read deadline does not cut a 300 ms replay, and a slow publish still finishes
        be.replayDelayMs = 300
        assertEquals(200, call("POST", "/api/asks/a1/replay", """{"template":"T"}""").status)
    }
}
