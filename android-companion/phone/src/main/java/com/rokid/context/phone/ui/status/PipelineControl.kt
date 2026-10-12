package com.rokid.context.phone.ui.status

import android.content.Context
import com.rokid.context.data.runtime.AppConfigStore
import com.rokid.context.work.OrchestrationScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The ⏸/▶ switch behind Home, Activity, the paused banner and the legacy Config screen (one source of truth). */
interface PipelineControl {
    val enabled: StateFlow<Boolean>
    fun setEnabled(enabled: Boolean)
}

/** Phase 4: the legacy "pipeline enabled" flag + worker pause/resume. Phase 7 replaces it with PauseRecords (R-65). */
class LegacyPipelineControl(private val context: Context, private val scheduler: OrchestrationScheduler) : PipelineControl {
    private val state = MutableStateFlow(AppConfigStore.get(context).pipelineEnabled)
    override val enabled: StateFlow<Boolean> = state.asStateFlow()

    override fun setEnabled(enabled: Boolean) {
        if (state.value == enabled) return
        AppConfigStore.setPipelineEnabled(context, enabled)
        if (enabled) scheduler.resumePipeline() else scheduler.pausePipeline()
        state.value = enabled
    }
}
