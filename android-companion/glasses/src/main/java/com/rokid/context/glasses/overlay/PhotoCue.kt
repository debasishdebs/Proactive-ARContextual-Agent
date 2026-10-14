package com.rokid.context.glasses.overlay

import com.rokid.context.core.hud.render.HudCopy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The "photo sent" cue, glasses-local (no protocol field): [pulse] on a frame buffered or delivered (PcUplink) turns
 * [active] on for [durationMs]. The Glance timer shows a check and the minimised icon's ring pulses while it is on. A
 * pulse inside the window restarts it; an older pulse's timer never cuts a newer one short.
 */
class PhotoCue(private val scope: CoroutineScope, private val durationMs: Long = DURATION_MS) {
    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active
    private var generation = 0L

    fun pulse() {
        val g = synchronized(this) { _active.value = true; ++generation }
        scope.launch {
            delay(durationMs)
            synchronized(this@PhotoCue) { if (generation == g) _active.value = false }
        }
    }

    companion object {
        const val DURATION_MS = 1_000L
        const val CHECK = HudCopy.PHOTO_CUE
    }
}
