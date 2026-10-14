package com.rokid.context

import android.app.Application
import com.rokid.context.ai.EnvDefaults

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
        // One call budget for every provider call (P2); its period follows Settings > Capture > AI call pace (10 / 12 / 15 s).
        val pace = com.rokid.context.phone.ui.settings.capture.AiCallPaceStore.get(this).paceMs
        com.rokid.context.ai.AiGraph.gate = com.rokid.context.work.budget.CallBudget(clock = System::currentTimeMillis, periodMs = { pace.value })
        // Phone power policy inputs (spec P2.5): the real thermal status and whether the app is in the foreground.
        com.rokid.context.work.frames.KeyframeGraph.thermal = com.rokid.context.work.frames.KeyframeGraph.systemThermal(this)
        com.rokid.context.work.frames.KeyframeGraph.foreground = {
            androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
        }
        com.rokid.context.memory.MemoryGraph.get(this) { com.rokid.context.data.runtime.AppConfigStore.getWithoutSecret(this).encryptionEnabled }
        // Gaps G1: frame files follow "Encrypt memory"; every reader opens plain and sealed ones. The worker finishes any
        // conversion a toggle or a process death left part-way (a no-op when every file is already in the wanted form).
        com.rokid.context.phone.FrameReaders.install(this)
        com.rokid.context.orchestration.FrameCryptoWorker.enqueue(this)
        // Eager background import so it is normally done before first use; PipelineRecords calls wait only if it is still running.
        kotlin.concurrent.thread(name = "legacy-import") {
            // Never throws (P8 D5): a failure is logged inside and retried by LegacyImportWorker.
            com.rokid.context.memory.MemoryGraph.ensureLegacyImported(this)
        }
        // Resumes a partial seed on every start until done. The seeder logs its own (key-free) failure line and leaves
        // roles unbound; this guard only catches setup failures (e.g. Keystore) so they can never crash the app.
        kotlin.concurrent.thread(name = "ai-seed") {
            runCatching {
                kotlinx.coroutines.runBlocking {
                    // Seeds, then clears the legacy key only once it reached KeyVault (F23).
                    com.rokid.context.orchestration.LegacyKeySeed.run(this@ContextApp, com.rokid.context.ai.AiGraph.get(this@ContextApp).seeder)
                }
            }.onFailure { android.util.Log.w("ContextApp", "AI seed failed; will retry next start (${it.javaClass.simpleName})") }
        }
        // Keyframe capture P1: after the v1 -> v2 migration (the first DB use runs it), adopt pre-v2 frames once, then queue a
        // drain: every drain starts with the receipt reconciliation (startup pass), so half-finished receipts are finished.
        kotlin.concurrent.thread(name = "frame-adoption") {
            runCatching {
                kotlinx.coroutines.runBlocking { com.rokid.context.work.frames.FrameAdoption.run(this@ContextApp) }
                com.rokid.context.phone.hub.enqueueInboxIngest(this)
                // Startup: the pipeline starts, abandoned claims go back to PENDING and everything claimable is drained.
                com.rokid.context.work.frames.KeyframeGraph.get(this).start()
            }.onFailure { android.util.Log.w("ContextApp", "frame adoption failed; retries next start (${it.javaClass.simpleName})") }
        }
        com.rokid.context.orchestration.LegacyImportWorker.enqueue(this)
        com.rokid.context.orchestration.EmbeddingWorker.enqueue(this) // daily canary check + any backlog
        registerActivityLifecycleCallbacks(com.rokid.context.phone.hub.HubActivityHook)
    }
}
