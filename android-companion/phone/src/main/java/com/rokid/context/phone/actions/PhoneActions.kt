package com.rokid.context.phone.actions

import android.content.Context
import com.rokid.context.connectors.actions.ActionsGraph
import com.rokid.context.core.model.HudSettings
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.phone.hub.HubActions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** The phone side of actions (R-62): installs the engine with the HUD verbosity and builds the hub port. */
object PhoneActions {
    fun hubPort(app: Context, hud: () -> HudSettings): HubActions {
        ActionsGraph.install(app) { hud().verbosity }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val engine = ActionsGraph.get(app).engine
        return HubActionsImpl(engine, MemoryGraph.get(app).proposals, scope)
    }
}
