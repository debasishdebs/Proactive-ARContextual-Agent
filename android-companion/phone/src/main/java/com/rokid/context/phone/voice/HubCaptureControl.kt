package com.rokid.context.phone.voice

import com.rokid.context.core.model.PauseOrigin
import com.rokid.context.core.model.zone.ZonePause
import com.rokid.context.phone.zone.ZoneHold
import com.rokid.context.phone.ui.status.PauseHub
import com.rokid.context.voice.CaptureControl
import com.rokid.context.voice.CaptureResult
import com.rokid.context.voice.Utterance
import com.rokid.context.voice.UtteranceSource
import kotlinx.coroutines.CancellationException

/**
 * v0.9.9: voice pause/resume over the same hub entry the phone buttons use ([PauseHub.pausePhoneAcked] / [PauseHub.resumeAcked]). Awaited. Every reply says ONLY what was confirmed:
 *  - "...on the phone and the glasses" only when the settings push was accepted by the link; otherwise "...on the phone; the glasses will follow when they reconnect". Never the link state alone.
 *  - Pause always leaves a lasting user pause: an indefinite PHONE record, even while a timed pause, quiet hours or a glasses pause is active.
 *  - Resume clears ONLY the PHONE-origin records. A synchronised glasses pause or quiet hours are never cleared by voice and are named.
 *  - The phone has no signal for the glasses' low-battery capture hold (only a battery percentage), so no reply ever claims or denies one.
 * Nothing is logged but a fixed token; the transcript never gets here.
 */
class HubCaptureControl(
    private val hub: () -> PauseHub,
    private val log: (String) -> Unit = {},
) : CaptureControl {
    override suspend fun pause(source: UtteranceSource): CaptureResult = guarded("pause") {
        val h = hub()
        // A zone pause is PHONE-origin and indefinite but is not the user's: it ends by itself at the zone's exit, so "pause" still makes the lasting USER pause (capture must not resume against the request).
        if (h.loadActive().any { it.origin == PauseOrigin.PHONE && it.untilMs == null && !ZonePause.isZoneRecord(it.pauseId) }) return@guarded CaptureResult.AlreadyInState
        val (_, pushed) = h.pausePhoneAcked(null)
        CaptureResult.Said(if (pushed) Utterance.CAPTURE_PAUSED else Utterance.PAUSED_PHONE_ONLY)
    }

    override suspend fun resume(source: UtteranceSource): CaptureResult = guarded("resume") {
        val h = hub()
        val active = h.loadActive()
        val mine = active.filter { it.origin == PauseOrigin.PHONE && !ZonePause.isZoneRecord(it.pauseId) }.map { it.pauseId }.toSet()
        val zoneHold = active.any { ZonePause.isZoneRecord(it.pauseId) } || ZoneHold.held
        if (mine.isEmpty()) {
            if (zoneHold) return@guarded CaptureResult.Said(ZONE_REFUSAL)
            val holds = holds(active.map { it.origin }.toSet())
            return@guarded if (holds == null) CaptureResult.AlreadyInState else CaptureResult.Said("${Utterance.ALREADY_CAPTURING}. $holds")
        }
        val pushed = h.resumeAcked(mine)
        val left = h.loadActive()
        if (left.any { it.origin == PauseOrigin.PHONE && !ZonePause.isZoneRecord(it.pauseId) }) return@guarded CaptureResult.Failed
        val base = if (pushed) Utterance.CAPTURE_RESUMED else Utterance.RESUMED_PHONE_ONLY
        val holds = listOfNotNull(holds(left.map { it.origin }.toSet()), if (left.any { ZonePause.isZoneRecord(it.pauseId) } || ZoneHold.held) ZONE_HOLD else null).joinToString(". ").ifEmpty { null }
        CaptureResult.Said(if (holds == null) base else "$base. $holds")
    }

    companion object {
        /** A zone pause is never ended by voice: it ends when the phone leaves the area. Names no place. */
        const val ZONE_REFUSAL = "Capture is paused near a protected area and resumes by itself when you leave"
        const val ZONE_HOLD = "Capture stays paused near a protected area"
    }

    private fun holds(origins: Set<PauseOrigin>): String? {
        val parts = buildList {
            if (PauseOrigin.GLASSES in origins) add("The glasses are still paused")
            if (PauseOrigin.QUIET_HOURS in origins) add("Quiet hours are still on")
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(". ")
    }

    private suspend fun guarded(what: String, block: suspend () -> CaptureResult): CaptureResult =
        try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { log("voice $what failed (${e.javaClass.simpleName})"); CaptureResult.Failed }
}
