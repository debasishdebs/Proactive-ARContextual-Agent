package com.rokid.context.phone.ui.settings.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionPlanTest {
    private val activity = "android.permission.ACTIVITY_RECOGNITION"
    private val fine = "android.permission.ACCESS_FINE_LOCATION"
    private val coarse = "android.permission.ACCESS_COARSE_LOCATION"
    private val bt = "android.permission.BLUETOOTH_CONNECT"

    @Test fun table() {
        assertEquals(listOf(activity), PermissionPlan.forSource("phone_activity", 34))
        assertEquals(listOf(activity), PermissionPlan.forSource("phone_steps", 34))
        assertEquals(listOf(fine, coarse), PermissionPlan.forSource("phone_speed", 34))
        assertEquals(listOf(bt), PermissionPlan.forSource("phone_car", 34))
        assertEquals(emptyList<String>(), PermissionPlan.forSource("watch_app", 34))
        assertEquals(emptyList<String>(), PermissionPlan.forSource("image_intent", 34))
        assertEquals(emptyList<String>(), PermissionPlan.forSource("phone_airplane", 34))
        assertEquals(emptyList<String>(), PermissionPlan.forSource("nonsense", 34))
    }

    @Test fun bluetoothConnectIsOnlyRequestedFromAndroid12() {
        assertEquals(emptyList<String>(), PermissionPlan.forSource("phone_car", 30))
        assertEquals(listOf(bt), PermissionPlan.forSource("phone_car", 31))
    }

    @Test fun neverBackgroundLocation() {
        for (id in PermissionPlan.SOURCE_IDS) for (sdk in listOf(29, 31, 34, 35))
            assertFalse(PermissionPlan.forSource(id, sdk).any { it.contains("BACKGROUND") })
    }

    @Test fun speedAcceptsCoarseOnlyOthersNeedEverything() {
        assertTrue(PermissionPlan.accepted("phone_speed", 34) { it == coarse })
        assertTrue(PermissionPlan.accepted("phone_speed", 34) { it == fine })
        assertFalse(PermissionPlan.accepted("phone_speed", 34) { false })
        assertTrue(PermissionPlan.accepted("phone_car", 34) { it == bt })
        assertFalse(PermissionPlan.accepted("phone_car", 34) { false })
        assertTrue(PermissionPlan.accepted("image_intent", 34) { false })
        assertTrue(PermissionPlan.accepted("phone_car", 30) { false })
    }
}
