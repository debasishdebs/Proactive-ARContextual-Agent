package com.rokid.context.ai

import android.content.Context
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.prompt.AssetPromptSeeds
import com.rokid.context.prompt.BuiltInPromptSeeds
import com.rokid.context.prompt.InMemoryPersonaSettings
import com.rokid.context.prompt.InMemoryPromptSettings
import com.rokid.context.prompt.PersonaSettings
import com.rokid.context.prompt.PrefsPersonaSettings
import com.rokid.context.prompt.PrefsPromptSettings
import com.rokid.context.prompt.PromptRepository
import com.rokid.context.prompt.PromptSeeds
import com.rokid.context.prompt.PromptSettings

data class AiSettings(val prompts: PromptSettings, val persona: PersonaSettings) {
    companion object {
        fun inMemory() = AiSettings(InMemoryPromptSettings(), InMemoryPersonaSettings())
        fun prefs(context: Context) = AiSettings(PrefsPromptSettings(context), PrefsPersonaSettings(context))
    }
}

class AiGraph(
    memory: MemoryGraph,
    vault: KeyVault,
    seedState: SeedState,
    providers: ProviderBuilder = ProviderFactory,
    val settings: AiSettings = AiSettings.inMemory(),
    seeds: PromptSeeds = BuiltInPromptSeeds,
    /** Gaps G6: phone power for the on-device model gate; ungated unless the app passes the real reading. */
    power: () -> com.rokid.context.ai.embed.PowerState = { com.rokid.context.ai.embed.PowerState.UNGATED },
    /** Wraps every provider call by class (the call budget, P2); pass-through by default. */
    gate: com.rokid.context.model.CallGate = com.rokid.context.model.PassThroughGate,
    /** The roaming data saver ([com.rokid.context.ai.datasaver.DataSaverGraph] in the app); allows everything by default (tests). */
    dataGate: com.rokid.context.core.net.DataGate = com.rokid.context.core.net.DataGate.ALLOW_ALL,
) {
    val endpoints = EndpointRepository(memory.db.endpointDao(), vault)
    val bindings = RoleBindingRepository(memory.db.roleBindingDao(), endpoints)
    val router = ModelRouter(bindings, endpoints, providers, gate, dataGate)
    val tester = EndpointTester(endpoints, providers)
    val embedders = com.rokid.context.ai.embed.Embedders(endpoints, bindings, power = power)
    val retriever = com.rokid.context.memory.retrieval.MemoryRetriever(
        com.rokid.context.memory.retrieval.LexicalRetriever(memory.db.retrievalDao()),
        com.rokid.context.memory.retrieval.SemanticRetriever(memory.spaces, memory.vectorIndex, embedders).apply { embedBound = { bindings.get(Role.EMBED) != null } },
        memory.db.memoryViewDao(),
    )
    val seeder = EndpointSeeder(endpoints, bindings, seedState)
    val prompts = PromptRepository(memory.db.promptDao(), seeds, settings.prompts)
    val persona: PersonaSettings get() = settings.persona
    val runner = com.rokid.context.ai.run.RoleRunner(router, prompts, settings.persona)
    val testBed = com.rokid.context.ai.run.TestBed(router, prompts, settings.persona, bindings, endpoints)

    companion object {
        @Volatile private var instance: AiGraph? = null
        /** The call budget the app installs at startup (P2), before the first [get]; pass-through until then. */
        @Volatile var gate: com.rokid.context.model.CallGate = com.rokid.context.model.PassThroughGate
        fun get(context: Context): AiGraph = instance ?: synchronized(this) {
            val app = context.applicationContext
            instance ?: AiGraph(MemoryGraph.get(app), EncryptedKeyVault(app), PrefsSeedState(app), ProviderFactory, AiSettings.prefs(app), AssetPromptSeeds(app), power = { com.rokid.context.ai.embed.LocalModelPower.read(app) }, gate = gate, dataGate = com.rokid.context.ai.datasaver.DataSaverGraph.get(app))
                .also { instance = it }
        }
        fun resetForTests(graph: AiGraph?) { synchronized(this) { instance = graph } }
    }
}
