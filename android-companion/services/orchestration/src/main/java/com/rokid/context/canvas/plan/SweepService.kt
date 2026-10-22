package com.rokid.context.canvas.plan

import com.rokid.context.capture.plan.PlanConfig
import com.rokid.context.capture.plan.PlanJson
import com.rokid.context.capture.plan.PlanResolver
import com.rokid.context.capture.replay.ReplayReport
import com.rokid.context.capture.sweep.SweepFigures
import com.rokid.context.capture.sweep.SweepMode
import com.rokid.context.capture.sweep.SweepObjectives
import com.rokid.context.capture.sweep.SweepPlanner
import com.rokid.context.capture.sweep.SweepPoint
import com.rokid.context.capture.sweep.SweepSpec
import com.rokid.context.memory.db.CanvasSlice3Dao
import com.rokid.context.memory.db.CanvasSweepEntity
import com.rokid.context.memory.db.DaoFence
import com.rokid.context.memory.db.TraceSource
import com.rokid.context.memory.db.TraceSources
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID
import kotlin.coroutines.coroutineContext

/**
 * [spec] is expanded against [base] (default: today's behaviour as a plan config) when the request starts; a refused spec throws [IllegalArgumentException] with a fixed text
 * and writes nothing. [paramsJson] is stored with the job (the request, for the UI).
 */
class SweepRequest(val traceIds: List<String>, val spec: SweepSpec, val base: PlanConfig = PlanResolver.defaults(), val paramsJson: String = "{}")

/**
 * Runs one RT param sweep at a time over the recorded traces (S16, S77): every config of the grid or the seeded sample is replayed once per trace (counterfactual, point-estimate
 * keeps, APPROXIMATE) and scored on captures/min, estimated %/min and hours, opens/min and the unobserved opens %. Moment recall, the holdout by day and the Pareto front are
 * LATER (LA6); `splitDaysJson` stays `[]`.
 *
 * The constructor has no model dependency (a reflection test pins it): a sweep makes no model call. The life cycle is [ReplayService]'s: RUNNING (with `sourcesJson` and `labelRev`
 * recorded before anything is read), DONE through [CanvasSlice3Dao.commitSweepFenced] (a wipe, a tombstoned or rewritten source or a moved `labelRev` drops the result),
 * CANCELLED (thermal SEVERE, shutdown, Developer mode off, the user, a failure) or ABORTED (the process died: [recover]). The one-job [jobGate] is shared with [ReplayService].
 */
