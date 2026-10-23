package com.rokid.context.glasses.capture.trace

import java.util.concurrent.atomic.AtomicInteger

/** Why the SESSION did not offer a clean boundary (LGC5, plan 3.6); [wire] is the stable name the state line carries. */
enum class BoundaryBlocker(val wire: String) {
    NOT_QUIET("no_boundary_not_quiet"), EPISODE("no_boundary_episode"), GATE_WINDOW("no_boundary_gate_window"),
    CAPTURE_BLOCKED("no_boundary_capture_blocked"), SESSION_INACTIVE("session_inactive"),
}

/**
 * What [com.rokid.context.glasses.capture.session.SamplingSession] says about the trace boundary (the session short-circuits BEFORE the recorder's `wantsStart` for several
 * conditions, so only it can say why no trace was offered). Counters and one volatile value: no lock, no I/O, never read back by the session. [current] starts as
 * [BoundaryBlocker.SESSION_INACTIVE] (no capture session has run in this process); the session sets it to null while it offers or records, and back to
 * [BoundaryBlocker.SESSION_INACTIVE] when it stops, so "no trace" with the app idle is explained.
 */
class TraceBoundaryReporter {
    @Volatile var current: BoundaryBlocker? = BoundaryBlocker.SESSION_INACTIVE
        private set
    private val offered = AtomicInteger(0)
    private val started = AtomicInteger(0)
    private val refused = AtomicInteger(0)
    /** Clean boundaries the session offered to the recorder, and how many it took or refused. */
    val offers: Int get() = offered.get()
    val starts: Int get() = started.get()
    val startRefused: Int get() = refused.get()

    fun report(b: BoundaryBlocker?) { current = b }

    fun offered(started: Boolean) { offered.incrementAndGet(); (if (started) this.started else refused).incrementAndGet() }
}
