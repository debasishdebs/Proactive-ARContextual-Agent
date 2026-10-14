package com.rokid.context.phone.ui.status

import com.rokid.context.core.model.CaptureState
import com.rokid.context.core.model.GlassesStatus
import com.rokid.context.core.model.LinkState
import com.rokid.context.core.model.carriesData
import com.rokid.context.core.model.PauseOrigin
import com.rokid.context.core.model.PauseRecord
import java.util.Locale
import kotlin.math.ceil

/** Home subline / Activity status strip text (spec §2.4): "Capturing · next 4:12", "Paused", "Glasses offline". */
object CaptureLine {
    fun paused(s: GlassesStatus, pipelineEnabled: Boolean): Boolean = !pipelineEnabled || s.frame?.capture is CaptureState.Paused

    /**
     * Legacy only: paused by the glasses while the old pipeline switch is on, so the switch can't resume it. With the
     * Phase 7 pause control `pipelineEnabled` is false whenever a record is active, so this is false and ⏸/▶ resumes.
     */
    fun pausedOnGlasses(s: GlassesStatus, pipelineEnabled: Boolean): Boolean = pipelineEnabled && s.frame?.capture is CaptureState.Paused

    fun live(s: GlassesStatus, pipelineEnabled: Boolean): Boolean =
        !paused(s, pipelineEnabled) && s.link.carriesData && s.frame?.capture == CaptureState.Running

    /** [pauses]: the active records (Phase 7); they name the pause when the control reports paused. */
    fun text(s: GlassesStatus, pipelineEnabled: Boolean, nowMs: Long, pauses: List<PauseRecord> = emptyList()): String {
        if (!pipelineEnabled) return pausedText(pauses)
        if (pausedOnGlasses(s, pipelineEnabled)) return "Paused on glasses"
        val frame = s.frame
        if (s.link == LinkState.MINIMISED && frame?.capture == CaptureState.Running) return "Minimised on glasses \u00b7 capturing"
        if (frame == null || s.link != LinkState.CONNECTED) return when (s.link) {
            LinkState.CONNECTING -> "Connecting to glasses…"
            LinkState.PAUSED_BT -> "Bluetooth paused · reconnecting"
            LinkState.CONNECTED -> "Connected"
            else -> "Glasses offline"
        }
        return when (val c = frame.capture) {
            CaptureState.Running -> frame.nextCaptureAtMs?.let { "Capturing · next ${countdown(it, nowMs)}" } ?: "Capturing"
            is CaptureState.Deferred -> "Deferred · ${c.reason.take(40)}"
            is CaptureState.Blocked -> "Blocked · ${c.reason.take(40)}"
            is CaptureState.Paused -> "Paused"
        }
    }

    /** "Quiet hours" when only quiet hours, "Paused on glasses" when every record is the glasses', else "Paused". */
    fun pausedText(pauses: List<PauseRecord>): String = when {
        pauses.isEmpty() -> "Paused"
        pauses.all { it.origin == PauseOrigin.QUIET_HOURS } -> "Quiet hours"
        pauses.all { it.origin == PauseOrigin.GLASSES } -> "Paused on glasses"
        else -> "Paused"
    }

    /** m:ss (h:mm:ss past an hour), rounded up; "now" once due. */
    fun countdown(atMs: Long, nowMs: Long): String {
        val s = ceil((atMs - nowMs) / 1000.0).toLong()
        if (s <= 0) return "now"
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec) else String.format(Locale.US, "%d:%02d", m, sec)
    }
}
