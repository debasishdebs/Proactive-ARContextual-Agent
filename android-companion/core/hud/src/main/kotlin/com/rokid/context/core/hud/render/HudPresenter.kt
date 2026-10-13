package com.rokid.context.core.hud.render

import com.rokid.context.core.model.HudPage
import com.rokid.context.core.model.Overlay
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
 * after 8 s, Confirm after 15 s without voting, Paused splash and toasts after 3 s; Listening and Answer stay until the
 * phone clears them or the wearer dismisses them. A local overlay wins over a phone one, except over Confirm.
 */
class HudPresenter {
    private var shown: Overlay? = null
    private var shownAtMs = 0L
    private var dismissed: Overlay? = null

    fun dismiss(o: Overlay?) { dismissed = o }

    fun present(frame: StateFrame?, verbosity: Verbosity, nav: HudNav, local: LocalOverlay?, nowMs: Long): HudView {
        val pages = pagesFor(verbosity)
        val page = if (pages.isEmpty()) null else nav.page.takeIf { it in pages } ?: HudPage.GLANCE
        val phone = frame?.overlay?.takeIf { it != dismissed && allowed(it, verbosity) }
        if (phone != shown) { shown = phone; shownAtMs = nowMs }
        val phoneVisible = phone?.takeIf { nowMs - shownAtMs < limitMs(it) }
        val localVisible = when (local) {
            is LocalOverlay.Toast -> Overlay.Toast(local.text).takeIf { nowMs - local.atMs < TOAST_MS }
            is LocalOverlay.Splash -> Overlay.PausedSplash.takeIf { nowMs - local.atMs < SPLASH_MS }
            null -> null
        }
        val overlay = when {
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

        fun allowed(o: Overlay, v: Verbosity): Boolean = !(o is Overlay.Nudge && v != Verbosity.MAX)

        fun limitMs(o: Overlay): Long = when (o) {
            is Overlay.Nudge -> NUDGE_MS
            is Overlay.Confirm -> CONFIRM_MS
            Overlay.PausedSplash -> SPLASH_MS
            is Overlay.Toast -> TOAST_MS
            is Overlay.Listening, is Overlay.Answer -> Long.MAX_VALUE
        }
    }
}
