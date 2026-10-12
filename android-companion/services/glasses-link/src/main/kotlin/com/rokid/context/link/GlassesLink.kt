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
    /** Explicit user action (e.g. "Open on glasses"): connects even after a close on the glasses. */
    fun connect()
    /** Hub start: one attempt, no retry loop when the glasses are away. */
    fun connectOnce()
    /** Phone app resumed: reconnects only an OFFLINE, authorized link ([shouldReconnectOnResume]). */
    fun connectOnResume()
    /** Phone → glasses JSON on [channel]; false when no session is open or CXR refused it. */
    suspend fun send(channel: String, json: String): Boolean
    fun close()
}
