package com.rokid.context.phone.ui.status

import android.content.Context
import com.rokid.context.data.runtime.AppConfigStore
import com.rokid.context.phone.hub.HubGraph
import com.rokid.context.phone.hub.HubSettingsStore
import com.rokid.context.work.OrchestrationScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

/**
 * The phone's capture pause control (R-65): PauseRecords through the hub. One per process, shared by PhoneDeps and the
 * hub notification, so a Resume from either feeds the same "Still paused on glasses" prompt.
 */
object CapturePauses {
    @Volatile private var control: PipelineControl? = null
    /** Process lifetime: leaving a screen never drops a tap half-applied. */
    private val scope = CoroutineScope(SupervisorJob())

    fun control(app: Context): PipelineControl = control ?: synchronized(this) {
        control ?: build(app.applicationContext).also { control = it }
    }

    private fun build(app: Context): PipelineControl =
        PausePipelineControl(HubGraph.get(app), scope, legacyFlag = PrefsLegacyFlag(app, OrchestrationScheduler(app)))
}

/** The legacy switch and the `pause_migrated_v7` marker (in the `hub_settings` prefs). Called on IO only. */
private class PrefsLegacyFlag(private val app: Context, private val scheduler: OrchestrationScheduler) : LegacyFlag {
    private val prefs get() = app.getSharedPreferences(HubSettingsStore.PREFS, Context.MODE_PRIVATE)
    override fun migrated(): Boolean = HubSettingsStore.pauseMigrated(prefs)
    override fun markMigrated() = HubSettingsStore.markPauseMigrated(prefs)
    override fun pipelineEnabled(): Boolean = AppConfigStore.isPipelineEnabled(app)
    override fun enablePipeline() {
        AppConfigStore.setPipelineEnabled(app, true)
        scheduler.resumePipeline()
    }
}
