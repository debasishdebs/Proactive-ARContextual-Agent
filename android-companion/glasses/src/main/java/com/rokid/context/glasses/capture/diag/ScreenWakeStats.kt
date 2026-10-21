package com.rokid.context.glasses.capture.diag

/**
 * Screen-wake attribution (battery wave A3, spec §9): how often the display turned on, and how often that followed our
 * own UI (pop-up, Confirm launch, fallback launch) within [OURS_WINDOW_MS]. Counts only; reset by [take].
 */
class ScreenWakeStats(private val clock: () -> Long) {
    private var lastOurUiMs = Long.MIN_VALUE
    private var on = 0
    private var ours = 0
    private var nudgeOff = 0

    @Synchronized fun onOurUi() { lastOurUiMs = clock() }

    @Synchronized fun onScreenOn() {
        on++
        if (lastOurUiMs != Long.MIN_VALUE && clock() - lastOurUiMs in 0..OURS_WINDOW_MS) ours++
    }

    @Synchronized fun onNudgeSkipped() { nudgeOff++ }

    /** (scrOn, scrOurs, nudgeOff) since the last call; resets them. */
    @Synchronized fun take(): Triple<Int, Int, Int> = Triple(on, ours, nudgeOff).also { on = 0; ours = 0; nudgeOff = 0 }

    companion object { const val OURS_WINDOW_MS = 2_000L }
}
