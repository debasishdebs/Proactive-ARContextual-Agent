package com.rokid.context.phone.hub

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
 * one send that reads the latest state. Adapted from Live Fit `sync/StateBroadcaster.kt`.
 */
class StateBroadcaster(
    private val scope: CoroutineScope,
    private val minIntervalMs: Long = 250,
    private val heartbeatMs: Long = 10_000,
    private val onError: (Throwable) -> Unit = {},
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
                onError(e)
            }
            delay(minIntervalMs)
        }
    }
}
