package com.rokid.context.orchestration.forget

import android.content.Context
import com.rokid.context.core.log.PcLog
import com.rokid.context.ai.SafeLog
import com.rokid.context.connectors.actions.ProposalNotifications
import com.rokid.context.connectors.actions.executors.ReminderExecutor
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.MemoryMaintenance
import com.rokid.context.memory.forget.ForgetService
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

    /** How a Forget ended. [ExportInProgress] = a running export held the [DataOpGate] for 30 s: nothing was forgotten, try again (never silently dropped). */
    enum class Outcome { DONE, EXPORT_IN_PROGRESS, FAILED }

    suspend fun forgetFact(context: Context, memoryId: String, onForgotten: () -> Unit = {}): Boolean = forgetFactOutcome(context, memoryId, onForgotten) == Outcome.DONE

    suspend fun forgetMoment(context: Context, frameId: String, onForgotten: () -> Unit = {}): Boolean = forgetMomentOutcome(context, frameId, onForgotten) == Outcome.DONE

    suspend fun forgetFactOutcome(context: Context, memoryId: String, onForgotten: () -> Unit = {}): Outcome =
        run(context, onForgotten) { it.forgetFact(memoryId) }

    suspend fun forgetMomentOutcome(context: Context, frameId: String, onForgotten: () -> Unit = {}): Outcome =
        run(context, onForgotten) { it.forgetMoment(frameId) }

    /**
     * Set once by the phone at start-up (the hub refresh). Background passes (retention) call it after removing data,
     * so the next StateFrame drops it. The Memory detail route passes its own [onForgotten] as well.
     */
    @Volatile var dataRemoved: () -> Unit = {}

    /** After a committed cascade: files, then the re-summarise. A failure here is logged; the Forget itself succeeded. */
    internal suspend fun apply(context: Context, r: ForgetService.Result) {
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
        com.rokid.context.orchestration.MemoryExport.deleteCached(context)
        if (r.invalidated.isNotEmpty()) {
            try {
                ResummarizeWorker.enqueue(context)
            } catch (e: Exception) {
                PcLog.w(TAG, "re-summarise not queued (start-up retries): ${SafeLog.describe(e)}")
            }
        }
    }

    /** Text the queue log still holds in finished rows (fix round 1 I4). */
    internal fun scrubQueue(context: Context) {
        try {
            QueueRuntimeStore.scrubTerminalText(context)
        } catch (e: Exception) {
            PcLog.w(TAG, "queue scrub failed: ${SafeLog.describe(e)}")
        }
    }

    private suspend fun run(context: Context, onForgotten: () -> Unit, op: suspend (ForgetService) -> ForgetService.Result): Outcome =
        withContext(Dispatchers.IO + NonCancellable) {
            try {
                DataOpGate.shared.delete { runLocked(context, onForgotten, op) }
            } catch (e: DataOpGate.ExportInProgress) {
                PcLog.w(TAG, "forget not run: export in progress")
                Outcome.EXPORT_IN_PROGRESS
            }
        }

    private suspend fun runLocked(context: Context, onForgotten: () -> Unit, op: suspend (ForgetService) -> ForgetService.Result): Outcome {
        val r = try {
            val graph = MemoryGraph.get(context)
            op(ForgetService(graph.db, graph.proposals))
        } catch (e: Exception) {
            PcLog.w(TAG, "forget failed: ${SafeLog.describe(e)}")
            return Outcome.FAILED
        }
        // Committed: everything after this is best effort and never turns the Forget into a failure.
        apply(context, r)
        scrubQueue(context)
        runCatching { MemoryMaintenance.checkpoint(MemoryGraph.get(context).db) }
        runCatching(onForgotten)
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
        runCatching { FrameSweep.sweep(context) }
        runCatching { if (MemoryGraph.get(context).db.forgetDao().invalidatedDerived(1).isNotEmpty()) ResummarizeWorker.enqueue(context) }
        runCatching { com.rokid.context.work.OrchestrationScheduler(context).ensureBackgroundWork() } // retention and the Moment cadence
        Unit
    }
}
