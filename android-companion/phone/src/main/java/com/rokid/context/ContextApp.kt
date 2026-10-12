package com.rokid.context

import android.app.Application
import com.rokid.context.ai.EnvDefaults
import com.rokid.context.data.runtime.toProviderSettings

class ContextApp : Application() {
    override fun onCreate() {
        super.onCreate()
        EnvDefaults.init(
            azureEndpoint = BuildConfig.AZURE_KG_ENDPOINT,
            azureDeployment = BuildConfig.AZURE_KG_DEPLOYMENT,
            ollamaBaseUrl = BuildConfig.OLLAMA_BASE_URL,
            ollamaModel = BuildConfig.OLLAMA_MODEL,
            localRuntimeMode = BuildConfig.LOCAL_RUNTIME_MODE,
            localNativeModelPath = BuildConfig.LOCAL_NATIVE_MODEL_PATH,
        )
        com.rokid.context.memory.MemoryGraph.get(this) { com.rokid.context.data.runtime.AppConfigStore.get(this).encryptionEnabled }
        // Eager background import so it is normally done before first use; PipelineRecords calls wait only if it is still running.
        kotlin.concurrent.thread(name = "legacy-import") {
            runCatching { com.rokid.context.memory.MemoryGraph.ensureLegacyImported(this) }
                .onFailure { android.util.Log.w("ContextApp", "legacy import failed; will retry: ${com.rokid.context.ai.SafeLog.describe(it)}") }
        }
        // Resumes a partial seed on every start until done. The seeder logs its own (key-free) failure line and leaves
        // roles unbound; this guard only catches setup failures (e.g. Keystore) so they can never crash the app.
        kotlin.concurrent.thread(name = "ai-seed") {
            runCatching {
                kotlinx.coroutines.runBlocking {
                    com.rokid.context.ai.AiGraph.get(this@ContextApp).seeder
                        .seedIfNeeded(com.rokid.context.data.runtime.AppConfigStore.get(this@ContextApp).toProviderSettings())
                }
            }.onFailure { android.util.Log.w("ContextApp", "AI seed failed; will retry next start (${it.javaClass.simpleName})") }
        }
        com.rokid.context.orchestration.LegacyImportWorker.enqueue(this)
        com.rokid.context.orchestration.EmbeddingWorker.enqueue(this) // daily canary check + any backlog
        registerActivityLifecycleCallbacks(com.rokid.context.phone.hub.HubActivityHook)
    }
}
