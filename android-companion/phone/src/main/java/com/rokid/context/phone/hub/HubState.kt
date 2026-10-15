package com.rokid.context.phone.hub

import com.rokid.context.core.model.GlassesStatus
import com.rokid.context.core.model.LinkState
import com.rokid.context.core.model.carriesData
import com.rokid.context.core.model.StateFrame
import com.rokid.context.core.model.UserFacingError
import com.rokid.context.link.ClockOffset

/** What the hub knows about the glasses: one immutable snapshot. Instants are phone clock. */
data class HubState(
    val link: LinkState = LinkState.OFFLINE,
    /** The glasses app spoke another protocol version in this session. */
    val versionMismatch: Boolean = false,
    val glassesBatteryPct: Int? = null,
    val bufferedFrames: Int = 0,
    val lastFrameAtMs: Long? = null,
    val nextCaptureAtMs: Long? = null,
    val offset: ClockOffset? = null,
    val error: UserFacingError? = null,
    val lastFrame: StateFrame? = null,
    /** Fix wave H4 (counts only): how often Rokid force-stopped the glasses app, and the latest restart gap, from the hello. */
    val rokidCloses: Int = 0,
    /** The wearer exited the glasses app on purpose (`pc_user_exit`); cleared when it connects again. */
    val closedByUser: Boolean = false,
    val lastRestartGapSec: Int? = null,
    /** Fix wave I: the glasses app is minimised without "display over other apps": capture runs but no icon shows. */
    val iconBlocked: Boolean = false,
) {
    /**
     * Diagnostics line "Rokid closed the app N times (last gap Xs)"; null until the glasses report one. Honest about what it
     * counts: a process that the system ended with REASON_USER_REQUESTED, which Rokid's scene switch causes but a force-stop
     * from Settings or adb also does (Android cannot tell them apart); a wearer exit or an app update is never counted.
     */
    fun rokidClosesLine(): String? {
        if (rokidCloses <= 0) return null
        return "Rokid closed the app $rokidCloses time${if (rokidCloses == 1) "" else "s"} (last gap ${lastRestartGapSec ?: 0}s)"
    }

    /** Fix wave I: every Diagnostics > Glasses line (restarts, missing overlay permission), or null when there is nothing to say. */
    fun glassesDiagnostics(): String? = listOfNotNull(
        rokidClosesLine(),
        if (iconBlocked) ICON_BLOCKED_LINE else null,
    ).takeIf { it.isNotEmpty() }?.joinToString("\n")

    /** Phase 4 contract view. A mismatch matters only while a session is up. */
    fun toGlassesStatus(): GlassesStatus = GlassesStatus(
        link = if (versionMismatch && link.carriesData) LinkState.VERSION_MISMATCH else link,
        glassesBatteryPct = glassesBatteryPct,
        bufferedFrames = bufferedFrames,
        lastFrameAtMs = lastFrameAtMs,
        frame = lastFrame,
    )
}

/** Shown while the glasses app runs minimised without the "display over other apps" permission (capture runs, no icon). */
const val ICON_BLOCKED_LINE = "Glasses icon hidden: allow 'display over other apps' for the app on the glasses"
