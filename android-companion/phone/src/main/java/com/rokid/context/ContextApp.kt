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
            java.io.File(applicationInfo.dataDir, "files"), { com.rokid.context.phone.canvas.DeveloperModeStore.get(this).diagnosticLog },
            com.rokid.context.phone.log.PrefsWipePending(this),
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO),
        )?.let { com.rokid.context.memory.forget.ForgetListeners.register(com.rokid.context.phone.log.LogWipeListener(it.wiper, it.ready)) }
        // Seed estimates and the per-seed cell cap follow the Developer flag `bulkSeed` (default ON): bulk numbers and the 30,000-cell cap, else the per-cell ones.
        com.rokid.context.phone.ui.settings.privacy.zones.SeedEstimate.bulkMode = { com.rokid.context.orchestration.forget.DeveloperFlags.bulkSeed(this) }
        // Model privacy (Offline only): the DNS guard of every model client reads the stored mode; the first load of the prefs files happens on IO, never here.
        com.rokid.context.ai.OfflineOnlyDns.globalMode = { com.rokid.context.ai.resolve.ModelPrivacyStore.get(this).current() }
        com.rokid.context.ai.OfflineOnlyNet.vpnProbe = com.rokid.context.ai.OfflineOnlyNet.androidVpnProbe(this) // Offline only refuses non-loopback private addresses while a VPN is up
        Thread { try { com.rokid.context.ai.resolve.ModelPrivacyStore.get(this).warm() } catch (_: Exception) { } }.start()
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
        // Codex review 5: the preference-backed stores below are read lazily (first use, normally on a worker) and warmed on a background thread, never loaded here.
        com.rokid.context.ai.AiGraph.gate = com.rokid.context.work.budget.CallBudget(clock = System::currentTimeMillis, periodMs = { com.rokid.context.phone.ui.settings.capture.AiCallPaceStore.get(this).paceMs.value })
        // 2026-11-16: on-device index work runs above the battery level set in Settings > Capture > AI (default 50 %); read lazily like the call pace.
        com.rokid.context.ai.embed.LocalModelPower.minBatteryPct = { com.rokid.context.phone.ui.settings.capture.IndexBatteryStore.get(this).pct.value }
        // 3d: the canvas compiler's default window tolerance follows Settings > Capture > Window tolerance (new compiles only; a published plan holds its own number).
        com.rokid.context.ai.canvas.plan.WindowTolerance.provider = { com.rokid.context.phone.ui.settings.capture.WindowToleranceStore.get(this).pct.value }
        // v0.9.7 A: the canvas compiler and try-it read the disabled signals and derived definitions from Settings (new compiles only; a published plan is already expanded).
        com.rokid.context.memory.forget.ForgetListeners.register(com.rokid.context.phone.ui.settings.capture.SignalConfigStore.wipeListener(this)) // Delete all removes the user's signal configuration
        com.rokid.context.ai.canvas.plan.SignalConfigs.provider = { com.rokid.context.phone.ui.settings.capture.SignalConfigStore.get(this).config.value }
        com.rokid.context.ai.canvas.plan.SignalConfigs.historyProvider = { com.rokid.context.phone.ui.settings.capture.SignalConfigStore.get(this).history }
        com.rokid.context.memory.forget.ForgetListeners.register(com.rokid.context.phone.place.SeedWipeListener(this)) // Delete all cancels the queued and running place seeds
        com.rokid.context.memory.forget.ForgetListeners.register(com.rokid.context.phone.ui.settings.privacy.zones.online.OnlineMapGraph.wipeListener()) // Delete all resets the street-map switch and consent in memory
        // v3.0 proactive loop (SHADOW MODE, switches OFF by default): the seams only the phone knows, and the Forget / Delete all hook that withdraws records whose sources are gone. Every default fails closed.
        com.rokid.context.memory.forget.ForgetListeners.register(com.rokid.context.orchestration.proactive.ProactiveGraph.forgetListener(this))
        com.rokid.context.memory.forget.ForgetListeners.register(com.rokid.context.work.frames.place.PinMomentLinks.listener(this)) // slice 1.4.2: an expired Moment's pin link is cleared (the pin stays); explicit Forgets delete the pin through PinMomentLinks.explicit
        com.rokid.context.memory.forget.ForgetListeners.register(com.rokid.context.work.frames.place.ShadowCaches.listener()) // 1.4.4 B1: every Forget and Delete all empties the Place Guide shadow's in-memory vectors (pin reference photos) at once
        com.rokid.context.orchestration.proactive.ProactiveEnvironment.worn = { runCatching { com.rokid.context.phone.hub.HubGraph.get(this).wornNow() == true }.getOrDefault(false) }
        // v3.0 T14 / ruling 10: the driving state comes from signals the context hub already collects (see DrivingSignal): NOT_DRIVING only on two independent fresh calm readings (one of them moving) with no vehicle suspicion for 10 min, on a monotonic clock; the decision and the write are one atomic step; DRIVING and UNKNOWN both drop. Read fresh at every gate and again at commit; a throw is a drop.
        com.rokid.context.orchestration.proactive.ProactiveEnvironment.driving = { com.rokid.context.orchestration.proactive.DrivingSignal.blocks() }
        com.rokid.context.orchestration.proactive.ProactiveEnvironment.drivingVersion = { com.rokid.context.orchestration.proactive.DrivingSignal.version() }
        com.rokid.context.orchestration.proactive.ProactiveEnvironment.drivingCommit = { v, w -> com.rokid.context.orchestration.proactive.DrivingSignal.commitIfNotDriving(v, w) }
        // v3.1 r1: the pause set WITHOUT suspending, read inside the delivery's fences (the hub's live pauses, including quiet hours and zone holds, and Battery Saver); a throw is a pause.
        com.rokid.context.orchestration.proactive.ProactiveEnvironment.pausedNow = { runCatching { !com.rokid.context.phone.hub.HubGraph.get(this).captureRunning() || com.rokid.context.orchestration.BatterySaver.isOn(this) }.getOrDefault(true) }
        com.rokid.context.orchestration.proactive.ProactiveEnvironment.drivingAssessment = { com.rokid.context.orchestration.proactive.DrivingSignal.assess() }
        com.rokid.context.orchestration.proactive.ProactiveEnvironment.drivingSignalAvailable = true
        // a pin deleted or edited in Settings withdraws the suggestions built from it at once (it is no Forget event)
        com.rokid.context.orchestration.proactive.ProactiveGraph.registerPinHook(this)
        com.rokid.context.orchestration.proactive.ProactiveEnvironment.zones = {
            runCatching { com.rokid.context.phone.zone.ZoneStore(getSharedPreferences(com.rokid.context.phone.zone.ZoneStore.PREFS, MODE_PRIVATE)).let { st -> val z = st.zones(); if (st.corrupt) null else z } }.getOrNull()
        }
        com.rokid.context.orchestration.proactive.ProactiveEnvironment.nudgeCap = { runCatching { com.rokid.context.connectors.sources.SourcesGraph.get(this).settings.nudgesPerDay.value }.getOrDefault(0) }
        // v3.1 delivery (every switch OFF by default): the live surfaces (HUD overlay through the hub, Nexus through its feeder and sink) and who the HUD host is.
        com.rokid.context.orchestration.proactive.ProactiveEnvironment.nexusHost = { runCatching { com.rokid.context.phone.nexus.NexusHostStore.of(this).current() == com.rokid.context.core.model.HudHost.NEXUS }.getOrDefault(false) }
        com.rokid.context.orchestration.proactive.SuggestionWithdrawal.install() // v3.1 r3: driving closes / quiet hours begin withdraw every displayed suggestion (HUD holder, Nexus) even when Home is absent
        com.rokid.context.orchestration.proactive.ProactiveEnvironment.surfaces = com.rokid.context.phone.hub.PhoneSuggestionSurfaces(com.rokid.context.phone.hub.SuggestionHud.holder,
            nexus = com.rokid.context.phone.nexus.NexusGraph::suggestion, withdrawNexus = com.rokid.context.phone.nexus.NexusGraph::withdrawSuggestions, withdrawAllNexus = com.rokid.context.phone.nexus.NexusGraph::withdrawAllSuggestions)
        // v0.9.8 D: delivery and the voice surfaces read the user's device capabilities (mic and display) from Settings; unset = everything on.
        com.rokid.context.orchestration.DeliveryEnvironment.capabilities = { com.rokid.context.phone.ui.settings.capture.DeviceCapabilityStore.get(this).capabilities.value }
        // The photo Forget radius follows Settings > Capture > Forget (default 10 s); the Forget path reads it once per Forget through ForgetRadius.ms().
        val timing by lazy { com.rokid.context.phone.ui.settings.capture.CaptureTimingStore.get(this) } // lazy: loaded at the first Forget (or by the warm-up thread)
        com.rokid.context.memory.forget.ForgetRadius.provider = { timing.forgetRadiusMs() }
        com.rokid.context.memory.forget.ForgetScopeSetting.provider = { timing.forgetScopeNow() } // the Forget scope (Photo | Moment | Scene), read when a Forget starts
        val devMode by lazy { com.rokid.context.phone.canvas.DeveloperModeStore.get(this).enabled }
        com.rokid.context.memory.forget.ForgetScopeSetting.experimentalAllowed = { devMode.value } // the engine re-checks Developer mode at every Moment/Scene Forget
        kotlin.concurrent.thread(name = "settings-warm") {
            runCatching {
                com.rokid.context.phone.ui.settings.capture.AiCallPaceStore.get(this); com.rokid.context.phone.ui.settings.capture.WindowToleranceStore.get(this)
                com.rokid.context.phone.ui.settings.capture.SignalConfigStore.get(this); com.rokid.context.phone.ui.settings.capture.DeviceCapabilityStore.get(this)
                com.rokid.context.phone.ui.settings.capture.CaptureTimingStore.get(this); com.rokid.context.phone.canvas.DeveloperModeStore.get(this)
                com.rokid.context.phone.ui.settings.capture.IndexBatteryStore.get(this)
            }.onFailure { PcLog.w("ContextApp", "settings warm-up failed: ${it.javaClass.simpleName}") }
        }
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
        // daily canary check + any backlog; the scheduling decision reads the index prefs, so it runs off the main thread (Codex idxbatt r1 P2)
        kotlin.concurrent.thread(name = "index-enqueue") {
            runCatching { com.rokid.context.orchestration.EmbeddingWorker.enqueue(this) }.onFailure { PcLog.w("ContextApp", "index enqueue failed: ${it.javaClass.simpleName}") }
        }
        // S3: an area marked "seeding" that has no live work (killed, cancelled, cleared) becomes partial, so it can never stay "(seeding)" forever. Opens the pack off the main thread.
        kotlin.concurrent.thread(name = "seed-reconcile") { runCatching { kotlinx.coroutines.runBlocking { com.rokid.context.phone.place.SeedWork(this@ContextApp).reconcile(com.rokid.context.work.frames.place.PlaceGraph.readyPack(this@ContextApp)) } } }
        // A restart with "I'm abroad" ON: model downloads queued earlier with "Allow mobile data" are made Wi-Fi only again (WorkManager and prefs reads: off the main thread).
        kotlin.concurrent.thread(name = "model-download-net") { runCatching { com.rokid.context.data.models.LocalModelStore(this).reevaluate() } }
        // rc1 fix: a committed Forget withdraws the answers of the asks it redacted wherever they may still show (phone voice card, glasses HUD through the hub's frames).
        com.rokid.context.orchestration.forget.ForgetActions.asksRedacted = { ids -> android.os.Handler(android.os.Looper.getMainLooper()).post { runCatching { com.rokid.context.phone.voice.VoiceGraph.onAsksRedacted(this, ids) }.onFailure { PcLog.w("ContextApp", "answer withdrawal failed: ${it.javaClass.simpleName}") } } }
        registerActivityLifecycleCallbacks(com.rokid.context.phone.hub.HubActivityHook)
        // Trip diagnostics: battery, network, clock, permissions, worker states and an hourly heartbeat (never text, names or places); see DiagProbe.
        com.rokid.context.phone.log.DiagProbe(this, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO), onTimeChange = { com.rokid.context.ai.datasaver.DataSaverGraph.get(this).onClockChanged() }).start()
        // v1.1 T1: one PcExit line per cold start (how the last processes ended, battery exemption); the exit-record read is a binder call, so off the main thread.
        kotlin.concurrent.thread(name = "exit-diag") { com.rokid.context.phone.hub.PhoneExitDiagnostics.logAtStart(this) }
        // A hub that stopped itself (glasses away) may start again when the platform starts this process (a 15 min job) AND the app is exempt from battery optimization; never otherwise, never after a manual stop.
        // Codex review 4: only an event is submitted here; the claim, its commits and the start run on the AutoStop actor's IO context, never on the main thread.
        runCatching { com.rokid.context.phone.hub.AutoStopWake.processStarted(this) }
    }
}
