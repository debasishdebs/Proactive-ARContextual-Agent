package com.rokid.context.phone.hub

import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneUseSignalTest {
    @Test fun inUseOnlyWhenInteractiveAndUnlocked() {
        val s = PhoneUseSignal()
        assertEquals(false, s.inUse.value)
        s.onScreen(interactive = true, keyguardLocked = false); assertEquals(true, s.inUse.value)
        s.onScreen(interactive = true, keyguardLocked = true); assertEquals(false, s.inUse.value)
        s.onScreen(interactive = false, keyguardLocked = false); assertEquals(false, s.inUse.value)
    }
}
