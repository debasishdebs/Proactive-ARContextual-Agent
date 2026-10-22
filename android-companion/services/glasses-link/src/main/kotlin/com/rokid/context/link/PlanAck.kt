package com.rokid.context.link

import com.rokid.context.core.model.CapturePlanAck
import java.util.concurrent.atomic.AtomicInteger

enum class AckVerdict {
    /** The unsolicited caps announcement of a session (gen 0, no install, no session): capabilities only, nothing to match. */
    CAPS,
    /** Echoes this install and this session: it may answer an outstanding gen (the caller matches the gen). */
    MATCH,
    /** Another install, another session or a pre-session stamp: counted and ignored. */
    STALE,
}

/**
 * Slice 3a ack correlation (S24, S74). A `pc_capture_plan_ack` is passed through only when its `phoneInstall` is this
 * install and its `sessionStartMs` is the current glasses session; anything else is [AckVerdict.STALE] and counted in
 * [ackStale] (a count only: no ack content is ever logged).
 */
class PlanAckGate(private val install: () -> String, private val sessionStart: () -> Long) {
    private val stale = AtomicInteger()
    val ackStale: Int get() = stale.get()

    fun classify(ack: CapturePlanAck): AckVerdict {
        if (ack.gen == 0L && ack.phoneInstall.isEmpty() && ack.sessionStartMs == 0L && ack.reason == null) return AckVerdict.CAPS
        if (ack.phoneInstall == install() && ack.sessionStartMs == sessionStart()) return AckVerdict.MATCH
        stale.incrementAndGet()
        return AckVerdict.STALE
    }
}

enum class PlanPeer { UNKNOWN, CAPABLE, TOO_OLD }

/**
 * Whether the glasses app understands capture plans. A new glasses build sends a caps ack on every session open; an old
 * one never does. TOO_OLD = a hello was seen, [graceMs] passed and no caps ack arrived (the UI says "Glasses app too old
 * for canvas plans"). Resets with each session. Not thread-safe by itself: call from the hub's scope.
 */
class PlanPeerTracker(private val graceMs: Long = 10_000L) {
    private var helloAtMs: Long? = null
    private var caps = false

    fun onSessionOpen(@Suppress("UNUSED_PARAMETER") nowMs: Long) { helloAtMs = null; caps = false }
    fun onHello(nowMs: Long) { if (helloAtMs == null) helloAtMs = nowMs }
    fun onCapsAck() { caps = true }

    fun state(nowMs: Long): PlanPeer = when {
        caps -> PlanPeer.CAPABLE
        helloAtMs?.let { nowMs - it >= graceMs } == true -> PlanPeer.TOO_OLD
        else -> PlanPeer.UNKNOWN
    }
}
