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
    /** Wave 3 (counts only): force-stops with an unverifiable caller, and by a verified non-Rokid caller. */
    val unverifiedCloses: Int = 0,
    val otherCloses: Int = 0,
    /** Fix wave I: the glasses app is minimised without "display over other apps": capture runs but no icon shows. */
    val iconBlocked: Boolean = false,
    /** The watchdog's user-visible line (link down / app not running / stopped by you, with the last-heard age); null = none. */
    val watchdogNote: String? = null,
) {
    /**
     * Diagnostics line "Rokid closed the app N times (last gap Xs; M unverified; K other force-stops)"; null until the glasses
     * report a count. Rokid = a force-stop whose caller the glasses verified as a com.rokid process; unverified = the caller was
     * hidden or could not be checked; other = a verified non-Rokid caller (Settings, adb). A wearer exit or an update is never counted.
     */
    fun rokidClosesLine(): String? = com.rokid.context.core.model.closesSummary(rokidCloses, lastRestartGapSec, unverifiedCloses, otherCloses)

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
        note = watchdogNote,
    )
}

/** Shown while the glasses app runs minimised without the "display over other apps" permission (capture runs, no icon). */
const val ICON_BLOCKED_LINE = "Glasses icon hidden: allow 'display over other apps' for the app on the glasses"
