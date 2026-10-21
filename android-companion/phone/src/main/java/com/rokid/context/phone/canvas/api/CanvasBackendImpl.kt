package com.rokid.context.phone.canvas.api

import android.content.Context
import android.util.Base64
import com.rokid.context.ai.AiGraph
import com.rokid.context.ai.Role
import com.rokid.context.ai.canvas.LiveShape
import com.rokid.context.ai.canvas.NodeTypes
import com.rokid.context.ai.canvas.Pipeline
import com.rokid.context.ai.canvas.PipelineFlows
import com.rokid.context.ai.canvas.PipelineJson
import com.rokid.context.ai.canvas.PipelineNode
import com.rokid.context.ai.canvas.PipelineValidator
import com.rokid.context.canvas.CanvasPublisher
import com.rokid.context.canvas.CanvasRepository
import com.rokid.context.canvas.CanvasRunner
import com.rokid.context.canvas.ConfirmTokens
import com.rokid.context.canvas.Estimate
import com.rokid.context.canvas.Executors
import com.rokid.context.canvas.ModelLabel
import com.rokid.context.canvas.PhotoLoad
import com.rokid.context.canvas.PhotoSource
import com.rokid.context.canvas.RunContext
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.CanvasDao
import com.rokid.context.memory.db.CanvasPipelineVersionEntity
import com.rokid.context.memory.db.CanvasRunEntity
import com.rokid.context.memory.db.CanvasScoreEntity
import com.rokid.context.orchestration.ExtractSave
import com.rokid.context.orchestration.FrameInputs
import com.rokid.context.orchestration.PipelineRecords
import com.rokid.context.phone.canvas.server.CanvasAuth
import com.rokid.context.prompt.LineDiff
import com.rokid.context.prompt.PromptDraft
import com.rokid.context.work.frames.FramePhotos
import com.rokid.context.work.frames.KeyframeGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The wiring of the canvas API to the repository, runner, publisher, DAO, frames and the AI graph.
 * Hold exactly ONE instance per process: [CanvasPublisher] serialises publish and rollback with its own mutex and
 * [CanvasRunner] allows one run at a time, so both are only correct behind a single backend. Use [create].
 */
