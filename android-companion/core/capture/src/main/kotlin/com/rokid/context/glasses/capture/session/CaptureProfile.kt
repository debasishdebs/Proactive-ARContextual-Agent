package com.rokid.context.glasses.capture.session

import com.rokid.context.core.model.CameraWake

enum class BudgetTier { T0, T1, T2, T3 }

/** Capture cadences for a wake mode and budget tier (spec §6). Pure. */
data class CaptureProfile(
    val wake: CameraWake,
    val settledCheckMs: Long,
    val quietCheckMs: Long,
    val walkCheckMs: Long?,
    val analysisMs: Long,
    val paused: Boolean,
    /** A still scene backs the brief checks off (doubling) up to this gap; a changed scene, motion or a settle resets it. */
    val stillCapMs: Long = 60_000L,
) {
    /** The profile while resting (WorkRest on): glance captures only, timer checks at least 30 s apart, backing off to 2 min. */
    fun forRest(): CaptureProfile = copy(
        wake = CameraWake.SETTLE, settledCheckMs = maxOf(settledCheckMs, 30_000), quietCheckMs = maxOf(quietCheckMs, 60_000),
        walkCheckMs = maxOf(walkCheckMs ?: settledCheckMs, 30_000), stillCapMs = maxOf(stillCapMs, 120_000),
    )

    /**
     * Wave B: applies a context policy on top (never shortens a gap, never turns pause off). An all-zero policy (UNKNOWN,
     * WALKING) returns an equal profile. Context and intent alone never pause capture (the policy table has no pause row).
     */
    fun withContext(p: com.rokid.context.core.model.context.ContextCapturePolicy): CaptureProfile = copy(
        wake = if (p.forceSettle) CameraWake.SETTLE else wake,
        settledCheckMs = maxOf(settledCheckMs, p.minSettledCheckMs),
        quietCheckMs = maxOf(quietCheckMs, p.minQuietCheckMs),
        walkCheckMs = if (p.minWalkCheckMs > 0) maxOf(walkCheckMs ?: settledCheckMs, p.minWalkCheckMs) else walkCheckMs,
        stillCapMs = maxOf(stillCapMs, p.minStillCapMs),
        paused = paused || p.pause,
    )

    companion object {
        fun of(wake: CameraWake, tier: BudgetTier): CaptureProfile = when (tier) {
            BudgetTier.T0 -> when (wake) {
                CameraWake.MOTION -> CaptureProfile(CameraWake.MOTION, 5_000, 15_000, null, 333, false)
                CameraWake.SETTLE -> CaptureProfile(CameraWake.SETTLE, 10_000, 30_000, 10_000, 333, false)
            }
            BudgetTier.T1 -> CaptureProfile(CameraWake.SETTLE, 10_000, 30_000, 10_000, 333, false)
            BudgetTier.T2 -> CaptureProfile(CameraWake.SETTLE, 20_000, 60_000, 20_000, 666, false)
            BudgetTier.T3 -> CaptureProfile(CameraWake.SETTLE, 20_000, 60_000, 20_000, 666, true)
        }

        val DEFAULT: CaptureProfile = of(CameraWake.MOTION, BudgetTier.T0)
    }
}
