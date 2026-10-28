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
}
