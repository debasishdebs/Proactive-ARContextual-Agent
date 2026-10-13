package com.rokid.context.phone.ui.status

import android.content.Context
import com.rokid.context.work.OrchestrationScheduler

/** The phone's capture pause control (R-65). Placeholder (Stream 0): today's legacy pipeline flag. Stream A replaces it. */
object CapturePauses {
    fun control(app: Context): PipelineControl = LegacyPipelineControl(app, OrchestrationScheduler(app))
}
