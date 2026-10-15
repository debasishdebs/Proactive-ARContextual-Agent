package com.rokid.context.glasses.capture.motion

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The wear state. Fix wave H: the only real signal is the phone's `pc_wear` (the Rokid SDK's wearingStatus, driven by the
 * glasses' own sensor), via [onRemote]. Unknown counts as worn: [worn] is false only after a real `worn=false` in this
 * session; [reset] (session closed, process start) goes back to unknown. The Android TYPE_PROXIMITY sensor reads a
 * constant "far" on these glasses, so [onProximity] only records what it saw for the diagnostic log and never changes [worn].
 */
class WearDetector(@Suppress("UNUSED_PARAMETER") clock: () -> Long = { 0L }) {
    private val _worn = MutableStateFlow(true)
    val worn: StateFlow<Boolean> = _worn

    /** True once a real `pc_wear` arrived in this session. */
    @Volatile var known = false
        private set
    /** Diagnostic only: the last Android proximity verdict (true = near), null before any. */
    @Volatile var lastProximityNear: Boolean? = null
        private set

    fun onRemote(worn: Boolean) { known = true; _worn.value = worn }

    fun reset() { known = false; _worn.value = true }

    @Suppress("UNUSED_PARAMETER")
    fun onProximity(near: Boolean, tMs: Long = 0L) { lastProximityNear = near }

    /** Kept for the sensor feed's once-a-second call; nothing to age any more. */
    fun tick() = Unit
}
