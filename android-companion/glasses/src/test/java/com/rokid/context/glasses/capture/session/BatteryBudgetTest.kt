package com.rokid.context.glasses.capture.session

import com.rokid.context.core.model.CameraWake
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BatteryBudgetTest {
    private var now = 0L
    private val budget = BatteryBudget { now }
    private val logs = mutableListOf<String>()

    init { budget.onChange = { logs += it } }

    private fun sample(pct: Double, charging: Boolean = false, target: Int = 4): BudgetTier =
        budget.onSample(Math.round(pct).toInt(), charging, target)

    /** One sample per minute, [pct] decreasing by [perMin] each minute, starting at [start]. */
    private fun run(minutes: Int, start: Double, perMin: Double, target: Int = 4): List<BudgetTier> {
        val out = mutableListOf<BudgetTier>()
        for (m in 0..minutes) {
            now = m * 60_000L
            out += sample(start - perMin * m, target = target)
        }
        return out
    }

    @Test fun offStaysT0() {
        val tiers = run(40, 100.0, 1.0, target = 0)
        assertTrue(tiers.all { it == BudgetTier.T0 })
    }

    @Test fun heavyDrainStepsDownEveryTenMinutesButNeverT3ByDrain() {
        val tiers = run(60, 100.0, 1.0)
        assertTrue((0..9).all { tiers[it] == BudgetTier.T0 })
        assertEquals(BudgetTier.T1, tiers[10])
        assertEquals(BudgetTier.T1, tiers[19])
        assertEquals(BudgetTier.T2, tiers[20])
        assertTrue(tiers.drop(20).all { it == BudgetTier.T2 })
        assertTrue(logs.first().startsWith("battery tier T0->T1 pct="))
        assertTrue(logs.first().contains("target_h=4"))
    }

    @Test fun lowDrainStepsBackUpAfterFifteenMinutes() {
        run(10, 100.0, 0.5) // 0.5 %/min is just over 1.15 x budget: T1 at minute 10, pct 95
        assertEquals(BudgetTier.T1, budget.tier.value)
        var tier = BudgetTier.T1
        var back = -1
        for (m in 11..40) {
            now = m * 60_000L
            tier = sample(95.0 - 0.2 * (m - 10))
            if (tier == BudgetTier.T0 && back < 0) back = m
        }
        assertEquals(25, back, "stepped up before 15 min in tier: $back")
        assertEquals(BudgetTier.T0, tier)
    }

    @Test fun levelFloors() {
        now = 0
        assertEquals(BudgetTier.T2, sample(25.0))
        now = 60_000
        assertEquals(BudgetTier.T3, sample(15.0))
    }

    @Test fun chargingResetsToT0AndClearsHistory() {
        run(12, 100.0, 1.0)
        assertNotNull(budget.drainPctPerMin)
        now = 13 * 60_000L
        assertEquals(BudgetTier.T0, sample(80.0, charging = true))
        assertNull(budget.drainPctPerMin)
    }

    @Test fun levelRiseClearsHistory() {
        run(12, 100.0, 1.0)
        assertNotNull(budget.drainPctPerMin)
        now = 13 * 60_000L
        sample(95.0)
        assertNull(budget.drainPctPerMin)
    }

    @Test fun unknownPctIgnored() {
        now = 0
        sample(80.0)
        assertEquals(BudgetTier.T0, budget.onSample(-1, false, 4))
        assertNull(budget.drainPctPerMin)
    }

    @Test fun projectedHours() {
        val b = BatteryBudget { now }
        now = 0; b.onSample(90, false, 4)
        now = 10 * 60_000L; b.onSample(85, false, 4)
        now = 20 * 60_000L; b.onSample(80, false, 4)
        assertEquals(0.5, b.drainPctPerMin!!, 1e-9)
        assertEquals(2.67, b.projectedHours!!, 0.005)
    }

    @Test fun profileTable() {
        assertEquals(CaptureProfile(CameraWake.MOTION, 5_000, 15_000, null, 333, false), CaptureProfile.of(CameraWake.MOTION, BudgetTier.T0))
        assertEquals(CaptureProfile(CameraWake.SETTLE, 10_000, 30_000, 10_000, 333, false), CaptureProfile.of(CameraWake.SETTLE, BudgetTier.T0))
        assertEquals(CaptureProfile(CameraWake.SETTLE, 10_000, 30_000, 10_000, 333, false), CaptureProfile.of(CameraWake.MOTION, BudgetTier.T1))
        assertEquals(CaptureProfile(CameraWake.SETTLE, 20_000, 60_000, 20_000, 666, false), CaptureProfile.of(CameraWake.SETTLE, BudgetTier.T2))
        assertEquals(CaptureProfile(CameraWake.SETTLE, 20_000, 60_000, 20_000, 666, true), CaptureProfile.of(CameraWake.MOTION, BudgetTier.T3))
        assertEquals(CaptureProfile.of(CameraWake.MOTION, BudgetTier.T0), CaptureProfile.DEFAULT)
    }
}
