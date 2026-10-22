package com.rokid.context.canvas

import android.util.Log
import com.rokid.context.ai.canvas.Pipeline
import com.rokid.context.ai.canvas.PipelineJson
import com.rokid.context.ai.run.RoleRunner
import com.rokid.context.memory.db.CanvasDao
import com.rokid.context.memory.db.CanvasLiveTraceEntity
import com.rokid.context.memory.db.CanvasPublishEntity
import com.rokid.context.model.CallClass
import com.rokid.context.orchestration.ExtractPhoto
import com.rokid.context.orchestration.ExtractSave
import com.rokid.context.orchestration.ExtractedBatch
import com.rokid.context.orchestration.MemoryWipe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException

/** Mode of an interpreter publish: Shadow only records traces, Live replaces the extraction step. */
enum class LiveMode { SHADOW, LIVE }

/**
 * What a batch buffered for after the save (S24, S29): [traces] and [labels] are written by [LiveExtract.commit] only after the save returned `Saved`.
 * [shadow]: the active publish is a SHADOW one, so the caller may schedule [LiveExtract.shadow] after the save.
 * [None] (no interpreter publish): nothing to write, nothing to schedule.
 */
class LiveEffects(
    val publishId: Long?,
    val traces: List<CanvasLiveTraceEntity>,
    val labels: Map<String, Pair<String, Float?>>,
    val fellBack: Boolean,
    val shadow: Boolean = false,
) {
    companion object { val None = LiveEffects(null, emptyList(), emptyMap(), false) }
}

class LiveBatch(val batch: ExtractedBatch, val effects: LiveEffects)

class LiveDeps(
    val dao: CanvasDao,
    val runner: RoleRunner,
    val context: (runId: String, callClass: CallClass) -> RunContext,
    val interpreter: PipelineInterpreter = PipelineInterpreter(),
    val sink: FrameIntentSink = FrameIntentSink.None,
    /** Auto-rollback: (publish id, fixed reason) -> whether this call rolled it back. */
    val publisher: suspend (Long, String) -> Boolean,
    /** Developer mode on (Shadow pauses while it is off). */
    val shadowAllowed: () -> Boolean,
    val clock: () -> Long = System::currentTimeMillis,
    /** S30. Injectable so tests need not wait four minutes. */
    val deadlineMs: Long = LiveExtract.INTERPRETER_DEADLINE_MS,
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    internal val shadowCalls = AtomicInteger(0)
    internal val shadowBusy = AtomicBoolean(false)
    @Volatile internal var cached: Pair<Long, Pipeline>? = null
}

/**
 * The live extractor seam (spec section 5). With no interpreter publish, [batch] is exactly `ExtractSave.extract(runner, photos, callClass)` and
 * [commit] touches nothing. The interpreter itself writes nothing: traces and labels are buffered in [LiveEffects].
 */
object LiveExtract {
    const val FALLBACK_WINDOW = 30
    const val FALLBACK_LIMIT = 10
    const val SHADOW_EVERY = 4
    const val TRACE_KEEP = 5_000
    const val TRACE_DAYS = 7L
    const val INTERPRETER_DEADLINE_MS = 240_000L
    const val AUTO_ROLLBACK_REASON = "auto: 10 of 30 fell back"
    private const val TAG = "LiveExtract"

    /** The extraction step of the keyframe extractor. Only an active LIVE interpreter publish changes the result. */
    suspend fun batch(deps: LiveDeps, photos: List<ExtractPhoto>, callClass: CallClass): LiveBatch {
        val pub = try { deps.dao.activeInterpreter() } catch (c: CancellationException) { throw c } catch (e: Exception) {
            Log.w(TAG, "publish lookup failed: ${e.javaClass.simpleName}"); null
        }
        if (pub == null) return LiveBatch(ExtractSave.extract(deps.runner, photos, callClass), LiveEffects.None)
        if (pub.mode != LiveMode.LIVE.name) return LiveBatch(ExtractSave.extract(deps.runner, photos, callClass), LiveEffects(pub.id, emptyList(), emptyMap(), false, shadow = true))

        val outcome = interpret(deps, pub, "LIVE", photos) { deps.context("live:${pub.id}", callClass) }
        return when (outcome) {
            is PipelineInterpreter.Outcome.Extracted -> LiveBatch(outcome.batch, LiveEffects(pub.id, outcome.traces, outcome.labels, false))
            // memories are never lost to an experiment: the whole batch goes through the live extraction; its own failure propagates as live does today
            is PipelineInterpreter.Outcome.Failed -> LiveBatch(ExtractSave.extract(deps.runner, photos, callClass), LiveEffects(pub.id, outcome.traces, emptyMap(), true))
        }
    }