class CanvasBackendImpl(
    private val app: Context,
    private val ai: AiGraph,
    private val dao: CanvasDao,
    private val repo: CanvasRepository,
    private val publisher: CanvasPublisher,
    private val runner: CanvasRunner,
    private val scope: CoroutineScope,
    private val auth: CanvasAuth,
    private val lan: () -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
) : CanvasBackend {
    companion object {
        private val pretty = Json { prettyPrint = true }
        private const val NOT_SET = "not set"

        /** Frames the runner reads through the same loader as live extraction: archived keyframes, sealed ones opened. */
        fun photoSource(app: Context) = PhotoSource { ids ->
            val l = FramePhotos.load(app, ids)
            PhotoLoad(l.photos, l.gone + l.unseen)
        }

        /** The one place the pieces are built; [photos] and [hot] default to the production readers (tests may pass their own). */
        fun create(
            app: Context,
            ai: AiGraph,
            auth: CanvasAuth,
            lan: () -> Boolean,
            scope: CoroutineScope,
            photos: PhotoSource = photoSource(app),
            hot: () -> Boolean = { KeyframeGraph.get(app).power.severe() },
            clock: () -> Long = System::currentTimeMillis,
        ): CanvasBackendImpl {
            val dao = MemoryGraph.get(app).db.canvasDao()
            val repo = CanvasRepository(dao, { key -> ai.prompts.active(key).template }, clock)
            val publisher = CanvasPublisher(dao, ai.prompts, { key, draft -> ai.testBed.compatibility(key, draft) }, clock)
            val runner = CanvasRunner(
                dao, photos, Executors.all(),
                { runId -> RunContext(runId, ai.runner, ai.router, ai.prompts, ai.persona, { role -> ai.bindings.get(role) }) },
                ConfirmTokens(clock), hot, clock,
            )
            return CanvasBackendImpl(app, ai, dao, repo, publisher, runner, scope, auth, lan, clock)
        }
    }

    /** True while an experiment run is executing (the service's idle rule never stops the server then). */
    fun runActive(): Boolean = runner.active.value != null

    private val startLock = Mutex()
    @Volatile private var started = false

    /**
     * Seeds the built-in pipelines and marks runs a process death left RUNNING as interrupted. Runs once (later calls do nothing);
     * every route that reads or starts canvas data calls it first, so the app may also call it up front when the server starts.
     */
    suspend fun start() {
        if (started) return
        startLock.withLock {
            if (started) return
            repo.ensureBuiltIns()
            runner.recoverAtStart()
            started = true
        }
    }

    // ---- session ----

    override suspend fun session(token: String): JsonObject = buildJsonObject {
        put("lan", lan())
        put("idleTimeoutMs", CanvasAuth.IDLE_MS)
        put("maxAgeMs", CanvasAuth.MAX_AGE_MS)
    }

    override suspend fun logout(token: String) = auth.logout(token)

    // ---- registry ----

    override fun nodeTypes(): JsonArray = JsonArray(NodeTypes.all.map { t ->
        buildJsonObject {
            put("type", t.type)
            put("label", t.label)
            put("inputs", JsonArray(t.inputs.map { JsonPrimitive(it.name) }))
            put("output", t.output.name)
            put("promptKey", t.promptKey?.name)
            put("branches", JsonArray(t.branches(JsonObject(emptyMap())).map { JsonPrimitive(it) }))
            put("branchesFromCases", t.type == NodeTypes.SWITCH)
            put("params", JsonArray(paramsOf(t.type)))
        }
    })

    private fun param(name: String, kind: String, extra: Map<String, Int> = emptyMap()) =
        buildJsonObject { put("name", name); put("kind", kind); extra.forEach { (k, v) -> put(k, v) } }

    private fun paramsOf(type: String): List<JsonObject> = when (type) {
        NodeTypes.VISION_EXTRACT -> listOf(param("batch", "int", mapOf("min" to 1, "max" to ExtractSave.MAX_BATCH)), param("prompt", "prompt"))
        NodeTypes.CRITIC -> listOf(param("prompt", "prompt"))
        NodeTypes.PROMPT -> listOf(param("role", "role"), param("template", "text"), param("schema", "schema"), param("images", "bool"), param("name", "text"))
        NodeTypes.SWITCH -> listOf(param("cases", "labels"))
        else -> emptyList()
    }

    // ---- pipelines ----

    private fun versionJson(v: CanvasPipelineVersionEntity) = buildJsonObject {
        put("version", v.version); put("hash", v.hash); put("note", v.note); put("frozenAt", v.frozenAt)
    }

    private suspend fun liveJson(id: String): JsonElement =
        dao.activePublishes().firstOrNull { it.pipelineId == id }?.let { buildJsonObject { put("version", it.version) } } ?: JsonNull

    override suspend fun pipelines(): JsonArray {
        start()
        return JsonArray(repo.list().map { e ->
            buildJsonObject {
                put("id", e.id); put("name", e.name); put("builtIn", e.builtIn); put("draftRev", e.draftRev)
                put("versions", JsonArray(dao.versions(e.id).map(::versionJson)))
                put("live", liveJson(e.id))
            }
        })
    }

    override suspend fun pipeline(id: String): JsonObject? {
        start()
        val e = repo.get(id) ?: return null
        val p = PipelineJson.decode(e.draftJson).getOrNull()
        val problems = if (p == null) listOf("The draft is not readable") else PipelineValidator.validate(p)
        val prompts = buildJsonObject {
            p?.nodes?.forEach { n ->
                val key = NodeTypes.byType(n.type)?.promptKey ?: return@forEach
                val a = ai.prompts.active(key)
                put(n.id, buildJsonObject { put("template", a.template); put("schema", a.outputSchema); put("version", a.version) })
            }
        }
        return buildJsonObject {
            put("id", e.id); put("name", e.name)
            put("json", e.draftJson)
            put("draftRev", e.draftRev)
            put("builtIn", e.builtIn)
            put("problems", JsonArray(problems.map { JsonPrimitive(it) }))
            put("layout", p?.let { runCatching { PipelineFlows.layoutJson(it) }.getOrNull() } ?: emptyLayout)
            put("versions", JsonArray(dao.versions(id).map(::versionJson)))
            put("liveShape", p != null && LiveShape.matches(p))
            put("live", liveJson(id))
            put("activePrompts", prompts)
        }
    }

    private val emptyLayout = buildJsonObject { put("layers", 0); put("columns", 0); put("cells", JsonObject(emptyMap())) }

    override suspend fun duplicate(from: String, name: String): String {
        start()
        val id = from.substringBefore('@')
        val version = from.substringAfter('@', "").toIntOrNull()
        return try {
            repo.duplicate(id, version, name)
        } catch (e: IllegalArgumentException) {
            if (e.message == "source_not_found") throw NoSuchElementException("source") else throw e
        }
    }

    override suspend fun saveDraft(id: String, json: String, rev: Int): CanvasRepository.SaveResult {
        start()
        if (repo.get(id) == null) throw NoSuchElementException("pipeline")
        return repo.saveDraft(id, json, rev)
    }

    override suspend fun deletePipeline(id: String): Boolean {
        start()
        if (repo.get(id) == null) throw NoSuchElementException("pipeline")
        return repo.delete(id)
    }

    override suspend fun freeze(id: String, note: String): JsonObject {
        start()
        if (repo.get(id) == null) throw NoSuchElementException("pipeline")
        val v = repo.freeze(id, note)
        return buildJsonObject { put("version", v.version); put("hash", v.hash) }
    }

    // ---- publish ----

    override suspend fun publish(id: String, version: Int): CanvasPublisher.Outcome = publisher.publish(id, version)

    override suspend fun activePublish(): JsonObject {
        val p = dao.activePublishes().firstOrNull() ?: return buildJsonObject { put("active", false) }
        return buildJsonObject {
            put("active", true); put("publishId", p.id); put("pipelineId", p.pipelineId); put("version", p.version); put("publishedAt", p.publishedAt)
        }
    }

    override suspend fun rollback(): CanvasPublisher.Outcome = publisher.rollback()

    override suspend fun checkPrompt(nodeType: String, template: String, schema: String?): List<String> {
        val type = NodeTypes.byType(nodeType) ?: return listOf("Unknown node type")
        type.promptKey?.let { return ai.testBed.compatibility(it, PromptDraft(template, schema)) }
        if (nodeType != NodeTypes.PROMPT) return listOf("This node has no prompt")
        // A free prompt has no contract to refine (no key), so only the schema's shape is checked.
        if (schema.isNullOrBlank()) return emptyList()
        val parsed = runCatching { Json.parseToJsonElement(schema) }.getOrNull()
        return if (parsed is JsonObject) emptyList() else listOf("Schema must be a JSON object")
    }

    // ---- frames ----

    override suspend fun frames(beforeMs: Long, limit: Int, dwellOnly: Boolean): JsonArray =
        JsonArray(dao.recentFrames(beforeMs, dwellOnly, limit).map { r ->
            buildJsonObject {
                put("id", r.id); put("ts", r.ts); put("motion", r.motion); put("dwell", r.dwell); put("blur", r.blur); put("state", r.state)
                put("hasLiveMemories", r.liveCount > 0)
            }
        })

    override suspend fun frame(id: String): JsonObject? {
        val f = PipelineRecords.framesByIds(app, listOf(id)).firstOrNull() ?: return null
        val db = MemoryGraph.get(app).db
        val meta = db.frameMetaDao().get(id)
        val state = db.frameStateDao().get(id)
        val live = dao.liveMemories(id)
        return buildJsonObject {
            put("id", f.id); put("ts", f.ts); put("source", f.source)
            put("motion", meta?.motion); put("dwell", meta?.dwell); put("blur", meta?.blur); put("state", state?.state)
            put("hasLiveMemories", live.isNotEmpty())
            put("live", JsonArray(live.map { m -> buildJsonObject { put("id", m.id); put("text", m.text); put("type", m.type) } }))
        }
    }

    override suspend fun frameImage(id: String, thumb: Boolean): ByteArray? {
        val f = PipelineRecords.framesByIds(app, listOf(id)).firstOrNull() ?: return null
        val b64 = FrameInputs.fromFrames(listOf(f), 1).singleOrNull()?.base64Data ?: return null
        val bytes = runCatching { Base64.decode(b64, Base64.DEFAULT) }.getOrNull() ?: return null
        return if (thumb) FrameImagesHttp.thumbnail(bytes) else bytes
    }

    // ---- runs ----

    private suspend fun loadPipeline(id: String, version: Int?): Pipeline? {
        start()
        val json = if (version != null) dao.version(id, version)?.json else repo.get(id)?.draftJson
        return PipelineJson.decode(json ?: throw NoSuchElementException("pipeline")).getOrNull()
    }

    private fun roleOf(n: PipelineNode): Role? {
        NodeTypes.byType(n.type)?.promptKey?.let { return it.role }
        if (n.type != NodeTypes.PROMPT) return null
        val named = (n.params["role"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { s -> Role.entries.firstOrNull { it.name == s } }
        val images = (n.params["images"] as? JsonPrimitive)?.takeIf { !it.isString }?.content == "true"
        return named ?: if (images) Role.VISION_EXTRACT else Role.SUMMARIZE
    }

    private suspend fun label(n: PipelineNode): ModelLabel? {
        val role = roleOf(n) ?: return null
        val b = ai.bindings.get(role) ?: return ModelLabel(n.id, NOT_SET, "")
        return ModelLabel(n.id, ai.endpoints.byId(b.endpointId)?.name ?: NOT_SET, b.model)
    }

    override suspend fun estimate(pipelineId: String, version: Int?, frameIds: List<String>): Estimate {
        val p = loadPipeline(pipelineId, version) ?: return Estimate.Refused(listOf("pipeline is not readable"))
        return runner.estimate(p, frameIds, ::label)
    }

    override suspend fun startRun(pipelineId: String, version: Int?, frameIds: List<String>, token: String): String {
        val p = loadPipeline(pipelineId, version) ?: throw IllegalArgumentException("invalid_pipeline")
        return runner.start(p, pipelineId, version, frameIds, token, scope)
    }

    private fun parse(text: String?): JsonElement = text?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() } ?: JsonNull

    private fun runJson(r: CanvasRunEntity) = buildJsonObject {
        put("runId", r.runId); put("pipelineId", r.pipelineId); put("version", r.version); put("pipelineHash", r.pipelineHash)
        put("status", r.status); put("frameCount", r.frameCount); put("estCalls", r.estCalls); put("calls", r.calls); put("failures", r.failures)
        put("startedAt", r.startedAt); put("finishedAt", r.finishedAt)
        put("models", parse(r.modelsJson).takeIf { it is JsonArray } ?: JsonArray(emptyList()))
    }

    override suspend fun runs(): JsonArray {
        start()
        return JsonArray(dao.runs(CanvasRunner.KEEP_RUNS).map(::runJson))
    }

    override suspend fun run(id: String): JsonObject? {
        start()
        val r = dao.run(id) ?: return null
        val items = dao.items(id)
        val nodes = (PipelineJson.decode(r.pipelineJson).getOrNull()?.nodes?.size ?: 0)
        val total = r.frameCount * nodes
        return JsonObject(
            runJson(r) + mapOf(
                "pipelineJson" to JsonPrimitive(r.pipelineJson),
                "items" to JsonArray(items.map { i ->
                    buildJsonObject {
                        put("frameId", i.frameId); put("nodeId", i.nodeId); put("status", i.status); put("branch", i.branch)
                        put("output", parse(i.outputJson)); put("error", i.error)
                        put("latencyMs", i.latencyMs); put("promptTokens", i.promptTokens); put("completionTokens", i.completionTokens)
                        put("model", i.model); put("promptVersion", i.promptVersion); put("batchId", i.batchId)
                    }
                }),
                "scores" to JsonArray(dao.scores(id).map { s ->
                    buildJsonObject { put("frameId", s.frameId); put("nodeId", s.nodeId); put("score", s.score); put("note", s.note); put("updatedAt", s.updatedAt) }
                }),
                "progress" to buildJsonObject { put("done", minOf(items.size, total)); put("total", total) },
            ),
        )
    }

    override suspend fun cancelRun(id: String): Boolean = runner.cancel(id)

    override suspend fun deleteRun(id: String): Boolean {
        if (runner.active.value == id) throw CanvasRunner.Busy()
        if (dao.run(id) == null) return false
        dao.deleteRun(id)
        return true
    }

    override suspend fun score(runId: String, frameId: String, nodeId: String, score: Int, note: String) {
        if (dao.run(runId) == null || dao.items(runId).none { it.frameId == frameId && it.nodeId == nodeId }) throw NoSuchElementException("item")
        dao.upsertScore(CanvasScoreEntity(runId, frameId, nodeId, score, note, clock()))
    }

    override suspend fun compare(a: String, b: String): JsonObject? {
        val ra = dao.run(a) ?: return null
        val rb = dao.run(b) ?: return null
        val ia = dao.items(ra.runId).associateBy { it.frameId to it.nodeId }
        val ib = dao.items(rb.runId).associateBy { it.frameId to it.nodeId }
        val frames = ia.keys.map { it.first }.distinct().filter { f -> ib.keys.any { it.first == f } }
        return buildJsonObject {
            put("frames", JsonArray(frames.map { f ->
                buildJsonObject {
                    put("frameId", f)
                    put("nodes", JsonArray(ia.keys.filter { it.first == f && it in ib }.map { k ->
                        val ta = text(ia.getValue(k).outputJson, ia.getValue(k).error)
                        val tb = text(ib.getValue(k).outputJson, ib.getValue(k).error)
                        buildJsonObject {
                            put("nodeId", k.second); put("a", ta); put("b", tb)
                            put("diff", JsonArray(LineDiff.diff(ta, tb).map { l ->
                                buildJsonObject { put("op", l.kind.name.lowercase()); put("text", l.text) }
                            }))
                        }
                    }))
                }
            }))
        }
    }

    private fun text(output: String?, error: String?): String = when {
        output != null -> (parse(output).takeIf { it !is JsonNull })?.let { pretty.encodeToString(JsonElement.serializer(), it) } ?: output
        else -> "error: ${error ?: "none"}"
    }
}
