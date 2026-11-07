package com.rokid.context.phone.hub

import android.content.Context
import com.rokid.context.link.CxrGlassesLink

/** Reads the real inputs of [BackgroundPolicy] (settings, the pair, the sources, the hub's own activity). Cheap prefs reads; builds nothing. */
internal object BackgroundInputs {
    fun needs(app: Context): BackgroundPolicy.Needs {
        // The AutoStop actor's committed state. Until it is loaded (on IO, never here on the caller's thread) the safe "keep running" values apply.
        val engine = AutoStopWake.engine(app)
        val store = engine.visible() ?: AutoStopSnapshot().also { engine.warm() }
        return BackgroundPolicy.Needs(
            enabled = KeepRunningNearby.of(app).enabled(),
            paired = CxrGlassesLink.isPaired(app),
            activeWork = HubGraph.busy(),
            autoStopped = store.autoStoppedAtMs != 0L,
            manualStop = store.manualStop,
        )
    }

    fun hubNeeded(app: Context): Boolean = BackgroundPolicy.hubNeeded(needs(app))
}
