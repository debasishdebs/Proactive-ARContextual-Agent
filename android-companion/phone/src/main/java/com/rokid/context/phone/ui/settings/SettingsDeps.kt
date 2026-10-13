package com.rokid.context.phone.ui.settings

import android.content.Context
import android.util.Log
import com.rokid.context.ai.AiGraph
import com.rokid.context.core.model.HudSettings
import com.rokid.context.link.AuthActivity
import com.rokid.context.memory.EmbeddingSpaces
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.retrieval.RetrievalSettingsStore
import com.rokid.context.orchestration.AnswerService
import com.rokid.context.orchestration.TestBedInputs
import com.rokid.context.phone.hub.Hub
import com.rokid.context.phone.hub.HubGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** The HUD settings the phone owns (spec §2.4 Devices / Capture): persisted and pushed by the hub. */
interface HudSettingsControl {
    val settings: StateFlow<HudSettings>
    fun update(transform: (HudSettings) -> HudSettings)
}

class HubHudSettings(private val hub: Hub) : HudSettingsControl {
    override val settings: StateFlow<HudSettings> get() = hub.hudSettings
    override fun update(transform: (HudSettings) -> HudSettings) = hub.updateSettings(transform)
}

/** Everything the Settings screens need (no DI in v1). Only this file may reach for process-wide graphs. */
class SettingsDeps(
    /** Built on first use by a Settings screen, not when the shell builds these deps on Main (its prompt/persona settings read prefs). */
    private val lazyAi: Lazy<AiGraph>,
    val hud: HudSettingsControl,
    val reauthorize: () -> Unit,
    val onEmbedBindingChanged: () -> Unit,
    /** Lazy for the same reason as [lazyAi]: building it reaches AiGraph and the memory database. */
    private val lazyTestInputs: Lazy<TestBedInputs>,
    /** Lazy for the same reason as [lazyAi]: building them reads prefs / reaches the memory database. */
    private val lazyRetrieval: Lazy<RetrievalSettingsStore>,
    private val lazySpaces: Lazy<EmbeddingSpaces>,
    /** The user's Rebuild: durable at once (pending while paused), resets the drift streak (Task 11). */
    val rebuildIndex: () -> Unit,
) {
    val ai: AiGraph get() = lazyAi.value
    val testInputs: TestBedInputs get() = lazyTestInputs.value
    val retrieval: RetrievalSettingsStore get() = lazyRetrieval.value
    val spaces: EmbeddingSpaces get() = lazySpaces.value

    companion object {
        /** Process-lifetime work the Settings deps start (a Rebuild commits prefs, so it leaves Main). */
        private val scope = CoroutineScope(SupervisorJob())

        fun create(app: Context): SettingsDeps = SettingsDeps(
            lazyAi = lazy { AiGraph.get(app) },
            hud = HubHudSettings(HubGraph.get(app)),
            reauthorize = { AuthActivity.launch(app).onFailure { Log.i("Settings", "re-authorize failed (${it.javaClass.simpleName})") } },
            onEmbedBindingChanged = { com.rokid.context.orchestration.EmbeddingWorker.enqueue(app) },
            lazyTestInputs = lazy { TestBedInputs(MemoryGraph.get(app), AnswerService.forApp(app)) },
            lazyRetrieval = lazy { RetrievalSettingsStore.get(app) },
            lazySpaces = lazy { MemoryGraph.get(app).spaces },
            rebuildIndex = { scope.launch(Dispatchers.IO) { com.rokid.context.orchestration.EmbeddingWorker.enqueue(app, rebuild = true) } },
        )
    }
}
