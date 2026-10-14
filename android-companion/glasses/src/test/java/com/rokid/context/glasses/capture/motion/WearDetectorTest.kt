package com.rokid.context.glasses.capture.motion

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WearDetectorTest {
    private var now = 0L
    private val w = WearDetector { now }

    @Test fun farFor5sMeansNotWorn() {
        w.onProximity(false)
        now = 5000; w.tick()
        assertTrue(w.worn.value)
        now = 5001; w.tick()
        assertFalse(w.worn.value)
    }

    @Test fun briefFarIgnored() {
        w.onProximity(false)
        now = 3000; w.onProximity(true)
        now = 9000; w.tick()
        assertTrue(w.worn.value)
    }

    @Test fun nearRestoresAtOnce() {
        w.onProximity(false)
        now = 6000; w.tick()
        assertFalse(w.worn.value)
        w.onProximity(true)
        assertTrue(w.worn.value)
    }

    @Test fun onlyTheInjectedClockCounts() {
        now = 100_000
        // a sensor timestamp from another time base must not move the far start
        w.onProximity(false, 5)
        now = 104_999; w.tick()
        assertTrue(w.worn.value)
        now = 105_001; w.tick()
        assertFalse(w.worn.value)
    }
}
