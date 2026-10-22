package com.rokid.context.phone.canvas.api

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import com.rokid.context.canvas.CanvasRepository
import com.rokid.context.canvas.Estimate
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.CanvasRunEntity
import com.rokid.context.memory.db.CanvasRunItemEntity
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.FrameEntity
import com.rokid.context.memory.db.MemoryEntity
import com.rokid.context.memory.db.MemoryTier
import com.rokid.context.memory.db.ReviewState
import com.rokid.context.memory.frames.FrameImages
import com.rokid.context.memory.frames.TinkFrameCipher
import com.rokid.context.model.ModelProvider
import com.rokid.context.phone.canvas.server.CanvasAuth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CanvasBackendImplTest {
    @get:Rule val tmp = TemporaryFolder()
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val auth = CanvasAuth({ System.currentTimeMillis() })
    private lateinit var ai: AiGraph
    private lateinit var backend: CanvasBackendImpl
    private var lan = false
    private val jpeg: ByteArray = ByteArrayOutputStream().also { Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()

    private fun sentIds(p: String) = Regex("frame_id: (f\\d)").findAll(p).map { it.groupValues[1] }.toList().distinct()
    private fun extractReply(ids: List<String>) =
        """{"facts":[${ids.joinToString(",") { """{"text":"Mug in $it","type":"object","confidence":0.9,"tags":[],"source_frame_ids":["$it"]}""" }}],"captions":[${ids.joinToString(",") { """{"frame_id":"$it","caption":"Counter $it"}""" }}]}"""

    @Before fun setUp() {
        MemoryGraph.resetForTests(DatabaseFactory.inMemory(app))
        FrameImages.configureForTests(TinkFrameCipher.inMemoryForTests()) { true }
        ai = AiGraph(MemoryGraph.get(app), InMemoryKeyVault(), InMemorySeedState(), ProviderBuilder { _, _, _ ->
            object : ModelProvider { override suspend fun generate(prompt: String): String = extractReply(sentIds(prompt)) }
        }).also { AiGraph.resetForTests(it) }
        backend = CanvasBackendImpl.create(app, ai, auth, { lan }, scope)
    }

    @After fun tearDown() {
        scope.cancel()
        FrameImages.configureForTests(null) { false }
        AiGraph.resetForTests(null); MemoryGraph.get(app).db.close(); MemoryGraph.resetForTests(null)
    }

    private fun bind() = runBlocking {
        ai.endpoints.save(Endpoint("az", "Azure", EndpointKind.AZURE_OPENAI, "https://az.example", "2024-10-21", listOf("gpt"), setOf(Capability.CHAT, Capability.VISION), 1), apiKey = "k").getOrThrow()
        Role.entries.filter { it != Role.EMBED }.forEach { ai.bindings.set(RoleBinding(it, "az", "gpt")).getOrThrow() }
    }

    /** A sealed JPEG in the archive and its `frame` row; the source starts with "rokid" like real frames. */
    private fun seed(id: String, ts: Long): FrameEntity {
        val file = File(tmp.root, "rokid_${ts}_$id.jpg").apply { writeBytes(FrameImages.forDisk(jpeg)) }
        return FrameEntity(id, ts, file.path, "rokid").also { runBlocking { MemoryGraph.get(app).db.frameDao().insertIgnore(it) } }
    }

    private suspend fun insertPublish(kind: String, mode: String?, version: Int) = MemoryGraph.get(app).db.canvasDao().insertPublish(
        com.rokid.context.memory.db.CanvasPublishEntity(pipelineId = "live-extract", version = version, hash = "h$version", publishedAt = 1, rolledBackAt = null, snapshotJson = "{}", kind = kind, mode = mode))

    @Test fun interpreterPublishIsNotThePromptsLiveOrActivePublish() = runBlocking<Unit> {
        insertPublish("interpreter", "LIVE", 1)
        val live = backend.pipelines().first { it.jsonObject["id"]!!.jsonPrimitive.content == "live-extract" }.jsonObject
        assertEquals("null", live["live"].toString())
        assertEquals("false", backend.activePublish()["active"]!!.jsonPrimitive.content)
    }

    @Test fun anOlderPromptsPublishStaysTheActiveOneUnderANewerInterpreterRow() = runBlocking<Unit> {
        val promptsId = insertPublish("prompts", null, 1)
        insertPublish("interpreter", "SHADOW", 2)
        val a = backend.activePublish()
        assertEquals(promptsId.toString(), a["publishId"]!!.jsonPrimitive.content)
        val live = backend.pipelines().first { it.jsonObject["id"]!!.jsonPrimitive.content == "live-extract" }.jsonObject
        assertEquals("1", live["live"]!!.jsonObject["version"]!!.jsonPrimitive.content)
    }

    @Test fun pipelinesIncludeBuiltIns() = runBlocking<Unit> {
        val list = backend.pipelines()
        val ids = list.map { it.jsonObject["id"]!!.jsonPrimitive.content }
        assertTrue("live-extract" in ids)
        assertTrue("intent-draft" in ids)
        val live = list.first { it.jsonObject["id"]!!.jsonPrimitive.content == "live-extract" }.jsonObject
        assertEquals("true", live["builtIn"]!!.jsonPrimitive.content)
        assertTrue(live["versions"] is JsonArray)
        assertEquals("null", live["live"].toString())
    }

    @Test fun pipelineHasLayoutAndProblems() = runBlocking<Unit> {
        val p = checkNotNull(backend.pipeline("live-extract"))
        assertTrue(p["json"]!!.jsonPrimitive.content.contains("\"extract\""))
        assertEquals(0, p["problems"]!!.jsonArray.size)
        assertEquals("true", p["liveShape"]!!.jsonPrimitive.content)
        assertTrue(p["layout"]!!.jsonObject["cells"]!!.jsonObject.containsKey("extract"))
        val prompts = p["activePrompts"]!!.jsonObject
        assertTrue(prompts.containsKey("extract"))
        assertTrue(prompts.containsKey("critic"))
        assertFalse(prompts.containsKey("photos"))
        assertTrue(prompts["extract"]!!.jsonObject["template"]!!.jsonPrimitive.content.isNotEmpty())
        assertTrue(prompts["extract"]!!.jsonObject["version"]!!.jsonPrimitive.int >= 1)
        assertNull(backend.pipeline("nope"))
        // A copy is editable; an invalid save is kept and reported.
        val copy = backend.duplicate("live-extract", "Mine")
        val draft = checkNotNull(backend.pipeline(copy))
        assertEquals("false", draft["builtIn"]!!.jsonPrimitive.content)
        val saved = backend.saveDraft(copy, """{"schema":1,"id":"$copy","name":"Mine","nodes":[],"edges":[]}""", draft["draftRev"]!!.jsonPrimitive.int)
        assertTrue(saved is CanvasRepository.SaveResult.Saved)
        assertTrue((saved as CanvasRepository.SaveResult.Saved).problems.isNotEmpty())
        assertTrue(checkNotNull(backend.pipeline(copy))["problems"]!!.jsonArray.isNotEmpty())
    }

    @Test fun estimateListsModels() = runBlocking<Unit> {
        seed("f1", 1_000); seed("f2", 2_000); seed("f3", 3_000)
        val unbound = backend.estimate("live-extract", null, listOf("f1", "f2", "f3")) as Estimate.Ok
        assertEquals(3, unbound.photos)
        assertTrue(unbound.models.all { it.endpoint == "not set" })
        bind()
        val ok = backend.estimate("live-extract", null, listOf("f1", "f2", "f3")) as Estimate.Ok
        assertTrue(ok.models.isNotEmpty())
        assertTrue(ok.models.all { it.endpoint == "Azure" && it.model == "gpt" })
        assertTrue(ok.confirmToken.isNotEmpty())
        assertTrue(backend.estimate("live-extract", null, emptyList()) is Estimate.Refused)
    }

    @Test fun endToEndRunOnFakeFrames() = runBlocking<Unit> {
        bind()
        val ids = listOf("f1", "f2", "f3")
        ids.forEachIndexed { i, id -> seed(id, 1_000L + i) }
        val est = backend.estimate("live-extract", null, ids) as Estimate.Ok
        val runId = backend.startRun("live-extract", null, ids, est.confirmToken)
        withTimeout(30_000) { while (backend.run(runId)!!["status"]!!.jsonPrimitive.content == "RUNNING") delay(50) }
        val run = checkNotNull(backend.run(runId))
        assertEquals("DONE", run["status"]!!.jsonPrimitive.content)
        val items = run["items"]!!.jsonArray.map { it.jsonObject }
        for (node in listOf("photos", "extract", "trust", "write")) assertEquals(node, 3, items.count { it["nodeId"]!!.jsonPrimitive.content == node })
        assertTrue(items.filter { it["nodeId"]!!.jsonPrimitive.content == "photos" }.all { it["status"]!!.jsonPrimitive.content == "OK" })
        assertNotNull(run["progress"]!!.jsonObject["done"])
        assertTrue(run["models"] is JsonArray)
        assertEquals(1, backend.runs().size)
        // Nothing live was written.
        val db = MemoryGraph.get(app).db
        assertEquals(0, db.memoryDao().all().size)
        // A score is stored and shows up in the detail; the run can then be deleted.
        backend.score(runId, "f1", "extract", 1, "good")
        assertEquals(1, checkNotNull(backend.run(runId))["scores"]!!.jsonArray.size)
        assertTrue(backend.deleteRun(runId))
        assertNull(backend.run(runId))
    }

    @Test fun frameLiveMemoriesListed() = runBlocking<Unit> {
        seed("f1", 5_000)
        MemoryGraph.get(app).db.memoryDao().insert(MemoryEntity("m1", "f1", "A red mug", "object", "", MemoryTier.SHORT, 0.9, ReviewState.OK, true, 5_100, false, "a red mug"))
        val f = checkNotNull(backend.frame("f1"))
        val live = f["live"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("m1"), live.map { it["id"]!!.jsonPrimitive.content })
        assertEquals("A red mug", live[0]["text"]!!.jsonPrimitive.content)
        assertEquals("object", live[0]["type"]!!.jsonPrimitive.content)
        val list = backend.frames(Long.MAX_VALUE, 10, false)
        assertEquals("true", list[0].jsonObject["hasLiveMemories"]!!.jsonPrimitive.content)
        assertNull(backend.frame("missing"))
    }

    @Test fun thumbIsSmallJpeg() = runBlocking<Unit> {
        seed("f1", 1_000)
        val full = checkNotNull(backend.frameImage("f1", thumb = false))
        assertTrue(full.contentEquals(jpeg)) // the sealed file is opened to the stored JPEG
        val thumb = checkNotNull(backend.frameImage("f1", thumb = true))
        assertTrue(thumb.size < full.size)
        assertEquals(0xFF, thumb[0].toInt() and 0xFF)
        assertEquals(0xD8, thumb[1].toInt() and 0xFF)
        val b = BitmapFactory.decodeByteArray(thumb, 0, thumb.size)
        assertEquals(160, maxOf(b.width, b.height))
        assertNull(backend.frameImage("missing", thumb = true))
    }

    @Test fun compareDiffs() = runBlocking<Unit> {
        val dao = MemoryGraph.get(app).db.canvasDao()
        suspend fun run(id: String) = dao.insertRun(CanvasRunEntity(id, "p", null, "h", "{}", "DONE", 2, 0, 0, 0, 1, 2, "[]"))
        suspend fun item(run: String, frame: String, node: String, out: String?) =
            dao.upsertItem(CanvasRunItemEntity(run, frame, node, if (out == null) "ERROR" else "OK", null, out, if (out == null) "TIMEOUT" else null, null, null, null, null, null, null))
        run("ra"); run("rb")
        item("ra", "f1", "extract", """{"kind":"json","value":{"a":1,"b":2}}""")
        item("rb", "f1", "extract", """{"kind":"json","value":{"a":1,"b":3}}""")
        item("ra", "f1", "onlyA", """{"kind":"photo"}""")
        item("ra", "f2", "extract", """{"kind":"photo"}""") // frame only in run a
        val c = checkNotNull(backend.compare("ra", "rb"))
        val frames = c["frames"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("f1"), frames.map { it["frameId"]!!.jsonPrimitive.content })
        val nodes = frames[0]["nodes"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("extract"), nodes.map { it["nodeId"]!!.jsonPrimitive.content })
        val diff = nodes[0]["diff"]!!.jsonArray.map { it.jsonObject }
        assertTrue(diff.any { it["op"]!!.jsonPrimitive.content == "removed" && it["text"]!!.jsonPrimitive.content.contains("2") })
        assertTrue(diff.any { it["op"]!!.jsonPrimitive.content == "added" && it["text"]!!.jsonPrimitive.content.contains("3") })
        assertTrue(diff.any { it["op"]!!.jsonPrimitive.content == "same" })
        assertNull(backend.compare("ra", "missing"))
    }

    @Test fun checkPromptReportsSchemaProblems() = runBlocking<Unit> {
        assertTrue(backend.checkPrompt("ai.prompt", "t", "not json").isNotEmpty())
        assertTrue(backend.checkPrompt("ai.prompt", "t", "[1]").isNotEmpty())
        assertTrue(backend.checkPrompt("ai.prompt", "t", """{"type":"object"}""").isEmpty())
        assertTrue(backend.checkPrompt("ai.vision_extract", "t", null).isEmpty())
        assertTrue(backend.checkPrompt("ai.vision_extract", "t", "{not json").isNotEmpty())
        assertTrue(backend.checkPrompt("route.switch", "t", null).isNotEmpty()) // no prompt on this node
    }

    @Test fun sessionHasNoTokenAndLogoutEndsIt() = runBlocking<Unit> {
        val token = auth.newWebViewToken()
        val s = backend.session(token)
        assertFalse(s.toString().contains(token))
        assertEquals("false", s["lan"]!!.jsonPrimitive.content)
        lan = true
        assertEquals("true", backend.session(token)["lan"]!!.jsonPrimitive.content)
        assertTrue(auth.check(token))
        backend.logout(token)
        assertFalse(auth.check(token))
    }

    @Test fun nodeTypesListPortsAndBranches() {
        val types = backend.nodeTypes().map { it.jsonObject }
        val critic = types.first { it["type"]!!.jsonPrimitive.content == "ai.critic" }
        assertEquals(listOf("pass", "fail"), critic["branches"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("EXTRACTION", critic["output"]!!.jsonPrimitive.content)
        assertTrue(critic["inputs"]!!.jsonArray.isNotEmpty())
        assertTrue(types.size >= 7)
    }

    @Test fun recoverAtStartMarksRunningRunsInterrupted() = runBlocking<Unit> {
        val dao = MemoryGraph.get(app).db.canvasDao()
        dao.insertRun(CanvasRunEntity("r1", "p", null, "h", "{}", "RUNNING", 1, 0, 0, 0, 1, null, "[]"))
        backend.start()
        backend.start() // once
        assertEquals("INTERRUPTED", checkNotNull(backend.run("r1"))["status"]!!.jsonPrimitive.content)
    }
}
