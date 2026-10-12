package com.rokid.context.phone.hub

import android.content.Context
import com.rokid.context.core.model.GlassesStatusSource
import com.rokid.context.data.frames.RokidFrameInbox
import com.rokid.context.data.runtime.AppConfigStore
import com.rokid.context.link.CxrGlassesLink
import com.rokid.context.link.FrameAdmission
import com.rokid.context.link.FrameReassembler
import com.rokid.context.link.ReceivedFrameLedger
import com.rokid.context.memory.MemoryGraph
import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

/** Process-wide hub (no DI in v1). `ContextApp` has already initialised [MemoryGraph]. */
object HubGraph {
    @Volatile private var hub: Hub? = null

    fun get(context: Context): Hub = hub ?: synchronized(this) { hub ?: build(context.applicationContext).also { hub = it } }

    /** Phase 3 → Phase 4 contract: the real glasses status for the phone UI. */
    fun glassesStatus(context: Context): GlassesStatusSource = get(context)

    internal val isBuilt: Boolean get() = hub != null

    fun resetForTests(h: Hub?) { synchronized(this) { hub = h } }

    private fun build(app: Context): Hub {
        // Last line of defence: Hub guards every event, but nothing on this scope may ever kill the process.
        val guard = CoroutineExceptionHandler { _, t -> Log.w(Hub.TAG, "hub coroutine failed: ${t.javaClass.simpleName}") }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + guard)
        val ledger = ReceivedFrameLedger(File(app.filesDir, "cxr_received_frames.txt"))
        val pauses = HubPauses(MemoryGraph.get(app).db.pauseDao())
        val ingestor = FrameIngestor(RokidFrameInbox.inboxDir(app), FrameReassembler(), FrameAdmission(ledger, pauses), ledger) { enqueueInboxIngest(app) }
        val settings = HubSettingsStore(app.getSharedPreferences("hub_settings", Context.MODE_PRIVATE)) { AppConfigStore.glassesSilencyMode(app) }
        return Hub(scope, CxrGlassesLink(app, scope), pauses, settings, ingestor, LegacyHubSources(app))
    }
}
