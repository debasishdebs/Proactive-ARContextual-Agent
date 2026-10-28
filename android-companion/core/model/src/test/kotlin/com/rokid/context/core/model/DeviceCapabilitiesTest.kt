package com.rokid.context.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeviceCapabilitiesTest {
    private val off = DeviceCapabilities.ALL

    @Test fun defaultsEnableEverythingAndListTheFourEntries() {
        assertEquals(4, off.list().size); assertTrue(off.list().all { it.enabled })
        assertEquals("hud", off.list().single { it.device == "glasses" && it.capability == "display" }.constraint)
        assertNull(off.list().single { it.device == "phone" && it.capability == "display" }.constraint)
    }

    @Test fun withSwitchesOneEntryAndIgnoresUnknownOnes() {
        val c = off.with("glasses", "mic", false)
        assertFalse(c.micOn(true)); assertTrue(c.micOn(false)); assertEquals(off, c.with("glasses", "mic", true))
        assertEquals(off, off.with("watch", "mic", false)); assertEquals(off, off.with("phone", "camera", false))
        assertEquals(setOf("phone.mic"), DeviceCapabilities.clean(listOf("phone.mic", "bogus", "x.display")).disabled)
    }

    @Test fun deliveryWarningOnlyWhenTheOnlyTargetIsOff() {
        assertNull(off.deliveryWarning("phone")); assertNull(off.deliveryWarning("both")); assertNull(off.deliveryWarning("caller"))
        assertNotNull(off.with("phone", "display", false).deliveryWarning("phone"))
        assertNull(off.with("phone", "display", false).deliveryWarning("both")); assertNull(off.with("phone", "display", false).deliveryWarning("glasses"))
        assertNotNull(off.with("glasses", "display", false).deliveryWarning("glasses"))
        assertNotNull(off.with("phone", "display", false).with("glasses", "display", false).deliveryWarning("both"))
        assertNull(off.with("glasses", "display", false).deliveryWarning("caller"))
    }
}
