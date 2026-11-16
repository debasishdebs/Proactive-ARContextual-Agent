package com.rokid.context.core.hud.render

import com.rokid.context.core.model.HudPage
import com.rokid.context.core.model.MotionState
import com.rokid.context.core.model.Overlay
import com.rokid.context.core.model.PlaceCardInfo
import com.rokid.context.core.model.StateFrame
import com.rokid.context.core.model.Verbosity

/** Glasses-originated feedback shown at once (offline toast, local pause splash). */
sealed interface LocalOverlay {
    val atMs: Long
    data class Toast(val text: String, override val atMs: Long) : LocalOverlay
    data class Splash(override val atMs: Long) : LocalOverlay
}

/** What to draw: [page] null = no page (Silent: REC dot only); at most one [overlay]. */
data class HudView(val page: HudPage?, val overlay: Overlay?)

/**
 * Verbosity and overlay timing (spec §3.2, §3.4). Silent: no page; overlays only for user flows (a Nudge never shows).
 * Medium: Glance/Today; a Nudge only as the Glance one-liner. Max: also Nudge toasts and the Status page. Nudge hides
 * after 8 s, Confirm after 15 s without voting, Paused splash and toasts after 3 s (an undo toast after 10 s); Listening and Answer stay until the
 * phone clears them or the wearer dismisses them. A local overlay wins over a phone one, except over Confirm.
 * While recording, see [present].
 */
class HudPresenter {
    private var shown: Overlay? = null
    private var shownAtMs = 0L
    private var dismissed: Overlay? = null
    /** The last Answer the wearer dismissed, without its undo (BUG-R2-5): the undo coming or going does not revive it. */
    private var dismissedAnswer: Overlay.Answer? = null
    private var held: Overlay? = null

    private val motion = MotionOnset()
    /** The glasses IMU class (any thread, about once a second). An onset is a change from still or unknown to head moving, walking or a vehicle ([MotionOnset]). */
    fun onMotion(state: MotionState, nowMs: Long) { motion.onMotion(state, nowMs) }

    /** True while a place card is the phone overlay shown and has not been dismissed (the activity tells the phone when it goes). */
    fun placeShown(): Boolean = shown?.isPlace() == true

    /** Dismissing an Answer that carried the undo also dismisses that undo (the wearer saw it and moved on). */
    fun dismiss(o: Overlay?) {
        if (o !is Overlay.Answer) { dismissed = o; return }
        dismissedAnswer = o.copy(undoProposalId = null)
        o.undoProposalId?.let { dismissed = Overlay.Toast(UNDO_TOAST_TEXT, undoProposalId = it) }
    }

    /**
     * A dismissed Answer stays dismissed whatever its undo does; if it carries an open undo, the wearer still gets the
     * undo toast for it (BUG-R2-5).
     */
    private fun visibleOf(o: Overlay?): Overlay? = when {
        o == null || o == dismissed -> null
        o is Overlay.Answer && o.copy(undoProposalId = null) == dismissedAnswer ->
            o.undoProposalId?.let { Overlay.Toast(UNDO_TOAST_TEXT, undoProposalId = it) }?.takeIf { it != dismissed }
        else -> o
    }

    /**
     * While [recording], Listening shows at once until the phone's own Listening arrives. Only a phone toast that is not
     * an undo outranks it (the phone refused or ended the ask); a Confirm waits under it (votes need it on screen, final
     * review I1) and local toasts wait too.
     */
    fun present(frame: StateFrame?, verbosity: Verbosity, nav: HudNav, local: LocalOverlay?, nowMs: Long, recording: Boolean = false, zoneActive: Boolean = false): HudView {
        val pages = pagesFor(verbosity)
        val page = if (pages.isEmpty()) null else nav.page.takeIf { it in pages } ?: HudPage.GLANCE
        // Place Guide defence in depth: never a place card while a pause zone is active, whatever the phone sent.
        val phone = visibleOf(frame?.overlay)?.takeIf { allowed(it, verbosity) && !(zoneActive && it.isPlace()) }
        if (phone != shown) { shown = phone; shownAtMs = nowMs; held = null }
        // Head-motion onset (after the card has been up at least 2.5 s) dismisses it through the ordinary dismissal: the same card never returns.
        if (phone != null && phone.isPlace() && MotionOnset.dismisses(motion.lastOnsetAtMs, shownAtMs, nowMs)) { dismiss(phone); shown = null; return present(frame, verbosity, nav, local, nowMs, recording, zoneActive) }
        // Final review I1: a live Confirm hidden under the glasses' Listening is held; its window restarts when it shows.
        if (recording && phone is Overlay.Confirm && held == null && nowMs - shownAtMs < limitMs(phone)) held = phone
        if (held != null && !recording) { shownAtMs = nowMs; held = null }
        val phoneVisible = phone?.takeIf { nowMs - shownAtMs < limitMs(it) }
        val localVisible = when (local) {
            is LocalOverlay.Toast -> Overlay.Toast(local.text).takeIf { nowMs - local.atMs < TOAST_MS }
            is LocalOverlay.Splash -> Overlay.PausedSplash.takeIf { nowMs - local.atMs < SPLASH_MS }
            null -> null
        }
        val listening = if (recording && phoneVisible !is Overlay.Listening) Overlay.Listening("") else null
        val overlay = when {
            listening != null && phoneVisible.isAskFailure() -> phoneVisible // final review I2: the phone refused or ended this ask
            listening != null -> listening
            phoneVisible is Overlay.Confirm -> phoneVisible
            localVisible != null -> localVisible
            else -> phoneVisible
        }
        return HudView(page, overlay)
    }

    companion object {
        const val NUDGE_MS = 8_000L
        const val CONFIRM_MS = 15_000L
        const val SPLASH_MS = 3_000L
        const val TOAST_MS = 3_000L
        /** A phone undo toast (`undoProposalId` set) stays for the whole 10 s undo window. */
        const val UNDO_TOAST_MS = 10_000L
        /** The undo copy the glasses show for an Answer's undo (the phone's toast says the same). */
        const val UNDO_TOAST_TEXT = "Added \u00b7 double-tap undo"

        /** A phone toast with no undo: every one is voice copy for an ask the phone refused or ended. */
        fun Overlay?.isAskFailure(): Boolean = this is Overlay.Toast && undoProposalId == null

        fun Overlay?.isPlace(): Boolean = this is Overlay.Nudge && place != null

        /** A place card shows at Medium and Max (never Silent); a plain nudge only at Max. */
        fun allowed(o: Overlay, v: Verbosity): Boolean = when {
            o.isPlace() -> v != Verbosity.SILENT
            else -> !(o is Overlay.Nudge && v != Verbosity.MAX)
        }

        fun limitMs(o: Overlay): Long = when (o) {
            is Overlay.Nudge -> if (o.place != null) PlaceCardInfo.SHOW_MS else NUDGE_MS
            is Overlay.Confirm -> CONFIRM_MS
            Overlay.PausedSplash -> SPLASH_MS
            is Overlay.Toast -> if (o.undoProposalId != null) UNDO_TOAST_MS else TOAST_MS
            is Overlay.Listening, is Overlay.Answer -> Long.MAX_VALUE
        }
    }
}
