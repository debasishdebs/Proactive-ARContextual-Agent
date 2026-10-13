package com.rokid.context.phone.nudge

import android.content.Context
import com.rokid.context.core.model.HudSettings
import com.rokid.context.phone.hub.HubNudges

/** The phone side of nudges (R-61). Placeholder (Stream 0): no nudge line or overlay. Stream C fills it. */
object PhoneNudges {
    @Suppress("UNUSED_PARAMETER")
    fun hubPort(app: Context, hud: () -> HudSettings): HubNudges = HubNudges.None
}
