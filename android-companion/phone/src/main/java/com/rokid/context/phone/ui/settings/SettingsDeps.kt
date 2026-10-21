package com.rokid.context.phone.ui.settings

import android.content.Context
import android.util.Log
import com.rokid.context.ai.AiGraph
import com.rokid.context.core.model.HudSettings
import com.rokid.context.link.AuthActivity
import com.rokid.context.memory.DbEncryption
import com.rokid.context.memory.EmbeddingSpaces
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.MemorySettingsStore
import com.rokid.context.memory.retrieval.RetrievalSettingsStore
import com.rokid.context.orchestration.AnswerService
import com.rokid.context.orchestration.MemoryExport
import com.rokid.context.orchestration.MemoryWipe
import com.rokid.context.orchestration.TestBedInputs
import com.rokid.context.phone.hub.Hub
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import com.rokid.context.phone.hub.HubGraph
import com.rokid.context.phone.ui.settings.memory.EncryptionControl
import com.rokid.context.phone.ui.settings.memory.ShareFile
import com.rokid.context.phone.ui.status.StorageUsage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

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
    /** Lazy for the same reason as [lazyRetrieval] (Settings → Memory, Task 20). */
    private val lazyMemorySettings: Lazy<MemorySettingsStore>,
    /** The process-wide watch list (WatchListStore.get): the scene indexer reads the same instance. */
    private val lazyWatchList: Lazy<com.rokid.context.work.frames.scene.WatchListStore>,
    val encryption: EncryptionControl,
    val usedBytes: suspend () -> Long,
    val wipe: suspend () -> Unit,
    val export: suspend () -> File,
    val share: (File) -> Unit,
    /** Diagnostics › Logs: this app's own recent log lines, key-looking lines dropped (Task 21). */
    val logs: suspend () -> List<String>,
    /** Diagnostics › Export: a text file in cache/exports (the FileProvider folder), shared through [share]. */
    val exportDiagnostics: suspend () -> File,
    /** Settings › Phone camera & mic (Phase 6). */
    val voice: com.rokid.context.phone.voice.VoiceControls = com.rokid.context.phone.voice.VoiceControls.None,
    /** This app's system settings page (a microphone denied with "don't ask again"). */
    val openAppSettings: () -> Unit = {},
    /** Settings › Privacy (Phase 7 Stream A): the shared capture pause control. */
    val pauses: com.rokid.context.phone.ui.status.PipelineControl = com.rokid.context.phone.ui.status.PipelineControl.None,
    /** Settings › Data sources (Phase 7 C2). */
    val sources: com.rokid.context.phone.ui.settings.connectors.SourcesControl = com.rokid.context.phone.ui.settings.connectors.SourcesControl.None,
    /** Settings › Phone camera & mic "Capture now" (Phase 7 C2). */
    val capture: com.rokid.context.phone.capture.CaptureNowControl = com.rokid.context.phone.capture.CaptureNowControl.None,
    /** Settings › Actions & tools (Phase 7 B): lazy, so building these deps on Main never builds the actions graph. */
    private val lazyActions: Lazy<com.rokid.context.connectors.actions.ActionsGraphApi>,
    /** Settings > AI > On-device models (on-device embeddings spec section 5): the app wires [StoreLocalModels]. */
    val localModels: com.rokid.context.phone.ui.settings.ai.LocalModelGateway = com.rokid.context.phone.ui.settings.ai.LocalModelGateway.None,
    /** The page's "Allow mobile data" switch. */
    val localModelsMobileData: com.rokid.context.phone.ui.settings.ai.MobileDataSetting = com.rokid.context.phone.ui.settings.ai.InMemoryMobileData(),
    /** Consecutive on-device load failures per model (spec section 6); the app wires [NativeLoadFailures]. */
    val localModelLoadFailures: com.rokid.context.phone.ui.settings.ai.LoadFailures = com.rokid.context.phone.ui.settings.ai.LoadFailures.None,
    /** Diagnostics > Export settings (G19): the settings JSON in cache/exports, every secret excluded. */
    val exportSettings: (suspend () -> File)? = null,
    /** Settings > Capture > AI call pace (prefs key `ai_call_pace_ms`, read by the call budget). */
    val aiCallPace: com.rokid.context.phone.ui.settings.capture.AiCallPaceStore = com.rokid.context.phone.ui.settings.capture.AiCallPaceStore(),
    /** Diagnostics > Keyframes and the Capture status line (counts only). */
    val keyframes: KeyframeDiagnostics = KeyframeDiagnostics.None,
    /** Diagnostics: "Rokid closed the app N times (last gap Xs)" from the glasses' hello; null until reported. */
    val glassesRestarts: kotlinx.coroutines.flow.StateFlow<String?> = kotlinx.coroutines.flow.MutableStateFlow(null),
    /** Wave B: the process-wide context hub's read side and the settings it observes (None when no hub exists). */
    val contextSources: ContextSourcesControl = ContextSourcesControl.None,
) {
    val ai: AiGraph get() = lazyAi.value
    val testInputs: TestBedInputs get() = lazyTestInputs.value
    val retrieval: RetrievalSettingsStore get() = lazyRetrieval.value
    val spaces: EmbeddingSpaces get() = lazySpaces.value
    val memorySettings: MemorySettingsStore get() = lazyMemorySettings.value
    val watchList: com.rokid.context.work.frames.scene.WatchListStore get() = lazyWatchList.value
    val actions: com.rokid.context.connectors.actions.ActionsGraphApi get() = lazyActions.value

    companion object {
        /** Process-lifetime work the Settings deps start (a Rebuild commits prefs, so it leaves Main). */
        private val scope = CoroutineScope(SupervisorJob())

        fun create(app: Context): SettingsDeps = SettingsDeps(
            lazyAi = lazy { AiGraph.get(app) },
            hud = HubHudSettings(HubGraph.get(app)),
            reauthorize = { AuthActivity.launch(app).onFailure { Log.i("Settings", "re-authorize failed (${it.javaClass.simpleName})") } },
            onEmbedBindingChanged = { com.rokid.context.orchestration.EmbeddingWorker.enqueue(app) },
            lazyTestInputs = lazy {
                val mem = MemoryGraph.get(app)
                TestBedInputs(mem, AnswerService.forApp(app), com.rokid.context.orchestration.nudge.NudgeInputs(mem, com.rokid.context.connectors.sources.SourcesGraph.get(app).provider))
            },
            lazyRetrieval = lazy { RetrievalSettingsStore.get(app) },
            lazySpaces = lazy { MemoryGraph.get(app).spaces },
            rebuildIndex = { scope.launch(Dispatchers.IO) { com.rokid.context.orchestration.EmbeddingWorker.enqueue(app, rebuild = true) } },
            lazyMemorySettings = lazy { MemorySettingsStore.get(app) },
            lazyWatchList = lazy { com.rokid.context.work.frames.scene.WatchListStore.get(app) },
            encryption = object : EncryptionControl {
                override fun status() = DbEncryption.status(app)
                override fun request(encrypted: Boolean) {
                    DbEncryption.request(app, encrypted)
                    // Gaps G1: frame files convert now, in the background (the database follows at the next start).
                    com.rokid.context.orchestration.FrameCryptoWorker.enqueue(app)
                }
            },
            usedBytes = { StorageUsage.usedBytes(app) },
            wipe = { MemoryWipe.deleteAll(app); com.rokid.context.phone.context.ContextSourceSettings.of(app).clearPersonal() },
            export = { MemoryExport.write(app) },
            share = { f -> ShareFile.share(app, f) },
            logs = { LogReader.ownLines() },
            exportDiagnostics = {
                val traces = com.rokid.context.orchestration.PipelineRecords.recentTraces(app, 50).map { "${it.updatedAt} ${it.state} ${it.summaryId} ${it.note}" }
                DiagnosticsExport.write(app.cacheDir, traces, LogReader.ownLines())
            },
            voice = com.rokid.context.phone.voice.VoiceGraph.get(app).controls,
            openAppSettings = {
                val intent = android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", app.packageName, null))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { app.startActivity(intent) }.onFailure { Log.i("Settings", "app settings unavailable (${it.javaClass.simpleName})") }
            },
            pauses = com.rokid.context.phone.ui.status.CapturePauses.control(app),
            sources = AppSources(app),
            capture = com.rokid.context.phone.capture.PhoneCaptureControl(
                app, com.rokid.context.phone.capture.PhoneCaptureControl.storedPauses({ MemoryGraph.get(app).db.pauseDao() }, quietHours = { HubGraph.get(app).hudSettings.value.quietHours }),
            ),
            lazyActions = lazy { com.rokid.context.connectors.actions.ActionsGraph.get(app) },
            localModels = StoreLocalModels(com.rokid.context.data.models.LocalModelStore(app)),
            localModelsMobileData = AppConfigMobileData(app),
            localModelLoadFailures = NativeLoadFailures(com.rokid.context.data.models.LocalModelStore(app)),
            exportSettings = { com.rokid.context.orchestration.ConfigExport.write(app) },
            aiCallPace = com.rokid.context.phone.ui.settings.capture.AiCallPaceStore.get(app),
            keyframes = AppKeyframeDiagnostics(app),
            glassesRestarts = HubGraph.get(app).state.map { it.glassesDiagnostics() }.stateIn(scope, kotlinx.coroutines.flow.SharingStarted.Eagerly, null),
            contextSources = AppContextSources(app),
        )
    }
}

