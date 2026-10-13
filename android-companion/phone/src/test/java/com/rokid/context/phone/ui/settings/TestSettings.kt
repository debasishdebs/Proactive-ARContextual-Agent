package com.rokid.context.phone.ui.settings

import android.content.Context
import com.rokid.context.ai.AiGraph
import com.rokid.context.ai.InMemoryKeyVault
import com.rokid.context.ai.InMemorySeedState
import com.rokid.context.ai.ProviderBuilder
import com.rokid.context.core.model.HudSettings
import com.rokid.context.memory.DbEncryption
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.MemorySettingsStore
import com.rokid.context.memory.retrieval.RetrievalSettings
import com.rokid.context.memory.retrieval.RetrievalSettingsStore
import com.rokid.context.model.ModelProvider
import com.rokid.context.orchestration.AnswerService
import com.rokid.context.orchestration.TestBedInputs
import com.rokid.context.phone.ui.settings.memory.EncryptionControl
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class FakeHudSettings(initial: HudSettings = HudSettings()) : HudSettingsControl {
    private val state = MutableStateFlow(initial)
    override val settings: StateFlow<HudSettings> = state.asStateFlow()
    override fun update(transform: (HudSettings) -> HudSettings) { state.value = transform(state.value) }
}

/** Settings dependencies over the test MemoryGraph; every model call returns [reply]. */
object TestSettings {
    var reauthorized = 0
    var embedChanges = 0
    var rebuilds = 0

    fun ai(ctx: Context, reply: suspend (String) -> String = { "OK" }): AiGraph =
        AiGraph(MemoryGraph.get(ctx), InMemoryKeyVault(), InMemorySeedState(), ProviderBuilder { _, _, _ ->
            object : ModelProvider { override suspend fun generate(prompt: String): String = reply(prompt) }
        })

    fun deps(ctx: Context, ai: AiGraph = ai(ctx), hud: HudSettingsControl = FakeHudSettings()): SettingsDeps =
        SettingsDeps(lazyAi = lazyOf(ai), hud = hud, reauthorize = { reauthorized++ }, onEmbedBindingChanged = { embedChanges++ },
            lazyTestInputs = lazy { TestBedInputs(MemoryGraph.get(ctx), AnswerService(MemoryGraph.get(ctx), ai.runner, ai.retriever, { RetrievalSettings() })) },
            lazyRetrieval = lazy { RetrievalSettingsStore(ctx.getSharedPreferences("retrieval_test", 0)) }, lazySpaces = lazy { MemoryGraph.get(ctx).spaces }, rebuildIndex = { rebuilds++ },
            lazyMemorySettings = lazy { MemorySettingsStore(ctx.getSharedPreferences("memory_settings_test_deps", 0)) },
            encryption = object : EncryptionControl { override fun status() = DbEncryption.Status(false, null, false); override fun request(encrypted: Boolean) = Unit },
            usedBytes = { 0L }, wipe = {}, export = { java.io.File(ctx.cacheDir, "x.json") }, share = {})
}
