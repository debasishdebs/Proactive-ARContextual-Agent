package com.rokid.context.canvas.plan

import com.rokid.context.capture.plan.PlanBounds
import com.rokid.context.capture.replay.CounterfactualReplay
import com.rokid.context.capture.replay.ReplayCandidate
import com.rokid.context.capture.replay.ReplayReport
import com.rokid.context.capture.trace.Manifest
import com.rokid.context.capture.trace.TraceEvent
import com.rokid.context.memory.db.CanvasReplayEntity
import com.rokid.context.memory.db.CanvasSlice3Dao
import com.rokid.context.memory.db.DaoFence
import com.rokid.context.memory.db.TraceSource
import com.rokid.context.memory.db.TraceSources
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job

/**
 * A trace the replay can read: the plan hash it was recorded under, the revision and interval it was read at (recorded as provenance), and [replay], one streamed
 * pass per config (never materialised). [tick] is called for every event so a cancel stops the pass at once.
 */
abstract class ReplayTrace(val planHash: String?, val revision: Int, val fromMs: Long, val toMs: Long) {
    abstract fun replay(candidate: ReplayCandidate?, candidateFusion: Map<String, Double>?, tick: () -> Unit): ReplayReport

    companion object {
        /** The production trace: [events] is re-openable (one pass per call). [recordedFusion] is the fusion block of the plan the trace was recorded under. */
        fun of(
            manifest: Manifest, revision: Int, fromMs: Long, toMs: Long, events: () -> Sequence<TraceEvent>,
            recordedFusion: Map<String, Double> = PlanBounds.FUSION_DEFAULTS,
        ): ReplayTrace = object : ReplayTrace(manifest.snapshot.planHash, revision, fromMs, toMs) {
            override fun replay(candidate: ReplayCandidate?, candidateFusion: Map<String, Double>?, tick: () -> Unit): ReplayReport =
                CounterfactualReplay.run(events, manifest, candidate, recordedFusion, candidateFusion ?: recordedFusion, tick)
        }
    }
}

/** Where traces come from. The phone binds it to the sealed chunk files (Task 22); null = unknown, tombstoned or incomplete. */
fun interface ReplayTraces { suspend fun load(traceId: String): ReplayTrace? }

/** [candidate] null = replay the recorded config only (class EXACT). [candidateFusion] null = the trace's own fusion. */
class ReplayRequest(val traceIds: List<String>, val candidate: ReplayCandidate? = null, val candidateFusion: Map<String, Double>? = null, val paramsJson: String = "{}")

class ReplayBusy : IllegalStateException("A replay is already running.")
class ReplayDeveloperModeOff : IllegalStateException("Developer mode is off.")
class ReplayUnknownTrace(val traceId: String) : NoSuchElementException("trace")

/** Why a running job was stopped (S77). */
enum class ReplayCancel { THERMAL_SEVERE, SHUTDOWN, DEVELOPER_MODE_OFF, USER }

/**
 * Runs one replay job at a time (a mutex, S77) and persists its life: RUNNING at start, then DONE (committed through the FENCED DAO, so a wipe, a tombstoned or
 * rewritten source or a moved `labelRev` drops the result), CANCELLED (thermal SEVERE, shutdown, Developer mode off, the user) or ABORTED (the process died: [recover]
 * marks every job still RUNNING at start-up). `sourcesJson` (trace id, revision, interval) and `labelRev` are recorded when the job starts, before anything is read.
 *
 * Replay is APPROXIMATE (see [CounterfactualReplay]); the result JSON carries its warnings.
 */
