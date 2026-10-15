package com.rokid.context.work.budget

/** The phone's thermal status, in `PowerManager.THERMAL_STATUS_*` order. */
enum class PhoneThermal { NONE, LIGHT, MODERATE, SEVERE, CRITICAL, EMERGENCY, SHUTDOWN }

/** Reads the phone's current thermal status (the app wires `PowerManager.currentThermalStatus`; tests fake it). */
fun interface ThermalStatusProvider { fun status(): PhoneThermal }

enum class BackgroundWork { EXTRACTION, IMAGE_VECTORS, CAPTION_QUEUE, BACKFILL, USER_ASK }

/**
 * Phone power and heat policy (spec §P2.5; no work/rest on the phone). Pure: the clock, the foreground flag and the
 * thermal status are injected; the pipeline and the image-vector batching read the flags.
 *
 * - app not in the foreground: image vectors are batched every 60 s ([vectorBatchIntervalMs]);
 * - thermal >= MODERATE: image vectors, caption queue and backfill pause; non-user extraction is allowed once per 30 s
 *   ([tryExtraction]);
 * - thermal >= SEVERE: everything in the background pauses until the status has been below MODERATE for 2 min;
 * - user-started asks are never paused.
 */
class PhonePowerPolicy(
    private val clock: () -> Long,
    private val foreground: () -> Boolean,
    private val thermal: ThermalStatusProvider,
) {
    private var severe = false
    private var belowSinceMs = -1L
    private var lastModerateExtractionMs = Long.MIN_VALUE / 2

    /** 0 = process each arrival; otherwise batch image vectors on this period. */
    fun vectorBatchIntervalMs(): Long = if (foreground()) 0L else VECTOR_BATCH_MS

    /** True while background work of this kind may run right now (extraction at MODERATE honours the 30 s allowance; [tryExtraction] takes it). Thread-safe. */
    @Synchronized fun mayRunBackground(work: BackgroundWork): Boolean {
        if (work == BackgroundWork.USER_ASK) return true
        refresh()
        if (severe) return false
        return when {
            thermal.status() < PhoneThermal.MODERATE -> true
            // At MODERATE extraction is allowed only while the 30 s allowance is free (a query: [tryExtraction] takes it).
            else -> work == BackgroundWork.EXTRACTION && clock() - lastModerateExtractionMs >= MODERATE_EXTRACTION_GAP_MS
        }
    }

    /** Whether heat has latched SEVERE: live nudges pause with the background work (spec §P2.5). */
    @Synchronized fun severe(): Boolean { refresh(); return severe }

    /** Asks to start one non-user extraction call; at MODERATE it takes the 1-per-30-s allowance. */
    @Synchronized fun tryExtraction(): Boolean {
        if (!mayRunBackground(BackgroundWork.EXTRACTION)) return false
        if (thermal.status() < PhoneThermal.MODERATE) return true
        val now = clock()
        if (now - lastModerateExtractionMs < MODERATE_EXTRACTION_GAP_MS) return false
        lastModerateExtractionMs = now
        return true
    }

    /** Latches SEVERE; releases it once the status has stayed below MODERATE for 2 min. */
    private fun refresh() {
        val s = thermal.status()
        val now = clock()
        if (s >= PhoneThermal.SEVERE) { severe = true; belowSinceMs = -1L; return }
        if (!severe) return
        if (s >= PhoneThermal.MODERATE) { belowSinceMs = -1L; return }
        if (belowSinceMs < 0) belowSinceMs = now
        if (now - belowSinceMs >= RECOVERY_MS) { severe = false; belowSinceMs = -1L }
    }

    companion object {
        const val VECTOR_BATCH_MS = 60_000L
        const val MODERATE_EXTRACTION_GAP_MS = 30_000L
        const val RECOVERY_MS = 2 * 60_000L
    }
}
