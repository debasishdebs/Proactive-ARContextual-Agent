package com.rokid.context.phone.voice

import com.rokid.context.core.model.PauseOrigin
import com.rokid.context.phone.ui.status.PauseHub
import com.rokid.context.voice.CaptureControl
import com.rokid.context.voice.CaptureResult
import com.rokid.context.voice.UtteranceSource
import kotlinx.coroutines.CancellationException

/**
 * v0.9.9: voice pause/resume over the same hub entry the phone buttons use ([PauseHub.pausePhone] / [PauseHub.resume]). Awaited, so the reply is true.
 * The origin is always PHONE: `Hub.pausePhone` is the only creator, so a glasses voice pause is a PHONE record too.
 *  - Pause always leaves a lasting user pause: an indefinite PHONE record, even while a timed pause, quiet hours or a glasses pause is active (those end by themselves or elsewhere).
 *  - Resume clears ONLY the PHONE-origin records. A glasses double-tap pause, quiet hours or a low battery are never cleared by voice, and the reply names what still holds capture.
 *    "Capture resumed" is said only when nothing else holds it; with the glasses link down it says the phone resumed and the glasses follow when connected.
 * Nothing is logged but a fixed token; the transcript never gets here.
 */
class HubCaptureControl(
    private val hub: () -> PauseHub,
    private val log: (String) -> Unit = {},
    /** The glasses link carries data, so the resumed state reached them; false = they follow when connected. */
    private val glassesLinked: () -> Boolean = { true },
    /** Low battery holds capture on the glasses (not a pause record). */
    private val batteryHold: () -> Boolean = { false },
) : CaptureControl {
    override suspend fun pause(source: UtteranceSource): CaptureResult = guarded("pause") {
        val h = hub()
        if (h.loadActive().any { it.origin == PauseOrigin.PHONE && it.untilMs == null }) CaptureResult.AlreadyInState else { h.pausePhone(null); CaptureResult.Done }
    }

    override suspend fun resume(source: UtteranceSource): CaptureResult = guarded("resume") {
        val h = hub()
        val active = h.loadActive()
        val mine = active.filter { it.origin == PauseOrigin.PHONE }.map { it.pauseId }.toSet()
        if (mine.isEmpty()) {
            val holds = holds(active.map { it.origin }.toSet())
            return@guarded if (holds == null) CaptureResult.AlreadyInState else CaptureResult.Said("Nothing paused by you. Capture is still $holds")
        }
        h.resume(mine)
        val left = h.loadActive()
        if (left.any { it.origin == PauseOrigin.PHONE }) return@guarded CaptureResult.Failed
        val holds = holds(left.map { it.origin }.toSet())
        when {
            holds != null -> CaptureResult.Said("Your pause is cleared. Capture is still $holds")
            !glassesLinked() -> CaptureResult.Said("Phone resumed; glasses will follow when connected")
            else -> CaptureResult.Done
        }
    }

    private fun holds(origins: Set<PauseOrigin>): String? {
        val parts = buildList {
            if (PauseOrigin.GLASSES in origins) add("paused from the glasses")
            if (PauseOrigin.QUIET_HOURS in origins) add("paused by quiet hours")
            if (batteryHold()) add("held by low battery")
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" and ")
    }

    private suspend fun guarded(what: String, block: suspend () -> CaptureResult): CaptureResult =
        try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { log("voice $what failed (${e.javaClass.simpleName})"); CaptureResult.Failed }
}
