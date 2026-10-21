package com.rokid.context.glasses.capture.session

import com.rokid.context.core.model.MotionState
import com.rokid.context.core.model.context.ContextState
import com.rokid.context.core.model.context.MotionContextMessage

/**
 * Glasses side of the motion hub (spec 2026-10-21 §9.1). Holds the last `pc_motion_ctx` with its receipt time (glasses
 * `elapsedRealtime`; the TTL runs from receipt) and says which [ContextState] to apply to capture right now. Pure.
 *
 * The local walking veto is unconditional: within [VETO_WINDOW_MS] of locally seen walking, a REST / VEHICLE_* / FLIGHT
 * message is not applied, whatever its reasons. A manual capture and the A3 rules are never touched here.
 */
class ContextGate {
    private var msg: MotionContextMessage? = null
    private var receivedAtMs = 0L
    private var vetoes = 0
    private var vetoing = false

    @Synchronized fun onMessage(m: MotionContextMessage?, receivedAtMs: Long) {
        msg = m
        this.receivedAtMs = receivedAtMs
        if (m == null) vetoing = false
    }

    /** The state to apply now (UNKNOWN = the A3 rules alone). A veto is counted once per veto spell, not per call. */
    @Synchronized fun effective(nowMs: Long, localState: MotionState, lastLocalWalkAtMs: Long?, sourcesOff: List<String>): ContextState {
        val m = msg
        if (m == null || nowMs - receivedAtMs > m.validForMs || !m.apply || "phone_motion" in sourcesOff) { vetoing = false; return ContextState.UNKNOWN }
        val reduces = m.state == ContextState.REST || m.state == ContextState.VEHICLE_PASSENGER ||
            m.state == ContextState.VEHICLE_DRIVING || m.state == ContextState.FLIGHT
        val walking = localState == MotionState.WALKING || (lastLocalWalkAtMs != null && nowMs - lastLocalWalkAtMs in 0 until VETO_WINDOW_MS)
        if (reduces && walking) {
            if (!vetoing) { vetoing = true; vetoes++ }
            return ContextState.UNKNOWN
        }
        vetoing = false
        return m.state
    }

    /** The last message's state whatever its age or apply flag; null before any message. */
    @Synchronized fun raw(): ContextState? = msg?.state

    @Synchronized fun ageMs(nowMs: Long): Long? = msg?.let { (nowMs - receivedAtMs).coerceAtLeast(0) }

    /** Vetoes since the last call (for the heartbeat). */
    @Synchronized fun takeVetoes(): Int { val v = vetoes; vetoes = 0; return v }

    companion object { const val VETO_WINDOW_MS = 15_000L }
}
