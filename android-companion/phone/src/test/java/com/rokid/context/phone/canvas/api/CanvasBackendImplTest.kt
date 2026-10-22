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
import com.rokid.context.ai.contract.PromptKey
import com.rokid.context.canvas.AskDebug
import com.rokid.context.canvas.AskDebugPhotos
import com.rokid.context.canvas.AskPhotoRecall
import com.rokid.context.canvas.CanvasRepository
import com.rokid.context.orchestration.AnswerService
import com.rokid.context.memory.db.AskEntity
import com.rokid.context.memory.retrieval.RetrievalMode
import com.rokid.context.memory.retrieval.RetrievalSettings
import com.rokid.context.model.ModelReply
import com.rokid.context.model.ModelRequest
import com.rokid.context.canvas.Estimate
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.CanvasLiveTraceEntity
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
import kotlinx.serialization.json.JsonPrimitive
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
            object : ModelProvider {
                override suspend fun generate(prompt: String): String = extractReply(sentIds(prompt))
                override suspend fun complete(request: ModelRequest): ModelReply {
                    requests += request
                    gate?.await()
                    val text = if (request.prompt.contains("BEGIN_DATA question")) """{"answer":"In the fridge","sources":[],"proposedAction":null}""" else generate(request.prompt)
                    return ModelReply(text, 100, 10)
                }
            }
        }).also { AiGraph.resetForTests(it) }
        backend = newBackend()
    }

    private val requests = java.util.concurrent.CopyOnWriteArrayList<ModelRequest>()
    @Volatile private var gate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    private fun newBackend(photos: () -> AskDebugPhotos = AskPhotoBinding::current) = CanvasBackendImpl.create(
        app, ai, auth, { lan }, scope,
        answers = { AnswerService(MemoryGraph.get(app), ai.runner, ai.retriever, { RetrievalSettings(mode = RetrievalMode.LEXICAL, topK = 8) }) },
        askPhotos = photos,
    )

    @After fun tearDown() {
        AskPhotoBinding.reset()
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
        val list = backend.frames(null, Long.MAX_VALUE, 10, false)
        assertEquals("true", list[0].jsonObject["hasLiveMemories"]!!.jsonPrimitive.content)
        assertNull(backend.frame("missing"))
    }

    @Test fun thumbIsSmallJpeg() = runBlocking<Unit> {
        seed("f1", 1_000)
        val fullImage = checkNotNull(backend.frameImage("f1", thumb = false))
        val full = fullImage.bytes
        assertEquals("image/jpeg", fullImage.contentType)
        assertTrue(full.contentEquals(jpeg)) // the sealed file is opened to the stored JPEG
        val thumbImage = checkNotNull(backend.frameImage("f1", thumb = true))
        val thumb = thumbImage.bytes
        assertEquals("image/jpeg", thumbImage.contentType)
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

    // ---- slice 2, Task 9 (commit 1): node types, validate, diff, freeze rev, day filter ----

    @Test fun nodeTypesCarryCategory() {
        val types = backend.nodeTypes().map { it.jsonObject }.associateBy { it["type"]!!.jsonPrimitive.content }
        fun s(t: String, k: String) = types.getValue(t)[k]!!.jsonPrimitive.content
        assertEquals(mapOf(
            "input.photos" to "photos", "ai.vision_extract" to "extract", "gate.local_critic" to "trust", "ai.critic" to "critic",
            "ai.prompt" to "prompt", "route.switch" to "route", "memory.write" to "write",
        ), types.keys.associateWith { s(it, "short") })
        assertEquals(mapOf(
            "input.photos" to "Inputs", "ai.vision_extract" to "AI", "gate.local_critic" to "Checks", "ai.critic" to "AI",
            "ai.prompt" to "AI", "route.switch" to "Routing", "memory.write" to "Memory",
        ), types.keys.associateWith { s(it, "category") })
        // exactly one photo input per pipeline: not offered by the palette
        assertEquals(listOf("input.photos"), types.keys.filter { s(it, "palette") == "false" })
        assertTrue(types.keys.all { s(it, "domain") == "ASYNC" })
        // the editor needs the switch's field param to edit it
        val params = types.getValue("route.switch")["params"]!!.jsonArray.map { it.jsonObject }
        assertEquals("text", params.first { it["name"]!!.jsonPrimitive.content == "field" }["kind"]!!.jsonPrimitive.content)
        assertTrue(params.any { it["name"]!!.jsonPrimitive.content == "cases" })
        // every short id is valid as a node id prefix
        assertTrue(types.keys.all { Regex("^[a-z][a-z0-9]{0,15}$").matches(s(it, "short")) })
    }

    @Test fun validateReturnsLayoutAndProblems() = runBlocking<Unit> {
        val live = checkNotNull(backend.pipeline("live-extract"))["json"]!!.jsonPrimitive.content
        val ok = backend.validate(live)
        assertEquals(0, ok["problems"]!!.jsonArray.size)
        assertTrue(ok["layout"]!!.jsonObject["cells"]!!.jsonObject.containsKey("extract"))
        assertEquals("true", ok["liveShape"]!!.jsonPrimitive.content)
        val interp = ok["interpretable"]!!.jsonObject
        assertEquals(0, interp["problems"]!!.jsonArray.size)
        val range = interp["callRange"]!!.jsonArray.map { it.jsonPrimitive.content.toDouble() }
        assertEquals(2, range.size)
        assertTrue(range[0] > 0.0 && range[0] <= range[1])
        assertFalse(interp.containsKey("callFactor"))
        // a node with a bad param and an edge to nowhere: problems are objects with nodeId or edge
        val broken = live.replace(""""batch":3""", """"batch":9""")
        val bad = backend.validate(broken)
        val problems = bad["problems"]!!.jsonArray.map { it.jsonObject }
        assertTrue(problems.isNotEmpty())
        assertTrue(problems.all { it["text"]!!.jsonPrimitive.content.isNotEmpty() })
        assertTrue(problems.any { it["nodeId"]?.jsonPrimitive?.content == "extract" })
        assertTrue(bad["layout"] is JsonObject)
        assertEquals(listOf("Fix the pipeline's problems first"), bad["interpretable"]!!.jsonObject["problems"]!!.jsonArray.map { it.jsonPrimitive.content })
        // an edge problem names its edge
        val edgeBad = backend.validate(live.replace(""""to":"trust"""", """"to":"photos""""))
        assertTrue(edgeBad["problems"]!!.jsonArray.map { it.jsonObject }.any { it["edge"] is JsonObject })
        // validate saves nothing
        val before = checkNotNull(backend.pipeline("live-extract"))
        backend.validate(broken)
        assertEquals(before["draftRev"], checkNotNull(backend.pipeline("live-extract"))["draftRev"])
        assertEquals(before["json"], checkNotNull(backend.pipeline("live-extract"))["json"])
    }

    @Test fun validateUndecodable() = runBlocking<Unit> {
        for (text in listOf("", "not json", "[1,2]", """{"schema":99}""", "{" .repeat(5))) {
            val r = backend.validate(text)
            val problems = r["problems"]!!.jsonArray
            assertEquals(text, 1, problems.size)
            assertEquals("The pipeline JSON is not valid.", problems[0].jsonObject["text"]!!.jsonPrimitive.content)
            assertEquals("null", r["layout"].toString())
            assertEquals("false", r["liveShape"]!!.jsonPrimitive.content)
            assertEquals(1, r["interpretable"]!!.jsonObject["problems"]!!.jsonArray.size)
        }
        // the fixed text never echoes the input
        assertFalse(backend.validate("secret-text-xyz").toString().contains("secret-text-xyz"))
    }

    @Test fun pipelineDetailHasDetailedProblemsAndInterpretable() = runBlocking<Unit> {
        val p = checkNotNull(backend.pipeline("intent-draft"))
        assertEquals(0, p["problems"]!!.jsonArray.size)
        assertTrue(p["problemsDetailed"] is JsonArray)
        val interp = p["interpretable"]!!.jsonObject
        assertEquals(2, interp["callRange"]!!.jsonArray.size)
        assertTrue(interp["warnings"] is JsonArray)
        assertFalse(interp.containsKey("callFactor"))
        val copy = backend.duplicate("live-extract", "Mine")
        val d = checkNotNull(backend.pipeline(copy))
        backend.saveDraft(copy, d["json"]!!.jsonPrimitive.content.replace(""""batch":3""", """"batch":9"""), d["draftRev"]!!.jsonPrimitive.int)
        val bad = checkNotNull(backend.pipeline(copy))
        assertTrue(bad["problems"]!!.jsonArray.all { it is JsonPrimitive })
        assertEquals(bad["problems"]!!.jsonArray.size, bad["problemsDetailed"]!!.jsonArray.size)
        assertTrue(bad["problemsDetailed"]!!.jsonArray.any { it.jsonObject["nodeId"]?.jsonPrimitive?.content == "extract" })
    }

    @Test fun diffDraftVsVersion() = runBlocking<Unit> {
        val id = backend.duplicate("live-extract", "Mine")
        backend.freeze(id, "one", null)
        val d = checkNotNull(backend.pipeline(id))
        val edited = d["json"]!!.jsonPrimitive.content.replace(""""batch":3""", """"batch":2""")
        backend.saveDraft(id, edited, d["draftRev"]!!.jsonPrimitive.int)
        val diff = checkNotNull(backend.diff(id, "1", "draft"))
        val nodes = diff["nodes"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("extract"), nodes.map { it["nodeId"]!!.jsonPrimitive.content })
        assertEquals("changed", nodes[0]["kind"]!!.jsonPrimitive.content)
        assertEquals(listOf("batch"), nodes[0]["params"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(0, diff["edgesAdded"]!!.jsonArray.size)
        assertEquals("false", diff["renamed"]!!.jsonPrimitive.content)
        assertTrue(diff["prompts"] is JsonArray)
        // version against version, and the same on both sides is empty
        backend.freeze(id, "two", null)
        assertEquals(1, checkNotNull(backend.diff(id, "1", "2"))["nodes"]!!.jsonArray.size)
        assertEquals(0, checkNotNull(backend.diff(id, "2", "draft"))["nodes"]!!.jsonArray.size)
        assertNull(backend.diff(id, "9", "draft"))
        assertNull(backend.diff("nope", "1", "draft"))
        assertNull(backend.diff(id, "x", "draft"))
    }

    @Test fun freezeStaleRevThrowsAndFreezesNothing() = runBlocking<Unit> {
        val id = backend.duplicate("live-extract", "Mine")
        val d = checkNotNull(backend.pipeline(id))
        val rev = d["draftRev"]!!.jsonPrimitive.int
        val saved = backend.saveDraft(id, d["json"]!!.jsonPrimitive.content, rev) as CanvasRepository.SaveResult.Saved
        try { backend.freeze(id, "n", rev); org.junit.Assert.fail("stale rev must be refused") } catch (e: CanvasRepository.DraftChanged) { }
        assertEquals(0, MemoryGraph.get(app).db.canvasDao().versions(id).size)
        assertEquals(1, backend.freeze(id, "n", saved.rev)["version"]!!.jsonPrimitive.int)
        assertEquals(1, backend.freeze(id, "n", null)["version"]!!.jsonPrimitive.int) // no rev: today's behaviour (the same hash returns v1)
    }

    @Test fun framesAfterAndBefore() = runBlocking<Unit> {
        seed("f1", 1_000); seed("f2", 2_000); seed("f3", 3_000)
        fun ids(a: JsonArray) = a.map { it.jsonObject["id"]!!.jsonPrimitive.content }
        assertEquals(listOf("f3", "f2", "f1"), ids(backend.frames(null, Long.MAX_VALUE, 10, false)))
        assertEquals(listOf("f2"), ids(backend.frames(2_000, 3_000, 10, false))) // after inclusive, before exclusive
        assertEquals(listOf("f3", "f2"), ids(backend.frames(2_000, Long.MAX_VALUE, 10, false)))
    }

    // ---- slice 2, Task 9 (commit 2): stack, history, live view, pruning ----

    private suspend fun insertFull(kind: String, mode: String?, version: Int, at: Long, rolledBackAt: Long? = null, reason: String? = null, pipelineId: String = "live-extract") =
        MemoryGraph.get(app).db.canvasDao().insertPublish(com.rokid.context.memory.db.CanvasPublishEntity(
            pipelineId = pipelineId, version = version, hash = "h$version", publishedAt = at, rolledBackAt = rolledBackAt, snapshotJson = "{}", kind = kind, mode = mode, rollbackReason = reason))

    @Test fun historyListsRolledBackRows() = runBlocking<Unit> {
        val p = insertFull("prompts", null, 1, at = 100, rolledBackAt = 150)
        val a = insertFull("interpreter", "SHADOW", 2, at = 200)
        val b = insertFull("interpreter", "LIVE", 3, at = 300, rolledBackAt = 350, reason = "auto: 10 of 30 fell back")
        val rows = backend.publishHistory(50).map { it.jsonObject }
        assertEquals(listOf(b, a, p), rows.map { it["id"]!!.jsonPrimitive.content.toLong() })
        val live = rows[0]
        assertEquals("interpreter", live["kind"]!!.jsonPrimitive.content)
        assertEquals("LIVE", live["mode"]!!.jsonPrimitive.content)
        assertEquals("live-extract", live["pipelineId"]!!.jsonPrimitive.content)
        assertEquals(3, live["version"]!!.jsonPrimitive.int)
        assertEquals(300L, live["publishedAt"]!!.jsonPrimitive.content.toLong())
        assertEquals(350L, live["rolledBackAt"]!!.jsonPrimitive.content.toLong())
        assertEquals("auto: 10 of 30 fell back", live["rollbackReason"]!!.jsonPrimitive.content)
        assertEquals("null", rows[1]["rolledBackAt"].toString())
        assertEquals("null", rows[1]["rollbackReason"].toString())
        assertEquals("prompts", rows[2]["kind"]!!.jsonPrimitive.content)
        assertEquals("null", rows[2]["mode"].toString())
        assertEquals(2, backend.publishHistory(2).size)
        assertEquals(setOf("id", "kind", "mode", "pipelineId", "version", "publishedAt", "rolledBackAt", "rollbackReason"), rows[0].keys)
    }

    @Test fun activePublishShowsTheStackAndTheInterpreter() = runBlocking<Unit> {
        val p = insertFull("prompts", null, 1, at = 100)
        val i = insertFull("interpreter", "SHADOW", 2, at = 200)
        val dao = MemoryGraph.get(app).db.canvasDao()
        dao.upsertTrace(listOf(
            CanvasLiveTraceEntity(i, "f1", "_batch", "SHADOW", "OK", null, null, null, "b1", System.currentTimeMillis() - 3),
            CanvasLiveTraceEntity(i, "f2", "_batch", "SHADOW", "ERROR", null, null, "deadline", "b1", System.currentTimeMillis() - 2),
            CanvasLiveTraceEntity(i, "f3", "_batch", "SHADOW", "OK", null, null, null, "b2", System.currentTimeMillis() - 1),
        ))
        val a = backend.activePublish()
        assertEquals("true", a["active"]!!.jsonPrimitive.content) // the prompts publish, as before
        assertEquals(p, a["publishId"]!!.jsonPrimitive.content.toLong())
        val stack = a["stack"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf(i, p), stack.map { it["id"]!!.jsonPrimitive.content.toLong() })
        assertEquals("SHADOW", stack[0]["mode"]!!.jsonPrimitive.content)
        assertEquals("null", stack[1]["mode"].toString())
        val interp = a["interpreter"]!!.jsonObject
        assertEquals(i, interp["id"]!!.jsonPrimitive.content.toLong())
        assertEquals(3, interp["interpreted"]!!.jsonPrimitive.int)
        assertEquals(1, interp["fallbacks"]!!.jsonPrimitive.int)
        // nothing active: an empty stack and no interpreter
        dao.markRolledBack(i, 5); dao.markRolledBack(p, 5)
        val none = backend.activePublish()
        assertEquals("false", none["active"]!!.jsonPrimitive.content)
        assertEquals(0, none["stack"]!!.jsonArray.size)
        assertEquals("null", none["interpreter"].toString())
    }

    @Test fun liveViewListsTraces() = runBlocking<Unit> {
        val dao = MemoryGraph.get(app).db.canvasDao()
        backend.start()
        val id = backend.duplicate("intent-draft", "Mine")
        val v = backend.freeze(id, "n", null)["version"]!!.jsonPrimitive.int
        val pub = insertFull("interpreter", "LIVE", v, at = 10, pipelineId = id)
        val now = System.currentTimeMillis()
        fun t(frame: String, node: String, status: String = "OK", branch: String? = null, out: String? = null, err: String? = null, at: Long, batch: String = "b-$frame") =
            CanvasLiveTraceEntity(pub, frame, node, "LIVE", status, branch, out, err, batch, at)
        dao.upsertTrace(listOf(
            t("f1", "photos", at = now - 300), t("f1", "intent", out = """{"kind":"json"}""", at = now - 290), t("f1", "route", branch = "cooking", at = now - 280),
            t("f1", "extract_cooking", at = now - 270), t("f1", "_batch", at = now - 260),
            t("f2", "intent", at = now - 200), t("f2", "route", branch = "other", at = now - 190), t("f2", "extract_other", status = "ERROR", err = "deadline", at = now - 180),
            t("f2", "_batch", status = "ERROR", err = "deadline", at = now - 170),
            t("f3", "intent", at = now - 100), t("f3", "route", branch = "cooking", at = now - 90), t("f3", "_batch", at = now - 80),
        ))
        val view = backend.live(50)
        assertEquals("true", view["active"]!!.jsonPrimitive.content)
        assertEquals(pub, view["publishId"]!!.jsonPrimitive.content.toLong())
        assertEquals("LIVE", view["mode"]!!.jsonPrimitive.content)
        assertEquals(3, view["interpreted"]!!.jsonPrimitive.int)
        assertEquals(1, view["fallbacks"]!!.jsonPrimitive.int)
        val frames = view["frames"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("f3", "f2", "f1"), frames.map { it["frameId"]!!.jsonPrimitive.content }) // newest first
        val f1 = frames[2]
        assertEquals("cooking", f1["label"]!!.jsonPrimitive.content)
        assertEquals("OK", f1["batch"]!!.jsonPrimitive.content)
        assertEquals(now - 260, f1["at"]!!.jsonPrimitive.content.toLong())
        val nodes = f1["nodes"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("photos", "intent", "route", "extract_cooking"), nodes.map { it["nodeId"]!!.jsonPrimitive.content }) // _batch is the frame's own status
        assertEquals("cooking", nodes[2]["branch"]!!.jsonPrimitive.content)
        assertEquals("""{"kind":"json"}""", nodes[1]["output"]!!.jsonPrimitive.content)
        assertEquals(setOf("nodeId", "status", "branch", "output", "error"), nodes[0].keys)
        val f2 = frames[1]
        assertEquals("ERROR", f2["batch"]!!.jsonPrimitive.content)
        assertEquals("other", f2["label"]!!.jsonPrimitive.content)
        assertEquals("deadline", f2["nodes"]!!.jsonArray.last().jsonObject["error"]!!.jsonPrimitive.content)
        assertEquals(mapOf("cooking" to 2, "other" to 1), view["labels"]!!.jsonObject.mapValues { it.value.jsonPrimitive.int })
        assertEquals(2, backend.live(2)["frames"]!!.jsonArray.size)
        // no interpreter publish active: an empty view
        dao.markRolledBack(pub, 5)
        val off = backend.live(50)
        assertEquals("false", off["active"]!!.jsonPrimitive.content)
        assertEquals(0, off["frames"]!!.jsonArray.size)
    }

    @Test fun liveViewHasNoLabelWhenTheSwitchFailed() = runBlocking<Unit> {
        val dao = MemoryGraph.get(app).db.canvasDao()
        backend.start()
        val id = backend.duplicate("intent-draft", "Mine")
        val v = backend.freeze(id, "n", null)["version"]!!.jsonPrimitive.int
        val pub = insertFull("interpreter", "LIVE", v, at = 10, pipelineId = id)
        dao.upsertTrace(listOf(CanvasLiveTraceEntity(pub, "f1", "_batch", "LIVE", "ERROR", null, null, "internal", "b", System.currentTimeMillis())))
        val f = backend.live(10)["frames"]!!.jsonArray.single().jsonObject
        assertEquals("null", f["label"].toString())
        assertEquals(0, backend.live(10)["labels"]!!.jsonObject.size)
    }

    @Test fun liveReadPrunes() = runBlocking<Unit> {
        val dao = MemoryGraph.get(app).db.canvasDao()
        val pub = insertFull("interpreter", "LIVE", 1, at = 10)
        dao.upsertTrace(listOf(CanvasLiveTraceEntity(pub, "old", "_batch", "LIVE", "OK", null, null, null, "b", 1)))
        assertEquals(1, dao.traces(pub, 10).size)
        backend.live(10)
        assertEquals("a trace older than 7 days is pruned by a read", 0, dao.traces(pub, 10).size)
        // the history and the stack reads prune too (once an hour: a fresh backend has its own stamp)
        val backend2 = newBackend()
        dao.upsertTrace(listOf(CanvasLiveTraceEntity(pub, "old2", "_batch", "LIVE", "OK", null, null, null, "b2", 1)))
        backend2.publishHistory(5)
        assertEquals(0, dao.traces(pub, 10).size)
        val backend3 = newBackend()
        dao.upsertTrace(listOf(CanvasLiveTraceEntity(pub, "old3", "_batch", "LIVE", "OK", null, null, null, "b3", 1)))
        backend3.activePublish()
        assertEquals(0, dao.traces(pub, 10).size)
    }

    @Test fun publishInterpreterBlockedTextsAreFixed() = runBlocking<Unit> {
        backend.start()
        val id = backend.duplicate("intent-draft", "Mine")
        val v = backend.freeze(id, "n", null)["version"]!!.jsonPrimitive.int
        val out = backend.publishInterpreter(id, v, com.rokid.context.canvas.LiveMode.LIVE) as com.rokid.context.canvas.CanvasPublisher.Outcome.Blocked
        assertEquals(1, out.reasons.size)
        assertTrue(out.reasons.single().startsWith("Run this version first: "))
        assertEquals(listOf("Version not found"), (backend.publishInterpreter(id, 99, com.rokid.context.canvas.LiveMode.SHADOW) as com.rokid.context.canvas.CanvasPublisher.Outcome.Blocked).reasons)
    }

    @Test fun rollbackByExpectedIdThroughTheBackend() = runBlocking<Unit> {
        val a = insertFull("interpreter", "SHADOW", 1, at = 100)
        val b = insertFull("interpreter", "LIVE", 2, at = 200)
        val dao = MemoryGraph.get(app).db.canvasDao()
        assertTrue(dao.markRolledBackWithReason(b, 250, "auto: 10 of 30 fell back") > 0)
        val out = backend.rollback(b) as com.rokid.context.canvas.CanvasPublisher.Outcome.Blocked
        assertEquals(listOf("That publish was already rolled back."), out.reasons)
        assertEquals(a, dao.activeInterpreter()!!.id)
        assertTrue(backend.rollback(a) is com.rokid.context.canvas.CanvasPublisher.Outcome.Published)
        assertNull(dao.activeInterpreter())
    }

    // ---- slice 2, Task 9 (commit 3): usage, branches, compare, Ask debug ----

    private suspend fun insertRun(id: String, pipeline: com.rokid.context.ai.canvas.Pipeline? = null, abGroup: String? = null, est: Int = 4, at: Long = 1) =
        MemoryGraph.get(app).db.canvasDao().insertRun(CanvasRunEntity(
            id, "p", null, "h", pipeline?.let { com.rokid.context.ai.canvas.PipelineJson.canonical(it) } ?: "{}", "DONE", 3, est, 0, 0, at, at + 1, "[]", abGroup))
    private suspend fun insertItem(run: String, frame: String, node: String, branch: String? = null, calls: Int? = null, p: Int? = null, c: Int? = null, latency: Long? = null) =
        MemoryGraph.get(app).db.canvasDao().upsertItem(CanvasRunItemEntity(run, frame, node, "OK", branch, """{"kind":"photo"}""", null, latency, p, c, null, null, null, calls))

    @Test fun runUsageLine() = runBlocking<Unit> {
        insertRun("r1", est = 5)
        insertItem("r1", "f1", "extract", calls = 2, p = 300, c = 40, latency = 100)
        insertItem("r1", "f2", "extract") // a batch-mate: no tokens, no calls
        insertItem("r1", "f3", "critic", calls = 1, p = 50, c = 5, latency = 300)
        val run = checkNotNull(backend.run("r1"))
        val usage = run["usage"]!!.jsonObject
        assertEquals(3, usage["calls"]!!.jsonPrimitive.int)
        assertEquals(5, usage["est"]!!.jsonPrimitive.int)
        assertEquals(350, usage["promptTokens"]!!.jsonPrimitive.int)
        assertEquals(45, usage["completionTokens"]!!.jsonPrimitive.int)
        assertEquals(200L, usage["medianLatencyMs"]!!.jsonPrimitive.content.toLong())
        assertEquals("null", run["abGroup"].toString())
        assertEquals(0, run["branches"]!!.jsonObject.size)
        insertRun("r2"); assertEquals("null", checkNotNull(backend.run("r2"))["usage"]!!.jsonObject["medianLatencyMs"].toString())
        insertRun("r3", abGroup = "g1"); assertEquals("g1", checkNotNull(backend.run("r3"))["abGroup"]!!.jsonPrimitive.content)
    }

    @Test fun runBranchesCountPerSwitch() = runBlocking<Unit> {
        val p = com.rokid.context.ai.canvas.DefaultPipelines.intentDraft("x")
        insertRun("r1", p)
        insertItem("r1", "f1", "route", "cooking"); insertItem("r1", "f2", "route", "cooking"); insertItem("r1", "f3", "route", "other")
        insertItem("r1", "f1", "intent") // not a switch
        val b = checkNotNull(backend.run("r1"))["branches"]!!.jsonObject
        assertEquals(setOf("route"), b.keys)
        assertEquals(mapOf("cooking" to 2, "other" to 1), b["route"]!!.jsonObject.mapValues { it.value.jsonPrimitive.int })
    }

    @Test fun compareBranchesAndAgreement() = runBlocking<Unit> {
        val dao = MemoryGraph.get(app).db.canvasDao()
        val p = com.rokid.context.ai.canvas.DefaultPipelines.intentDraft("x")
        insertRun("ra", p); insertRun("rb", p)
        for ((f, ba, bb) in listOf(Triple("f1", "cooking", "cooking"), Triple("f2", "cooking", "other"), Triple("f3", "other", "other"))) {
            insertItem("ra", f, "route", ba); insertItem("rb", f, "route", bb)
            insertItem("ra", f, "extract_other"); insertItem("rb", f, "extract_other")
        }
        insertItem("ra", "f4", "route", "cooking") // only in a: not counted
        dao.upsertScore(com.rokid.context.memory.db.CanvasScoreEntity("ra", "f1", "extract_other", 1, "", 1)); dao.upsertScore(com.rokid.context.memory.db.CanvasScoreEntity("ra", "f2", "extract_other", 1, "", 1))
        dao.upsertScore(com.rokid.context.memory.db.CanvasScoreEntity("rb", "f1", "extract_other", -1, "", 1)); dao.upsertScore(com.rokid.context.memory.db.CanvasScoreEntity("rb", "f3", "extract_other", 0, "", 1))
        dao.upsertScore(com.rokid.context.memory.db.CanvasScoreEntity("rb", "f3", "route", 1, "", 1))
        val c = checkNotNull(backend.compare("ra", "rb"))
        val br = c["branches"]!!.jsonObject
        assertEquals(mapOf("cooking" to 3, "other" to 1), br["a"]!!.jsonObject["route"]!!.jsonObject.mapValues { it.value.jsonPrimitive.int })
        assertEquals(mapOf("cooking" to 1, "other" to 2), br["b"]!!.jsonObject["route"]!!.jsonObject.mapValues { it.value.jsonPrimitive.int })
        assertEquals(2.0 / 3.0, c["agreement"]!!.jsonObject["route"]!!.jsonPrimitive.content.toDouble(), 1e-9)
        val sums = c["scoreSums"]!!.jsonObject
        assertEquals(2, sums["extract_other"]!!.jsonObject["a"]!!.jsonPrimitive.int); assertEquals(-1, sums["extract_other"]!!.jsonObject["b"]!!.jsonPrimitive.int)
        assertEquals(0, sums["route"]!!.jsonObject["a"]!!.jsonPrimitive.int); assertEquals(1, sums["route"]!!.jsonObject["b"]!!.jsonPrimitive.int)
        assertTrue(c["frames"] is JsonArray) // the old shape is intact
    }

    @Test fun compareAgreementIsAbsentWithoutASwitch() = runBlocking<Unit> {
        insertRun("ra"); insertRun("rb"); insertItem("ra", "f1", "extract"); insertItem("rb", "f1", "extract")
        val c = checkNotNull(backend.compare("ra", "rb"))
        assertEquals(0, c["agreement"]!!.jsonObject.size)
        assertEquals(0, c["branches"]!!.jsonObject["a"]!!.jsonObject.size)
    }

    @Test fun estimateAbThenStartAbReservesBothRunsAndCancelCancelsTheGroup() = runBlocking<Unit> {
        bind()
        listOf("f1", "f2", "f3").forEachIndexed { i, id -> seed(id, 1_000L + i) }
        val ids = listOf("f1", "f2", "f3")
        val a = PipelineRef("live-extract", null); val b = PipelineRef("live-extract", null)
        val est = backend.estimateAb(a, b, ids) as Estimate.Ok
        val single = backend.estimate("live-extract", null, ids) as Estimate.Ok
        assertEquals(single.nominal * 2, est.nominal)
        assertTrue(est.confirmToken != single.confirmToken)
        gate = kotlinx.coroutines.CompletableDeferred()
        val started = backend.startAb(a, b, ids, est.confirmToken)
        val ra = checkNotNull(backend.run(started.runA)); val rb = checkNotNull(backend.run(started.runB))
        assertEquals(started.group, ra["abGroup"]!!.jsonPrimitive.content); assertEquals(started.group, rb["abGroup"]!!.jsonPrimitive.content)
        assertEquals("QUEUED", rb["status"]!!.jsonPrimitive.content)
        // the token is spent, and any other run is refused while the group is live
        try { backend.startAb(a, b, ids, est.confirmToken); org.junit.Assert.fail() } catch (e: com.rokid.context.canvas.CanvasRunner.Busy) { }
        // cancelling the QUEUED member cancels the whole group
        assertTrue(backend.cancelRun(started.runB))
        gate!!.complete(Unit)
        withTimeout(30_000) { while (backend.run(started.runA)!!["status"]!!.jsonPrimitive.content in listOf("RUNNING", "QUEUED") || backend.run(started.runB)!!["status"]!!.jsonPrimitive.content in listOf("RUNNING", "QUEUED")) delay(50) }
        assertEquals("CANCELLED", backend.run(started.runA)!!["status"]!!.jsonPrimitive.content)
        assertEquals("CANCELLED", backend.run(started.runB)!!["status"]!!.jsonPrimitive.content)
        assertEquals(0, checkNotNull(backend.run(started.runB))["items"]!!.jsonArray.size)
    }

    // ---- Ask debug ----

    private suspend fun seedAsk(id: String = "a1", ts: Long = 1_000, q: String = "milk", redacted: Boolean = false, frame: String? = null, sources: String = "[\"m1\"]") {
        MemoryGraph.get(app).db.memoryDao().insert(MemoryEntity("m1", frame, "milk carton in the fridge", "object", "", MemoryTier.SHORT, 0.9, ReviewState.OK, true, 10, false, "milk carton in the fridge"))
        MemoryGraph.get(app).db.askDao().upsert(AskEntity(id, ts, q, "In the fridge", sources, 1, "phone_typed", redacted))
    }

    @Test fun asksListedNewestFirst() = runBlocking<Unit> {
        seedAsk("a1", ts = 5)
        MemoryGraph.get(app).db.askDao().upsert(AskEntity("a2", 9, "eggs", "x".repeat(500), "[]", null, "phone_typed", false))
        val list = backend.asks().map { it.jsonObject }
        assertEquals(listOf("a2", "a1"), list.map { it["id"]!!.jsonPrimitive.content })
        assertEquals(setOf("id", "ts", "question", "answer", "rating", "redacted"), list[0].keys)
        assertTrue(list[0]["answer"]!!.jsonPrimitive.content.length <= 140) // a short answer
        assertEquals("1", list[1]["rating"]!!.jsonPrimitive.content)
    }

    @Test fun askViewHasScoresAndTheComposedPrompt() = runBlocking<Unit> {
        seedAsk()
        val v = checkNotNull(backend.ask("a1"))
        assertEquals("milk", v["question"]!!.jsonPrimitive.content)
        val hit = v["hits"]!!.jsonArray.single().jsonObject
        assertEquals("m1", hit["memoryId"]!!.jsonPrimitive.content)
        assertTrue(hit["score"]!!.jsonPrimitive.content.toDouble() > 0.0)
        assertEquals("true", hit["cited"]!!.jsonPrimitive.content); assertEquals("true", hit["inPrompt"]!!.jsonPrimitive.content)
        assertTrue(v["composedPrompt"]!!.jsonPrimitive.content.contains("milk carton in the fridge"))
        assertEquals("LEXICAL", v["mode"]!!.jsonPrimitive.content)
        assertEquals(listOf("m1"), v["cited"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertTrue(v["activeVersion"]!!.jsonPrimitive.int >= 1)
        assertNull(backend.ask("nope"))
    }

    @Test fun askViewOfARedactedAskIsRefused() = runBlocking<Unit> {
        seedAsk(redacted = true)
        try { backend.ask("a1"); org.junit.Assert.fail() } catch (e: IllegalStateException) { assertEquals("redacted", e.message) }
    }

    @Test fun replayDoesNotChangeTheAskCount() = runBlocking<Unit> {
        bind(); seedAsk()
        val db = MemoryGraph.get(app).db
        fun count(t: String) = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM `$t`").use { it.moveToFirst(); it.getInt(0) }
        val before = listOf("ask", "memory", "proposal").map(::count)
        val r = backend.replayAsk("a1", "T {{question}} {{retrieved_memories}}") as AskDebug.Replay.Ok
        assertEquals("In the fridge", r.answer)
        assertEquals(before, listOf("ask", "memory", "proposal").map(::count))
        assertEquals(1, requests.size)
        assertTrue(requests.single().prompt.contains("milk carton in the fridge"))
    }

    @Test fun replayWithoutABindingNeverLoadsAnImage() = runBlocking<Unit> {
        bind(); seed("f1", 500); seedAsk(frame = "f1")
        assertTrue("the default is Refuse", AskPhotoBinding.current() === AskDebugPhotos.Refuse)
        backend.ask("a1")
        val r = backend.replayAsk("a1", "T {{question}} {{retrieved_memories}}")
        assertTrue(r is AskDebug.Replay.Ok)
        assertTrue(requests.isNotEmpty() && requests.all { it.images.isEmpty() })
    }

    @Test fun aRefusingGateIsNeverAskedToRecall() = runBlocking<Unit> {
        bind(); seed("f1", 500); seedAsk(frame = "f1")
        var recalls = 0; var asked = 0
        val gateBinding = object : AskDebugPhotos {
            override suspend fun allowed(): Boolean { asked++; return false }
            override suspend fun recall(question: String, nowMs: Long): AskPhotoRecall { recalls++; return AskPhotoRecall(emptyList(), emptySet(), emptySet()) }
        }
        val b = newBackend { gateBinding }
        b.replayAsk("a1", "T {{question}} {{retrieved_memories}}")
        assertEquals(0, recalls); assertTrue(asked >= 1)
        assertTrue(requests.all { it.images.isEmpty() })
    }

    @Test fun askCompatibilityIsTheRealTestBed() = runBlocking<Unit> {
        bind()
        val broken = com.rokid.context.prompt.PromptDraft("T", "{not json")
        val real = ai.testBed.compatibility(PromptKey.ANSWER, broken)
        assertTrue("the real gate reports the broken schema", real.isNotEmpty())
        assertEquals(real, CanvasBackendImpl.compatibilityOf(ai)(PromptKey.ANSWER, broken))
    }

    @Test fun replayOfARedactedOrUnknownAskIsFailedWithFixedWords() = runBlocking<Unit> {
        bind(); seedAsk(redacted = true)
        assertEquals(AskDebug.Replay.Failed("redacted"), backend.replayAsk("a1", "T"))
        assertEquals(AskDebug.Replay.Failed("not_found"), backend.replayAsk("nope", "T"))
        assertTrue(requests.isEmpty())
    }
}
