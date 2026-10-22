package com.rokid.context.glasses.capture.motion

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WearDetectorTest {
    private val w = WearDetector()

    @Test fun unknownCountsAsWorn() { assertTrue(w.worn.value); assertFalse(w.known) }

    @Test fun onlyARealPcWearFalseMakesItNotWorn() {
        w.onRemote(false); assertFalse(w.worn.value); assertTrue(w.known)
        w.onRemote(true); assertTrue(w.worn.value)
    }

    @Test fun resetGoesBackToUnknownWorn() {
        w.onRemote(false); w.reset()
        assertTrue(w.worn.value); assertFalse(w.known)
    }

    @Test fun proximityNeverChangesTheState() {
        w.onProximity(false, 0); w.tick()
        assertTrue(w.worn.value); assertEquals(false, w.lastProximityNear)
        w.onRemote(false); w.onProximity(true, 0)
        assertFalse(w.worn.value)
    }

    @Test fun diagnosticLineShowsRawAndMaxAndNeverContent() {
        assertEquals("proximity raw=5.0 max=5.0 worn=false (diagnostic only)", proximityDiagLine(5f, 5f, present = true, worn = false))
        assertEquals("proximity raw=none max=5.0 worn=true (diagnostic only)", proximityDiagLine(Float.NaN, 5f, present = true, worn = true))
        assertEquals("proximity absent", proximityDiagLine(0f, 0f, present = false, worn = true))
    }

    private fun still(n: Int, from: Long = 0L) { for (i in 0 until n) w.onSecond(0f, 0f, from + i * 1000L) }

    @Test fun resetStillRunDropsACarriedOverRunAndTheParkedFlag() {
        still(130); assertTrue(w.parked.value)
        w.resetStillRun(); assertFalse(w.parked.value)
        w.onSecond(0f, 0f, 200_000L); assertFalse(w.parked.value) // a fresh run starts here, not at 0
        still(118, 201_000L); assertFalse(w.parked.value)
        w.onSecond(0f, 0f, 319_000L); assertTrue(w.parked.value)
    }

    @Test fun parksAfter120StillSecondsWhenUnknown() {
        still(119); assertFalse(w.parked.value)
        w.onSecond(0f, 0f, 119_000L); assertTrue(w.parked.value)
    }

    @Test fun knownWornParksAt600sNotAt120s() {
        w.onRemote(true)
        still(120); assertFalse(w.parked.value)
        still(479, 120_000L); assertFalse(w.parked.value)
        w.onSecond(0f, 0f, 599_000L); assertTrue(w.parked.value)
    }

    @Test fun oneMovingSecondUnparks() {
        still(120); assertTrue(w.parked.value)
        w.onSecond(0.05f, 0f, 120_000L); assertFalse(w.parked.value)
        w.onSecond(0f, 0f, 121_000L); assertFalse(w.parked.value)
    }

    @Test fun stillThresholdsAreStrict() {
        for (i in 0 until 120) w.onSecond(0.049f, 0.0099f, i * 1000L)
        assertTrue(w.parked.value)
        w.onSecond(0f, 0.01f, 120_000L); assertFalse(w.parked.value)
    }

    @Test fun resetAndOnRemoteKeepParked() {
        still(120); assertTrue(w.parked.value)
        w.reset(); assertTrue(w.parked.value)
        w.onRemote(true); assertTrue(w.parked.value)
    }

    @Test fun verdictLineFormat() {
        assertEquals("wear=true known=false parked=false", w.verdictLine())
        w.onRemote(false); still(120)
        assertEquals("wear=false known=true parked=true", w.verdictLine())
    }

    @Test fun customUnknownThresholdIsHonoured() {
        val d = WearDetector().apply { config = { WearParkConfig(true, 30_000L, 600_000L) } }
        for (i in 0 until 29) d.onSecond(0f, 0f, i * 1000L)
        assertFalse(d.parked.value)
        d.onSecond(0f, 0f, 29_000L); assertTrue(d.parked.value)
    }

    @Test fun customWornThresholdIsHonoured() {
        val d = WearDetector().apply { config = { WearParkConfig(true, 120_000L, 300_000L) } }
        d.onRemote(true)
        for (i in 0 until 299) d.onSecond(0f, 0f, i * 1000L)
        assertFalse(d.parked.value)
        d.onSecond(0f, 0f, 299_000L); assertTrue(d.parked.value)
    }

    @Test fun disabledNeverParksByStillnessButPcWearStillWorks() {
        val d = WearDetector().apply { config = { WearParkConfig(false, 120_000L, 600_000L) } }
        for (i in 0 until 5_000) d.onSecond(0f, 0f, i * 1000L)
        assertFalse(d.parked.value)
        d.onRemote(false); assertFalse(d.worn.value)
    }

    @Test fun configIsReadLiveSoALaterPushApplies() {
        var cfg = WearParkConfig(false, 120_000L, 600_000L)
        val d = WearDetector().apply { config = { cfg } }
        for (i in 0 until 200) d.onSecond(0f, 0f, i * 1000L)
        assertFalse(d.parked.value)
        cfg = WearParkConfig(true, 120_000L, 600_000L)
        d.onSecond(0f, 0f, 200_000L); assertTrue(d.parked.value)
    }

    @Test fun defaultsAreTwoAndTenMinutes() {
        assertEquals(WearParkConfig(true, 120_000L, 600_000L), WearParkConfig.DEFAULT)
    }
}
