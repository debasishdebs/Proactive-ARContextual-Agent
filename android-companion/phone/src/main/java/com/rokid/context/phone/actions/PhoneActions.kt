package com.rokid.context.phone.actions

import android.content.Context
import com.rokid.context.core.model.HudSettings
import com.rokid.context.phone.hub.HubActions

/** The phone side of actions (R-62). Placeholder (Stream 0): no overlay, commands ignored. Stream B fills it. */
object PhoneActions {
    @Suppress("UNUSED_PARAMETER")
    fun hubPort(app: Context, hud: () -> HudSettings): HubActions = HubActions.None
}