/** Wave B: what the Context sources screen, the Capture row and Diagnostics read. The UI only writes [settings]; the hub observes them. */
interface ContextSourcesControl {
    val settings: com.rokid.context.phone.context.ContextSourceSettings?
    val certLookup: com.rokid.context.phone.ui.settings.capture.LiveFitCertLookup
    val decision: StateFlow<com.rokid.context.core.model.context.ContextDecision>
    val status: StateFlow<Map<String, com.rokid.context.phone.context.SourceStatus>>
    val stats: com.rokid.context.phone.context.ContextHubStats?

    object None : ContextSourcesControl {
        override val settings = null
        override val certLookup = com.rokid.context.phone.ui.settings.capture.LiveFitCertLookup { null }
        override val decision: StateFlow<com.rokid.context.core.model.context.ContextDecision> = kotlinx.coroutines.flow.MutableStateFlow(
            com.rokid.context.core.model.context.ContextDecision(com.rokid.context.core.model.context.ContextState.UNKNOWN, 0f, 0L, emptySet(), null, com.rokid.context.core.model.context.ContextState.UNKNOWN, false))
        override val status: StateFlow<Map<String, com.rokid.context.phone.context.SourceStatus>> = kotlinx.coroutines.flow.MutableStateFlow(emptyMap())
        override val stats = null
    }
}

