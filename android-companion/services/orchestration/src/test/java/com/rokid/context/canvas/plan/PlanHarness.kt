package com.rokid.context.canvas.plan

import android.content.Context
import com.rokid.context.ai.AiGraph
import com.rokid.context.ai.canvas.Pipeline
import com.rokid.context.ai.canvas.PipelineHash
import com.rokid.context.ai.canvas.PipelineJson
import com.rokid.context.ai.canvas.plan.asyncProjection
import com.rokid.context.canvas.CanvasPublisher
import com.rokid.context.canvas.CanvasRepository
import com.rokid.context.capture.replay.ReplayCandidate
import com.rokid.context.capture.replay.ReplayFigures
import com.rokid.context.capture.replay.ReplayReport
import com.rokid.context.capture.replay.StateFigures
import com.rokid.context.core.model.CapturePlanAck
import com.rokid.context.core.model.PlanAckState
import com.rokid.context.core.model.PlanMode
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.CanvasRunEntity
import com.rokid.context.memory.db.CanvasRunItemEntity
import com.rokid.context.memory.db.CanvasTraceEntity
import com.rokid.context.memory.db.DaoFence
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.DeletionEpoch
import com.rokid.context.orchestration.TestAi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals

/** The real pieces of a plan publish over an in-memory database: the groups, the gates, the publishers, with only the glasses link (a transport that records) and the phone RT (a recorder) faked. */
class PlanHarness(app: Context) {
    val db = DatabaseFactory.inMemory(app).also { MemoryGraph.resetForTests(it) }
    val canvas = db.canvasDao()
    val dao = db.canvasSlice3Dao()
    val ai: AiGraph = TestAi.graph(app) { """{"facts":[],"captions":[]}""" }.also { TestAi.bindAll(it) }
    var now = 1_000_000L
    private var ids = 0
    val repo = CanvasRepository(canvas, { "seed {{recent_context}}" }, { now++ }, { "p-${++ids}" })
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    var glasses = GlassesState.LINKED_CAPABLE

    class Sent(val gen: Long, val mode: PlanMode, val planJson: String?, val hash: String?, val publishId: String, val trialMaxMs: Long?)
    val sent = ArrayList<Sent>()
    private var gen = 0L
    val transport = object : PlanTransport {
        override suspend fun send(mode: PlanMode, planJson: String?, configHash: String?, publishId: String, trialMaxMs: Long?): SendOutcome {
            gen++; sent += Sent(gen, mode, planJson, configHash, publishId, trialMaxMs); return SendOutcome(gen, true)
        }
    }
    val phoneRt = ArrayList<String?>()
    val m1 = DaoM1EvidenceStore(dao)
    val store = DaoGroupStore(dao)
    val publisher = CanvasPublisher(canvas, ai.prompts, { _, _ -> emptyList() }, { now++ }, CanvasPublisher.inTransaction(db))
    val activation = PlanActivation(canvas, publisher, { phoneRt += it }, { now++ })
    val groups = PlanPublishGroups(store, transport, { INSTALL }, { now++ }, activation::activate, MetaPlanSource(dao), activation::deactivate)
    val gates = PlanEvidenceGates(dao, m1, { glasses }, canvas)
    val plans = CapturePlanPublisher(canvas, groups, store, gates, publisher, { now++ }, newGroupId = { "g${++groupSeq}" })
    private var groupSeq = 0
    init { runBlocking { repo.ensureBuiltIns() } }

    private val epoch = DeletionEpoch("install", 1, 0)
    private fun fence() = DaoFence(1, epoch, { 1 }, { epoch })
    private val traces = HashMap<String, ReplayTrace>()

    fun close() { scope.cancel(); AiGraph.resetForTests(null); db.close(); MemoryGraph.resetForTests(null) }

    /** A COMPLETE trace and a finished candidate replay of [hash] over it (40 min, REST and WALKING, the given estimates). */
    fun addReplay(hash: String, cand: Double = 0.45, base: Double = 0.49, traceId: String = "t${++traceSeq}"): String = runBlocking {
        dao.acceptTraceFenced(CanvasTraceEntity(traceId, "IMU", "COMPLETE", "{}", 1, false, 0, null, epoch.installId, epoch.counter, true, 10_000, 20_000, 1), fence())
        fun fig(h: String, pct: Double) = ReplayFigures(h, 30, 1.0, 20.0, 0.5, 30.0, 1.0, 0, pct, 3.0)
        traces[traceId] = object : ReplayTrace("0", 0, 10_000, 20_000) {
            override fun replay(candidate: ReplayCandidate?, candidateFusion: Map<String, Double>?, tick: () -> Unit) =
                ReplayReport("COUNTERFACTUAL", 40.0, fig("0", base), candidate?.let { fig(it.configHash, cand) }, 0.0, 1, 0, 0, emptyMap(), false, emptyList(),
                    mapOf("REST" to StateFigures(25.0, 3.0), "WALKING" to StateFigures(15.0, 6.0)))
        }
        val svc = ReplayService(dao, { id -> traces[id] }, ::fence, scope, MutableStateFlow(true), null, { now++ }, { "rp_${now++}" })
        val id = svc.start(ReplayRequest(listOf(traceId), ReplayCandidate(hash)))
        withTimeout(10_000) { while (dao.replay(id)!!.jobState == "RUNNING") delay(5) }
        assertEquals("DONE", dao.replay(id)!!.jobState)
        id
    }
    private var traceSeq = 0

    /** Saves [p] as a new pipeline, freezes it, and (when [ran]) records a successful run of its ASYNC projection so the interpreter publish's "tested" gate passes. Returns id and version. */
    fun frozen(p: Pipeline, ran: Boolean = true): Pair<String, Int> = runBlocking {
        val id = repo.duplicate("live-extract", null, p.name)
        repo.saveDraft(id, PipelineJson.encode(p.copy(id = id)), 0)
        val v = repo.freeze(id, "n")
        if (ran) {
            val h = PipelineHash.of(PipelineJson.decode(v.json).getOrThrow().asyncProjection())
            canvas.insertRun(CanvasRunEntity("r${++ids}", "x", 1, h, "{}", "DONE", 1, 1, 1, 0, 1, 2, "[]"))
            canvas.upsertItem(CanvasRunItemEntity("r$ids", "f1", "extract", "OK", null, null, null, 1, null, null, null, null, null))
        }
        id to v.version
    }

    fun compileHash(p: Pipeline) = com.rokid.context.ai.canvas.plan.PlanCompiler.compile(p).plan!!.configHash

    fun ack(state: PlanAckState, gen: Long = this.gen, mode: PlanMode? = null, hash: String? = null, reason: String? = null): CapturePlanAck {
        val s = sent.last { it.gen == gen }
        return CapturePlanAck(gen = gen, publishId = s.publishId, mode = mode ?: s.mode, effectiveHash = hash ?: s.hash, state = state, reason = reason, phoneInstall = INSTALL)
    }
    fun effective(gen: Long = this.gen) = runBlocking { groups.onAck(ack(PlanAckState.EFFECTIVE, gen)) }
    fun reverted(gen: Long = this.gen, reason: String = "auto:trial_end") = runBlocking { groups.onAck(ack(PlanAckState.REVERTED, gen, hash = null, reason = reason)) }

    companion object { const val INSTALL = "inst" }
}
