package com.rokid.context.work.budget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhonePowerPolicyTest {
    private var now = 0L
    private var fg = true
    private var thermal = PhoneThermal.NONE
    private val policy = PhonePowerPolicy({ now }, { fg }, ThermalStatusProvider { thermal })

    @Test fun backgroundBatchesVectors60s() {
        assertEquals(0L, policy.vectorBatchIntervalMs()) // foreground: on every arrival
        fg = false
        assertEquals(60_000L, policy.vectorBatchIntervalMs())
        assertTrue(policy.mayRunBackground(BackgroundWork.IMAGE_VECTORS))
    }

    @Test fun moderatePausesBackground() {
        thermal = PhoneThermal.MODERATE
        assertFalse(policy.mayRunBackground(BackgroundWork.IMAGE_VECTORS))
        assertFalse(policy.mayRunBackground(BackgroundWork.CAPTION_QUEUE))
        assertFalse(policy.mayRunBackground(BackgroundWork.BACKFILL))
        // Non-user extraction: at most one call per 30 s.
        assertTrue(policy.tryExtraction())
        now = 29_999; assertFalse(policy.tryExtraction())
        now = 30_000; assertTrue(policy.tryExtraction())
    }

    @Test fun severePausesAll() {
        thermal = PhoneThermal.SEVERE
        assertFalse(policy.tryExtraction())
        assertFalse(policy.mayRunBackground(BackgroundWork.EXTRACTION))
        assertFalse(policy.mayRunBackground(BackgroundWork.IMAGE_VECTORS))
        thermal = PhoneThermal.MODERATE // below SEVERE, but not below MODERATE: stays paused
        now = 10 * 60_000L
        assertFalse(policy.mayRunBackground(BackgroundWork.IMAGE_VECTORS))
        thermal = PhoneThermal.LIGHT // below MODERATE: needs 2 min
        now += 1_000; assertFalse(policy.mayRunBackground(BackgroundWork.EXTRACTION))
        now += 119_000; assertFalse(policy.mayRunBackground(BackgroundWork.EXTRACTION))
        now += 1_000; assertTrue(policy.mayRunBackground(BackgroundWork.EXTRACTION))
        assertTrue(policy.tryExtraction())
    }

    @Test fun userAskAlwaysAllowed() {
        thermal = PhoneThermal.SEVERE; fg = false
        assertTrue(policy.mayRunBackground(BackgroundWork.USER_ASK))
        thermal = PhoneThermal.CRITICAL
        assertTrue(policy.mayRunBackground(BackgroundWork.USER_ASK))
    }
}
