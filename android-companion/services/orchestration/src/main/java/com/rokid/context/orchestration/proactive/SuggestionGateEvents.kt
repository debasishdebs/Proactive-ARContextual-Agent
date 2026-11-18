package com.rokid.context.orchestration.proactive

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * v3.1 r2 P1-2: "a gate of the suggestions just closed" (a zone is approached or held, a pause was added, driving started or became unknown). Whoever shows previously delivered text (the Home feed) observes [closes]
 * and re-derives at once instead of waiting for its backstop tick. A counter, no content; the writers are the zone controller, the pause set and the driving tracker. Safe to call from any thread.
 *
 * v3.1 r3 P1-2: [drivingClosed] and [quietBoundary] ALSO tell every registered withdraw listener (production: [SuggestionWithdrawal], which clears the HUD card and the Nexus notice) even when Home is not collecting. The
 * listeners run on one background worker, never on the caller's thread: the callers hold the driving monitor's neighbours or a zone fence, and a listener takes the surfaces' own locks (lock order: zone fences -> driving monitor -> store lock; listeners hold none of them).
 */
object SuggestionGateEvents {
    private val v = MutableStateFlow(0L)
    val closes: StateFlow<Long> = v.asStateFlow()
    fun closed() { v.value = v.value + 1 }

    private val withdrawListeners = CopyOnWriteArrayList<() -> Unit>()
    fun addWithdrawListener(l: () -> Unit) { if (l !in withdrawListeners) withdrawListeners.add(l) }
    fun removeWithdrawListener(l: () -> Unit) { withdrawListeners.remove(l) }

    /** Runs a listener batch. Production: one daemon worker that dies when idle; tests replace it with a direct call. */
    @Volatile var dispatch: (() -> Unit) -> Unit = { job -> worker.execute(job) }
    private val worker: ThreadPoolExecutor by lazy {
        ThreadPoolExecutor(0, 1, 5, TimeUnit.SECONDS, LinkedBlockingQueue()) { r -> Thread(r, "pc-suggestion-withdraw").apply { isDaemon = true } }
    }

    /** Driving became DRIVING or UNKNOWN: the feed re-derives and every displayed suggestion is withdrawn. */
    fun drivingClosed() { closed(); withdrawSurfaces() }
    /** The quiet-hours window just started. */
    fun quietBoundary() { closed(); withdrawSurfaces() }

    private fun withdrawSurfaces() {
        if (withdrawListeners.isEmpty()) return
        try { dispatch { for (l in withdrawListeners) try { l() } catch (_: Exception) { } } } catch (_: Exception) { }
    }
}
