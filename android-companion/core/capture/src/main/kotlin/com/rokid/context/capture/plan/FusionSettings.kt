package com.rokid.context.capture.plan

import com.rokid.context.core.model.context.FusionParams
import java.security.MessageDigest

/**
 * The user-editable phone fusion thresholds (v0.9.7 B): the 11 keys of the plan's `phoneFusion` block ([PlanBounds.FUSION_DEFAULTS]). Defaults ARE today's constants
 * (a test pins every one); the safe ranges ARE the plan's own ([PlanBounds.fusionRange], default +-50 %, fastKmh 20..40, fractions capped at 1), so a setting can never leave what a plan may tune.
 * The activity states are not touched: only when the fusion changes its mind. Every value passes [clean]; only non-default keys are kept.
 */
object FusionSettings {
    val DEFAULTS: Map<String, Double> = PlanBounds.FUSION_DEFAULTS

    /** key to (default, min, max). */
    val RANGES: Map<String, Triple<Double, Double, Double>> = DEFAULTS.mapValues { (k, d) -> PlanBounds.fusionRange(k)!!.let { Triple(d, it.start, it.endInclusive) } }

    private fun round(x: Double) = Math.round(x * 1000.0) / 1000.0

    /** The canonical override map: valid, clamped, non-default entries; `stillFracWithImage` must stay at or below `stillFrac` (else both are dropped). */
    fun clean(raw: Map<String, Double>): Map<String, Double> {
        val m = LinkedHashMap<String, Double>()
        for ((k, r) in RANGES) {
            val v = raw[k]?.takeIf { it.isFinite() } ?: continue
            m[k] = round(v.coerceIn(r.second, r.third))
        }
        val still = m["stillFrac"] ?: DEFAULTS.getValue("stillFrac"); val withImage = m["stillFracWithImage"] ?: DEFAULTS.getValue("stillFracWithImage")
        if (withImage > still) { m.remove("stillFrac"); m.remove("stillFracWithImage") }
        return m.filter { (k, v) -> v != DEFAULTS.getValue(k) }
    }

    /** [base] with the cleaned [raw] values applied; empty overrides return [base] itself. */
    fun apply(base: FusionParams, raw: Map<String, Double>): FusionParams {
        val c = clean(raw)
        if (c.isEmpty()) return base
        fun l(k: String, d: Long) = c[k]?.let { Math.round(it) } ?: d
        fun f(k: String, d: Float) = c[k]?.toFloat() ?: d
        return base.copy(
            fastKmh = f("fastKmh", base.fastKmh), fastTtlMs = l("fastTtlMs", base.fastTtlMs), fastConfirmGapMs = l("fastConfirmGapMs", base.fastConfirmGapMs),
            vehicleHoldMs = l("vehicleHoldMs", base.vehicleHoldMs), enterVehicleMs = l("enterVehicleMs", base.enterVehicleMs), switchDriverMs = l("switchDriverMs", base.switchDriverMs),
            enterRestMs = l("enterRestMs", base.enterRestMs), enterUnknownMs = l("enterUnknownMs", base.enterUnknownMs), hintStillTtlMs = l("hintStillTtlMs", base.hintStillTtlMs),
            stillFrac = f("stillFrac", base.stillFrac), stillFracWithImage = f("stillFracWithImage", base.stillFracWithImage),
        )
    }

    /** `"default"` with no overrides, else 12 hex of SHA-256 over the canonical `key=value;` list. */
    fun hash(raw: Map<String, Double>): String {
        val c = clean(raw)
        if (c.isEmpty()) return "default"
        val text = c.entries.sortedBy { it.key }.joinToString(";") { "${it.key}=${it.value}" }
        return MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }.take(12)
    }

    /** Five presets across the safe range (min, between, default, between, max), plus a stored custom value so it stays selectable. */
    fun options(key: String, current: Double): List<Double> {
        val (d, lo, hi) = RANGES.getValue(key)
        return listOf(lo, round((lo + d) / 2), d, round((d + hi) / 2), hi, round(current)).distinct().sorted()
    }
}
