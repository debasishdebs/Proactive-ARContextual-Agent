package com.rokid.context.link

/**
 * [GlassesInbound] scoped to one CXR session (Phase 3 carry): every session gets a fresh decoder and only the current
 * session's messages are decoded, so a late `ListenStart`/`pc_audio` from a closed session can never open or feed a
 * listen in the next one. Thread-safe: fed from CXR callback threads, opened and closed on the link's scope.
 */
class SessionInbound {
    private var current: Any? = null
    private var inbound = GlassesInbound()

    @Synchronized fun open(session: Any) { current = session; inbound = GlassesInbound() }

    /**
     * Opens [session], then runs [setup] (callback registration, connect) outside the lock. If [setup] throws, the
     * session is closed again before rethrowing, so a session whose setup failed can never emit events.
     */
    fun open(session: Any, setup: () -> Unit) {
        open(session)
        try {
            setup()
        } catch (e: Throwable) {
            close(session)
            throw e
        }
    }

    /** [session] closed or failed; a session that is no longer current changes nothing. */
    @Synchronized fun close(session: Any) {
        if (current !== session) return
        current = null
        inbound = GlassesInbound()
    }

    /** A BT pause: an utterance in flight can't complete, but the session stays current and resumes by itself. */
    @Synchronized fun interrupt(session: Any) { if (current === session) inbound.reset() }

    @Synchronized fun onMessage(session: Any, channel: String, parts: List<String>): List<GlassesEvent> =
        if (session !== current) emptyList() else inbound.onMessage(channel, parts)
}
