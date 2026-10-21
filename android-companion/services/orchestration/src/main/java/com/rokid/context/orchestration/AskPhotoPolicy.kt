package com.rokid.context.orchestration

import android.content.Context
import com.rokid.context.work.frames.recall.PhotoHit

/**
 * Whether and which photos an Ask may send to the ANSWER model (fix round 1, controller ruling C1).
 *
 * - The user switch "Send matching photos with questions" (Settings > AI) is **off by default**. Off: no image-vector scan, no image, no photo line
 *   (the caption memories still go as ordinary text lines).
 * - On: only photos at or above [MIN_SCORE] and within [MARGIN] of the best hit are used (neither image nor line below the floor).
 * - Never while the pipeline is paused, inside quiet hours, or when the ANSWER endpoint cannot see images.
 *
 * The floor values are PROVISIONAL: to be calibrated on device with the Developer-mode photo labelling screen (Task 10).
 * The switch lives in its own prefs file ([PREFS]), excluded from backup and device transfer like the other opt-in privacy switches.
 */
object AskPhotoPolicy {
    const val PREFS = "ask_photos"
    private const val KEY = "send_photos"

    /** Provisional minimum Nomic text-to-image cosine of a photo to be used at all (to be calibrated on device). */
    const val MIN_SCORE = 0.22
    /** Provisional: a photo must be within this of the top hit (to be calibrated on device). */
    const val MARGIN = 0.05
    /** At most this many photos per ask. */
    const val MAX_PHOTOS = 2
    /** The image scan's own budget, so partial hits survive the outer timeout. */
    const val SCAN_BUDGET_MS = 1_200L
    /** The outer backstop around the whole photo search. */
    const val ASK_TIMEOUT_MS = 1_500L

    fun enabled(context: Context): Boolean = prefs(context).getBoolean(KEY, false)
    fun set(context: Context, on: Boolean) { prefs(context).edit().putBoolean(KEY, on).apply() }
    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Every condition must hold; unknown quiet hours are treated as quiet by the caller (fail-closed). */
    fun allowed(enabled: Boolean, pipelineOn: Boolean, quietNow: Boolean, answerHasVision: Boolean): Boolean =
        enabled && pipelineOn && !quietNow && answerHasVision

    /** The floor: best first, at or above [MIN_SCORE], within [MARGIN] of the top, at most [MAX_PHOTOS]. */
    fun select(hits: List<PhotoHit>): List<PhotoHit> {
        val sorted = hits.sortedByDescending { it.score }
        val top = sorted.firstOrNull()?.score ?: return emptyList()
        return sorted.filter { it.score >= MIN_SCORE && it.score >= top - MARGIN }.take(MAX_PHOTOS)
    }
}
