package com.rokid.context.core.model

/** Whether a glasses surface can be reached right now: the link is up, down, or this caller has no path to the glasses at all (the Home chat). */
enum class GlassesReach { UP, DOWN, NO_PATH }

/**
 * v0.9.8 slice C: where one answer or nudge goes, resolved from a flow's `out.deliver` target (`caller`, `phone`, `glasses`, `both`). Each surface is
 * either in or out, so one answer is never delivered twice to the same surface. A glasses target the glasses cannot take falls back to the phone and
 * says why in [fallback] (a fixed token, recorded by the caller: a fallback is never silent). [explicit] is false for `caller`: the surface that asked keeps
 * today's behaviour untouched.
 */
data class DeliveryRoute(val phone: Boolean, val glasses: Boolean, val fallback: String? = null, val explicit: Boolean = true) {
    companion object {
        const val GLASSES_NOT_CONNECTED = "glasses_not_connected"
        const val NO_GLASSES_PATH = "no_glasses_path"

        /**
         * [callerOnGlasses]: the ask was made on the glasses (glasses voice); [reach]: the glasses surface as seen from the asker. An unknown [target] is treated as `caller`.
         * [caps] (v0.9.8 slice D): a switched-off display is not a surface; the route falls back and says why (the answer row is always stored, so a route never ends with nothing).
         */
        fun resolve(target: String, callerOnGlasses: Boolean, reach: GlassesReach, caps: DeviceCapabilities = DeviceCapabilities.ALL): DeliveryRoute {
            val pd = caps.phoneDisplay; val gd = caps.glassesDisplay
            fun phoneInstead() = DeliveryRoute(phone = true, glasses = false, fallback = if (reach == GlassesReach.NO_PATH) NO_GLASSES_PATH else GLASSES_NOT_CONNECTED)
            fun phoneOnly(why: String) = DeliveryRoute(phone = true, glasses = false, fallback = why)
            return when (target) {
                "phone" -> when {
                    pd -> DeliveryRoute(phone = true, glasses = false)
                    gd && reach == GlassesReach.UP -> DeliveryRoute(phone = false, glasses = true, fallback = DeviceCapabilities.PHONE_DISPLAY_OFF)
                    else -> phoneOnly(if (gd) DeviceCapabilities.PHONE_DISPLAY_OFF else DeviceCapabilities.NO_DISPLAY)
                }
                "glasses" -> when {
                    !gd -> phoneOnly(if (pd) DeviceCapabilities.GLASSES_DISPLAY_OFF else DeviceCapabilities.NO_DISPLAY)
                    reach == GlassesReach.UP -> DeliveryRoute(phone = false, glasses = true)
                    else -> phoneInstead()
                }
                "both" -> when {
                    !gd -> phoneOnly(if (pd) DeviceCapabilities.GLASSES_DISPLAY_OFF else DeviceCapabilities.NO_DISPLAY)
                    reach != GlassesReach.UP -> phoneInstead()
                    pd -> DeliveryRoute(phone = true, glasses = true)
                    else -> DeliveryRoute(phone = false, glasses = true, fallback = DeviceCapabilities.PHONE_DISPLAY_OFF)
                }
                else -> when {
                    !pd && !gd -> phoneOnly(DeviceCapabilities.NO_DISPLAY)
                    callerOnGlasses && !gd -> phoneOnly(DeviceCapabilities.GLASSES_DISPLAY_OFF) // whatever the link does meanwhile
                    callerOnGlasses && reach == GlassesReach.UP -> DeliveryRoute(phone = false, glasses = true, explicit = false)
                    else -> DeliveryRoute(phone = true, glasses = false, explicit = false)
                }
            }
        }
    }
}
