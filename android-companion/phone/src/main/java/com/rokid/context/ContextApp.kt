package com.rokid.context

import com.rokid.context.core.log.PcLog
import android.app.Application
import com.rokid.context.ai.EnvDefaults

class ContextApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Always-on logging LGC2: the ONE phone log sink, installed first so every later component logs through PcLog's gate. Nothing here touches the
        // filesystem on the main thread: the path is built from dataDir (Context.getFilesDir() may stat or create the directory), the writer creates it,
        // and the Developer mode flag (SharedPreferences load) is applied on IO BEFORE the bootstrap ring drains. Delete all clears the files through the listener.
        com.rokid.context.phone.log.PhoneLogging.install(
            java.io.File(applicationInfo.dataDir, "files"), { com.rokid.context.phone.canvas.DeveloperModeStore.get(this).enabled },
            com.rokid.context.phone.log.PrefsWipePending(this),
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO),
        )?.let { com.rokid.context.memory.forget.ForgetListeners.register(com.rokid.context.phone.log.LogWipeListener(it.wiper, it.ready)) }
        // Fix wave I5: stamp the process start before any worker can claim a frame (the start release spares newer claims).
        com.rokid.context.work.frames.ProcessStart.wallMs
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
        // 3d: the canvas compiler's default window tolerance follows Settings > Capture > Window tolerance (new compiles only; a published plan holds its own number).
        val windowTolerance = com.rokid.context.phone.ui.settings.capture.WindowToleranceStore.get(this).pct
        com.rokid.context.ai.canvas.plan.WindowTolerance.provider = { windowTolerance.value }
        // v0.9.7 A: the canvas compiler and try-it read the disabled signals and derived definitions from Settings (new compiles only; a published plan is already expanded).
        com.rokid.context.memory.forget.ForgetListeners.register(com.rokid.context.phone.ui.settings.capture.SignalConfigStore.wipeListener(this)) // Delete all removes the user's signal configuration
        val signalConfig = com.rokid.context.phone.ui.settings.capture.SignalConfigStore.get(this).config
        com.rokid.context.ai.canvas.plan.SignalConfigs.provider = { signalConfig.value }
        com.rokid.context.ai.canvas.plan.SignalConfigs.historyProvider = { com.rokid.context.phone.ui.settings.capture.SignalConfigStore.get(this).history }
        // v0.9.8 D: delivery and the voice surfaces read the user's device capabilities (mic and display) from Settings; unset = everything on.
        val deviceCaps = com.rokid.context.phone.ui.settings.capture.DeviceCapabilityStore.get(this).capabilities
        com.rokid.context.orchestration.DeliveryEnvironment.capabilities = { deviceCaps.value }
        // The photo Forget radius follows Settings > Capture > Forget (default 10 s); the Forget path reads it once per Forget through ForgetRadius.ms().
        val timing = com.rokid.context.phone.ui.settings.capture.CaptureTimingStore.get(this)
        com.rokid.context.memory.forget.ForgetRadius.provider = { timing.forgetRadiusMs() }
        com.rokid.context.memory.forget.ForgetScopeSetting.provider = { timing.forgetScopeNow() } // the Forget scope (Photo | Moment | Scene), read when a Forget starts
        val devMode = com.rokid.context.phone.canvas.DeveloperModeStore.get(this).enabled
        com.rokid.context.memory.forget.ForgetScopeSetting.experimentalAllowed = { devMode.value } // the engine re-checks Developer mode at every Moment/Scene Forget
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
        // Canvas slice 3a start-up recovery (S58, S77): orphan files in canvas_tmp go, and a replay or sweep left RUNNING by a dead process is ABORTED.
        kotlin.concurrent.thread(name = "canvas-startup") {
            runCatching { kotlinx.coroutines.runBlocking { com.rokid.context.canvas.trace.CanvasStartup.run(this@ContextApp, com.rokid.context.memory.MemoryGraph.get(this@ContextApp).db) } }
                .onFailure { PcLog.w("ContextApp", "canvas start-up recovery failed; retries next start (${it.javaClass.simpleName})") }
        }
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
            }.onFailure { PcLog.w("ContextApp", "AI seed failed; will retry next start (${it.javaClass.simpleName})") }
        }
        // Keyframe capture P1: after the v1 -> v2 migration (the first DB use runs it), adopt pre-v2 frames once, then queue a
        // drain: every drain starts with the receipt reconciliation (startup pass), so half-finished receipts are finished.
        kotlin.concurrent.thread(name = "frame-adoption") {
            runCatching {
                kotlinx.coroutines.runBlocking { com.rokid.context.work.frames.FrameAdoption.run(this@ContextApp) }
                com.rokid.context.phone.hub.enqueueInboxIngest(this)
                // Startup: the pipeline starts, abandoned claims go back to PENDING and everything claimable is drained.
                com.rokid.context.work.frames.KeyframeGraph.get(this).start()
            }.onFailure { PcLog.w("ContextApp", "frame adoption failed; retries next start (${it.javaClass.simpleName})") }
        }
        // The Moment cadence and retention are scheduled on EVERY process start (not only when MainActivity runs with processing on): the cadence builds the MARK Moments of marks recorded by an older build.
        com.rokid.context.work.OrchestrationScheduler(this).ensureBackgroundWork()
        com.rokid.context.orchestration.LegacyImportWorker.enqueue(this)
        com.rokid.context.orchestration.EmbeddingWorker.enqueue(this) // daily canary check + any backlog
        registerActivityLifecycleCallbacks(com.rokid.context.phone.hub.HubActivityHook)
        // A hub that stopped itself (glasses away) may start again when the platform starts this process (a 15 min job) AND the app is exempt from battery optimization; never otherwise, never after a manual stop.
        // Codex review 4: only an event is submitted here; the claim, its commits and the start run on the AutoStop actor's IO context, never on the main thread.
        runCatching { com.rokid.context.phone.hub.AutoStopWake.processStarted(this) }
    }
}
