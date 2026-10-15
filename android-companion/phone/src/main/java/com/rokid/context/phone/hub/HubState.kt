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
) {
    /** Diagnostics line "Rokid closed the app N times (last gap Xs)"; null until the glasses report one. */
    fun rokidClosesLine(): String? {
        if (rokidCloses <= 0) return null
        return "Rokid closed the app $rokidCloses time${if (rokidCloses == 1) "" else "s"} (last gap ${lastRestartGapSec ?: 0}s)"
    }

    /** Phase 4 contract view. A mismatch matters only while a session is up. */
    fun toGlassesStatus(): GlassesStatus = GlassesStatus(
        link = if (versionMismatch && link.carriesData) LinkState.VERSION_MISMATCH else link,
        glassesBatteryPct = glassesBatteryPct,
        bufferedFrames = bufferedFrames,
        lastFrameAtMs = lastFrameAtMs,
        frame = lastFrame,
    )
}
