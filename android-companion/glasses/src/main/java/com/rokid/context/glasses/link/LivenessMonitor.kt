package com.rokid.context.glasses.link

/**
 * Offline after [timeoutMs] without a phone message or on an explicit transport disconnect (ported from Live Fit
 * `sync/LivenessMonitor.kt`). The phone's heartbeat is 10 s, so 25 s tolerates two missed beats.
 */
class LivenessMonitor(private val clock: () -> Long, private val timeoutMs: Long = 25_000) {
    private var lastMessageMs: Long? = null
    private var disconnected = true

    @Synchronized fun onFrame() { lastMessageMs = clock(); disconnected = false }
    @Synchronized fun onTransportDisconnected() { disconnected = true }
    @Synchronized fun isOnline(): Boolean = !disconnected && lastMessageMs?.let { clock() - it < timeoutMs } == true
}
