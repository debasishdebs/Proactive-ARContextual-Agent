package com.rokid.context.phone.hub

import com.rokid.context.core.log.PcLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Full-frame push on change, at most once per [minIntervalMs] (250 ms → ≤ 4 Hz, spec §1.6), plus a heartbeat every
 * [heartbeatMs] that keeps the glasses' liveness check and the `today` list fresh. Changes inside the window merge into
 * one send that reads the latest state. Adapted from Live Fit `sync/StateBroadcaster.kt`. A failing [send] or a
 * throwing [onError] never ends the loop (Phase 8 C5); only cancellation does.
 */
private const val TAG = "ContextHub"

class StateBroadcaster(
    private val scope: CoroutineScope,
    private val minIntervalMs: Long = 250,
    private val heartbeatMs: Long = 10_000,
    private val onError: (Throwable) -> Unit = {},
    private val log: (String) -> Unit = { PcLog.w(TAG, it) },
    private val send: suspend () -> Unit,
) {
    private val dirty = Channel<Unit>(Channel.CONFLATED)

    fun markDirty() { dirty.trySend(Unit) }

    fun start(): Job = scope.launch {
        while (isActive) {
            withTimeoutOrNull(heartbeatMs) { dirty.receive() }
            dirty.tryReceive() // a change that raced with the timeout is part of this send
            try {
                send()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A throwing handler must not end the loop (the HUD would freeze). An Error still crashes: the hub
                // FGS restarts. Cancellation still ends it.
                try {
                    onError(e)
                } catch (c: CancellationException) {
                    throw c
                } catch (h: Exception) {
                    log("state broadcast onError threw ${h.javaClass.simpleName}")
                }
            }
            delay(minIntervalMs)
        }
    }
}
