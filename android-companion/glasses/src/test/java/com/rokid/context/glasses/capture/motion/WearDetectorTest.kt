package com.rokid.context.glasses.capture.motion

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WearDetectorTest {
    private var now = 0L
    private val w = WearDetector { now }

    @Test fun farFor5sMeansNotWorn() {
        w.onProximity(false, 0)
        now = 5000; w.tick()
        assertTrue(w.worn.value)
        now = 5001; w.tick()
        assertFalse(w.worn.value)
    }

    @Test fun briefFarIgnored() {
        w.onProximity(false, 0)
        now = 3000; w.onProximity(true, 3000)
        now = 9000; w.tick()
        assertTrue(w.worn.value)
    }

    @Test fun nearRestoresAtOnce() {
        w.onProximity(false, 0)
        now = 6000; w.tick()
        assertFalse(w.worn.value)
        w.onProximity(true, 6000)
        assertTrue(w.worn.value)
    }
}
