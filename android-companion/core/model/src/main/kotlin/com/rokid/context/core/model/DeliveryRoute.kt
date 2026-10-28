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

        /** [callerOnGlasses]: the ask was made on the glasses (glasses voice); [reach]: the glasses surface as seen from the asker. An unknown [target] is treated as `caller`. */
        fun resolve(target: String, callerOnGlasses: Boolean, reach: GlassesReach): DeliveryRoute {
            fun phoneInstead() = DeliveryRoute(phone = true, glasses = false, fallback = if (reach == GlassesReach.NO_PATH) NO_GLASSES_PATH else GLASSES_NOT_CONNECTED)
            return when (target) {
                "phone" -> DeliveryRoute(phone = true, glasses = false)
                "glasses" -> if (reach == GlassesReach.UP) DeliveryRoute(phone = false, glasses = true) else phoneInstead()
                "both" -> if (reach == GlassesReach.UP) DeliveryRoute(phone = true, glasses = true) else phoneInstead()
                else -> if (callerOnGlasses && reach == GlassesReach.UP) DeliveryRoute(phone = false, glasses = true, explicit = false) else DeliveryRoute(phone = true, glasses = false, explicit = false)
            }
        }
    }
}
