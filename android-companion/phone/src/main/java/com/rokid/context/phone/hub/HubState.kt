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
) {
    /** Phase 4 contract view. A mismatch matters only while a session is up. */
    fun toGlassesStatus(): GlassesStatus = GlassesStatus(
        link = if (versionMismatch && link.carriesData) LinkState.VERSION_MISMATCH else link,
        glassesBatteryPct = glassesBatteryPct,
        bufferedFrames = bufferedFrames,
        lastFrameAtMs = lastFrameAtMs,
        frame = lastFrame,
    )
}
