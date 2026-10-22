package com.rokid.context.phone.canvas

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.ai.AiGraph
import com.rokid.context.ai.Capability
import com.rokid.context.ai.Endpoint
import com.rokid.context.ai.EndpointKind
import com.rokid.context.ai.InMemoryKeyVault
import com.rokid.context.ai.InMemorySeedState
import com.rokid.context.ai.ProviderBuilder
import com.rokid.context.ai.Role
import com.rokid.context.ai.RoleBinding
import com.rokid.context.ai.canvas.DefaultPipelines
import com.rokid.context.ai.canvas.PipelineJson
import com.rokid.context.ai.contract.DefaultSchemas
import com.rokid.context.ai.contract.PromptKey
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.ProposalRepository
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.FrameEntity
import com.rokid.context.memory.forget.ForgetService
import com.rokid.context.memory.frames.FrameImages
import com.rokid.context.memory.frames.TinkFrameCipher
import com.rokid.context.model.ModelProvider
import com.rokid.context.phone.canvas.api.CanvasApi
import com.rokid.context.phone.canvas.api.CanvasBackendImpl
import com.rokid.context.phone.canvas.server.AssetSource
import com.rokid.context.phone.canvas.server.CanvasAuth
import com.rokid.context.phone.canvas.server.CanvasRouter
import com.rokid.context.phone.canvas.server.CanvasServer
import com.rokid.context.phone.canvas.server.HostPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * The whole canvas stack on a real loopback server: pairing, the router, the API, the backend, runner and publisher over an
 * in-memory database, a fake model and sealed photos on disk. Only the model and the network interface are faked.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CanvasEndToEndTest {
    @get:Rule val tmp = TemporaryFolder()
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val auth = CanvasAuth({ System.currentTimeMillis() })
    private val sent = CopyOnWriteArrayList<String>()
    private val client = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()
    private lateinit var ai: AiGraph
    private lateinit var server: CanvasServer
    private var base = ""
    private val jpeg: ByteArray = ByteArrayOutputStream().also { Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()

    private fun sentIds(p: String) = Regex("frame_id: (f\\d)").findAll(p).map { it.groupValues[1] }.toList().distinct()
    private fun extractReply(ids: List<String>) =
        """{"facts":[${ids.joinToString(",") { """{"text":"Mug in $it","type":"object","confidence":0.9,"tags":[],"source_frame_ids":["$it"]}""" }}],"captions":[${ids.joinToString(",") { """{"frame_id":"$it","caption":"Counter $it"}""" }}]}"""

    @Before fun setUp() {
        MemoryGraph.resetForTests(DatabaseFactory.inMemory(app))
        FrameImages.configureForTests(TinkFrameCipher.inMemoryForTests()) { true }
        ai = AiGraph(MemoryGraph.get(app), InMemoryKeyVault(), InMemorySeedState(), ProviderBuilder { _, _, _ ->
            object : ModelProvider { override suspend fun generate(prompt: String): String { sent += prompt; return extractReply(sentIds(prompt)) } }
        }).also { AiGraph.resetForTests(it) }
        runBlocking {
            ai.endpoints.save(Endpoint("az", "Azure", EndpointKind.AZURE_OPENAI, "https://az.example", "2024-10-21", listOf("gpt"), setOf(Capability.CHAT, Capability.VISION), 1), apiKey = "k").getOrThrow()
            Role.entries.filter { it != Role.EMBED }.forEach { ai.bindings.set(RoleBinding(it, "az", "gpt")).getOrThrow() }
        }
        val backend = CanvasBackendImpl.create(app, ai, auth, { false }, scope)
        lateinit var s: CanvasServer
        val router = CanvasRouter(auth, HostPolicy({ s.port ?: 0 }, { null }), CanvasApi(backend), AssetSource { null })
        s = CanvasServer(router, bindLan = false)
        server = s
        base = "http://127.0.0.1:${server.start()}"
    }

    @After fun tearDown() {
        server.stop()
        scope.cancel()
        FrameImages.configureForTests(null) { false }
        AiGraph.resetForTests(null); MemoryGraph.get(app).db.close(); MemoryGraph.resetForTests(null)
    }

    private fun seed(id: String, ts: Long) {
        val file = File(tmp.root, "rokid_${ts}_$id.jpg").apply { writeBytes(FrameImages.forDisk(jpeg)) }
        runBlocking { MemoryGraph.get(app).db.frameDao().insertIgnore(FrameEntity(id, ts, file.path, "rokid")) }
    }

    private class Reply(val status: Int, val body: JsonElement?)

    private fun call(method: String, path: String, token: String?, body: JsonElement? = null): Reply {
        val b = body?.toString()?.toRequestBody() ?: if (method == "GET") null else ByteArray(0).toRequestBody()
        val req = Request.Builder().url(base + path).method(method, b).apply { token?.let { header("Authorization", "Bearer $it") } }.build()
        client.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            return Reply(r.code, if (text.isEmpty()) null else Json.parseToJsonElement(text))
        }
    }

    private fun pair(): String {
        val r = call("POST", "/api/pair", null, buildJsonObject { put("code", auth.newCode().code) })
        assertEquals(200, r.status)
        return r.body!!.jsonObject["token"]!!.jsonPrimitive.content
    }

    private fun waitDone(token: String, runId: String): JsonObject {
        val end = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < end) {
            val run = call("GET", "/api/runs/$runId", token).body!!.jsonObject
            if (run["status"]!!.jsonPrimitive.content != "RUNNING") return run
            Thread.sleep(50)
        }
        throw AssertionError("run did not finish")
    }

    private fun frameIdsJson(ids: List<String>) = buildJsonArray { ids.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }

    private fun runOn(token: String, pipelineId: String, ids: List<String>): String {
        val est = call("POST", "/api/runs/estimate", token, buildJsonObject { put("pipelineId", pipelineId); put("frameIds", frameIdsJson(ids)) })
        assertEquals(200, est.status)
        val confirm = est.body!!.jsonObject["confirmToken"]!!.jsonPrimitive.content
        val start = call("POST", "/api/runs", token, buildJsonObject { put("pipelineId", pipelineId); put("frameIds", frameIdsJson(ids)); put("confirmToken", confirm) })
        assertEquals(200, start.status)
        return start.body!!.jsonObject["runId"]!!.jsonPrimitive.content
    }

    /** A copy of Live extraction whose extract prompt is the live template plus a marker; returns its id. */
    private fun draftCopy(token: String, marker: String): String {
        val list = call("GET", "/api/pipelines", token).body!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }
        assertTrue("live-extract" in list); assertTrue("intent-draft" in list)
        val dup = call("POST", "/api/pipelines", token, buildJsonObject { put("from", "live-extract"); put("name", "Mine") })
        assertEquals(200, dup.status)
        val id = dup.body!!.jsonObject["id"]!!.jsonPrimitive.content
        val rev = call("GET", "/api/pipelines/$id", token).body!!.jsonObject["draftRev"]!!.jsonPrimitive.int
        val template = runBlocking { ai.prompts.active(PromptKey.VISION_EXTRACT).template } + "\n" + marker
        val base = DefaultPipelines.liveExtract()
        val edited = base.copy(id = id, name = "Mine", nodes = base.nodes.map {
            if (it.id != "extract") it else it.copy(params = buildJsonObject {
                put("batch", 3)
                put("prompt", buildJsonObject { put("source", "draft"); put("template", template); put("schema", DefaultSchemas.text(PromptKey.VISION_EXTRACT)) })
            })
        })
        val saved = call("PUT", "/api/pipelines/$id", token, buildJsonObject { put("json", PipelineJson.encode(edited)); put("draftRev", rev) })
        assertEquals(200, saved.status)
        assertEquals(0, saved.body!!.jsonObject["problems"]!!.jsonArray.size)
        return id
    }

    private fun liveRows() = runBlocking { MemoryGraph.get(app).db.memoryDao().all().size }

    @Test fun fullFlow() {
        val ids = listOf("f1", "f2", "f3")
        ids.forEachIndexed { i, id -> seed(id, 1_000L + i) }
        val token = pair()
        val original = runBlocking { ai.prompts.active(PromptKey.VISION_EXTRACT).template }
        val id = draftCopy(token, "DRAFT-MARKER-7")

        val liveBefore = liveRows()
        val runId = runOn(token, id, ids)
        val run = waitDone(token, runId)
        assertEquals("DONE", run["status"]!!.jsonPrimitive.content)
        val items = run["items"]!!.jsonArray.map { it.jsonObject }
        for (node in listOf("photos", "extract", "trust", "write")) assertEquals(node, 3, items.count { it["nodeId"]!!.jsonPrimitive.content == node })
        assertTrue("the model saw the draft text", sent.any { "DRAFT-MARKER-7" in it })
        assertEquals("live tables unchanged", liveBefore, liveRows())
        assertEquals("live prompt unchanged by a run", original, runBlocking { ai.prompts.active(PromptKey.VISION_EXTRACT).template })

        val scored = call("PUT", "/api/runs/$runId/scores", token, buildJsonObject { put("frameId", "f1"); put("nodeId", "extract"); put("score", 1); put("note", "good") })
        assertEquals(200, scored.status)
        assertEquals(1, call("GET", "/api/runs/$runId", token).body!!.jsonObject["scores"]!!.jsonArray.size)

        val frozen = call("POST", "/api/pipelines/$id/freeze", token, buildJsonObject { put("note", "first") })
        assertEquals(200, frozen.status)
        val v = frozen.body!!.jsonObject["version"]!!.jsonPrimitive.int
        assertEquals(1, v)

        val published = call("POST", "/api/pipelines/$id/versions/$v/publish", token)
        assertEquals(published.body.toString(), 200, published.status)
        assertTrue(published.body!!.jsonObject["keys"]!!.jsonArray.isNotEmpty())
        val live = runBlocking { ai.prompts.active(PromptKey.VISION_EXTRACT).template }
        assertTrue(live.endsWith("DRAFT-MARKER-7"))
        assertTrue(call("GET", "/api/publish", token).body!!.jsonObject["active"]!!.jsonPrimitive.content == "true")

        val back = call("POST", "/api/publish/rollback", token)
        assertEquals(200, back.status)
        assertEquals(original, runBlocking { ai.prompts.active(PromptKey.VISION_EXTRACT).template })

        val again = call("POST", "/api/publish/rollback", token)
        assertEquals(409, again.status)
        assertEquals("rollback_blocked", again.body!!.jsonObject["error"]!!.jsonPrimitive.content)
    }

    @Test fun forgetCascadesIntoCanvas() {
        val ids = listOf("f1", "f2", "f3")
        ids.forEachIndexed { i, id -> seed(id, 1_000L + i) }
        val token = pair()
        val runId = runOn(token, "live-extract", ids)
        val before = waitDone(token, runId)["items"]!!.jsonArray.map { it.jsonObject }
        assertTrue(before.any { it["frameId"]!!.jsonPrimitive.content == "f2" })

        val db = MemoryGraph.get(app).db
        runBlocking { ForgetService(db, ProposalRepository(db.proposalDao(), db.actionDao(), db)).forgetMoment("f2") }

        val after = call("GET", "/api/runs/$runId", token).body!!.jsonObject["items"]!!.jsonArray.map { it.jsonObject }
        assertFalse(after.any { it["frameId"]!!.jsonPrimitive.content == "f2" })
        assertTrue(after.any { it["frameId"]!!.jsonPrimitive.content == "f1" })
    }

    @Test fun noTokenNoData() {
        val routes = listOf(
            "GET" to "/api/session", "POST" to "/api/logout", "GET" to "/api/node-types", "GET" to "/api/pipelines", "POST" to "/api/pipelines",
            "GET" to "/api/pipelines/live-extract", "PUT" to "/api/pipelines/live-extract", "DELETE" to "/api/pipelines/live-extract",
            "POST" to "/api/pipelines/live-extract/freeze", "POST" to "/api/pipelines/live-extract/versions/1/publish",
            "GET" to "/api/publish", "POST" to "/api/publish/rollback", "POST" to "/api/check-prompt", "GET" to "/api/frames",
            "GET" to "/api/frames/f1", "GET" to "/api/frames/f1/thumb", "GET" to "/api/frames/f1/image", "POST" to "/api/runs/estimate",
            "POST" to "/api/runs", "GET" to "/api/runs", "GET" to "/api/runs/r1", "POST" to "/api/runs/r1/cancel", "DELETE" to "/api/runs/r1",
            "PUT" to "/api/runs/r1/scores", "GET" to "/api/compare?a=r1&b=r2",
            // slice 2
            "POST" to "/api/validate", "GET" to "/api/pipelines/live-extract/diff?a=1&b=draft", "GET" to "/api/publish/history", "GET" to "/api/live",
            "POST" to "/api/runs/estimate-ab", "POST" to "/api/runs/ab", "GET" to "/api/asks", "GET" to "/api/asks/a1", "POST" to "/api/asks/a1/replay",
        )
        for ((m, p) in routes) {
            assertEquals("$m $p without a token", 401, call(m, p, null, if (m == "GET") null else buildJsonObject { }).status)
            assertEquals("$m $p with a wrong token", 401, call(m, p, "not-a-token", if (m == "GET") null else buildJsonObject { }).status)
        }
    }
}
