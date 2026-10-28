package com.rokid.context.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeliveryRouteTest {
    @Test fun callerReturnsToTheSurfaceThatAsked() {
        val fromGlasses = DeliveryRoute.resolve("caller", callerOnGlasses = true, reach = GlassesReach.UP)
        assertTrue(fromGlasses.glasses); assertFalse(fromGlasses.phone); assertFalse(fromGlasses.explicit)
        val fromChat = DeliveryRoute.resolve("caller", callerOnGlasses = false, reach = GlassesReach.NO_PATH)
        assertTrue(fromChat.phone); assertFalse(fromChat.glasses); assertNull(fromChat.fallback)
    }

    @Test fun phoneAndGlassesAreExclusiveAndBothIsBoth() {
        DeliveryRoute.resolve("phone", true, GlassesReach.UP).let { assertTrue(it.phone); assertFalse(it.glasses) }
        DeliveryRoute.resolve("glasses", false, GlassesReach.UP).let { assertTrue(it.glasses); assertFalse(it.phone); assertNull(it.fallback) }
        DeliveryRoute.resolve("both", false, GlassesReach.UP).let { assertTrue(it.glasses && it.phone) }
    }

    @Test fun glassesThatCannotBeReachedFallBackToThePhoneWithAReason() {
        for (target in listOf("glasses", "both")) {
            val down = DeliveryRoute.resolve(target, false, GlassesReach.DOWN)
            assertTrue(down.phone); assertFalse(down.glasses); assertEquals(DeliveryRoute.GLASSES_NOT_CONNECTED, down.fallback)
            assertEquals(DeliveryRoute.NO_GLASSES_PATH, DeliveryRoute.resolve(target, false, GlassesReach.NO_PATH).fallback)
        }
        assertNull(DeliveryRoute.resolve("phone", false, GlassesReach.DOWN).fallback)
    }

    private val noGlassesDisplay = DeviceCapabilities.ALL.with("glasses", "display", false)
    private val noPhoneDisplay = DeviceCapabilities.ALL.with("phone", "display", false)
    private val none = noGlassesDisplay.with("phone", "display", false)

    @Test fun aDisabledGlassesDisplayFallsBackToThePhoneWithAReason() {
        for (target in listOf("glasses", "both")) {
            val r = DeliveryRoute.resolve(target, false, GlassesReach.UP, noGlassesDisplay)
            assertTrue(r.phone); assertFalse(r.glasses); assertEquals(DeviceCapabilities.GLASSES_DISPLAY_OFF, r.fallback)
        }
        val caller = DeliveryRoute.resolve("caller", true, GlassesReach.UP, noGlassesDisplay)
        assertTrue(caller.phone); assertFalse(caller.glasses); assertTrue(caller.explicit); assertEquals(DeviceCapabilities.GLASSES_DISPLAY_OFF, caller.fallback)
        assertNull(DeliveryRoute.resolve("phone", false, GlassesReach.UP, noGlassesDisplay).fallback)
    }

    @Test fun aDisabledPhoneDisplayMovesToTheGlassesWhenTheyAreUp() {
        DeliveryRoute.resolve("phone", false, GlassesReach.UP, noPhoneDisplay).let { assertTrue(it.glasses); assertFalse(it.phone); assertEquals(DeviceCapabilities.PHONE_DISPLAY_OFF, it.fallback) }
        DeliveryRoute.resolve("both", false, GlassesReach.UP, noPhoneDisplay).let { assertTrue(it.glasses); assertFalse(it.phone); assertEquals(DeviceCapabilities.PHONE_DISPLAY_OFF, it.fallback) }
        DeliveryRoute.resolve("phone", false, GlassesReach.DOWN, noPhoneDisplay).let { assertTrue(it.phone); assertEquals(DeviceCapabilities.PHONE_DISPLAY_OFF, it.fallback) }
        assertNull(DeliveryRoute.resolve("glasses", false, GlassesReach.UP, noPhoneDisplay).fallback)
    }

    @Test fun noDisplayAtAllStillStoresOnThePhoneAndSaysSo() {
        for (target in listOf("phone", "glasses", "both")) {
            val r = DeliveryRoute.resolve(target, false, GlassesReach.UP, none)
            assertTrue(r.phone); assertFalse(r.glasses); assertEquals(DeviceCapabilities.NO_DISPLAY, r.fallback)
        }
    }

    @Test fun defaultCapabilitiesChangeNothing() {
        for (target in listOf("caller", "phone", "glasses", "both")) for (reach in GlassesReach.values()) for (g in listOf(true, false))
            assertEquals(DeliveryRoute.resolve(target, g, reach), DeliveryRoute.resolve(target, g, reach, DeviceCapabilities.ALL))
    }
}
