package com.rokid.context.phone.voice

import com.rokid.context.phone.ui.status.PauseHub
import com.rokid.context.voice.CaptureControl
import com.rokid.context.voice.CaptureResult
import com.rokid.context.voice.UtteranceSource
import kotlinx.coroutines.CancellationException

/**
 * v0.9.9: voice pause/resume over the same hub entry the phone buttons use ([PauseHub.pausePhone] / [PauseHub.resume]). Awaited, so the reply is true.
 * The origin is always PHONE: `Hub.pausePhone` is the only creator and the pause model's GLASSES origin is a glasses-made record (via the glasses link), so a glasses voice pause is a PHONE record too.
 * Resume ends the pauses stored now, as the notification's Resume does. Nothing is logged but a fixed token; the transcript never gets here.
 */
class HubCaptureControl(private val hub: () -> PauseHub, private val log: (String) -> Unit = {}) : CaptureControl {
    override suspend fun pause(source: UtteranceSource): CaptureResult = guarded("pause") {
        val h = hub()
        if (h.loadActive().isNotEmpty()) CaptureResult.AlreadyInState else { h.pausePhone(null); CaptureResult.Done }
    }

    override suspend fun resume(source: UtteranceSource): CaptureResult = guarded("resume") {
        val h = hub()
        val ids = h.loadActive().map { it.pauseId }.toSet()
        if (ids.isEmpty()) CaptureResult.AlreadyInState else { h.resume(ids); CaptureResult.Done }
    }

    private suspend fun guarded(what: String, block: suspend () -> CaptureResult): CaptureResult =
        try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { log("voice $what failed (${e.javaClass.simpleName})"); CaptureResult.Failed }
}
