package com.rokid.context.phone.hub.plan

import com.rokid.context.canvas.trace.CollectSession
import com.rokid.context.core.model.Channels
import com.rokid.context.core.model.TraceControl
import com.rokid.context.core.model.Wire
import com.rokid.context.link.GlassesLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Sends [CollectSession]'s `traceControl` flow to the glasses on `pc_trace_ctl`: every change of the session (start, stop, expiry refresh) and, in a new glasses
 * session, a fresh control built from the persisted epoch. Hub-owned, so it does not depend on the canvas server running.
 */
class TraceControlPublisher(private val link: GlassesLink, private val collect: CollectSession, private val scope: CoroutineScope) {
    val traceControl: StateFlow<TraceControl> get() = collect.flow
    private var job: Job? = null

    @Synchronized fun start() {
        if (job != null) return
        job = scope.launch { collect.flow.drop(1).distinctUntilChanged().collect { send(it) } }
    }

    @Synchronized fun stop() { job?.cancel(); job = null }

    /** A glasses session opened: the session may have expired, and the epoch is read again (never cached). */
    suspend fun onSessionReady() { send(collect.refresh()) }

    /** LGC4P: the current control again (epoch read fresh) while the glasses have not enabled diagnostics; same message the session-ready send builds. */
    suspend fun resend() { send(collect.refresh()) }

    private suspend fun send(c: TraceControl) {
        try { link.send(Channels.TRACE_CTL, Wire.encode(c)) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { /* sent again at the next session */ }
    }
}
