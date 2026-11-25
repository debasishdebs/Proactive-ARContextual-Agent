package com.rokid.context.canvas.window

/**
 * Canvas v2.1: the source of a Test run and the fail-closed policy that admits it. PURE. Every Test run is a dry-run: no send, no delivery, no capture-plan publish, no write to user data (design section 1.7).
 * Refusal reasons are fixed tokens (they are logged and shown; never an id, text or coordinate).
 */
sealed interface TestRunSource {
    data class Window(val fromMs: Long, val toMs: Long, val mode: PickMode, val n: Int, val seed: String?) : TestRunSource
    data class Trace(val traceId: String, val fromMs: Long, val toMs: Long, val mode: PickMode, val n: Int, val seed: String?) : TestRunSource
    data class Tap(val maxMin: Int, val maxFrames: Int, val maxPhotosSent: Int) : TestRunSource
}

object TestRunPolicy {
    const val MAX_WINDOW_MS = 24L * 3_600_000L

    /** What the caller knows about the world when it asks. */
    data class Ctx(
        val flagOn: Boolean,
        val developerMode: Boolean,
        /** Another run (or tap) holds the runner. */
        val busy: Boolean,
        val offlineOnly: Boolean,
        /** The pipeline has a node that sends a photo to an online model. */
        val hasOnlineVision: Boolean,
        val hasPyFn: Boolean,
        /** The window overlaps a pause zone the fence still holds (fails closed when unknown). */
        val zoneHeldOrUnknown: Boolean,
    )

    /** Null = admitted; else the first fixed refusal token. */
    fun refusal(src: TestRunSource, c: Ctx): String? {
        if (!c.flagOn || !c.developerMode) return "test_run_off"
        if (c.busy) return "busy"
        if (c.offlineOnly && c.hasOnlineVision) return "offline_only"
        when (src) {
            is TestRunSource.Window -> windowRefusal(src.fromMs, src.toMs, src.n)?.let { return it }
            is TestRunSource.Trace -> windowRefusal(src.fromMs, src.toMs, src.n)?.let { return it }
            is TestRunSource.Tap -> {
                if (c.hasPyFn) return "tap_py_fn"
                if (src.maxMin !in 1..15 || src.maxFrames !in 1..WindowFramePicker.MAX_PHOTOS || src.maxPhotosSent < 0 || src.maxPhotosSent > src.maxFrames) return "bad_bounds"
                if (c.hasOnlineVision && src.maxPhotosSent == 0) return "tap_no_photo_budget"
            }
        }
        if (c.zoneHeldOrUnknown) return "zone_held"
        return null
    }

    private fun windowRefusal(from: Long, to: Long, n: Int): String? {
        if (to <= from) return "bad_window"
        if (to - from > MAX_WINDOW_MS) return "window_too_long"
        if (n < 1 || n > WindowFramePicker.MAX_PHOTOS) return "bad_count"
        return null
    }
}
