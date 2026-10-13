package com.rokid.context.phone.ui.status

import android.content.Context
import com.rokid.context.data.runtime.AppConfigStore
import com.rokid.context.work.OrchestrationScheduler
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The ⏸/▶ switch behind Home, Activity, the paused banner and the legacy Config screen (one source of truth). */
interface PipelineControl {
    val enabled: StateFlow<Boolean>
    fun setEnabled(enabled: Boolean)
}

/**
 * Phase 4: the legacy "pipeline enabled" flag + worker pause/resume. Phase 7 replaces it with PauseRecords (R-65).
 * Final-review I3: a tap only queues the change. The flag commits, the queue TTL freeze/extend (a rewrite of
 * `queue_runtime.log`) and the scheduler calls run on [io], one tap at a time in tap order; [enabled] is published
 * once the change is persisted.
 */
class LegacyPipelineControl(
    private val context: Context,
    private val scheduler: OrchestrationScheduler,
    private val io: CoroutineDispatcher = SERIAL_IO,
    private val scope: CoroutineScope = APP_SCOPE,
) : PipelineControl {
    // Plain prefs only: AppConfigStore.get also opens the Keystore-backed secrets (Threads constraint).
    private val state = MutableStateFlow(AppConfigStore.isPipelineEnabled(context))
    override val enabled: StateFlow<Boolean> = state.asStateFlow()

    override fun setEnabled(enabled: Boolean) {
        scope.launch(io) {
            // No cached-state early return: legacy MainActivity writes the flag/scheduler directly until Task 10,
            // and scheduling is idempotent, so always apply and then publish.
            AppConfigStore.setPipelineEnabled(context, enabled) // stamp / TTL freeze or extend / flag, in that order
            if (enabled) scheduler.resumePipeline() else scheduler.pausePipeline()
            state.value = AppConfigStore.isPipelineEnabled(context) // publish what was actually persisted
        }
    }

    private companion object {
        /** One at a time, FIFO: a quick pause-then-resume applies in tap order. */
        val SERIAL_IO: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)
        /** Process lifetime: leaving the screen never drops a tap half-applied. */
        val APP_SCOPE = CoroutineScope(SupervisorJob())
    }
}
