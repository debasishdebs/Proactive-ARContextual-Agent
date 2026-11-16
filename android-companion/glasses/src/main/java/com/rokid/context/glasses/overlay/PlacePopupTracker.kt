package com.rokid.context.glasses.overlay

import com.rokid.context.core.hud.render.MotionOnset
import com.rokid.context.core.model.MotionState
import com.rokid.context.core.model.Overlay
import com.rokid.context.core.model.PlaceCardInfo

/**
 * Which place card the minimised pop-up is showing, and when it must go BEFORE its 12 s timer: the phone withdrew the card ([overlayChanged]: overlay null or anything else, which is also what a
 * Forget or Delete all looks like on the wire), a pause zone became active ([zoneActive]), or head motion began after it had been up 2.5 s ([onMotion], the same [MotionOnset] rule as the HUD
 * presenter). Every method returns the [Shown] to take down (and tell the phone about), or null. Pure state; the activity hides the window.
 */
class PlacePopupTracker {
    /** The card and the pop-up text that was shown for it (the window is only hidden while it still shows that text). */
    data class Shown(val card: PlaceCardInfo, val text: String)

    private var shown: Shown? = null
    private var shownAtMs = 0L
    private val motion = MotionOnset()

    @Synchronized fun show(card: PlaceCardInfo, text: String, nowMs: Long) { shown = Shown(card, text); shownAtMs = nowMs }

    /** The pop-up went away on its own (timer, the HUD opened): nothing to withdraw. */
    @Synchronized fun gone() { shown = null }

    @Synchronized fun current(): Shown? = shown

    /** A phone overlay arrived: the shown card is withdrawn unless the phone still shows that very card. */
    @Synchronized fun overlayChanged(o: Overlay?): Shown? {
        val s = shown ?: return null
        return if (o is Overlay.Nudge && o.place == s.card) null else take()
    }

    @Synchronized fun zoneActive(active: Boolean): Shown? = if (active) take() else null

    @Synchronized fun onMotion(state: MotionState, nowMs: Long): Shown? {
        if (!motion.onMotion(state, nowMs)) return null
        if (shown == null) return null
        return if (MotionOnset.dismisses(nowMs, shownAtMs, nowMs)) take() else null
    }

    private fun take(): Shown? = shown.also { shown = null }
}
