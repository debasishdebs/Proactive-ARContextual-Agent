package com.rokid.context.glasses.capture.trace

import com.rokid.context.glasses.log.DiagFlagState

/**
 * LGC5 (plan 3.6): the `trace state:` line, so "no trace" always names its cause. ONE blocker model with two producers: the recorder ([TraceRecorder.startBlocker], read-only),
 * then the session ([TraceBoundaryReporter]). The published blocker is the first of: the recorder's blocker (with `armed_no_epoch` named instead of the generic `gate_off`
 * when the phone asked for diagnostics but no epoch is saved), `already_recording` (a NORMAL state: `rec=1`, never a warning), the session's blocker, else `none`.
 *
 * Printed by [poll] when the blocker changes and at most once per [heartbeatMs] otherwise. [poll] is driven by loops that already exist (the capture supervisor's 1 s status
 * tick, the runtime's 30 s heartbeat): no timer and no wake-up of its own. Everything it reads is memory (volatile and atomic reads, no disk, no lock but its own), so it is safe
 * on any thread. Counts and fixed words only. Recording depends on nothing but the gate and the clean boundary, so nothing here reads the phone link.
 */
class TraceStateReporter(
    private val recorder: TraceRecorder,
    private val boundary: TraceBoundaryReporter,
    private val flagState: () -> DiagFlagState,
    private val clock: () -> Long,
    /** `warn` is true for a fault (the log level), false for everything else. */
    private val emit: (warn: Boolean, line: String) -> Unit,
    private val heartbeatMs: Long = 60_000L,
) {
    private var lastBlocker: String? = null
    private var lastAt = 0L

    /** The published blocker (a closed vocabulary). */
    fun blocker(): String {
        val rb = recorder.startBlocker()
        return when {
            rb == TraceStartBlocker.GATE_OFF && flagState() == DiagFlagState.ARMED_NO_EPOCH -> "armed_no_epoch"
            rb != null -> rb.wire
            recorder.state().recording -> "already_recording"
            else -> boundary.current?.wire ?: "none"
        }
    }

    @Synchronized fun poll() {
        val now = clock()
        val b = blocker()
        if (b == lastBlocker && now - lastAt < heartbeatMs) return
        lastBlocker = b; lastAt = now
        emit(b in FAULTS, line(b))
    }

    private fun line(b: String): String {
        val s = recorder.state()
        return "trace state: blocker=$b recovered=${s.recovered.i()} recoveredMs=${s.recoveredMs} collect=${s.collect.i()} untilInMs=${s.untilInMs ?: "-"} diag=${s.diag.i()} " +
            "flagState=${flagState().wire} epoch=${if (s.epochSaved) "set" else "unset"} usedMB=${s.usedBytes shr 20} softMB=${s.capBytes shr 20} rec=${s.recording.i()} " +
            "traces=${s.traces} offers=${boundary.offers} starts=${boundary.starts} startRefused=${boundary.startRefused}"
    }

    /** The process-start line (see [boot]). */
    fun bootLine(restart: Int, killedTraces: Int): String = boot(restart, flagState(), recorder.epochSaved, killedTraces)

    private fun Boolean.i() = if (this) 1 else 0

    companion object {
        /** A blocker that is a fault (a warning), as opposed to a normal state: a wipe that keeps failing, or no room. */
        private val FAULTS = setOf(TraceStartBlocker.WIPE_FAILED.wire, TraceStartBlocker.DISK_CAP.wire)

        /** `trace boot:` at process start, after the recovery: how often the app restarted, the flag state, whether an epoch is saved, how many killed traces recovery found. */
        fun boot(restart: Int, flag: DiagFlagState, epochSaved: Boolean, killedTraces: Int): String =
            "trace boot: restart=$restart flagState=${flag.wire} epoch=${if (epochSaved) "set" else "unset"} killedTraces=$killedTraces"
    }
}
