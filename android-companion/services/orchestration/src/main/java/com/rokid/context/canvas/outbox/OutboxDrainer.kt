package com.rokid.context.canvas.outbox

import com.rokid.context.core.log.Level
import com.rokid.context.core.log.LogFields
import com.rokid.context.core.log.LogPort
import com.rokid.context.memory.db.CleanupPayload
import com.rokid.context.memory.db.CleanupTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Turns `cleanup_outbox` rows into real deletions (moment-scene decisions 6.1). Pure logic over ports ([OutboxStore], [PhoneFilePort], [GlassesCleanupPort], [HelperCleanupPort]);
 * the Room and file adapters live beside it, the trigger is `MomentCadenceWorker` (no always-running service).
 *
 * - **A row goes only after the deletion is CONFIRMED** by its port. A crash between the delete and the row removal leaves the row; the next run finds the file already gone, which counts as done.
 * - **Phone rows first**, then the rest, each by id. At most [maxRowsPerRun] rows are attempted per run.
 * - **Failure keeps the row** and charges one attempt; the row is not touched again before [backoffMs] of its attempt count has passed (doubling from [BASE_BACKOFF_MS] to [MAX_BACKOFF_MS]).
 *   A row is never given up on (a privacy deletion does not expire): past [STUCK_ATTEMPTS] it is only reported as stuck.
 * - **Not reachable is not a failure**: a port that cannot be reached (probed once per run) keeps its rows untouched, charges no attempt and uses no run budget. Delete all (`ALL`) needs the
 *   glasses AND the helper; each reachable half runs, the row stays until both confirmed (both are idempotent). The phone's own part of Delete all is `MemoryWipe`'s.
 * - **A row it cannot read or does not know is never acted on**: kept and counted ([Report.malformed]).
 * - **Logs counts only** (never an id, path, range or exception message).
 */
