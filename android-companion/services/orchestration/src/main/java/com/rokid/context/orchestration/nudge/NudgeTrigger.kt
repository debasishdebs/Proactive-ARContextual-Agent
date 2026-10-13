package com.rokid.context.orchestration.nudge

import android.content.Context

/** Called after each summarizer pass (trusted summary or reprocess). Placeholder (Stream 0): Stream C schedules the NudgeEngine. */
object NudgeTrigger {
    @Suppress("UNUSED_PARAMETER")
    fun afterSummarizerPass(context: Context) = Unit
}
