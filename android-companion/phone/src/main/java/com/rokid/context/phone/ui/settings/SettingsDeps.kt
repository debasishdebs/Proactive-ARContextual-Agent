package com.rokid.context.phone.ui.settings

import android.content.Context
import android.util.Log
import com.rokid.context.ai.AiGraph
import com.rokid.context.core.model.HudSettings
import com.rokid.context.link.AuthActivity
import com.rokid.context.phone.hub.Hub
import com.rokid.context.phone.hub.HubGraph
import kotlinx.coroutines.flow.StateFlow

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
) {
    val ai: AiGraph get() = lazyAi.value

    companion object {
        fun create(app: Context): SettingsDeps = SettingsDeps(
            lazyAi = lazy { AiGraph.get(app) },
            hud = HubHudSettings(HubGraph.get(app)),
            reauthorize = { AuthActivity.launch(app).onFailure { Log.i("Settings", "re-authorize failed (${it.javaClass.simpleName})") } },
            onEmbedBindingChanged = { com.rokid.context.orchestration.EmbeddingWorker.enqueue(app) },
        )
    }
}