class SweepService(
    private val dao: CanvasSlice3Dao,
    private val traces: ReplayTraces,
    /** Captured BEFORE a job starts and re-checked inside the commit transaction (S57, S58). */
    private val fence: () -> DaoFence,
    private val scope: CoroutineScope,
    private val developerMode: StateFlow<Boolean>,
    private val thermalSevere: Flow<Boolean>? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { "sw_" + UUID.randomUUID().toString().replace("-", "").take(16) },
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val jobGate: Mutex = Mutex(),
) {
    @Volatile private var running: Running? = null
    private class Running(val id: String, val job: Job)

    init {
        scope.launch { developerMode.collect { on -> if (!on) cancelRunning() } }
        thermalSevere?.let { f -> scope.launch { f.collect { severe -> if (severe) cancelRunning() } } }
    }

    /** Start-up recovery: marks every job still RUNNING (replays and sweeps) ABORTED. Returns how many. */
    suspend fun recover(): Int = dao.markRunningAborted(clock())

    fun runningId(): String? = running?.id

    /** Starts a job and returns its id at once. Throws [ReplayDeveloperModeOff], [IllegalArgumentException] (a refused spec), [ReplayBusy] or [ReplayUnknownTrace]. */
    suspend fun start(req: SweepRequest): String {
        if (!developerMode.value) throw ReplayDeveloperModeOff()
        require(req.traceIds.isNotEmpty() && req.traceIds.size <= ReplayService.MAX_TRACES) { "traces" }
        val points = SweepPlanner.points(req.base, req.spec)            // refuses sensing keys, bad bounds and oversize sweeps before anything is written
        if (!jobGate.tryLock()) throw ReplayBusy()
        try {
            val f = fence()
            val labelRev = dao.labelRev()
            val loaded = req.traceIds.map { it to (traces.load(it) ?: throw ReplayUnknownTrace(it)) }
            val sources = loaded.map { (id, t) -> TraceSource(id, t.revision, t.fromMs, t.toMs) }
            val baseHash = PlanJson.configHash(req.base)
            val id = newId()
            dao.insertSweep(CanvasSweepEntity(
                sweepId = id, paramsJson = req.paramsJson, sourcesJson = TraceSources.encode(sources), labelRev = labelRev, jobState = "RUNNING",
                fromMs = sources.minOf { it.fromMs }, toMs = sources.maxOf { it.toMs }, createdAt = clock(), configHash = baseHash,
            ))
            val job = scope.launch(start = CoroutineStart.LAZY) { run(id, loaded.map { it.second }, req, points, sources, labelRev, baseHash, f) }
            val r = Running(id, job)
            running = r
            job.invokeOnCompletion { if (running === r) running = null; jobGate.unlock() }
            job.start()
            return id
        } catch (e: Throwable) {
            if (running == null && jobGate.isLocked) jobGate.unlock()
            throw e
        }
    }

    private suspend fun run(id: String, ts: List<ReplayTrace>, req: SweepRequest, points: List<SweepPoint>, sources: List<TraceSource>, labelRev: Long, baseHash: String, f: DaoFence) {
        try {
            val result = withContext(dispatcher) {
                val self = coroutineContext.job
                val perPoint = points.map { p ->
                    val cand = SweepPlanner.candidate(p)
                    p to ts.map { t -> t.replay(cand, null) { self.ensureActive() } }
                }
                resultJson(req, baseHash, sources, perPoint)
            }
            val ok = withContext(NonCancellable) { dao.commitSweepFenced(id, result, baseHash, sources, labelRev, f, clock()) }
            if (!ok) withContext(NonCancellable) { dao.cancelSweep(id, clock()) }   // refused by the fence: the result is dropped, never left RUNNING
        } catch (e: CancellationException) {
            withContext(NonCancellable) { dao.cancelSweep(id, clock()) }
            throw e
        } catch (e: Throwable) {
            withContext(NonCancellable) { dao.cancelSweep(id, clock()) }            // a failed job is CANCELLED, never DONE
        }
    }

    private fun resultJson(req: SweepRequest, baseHash: String, sources: List<TraceSource>, perPoint: List<Pair<SweepPoint, List<ReplayReport>>>): String {
        val first = perPoint.first().second
        fun figures(b: kotlinx.serialization.json.JsonObjectBuilder, x: SweepFigures) {
            b.put("capturesPerMin", x.capturesPerMin); b.put("opensPerMin", x.opensPerMin); b.put("camOpenSPerMin", x.camOpenSPerMin)
            b.put("estimatedPctPerMin", x.estimatedPctPerMin); b.put("estimatedHoursFromFull", x.estimatedHoursFromFull); b.put("unobservedOpensPct", x.unobservedOpensPct)
        }
        val base = SweepObjectives.baseline(first)
        val warnings = perPoint.flatMap { (_, rs) -> rs.flatMap { it.warnings } }.distinct()
        return buildJsonObject {
            put("estimate", true)
            put("mode", req.spec.mode.name.lowercase())
            if (req.spec.mode == SweepMode.RANDOM) { put("seed", req.spec.seed); put("samples", req.spec.samples) }
            put("baseConfigHash", baseHash)
            put("traces", JsonArray(sources.map { JsonPrimitive(it.traceId) }))
            put("minutes", base.minutes)
            put("inexactWindows", first.sumOf { it.inexactWindows })
            put("baseline", buildJsonObject { figures(this, base) })
            put("points", JsonArray(perPoint.map { (p, rs) ->
                buildJsonObject {
                    put("index", p.index); put("configHash", p.configHash)
                    put("params", JsonObject(p.assignments))
                    figures(this, SweepObjectives.candidate(rs))
                }
            }))
            put("warnings", JsonArray(warnings.map { JsonPrimitive(it) }))
        }.toString()
    }

    /** Cancels the running job (if any) and waits until its CANCELLED state is persisted. */
    suspend fun cancelRunning(): Boolean {
        val r = running ?: return false
        r.job.cancel(CancellationException("cancelled"))
        r.job.join()
        withContext(NonCancellable) { dao.cancelSweep(r.id, clock()) }
        return true
    }

    suspend fun shutdown() { cancelRunning() }

    suspend fun get(id: String): CanvasSweepEntity? = dao.sweep(id)
    suspend fun list(limit: Int = 50): List<CanvasSweepEntity> = dao.sweeps(limit)
}
