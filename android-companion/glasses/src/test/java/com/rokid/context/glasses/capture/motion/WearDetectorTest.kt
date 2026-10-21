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
}
