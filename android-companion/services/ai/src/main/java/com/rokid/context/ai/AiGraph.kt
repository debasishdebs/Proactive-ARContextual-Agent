package com.rokid.context.ai

import android.content.Context
import com.rokid.context.memory.MemoryGraph

class AiGraph(memory: MemoryGraph, vault: KeyVault, seedState: SeedState, providers: ProviderBuilder = ProviderFactory) {
    val endpoints = EndpointRepository(memory.db.endpointDao(), vault)
    val bindings = RoleBindingRepository(memory.db.roleBindingDao(), endpoints)
    val router = ModelRouter(bindings, endpoints, providers)
    val tester = EndpointTester(endpoints, providers)
    val seeder = EndpointSeeder(endpoints, bindings, seedState)

    companion object {
        @Volatile private var instance: AiGraph? = null
        fun get(context: Context): AiGraph = instance ?: synchronized(this) {
            val app = context.applicationContext
            instance ?: AiGraph(MemoryGraph.get(app), EncryptedKeyVault(app), PrefsSeedState(app)).also { instance = it }
        }
        fun resetForTests(graph: AiGraph?) { synchronized(this) { instance = graph } }
    }
}
