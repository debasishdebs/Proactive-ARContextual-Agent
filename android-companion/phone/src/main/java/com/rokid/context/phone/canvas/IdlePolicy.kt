package com.rokid.context.phone.canvas

/** R8: the canvas service stops after 30 min with no authenticated request, unless a run is active or a canvas screen is open. */
object IdlePolicy {
    const val IDLE_MS = 30 * 60_000L

    fun shouldStop(lastActivityMs: Long, nowMs: Long, runActive: Boolean, screenOpen: Boolean): Boolean =
        !runActive && !screenOpen && nowMs - lastActivityMs >= IDLE_MS
}
