package com.rokid.context.orchestration.forget

import android.content.Context
import com.rokid.context.core.log.PcLog
import com.rokid.context.ai.SafeLog
import com.rokid.context.connectors.actions.ProposalNotifications
import com.rokid.context.connectors.actions.executors.ReminderExecutor
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.MemoryMaintenance
import com.rokid.context.memory.forget.ForgetScope
import com.rokid.context.memory.forget.ForgetScopeSetting
import com.rokid.context.memory.forget.ForgetService
import com.rokid.context.memory.forget.ForgetTarget
import com.rokid.context.memory.forget.ScopedForget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import com.rokid.context.data.runtime.QueueRuntimeStore
import com.rokid.context.orchestration.DataOpGate

/**
 * Memory detail's Forget fact / Forget moment (spec §2.4, §6.1.1, R-16, R-77). On IO and not cancellable once started
 * (a privacy action: leaving the screen must not stop it part-way). The cascade is one transaction
 * ([ForgetService]); then the listed JPEGs are deleted, the WAL is truncated (deleted pages don't linger), the
 * re-summarise worker is queued when anything was invalidated, and [onForgotten] runs (the hub refresh, so the next
 * StateFrame drops the memory from `today`). False on any failure; nothing is logged but the error's class.
 *
 * In-flight work is fenced elsewhere: a queued job carries ids and re-reads them (forgotten ones dropped), a fact for a
 * tombstoned frame is never written, and a derived write whose source vanished is dropped (PipelineRecords.appendFact).
 */
object ForgetActions {
    private const val TAG = "ForgetActions"

    /**
     * How a Forget ended. [EXPORT_IN_PROGRESS] = a running export held the [DataOpGate] for 30 s: nothing was forgotten, try again (never silently dropped). [SAVE_IN_PROGRESS] = a Canvas Save
     * held it (a save is short): nothing was forgotten, try again. [CACHE_CLEANUP_FAILED] = the Forget COMMITTED but, in a production build, a cached memory export that still holds text could not be
     * deleted: it is retried at the next start and the next Forget, and the caller says what remains. [TOO_LARGE] = the facts connected to a Moment/Scene Forget are beyond the depth/size cap: nothing was deleted. [CONTENT_CHANGED] = a scoped Forget was confirmed against a different set than the one previewed (new photos, marks or Moments in the span, a changed Scene): nothing was deleted, preview again.
     */
    enum class Outcome { DONE, EXPORT_IN_PROGRESS, SAVE_IN_PROGRESS, CACHE_CLEANUP_FAILED, CONTENT_CHANGED, TOO_LARGE, FAILED;
        /** The Forget itself went through (the cached-export leftover does not undo it). */
        val committed: Boolean get() = this == DONE || this == CACHE_CLEANUP_FAILED
    }

    suspend fun forgetFact(context: Context, memoryId: String, onForgotten: () -> Unit = {}): Boolean = forgetFactOutcome(context, memoryId, onForgotten).committed

    suspend fun forgetMoment(context: Context, frameId: String, onForgotten: () -> Unit = {}): Boolean = forgetMomentOutcome(context, frameId, onForgotten).committed

    suspend fun forgetFactOutcome(context: Context, memoryId: String, onForgotten: () -> Unit = {}): Outcome =
        run(context, onForgotten, LoggedScope.FACT, shadowRoot = com.rokid.context.memory.forget.lifecycle.Ref(com.rokid.context.memory.forget.lifecycle.Family.MEMORY, memoryId)) { it.forgetFact(memoryId) }

    suspend fun forgetMomentOutcome(context: Context, frameId: String, onForgotten: () -> Unit = {}): Outcome =
        run(context, onForgotten, LoggedScope.PHOTO, shadowRoot = com.rokid.context.memory.forget.lifecycle.Ref(com.rokid.context.memory.forget.lifecycle.Family.PHOTO, frameId)) { it.forgetMoment(frameId) }

