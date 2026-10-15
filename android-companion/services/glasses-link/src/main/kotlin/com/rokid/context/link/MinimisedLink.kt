package com.rokid.context.link

import com.rokid.context.core.model.Channels
import com.rokid.context.core.model.HelloRequest
import com.rokid.context.core.model.Wire
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The SDK-free transport under [MinimisedLink]: in the app, the session-less CXRLink of [GlassAppWatcher]. It has no
 * way to start the glasses app (no appStart / appStop / sendExitAI), so the HUD is never brought forward.
 */
internal interface BackgroundTransport {
    /** A bind was requested and not stopped. */
    val active: Boolean
    /** Requests the Hi Rokid bind; false when it failed at once. */
    fun start(token: String): Boolean
    fun stop()
    /** null while unbound; otherwise Hi Rokid's code (0 = handed over, not delivered). */
    fun send(channel: String, json: String): Int?
}

/**
 * Minimised Option A (minimised-link-investigation.md): after GLASSES_EXIT the phone keeps a session-less CXRLink as a
 * two-way data link. On every bind it sends one `pc_hello_request`, so the glasses reopen their side, say hello and
 * flush their buffer. Inbound goes through [inbound] under a key of its own per start, so the CUSTOM_APP session
 * taking over (it opens its own key) drops any late background message: a frame is ingested once (ledger) and acked
 * on whichever path is current. The first glasses message after a start calls [onAlive]; none within [confirmMs]
 * (or a failed bind) calls [onSilent]: the app really exited.
 *
 * Fix wave I (C1): a kill later, while minimised, left the phone in MINIMISED for good. Once the glasses answered, a
 * liveness probe (`pc_hello_request`, at most one per [probeMs] + [confirmMs], counts only in the log) runs for as long
 * as the link is bound; any glasses message in the [confirmMs] after a probe counts as an answer, and [maxMisses]
 * unanswered probes in a row call [onSilent] (the app is gone: CLOSED_ON_GLASSES, so the guarded reopen can fire).
 * Used on the link's scope only.
 */
internal class MinimisedLink(
    private val scope: CoroutineScope,
    private val transport: BackgroundTransport,
    private val inbound: SessionInbound,
    private val onAlive: () -> Unit,
    private val onSilent: () -> Unit,
    private val log: (String) -> Unit,
    private val confirmMs: Long = CONFIRM_MS,
    private val probeMs: Long = PROBE_MS,
    private val maxMisses: Int = MAX_MISSES,
) {
    private var key: Any? = null
    private var bound = false
    private var heard = false
    private var confirmJob: Job? = null
    private var probeJob: Job? = null
    private var checkJob: Job? = null
    private var rx = 0
    private var tx = 0

    val active: Boolean get() = transport.active

    fun start(token: String): Boolean {
        if (transport.active) return true
        val k = Any()
        key = k; bound = false; heard = false; rx = 0; tx = 0
        inbound.open(k)
        if (!transport.start(token)) {
            inbound.close(k); key = null
            log("bg bind failed")
            onSilent()
            return false
        }
        confirmJob?.cancel()
        confirmJob = scope.launch { delay(confirmMs); if (!heard) { log("bg silent after ${confirmMs}ms"); onSilent() } }
        log("bg bind requested")
        return true
    }

    /**
     * One liveness check now (the wearer asked to open the app while minimised): send a probe and call [onSilent] when
     * nothing at all comes back within [confirmMs]; [onAlive] is not called here (the first message of a start does that).
     */
    fun probeNow() {
        if (key == null) { onSilent(); return }
        val mark = rx
        if (!sendProbe()) { log("bg probe now: not sent"); onSilent(); return }
        checkJob?.cancel()
        checkJob = scope.launch { delay(confirmMs); if (rx == mark) { log("bg probe now: silent"); onSilent() } }
    }

    private fun sendProbe(): Boolean {
        if (!bound) return false
        val code = transport.send(Channels.HELLO_REQUEST, Wire.encode(HelloRequest()))
        return code == 0
    }

    private fun startProbing() {
        probeJob?.cancel()
        probeJob = scope.launch {
            var misses = 0
            while (true) {
                delay(probeMs)
                val mark = rx
                val sent = sendProbe()
                delay(confirmMs)
                if (sent && rx != mark) { misses = 0; continue }
                misses++
                log("bg probe unanswered $misses/$maxMisses")
                if (misses >= maxMisses) { onSilent(); return@launch }
            }
        }
    }

    /** Hi Rokid service (dis)connected (ICXRLinkCbk.onCXRLConnected). */
    fun onBound(connected: Boolean) {
        if (key == null) return
        val was = bound
        bound = connected
        log("bg bound=$connected")
        if (connected && !was) {
            val code = transport.send(Channels.HELLO_REQUEST, Wire.encode(HelloRequest()))
            log("bg tx ${Channels.HELLO_REQUEST} result=$code")
        }
    }

    /** One decoded glasses message from the background link. */
    fun onMessage(channel: String, parts: List<String>): List<GlassesEvent> {
        val k = key ?: return emptyList()
        rx++
        if (!heard) { heard = true; confirmJob?.cancel(); confirmJob = null; onAlive() }
        if (probeJob?.isActive != true) startProbing() // also after a silence that a late message undid
        return inbound.onMessage(k, channel, parts)
    }

    fun send(channel: String, json: String): Boolean {
        if (key == null || !bound) return false
        val code = transport.send(channel, json)
        if (code != 0) { log("bg send $channel failed $code"); return false }
        tx++
        return true
    }

    fun stop() {
        confirmJob?.cancel(); confirmJob = null
        probeJob?.cancel(); probeJob = null
        checkJob?.cancel(); checkJob = null
        key?.let { inbound.close(it); log("bg stopped rx=$rx tx=$tx") }
        key = null; bound = false
        transport.stop()
    }

    companion object {
        /** The glasses answer a `pc_hello_request` within a second or two; silence this long means a real exit. */
        const val CONFIRM_MS = 10_000L
        /** Fix wave I: the quiet time before each liveness probe while minimised, and how many unanswered ones mean "gone". */
        const val PROBE_MS = 20_000L
        const val MAX_MISSES = 2
    }
}
