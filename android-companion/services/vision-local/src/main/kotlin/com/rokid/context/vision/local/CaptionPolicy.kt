package com.rokid.context.vision.local

import com.rokid.context.core.model.vision.CaptionRefusal

/** What the phone reports right now. [thermal] is `PowerManager.currentThermalStatus` (0 = none .. 6 = shutdown). */
data class DeviceState(val thermal: Int, val batterySaver: Boolean, val batteryPct: Int, val charging: Boolean)

/** When the phone is too hot or low on power to run the model. Pure. */
object CaptionPolicy {
    /** The reason to refuse, or null when running is fine. Thermal first (safety), then battery saver, then a low battery that is not charging. */
    fun refusal(s: DeviceState): CaptionRefusal? = when {
        s.thermal >= CaptionConfig.THERMAL_SEVERE -> CaptionRefusal.THERMAL
        s.batterySaver -> CaptionRefusal.BATTERY_SAVER
        s.batteryPct < CaptionConfig.MIN_BATTERY_PCT && !s.charging -> CaptionRefusal.BATTERY_LOW
        else -> null
    }
}
