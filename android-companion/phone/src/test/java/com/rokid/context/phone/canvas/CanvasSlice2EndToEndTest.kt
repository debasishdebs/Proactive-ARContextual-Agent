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
import com.rokid.context.ai.canvas.PipelineJson
import com.rokid.context.canvas.CanvasPublisher
import com.rokid.context.canvas.LiveDeps
import com.rokid.context.canvas.LiveExtract
import com.rokid.context.canvas.PipelineInterpreter
import com.rokid.context.canvas.RunContext
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.AskEntity
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.FrameEntity
import com.rokid.context.memory.db.MemoryEntity
import com.rokid.context.memory.db.MemoryTier
import com.rokid.context.memory.db.ReviewState
import com.rokid.context.memory.frames.FrameImages
import com.rokid.context.memory.frames.TinkFrameCipher
import com.rokid.context.memory.retrieval.RetrievalMode
import com.rokid.context.memory.retrieval.RetrievalSettings
import com.rokid.context.model.CallClass
import com.rokid.context.model.ModelImageInput
import com.rokid.context.model.ModelProvider
import com.rokid.context.orchestration.AnswerService
import com.rokid.context.orchestration.ExtractPhoto
import com.rokid.context.orchestration.MemoryWipe
import com.rokid.context.phone.canvas.api.AskPhotoBinding
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
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
 * Slice 2 on a real loopback server (spec section 14): validate, A/B with the intent summary, Shadow then Live publish, the live trace view,
 * unified rollback by expected id and the history, and the Ask debug flow. Only the model and the network interface are faked.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CanvasSlice2EndToEndTest {
    @get:Rule val tmp = TemporaryFolder()
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val auth = CanvasAuth({ System.currentTimeMillis() })
    private val sent = CopyOnWriteArrayList<String>()
    private val client = OkHttpClient.Builder().callTimeout(60, TimeUnit.SECONDS).build()
    private lateinit var ai: AiGraph
    private lateinit var server: CanvasServer
    private var base = ""
    private val jpeg: ByteArray = ByteArrayOutputStream().also { Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()

    /** One activity per frame, so every branch of the intent switch (and `other`) is reached. */
    private val activity = mapOf("f1" to "workout", "f2" to "walking", "f3" to "driving", "f4" to "computer_work", "f5" to "sitting_idle", "f6" to "other")
    private fun sentIds(p: String) = Regex("frame_id: (f\\d)").findAll(p).map { it.groupValues[1] }.toList().distinct()
    private fun extractReply(ids: List<String>) =
        """{"facts":[${ids.joinToString(",") { """{"text":"Mug in $it","type":"object","confidence":0.9,"tags":[],"source_frame_ids":["$it"]}""" }}],"captions":[${ids.joinToString(",") { """{"frame_id":"$it","caption":"Counter $it"}""" }}]}"""

    @Before fun setUp() {
        MemoryGraph.resetForTests(DatabaseFactory.inMemory(app))
        FrameImages.configureForTests(TinkFrameCipher.inMemoryForTests()) { true }
        ai = AiGraph(MemoryGraph.get(app), InMemoryKeyVault(), InMemorySeedState(), ProviderBuilder { _, _, _ ->
            object : ModelProvider {
                override suspend fun generate(prompt: String): String {
                    sent += prompt
                    return when {
                        prompt.contains("decide what the wearer is doing") -> """{"activity":"${activity[sentIds(prompt).firstOrNull()] ?: "other"}","confidence":0.9,"reason":"r"}"""
                        prompt.contains("BEGIN_DATA question") -> """{"answer":"In the fridge","sources":["m1"],"proposedAction":null}"""
                        else -> extractReply(sentIds(prompt))
                    }
                }
            }
        }).also { AiGraph.resetForTests(it) }
        runBlocking {
            ai.endpoints.save(Endpoint("az", "Azure", EndpointKind.AZURE_OPENAI, "https://az.example", "2024-10-21", listOf("gpt"), setOf(Capability.CHAT, Capability.VISION), 1), apiKey = "k").getOrThrow()
            Role.entries.filter { it != Role.EMBED }.forEach { ai.bindings.set(RoleBinding(it, "az", "gpt")).getOrThrow() }
        }
        val backend = CanvasBackendImpl.create(
            app, ai, auth, { false }, scope,
            answers = { AnswerService(MemoryGraph.get(app), ai.runner, ai.retriever, { RetrievalSettings(mode = RetrievalMode.LEXICAL, topK = 8) }) },
        )
        lateinit var s: CanvasServer
        val router = CanvasRouter(auth, HostPolicy({ s.port ?: 0 }, { null }), CanvasApi(backend), AssetSource { null })
        s = CanvasServer(router, bindLan = false)
        server = s
        base = "http://127.0.0.1:${server.start()}"
    }

    @After fun tearDown() {
        server.stop()
        scope.cancel()
        AskPhotoBinding.reset()
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
    private fun ok(r: Reply): JsonObject { assertEquals(r.body.toString(), 200, r.status); return r.body!!.jsonObject }
    private fun ids(vararg v: String) = buildJsonArray { v.forEach { add(JsonPrimitive(it)) } }

    private fun waitFinished(token: String, vararg runIds: String) {
        val end = System.currentTimeMillis() + 60_000
        while (System.currentTimeMillis() < end) {
            val all = runIds.map { call("GET", "/api/runs/$it", token).body!!.jsonObject["status"]!!.jsonPrimitive.content }
            if (all.none { it == "RUNNING" || it == "QUEUED" }) return
            Thread.sleep(50)
        }
        throw AssertionError("runs did not finish")
    }

    @Test fun slice2Flow() {
        val frames = listOf("f1", "f2", "f3", "f4", "f5", "f6")
        frames.forEachIndexed { i, id -> seed(id, 1_000L + i) }
        val dao = MemoryGraph.get(app).db.canvasDao()

        // 1. pair
        val r = call("POST", "/api/pair", null, buildJsonObject { put("code", auth.newCode().code) })
        assertEquals(200, r.status)
        val token = r.body!!.jsonObject["token"]!!.jsonPrimitive.content

        // 2. duplicate intent-draft; two per-intent draft prompts through PUT
        val id = ok(call("POST", "/api/pipelines", token, buildJsonObject { put("from", "intent-draft"); put("name", "Mine") }))["id"]!!.jsonPrimitive.content
        val pipe = ok(call("GET", "/api/pipelines/$id", token))
        val decoded = PipelineJson.decode(pipe["json"]!!.jsonPrimitive.content).getOrThrow()
        val edited = decoded.copy(nodes = decoded.nodes.map { n ->
            val marker = when (n.id) { "extract_workout" -> "WORKOUT-MARKER"; "extract_walking" -> "WALKING-MARKER"; else -> return@map n }
            val prompt = n.params["prompt"] as JsonObject
            val template = prompt["template"]!!.jsonPrimitive.content + "\n" + marker
            n.copy(params = JsonObject(n.params + ("prompt" to JsonObject(prompt + ("template" to JsonPrimitive(template))))))
        })
        val saved = ok(call("PUT", "/api/pipelines/$id", token, buildJsonObject { put("json", PipelineJson.encode(edited)); put("draftRev", pipe["draftRev"]!!.jsonPrimitive.int) }))
        assertEquals(0, saved["problems"]!!.jsonArray.size)

        // 3. validate: no problems, interpretable, the rev comes back
        val v = ok(call("POST", "/api/validate", token, buildJsonObject { put("json", PipelineJson.encode(edited)); put("rev", 5) }))
        assertEquals(5, v["rev"]!!.jsonPrimitive.int)
        assertEquals(0, v["problems"]!!.jsonArray.size)
        assertEquals(0, v["interpretable"]!!.jsonObject["problems"]!!.jsonArray.size)
        assertEquals(2, v["interpretable"]!!.jsonObject["callRange"]!!.jsonArray.size)
        assertTrue(v["layout"]!!.jsonObject["cells"]!!.jsonObject.containsKey("route"))

        // 4. estimate-ab, start the A/B, poll until both are done, compare shows agreement and branches
        val a = buildJsonObject { put("pipelineId", id) }
        val b = buildJsonObject { put("pipelineId", "intent-draft") }
        val est = ok(call("POST", "/api/runs/estimate-ab", token, buildJsonObject { put("a", a); put("b", b); put("frameIds", ids(*frames.toTypedArray())) }))
        val ab = ok(call("POST", "/api/runs/ab", token, buildJsonObject { put("a", a); put("b", b); put("frameIds", ids(*frames.toTypedArray())); put("confirmToken", est["confirmToken"]!!.jsonPrimitive.content) }))
        val runA = ab["runA"]!!.jsonPrimitive.content; val runB = ab["runB"]!!.jsonPrimitive.content
        assertEquals(setOf("group", "runA", "runB"), ab.keys)
        waitFinished(token, runA, runB)
        val ra = ok(call("GET", "/api/runs/$runA", token)); val rb = ok(call("GET", "/api/runs/$runB", token))
        assertEquals("DONE", ra["status"]!!.jsonPrimitive.content); assertEquals("DONE", rb["status"]!!.jsonPrimitive.content)
        assertEquals(ab["group"]!!.jsonPrimitive.content, ra["abGroup"]!!.jsonPrimitive.content)
        assertEquals(ab["group"]!!.jsonPrimitive.content, rb["abGroup"]!!.jsonPrimitive.content)
        assertTrue(ra["usage"]!!.jsonObject["calls"]!!.jsonPrimitive.int > 0)
        assertEquals(1, ra["branches"]!!.jsonObject["route"]!!.jsonObject["workout"]!!.jsonPrimitive.int)
        assertTrue("the model saw the per-intent draft", sent.any { "WORKOUT-MARKER" in it } && sent.any { "WALKING-MARKER" in it })
        val cmp = ok(call("GET", "/api/compare?a=$runA&b=$runB", token))
        assertEquals(1.0, cmp["agreement"]!!.jsonObject["route"]!!.jsonPrimitive.content.toDouble(), 0.0)
        assertEquals(1, cmp["branches"]!!.jsonObject["b"]!!.jsonObject["route"]!!.jsonObject["driving"]!!.jsonPrimitive.int)

        // 5. freeze, publish in Shadow: the active stack shows it
        val v1 = ok(call("POST", "/api/pipelines/$id/freeze", token, buildJsonObject { put("note", "first") }))["version"]!!.jsonPrimitive.int
        val shadow = ok(call("POST", "/api/pipelines/$id/versions/$v1/publish", token, buildJsonObject { put("mode", "shadow") }))
        assertEquals("SHADOW", shadow["mode"]!!.jsonPrimitive.content)
        val stack1 = ok(call("GET", "/api/publish", token))
        assertEquals("SHADOW", stack1["interpreter"]!!.jsonObject["mode"]!!.jsonPrimitive.content)
        assertEquals(shadow["publishId"]!!.jsonPrimitive.content, stack1["interpreter"]!!.jsonObject["id"]!!.jsonPrimitive.content)

        // 6. publish Live; drive the live seam directly; the live view lists the traces with labels
        val live = ok(call("POST", "/api/pipelines/$id/versions/$v1/publish", token, buildJsonObject { put("mode", "live") }))
        val liveId = live["publishId"]!!.jsonPrimitive.content.toLong()
        val deps = LiveDeps(
            dao = dao, runner = ai.runner,
            context = { runId, cc, lv -> RunContext(runId, ai.runner, ai.router, ai.prompts, ai.persona, { role -> ai.bindings.get(role) }, callClass = cc, liveness = lv) },
            interpreter = PipelineInterpreter(),
            publisher = { pid, reason -> CanvasPublisher.autoRollbackOn(dao, System::currentTimeMillis, pid, reason) },
            shadowAllowed = { false },
        )
        val photo = { fid: String -> ExtractPhoto(fid, "ts=1", ModelImageInput("image/jpeg", "AAAA")) }
        val generation = MemoryWipe.generation.get()
        val out = runBlocking {
            LiveExtract.batch(deps, listOf("f1", "f2", "f3").map(photo), CallClass.EXTRACT, com.rokid.context.phone.canvas.AllLive).also { LiveExtract.commit(deps, it.effects, generation, setOf("f1", "f2", "f3")) }
        }
        assertFalse("the interpreter, not the fallback, extracted", out.effects.fellBack)
        val view = ok(call("GET", "/api/live?limit=10", token))
        assertEquals("LIVE", view["mode"]!!.jsonPrimitive.content)
        val byFrame = view["frames"]!!.jsonArray.map { it.jsonObject }.associateBy { it["frameId"]!!.jsonPrimitive.content }
        assertEquals(setOf("f1", "f2", "f3"), byFrame.keys)
        assertEquals("workout", byFrame.getValue("f1")["label"]!!.jsonPrimitive.content)
        assertEquals("walking", byFrame.getValue("f2")["label"]!!.jsonPrimitive.content)
        assertEquals("driving", byFrame.getValue("f3")["label"]!!.jsonPrimitive.content)
        assertTrue(byFrame.values.all { it["batch"]!!.jsonPrimitive.content == "OK" })
        assertEquals(3, view["interpreted"]!!.jsonPrimitive.int); assertEquals(0, view["fallbacks"]!!.jsonPrimitive.int)
        assertEquals(1, view["labels"]!!.jsonObject["workout"]!!.jsonPrimitive.int)

        // 7. rollback by expected id: Live goes, Shadow is active again; again: none; history lists both with rolledBackAt
        val stale = call("POST", "/api/publish/rollback", token, buildJsonObject { put("expectedId", shadow["publishId"]!!.jsonPrimitive.content.toLong()) })
        assertEquals("an older publish is not undone by a stale click", 409, stale.status)
        ok(call("POST", "/api/publish/rollback", token, buildJsonObject { put("expectedId", liveId) }))
        val afterOne = ok(call("GET", "/api/publish", token))
        assertEquals("SHADOW", afterOne["interpreter"]!!.jsonObject["mode"]!!.jsonPrimitive.content)
        ok(call("POST", "/api/publish/rollback", token, buildJsonObject { put("expectedId", shadow["publishId"]!!.jsonPrimitive.content.toLong()) }))
        assertEquals("null", ok(call("GET", "/api/publish", token))["interpreter"].toString())
        val history = call("GET", "/api/publish/history", token).body!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("LIVE", "SHADOW"), history.map { it["mode"]!!.jsonPrimitive.content })
        assertTrue(history.all { it["rolledBackAt"]!!.jsonPrimitive.content != "null" && it["kind"]!!.jsonPrimitive.content == "interpreter" })

        // 8. the ask flow: view has scores, the replay leaves the ask count unchanged
        runBlocking {
            MemoryGraph.get(app).db.memoryDao().insert(MemoryEntity("m1", null, "milk carton in the fridge", "object", "", MemoryTier.SHORT, 0.9, ReviewState.OK, true, 10, false, "milk carton in the fridge"))
            MemoryGraph.get(app).db.askDao().upsert(AskEntity("a1", 1_000, "milk", "In the fridge", "[\"m1\"]", null, "phone_typed", false))
        }
        val askView = ok(call("GET", "/api/asks/a1", token))
        assertTrue(askView["hits"]!!.jsonArray.single().jsonObject["score"]!!.jsonPrimitive.content.toDouble() > 0.0)
        val before = call("GET", "/api/asks", token).body!!.jsonArray.size
        val replay = ok(call("POST", "/api/asks/a1/replay", token, buildJsonObject { put("template", askView["activeTemplate"]!!.jsonPrimitive.content) }))
        assertEquals("In the fridge", replay["answer"]!!.jsonPrimitive.content)
        assertEquals(before, call("GET", "/api/asks", token).body!!.jsonArray.size)
    }
}