/** Over [HubGraph.contextHub] (built with the hub, so created lazily on first read). Settings are the process-wide prefs either way. */
private class AppContextSources(private val app: Context) : ContextSourcesControl {
    private val hub get() = HubGraph.get(app).let { HubGraph.contextHub }
    override val settings get() = com.rokid.context.phone.context.ContextSourceSettings.of(app)
    override val certLookup = com.rokid.context.phone.ui.settings.capture.PackageLiveFitCertLookup(app.packageManager)
    override val decision get() = hub?.decision ?: ContextSourcesControl.None.decision
    override val status get() = hub?.status ?: ContextSourcesControl.None.status
    override val stats get() = hub?.stats
}

/** Keyframe stats over the process-wide graphs; built lazily so the shell's Main-thread deps never start the pipeline. */
private class AppKeyframeDiagnostics(private val app: Context) : KeyframeDiagnostics {
    private val handle by lazy { com.rokid.context.work.frames.KeyframeGraph.get(app) }
    override val stats get() = handle.stats
    override suspend fun droppedNudges(): Map<String, Int> =
        com.rokid.context.orchestration.PipelineRecords.recentTraces(app, 500).filter { it.state == "NUDGE_DROPPED" }.groupingBy { it.note }.eachCount()
    override suspend fun sidecarLost() = com.rokid.context.data.runtime.PipelineStatsStore.sidecarLost(app)
    override suspend fun retentionCounters() = com.rokid.context.data.runtime.PipelineStatsStore.let {
        val dao = MemoryGraph.get(app).db.frameStateDao()
        com.rokid.context.phone.ui.settings.RetentionCounters(it.skippedPhotosDeleted(app), it.describedBeforeDelete(app), it.deletedUndescribed(app), dao.describeWaiting(), dao.describeFailed())
    }
    override suspend fun promptIsCurrent() =
        com.rokid.context.orchestration.ExtractSave.promptIsCurrent(AiGraph.get(app).runner.activeSchema(com.rokid.context.ai.contract.PromptKey.VISION_EXTRACT))
    override suspend fun clusterPromptIsCurrent() =
        com.rokid.context.ai.contract.ClusterContract.promptIsCurrent(AiGraph.get(app).runner.activeSchema(com.rokid.context.ai.contract.PromptKey.CLUSTER_SUMMARY))
    override suspend fun retryAllFinal() = MemoryGraph.get(app).db.frameStateDao().retryAllFinal(System.currentTimeMillis())
}