class ReplayService(
    private val dao: CanvasSlice3Dao,
    private val traces: ReplayTraces,
    /** Captured BEFORE a job starts and re-checked inside the commit transaction (S57, S58). */
    private val fence: () -> DaoFence,
    private val scope: CoroutineScope,
    private val developerMode: StateFlow<Boolean>,
    private val thermalSevere: Flow<Boolean>? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { "rp_" + UUID.randomUUID().toString().replace("-", "").take(16) },
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    /** The one-job gate (S77): a [SweepService] given the same mutex excludes this service and the other way round. */
    jobGate: Mutex = Mutex(),
) {
    private val gate = jobGate
    @Volatile private var running: Running? = null
    private class Running(val id: String, val job: Job, @Volatile var reason: ReplayCancel? = null)

    init {
        scope.launch { developerMode.collect { on -> if (!on) cancelRunning(ReplayCancel.DEVELOPER_MODE_OFF) } }
        thermalSevere?.let { f -> scope.launch { f.collect { severe -> if (severe) cancelRunning(ReplayCancel.THERMAL_SEVERE) } } }
    }

    /** Start-up recovery: a job still RUNNING belonged to a dead process. Returns how many were marked ABORTED. */
    suspend fun recover(): Int = dao.markRunningAborted(clock())

    /** The id of the running job, or null. */
    fun runningId(): String? = running?.id

    /** Starts a job and returns its id at once; the result lands in the row. Throws [ReplayDeveloperModeOff], [ReplayBusy] or [ReplayUnknownTrace]. */
    suspend fun start(req: ReplayRequest): String {
        if (!developerMode.value) throw ReplayDeveloperModeOff()
        require(req.traceIds.isNotEmpty() && req.traceIds.size <= MAX_TRACES) { "traces" }
        if (!gate.tryLock()) throw ReplayBusy()
        try {
            val f = fence()
            val labelRev = dao.labelRev()
            val loaded = req.traceIds.map { it to (traces.load(it) ?: throw ReplayUnknownTrace(it)) }
            val sources = loaded.map { (id, t) -> TraceSource(id, t.revision, t.fromMs, t.toMs) }
            val hashes = loaded.map { (_, t) -> t.planHash ?: "0" }.distinct()
            val configHash = req.candidate?.configHash ?: hashes.singleOrNull()
            val id = newId()
            val row = CanvasReplayEntity(
                replayId = id, paramsJson = req.paramsJson, replayClass = if (req.candidate == null) "EXACT" else "COUNTERFACTUAL",
                sourcesJson = TraceSources.encode(sources), labelRev = labelRev, jobState = "RUNNING",
                fromMs = sources.minOf { it.fromMs }, toMs = sources.maxOf { it.toMs }, createdAt = clock(), configHash = configHash,
            )
            // Fenced like the final commit: a Forget or Delete all that landed after the sources were loaded writes nothing (no row keeps their ids, intervals or parameters).
            if (!dao.insertReplayFenced(row, sources, labelRev, f)) throw ReplayUnknownTrace(req.traceIds.first())
            val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) { run(id, loaded.map { it.second }, req, sources, labelRev, configHash, f) }
            val r = Running(id, job)
            running = r
            job.invokeOnCompletion { if (running === r) running = null; gate.unlock() }
            job.start()
            return id
        } catch (e: Throwable) {
            if (running == null && gate.isLocked) gate.unlock()
            throw e
        }
    }

    private suspend fun run(id: String, ts: List<ReplayTrace>, req: ReplayRequest, sources: List<TraceSource>, labelRev: Long, configHash: String?, f: DaoFence) {
        try {
            val reports = withContext(dispatcher) {
                val self = coroutineContext.job
                ts.map { t ->
                    t.replay(req.candidate, req.candidateFusion) { self.ensureActive() }
                }
            }
            val result = resultJson(sources, reports)
            val ok = withContext(NonCancellable) { dao.commitReplayFenced(id, result, configHash, sources, labelRev, f, clock()) }
            // Refused by the fence (a wipe, a deleted or rewritten source, moved labels): the result is dropped and the job closed, never left RUNNING.
            if (!ok) withContext(NonCancellable) { dao.cancelReplay(id, clock()) }
        } catch (e: CancellationException) {
            withContext(NonCancellable) { dao.cancelReplay(id, clock()) }
            throw e
        } catch (e: Throwable) {
            withContext(NonCancellable) { dao.cancelReplay(id, clock()) }   // a failed job is CANCELLED, never DONE
        }
    }

    private fun resultJson(sources: List<TraceSource>, reports: List<ReplayReport>) = buildJsonObject {
        put("estimate", true)
        put("reports", JsonArray(reports.mapIndexed { i, r -> buildJsonObject { put("traceId", sources[i].traceId); put("report", r.toJson()) } }))
    }.toString()

    /** Cancels the running job (if any), waits until its CANCELLED state is persisted, and returns true when there was one. */
    suspend fun cancelRunning(reason: ReplayCancel): Boolean {
        val r = running ?: return false
        r.reason = reason
        r.job.cancel(CancellationException(reason.name))
        r.job.join()
        // A job cancelled before its body ever ran has not written its state: close it here (a no-op when the body already did).
        withContext(NonCancellable) { dao.cancelReplay(r.id, clock()) }
        return true
    }

    /** Service shutdown: stop the job and persist it as CANCELLED. */
    suspend fun shutdown() { cancelRunning(ReplayCancel.SHUTDOWN) }

    suspend fun get(id: String): CanvasReplayEntity? = dao.replay(id)
    suspend fun list(limit: Int = 50): List<CanvasReplayEntity> = dao.replays(limit)

    companion object { const val MAX_TRACES = 8 }
}