    /**
     * The granular Forget with the user's SCOPE (Photo | Moment | Scene, [ForgetScopeSetting]): memory detail's Forget fact and Forget photo, and any Moment or Scene entry, go through here. Same gate, same typed
     * outcomes, same post-commit cleanup as every Forget (cached export, reminders, notifications, outbox, queue scrub, listeners); MOMENT and SCENE run the range-Forget code ([com.rokid.context.memory.forget.ScopedForget]).
     * The scope is read ONCE here ([scope] defaults to the setting) so the dialog's counts and the delete name the same scope.
     */
    suspend fun forgetScopedOutcome(context: Context, target: ForgetTarget, scope: ForgetScope = ForgetScopeSetting.get(), expectedHash: String? = null, undoMark: (suspend (String) -> Unit)? = null, onForgotten: () -> Unit = {}): Outcome =
        if (target is ForgetTarget.Mark) markOutcome(target.labelId, onForgotten, undoMark)
        else {
            var ran: ForgetScope? = null
            run(context, onForgotten, { ran?.let { LoggedScope.valueOf(it.name) } ?: LoggedScope.PHOTO }) { svc ->
                val engine = ScopedForget(MemoryGraph.get(context).db, svc)
                val plan = engine.plan(target, scope)
                ran = plan.effective
                engine.forget(plan, expectedHash).cascade
            }
        }

    /**
     * A mark is undone through the recorder's own undo ([undoMark]: the fence, the gated delete, the Moment of its second rebuilt): the same typed outcomes as every Forget. No delete logic here.
     * With no recorder given nothing is removed.
     */
    private suspend fun markOutcome(labelId: String, onForgotten: () -> Unit, undoMark: (suspend (String) -> Unit)?): Outcome {
        if (undoMark == null) return Outcome.FAILED
        return withContext(Dispatchers.IO + NonCancellable) {
            try {
                undoMark(labelId)
                runCatching(onForgotten)
                Outcome.DONE
            } catch (e: DataOpGate.WriteInProgress) {
                Outcome.SAVE_IN_PROGRESS
            } catch (e: DataOpGate.ExportInProgress) {
                Outcome.EXPORT_IN_PROGRESS
            } catch (e: Exception) {
                PcLog.w(TAG, "mark forget failed: ${SafeLog.describe(e)}")
                Outcome.FAILED
            }
        }
    }

    /** What [forgetScopedOutcome] would remove, for the confirm dialog (read only, no gate): the scope it really runs at and the counts. */
    data class ScopedPreview(val effective: ForgetScope, val photos: Int, val memories: Int, val marks: Int, val moments: Int, val scenesRemoved: Int, val scenesShrunk: Int, val planHash: String, val outside: Int = 0, val tooLarge: Boolean = false, val peerResults: Int = 0, val needsAck: Boolean = false, val ackHash: String = "")
    suspend fun previewScoped(context: Context, target: ForgetTarget, scope: ForgetScope = ForgetScopeSetting.get()): ScopedPreview = withContext(Dispatchers.IO) {
        val graph = MemoryGraph.get(context)
        val engine = ScopedForget(graph.db, ForgetService(graph.db, graph.proposals))
        val plan = engine.plan(target, scope); val c = engine.preview(plan)
        ScopedPreview(plan.effective, c.photos, c.memories, c.marks, c.moments, c.scenesRemoved, c.scenesShrunk, c.planHash, c.outside, c.tooLarge, c.peerResults, c.needsAck, c.ackHash)
    }

    /**
     * Set once by the phone at start-up (the hub refresh). Background passes (retention) call it after removing data,
     * so the next StateFrame drops it. The Memory detail route passes its own [onForgotten] as well.
     */
    @Volatile var dataRemoved: () -> Unit = {}

    /**
     * rc1 fix (set once by the phone at start-up): the ids of the asks a committed removal redacted or deleted. An answer still shown for one of them (the phone's voice
     * card, the glasses HUD through the hub's frames) is withdrawn at once, whatever the voice hold or the row watcher did. Called after the commit, on IO; never throws into the Forget.
     */
    @Volatile var asksRedacted: (List<String>) -> Unit = {}

    /** The scope a Forget's milestone line names (a fixed token: FACT and PHOTO for the single-item Forgets, MOMENT/SCENE for the scoped engine, RANGE for a time range). */
    enum class LoggedScope { FACT, PHOTO, MOMENT, SCENE, RANGE }

    /**
     * rc1 fix: ONE milestone line per committed Forget (reaches `pca.log` with diagnostics off), scope and counts only: never an id, a path or any text.
     * photos = phone photo files the Forget removed (a JPEG, not its sidecar); memories = rows deleted; asks = answers redacted.
     */
    internal fun logDone(kind: LoggedScope, r: ForgetService.Result) {
        val photos = r.framesToDelete.filterNot { it.endsWith(".meta.json") }
        PcLog.milestone(TAG, "forget done scope=${kind.name} photos=${photos.size} memories=${r.forgotten.size} invalidated=${r.invalidated.size} asks=${r.redactedAskIds.size}")
    }

