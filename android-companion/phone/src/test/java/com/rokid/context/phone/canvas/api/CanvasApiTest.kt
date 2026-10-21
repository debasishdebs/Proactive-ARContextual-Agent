package com.rokid.context.phone.canvas.api

import com.rokid.context.canvas.CanvasPublisher
import com.rokid.context.canvas.CanvasRepository
import com.rokid.context.canvas.CanvasRunner
import com.rokid.context.canvas.Estimate
import com.rokid.context.canvas.ModelLabel
import com.rokid.context.ai.contract.PromptKey
import com.rokid.context.phone.canvas.server.HttpRequest
import com.rokid.context.phone.canvas.server.HttpResponse
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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
    var lastFrames: Triple<Long, Int, Boolean>? = null
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
    override suspend fun freeze(id: String, note: String): JsonObject { enter("freeze:$note"); known(0); return buildJsonObject { put("version", 2); put("hash", "h") } }
    override suspend fun publish(id: String, version: Int): CanvasPublisher.Outcome { enter("publish:$version"); known(0); return publishOutcome }
    override suspend fun activePublish() = buildJsonObject { enter("activePublish"); put("active", false) }
    override suspend fun rollback(): CanvasPublisher.Outcome { enter("rollback"); return rollbackOutcome }
    override suspend fun checkPrompt(nodeType: String, template: String, schema: String?): List<String> { enter("check:$nodeType"); return listOf("bad") }
    override suspend fun frames(beforeMs: Long, limit: Int, dwellOnly: Boolean): JsonArray { enter("frames"); lastFrames = Triple(beforeMs, limit, dwellOnly); return JsonArray(emptyList()) }
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
            override suspend fun freeze(id: String, note: String): JsonObject = throw IllegalArgumentException("invalid_pipeline")
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
        assertEquals(Triple(99L, 10, true), be.lastFrames)
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
        assertEquals(120, be.lastFrames!!.second)
        call("GET", "/api/frames", query = mapOf("limit" to "0"))
        assertEquals(1, be.lastFrames!!.second)
        call("GET", "/api/frames", query = mapOf("limit" to "-5"))
        assertEquals(1, be.lastFrames!!.second)
        call("GET", "/api/frames")
        assertEquals(60, be.lastFrames!!.second)
        val ids = (1..80).joinToString(",") { "\"f$it\"" }
        call("POST", "/api/runs/estimate", """{"pipelineId":"p","frameIds":[$ids]}""")
        assertEquals(50, be.lastEstimateIds.size) // clamped to 50
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
}
