package com.rokid.context.capture.plan

import com.rokid.context.core.model.context.FusionParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.9.7 B item 2: the user-editable phone fusion thresholds (the plan's 11 `phoneFusion` keys), safe ranges, defaults equal to the shipped constants. */
class FusionSettingsTest {
    private val base = FusionParams(watchTtlMs = 1_500_000)

    @Test fun defaultsAreTheShippedConstants() {
        // the literal numbers the hub ran with before slice B, one per key
        assertEquals(
            mapOf(
                "fastKmh" to 25.0, "fastTtlMs" to 120_000.0, "fastConfirmGapMs" to 45_000.0, "vehicleHoldMs" to 180_000.0, "enterVehicleMs" to 30_000.0,
                "switchDriverMs" to 60_000.0, "enterRestMs" to 120_000.0, "enterUnknownMs" to 60_000.0, "hintStillTtlMs" to 120_000.0, "stillFrac" to 0.85, "stillFracWithImage" to 0.7,
            ),
            FusionSettings.DEFAULTS,
        )
        assertEquals(FusionSettings.DEFAULTS.keys, FusionSettings.RANGES.keys)
        for ((k, r) in FusionSettings.RANGES) assertEquals(k, FusionSettings.DEFAULTS.getValue(k), r.first, 0.0)
    }

    @Test fun noOverridesLeavesTheHubParamsUntouched() {
        assertEquals(base, FusionSettings.apply(base, emptyMap()))
        assertEquals("default", FusionSettings.hash(emptyMap()))
        assertEquals(base, FusionSettings.apply(base, FusionSettings.DEFAULTS))
    }

    @Test fun anOverrideReachesTheParamsAndOnlyThatField() {
        val p = FusionSettings.apply(base, mapOf("fastKmh" to 30.0, "enterVehicleMs" to 45_000.0, "stillFrac" to 0.9))
        assertEquals(base.copy(fastKmh = 30f, enterVehicleMs = 45_000, stillFrac = 0.9f), p)
        assertEquals("a field not in the 11 keys keeps the hub's own value", 1_500_000L, p.watchTtlMs)
    }

    @Test fun valuesAreClampedIntoTheSafeRangeAndBadOnesDropped() {
        val c = FusionSettings.clean(mapOf("fastKmh" to 500.0, "vehicleHoldMs" to 1.0, "stillFrac" to 7.0, "enterRestMs" to Double.NaN, "bogus" to 1.0))
        assertEquals(40.0, c["fastKmh"]!!, 0.0)
        assertEquals(90_000.0, c["vehicleHoldMs"]!!, 0.0)
        assertEquals(1.0, c["stillFrac"]!!, 0.0)
        assertTrue("NaN and unknown keys are dropped", "enterRestMs" !in c && "bogus" !in c)
        // the safe range is exactly the range a plan may tune
        for (k in FusionSettings.DEFAULTS.keys) assertEquals(k, PlanBounds.fusionRange(k)!!.start, FusionSettings.RANGES.getValue(k).second, 1e-9)
        for (k in FusionSettings.DEFAULTS.keys) assertEquals(k, PlanBounds.fusionRange(k)!!.endInclusive, FusionSettings.RANGES.getValue(k).third, 1e-9)
    }

    @Test fun stillWithImageNeverExceedsStill() {
        val c = FusionSettings.clean(mapOf("stillFrac" to 0.5, "stillFracWithImage" to 0.9))
        assertTrue("an inverted pair is dropped, the defaults stay", "stillFrac" !in c && "stillFracWithImage" !in c)
        assertEquals(mapOf("stillFrac" to 0.9), FusionSettings.clean(mapOf("stillFrac" to 0.9, "stillFracWithImage" to 0.7)))
    }

    @Test fun theHashChangesWithAValueAndIsOrderFree() {
        val a = FusionSettings.hash(mapOf("fastKmh" to 30.0, "stillFrac" to 0.9)); val b = FusionSettings.hash(mapOf("stillFrac" to 0.9, "fastKmh" to 30.0))
        assertEquals(a, b); assertNotEquals("default", a); assertNotEquals(a, FusionSettings.hash(mapOf("fastKmh" to 31.0, "stillFrac" to 0.9)))
    }

    @Test fun presetsAreFiveAcrossTheRangePlusTheCustomValue() {
        val o = FusionSettings.options("fastKmh", 25.0)
        assertEquals(listOf(20.0, 22.5, 25.0, 32.5, 40.0), o)
        assertTrue(33.0 in FusionSettings.options("fastKmh", 33.0))
    }
}
