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
    /** Settings › Data sources (Phase 7 C2). */
    val sources: com.rokid.context.phone.ui.settings.connectors.SourcesControl = com.rokid.context.phone.ui.settings.connectors.SourcesControl.None,
    /** Settings › Phone camera & mic "Capture now" (Phase 7 C2). */
    val capture: com.rokid.context.phone.capture.CaptureNowControl = com.rokid.context.phone.capture.CaptureNowControl.None,
) {
    val ai: AiGraph get() = lazyAi.value
    val testInputs: TestBedInputs get() = lazyTestInputs.value
    val retrieval: RetrievalSettingsStore get() = lazyRetrieval.value
    val spaces: EmbeddingSpaces get() = lazySpaces.value
    val memorySettings: MemorySettingsStore get() = lazyMemorySettings.value

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
            encryption = object : EncryptionControl {
                override fun status() = DbEncryption.status(app)
                override fun request(encrypted: Boolean) = DbEncryption.request(app, encrypted)
            },
            usedBytes = { StorageUsage.usedBytes(app) },
            wipe = { MemoryWipe.deleteAll(app) },
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
            sources = AppSources(app),
            capture = com.rokid.context.phone.capture.PhoneCaptureControl(
                app, com.rokid.context.phone.capture.PhoneCaptureControl.storedPauses({ MemoryGraph.get(app).db.pauseDao() }) { HubGraph.get(app).hudSettings.value.quietHours },
            ),
        )
    }
}

/** Settings › Data sources over the process-wide sources graph; the store is first read off Main by the ViewModel. */
private class AppSources(private val app: Context) : com.rokid.context.phone.ui.settings.connectors.SourcesControl {
    private val graph get() = com.rokid.context.connectors.sources.SourcesGraph.get(app)
    override val enabled get() = graph.settings.enabled
    override val nudgesPerDay get() = graph.settings.nudgesPerDay
    override fun set(k: com.rokid.context.connectors.sources.SourceKind, on: Boolean) = graph.settings.set(k, on)
    override fun setNudgesPerDay(n: Int) = graph.settings.setNudgesPerDay(n)
    override fun granted(k: com.rokid.context.connectors.sources.SourceKind) = com.rokid.context.connectors.sources.SourcePermissions.of(k).all {
        androidx.core.content.ContextCompat.checkSelfPermission(app, it) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }
}