/** Settings › Data sources over the process-wide sources graph; the store is first read off Main by the ViewModel. */
private class AppSources(private val app: Context) : com.rokid.context.phone.ui.settings.connectors.SourcesControl {
    private val graph get() = com.rokid.context.connectors.sources.SourcesGraph.get(app)
    override val enabled get() = graph.settings.enabled
    override val nudgesPerDay get() = graph.settings.nudgesPerDay
    override fun set(k: com.rokid.context.connectors.sources.SourceKind, on: Boolean) = graph.settings.set(k, on)
    override fun setNudgesPerDay(n: Int) = graph.settings.setNudgesPerDay(n)
    override val settleSec get() = graph.settings.settleSec
    override val checkGapSec get() = graph.settings.checkGapSec
    override val shownGapMin get() = graph.settings.shownGapMin
    override fun setSettleSec(n: Int) = graph.settings.setSettleSec(n)
    override fun setCheckGapSec(n: Int) = graph.settings.setCheckGapSec(n)
    override fun setShownGapMin(n: Int) = graph.settings.setShownGapMin(n)
    override fun granted(k: com.rokid.context.connectors.sources.SourceKind) = com.rokid.context.connectors.sources.SourcePermissions.of(k).all {
        androidx.core.content.ContextCompat.checkSelfPermission(app, it) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }
}

/** The page's gateway over the real download store. Deleting or replacing a file clears its load-failure count. */
class StoreLocalModels(
    private val store: com.rokid.context.data.models.LocalModelStore,
    private val failures: com.rokid.context.model.provider.LocalLoadFailures = com.rokid.context.model.provider.LlamaCppNativeProvider.loadFailures,
) : com.rokid.context.phone.ui.settings.ai.LocalModelGateway {
    override fun fileFor(model: com.rokid.context.ai.local.LocalModel) = store.fileFor(model)
    override fun state(model: com.rokid.context.ai.local.LocalModel) = store.state(model)
    override fun enqueue(model: com.rokid.context.ai.local.LocalModel, allowMetered: Boolean) = store.enqueue(model, allowMetered)
    override fun setAllowMetered(model: com.rokid.context.ai.local.LocalModel, allowMetered: Boolean) = store.setAllowMetered(model, allowMetered)
    override fun cancel(model: com.rokid.context.ai.local.LocalModel) = store.cancel(model)
    override fun delete(model: com.rokid.context.ai.local.LocalModel) {
        store.delete(model)
        failures.reset(store.fileFor(model).absolutePath)
    }
}

/** The page reads the embed path's consecutive load failures for a catalog model's file (spec section 6). */
class NativeLoadFailures(
    private val store: com.rokid.context.data.models.LocalModelStore,
    private val failures: com.rokid.context.model.provider.LocalLoadFailures = com.rokid.context.model.provider.LlamaCppNativeProvider.loadFailures,
) : com.rokid.context.phone.ui.settings.ai.LoadFailures {
    override suspend fun of(modelId: String): com.rokid.context.phone.ui.settings.ai.LoadFailure? {
        val model = com.rokid.context.ai.local.LocalModels.byId(modelId) ?: return null
        return failures.consecutive(store.fileFor(model).absolutePath).takeIf { it > 0 }?.let { com.rokid.context.phone.ui.settings.ai.LoadFailure(it) }
    }
}

/** Stored in the existing `app_config` prefs (AppConfigStore's file), under its own key. Read off Main by the page. */
class AppConfigMobileData(context: Context) : com.rokid.context.phone.ui.settings.ai.MobileDataSetting {
    private val prefs by lazy { context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    override fun allowed() = prefs.getBoolean(KEY, false)
    override fun set(allowed: Boolean) { prefs.edit().putBoolean(KEY, allowed).apply() }

    companion object {
        const val PREFS = "app_config"
        const val KEY = "local_models_allow_metered"
    }
}