    /**
     * Codex r21: moves after every committed removal (a Forget of any kind, each retention chunk, Delete all). The canvas page reads it
     * (GET /api/data-generation) and reads its open view again when it moved, so nothing removed stays on screen there.
     */
    val removals = java.util.concurrent.atomic.AtomicLong(0)

    /** After a committed cascade: files, then the re-summarise. A failure here is logged; the Forget itself succeeded. Returns false when a cached memory export could not be deleted. */
    internal suspend fun apply(context: Context, r: ForgetService.Result): Boolean {
        removals.incrementAndGet() // the cascade is committed
        if (r.redactedAskIds.isNotEmpty()) {
            try { asksRedacted(r.redactedAskIds) } catch (e: Exception) { PcLog.w(TAG, "answer withdrawal not sent: ${SafeLog.describe(e)}") }
        }
        // Integration carry 3: a redacted proposal's notifications still show its text; cancel them in any process.
        // Privacy carry: so does an armed reminder alarm (its intent holds the title and note); cancel it too.
        if (r.redactedProposals.isNotEmpty()) {
            try {
                r.redactedProposals.forEach { ReminderExecutor.cancelAlarm(context, it) }
            } catch (e: Exception) {
                PcLog.w(TAG, "alarms not cancelled: ${SafeLog.describe(e)}")
            }
            try {
                ProposalNotifications.cancel(context, r.redactedProposals)
            } catch (e: Exception) {
                PcLog.w(TAG, "notifications not cancelled: ${SafeLog.describe(e)}")
            }
        }
        FrameSweep.delete(r.framesToDelete)
        // GAP-1: a cached memory export still holds the forgotten text; the next export regenerates it.
        val cacheClean = com.rokid.context.orchestration.MemoryExport.deleteCached(context)
        if (r.invalidated.isNotEmpty()) {
            try {
                ResummarizeWorker.enqueue(context)
            } catch (e: Exception) {
                PcLog.w(TAG, "re-summarise not queued (start-up retries): ${SafeLog.describe(e)}")
            }
        }
        return cacheClean
    }

    /** The cleanup after a committed RANGE Forget (the coordinator calls it inside the delete gate): the same as a fact or photo Forget. False = a cached export could not be deleted. */
    suspend fun afterRange(context: Context, r: ForgetService.Result): Boolean {
        logDone(LoggedScope.RANGE, r)
        val clean = apply(context, r)
        scrubQueue(context)
        runCatching { MemoryMaintenance.checkpoint(MemoryGraph.get(context).db) }
        runCatching(dataRemoved)
        return clean
    }

    /** A time-range Forget with the typed outcome (the coordinator must have been built with `afterCascade = { afterRange(ctx, it) }`). */
    suspend fun forgetRangeOutcome(context: Context, coordinator: com.rokid.context.canvas.plan.CanvasForgetCoordinator, fromMs: Long, toMs: Long): Outcome =
        withContext(Dispatchers.IO + NonCancellable) {
            try {
                val r = coordinator.forgetRange(fromMs, toMs)
                if (!r.cacheClean && com.rokid.context.orchestration.MemoryExport.productionBuild(context)) Outcome.CACHE_CLEANUP_FAILED else Outcome.DONE
            } catch (e: DataOpGate.WriteInProgress) { Outcome.SAVE_IN_PROGRESS
            } catch (e: DataOpGate.ExportInProgress) { Outcome.EXPORT_IN_PROGRESS
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Exception) { PcLog.w(TAG, "range forget failed: ${SafeLog.describe(e)}"); Outcome.FAILED }
        }

    /** Text the queue log still holds in finished rows (fix round 1 I4). */
    internal fun scrubQueue(context: Context) {
        try {
            QueueRuntimeStore.scrubTerminalText(context)
        } catch (e: Exception) {
            PcLog.w(TAG, "queue scrub failed: ${SafeLog.describe(e)}")
        }
    }

    private suspend fun run(context: Context, onForgotten: () -> Unit, kind: LoggedScope, shadowRoot: com.rokid.context.memory.forget.lifecycle.Ref? = null, op: suspend (ForgetService) -> ForgetService.Result): Outcome =
        run(context, onForgotten, { kind }, shadowRoot, op)

