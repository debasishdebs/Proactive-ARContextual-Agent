package com.rokid.context.glasses.capture.motion

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Proximity wear check: "far" for more than [NOT_WORN_AFTER_MS] means not worn; "near" means worn at once.
 * The proximity sensor only reports changes, so the far timeout needs [tick] (SensorFeed calls it every second).
 */
class WearDetector(private val clock: () -> Long) {
    private val _worn = MutableStateFlow(true)
    val worn: StateFlow<Boolean> = _worn

    private var farSinceMs: Long? = null

    fun onProximity(near: Boolean, tMs: Long) {
        if (near) { farSinceMs = null; _worn.value = true }
        else { if (farSinceMs == null) farSinceMs = tMs; tick() }
    }

    fun tick() {
        val since = farSinceMs ?: return
        if (clock() - since > NOT_WORN_AFTER_MS) _worn.value = false
    }

    companion object { const val NOT_WORN_AFTER_MS = 5_000L }
}
