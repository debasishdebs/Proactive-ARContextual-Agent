package com.rokid.context.core.model.vision

/**
 * Describes a photo in words, fully on the phone (no network). V1 of the offline photo tier (owner decision 2026-11-26, round 5): the caption is
 * ranked as text by a later task. Pure interface so every consumer is tested with a fake; the real one is `SmolVlmCaptioner` (`:services:vision-local`).
 *
 * A caption is CONTENT: never log it or the path it came from (counts and latency only), never persist it here.
 */
interface OfflineCaptioner {
    /** Captions the JPEG at [jpegPath]. A failure is a [CaptionRefused] (nothing was run) or another exception (the run failed); this never throws except for cancellation. */
    suspend fun caption(jpegPath: String): Result<String>

    /** Frees the model and the native context now (end of a batch). A no-op when nothing is loaded. */
    suspend fun release()
}

/** Why a caption was not produced. Fixed names, safe to log. */
enum class CaptionRefusal { DISABLED, MODEL_MISSING, THERMAL, BATTERY_SAVER, BATTERY_LOW, MAIN_THREAD, BAD_IMAGE, ENGINE_FAILED }

class CaptionRefused(val reason: CaptionRefusal) : Exception(reason.name)
