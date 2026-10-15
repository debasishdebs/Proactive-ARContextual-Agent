package com.rokid.context.orchestration.nudge

import android.util.Log
import com.rokid.context.ai.SafeLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/** What one evaluation concluded. Showing it is a separate step ([NudgeGates.check]: shown-nudge gap and daily cap). */
data class NudgeDecision(val shouldNudge: Boolean, val text: String = "", val reason: String = "")

/**
 * The evaluate half of the fast path (spec §P2.1): only memories offered as [eligible] (salient or dwell keyframe, or
 * a new place) count. The first one opens a settle window of [settleMs]; anything eligible arriving inside it joins
 * the same evaluation. Evaluations start at least [gapMs] apart and never overlap: an arrival inside the gap, or during a
 * running check, opens the next window once the gap and the check have ended. The notify throttle is NOT applied here.
 *
 * Latency per decision = decision time minus the earliest capture time in the batch, corrected by the clock offset
 * (phone minus glasses), never raw glasses time.
 */
class NudgeEvaluator(
    private val clock: () -> Long,
    private val settleMs: () -> Long,
    private val gapMs: () -> Long,
    private val evaluate: suspend (List<String>) -> NudgeDecision,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val out = MutableSharedFlow<Pair<NudgeDecision, Long>>(extraBufferCapacity = 16)
    val decisions: SharedFlow<Pair<NudgeDecision, Long>> = out.asSharedFlow()

    private val lock = Any()
    private val pending = LinkedHashSet<String>()
    private var earliestCaptureMs = Long.MAX_VALUE
    private var lastStartMs = Long.MIN_VALUE / 2
    private var windowOpen = false

    /** [captureMs] is on the glasses clock; [clockOffsetMs] converts it to phone time. Defaults to "just now". */
    fun offer(memoryId: String, eligible: Boolean, captureMs: Long? = null, clockOffsetMs: Long = 0L) {
        if (!eligible) return
        val now = clock()
        val open: Boolean
        val waitMs: Long
        synchronized(lock) {
            pending += memoryId
            earliestCaptureMs = minOf(earliestCaptureMs, captureMs?.plus(clockOffsetMs) ?: now)
            open = !windowOpen
            windowOpen = true
            waitMs = maxOf(lastStartMs + gapMs(), now) + settleMs() - now
        }
        if (open) scope.launch { runWindow(waitMs) }
    }

    /**
     * Runs windows back to back while arrivals keep coming. The window stays "open" until the current check ENDS, so two
     * checks never overlap; arrivals during a check wait for the next window (gap counted from the last check's start).
     */
    private suspend fun runWindow(firstWaitMs: Long) {
        var waitMs = firstWaitMs
        while (true) {
            delay(waitMs)
            val ids: List<String>
            val captured: Long
            synchronized(lock) {
                ids = pending.toList(); pending.clear()
                captured = earliestCaptureMs; earliestCaptureMs = Long.MAX_VALUE
                lastStartMs = clock()
            }
            val decision = try {
                evaluate(ids)
            } catch (c: CancellationException) {
                synchronized(lock) { windowOpen = false }
                throw c
            } catch (e: Exception) {
                Log.i(TAG, "nudge check failed: ${SafeLog.describe(e)}") // class only, no text; the periodic backstop covers it
                null
            }
            if (decision != null) out.emit(decision to (clock() - captured).coerceAtLeast(0L))
            synchronized(lock) {
                if (pending.isEmpty()) { windowOpen = false; return }
                val now = clock()
                waitMs = maxOf(lastStartMs + gapMs(), now) + settleMs() - now
            }
        }
    }

    private companion object { const val TAG = "PcNudge" }
}
