package com.rokid.context.link

import com.rokid.context.core.model.LinkState
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** Phone side of the glasses link (spec §1.7). [CxrGlassesLink] in the app; [FakeGlassesLink] in tests. */
interface GlassesLink {
    /** Never [LinkState.VERSION_MISMATCH]: the hub derives that from [GlassesEvent.Outdated]. */
    val state: StateFlow<LinkState>
    val events: SharedFlow<GlassesEvent>
    /** A session opened or resumed: push `pc_settings` then `pc_state` now. */
    val ready: SharedFlow<Unit>
    /** Fix wave H2: the glasses' last CXR `wearingStatus` (true = worn) seen on the background link; null when none yet. */
    val wearing: StateFlow<Boolean?> get() = NO_WEARING
    /**
     * v1.0 wear fallback: the glasses' system wear check ("Wear detection" in Hi Rokid) as last read on the background link (true = on); null = never read.
     * Remembered across hub restarts (a device setting, it changes rarely); a re-read on every background bind refreshes it.
     */
    val wearCheck: StateFlow<Boolean?> get() = NO_WEARING
    /** Explicit user action (e.g. "Open on glasses"): connects even after a close on the glasses. */
    fun connect()
    /**
     * Fix wave I4: the phone's own reopen after Rokid closed the app (GlassesRelauncher). Like [connect], but a failure is not
     * retried by the link: the relauncher owns the retries (guards, backoff, hourly cap).
     */
    fun connectAutomatic() = connect()
    /**
     * Fix wave I1: while MINIMISED the wearer asked to open the app: check that it is alive (a liveness probe). Silence moves
     * the link to CLOSED_ON_GLASSES and then, when [openIfGone] (a wearer's tap), opens it (an explicit connect); an automatic caller passes false and leaves the reopen to the relauncher's guards. An answer leaves everything as it was.
     */
    fun probeAlive(openIfGone: Boolean = true) = Unit
    /** Fix wave I N4: the wearer exited the app earlier: do not connect, only watch for the wearer opening it again. */
    fun watchForReopen() = Unit
    /** Review hub-wp2 r3 P1-2/P2-5: whether [reconnectAutomatic] would do anything now (read BEFORE an attempt is reserved against the hourly quota). */
    fun reconnectEligible(): Boolean = true
    /** Review hub-wp2 r3 P1-2: the watchdog's OFFLINE reconnect as the phone's own automatic open: like [connectOnResume]'s checks but, as [connectAutomatic], a failure is not retried here (no 2/5/10/30 s loop around the guards). */
    fun reconnectAutomatic() = connectAutomatic()
    /** Hub start: one attempt, no retry loop when the glasses are away. */
    fun connectOnce()
    /** Phone app resumed: reconnects only an OFFLINE, authorized link ([shouldReconnectOnResume]). */
    fun connectOnResume()
    /** Phone → glasses JSON on [channel]; false when no session is open or CXR refused it. */
    suspend fun send(channel: String, json: String): Boolean
    fun close()
}

private val NO_WEARING: StateFlow<Boolean?> = kotlinx.coroutines.flow.MutableStateFlow(null)
