package com.rokid.context

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.rokid.context.phone.hub.ContextHubService
import com.rokid.context.phone.hub.HubGraph
import com.rokid.context.phone.hub.HubSettingsStore
import com.rokid.context.data.frames.FrameStreamStore
import com.rokid.context.data.frames.RokidFrameInbox
import com.rokid.context.data.runtime.AppConfigStore
import com.rokid.context.data.runtime.MemoryRecordStore
import com.rokid.context.data.runtime.PipelineStatsStore
import com.rokid.context.data.runtime.QueueRuntimeSnapshot
import com.rokid.context.data.runtime.QueueRuntimeStore
import com.rokid.context.data.runtime.SummaryTraceStore
import com.rokid.context.data.runtime.toProviderSettings
import com.rokid.context.ui.config.ConfigHubScreen
import com.rokid.context.ui.config.ConfigUiState
import com.rokid.context.ui.diagnostics.DiagnosticsScreen
import com.rokid.context.ui.frames.FrameSlide
import com.rokid.context.ui.frames.FrameSlideshowScreen
import com.rokid.context.ui.home.HomeScreen
import com.rokid.context.ui.home.HomeSnapshot
import com.rokid.context.ui.live.LivePipelineScreen
import com.rokid.context.ui.live.LivePipelineState
import com.rokid.context.ui.memory.MemoryItem
import com.rokid.context.ui.memory.MemoryScreen
import com.rokid.context.ui.memory.MemorySnapshot
import com.rokid.context.ui.migration.MigrationStatus
import com.rokid.context.ui.migration.MigrationStatusCard
import com.rokid.context.ui.onboarding.OnboardingScreen
import com.rokid.context.ui.onboarding.OnboardingState
import com.rokid.context.ui.queues.QueueScreen
import com.rokid.context.ui.queues.QueueSnapshot
import com.rokid.context.ui.status.StatusScreen
import com.rokid.context.ui.status.StatusSnapshot
import com.rokid.context.ui.tracker.SummarizerTrackerScreen
import com.rokid.context.ui.tracker.TrackerItem
import com.rokid.context.work.OrchestrationScheduler
import kotlinx.coroutines.delay
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        QueueRuntimeStore.purgeSynthetic(this)
        // Room-backed facades must not run on the main thread (may wait on the legacy import).
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            FrameStreamStore.purgeNonRokidEntries(this@MainActivity)
            SummaryTraceStore.purgeSynthetic(this@MainActivity)
            MemoryRecordStore.purgeNonRokid(this@MainActivity)
        }

        val scheduler = OrchestrationScheduler(this)
        scheduler.apply {
            scheduleFrameIngest()
            scheduleQueueDrain()
            scheduleCritic()
        }

        setContent {
            MaterialTheme {
                AppRoot(scheduler = scheduler)
            }
        }
    }
}

private enum class AppRoute {
    ONBOARDING,
    HOME,
    LIVE,
    TRACKER,
    FRAMES,
    STATUS,
    QUEUES,
    MEMORY,
    CONFIG,
    DIAGNOSTICS,
    MIGRATION,
}

