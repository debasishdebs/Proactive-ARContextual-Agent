package com.rokid.context.phone.hub.plan

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class DiagFlagState(val wire: String?) {
    UNKNOWN(null), UNPROVISIONED("unprovisioned"), ARMED_NO_EPOCH("armed_no_epoch"), ENABLED("enabled"), DISABLED("disabled");
    companion object { fun fromWire(w: String?): DiagFlagState = entries.firstOrNull { it.wire != null && it.wire == w } ?: UNKNOWN }
}

interface GlassesDiagStatusSource {
    val state: StateFlow<DiagFlagState>
    fun invalidate()
}

class MutableGlassesDiagStatus : GlassesDiagStatusSource {
    private val _state = MutableStateFlow(DiagFlagState.UNKNOWN)
    override val state: StateFlow<DiagFlagState> = _state
    /** LGC6-min: feed the glasses' reported `diagFlagState` wire name (`Hello.diagFlagState`); null or an unknown name = unknown. */
    fun update(wire: String?) { _state.value = DiagFlagState.fromWire(wire) }
    override fun invalidate() { _state.value = DiagFlagState.UNKNOWN }
}

object UnknownGlassesDiagStatus : GlassesDiagStatusSource {
    override val state: StateFlow<DiagFlagState> = MutableStateFlow(DiagFlagState.UNKNOWN)
    override fun invalidate() {}
}

/**
 * Re-sends the trace control (epoch, fences) while the glasses have not reported the diagnostic flag enabled (plan 3.4, LGC4P). One schedule at a time:
 * [restart] cancels the running one and starts a new one (5 s, doubling to 60 s, at most [MAX_ATTEMPTS] re-sends, then it gives up until the next restart).
 * It ends on `enabled` or `disabled`, when diagnostics are off ([diagWanted]), and on [cancel]. Counts only in the log; no I/O on the caller's thread.
 */
class DiagControlRetry(
    private val scope: CoroutineScope,
    private val status: GlassesDiagStatusSource,
    private val diagWanted: () -> Boolean,
    private val resend: suspend () -> Unit,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val log: (String) -> Unit = {},
) {
    private var job: Job? = null

    /** A new schedule (session ready, Developer mode on, reconnect). [newSession] drops the previous session's glasses status first: it must never end the new schedule. */
    @Synchronized fun restart(newSession: Boolean = false) {
        job?.cancel(); job = null
        if (newSession) status.invalidate()
        if (!diagWanted()) return
        job = scope.launch { run() }
    }

    @Synchronized fun cancel() { job?.cancel(); job = null }

    private suspend fun run() {
        var wait = MIN_DELAY_MS
        var sent = 0
        while (sent < MAX_ATTEMPTS) {
            if (!stillNeeded()) return
            sleep(wait)
            if (!stillNeeded()) return
            try { resend() } catch (e: CancellationException) { throw e } catch (e: Exception) { /* the next attempt or session sends again */ }
            sent++
            wait = (wait * 2).coerceAtMost(MAX_DELAY_MS)
        }
        log("diag control retry gave up after $sent re-sends")
    }

    private fun stillNeeded(): Boolean {
        if (!diagWanted()) return false
        return when (status.state.value) { DiagFlagState.ENABLED, DiagFlagState.DISABLED -> false; else -> true }
    }

    companion object { const val MIN_DELAY_MS = 5_000L; const val MAX_DELAY_MS = 60_000L; const val MAX_ATTEMPTS = 12 }
}
