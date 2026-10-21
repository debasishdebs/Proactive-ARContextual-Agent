package com.rokid.context.glasses.capture.session

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PhoneUseTest {
    @Test fun activeOnlyAfterTheDebounce() {
        val p = PhoneUse()
        assertFalse(p.active(0))
        p.onRemote(true, 1_000)
        assertFalse(p.active(3_999))
        assertTrue(p.active(4_000))
        p.onRemote(true, 5_000) // a repeat of the same state keeps the original start
        assertTrue(p.active(5_001))
        p.onRemote(false, 6_000)
        assertFalse(p.active(10_000))
    }

    @Test fun resetGoesBackToNotInUse() {
        val p = PhoneUse()
        p.onRemote(true, 0)
        assertTrue(p.active(3_000))
        p.reset()
        assertFalse(p.active(10_000))
    }
}
