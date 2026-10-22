package com.rokid.context.capture.plan

import com.rokid.context.core.model.context.FusionParams
import java.math.BigDecimal

/** Plan value bounds (section 8 node catalogue, S83). Fixed in Kotlin. */
object PlanBounds {
    val ONSET = 0.4f..1.0f
    val BRIDGE_MS = 100L..500L
    val MIN_BOUT_MS = 100L..800L
    val MIN_BOUT_RAD = 0.1f..0.8f
    val SETTLE_MS = 1_000L..3_000L
    val NOVELTY_WINDOW_MS = 5_000L..60_000L
    val NOVELTY_MIN_RAD = 0.1f..0.8f
    val BIG_TURN_RAD = 0.5f..1.5f
    const val RELEASE_LO = 0.2f
    val BIG_CHANGE = 16..32
    val HAMMING = 6..16
    val CAP_PER_MIN = 1..4
    const val GAP_MIN_MS = 5_000L
    const val GAP_MAX_MS = 300_000L
    val FAST_KMH = 20.0..40.0
    const val FUSION_FRACTION = 0.5

    /** The tunable phone fusion keys with today's defaults (walking veto, flight and image rules are not tunable, S32). */
    val FUSION_DEFAULTS: Map<String, Double> = FusionParams().let { f ->
        mapOf(
            "fastKmh" to f.fastKmh.dbl(), "fastTtlMs" to f.fastTtlMs.dbl(), "fastConfirmGapMs" to f.fastConfirmGapMs.dbl(),
            "vehicleHoldMs" to f.vehicleHoldMs.dbl(), "enterVehicleMs" to f.enterVehicleMs.dbl(),
            "switchDriverMs" to f.switchDriverMs.dbl(), "enterRestMs" to f.enterRestMs.dbl(),
            "enterUnknownMs" to f.enterUnknownMs.dbl(), "hintStillTtlMs" to f.hintStillTtlMs.dbl(),
            "stillFrac" to f.stillFrac.dbl(), "stillFracWithImage" to f.stillFracWithImage.dbl(),
        )
    }

    fun fusionRange(key: String): ClosedFloatingPointRange<Double>? {
        val d = FUSION_DEFAULTS[key] ?: return null
        return if (key == "fastKmh") FAST_KMH else (d * (1 - FUSION_FRACTION))..(d * (1 + FUSION_FRACTION))
    }

    fun fmt(n: Number): String {
        val bd = BigDecimal(n.toString())
        return if (bd.signum() == 0) "0" else bd.stripTrailingZeros().toPlainString()
    }

    private fun Number.dbl(): Double = toString().toDouble()
}
