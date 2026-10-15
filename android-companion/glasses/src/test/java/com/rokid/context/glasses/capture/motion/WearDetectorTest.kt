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
}
