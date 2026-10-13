package com.rokid.context.core.hud.render

/** Short HUD texts about the phone link (spec §3.6) and why Ask can't start (spec §5.1.1). Never error text. */
object HudCopy {
    const val CONNECTING = "Connecting to phone\u2026"
    const val OFFLINE = "Phone not connected"
    const val OUTDATED = "Update Proactive Context"
    private const val MAX_REASON = 60

    /** The Status page "Phone" value: "connecting" during the start-up window, never "offline" (Phase 3 carry). */
    fun phoneLink(c: HudConnection): String = when (c) {
        HudConnection.LIVE -> "linked"
        HudConnection.CONNECTING -> "connecting"
        HudConnection.OFFLINE -> "offline"
        HudConnection.OUTDATED -> "update needed"
    }

    /** The toast for tap = Ask, or null when Ask can start: the link first, then the phone's own reason. */
    fun askUnavailable(c: HudConnection, askBlockedReason: String?): String? = when (c) {
        HudConnection.OUTDATED -> OUTDATED
        HudConnection.CONNECTING -> CONNECTING
        HudConnection.OFFLINE -> OFFLINE
        HudConnection.LIVE -> askBlockedReason?.takeIf { it.isNotBlank() }?.take(MAX_REASON)
    }
}
