package com.rokid.context.orchestration.nudge

import com.rokid.context.core.model.QuietHours
import com.rokid.context.core.model.Verbosity
import java.time.ZoneId

/** The HUD settings the nudge gates need: quiet hours, verbosity (where a nudge shows) and the phone's zone. */
data class NudgeHud(val quietHours: QuietHours?, val verbosity: Verbosity, val zone: ZoneId)

/**
 * Set by the phone hub (PhoneNudges.hubPort). Null until the hub is built: the engine then skips its run, because the
 * quiet hours are unknown (fail-closed).
 */
object NudgeEnvironment {
    @Volatile var hud: (() -> NudgeHud)? = null
}
