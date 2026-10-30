package com.rokid.context.phone.hub

import android.content.Context
import com.rokid.context.link.CxrGlassesLink
import com.rokid.context.phone.context.ContextSourceSettings

/** Reads the real inputs of [BackgroundPolicy] (settings, the pair, the sources, the hub's own activity). Cheap prefs reads; builds nothing. */
internal object BackgroundInputs {
    fun needs(app: Context): BackgroundPolicy.Needs {
        val sources = ContextSourceSettings.of(app)
        return BackgroundPolicy.Needs(
            enabled = KeepRunningNearby.of(app).enabled(),
            paired = CxrGlassesLink.isPaired(app),
            contextSourceOn = BackgroundPolicy.CONTEXT_SOURCE_IDS.any(sources::enabled),
            activeWork = HubGraph.busy(),
        )
    }

    fun hubNeeded(app: Context): Boolean = BackgroundPolicy.hubNeeded(needs(app))
}
