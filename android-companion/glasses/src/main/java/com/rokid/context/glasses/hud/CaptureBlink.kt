package com.rokid.context.glasses.hud

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Fix wave H3: the wearer can feel each photo. [onKeyframeSaved] (a keyframe was stored) turns [active] on for
 * [durationMs] when [enabled] ("Capture blink on HUD", synced `captureCue`); the HUD draws [CaptureStatusCopy.CUE] in a
 * corner meanwhile. Never throws and never waits: capture must not depend on it. A newer blink is never cut short by an
 * older timer.
 */
class CaptureBlink(
    private val scope: CoroutineScope,
    private val enabled: () -> Boolean,
    private val durationMs: Long = DURATION_MS,
) {
    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active
    private var generation = 0L
    /** Blinks started since the process began (a diagnostic and test counter). */
    @Volatile var shown = 0
        private set

    fun onKeyframeSaved() {
        try {
            if (!enabled()) return
            val g = synchronized(this) { _active.value = true; shown++; ++generation }
            scope.launch {
                delay(durationMs)
                synchronized(this@CaptureBlink) { if (generation == g) _active.value = false }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) return
            // A broken settings read or a cancelled scope only loses the blink.
        }
    }

    companion object { const val DURATION_MS = 600L }
}