    /**
     * After a successful live save in SHADOW mode, every [SHADOW_EVERY]-th call runs the interpreter as EXPERIMENT (when [LiveDeps.shadowAllowed])
     * and commits its traces (fenced, no sink). At most one runs at a time. Never throws except cancellation.
     */
    suspend fun shadow(deps: LiveDeps, photos: List<ExtractPhoto>, generation: Long) {
        try {
            if (photos.isEmpty() || !deps.shadowAllowed()) return
            if (deps.shadowCalls.incrementAndGet() % SHADOW_EVERY != 0) return
            if (!deps.shadowBusy.compareAndSet(false, true)) return
            try {
                val pub = deps.dao.activeInterpreter()?.takeIf { it.mode == LiveMode.SHADOW.name } ?: return
                // privacy: the photos were loaded before the live save; a Delete all or a Forget since then must stop the run BEFORE any model call
                val ids = photos.map { it.frameId }
                if (MemoryWipe.generation.get() != generation || deps.dao.tombstonedAmong(ids) > 0) return
                val outcome = interpret(deps, pub, "SHADOW", photos) { deps.context("shadow:${pub.id}", CallClass.EXPERIMENT) }
                val traces = when (outcome) {
                    is PipelineInterpreter.Outcome.Extracted -> outcome.traces
                    is PipelineInterpreter.Outcome.Failed -> outcome.traces
                }
                if (deps.dao.activeInterpreter()?.id != pub.id) return // rolled back while it ran: nothing is recorded for it
                deps.dao.commitTracesFenced(traces, generation, photos.map { it.frameId }.toSet(), MemoryWipe.generation::get)
            } finally { deps.shadowBusy.set(false) }
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            Log.w(TAG, "shadow failed: ${e.javaClass.simpleName}")
        }
    }

    /** [shadow] in the deps' scope, without awaiting. */
    fun shadowAsync(deps: LiveDeps, photos: List<ExtractPhoto>, generation: Long) {
        deps.scope.launch { shadow(deps, photos, generation) }
    }

    /**
     * After `ExtractSaveResult.Saved` only. [LiveEffects.None] returns at once with no DB access. Otherwise each step is its own `runCatching`
     * (cancellation rethrown): the fenced trace commit, the sink for the labels of saved frames (only when the traces were committed, so a
     * fence that dropped them also drops the labels; also nothing when the wipe generation moved meanwhile), then the auto-rollback check.
     * Never throws except cancellation, and never changes the batch or the save.
     */
    suspend fun commit(deps: LiveDeps, effects: LiveEffects, generation: Long, savedFrames: Set<String>) {
        val id = effects.publishId ?: return
        if (effects.traces.isEmpty() && effects.labels.isEmpty() && !effects.fellBack) return
        var committed = false
        try {
            committed = deps.dao.commitTracesFenced(effects.traces, generation, savedFrames, MemoryWipe.generation::get)
        } catch (c: CancellationException) { throw c } catch (e: Exception) { Log.w(TAG, "trace commit failed: ${e.javaClass.simpleName}") }

        if (committed && !effects.fellBack) {
            for ((frameId, label) in effects.labels) {
                if (frameId !in savedFrames) continue
                if (MemoryWipe.generation.get() != generation) break
                try { deps.sink.record(frameId, label.first, label.second, deps.clock()) }
                catch (c: CancellationException) { throw c } catch (e: Exception) { Log.w(TAG, "intent sink failed: ${e.javaClass.simpleName}") }
            }
        }

        // After EVERY committed outcome, not only a fallback: the 30th photo of a window holding 10 failures may itself be a success.
        if (committed || effects.fellBack) {
            try {
                if (deps.dao.interpretedCountUpTo(id, FALLBACK_WINDOW) >= FALLBACK_WINDOW && deps.dao.recentFallbacks(id, FALLBACK_WINDOW) >= FALLBACK_LIMIT) {
                    deps.publisher(id, AUTO_ROLLBACK_REASON)
                }
            } catch (c: CancellationException) { throw c } catch (e: Exception) { Log.w(TAG, "auto-rollback check failed: ${e.javaClass.simpleName}") }
        }
    }

    /**
     * Runs the published version under the deadline (S30). A transient failure (S23) and cancellation propagate; the deadline, a version that can't be
     * loaded and any other throwable from the interpreter path are [PipelineInterpreter.Outcome.Failed] with their own `_batch` rows.
     */
    private suspend fun interpret(deps: LiveDeps, pub: CanvasPublishEntity, mode: String, photos: List<ExtractPhoto>, ctx: () -> RunContext): PipelineInterpreter.Outcome {
        fun failed(reason: String) = PipelineInterpreter.Outcome.Failed(reason, photos.map {
            CanvasLiveTraceEntity(pub.id, it.frameId, PipelineInterpreter.BATCH, mode, "ERROR", null, null, reason, null, deps.clock())
        })
        return try {
            val p = load(deps, pub) ?: return failed("internal")
            withTimeoutOrNull(deps.deadlineMs) { deps.interpreter.run(p, pub.id, mode, photos, ctx()) } ?: failed("deadline")
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            if (PipelineInterpreter.isTransient(e)) throw e
            Log.w(TAG, "interpreter failed: ${e.javaClass.simpleName}")
            failed("internal")
        }
    }

    private suspend fun load(deps: LiveDeps, pub: CanvasPublishEntity): Pipeline? {
        deps.cached?.takeIf { it.first == pub.id }?.let { return it.second }
        val v = deps.dao.version(pub.pipelineId, pub.version) ?: return null
        val p = PipelineJson.decode(v.json).getOrNull() ?: return null
        deps.cached = pub.id to p
        return p
    }
}
