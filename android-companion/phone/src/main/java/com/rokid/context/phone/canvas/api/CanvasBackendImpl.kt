package com.rokid.context.phone.canvas.api

import android.content.Context
import com.rokid.context.ai.AiGraph
import com.rokid.context.ai.Role
import com.rokid.context.ai.contract.PromptKey
import com.rokid.context.ai.canvas.InterpretableShape
import com.rokid.context.ai.canvas.LiveShape
import com.rokid.context.ai.canvas.InnerFlow
import com.rokid.context.ai.canvas.NodeTypes
import com.rokid.context.ai.canvas.ParamSpec
import com.rokid.context.ai.canvas.PipelineDiff
import com.rokid.context.ai.canvas.Problem
import com.rokid.context.ai.canvas.Pipeline
import com.rokid.context.ai.canvas.PipelineFlows
import com.rokid.context.ai.canvas.PipelineJson
import com.rokid.context.ai.canvas.PipelineNode
import com.rokid.context.ai.canvas.PipelineValidator
import com.rokid.context.canvas.AbSpec
import com.rokid.context.canvas.AbStarted
import com.rokid.context.canvas.AskDebug
import com.rokid.context.canvas.AskDebugPhotos
import com.rokid.context.canvas.CanvasPublisher
import com.rokid.context.canvas.CanvasRepository
import com.rokid.context.canvas.CanvasRunner
import com.rokid.context.canvas.ConfirmTokens
import com.rokid.context.canvas.Estimate
import com.rokid.context.canvas.Executors
import com.rokid.context.canvas.NodeExecutor
import com.rokid.context.canvas.LiveMode
import com.rokid.context.canvas.ModelLabel
import com.rokid.context.canvas.PhotoLoad
import com.rokid.context.canvas.PhotoSource
import com.rokid.context.canvas.RunContext
import com.rokid.context.canvas.RunUsage
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.frames.FrameImages
import com.rokid.context.memory.db.CanvasDao
import com.rokid.context.memory.db.CanvasMaintenance
import com.rokid.context.memory.db.CanvasPipelineVersionEntity
import com.rokid.context.memory.db.CanvasPublishEntity
import com.rokid.context.memory.db.CanvasRunEntity
import com.rokid.context.memory.db.CanvasScoreEntity
import com.rokid.context.orchestration.AnswerService
import com.rokid.context.orchestration.ExtractSave
import com.rokid.context.orchestration.MemoryWipe
import com.rokid.context.orchestration.PipelineRecords
import com.rokid.context.phone.canvas.server.CanvasAuth
import com.rokid.context.prompt.LineDiff
import com.rokid.context.prompt.PromptDraft
import com.rokid.context.work.frames.FramePhotos
import com.rokid.context.work.frames.KeyframeGraph
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
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
    /** The one publisher of the process: the capture-plan publisher shares it, so publish and rollback of every kind are serialised by one lock. */
    val publisher: CanvasPublisher,
    private val runner: CanvasRunner,
    private val scope: CoroutineScope,
    private val auth: CanvasAuth,
    private val lan: () -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
    private val askDebug: Lazy<AskDebug>,
    private val thumbnailer: (ByteArray) -> ByteArray? = { FrameImagesHttp.thumbnail(it) },
    /** Slice 3a (Task 28): the capture-plan publisher, read at call time (null until it is composed): Undo latest is group-aware through it. */
    private val plans: () -> com.rokid.context.canvas.plan.CapturePlanPublisher? = { null },
    /** 3b WP6: the helper job port (WP8 builds `py.fn` over it); null when no helper is bound. */
    val helperJobs: com.rokid.context.canvas.helper.HelperJobPort? = null,
    /** Moment-lite: the read-only resolver behind `GET /api/moments` (the runner holds the same one for Moment runs). */
    private val momentResolver: com.rokid.context.canvas.moment.MomentResolver = com.rokid.context.canvas.moment.MomentResolver(
        com.rokid.context.canvas.moment.DaoMomentSource(dao, MemoryGraph.get(app).db.canvasSlice3Dao()),
    ),
) : CanvasBackend {
    companion object {
        private val pretty = Json { prettyPrint = true }
        private const val NOT_SET = "not set"
        private const val LIVE_BATCH_NODE = "_batch"
        private const val ASK_LIST_LIMIT = 50
        private const val ASK_ANSWER_PREVIEW = 140
        /** Fixed text for a pipeline JSON that cannot be decoded (never the decoder's message, never the input). */
        const val UNDECODABLE = "The pipeline JSON is not valid."
        /** The Ask debug view's template editor names the Answer prompt with this type (it has no pipeline node). */
        const val ASK_ANSWER = "ask.answer"

        internal fun problemJson(p: Problem): JsonObject = buildJsonObject {
            put("text", p.text)
            p.nodeId?.let { put("nodeId", it) }
            p.edge?.let { e -> put("edge", buildJsonObject { put("from", e.from); put("to", e.to); e.`when`?.let { w -> put("when", w) } }) }
        }

        /** `{problems, warnings, callRange:[min,max]}` of the interpreter shape (S33). */
        internal fun interpretableJson(p: Pipeline?): JsonObject {
            if (p == null) return buildJsonObject {
                put("problems", JsonArray(listOf(JsonPrimitive(UNDECODABLE)))); put("warnings", JsonArray(emptyList()))
                put("callRange", JsonArray(listOf(JsonPrimitive(0.0), JsonPrimitive(0.0))))
            }
            val (min, max) = InterpretableShape.callRange(p)
            return buildJsonObject {
                put("problems", JsonArray(InterpretableShape.problems(p).map { JsonPrimitive(it) }))
                put("warnings", JsonArray(InterpretableShape.warnings(p).map { JsonPrimitive(it) }))
                put("callRange", JsonArray(listOf(JsonPrimitive(min), JsonPrimitive(max))))
            }
        }

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
            /** The Ask retrieval of the debug view (no backlog extraction: a view must not start model work). Tests pass their own. */
            answers: () -> AnswerService = { AnswerService.forApp(app, extractFirst = false) },
            /** The Ask photo gate of a replay; [AskPhotoBinding] by default, which refuses every photo until a gate is bound. */
            askPhotos: () -> AskDebugPhotos = AskPhotoBinding::current,
            thumbnailer: (ByteArray) -> ByteArray? = { FrameImagesHttp.thumbnail(it) },
            plans: () -> com.rokid.context.canvas.plan.CapturePlanPublisher? = { null },
            /** Task 33: the process's ONE publisher (the plan graph's), so slice 2 publishes and the plan groups share a lock; null builds its own (tests). */
            sharedPublisher: CanvasPublisher? = null,
            /** 3b WP6: the laptop-helper job port the experiment executor (WP8) submits through; null = no helper. Nothing here uses it yet. */
            helperJobs: com.rokid.context.canvas.helper.HelperJobPort? = null,
            /** 3b WP6: extra node executors (WP8's `py.fn`), composed beside the built-in ones; none by default. A type that a built-in already owns is refused. */
            extraExecutors: Map<String, NodeExecutor> = emptyMap(),
            /** 3b WP8: the ONE call-side logger the runner writes its count-only lines to (production: the gated core port). */
            log: com.rokid.context.canvas.helper.CanvasLogSink = com.rokid.context.phone.log.PhoneLogging.canvasPort(),
        ): CanvasBackendImpl {
            val dao = MemoryGraph.get(app).db.canvasDao()
            val repo = CanvasRepository(dao, { key -> ai.prompts.active(key).template }, clock)
            val publisher = sharedPublisher ?: CanvasPublisher(dao, ai.prompts, { key, draft -> ai.testBed.compatibility(key, draft) }, clock, CanvasPublisher.inTransaction(MemoryGraph.get(app).db))
            val momentResolver = com.rokid.context.canvas.moment.MomentResolver(com.rokid.context.canvas.moment.DaoMomentSource(dao, MemoryGraph.get(app).db.canvasSlice3Dao()))
            val runner = CanvasRunner(
                dao, photos, composeExecutors(extraExecutors),
                { runId -> RunContext(runId, ai.runner, ai.router, ai.prompts, ai.persona, { role -> ai.bindings.get(role) }) },
                ConfirmTokens(clock), hot, clock, log = log, moments = momentResolver,
            )
            val askDebug = lazy {
                AskDebug(dao, answers(), ai.runner, ai.prompts, { ai.persona }, compatibilityOf(ai), photos = LiveBoundPhotos(askPhotos))
            }
            return CanvasBackendImpl(app, ai, dao, repo, publisher, runner, scope, auth, lan, clock, askDebug, thumbnailer, plans, helperJobs, momentResolver)
        }

        /** The built-in executors plus [extra]; an extra executor never replaces a built-in node type. */
        internal fun composeExecutors(extra: Map<String, NodeExecutor>): Map<String, NodeExecutor> {
            val base = Executors.all()
            require(extra.keys.none { it in base }) { "an extra executor may not replace a built-in node" }
            return base + extra
        }

        /** The gate is read at every call, so a gate bound after the first Ask view is the one used (and nothing bound is [AskDebugPhotos.Refuse]). */
        private class LiveBoundPhotos(private val source: () -> AskDebugPhotos) : AskDebugPhotos {
            override suspend fun allowed() = source().allowed()
            override suspend fun recall(question: String, nowMs: Long) = source().recall(question, nowMs)
        }

        /** The real TestBed gate (`ai.testBed::compatibility`): a replay whose draft the bound endpoints cannot compile is refused as `incompatible`. */
        internal fun compatibilityOf(ai: AiGraph): suspend (PromptKey, PromptDraft) -> List<String> = { key, draft -> ai.testBed.compatibility(key, draft) }
    }

    /** True while an experiment run is executing (the service's idle rule never stops the server then). */
    fun runActive(): Boolean = runner.active.value != null

    /** Asks the active run (if any) to stop at its next chunk boundary. */
    fun cancelActiveRun() { runner.active.value?.let { runner.cancel(it) } }

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
            put("version", t.version)
            put("inputs", JsonArray(t.inputs.map { JsonPrimitive(it.name) }))
            put("output", t.output.name)
            put("promptKey", t.promptKey?.name)
            put("branches", JsonArray(t.branches(JsonObject(emptyMap())).map { JsonPrimitive(it) }))
            put("branchesFromCases", t.type == NodeTypes.SWITCH)
            put("params", JsonArray(if (t.params.specs.isEmpty()) paramsOf(t.type) else t.params.specs.map { paramJson(it) }))
            put("short", t.short.ifEmpty { t.type.substringAfterLast('.') })
            put("category", t.category.label)
            put("palette", t.palette) // exactly one photo input per pipeline: not offered
            put("domain", t.domain.name)
            put("domainFromParams", t.domainFromParams)
            put("executor", t.executor.name)
            put("root", t.root)
            put("sinceSchema", t.sinceSchema)
            put("liveable", t.liveable)
            put("hint", t.hint)
            t.inner?.let { put("inner", innerJson(it)) }
            put("cost", buildJsonObject { put("modelCalls", t.cost.modelCalls); put("rtOps", t.cost.rtOps); put("cpuClass", t.cost.cpuClass) })
        }
    })

    private fun innerJson(f: InnerFlow): JsonObject = buildJsonObject {
        put("nodes", JsonArray(f.nodes.map { n -> buildJsonObject { put("id", n.id); put("label", n.label); put("kind", n.kind) } }))
        put("wires", JsonArray(f.wires.map { w -> buildJsonObject { put("from", w.from); put("to", w.to); put("port", w.port) } }))
        put("exposed", JsonArray(f.exposed.map { e -> buildJsonObject { put("param", e.param); put("node", e.node) } }))
    }

    private fun paramJson(s: ParamSpec): JsonObject = buildJsonObject {
        put("name", s.name); put("kind", s.kind.wire)
        if (s.label.isNotEmpty()) put("label", s.label)
        s.min?.let { put("min", num(it)) }
        s.max?.let { put("max", num(it)) }
        s.step?.let { put("step", num(it)) }
        s.default?.let { put("default", it) }
        if (s.options.isNotEmpty()) put("options", JsonArray(s.options.map { JsonPrimitive(it) }))
        if (s.maxLen > 0) put("maxLen", s.maxLen)
        put("sweep", s.sweep)
        s.table?.let { tb ->
            put("table", buildJsonObject {
                put("rows", JsonArray(tb.rows.map { JsonPrimitive(it) }))
                put("cols", JsonArray(tb.cols.map { c ->
                    buildJsonObject {
                        put("name", c.name); put("label", c.label); put("kind", c.kind.wire)
                        c.min?.let { put("min", num(it)) }
                        c.max?.let { put("max", num(it)) }
                        put("blankable", c.blankable); put("allowZero", c.allowZero)
                        c.group?.let { put("group", it) }
                    }
                }))
            })
        }
    }

    /** Whole numbers print as integers, so 4.0 is 4 on the wire. */
    private fun num(d: Double): JsonPrimitive = if (d == Math.floor(d) && Math.abs(d) < 1e12) JsonPrimitive(d.toLong()) else JsonPrimitive(d)

    private fun param(name: String, kind: String, extra: Map<String, Int> = emptyMap()) =
        buildJsonObject { put("name", name); put("kind", kind); extra.forEach { (k, v) -> put(k, v) } }

    /** The slice 2 types keep their editor param descriptors here (their v1 descriptors have no [ParamSchema]). */
    private fun paramsOf(type: String): List<JsonObject> = when (type) {
        NodeTypes.VISION_EXTRACT -> listOf(param("batch", "int", mapOf("min" to 1, "max" to ExtractSave.MAX_BATCH)), param("prompt", "prompt"))
        NodeTypes.CRITIC -> listOf(param("prompt", "prompt"))
        NodeTypes.PROMPT -> listOf(param("role", "role"), param("template", "text"), param("schema", "schema"), param("images", "bool"), param("name", "text"))
        NodeTypes.SWITCH -> listOf(JsonObject(param("field", "text") + ("default" to JsonPrimitive("activity"))), param("cases", "labels"))
        else -> emptyList()
    }

    // ---- pipelines ----

    private fun versionJson(v: CanvasPipelineVersionEntity) = buildJsonObject {
        put("version", v.version); put("hash", v.hash); put("note", v.note); put("frozenAt", v.frozenAt)
    }

    /** A frozen version with its OWN publish-relevant shape (Task 12 fix): the dialog prices the version it publishes, not the draft. */
    private fun versionWithShape(v: CanvasPipelineVersionEntity): JsonObject {
        val p = PipelineJson.decode(v.json).getOrNull()
        return JsonObject(versionJson(v) + mapOf("liveShape" to JsonPrimitive(p != null && LiveShape.matches(p)), "interpretable" to interpretableJson(p)))
    }

    private suspend fun liveJson(id: String): JsonElement =
        dao.activePublishes().firstOrNull { it.kind == CanvasPublisher.KIND_PROMPTS && it.pipelineId == id }?.let { buildJsonObject { put("version", it.version) } } ?: JsonNull

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
        val detailed = if (p == null) listOf(Problem(UNDECODABLE)) else PipelineValidator.problems(p)
        val problems = detailed.map { it.text }
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
            put("problemsDetailed", JsonArray(detailed.map(::problemJson)))
            put("interpretable", interpretableJson(p))
            put("layout", p?.let { runCatching { PipelineFlows.layoutJson(it) }.getOrNull() } ?: emptyLayout)
            put("versions", JsonArray(dao.versions(id).map(::versionWithShape)))
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

    override suspend fun freeze(id: String, note: String, draftRev: Int?): JsonObject {
        start()
        if (repo.get(id) == null) throw NoSuchElementException("pipeline")
        val v = repo.freeze(id, note, draftRev)
        return buildJsonObject { put("version", v.version); put("hash", v.hash) }
    }

    override suspend fun validate(json: String): JsonObject {
        val p = PipelineJson.decode(json).getOrNull() ?: return buildJsonObject {
            put("problems", JsonArray(listOf(buildJsonObject { put("text", UNDECODABLE) })))
            put("layout", JsonNull)
            put("liveShape", false)
            put("interpretable", interpretableJson(null))
        }
        return buildJsonObject {
            put("problems", JsonArray(PipelineValidator.problems(p).map(::problemJson)))
            put("layout", runCatching { PipelineFlows.layoutJson(p) }.getOrNull() ?: JsonNull)
            put("liveShape", LiveShape.matches(p))
            put("interpretable", interpretableJson(p))
        }
    }

    private suspend fun diffSide(id: String, token: String): Pipeline? {
        val json = if (token == "draft") repo.get(id)?.draftJson else token.toIntOrNull()?.let { dao.version(id, it)?.json }
        return json?.let { PipelineJson.decode(it).getOrNull() }
    }

    override suspend fun diff(id: String, a: String, b: String): JsonObject? {
        start()
        val pa = diffSide(id, a) ?: return null
        val pb = diffSide(id, b) ?: return null
        val d = PipelineDiff.of(pa, pb)
        fun edge(e: com.rokid.context.ai.canvas.PipelineEdge) = buildJsonObject { put("from", e.from); put("to", e.to); e.`when`?.let { put("when", it) } }
        return buildJsonObject {
            put("renamed", d.renamed)
            put("nodes", JsonArray(d.nodes.map { n -> buildJsonObject { put("nodeId", n.nodeId); put("kind", n.kind); put("params", JsonArray(n.params.map { JsonPrimitive(it) })) } }))
            put("edgesAdded", JsonArray(d.edgesAdded.map(::edge)))
            put("edgesRemoved", JsonArray(d.edgesRemoved.map(::edge)))
            put("prompts", JsonArray(d.prompts.map { pd ->
                buildJsonObject {
                    put("nodeId", pd.nodeId); put("part", pd.part)
                    put("lines", JsonArray(pd.lines.map { l -> buildJsonObject { put("op", l.kind.name.lowercase()); put("text", l.text) } }))
                }
            }))
        }
    }

    // ---- publish ----

    override suspend fun publish(id: String, version: Int): CanvasPublisher.Outcome = publisher.publish(id, version)

    /** Trace retention (S44): at most once an hour, on the canvas reads; debug data, so a failure is swallowed. */
    private val maintenance = CanvasMaintenance(dao, clock)
    private suspend fun prune() {
        try { maintenance.pruneTraces() } catch (e: CancellationException) { throw e } catch (e: Exception) { /* history only */ }
    }

    private fun publishJson(p: CanvasPublishEntity) = buildJsonObject {
        put("id", p.id); put("kind", p.kind); put("mode", p.mode); put("pipelineId", p.pipelineId); put("version", p.version); put("publishedAt", p.publishedAt)
        p.groupId?.let { put("groupId", it) }
        p.configHash?.let { put("configHash", it) }
    }

    override suspend fun activePublish(): JsonObject {
        prune()
        val active = dao.activePublishes()
        val p = active.firstOrNull { it.kind == CanvasPublisher.KIND_PROMPTS }
        val i = active.firstOrNull { it.kind == CanvasPublisher.KIND_INTERPRETER }
        return buildJsonObject {
            put("active", p != null)
            if (p != null) { put("publishId", p.id); put("pipelineId", p.pipelineId); put("version", p.version); put("publishedAt", p.publishedAt) }
            put("stack", JsonArray(active.map(::publishJson)))
            put("plan", active.firstOrNull { it.kind == CanvasPublisher.KIND_CAPTURE_PLAN }?.let(::publishJson) ?: JsonNull)
            put("interpreter", if (i == null) JsonNull else JsonObject(publishJson(i) + mapOf(
                "interpreted" to JsonPrimitive(dao.interpretedCount(i.id)), "fallbacks" to JsonPrimitive(dao.recentFallbacks(i.id, Int.MAX_VALUE)),
            )))
        }
    }

    override suspend fun publishInterpreter(id: String, version: Int, mode: LiveMode): CanvasPublisher.Outcome {
        start()
        return publisher.publishInterpreter(id, version, mode)
    }

    override suspend fun publishHistory(limit: Int): JsonArray {
        prune()
        return JsonArray(dao.publishHistory(limit).map { p -> JsonObject(publishJson(p) + mapOf("rolledBackAt" to JsonPrimitive(p.rolledBackAt), "rollbackReason" to JsonPrimitive(p.rollbackReason))) })
    }

    override suspend fun live(limit: Int): JsonObject {
        prune()
        val pub = dao.activeInterpreter() ?: return buildJsonObject {
            put("active", false); put("interpreted", 0); put("fallbacks", 0); put("labels", JsonObject(emptyMap())); put("frames", JsonArray(emptyList()))
        }
        // the label of a photo is the branch its first switch took (the switch ids come from the published version)
        val switches = dao.version(pub.pipelineId, pub.version)?.json?.let { PipelineJson.decode(it).getOrNull() }
            ?.nodes?.filter { it.type == NodeTypes.SWITCH }?.map { it.id }.orEmpty()
        val frameIds = dao.traceFrames(pub.id, limit)
        val byFrame = if (frameIds.isEmpty()) emptyMap() else dao.tracesOfFrames(pub.id, frameIds).groupBy { it.frameId }
        val labels = LinkedHashMap<String, Int>()
        val frames = frameIds.map { f ->
            val rows = byFrame[f].orEmpty()
            val batch = rows.firstOrNull { it.nodeId == LIVE_BATCH_NODE }
            val label = switches.firstNotNullOfOrNull { sid -> rows.firstOrNull { it.nodeId == sid && it.status == "OK" }?.branch }
            if (label != null) labels[label] = (labels[label] ?: 0) + 1
            buildJsonObject {
                put("frameId", f)
                put("at", batch?.at ?: rows.maxOfOrNull { it.at } ?: 0L)
                put("label", label)
                put("batch", batch?.status)
                put("nodes", JsonArray(rows.filter { it.nodeId != LIVE_BATCH_NODE }.map { r ->
                    buildJsonObject { put("nodeId", r.nodeId); put("status", r.status); put("branch", r.branch); put("output", r.outputJson); put("error", r.error) }
                }))
            }
        }
        return buildJsonObject {
            put("active", true); put("publishId", pub.id); put("pipelineId", pub.pipelineId); put("version", pub.version); put("mode", pub.mode); put("publishedAt", pub.publishedAt)
            put("interpreted", dao.interpretedCount(pub.id)); put("fallbacks", dao.recentFallbacks(pub.id, Int.MAX_VALUE))
            put("labels", JsonObject(labels.mapValues { JsonPrimitive(it.value) }))
            put("frames", JsonArray(frames))
        }
    }

    /** Undo latest publish (S26): a publish of a group (a capture plan, its ASYNC interpreter) is undone as one unit, ASYNC first; anything else is slice 2's rollback. */
    override suspend fun rollback(expectedId: Long): CanvasPublisher.Outcome {
        val planner = plans()
        val newest = dao.activePublishes().firstOrNull()
        return if (planner != null && newest != null && newest.id == expectedId && (newest.groupId != null || newest.kind == CanvasPublisher.KIND_CAPTURE_PLAN)) planner.undoLatest(expectedId)
        else publisher.rollback(expectedId)
    }

    override suspend fun checkPrompt(nodeType: String, template: String, schema: String?): List<String> {
        // the Ask debug view's template editor (Task 12): the Answer prompt has no pipeline node, only its prompt key
        if (nodeType == ASK_ANSWER) return ai.testBed.compatibility(PromptKey.ANSWER, PromptDraft(template, schema))
        val type = NodeTypes.byType(nodeType) ?: return listOf("Unknown node type")
        type.promptKey?.let { return ai.testBed.compatibility(it, PromptDraft(template, schema)) }
        if (nodeType != NodeTypes.PROMPT) return listOf("This node has no prompt")
        // A free prompt has no contract to refine (no key), so only the schema's shape is checked.
        if (schema.isNullOrBlank()) return emptyList()
        val parsed = runCatching { Json.parseToJsonElement(schema) }.getOrNull()
        return if (parsed is JsonObject) emptyList() else listOf("Schema must be a JSON object")
    }

    // ---- frames ----

    override suspend fun frames(afterMs: Long?, beforeMs: Long, limit: Int, dwellOnly: Boolean): JsonArray =
        JsonArray((if (afterMs == null) dao.recentFrames(beforeMs, dwellOnly, limit) else dao.recentFramesBetween(afterMs, beforeMs, dwellOnly, limit)).map { r ->
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

    override suspend fun frameImage(id: String, thumb: Boolean): FrameImage? {
        val generation = MemoryWipe.generation.get()
        // framesByIds drops a tombstoned (forgotten) frame, an expired or deleted one (no row) and one whose file is gone
        val frame = PipelineRecords.framesByIds(app, listOf(id)).firstOrNull() ?: return null
        val bytes = withContext(Dispatchers.IO) {
            if (File(frame.path).length() > FrameImagesHttp.MAX_IMAGE_BYTES) null else FrameImages.readBytes(frame.path) // opened in memory; nothing is written
        } ?: return null
        if (bytes.size > FrameImagesHttp.MAX_IMAGE_BYTES) return null
        val type = FrameImagesHttp.sniff(bytes) ?: return null
        val image = if (!thumb) FrameImage(bytes, type) else withContext(Dispatchers.Default) { thumbnailer(bytes) }?.let { FrameImage(it, "image/jpeg") } ?: return null
        // like a live Ask (S35), as late as possible (after the decode, right before the answer is built): a Forget or a Delete all that landed
        // while the file was opened or decoded means nothing is served
        if (MemoryWipe.generation.get() != generation || dao.tombstonedAmong(listOf(id)) > 0 || PipelineRecords.framesByIds(app, listOf(id)).isEmpty()) return null
        return image
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

    override suspend fun moments(afterMs: Long, beforeMs: Long, limit: Int): JsonArray =
        JsonArray(momentResolver.window(afterMs, beforeMs - 1, limit).map { m ->
            buildJsonObject { put("id", m.id); put("startMs", m.startMs); put("photos", m.frames.size); put("memories", m.memories.size); put("marks", m.marks.size) }
        })

    override suspend fun estimateMoments(pipelineId: String, version: Int?, momentIds: List<String>): Estimate {
        val p = loadPipeline(pipelineId, version) ?: return Estimate.Refused(listOf("pipeline is not readable"))
        return runner.estimateMoments(p, momentIds, ::label)
    }

    override suspend fun startMomentRun(pipelineId: String, version: Int?, momentIds: List<String>, token: String): String {
        val p = loadPipeline(pipelineId, version) ?: throw IllegalArgumentException("invalid_pipeline")
        return runner.startMoments(p, pipelineId, version, momentIds, token, scope)
    }

    override suspend fun startRun(pipelineId: String, version: Int?, frameIds: List<String>, token: String): String {
        val p = loadPipeline(pipelineId, version) ?: throw IllegalArgumentException("invalid_pipeline")
        return runner.start(p, pipelineId, version, frameIds, token, scope)
    }

    override suspend fun estimateAb(a: PipelineRef, b: PipelineRef, frameIds: List<String>): Estimate {
        val pa = loadPipeline(a.pipelineId, a.version) ?: return Estimate.Refused(listOf("pipeline is not readable"))
        val pb = loadPipeline(b.pipelineId, b.version) ?: return Estimate.Refused(listOf("pipeline is not readable"))
        return runner.estimateAb(pa, pb, frameIds, ::label)
    }

    override suspend fun startAb(a: PipelineRef, b: PipelineRef, frameIds: List<String>, token: String): AbStarted {
        val pa = loadPipeline(a.pipelineId, a.version) ?: throw IllegalArgumentException("invalid_pipeline")
        val pb = loadPipeline(b.pipelineId, b.version) ?: throw IllegalArgumentException("invalid_pipeline")
        return runner.startAb(AbSpec(pa to a.version, a.pipelineId, pb to b.version, b.pipelineId), frameIds, token, scope)
    }

    // ---- Ask debug: the composed prompt and the replay hold the user's memories. Served on authenticated routes only; nothing here logs them. ----

    override suspend fun asks(): JsonArray {
        start()
        return JsonArray(askDebug.value.list(ASK_LIST_LIMIT).map { a ->
            buildJsonObject {
                put("id", a.id); put("ts", a.ts)
                put("question", if (a.redacted) "" else a.question)
                put("answer", if (a.redacted) "" else a.answer.take(ASK_ANSWER_PREVIEW))
                put("rating", a.rating); put("redacted", a.redacted)
            }
        })
    }

    override suspend fun ask(id: String): JsonObject? {
        start()
        val v = askDebug.value.view(id) ?: return null
        return buildJsonObject {
            put("askId", v.askId); put("ts", v.ts); put("question", v.question); put("answer", v.answer)
            put("cited", JsonArray(v.cited.map { JsonPrimitive(it) })); put("rating", v.rating)
            put("mode", v.mode); put("fallback", v.fallback)
            put("hits", JsonArray(v.hits.map { h ->
                buildJsonObject {
                    put("memoryId", h.memoryId); put("text", h.text); put("frameId", h.frameId)
                    put("lexical", h.lexical); put("semantic", h.semantic); put("fused", h.fused); put("score", h.score)
                    put("cited", h.cited); put("inPrompt", h.inPrompt)
                }
            }))
            put("composedPrompt", v.composedPrompt); put("activeTemplate", v.activeTemplate); put("activeVersion", v.activeVersion)
        }
    }

    override suspend fun replayAsk(id: String, template: String): AskDebug.Replay {
        start()
        return askDebug.value.replay(id, template)
    }

    private fun parse(text: String?): JsonElement = text?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() } ?: JsonNull

    private fun runJson(r: CanvasRunEntity) = buildJsonObject {
        put("runId", r.runId); put("abGroup", r.abGroup); put("pipelineId", r.pipelineId); put("version", r.version); put("pipelineHash", r.pipelineHash)
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
        val usage = RunUsage.of(items)
        return JsonObject(
            runJson(r) + mapOf(
                "abGroup" to JsonPrimitive(r.abGroup),
                "usage" to buildJsonObject {
                    put("calls", usage.calls); put("est", r.estCalls)
                    put("promptTokens", usage.promptTokens); put("completionTokens", usage.completionTokens); put("medianLatencyMs", usage.medianLatencyMs)
                },
                "branches" to RunInsights.branches(items, RunInsights.switchIds(r.pipelineJson)),
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
        if (runner.isBusy(id)) throw CanvasRunner.Busy() // the active run or either run of a live A/B group (a QUEUED B included)
        if (dao.run(id) == null) return false
        dao.deleteRun(id)
        return true
    }

    override suspend fun score(runId: String, frameId: String, nodeId: String, score: Int, note: String) {
        val gen = com.rokid.context.orchestration.MemoryWipe.generation.get()
        if (dao.run(runId) == null) throw NoSuchElementException("item")
        // generation, tombstone and the parent item are rechecked INSIDE the write's transaction (a Forget/Delete all in between must not recreate the note)
        if (!dao.upsertScoreFenced(CanvasScoreEntity(runId, frameId, nodeId, score, note, clock()), gen, com.rokid.context.orchestration.MemoryWipe.generation::get)) throw NoSuchElementException("item")
    }

    override suspend fun compare(a: String, b: String): JsonObject? {
        val ra = dao.run(a) ?: return null
        val rb = dao.run(b) ?: return null
        val ia = dao.items(ra.runId).associateBy { it.frameId to it.nodeId }
        val ib = dao.items(rb.runId).associateBy { it.frameId to it.nodeId }
        val frames = ia.keys.map { it.first }.distinct().filter { f -> ib.keys.any { it.first == f } }
        val switches = (RunInsights.switchIds(ra.pipelineJson) + RunInsights.switchIds(rb.pipelineJson)).distinct()
        return buildJsonObject {
            put("branches", buildJsonObject {
                put("a", RunInsights.branches(ia.values.toList(), RunInsights.switchIds(ra.pipelineJson)))
                put("b", RunInsights.branches(ib.values.toList(), RunInsights.switchIds(rb.pipelineJson)))
            })
            put("agreement", RunInsights.agreement(ia.values.toList(), ib.values.toList(), switches))
            put("scoreSums", RunInsights.scoreSums(dao.scores(ra.runId), dao.scores(rb.runId)))
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
