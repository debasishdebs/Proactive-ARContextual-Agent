package com.rokid.context.phone.ui.settings.capture

import android.Manifest
import android.os.Build

/**
 * Which runtime permissions each context source needs before it may start (wave B spec §11). Pure, so it is table-tested.
 * Background location is never requested: the speed source runs only while the app is in use or its service is foreground.
 */
object PermissionPlan {
    val SOURCE_IDS = listOf("phone_activity", "phone_steps", "phone_speed", "phone_car", "watch_app", "image_intent", "phone_airplane")

    fun forSource(id: String, sdk: Int = Build.VERSION.SDK_INT): List<String> = when (id) {
        "phone_activity", "phone_steps" -> if (sdk >= 29) listOf(Manifest.permission.ACTIVITY_RECOGNITION) else emptyList()
        "phone_speed" -> listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        "phone_car" -> if (sdk >= 31) listOf(Manifest.permission.BLUETOOTH_CONNECT) else emptyList()
        else -> emptyList() // watch (a certificate pin, no permission), image intent, airplane mode, unknown ids
    }

    /** Speed accepts approximate location alone (only long fast trips are then detected); every other source needs all of its permissions. */
    fun accepted(id: String, sdk: Int = Build.VERSION.SDK_INT, granted: (String) -> Boolean): Boolean {
        val needed = forSource(id, sdk)
        return if (id == "phone_speed") needed.any(granted) else needed.all(granted)
    }
}