class OutboxDrainer(
    private val store: OutboxStore,
    private val files: PhoneFilePort,
    private val glasses: GlassesCleanupPort,
    private val helper: HelperCleanupPort,
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: LogPort,
    private val maxRowsPerRun: Int = MAX_ROWS_PER_RUN,
) {
    /** [removed] rows confirmed and deleted, [failed] rows that failed this run, [deferred] rows left for an unreachable port, [waiting] rows still inside their backoff, [malformed] unreadable or unknown rows, [stuck] rows past [STUCK_ATTEMPTS]. */
    data class Report(val removed: Int, val failed: Int, val deferred: Int, val waiting: Int, val malformed: Int, val stuck: Int = 0, val dropped: Int = 0) {
        companion object { val NONE = Report(0, 0, 0, 0, 0) }
    }

    private enum class Port { FILES, GLASSES, HELPER }
    private class Step(val port: Port, val run: suspend () -> CleanupResult)

    private val lock = Mutex()

    /** A delete: the exclusive side of the [com.rokid.context.orchestration.DataOpGate]. While an export runs for 30 s the rows simply stay pending (nothing is dropped) and the next trigger retries. */
    suspend fun drain(): Report = try {
        com.rokid.context.orchestration.DataOpGate.shared.delete { lock.withLock { drainLocked() } }
    } catch (e: com.rokid.context.orchestration.DataOpGate.ExportInProgress) {
        log.log(Level.I, "OutboxDrainer", "drain deferred: export in progress")
        Report.NONE
    }

    private suspend fun drainLocked(): Report {
        val before = store.attempts()
        val now = clock()
        val total = store.count()
        if (total == 0) {
            if (before.isNotEmpty()) store.saveAttempts(emptyMap())
            return Report.NONE
        }
        // Bounded read: only rows that are due (not in their backoff), at most WINDOW of them, phone files first.
        val inBackoff = before.filter { (_, a) -> now >= a.lastAtMs && now - a.lastAtMs < backoffMs(a.count) }.keys.toList()
        val rows = store.due(WINDOW, inBackoff)
        val live = store.existing(before.keys)
        val attempts = before.filterKeys { it in live }.toMutableMap()
        val reach = HashMap<Port, Boolean>()
        suspend fun reachable(p: Port): Boolean = reach.getOrPut(p) {
            try {
                when (p) { Port.FILES -> true; Port.GLASSES -> glasses.reachable(); Port.HELPER -> helper.reachable() }
            } catch (e: CancellationException) { throw e } catch (e: Exception) { false }
        }
        var budget = maxRowsPerRun; var removed = 0; var failed = 0; var deferred = 0; var waiting = inBackoff.count { it in live }; var malformed = 0
        try {
            for (row in rows) {
                if (budget <= 0) break
                val steps = plan(row)
                if (steps == null) { malformed++; continue }
                val a = attempts[row.id]
                if (a != null && now >= a.lastAtMs && now - a.lastAtMs < backoffMs(a.count)) { waiting++; continue }
                var anyDeferred = false; var anyFailed = false; var attempted = false
                for (s in steps) {
                    if (!reachable(s.port)) { anyDeferred = true; continue }
                    attempted = true
                    val r = try { s.run() } catch (e: CancellationException) { throw e } catch (e: Exception) { CleanupResult.FAILED }
                    when (r) {
                        CleanupResult.DONE -> {}
                        CleanupResult.FAILED -> anyFailed = true
                        CleanupResult.NOT_REACHABLE -> { reach[s.port] = false; anyDeferred = true }
                    }
                }
                if (attempted) budget--
                when {
                    anyFailed -> { failed++; attempts[row.id] = OutboxAttempt((a?.count ?: 0) + 1, now) }
                    anyDeferred -> deferred++
                    else -> try {
                        store.remove(row.id); attempts.remove(row.id); removed++
                    } catch (e: CancellationException) { throw e } catch (e: Exception) {
                        failed++; attempts[row.id] = OutboxAttempt((a?.count ?: 0) + 1, now)
                    }
                }
            }
        } finally {
            if (attempts != before) withContext(NonCancellable) { store.saveAttempts(attempts) }
        }
        // A glasses transport that stays unreachable must not grow the table without bound: each glasses target keeps its newest GLASSES_CAP_PER_TARGET rows; the oldest are dropped and only counted.
        var dropped = 0
        for (t in CAPPED_TARGETS) dropped += try { store.trim(t, GLASSES_CAP_PER_TARGET) } catch (e: CancellationException) { throw e } catch (e: Exception) { 0 }
        val stuck = attempts.values.count { it.count >= STUCK_ATTEMPTS }
        val report = Report(removed, failed, deferred, waiting, malformed, stuck, dropped)
        log.log(Level.I, "OutboxDrainer", "drain removed=${LogFields.count(removed)} failed=${LogFields.count(failed)} deferred=${LogFields.count(deferred)} " +
            "waiting=${LogFields.count(waiting)} malformed=${LogFields.count(malformed)} stuck=${LogFields.count(stuck)} left=${LogFields.count(total - removed - dropped)} dropped=${LogFields.count(dropped)}")
        return report
    }

    /** The steps a row needs, or null when the row is unreadable or of an unknown kind. */
    private fun plan(row: OutboxRow): List<Step>? = when (row.target) {
        CleanupTarget.PHONE_FILE -> CleanupPayload.parseOne(row.payloadJson, "path")?.takeIf { it.isNotBlank() }?.let { p -> listOf(Step(Port.FILES) { files.deleteConfirmed(p) }) }
        CleanupTarget.GLASSES_FRAME -> CleanupPayload.parseOne(row.payloadJson, "wireFrameId")?.takeIf { it.isNotBlank() }?.let { w -> listOf(Step(Port.GLASSES) { glasses.deleteFrame(w) }) }
        CleanupTarget.GLASSES_TRACE -> CleanupPayload.parseOne(row.payloadJson, "traceId")?.takeIf { it.isNotBlank() }?.let { t -> listOf(Step(Port.GLASSES) { glasses.deleteTrace(t) }) }
        CleanupTarget.GLASSES_RANGE -> CleanupPayload.parseRange(row.payloadJson)?.let { (a, b) -> listOf(Step(Port.GLASSES) { glasses.deleteRange(a, b) }) }
        CleanupTarget.ALL -> listOf(Step(Port.GLASSES) { glasses.wipeAll() }, Step(Port.HELPER) { helper.wipeAll() })
        else -> null
    }

    companion object {
        const val MAX_ROWS_PER_RUN = 50
        /** Rows read per run (the SQL LIMIT). */
        const val WINDOW = 200
        /** Hard cap of pending rows per glasses target; the glasses transport is not built, so an unreachable glasses must not grow the table forever. Phone-file rows are never capped. */
        const val GLASSES_CAP_PER_TARGET = 2_000
        private val CAPPED_TARGETS = listOf(CleanupTarget.GLASSES_FRAME, CleanupTarget.GLASSES_TRACE, CleanupTarget.GLASSES_RANGE)
        const val BASE_BACKOFF_MS = 5 * 60_000L
        const val MAX_BACKOFF_MS = 6 * 3_600_000L
        const val STUCK_ATTEMPTS = 20

        /** Wait after the [attempts]-th failure: [BASE_BACKOFF_MS] doubling, capped at [MAX_BACKOFF_MS]. */
        fun backoffMs(attempts: Int): Long {
            if (attempts <= 1) return BASE_BACKOFF_MS
            if (attempts > 20) return MAX_BACKOFF_MS
            return minOf(BASE_BACKOFF_MS shl (attempts - 1), MAX_BACKOFF_MS)
        }
    }
}
