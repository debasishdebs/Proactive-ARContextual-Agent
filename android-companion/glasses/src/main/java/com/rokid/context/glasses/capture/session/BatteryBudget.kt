package com.rokid.context.glasses.capture.session

import com.rokid.context.core.model.CameraWake
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

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

/**
 * Battery-life controller (spec §6): reacts to the measured drain against a target runtime.
 * Not thread-safe; call [onSample] from one place (the supervisor, every 60 s).
 */
class BatteryBudget(private val clock: () -> Long) {
    private data class Sample(val atMs: Long, val pct: Int)

    private val history = ArrayDeque<Sample>()
    private val _tier = MutableStateFlow(BudgetTier.T0)
    private var tierSinceMs: Long? = null

    val tier: StateFlow<BudgetTier> = _tier

    var onChange: (String) -> Unit = {}

    /** %/min over the history, or null while the span is under 10 min. */
    val drainPctPerMin: Double?
        get() {
            val first = history.firstOrNull() ?: return null
            val last = history.last()
            val spanMs = last.atMs - first.atMs
            if (spanMs < MIN_SPAN_MS) return null
            return (first.pct - last.pct) / (spanMs / 60_000.0)
        }

    val projectedHours: Double?
        get() {
            val drain = drainPctPerMin ?: return null
            val pct = history.lastOrNull()?.pct ?: return null
            if (drain <= 0.0) return null
            return pct / drain / 60.0
        }

    fun onSample(pct: Int, charging: Boolean, targetHours: Int): BudgetTier {
        if (pct < 0) return _tier.value
        val now = clock()
        val since = tierSinceMs ?: now.also { tierSinceMs = it }

        if (targetHours <= 0 || charging) {
            history.clear()
            return moveTo(BudgetTier.T0, now, pct, targetHours)
        }

        if (history.isNotEmpty() && pct > history.last().pct) history.clear()
        history.addLast(Sample(now, pct))
        while (history.isNotEmpty() && now - history.first().atMs > WINDOW_MS) history.removeFirst()

        val floor = when {
            pct <= FLOOR_T3_PCT -> BudgetTier.T3
            pct <= FLOOR_T2_PCT -> BudgetTier.T2
            else -> BudgetTier.T0
        }
        val current = _tier.value
        var next = current
        val drain = drainPctPerMin
        if (drain != null) {
            val budget = 100.0 / (targetHours * 60)
            val dwell = now - since
            if (drain > DOWN_FACTOR * budget && dwell >= DOWN_DWELL_MS && current < BudgetTier.T2) {
                next = BudgetTier.entries[current.ordinal + 1]
            } else if (drain < UP_FACTOR * budget && dwell >= UP_DWELL_MS && current > BudgetTier.T0) {
                next = BudgetTier.entries[current.ordinal - 1]
            }
        }
        if (floor > next) next = floor
        return moveTo(next, now, pct, targetHours)
    }

    private fun moveTo(next: BudgetTier, now: Long, pct: Int, targetHours: Int): BudgetTier {
        val prev = _tier.value
        if (next != prev) {
            _tier.value = next
            tierSinceMs = now
            val drain = drainPctPerMin
            val drainText = if (drain == null) "n/a" else "%.2f".format(java.util.Locale.US, drain)
            onChange("battery tier $prev->$next pct=$pct drain=$drainText target_h=$targetHours")
        }
        return next
    }

    private companion object {
        const val WINDOW_MS = 20 * 60_000L
        const val MIN_SPAN_MS = 10 * 60_000L
        const val DOWN_DWELL_MS = 10 * 60_000L
        const val UP_DWELL_MS = 15 * 60_000L
        const val DOWN_FACTOR = 1.15
        const val UP_FACTOR = 0.75
        const val FLOOR_T2_PCT = 25
        const val FLOOR_T3_PCT = 15
    }
}