@Composable
private fun AppRoot(scheduler: OrchestrationScheduler) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val initialConfig = remember { AppConfigStore.get(context) }

    var route by rememberSaveable { mutableStateOf(AppRoute.HOME) }
    var lastAction by rememberSaveable { mutableStateOf("") }
    var lastSummarizerWorkId by rememberSaveable { mutableStateOf("") }
    var liveState by rememberSaveable { mutableStateOf("UNKNOWN") }
    var summarizerState by rememberSaveable { mutableStateOf("IDLE") }
    var summarizerEvent by rememberSaveable { mutableStateOf("") }
    var summarizerSucceeded by rememberSaveable { mutableStateOf(0) }
    var summarizerFailed by rememberSaveable { mutableStateOf(0) }
    var queueDepth by rememberSaveable { mutableStateOf(0) }
    var deferredItems by rememberSaveable { mutableStateOf(0) }
    var previousSummarizerState by rememberSaveable { mutableStateOf("IDLE") }
    var queueLastEvent by rememberSaveable { mutableStateOf("none") }

    var recentFrames by remember { mutableStateOf(emptyList<String>()) }
    var frameSlides by remember { mutableStateOf(emptyList<FrameSlide>()) }
    var selectedFrameIndex by rememberSaveable { mutableStateOf(0) }
    var trackerItems by remember { mutableStateOf(emptyList<TrackerItem>()) }
    var memoryItems by remember { mutableStateOf(emptyList<MemoryItem>()) }
    var queueSnapshot by remember {
        mutableStateOf(
            QueueRuntimeSnapshot(
                pendingByQueue = emptyMap(),
                runningByQueue = emptyMap(),
                deferredByQueue = emptyMap(),
                failedByQueue = emptyMap(),
                totalPending = 0,
                totalDeferred = 0,
            ),
        )
    }

    var totalSummTriggered by rememberSaveable { mutableStateOf(0) }
    var totalSummSucceeded by rememberSaveable { mutableStateOf(0) }
    var totalSummFailed by rememberSaveable { mutableStateOf(0) }
    var totalFramesCaptured by rememberSaveable { mutableStateOf(0) }

    var pipelineEnabled by rememberSaveable { mutableStateOf(initialConfig.pipelineEnabled) }
    var localModeEnabled by rememberSaveable { mutableStateOf(initialConfig.localModeEnabled) }
    var encryptionEnabled by rememberSaveable { mutableStateOf(initialConfig.encryptionEnabled) }
    var memoryWindow by rememberSaveable { mutableStateOf(initialConfig.memoryWindow) }
    var persona by rememberSaveable { mutableStateOf(initialConfig.persona) }

    var providerType by rememberSaveable { mutableStateOf(initialConfig.providerType) }
    var endpointInput by rememberSaveable { mutableStateOf(initialConfig.endpoint) }
    var apiKeyInput by rememberSaveable { mutableStateOf(initialConfig.apiKey) }
    var deploymentOrModelInput by rememberSaveable { mutableStateOf(initialConfig.deploymentOrModel) }
    var nativePathInput by rememberSaveable { mutableStateOf(initialConfig.nativeModelPath) }
    var glassesSilencyMode by rememberSaveable { mutableStateOf(AppConfigStore.glassesSilencyMode(context)) }
    var testResult by rememberSaveable { mutableStateOf("Not tested") }

    var bridgeListening by rememberSaveable { mutableStateOf(ContextHubService.isRunning()) }
    var bridgeMessage by rememberSaveable { mutableStateOf(ContextHubService.statusLine()) }
    var pendingBridgeEnable by rememberSaveable { mutableStateOf(false) }

    val bluetoothPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (pendingBridgeEnable && granted) {
            bridgeListening = true
            ContextHubService.ensureRunning(context)
            lastAction = "Rokid bridge started"
        } else if (pendingBridgeEnable) {
            bridgeListening = false
            lastAction = "Bluetooth permission denied. Bridge remains off."
        }
        pendingBridgeEnable = false
    }

    var explicitWriteInput by rememberSaveable { mutableStateOf("") }
    var readQueryInput by rememberSaveable { mutableStateOf("") }

    fun hasBluetoothConnectPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.BLUETOOTH_CONNECT,
        ) == PackageManager.PERMISSION_GRANTED
    }

    var registrySyncJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    // Prefs are written per keystroke; the registry is synced once, off the main thread (debounced for text edits).
    fun syncRegistryLater(debounceMs: Long) {
        registrySyncJob?.cancel()
        registrySyncJob = scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            if (debounceMs > 0) delay(debounceMs)
            val r = com.rokid.context.orchestration.LegacyConfigBridge.sync(context)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                r.onSuccess { if (lastAction.startsWith(AI_NOT_APPLIED)) lastAction = "" }
                    .onFailure { lastAction = "$AI_NOT_APPLIED: ${it.message}" }
            }
        }
    }

    fun persistProviderState() {
        AppConfigStore.setProvider(
            context = context,
            providerType = providerType,
            endpoint = endpointInput,
            apiKey = apiKeyInput,
            deploymentOrModel = deploymentOrModelInput,
            nativeModelPath = nativePathInput,
            syncRegistry = false,
        )
    }

    fun ensureBridgeStarted(reason: String) {
        if (!pipelineEnabled) return
        if (!hasBluetoothConnectPermission()) {
            bridgeListening = false
            bridgeMessage = "Grant Bluetooth permission to start Rokid bridge"
            return
        }
        bridgeListening = true
        ContextHubService.ensureRunning(context)
        bridgeMessage = "Bridge start requested ($reason)"
    }

    fun stopBridge(reason: String) {
        bridgeListening = false
        pendingBridgeEnable = false
        ContextHubService.stop(context)
        bridgeMessage = "Bridge stopped ($reason)"
    }

    LaunchedEffect(pipelineEnabled) {
        if (pipelineEnabled) {
            ensureBridgeStarted("pipeline_enabled")
        } else {
            stopBridge("pipeline_disabled")
        }
    }

    LaunchedEffect(lastSummarizerWorkId) {
        while (true) {
            val focusId = runCatching {
                if (lastSummarizerWorkId.isBlank()) null else UUID.fromString(lastSummarizerWorkId)
            }.getOrNull()

            val snapshot = scheduler.runtimeSnapshot(focusSummarizerId = focusId)
            queueSnapshot = snapshot.queueSnapshot
            queueDepth = snapshot.queueSnapshot.totalPending
            deferredItems = snapshot.queueSnapshot.totalDeferred
            queueLastEvent = QueueRuntimeStore.recent(context, 1).firstOrNull()?.let {
                "${it.queue} ${it.state} attempts=${it.attemptCount}"
            } ?: "none"

            liveState = if (pipelineEnabled && snapshot.pipelineLive) "ON" else if (pipelineEnabled) "IDLE" else "OFF"
            summarizerState = snapshot.focusSummarizerState
            summarizerSucceeded = snapshot.summarizerSucceeded
            summarizerFailed = snapshot.summarizerFailed

            if (snapshot.focusSummarizerState != previousSummarizerState) {
                previousSummarizerState = snapshot.focusSummarizerState
                summarizerEvent = "${snapshot.focusSummarizerState} at ${System.currentTimeMillis()}"
            }

            val (visibleRecords, traceRows, memoryRows) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                Triple(FrameStreamStore.recent(context, 40), SummaryTraceStore.recent(context, 40), MemoryRecordStore.recent(context, 220))
            }
            recentFrames = visibleRecords.take(12).map {
                "${formatTime(it.capturedAtEpochMs)} ${it.source} ${it.payload.take(38)}"
            }
            frameSlides = visibleRecords.take(24).mapIndexed { idx, it ->
                FrameSlide(
                    id = it.frameId.ifBlank { "$idx-${it.capturedAtEpochMs}" },
                    timestamp = formatTime(it.capturedAtEpochMs),
                    source = it.source,
                    payload = it.payload,
                    summaryHint = "Tracker/Memory card has linked summary state",
                    imagePath = it.previewImagePath,
                )
            }

            trackerItems = traceRows.map {
                TrackerItem(
                    summaryId = it.summaryId,
                    state = it.state,
                    updatedAt = formatTime(it.updatedAtEpochMs),
                    note = it.note,
                )
            }

            memoryItems = memoryRows.map {
                MemoryItem(
                    id = it.id,
                    type = it.memoryType,
                    confidence = "conf=${"%.2f".format(it.confidence)}",
                    content = it.content,
                    createdAt = formatTime(it.createdAtEpochMs),
                )
            }

            val stats = PipelineStatsStore.get(context)
            totalSummTriggered = stats.summarizerTriggered
            totalSummSucceeded = stats.summarizerSucceeded
            totalSummFailed = stats.summarizerFailed
            totalFramesCaptured = stats.framesCaptured

            bridgeListening = ContextHubService.isRunning()
            bridgeMessage = ContextHubService.statusLine()
            if (pipelineEnabled && hasBluetoothConnectPermission() && !bridgeListening) {
                ensureBridgeStarted("watchdog")
            }

            delay(1800)
        }
    }

    val homeSnapshot = HomeSnapshot(
        mode = BuildConfig.LOCAL_RUNTIME_MODE,
        provider = providerType,
        queueDepth = queueDepth,
        trustedMemories = memoryItems.count { !it.type.contains("review", ignoreCase = true) },
        deferredItems = deferredItems,
        batteryPolicy = "Respect battery saver; deferred TTL=24h",
        liveState = liveState,
        summarizerState = summarizerState,
        summarizerWorkIdShort = if (lastSummarizerWorkId.isBlank()) "none" else lastSummarizerWorkId.take(8),
        summarizerEvent = summarizerEvent,
        summarizerSucceeded = summarizerSucceeded,
        summarizerFailed = summarizerFailed,
        recentFrames = recentFrames,
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Top,
    ) {
        BackHandler(enabled = route != AppRoute.HOME) {
            route = AppRoute.HOME
        }

        when (route) {
            AppRoute.ONBOARDING -> {
                OnboardingScreen.Render(
                    state = OnboardingState(
                        permissionsReady = true,
                        providerReady = true,
                        optionalPermissionsSkipped = false,
                    ),
                    onContinue = { route = AppRoute.HOME },
                )
            }

            AppRoute.HOME -> {
                HomeScreen.Render(
                    snapshot = homeSnapshot,
                    lastAction = lastAction,
                    showMigrationCard = true,
                    onLive = { route = AppRoute.LIVE },
                    onTracker = { route = AppRoute.TRACKER },
                    onStatus = { route = AppRoute.STATUS },
                    onQueues = { route = AppRoute.QUEUES },
                    onFrames = { route = AppRoute.FRAMES },
                    onMemory = { route = AppRoute.MEMORY },
                    onConfig = { route = AppRoute.CONFIG },
                    onDiagnostics = { route = AppRoute.DIAGNOSTICS },
                    onMigration = { route = AppRoute.MIGRATION },
                    onRunSmoke = {
                        if (!pipelineEnabled) {
                            lastAction = "Pipeline is OFF. Enable it first."
                            return@Render
                        }
                        val queueId = scheduler.enqueueFrameIngestQueue(source = "manual_smoke")
                        lastAction = "Frame ingest queued: ${queueId.takeLast(10)}"
                    },
                    onTriggerSummarizer = {
                        if (!pipelineEnabled) {
                            lastAction = "Pipeline is OFF. Enable it from Live Pipeline card."
                            return@Render
                        }
                        val summaryId = UUID.randomUUID().toString()
                        val workId = scheduler.enqueueSummarizer(
                            summaryId = summaryId,
                            confidence = 0.8,
                            createdAt = System.currentTimeMillis(),
                        )
                        lastSummarizerWorkId = workId.toString()
                        lastAction = "Summarizer enqueued: ${summaryId.take(8)}"
                        summarizerEvent = "ENQUEUED at ${System.currentTimeMillis()}"
                    },
                )
            }

            AppRoute.LIVE -> {
                LivePipelineScreen.Render(
                    state = LivePipelineState(
                        enabled = pipelineEnabled,
                        runtimeLive = liveState,
                        queueDepth = queueDepth,
                        deferredCount = deferredItems,
                        summarizerState = summarizerState,
                        frameInboxPath = RokidFrameInbox.inboxDir(context).absolutePath.takeLast(55),
                        bridgeListening = bridgeListening,
                        bridgeMessage = bridgeMessage,
                    ),
                    onToggleEnabled = { enabled ->
                        pipelineEnabled = enabled
                        AppConfigStore.setPipelineEnabled(context, enabled)
                        if (enabled) {
                            scheduler.resumePipeline()
                            ensureBridgeStarted("live_toggle")
                            lastAction = "Pipeline enabled"
                        } else {
                            scheduler.pausePipeline()
                            stopBridge("live_toggle")
                            lastAction = "Pipeline paused"
                        }
                    },
                    onRunFrameNow = {
                        val queueId = scheduler.enqueueFrameIngestQueue(source = "manual_live")
                        lastAction = "Capture pipeline queued: ${queueId.takeLast(10)} (ingest -> summarize latest window)"
                    },
                    onRunSummarizerNow = {
                        if (!pipelineEnabled) {
                            lastAction = "Pipeline is OFF. Turn it ON first."
                        } else {
                            val summaryId = UUID.randomUUID().toString()
                            scheduler.enqueueSummarizer(
                                summaryId = summaryId,
                                confidence = 0.8,
                                createdAt = System.currentTimeMillis(),
                            )
                            lastSummarizerWorkId = summaryId
                            summarizerEvent = "ENQUEUED at ${System.currentTimeMillis()}"
                            lastAction = "Summarizer queued"
                        }
                    },
                    onToggleBridge = { enabled ->
                        if (enabled) {
                            if (!hasBluetoothConnectPermission()) {
                                pendingBridgeEnable = true
                                bridgeListening = false
                                lastAction = "Grant Bluetooth permission to start Rokid bridge"
                                bluetoothPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                            } else {
                                bridgeListening = true
                                ContextHubService.ensureRunning(context)
                                lastAction = "Rokid bridge started"
                            }
                        } else {
                            bridgeListening = false
                            pendingBridgeEnable = false
                            ContextHubService.stop(context)
                            lastAction = "Rokid bridge stopped"
                        }
                    },
                    onBack = { route = AppRoute.HOME },
                )
            }

            AppRoute.STATUS -> {
                StatusScreen.Render(
                    snapshot = StatusSnapshot(
                        mode = homeSnapshot.mode,
                        provider = homeSnapshot.provider,
                        policyState = homeSnapshot.batteryPolicy,
                        queueHealth = if (deferredItems > 0) "Degraded (retries pending)" else "Healthy",
                    ),
                    onBack = { route = AppRoute.HOME },
                )
            }

            AppRoute.TRACKER -> {
                SummarizerTrackerScreen.Render(
                    items = trackerItems,
                    onBack = { route = AppRoute.HOME },
                )
            }

            AppRoute.FRAMES -> {
                FrameSlideshowScreen.Render(
                    slides = frameSlides,
                    selectedIndex = selectedFrameIndex,
                    onSelectIndex = { idx ->
                        selectedFrameIndex = idx.coerceIn(0, (frameSlides.size - 1).coerceAtLeast(0))
                    },
                    onPrev = { selectedFrameIndex = (selectedFrameIndex - 1).coerceAtLeast(0) },
                    onNext = { selectedFrameIndex = (selectedFrameIndex + 1).coerceAtMost((frameSlides.lastIndex).coerceAtLeast(0)) },
                    onBack = { route = AppRoute.HOME },
                )
            }

            AppRoute.QUEUES -> {
                QueueScreen.Render(
                    snapshot = QueueSnapshot(
                        readContext = (queueSnapshot.pendingByQueue[com.rokid.context.domain.queue.QueuePriority.READ] ?: 0),
                        writeContextExplicit = (queueSnapshot.pendingByQueue[com.rokid.context.domain.queue.QueuePriority.EXPLICIT_WRITE] ?: 0),
                        writeContextBatched = (queueSnapshot.pendingByQueue[com.rokid.context.domain.queue.QueuePriority.SUMMARIZER] ?: 0),
                        summarizer = (queueSnapshot.pendingByQueue[com.rokid.context.domain.queue.QueuePriority.SUMMARIZER] ?: 0),
                        retry = queueSnapshot.totalDeferred,
                        deferredTtlHours = 24,
                        totalSummarizerTriggered = totalSummTriggered,
                        totalSummarizerSucceeded = totalSummSucceeded,
                        totalFramesCaptured = totalFramesCaptured,
                        lastQueueEvent = queueLastEvent,
                    ),
                    explicitWriteInput = explicitWriteInput,
                    readQueryInput = readQueryInput,
                    onExplicitWriteInputChange = { explicitWriteInput = it },
                    onReadQueryInputChange = { readQueryInput = it },
                    onQueueExplicitWrite = {
                        val id = scheduler.enqueueExplicitWrite(rawInput = explicitWriteInput.trim())
                        lastAction = "writeContext queued: ${id.takeLast(10)}"
                        explicitWriteInput = ""
                    },
                    onQueueReadContext = {
                        val id = scheduler.enqueueReadContext(query = readQueryInput.trim())
                        lastAction = "readContext queued: ${id.takeLast(10)}"
                        readQueryInput = ""
                    },
                    onRunDrainNow = {
                        scheduler.enqueueQueueDrainNow()
                        lastAction = "Queue drain requested"
                    },
                    onBack = { route = AppRoute.HOME },
                )
            }

            AppRoute.MEMORY -> {
                MemoryScreen.Render(
                    snapshot = MemorySnapshot(
                        shortTermCount = memoryItems.count { it.type.contains("short", ignoreCase = true) },
                        mediumTermCount = memoryItems.count { it.type.contains("medium", ignoreCase = true) },
                        longTermCount = memoryItems.count { it.type.contains("long", ignoreCase = true) },
                        trustedCount = memoryItems.count { !it.type.contains("review", ignoreCase = true) },
                        needsReviewCount = memoryItems.count { it.type.contains("review", ignoreCase = true) },
                        items = memoryItems,
                    ),
                    actionMessage = lastAction,
                    onReprocessHistorical = {
                        val result = scheduler.enqueueHistoricalPhotoBackfill(maxFrames = 90, chunkSize = 6)
                        lastAction = "${result.reason} frames=${result.selectedFrames}, queued=${result.queuedJobs}, dropped=${result.droppedFrames}"
                    },
                    onBack = { route = AppRoute.HOME },
                )
            }

            AppRoute.CONFIG -> {
                ConfigHubScreen.Render(
                    state = ConfigUiState(
                        pipelineEnabled = pipelineEnabled,
                        localModeEnabled = localModeEnabled,
                        encryptionEnabled = encryptionEnabled,
                        memoryWindow = memoryWindow,
                        persona = persona,
                        providerType = providerType,
                        endpoint = endpointInput,
                        apiKeyMasked = apiKeyInput,
                        deploymentOrModel = deploymentOrModelInput,
                        nativeModelPath = nativePathInput,
                        glassesSilencyMode = glassesSilencyMode,
                        testResult = testResult,
                    ),
                    showAdvancedReadOnly = true,
                    onTogglePipeline = { enabled ->
                        pipelineEnabled = enabled
                        AppConfigStore.setPipelineEnabled(context, enabled)
                        if (enabled) {
                            scheduler.resumePipeline()
                            ensureBridgeStarted("config_toggle")
                        } else {
                            scheduler.pausePipeline()
                            stopBridge("config_toggle")
                        }
                    },
                    onToggleLocalMode = {
                        localModeEnabled = it
                        AppConfigStore.setLocalModeEnabled(context, it, syncRegistry = false)
                        syncRegistryLater(0)
                    },
                    onToggleEncryption = {
                        encryptionEnabled = it
                        AppConfigStore.setEncryptionEnabled(context, it)
                    },
                    onCycleMemoryWindow = {
                        memoryWindow = when (memoryWindow) {
                            "24h" -> "7d"
                            "7d" -> "30d"
                            else -> "24h"
                        }
                        AppConfigStore.setMemoryWindow(context, memoryWindow)
                    },
                    onSelectProvider = {
                        providerType = it
                        persistProviderState()
                        syncRegistryLater(0)
                        testResult = "Not tested"
                    },
                    onUpdateEndpoint = {
                        endpointInput = it
                        persistProviderState()
                        syncRegistryLater(600)
                    },
                    onUpdateApiKey = {
                        apiKeyInput = it
                        persistProviderState()
                        syncRegistryLater(600)
                    },
                    onUpdateDeploymentOrModel = {
                        deploymentOrModelInput = it
                        persistProviderState()
                        syncRegistryLater(600)
                    },
                    onUpdateNativePath = {
                        nativePathInput = it
                        persistProviderState()
                        syncRegistryLater(600)
                    },
                    onSelectGlassesSilencyMode = { mode ->
                        glassesSilencyMode = mode
                        AppConfigStore.setGlassesSilencyMode(context, mode)
                        val verbosity = HubSettingsStore.verbosityFor(mode)
                        HubGraph.get(context).updateSettings { it.copy(verbosity = verbosity) }
                        lastAction = "Glasses verbosity: ${verbosity.name} (applied when linked)"
                    },
                    onTestConnectivity = {
                        scope.launch {
                            testResult = "Testing..."
                            testResult = runCatching {
                                val provider = com.rokid.context.model.ModelProviderResolver.primary(
                                    com.rokid.context.data.runtime.AppConfig(
                                        pipelineEnabled = pipelineEnabled,
                                        localModeEnabled = localModeEnabled,
                                        encryptionEnabled = encryptionEnabled,
                                        memoryWindow = memoryWindow,
                                        persona = persona,
                                        providerType = providerType,
                                        endpoint = endpointInput,
                                        apiKey = apiKeyInput,
                                        deploymentOrModel = deploymentOrModelInput,
                                        nativeModelPath = nativePathInput,
                                    ).toProviderSettings(),
                                )
                                val out = provider.generate("Reply only with OK")
                                "OK: ${out.take(30)}"
                            }.getOrElse { "Test failed: ${it.message}" }
                        }
                    },
                    onBack = { route = AppRoute.HOME },
                )
            }

            AppRoute.DIAGNOSTICS -> {
                DiagnosticsScreen.Render(
                    verboseLogsEnabled = true,
                    onBack = { route = AppRoute.HOME },
                )
            }

            AppRoute.MIGRATION -> {
                MigrationStatusCard.Render(
                    status = MigrationStatus(active = true, progressPercent = 40),
                    onBack = { route = AppRoute.HOME },
                )
            }
        }
    }
}

private fun formatTime(epochMs: Long): String {
    val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    return sdf.format(Date(epochMs))
}

private const val AI_NOT_APPLIED = "AI settings not applied"