    private suspend fun run(context: Context, onForgotten: () -> Unit, kind: () -> LoggedScope, shadowRoot: com.rokid.context.memory.forget.lifecycle.Ref? = null, op: suspend (ForgetService) -> ForgetService.Result): Outcome =
        withContext(Dispatchers.IO + NonCancellable) {
            try {
                DataOpGate.shared.delete { runLocked(context, onForgotten, kind, shadowRoot, op) }
            } catch (e: DataOpGate.WriteInProgress) {
                PcLog.w(TAG, "forget not run: save in progress")
                Outcome.SAVE_IN_PROGRESS
            } catch (e: DataOpGate.ExportInProgress) {
                PcLog.w(TAG, "forget not run: export in progress")
                Outcome.EXPORT_IN_PROGRESS
            }
        }

    private suspend fun runLocked(context: Context, onForgotten: () -> Unit, kind: () -> LoggedScope, shadowRoot: com.rokid.context.memory.forget.lifecycle.Ref?, op: suspend (ForgetService) -> ForgetService.Result): Outcome {
        val shadow = if (shadowRoot != null) ForgetShadow.before(context, shadowRoot) else null // Forget lifecycle M2: reads only, off by default (DeveloperFlags), never affects the Forget
        val r = try {
            val graph = MemoryGraph.get(context)
            op(ForgetService(graph.db, graph.proposals))
        } catch (e: com.rokid.context.memory.forget.ForgetContentChanged) {
            PcLog.w(TAG, "forget not run: content changed")
            return Outcome.CONTENT_CHANGED
        } catch (e: com.rokid.context.memory.forget.ForgetNeedsAck) {
            PcLog.w(TAG, "forget not run: acknowledgement needed") // the dialog opens again with the warning and the acknowledgement
            return Outcome.CONTENT_CHANGED
        } catch (e: com.rokid.context.memory.forget.ForgetTooLarge) {
            PcLog.w(TAG, "forget not run: too large")
            return Outcome.TOO_LARGE
        } catch (e: Exception) {
            PcLog.w(TAG, "forget failed: ${SafeLog.describe(e)}")
            return Outcome.FAILED
        }
        // Committed: everything after this is best effort and never turns the Forget into a failure.
        runCatching { logDone(kind(), r) }
        runCatching { ForgetShadow.after(shadow, kind(), r) }
        val cacheClean = apply(context, r)
        scrubQueue(context)
        runCatching { MemoryMaintenance.checkpoint(MemoryGraph.get(context).db) }
        runCatching(onForgotten)
        if (!cacheClean && com.rokid.context.orchestration.MemoryExport.productionBuild(context)) {
            PcLog.w(TAG, "forget done, cached export not deleted")
            return Outcome.CACHE_CLEANUP_FAILED
        }
        return Outcome.DONE
    }
}

/**
 * Start-up (fix round 1): sweep photos a failed delete left behind, rebuild anything still INVALIDATED (a re-summarise
 * that never got queued or gave up), and make sure retention and the Moment cadence are scheduled, whatever the pipeline state.
 */
object ForgetStartup {
    suspend fun run(context: Context, refresh: () -> Unit) = withContext(Dispatchers.IO) {
        ForgetActions.dataRemoved = refresh
        runCatching { com.rokid.context.orchestration.MemoryExport.retryPending(context) } // a cached export an earlier Forget could not delete (bounded)
        runCatching { FrameSweep.sweep(context) }
        // v0.9.6: the one-time purge of invalid Moments and orphan memories, BEFORE the cadence is scheduled so a rebuild cannot race it (it stores its own marker and does nothing afterwards)
        try {
            val graph = MemoryGraph.get(context)
            com.rokid.context.canvas.moment.InvalidMomentPurge(graph.db, ForgetService(graph.db, graph.proposals), after = { ForgetActions.apply(context, it) }).runOnce()
        } catch (c: kotlinx.coroutines.CancellationException) { throw c } catch (e: Exception) { PcLog.i("PcMoments", "invalid purge failed (${e.javaClass.simpleName})") }
        runCatching { if (MemoryGraph.get(context).db.forgetDao().invalidatedDerived(1).isNotEmpty()) ResummarizeWorker.enqueue(context) }
        runCatching { com.rokid.context.work.OrchestrationScheduler(context).ensureBackgroundWork() } // retention and the Moment cadence
        Unit
    }
}
