package com.rokid.context

import android.app.Application
import com.rokid.context.ai.EnvDefaults

class ContextApp : Application() {
    override fun onCreate() {
        super.onCreate()
        EnvDefaults.init(
            azureEndpoint = BuildConfig.AZURE_KG_ENDPOINT,
            azureApiKey = BuildConfig.AZURE_KG_API_KEY,
            azureDeployment = BuildConfig.AZURE_KG_DEPLOYMENT,
            ollamaBaseUrl = BuildConfig.OLLAMA_BASE_URL,
            ollamaModel = BuildConfig.OLLAMA_MODEL,
            localRuntimeMode = BuildConfig.LOCAL_RUNTIME_MODE,
            localNativeModelPath = BuildConfig.LOCAL_NATIVE_MODEL_PATH,
        )
        com.rokid.context.memory.MemoryGraph.get(this) { com.rokid.context.data.runtime.AppConfigStore.get(this).encryptionEnabled }
        // Eager background import so it is normally done before any facade use; facades block only if it is still running.
        kotlin.concurrent.thread(name = "legacy-import") { com.rokid.context.memory.MemoryGraph.ensureLegacyImported(this) }
        com.rokid.context.orchestration.LegacyImportWorker.enqueue(this)
    }
}
