package com.rokid.context.phone.ui.settings

import com.rokid.context.core.log.PcLog
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Switch
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rokid.context.core.ui.components.ChipRow
import com.rokid.context.core.ui.components.ScreenHeader
import com.rokid.context.core.ui.components.SectionLabel
import com.rokid.context.core.ui.components.SoftCard
import com.rokid.context.core.ui.theme.PcColors
import com.rokid.context.model.CallClass
import com.rokid.context.phone.ui.status.PipelineControl
import com.rokid.context.work.frames.KeyframeStats
import com.rokid.context.work.OrchestrationScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

/** Manual runs that used to live on the removed Live and Memory screens (R-03). Messages are fixed/human. */
interface DiagnosticsActions {
    suspend fun summarizeNow(): String
    suspend fun captureNow(): String
    suspend fun reprocessOldPhotos(): String
}

class SchedulerDiagnosticsActions(private val scheduler: OrchestrationScheduler) : DiagnosticsActions {
    override suspend fun summarizeNow(): String = withContext(Dispatchers.IO) {
        scheduler.enqueueSummarizer(summaryId = UUID.randomUUID().toString(), confidence = 0.8, createdAt = System.currentTimeMillis())
        "Summarizer queued"
    }

    override suspend fun captureNow(): String = withContext(Dispatchers.IO) {
        scheduler.enqueueFrameIngestQueue(source = "manual_live")
        "Capture pipeline queued — ingest, then summarize the latest window"
    }

    override suspend fun reprocessOldPhotos(): String = withContext(Dispatchers.IO) {
        scheduler.enqueueHistoricalPhotoBackfill(maxFrames = 90, chunkSize = 6).let { "${it.reason} Frames ${it.selectedFrames}, jobs ${it.queuedJobs}." }
    }
}

/** What the keyframe rows read: the pipeline's stats plus three count-only extras. Never captions or user text. */
interface KeyframeDiagnostics {
    val stats: StateFlow<KeyframeStats>
    /** Dropped-nudge reason (gate name) to count. */
    suspend fun droppedNudges(): Map<String, Int>
    /** v2 frames ingested with defaults after their sidecar stayed lost. */
    suspend fun sidecarLost(): Int
    /** Retention counters (count only): ordinary skipped photos deleted, photos described by the night job, photos deleted undescribed (must stay 0). */
    suspend fun retentionCounters(): RetentionCounters = RetentionCounters()
    /** False when the stored VISION_EXTRACT prompt still has the facts-only schema. */
    suspend fun promptIsCurrent(): Boolean
    /** False when the stored CLUSTER_SUMMARY prompt lacks `cluster_id` / `source_frame_ids` (then one cluster per call). */
    suspend fun clusterPromptIsCurrent(): Boolean = true
    /** The active canvas interpreter publish (null: none), count-only. */
    suspend fun livePipeline(): LivePipelineInfo? = null
    /** The active canvas capture plan and its per-component group states (S25, S26); null: settings run the capture. */
    suspend fun capturePlan(): com.rokid.context.canvas.plan.PlanStatus? = null
    /** Puts every FAILED_FINAL frame back to PENDING; returns how many. */
    suspend fun retryAllFinal(): Int
    /** Room v16: the pre-fix memories that may describe a photo the model never saw (read-only). */
    suspend fun suspectDescriptions(): SuspectDescriptions = SuspectDescriptions()
    /** Marks [ids] (null = every current suspect) for review; returns the mark time for [undoMark], or 0 when nothing was marked. Only the flag changes. */
    suspend fun markForReview(ids: List<String>?): Long = 0
    /** Clears exactly the marks made at [at]; returns how many. */
    suspend fun undoMark(at: Long): Int = 0

    object None : KeyframeDiagnostics {
        override val stats: StateFlow<KeyframeStats> = MutableStateFlow(KeyframeStats())
        override suspend fun droppedNudges() = emptyMap<String, Int>()
        override suspend fun sidecarLost() = 0
        override suspend fun promptIsCurrent() = true
        override suspend fun retryAllFinal() = 0
    }
}

/** Task 8/9 counters. [deletedUndescribed] is a guarantee: it must stay 0. */
data class RetentionCounters(
    val skippedDeleted: Int = 0, val describedBeforeDelete: Int = 0, val deletedUndescribed: Int = 0,
    /** Photos waiting in the night queue, and photos parked after five failed describes (never deleted). */
    val describeWaiting: Int = 0, val describeFailed: Int = 0,
    /** Codex r12: photos some memory cites but none describes (only canvas/derived memories, facts awaiting review or scene-member links): kept for now. */
    val strandedPhotos: Int = 0,
)

/** Room v16: one suspect memory (its kind, when it was written, the start of its text). */
data class SuspectItem(val id: String, val type: String, val createdAt: Long, val text: String)
/** [total] suspects; [items] the newest of them (the list shows at most [SHOWN]). */
data class SuspectDescriptions(val total: Int = 0, val items: List<SuspectItem> = emptyList()) { companion object { const val SHOWN = 20 } }

/** The active interpreter publish for the "Live pipeline" line: [live] false is Shadow. */
data class LivePipelineInfo(val name: String, val version: Int, val live: Boolean, val fallbacks: Int, val interpreted: Int)

/** The Keyframes block of Diagnostics: the stats plus the loaded extras. */
data class KeyframeRows(
    val stats: KeyframeStats = KeyframeStats(),
    val droppedNudges: Map<String, Int> = emptyMap(),
    val sidecarLost: Int = 0,
    val retention: RetentionCounters = RetentionCounters(),
    val promptCurrent: Boolean = true,
    val clusterPromptCurrent: Boolean = true,
    val livePipeline: LivePipelineInfo? = null,
    val capturePlan: com.rokid.context.canvas.plan.PlanStatus? = null,
    val suspects: SuspectDescriptions = SuspectDescriptions(),
)

/** [undoAt]: the time of the last "Mark for review" (0 = nothing to undo). */
data class DiagnosticsUiState(val busy: Boolean = false, val message: String? = null, val undoAt: Long = 0)

class DiagnosticsViewModel(
    private val actions: DiagnosticsActions,
    private val pipeline: PipelineControl,
    exportDiagnostics: (suspend () -> java.io.File)? = null,
    private val share: (java.io.File) -> Unit = {},
    pushSettings: () -> Unit = {},
    private val glassesConnected: () -> Boolean = { true },
    /** G19: writes the settings JSON (every secret excluded), shared through [share]. */
    private val exportSettings: (suspend () -> java.io.File)? = null,
    private val keyframes: KeyframeDiagnostics = KeyframeDiagnostics.None,
) : ViewModel() {
    private val extras = MutableStateFlow(KeyframeRows())
    /** Live stats combined with the extras loaded at open and after each Retry all. */
    // Lazy: the constructor must not touch viewModelScope (it would bind to Main before a test installs its dispatcher).
    val keyframeRows: StateFlow<KeyframeRows> by lazy {
        combine(keyframes.stats, extras) { st, ex -> ex.copy(stats = st) }
            .onStart { refreshKeyframes() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), KeyframeRows(keyframes.stats.value))
    }

    private fun refreshKeyframes() {
        viewModelScope.launch {
            val loaded = try {
                withContext(Dispatchers.IO) { KeyframeRows(droppedNudges = keyframes.droppedNudges(), sidecarLost = keyframes.sidecarLost(), retention = keyframes.retentionCounters(), promptCurrent = keyframes.promptIsCurrent(), clusterPromptCurrent = keyframes.clusterPromptIsCurrent(), livePipeline = keyframes.livePipeline(), capturePlan = keyframes.capturePlan(), suspects = keyframes.suspectDescriptions()) }
            } catch (c: CancellationException) { throw c } catch (e: Exception) { PcLog.i(TAG, "keyframe extras failed (${e.javaClass.simpleName})"); return@launch }
            extras.value = loaded
        }
    }

    /** "Retry all": FAILED_FINAL frames go back to PENDING (the pipeline picks them up). */
    fun retryAll() = run(requiresPipeline = false) {
        val n = keyframes.retryAllFinal()
        refreshKeyframes()
        if (n == 0) "Nothing to retry" else "$n photos queued to retry"
    }

    /** Room v16 "Mark for review" (single, or every listed suspect): only the flag changes, never text or photos; the last marking can be undone. */
    fun markSuspect(id: String) = markSuspects(listOf(id))
    fun markAllSuspects() = markSuspects(null)
    private fun markSuspects(ids: List<String>?) {
        if (_state.value.busy) return
        _state.value = DiagnosticsUiState(busy = true)
        viewModelScope.launch {
            val at = try { keyframes.markForReview(ids) } catch (c: CancellationException) { throw c } catch (e: Exception) { PcLog.i(TAG, "mark for review failed (${e.javaClass.simpleName})"); -1L }
            refreshKeyframes()
            _state.value = when {
                at < 0 -> DiagnosticsUiState(message = FAILED)
                at == 0L -> DiagnosticsUiState(message = "Nothing to mark")
                else -> DiagnosticsUiState(message = MARKED, undoAt = at)
            }
        }
    }
    fun undoMark() {
        val at = _state.value.undoAt
        if (_state.value.busy || at == 0L) return
        _state.value = DiagnosticsUiState(busy = true)
        viewModelScope.launch {
            val n = try { keyframes.undoMark(at) } catch (c: CancellationException) { throw c } catch (e: Exception) { PcLog.i(TAG, "undo mark failed (${e.javaClass.simpleName})"); -1 }
            refreshKeyframes()
            _state.value = DiagnosticsUiState(message = if (n < 0) FAILED else "Marking undone")
        }
    }

    // F16: plain parameters (named like the test passes them) kept in vals that don't clash with the methods.
    private val exportAction = exportDiagnostics
    private val pushAction = pushSettings
    private val _state = MutableStateFlow(DiagnosticsUiState())
    val state: StateFlow<DiagnosticsUiState> = _state.asStateFlow()

    fun summarizeNow() = run(requiresPipeline = true) { actions.summarizeNow() }
    fun captureNow() = run(requiresPipeline = true) { actions.captureNow() }
    fun reprocess() = run(requiresPipeline = false) { actions.reprocessOldPhotos() }

    fun exportDiagnostics() = run(requiresPipeline = false) {
        val f = exportAction?.invoke() ?: return@run FAILED
        share(f); "Diagnostics ready to share"
    }

    fun exportSettings() = run(requiresPipeline = false) {
        val f = exportSettings?.invoke() ?: return@run FAILED
        share(f); SETTINGS_READY
    }

    /** spec §2.4 "manual config sync": re-push the HUD settings to the glasses (ruling, Task 21). */
    fun pushSettings() {
        pushAction() // persisted by the hub, which sends it now or on the next connect
        val msg = if (glassesConnected()) PUSHED else NOT_CONNECTED
        // Fix round 1 (m6): an export in flight keeps its busy guard; its own result replaces this message.
        _state.value = _state.value.copy(message = msg)
    }

    private fun run(requiresPipeline: Boolean, block: suspend () -> String) {
        if (_state.value.busy) return
        if (requiresPipeline && !pipeline.processingEnabled.value) { _state.value = DiagnosticsUiState(message = PAUSED); return }
        _state.value = DiagnosticsUiState(busy = true)
        viewModelScope.launch {
            val msg = try { block() } catch (c: CancellationException) { throw c } catch (e: Exception) {
                PcLog.i(TAG, "diagnostics action failed (${e.javaClass.simpleName})")
                FAILED
            }
            _state.value = DiagnosticsUiState(message = msg)
        }
    }

    companion object {
        const val PAUSED = "Capture is paused — resume it from Home first"
        const val MARKED = "Marked for review: no longer counted as the photo's description"
        const val FAILED = "That didn't work — try again"
        const val PUSHED = "Settings sent to the glasses"
        const val SETTINGS_READY = "Settings ready to share (no keys included)"
        const val NOT_CONNECTED = "Glasses not connected — settings will sync when they connect"
        private const val TAG = "Diagnostics"
    }
}

/** The fixed texts of the suspect-descriptions card (count-only summary; an item shows its kind, date and the start of its text). */
object SuspectLines {
    fun summary(n: Int) = "$n memories written before 2026-11-08 may describe a photo the model never saw (the old text-only retry). The app cannot tell which: marking one for review stops it counting as the photo's description."
    fun item(i: SuspectItem): String = "${i.type} · " + java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(i.createdAt)) + " · " + i.text.take(60)
}

private const val ALL = "\u0000all"

@Composable
fun DiagnosticsScreen(
    state: DiagnosticsUiState,
    onBack: () -> Unit,
    onOpenQueues: () -> Unit,
    onOpenTracker: () -> Unit,
    onSummarizeNow: () -> Unit,
    onCaptureNow: () -> Unit,
    onReprocess: () -> Unit,
    onOpenLogs: () -> Unit,
    onExport: () -> Unit,
    onPushSettings: () -> Unit,
    onExportSettings: () -> Unit = {},
    keyframes: KeyframeRows = KeyframeRows(),
    onRetryAll: () -> Unit = {},
    onOpenPrompts: () -> Unit = {},
    /** Fix wave H4: "Rokid closed the app N times (last gap Xs)"; shown only once the glasses report one. */
    glassesRestarts: String? = null,
    /** The Developer mode switch at the bottom: shows Settings > AI > Experiment canvas when on. */
    developerMode: Boolean = false,
    onDeveloperMode: (Boolean) -> Unit = {},
    /** Debug builds with Developer mode on only (the caller passes null otherwise): the photo 256-vs-768 decision test. */
    onOpenPhotoDimTest: (() -> Unit)? = null,
    /** Wave B: the Context hub card (enum names and numbers only); null while no hub exists. */
    contextHub: ContextHubCardData? = null,
    /** v0.9.7 B: the developer sensor recording rate in Hz (0 = off); the row shows only with [developerMode]. */
    sensorRecHz: Int = 0,
    onSensorRecHz: (Int) -> Unit = {},
    /** Room v16 suspect descriptions: mark one, mark every one, undo the last marking. */
    onMarkSuspect: (String) -> Unit = {},
    onMarkAllSuspects: () -> Unit = {},
    onUndoMark: () -> Unit = {},
) {
    var confirmMark by remember { mutableStateOf<String?>(null) } // a memory id, or ALL
    confirmMark?.let { target ->
        AlertDialog(
            onDismissRequest = { confirmMark = null },
            title = { Text(if (target == ALL) "Mark all ${keyframes.suspects.total} for review?" else "Mark this memory for review?") },
            text = { Text("A marked memory no longer counts as its photo's description: retention keeps the photo until it is described again, and the night job describes it again (a paid model call per photo, up to 60 a night). No text or photo is deleted, and you can undo.") },
            confirmButton = { TextButton(onClick = { confirmMark = null; if (target == ALL) onMarkAllSuspects() else onMarkSuspect(target) }, modifier = Modifier.testTag("suspect_confirm")) { Text("Mark for review") } },
            dismissButton = { TextButton(onClick = { confirmMark = null }, modifier = Modifier.testTag("suspect_cancel")) { Text("Cancel") } },
        )
    }
    Column(Modifier.fillMaxSize().background(PcColors.SurfaceSoft).verticalScroll(rememberScrollState())) {
        ScreenHeader("Diagnostics", onBack)
        SoftCard(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
            Column {
                ChipRow(Icons.Outlined.Inbox, PcColors.ChipSky, "Queues", "Pending, deferred and failed jobs", onClick = onOpenQueues)
                ChipRow(Icons.Outlined.Timeline, PcColors.ChipViolet, "Summarizer tracker", "Recent summary jobs", onClick = onOpenTracker)
                ChipRow(Icons.Outlined.Article, PcColors.ChipSlate, "Logs", "Recent app log lines", onClick = onOpenLogs)
                ChipRow(Icons.Outlined.IosShare, PcColors.ChipMint, "Export diagnostics", "Logs and recent jobs as a text file", onClick = onExport, enabled = !state.busy)
                ChipRow(Icons.Outlined.Sync, PcColors.ChipSky, "Push settings to glasses", "Re-send HUD settings now", onClick = onPushSettings)
                ChipRow(Icons.Outlined.Tune, PcColors.ChipViolet, "Export settings", "All settings as a JSON file, without keys", onClick = onExportSettings, enabled = !state.busy)
            }
        }
        SectionLabel("Keyframes")
        SoftCard(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                for (line in KeyframeLines.of(keyframes)) NoticeText(line.text, tag = line.tag)
                if (!keyframes.promptCurrent) {
                    ChipRow(Icons.Outlined.WarningAmber, PcColors.ChipAmber, KeyframeLines.PROMPT_OUT_OF_DATE, "Open Settings > Prompts", onClick = onOpenPrompts)
                }
                if (!keyframes.clusterPromptCurrent) {
                    ChipRow(Icons.Outlined.WarningAmber, PcColors.ChipAmber, KeyframeLines.CLUSTER_PROMPT_OUT_OF_DATE, "Open Settings > Prompts", onClick = onOpenPrompts)
                }
                ChipRow(
                    Icons.Outlined.Replay, PcColors.ChipSky, "Retry all", "Photos that failed for good: ${keyframes.stats.counts["FAILED_FINAL"] ?: 0}",
                    onClick = onRetryAll, enabled = !state.busy && (keyframes.stats.counts["FAILED_FINAL"] ?: 0) > 0,
                )
            }
        }
        if (keyframes.suspects.total > 0 || state.undoAt != 0L) {
            SectionLabel("Suspect descriptions")
            SoftCard(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    NoticeText(SuspectLines.summary(keyframes.suspects.total), tag = "suspect_count")
                    for (item in keyframes.suspects.items.take(SuspectDescriptions.SHOWN)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(SuspectLines.item(item), style = MaterialTheme.typography.bodySmall, color = PcColors.Ink, modifier = Modifier.weight(1f))
                            TextButton(onClick = { confirmMark = item.id }, enabled = !state.busy, modifier = Modifier.testTag("suspect_mark_${item.id}")) { Text("Mark for review") }
                        }
                    }
                    if (keyframes.suspects.total > 0) ChipRow(Icons.Outlined.WarningAmber, PcColors.ChipAmber, "Mark all ${keyframes.suspects.total} for review", "Only a flag: no text or photo is deleted",
                        onClick = { confirmMark = ALL }, enabled = !state.busy)
                    if (state.undoAt != 0L) ChipRow(Icons.Outlined.Replay, PcColors.ChipSky, "Undo last marking", "Puts those memories back as they were", onClick = onUndoMark, enabled = !state.busy)
                }
            }
        }
        if (contextHub != null) {
            SectionLabel("Context hub")
            ContextHubCard(contextHub)
        }
        if (glassesRestarts != null) {
            SectionLabel("Glasses")
            SoftCard(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) { NoticeText(glassesRestarts, tag = "glasses_restarts") }
            }
        }
        SectionLabel("Manual runs")
        SoftCard(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
            Column {
                ChipRow(Icons.Outlined.AutoAwesome, PcColors.ChipMint, "Summarize now", "Summarize the latest frames", onClick = onSummarizeNow, enabled = !state.busy)
                ChipRow(Icons.Outlined.PhotoCamera, PcColors.ChipAmber, "Run capture pipeline now", "Ingest new glasses frames, then summarize", onClick = onCaptureNow, enabled = !state.busy)
                ChipRow(Icons.Outlined.History, PcColors.ChipRose, "Reprocess old photos", "Re-summarize up to 90 stored photos", onClick = onReprocess, enabled = !state.busy)
            }
        }
        state.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = PcColors.Ink, modifier = Modifier.padding(20.dp)) }
        SectionLabel("Developer")
        SoftCard(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Developer mode", style = MaterialTheme.typography.titleSmall)
                    Text("Shows Experiment canvas in Settings. Turning it off stops the canvas server.", style = MaterialTheme.typography.bodySmall, color = PcColors.InkSoft)
                }
                Switch(checked = developerMode, onCheckedChange = onDeveloperMode, modifier = Modifier.testTag("developer_mode_switch"))
            }
            if (developerMode) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val rates = listOf(0) + com.rokid.context.core.model.SENSOR_REC_PRESETS_HZ
                    ChoiceRow("Record raw glasses sensors", rates.map { if (it == 0) "Off" else "$it Hz" }, rates.indexOf(sensorRecHz).coerceAtLeast(0), { onSensorRecHz(rates[it]) }, tag = "sensor_rec_rate")
                    if (sensorRecHz > 0) NoticeText(SensorRecCopy.warning(sensorRecHz), error = true, tag = "sensor_rec_warning")
                }
            }
            if (onOpenPhotoDimTest != null) {
                androidx.compose.foundation.layout.Box(Modifier.testTag("photo_dim_row")) {
                    ChipRow(Icons.Outlined.PhotoCamera, PcColors.ChipAmber, "Photo search dimension test (debug)", "Compare image search at 768 and 256 dimensions on your photos", onClick = onOpenPhotoDimTest)
                }
            }
        }
    }
}

/** The copy of the developer sensor-recording row (v0.9.7 B). */
object SensorRecCopy {
    /** Shown while a rate is chosen: what is recorded, where it goes, and the cost. */
    fun warning(hz: Int) = "records sensor data at $hz Hz at most (never faster than the glasses already sample, and nothing while they save battery, rest or are paused) and saves it in batches on the glasses (not sent to the phone yet; a developer pull reads it). Uses more battery."
}

/** The count-only text lines of the Keyframes block. */
object KeyframeLines {
    const val CLUSTER_PROMPT_OUT_OF_DATE = "Scene summary prompt is out of date \u2014 reset to default"
    const val PROMPT_OUT_OF_DATE = "VISION_EXTRACT prompt is out of date \u2014 reset to default"
    class Line(val tag: String, val text: String)

    private val ORDER = listOf("PENDING", "EXTRACTING", "EXTRACTED", "FAILED", "FAILED_FINAL")
    private val CLUSTER_ORDER = listOf("OPEN", "CLOSED", "SUMMARIZING", "SUMMARIZED", "FAILED", "FAILED_FINAL")

    fun ms(v: Long?): String = when {
        v == null -> "\u2014"
        v < 1_000 -> "$v ms"
        v < 120_000 -> "${v / 1_000} s"
        else -> "${v / 60_000} min"
    }

    fun of(k: KeyframeRows): List<Line> {
        val st = k.stats
        val states = (ORDER + (st.counts.keys - ORDER.toSet()).sorted()).filter { (st.counts[it] ?: 0) > 0 }
            .joinToString(" \u00b7 ") { "$it ${st.counts[it]}" }.ifEmpty { "none" }
        val named = listOf(CallClass.USER, CallClass.EXTRACT, CallClass.NUDGE, CallClass.EXPERIMENT)
        val other = st.callsPerHour.filterKeys { it !in named }.values.sum() // OTHER, CAPTION_QUEUE, BACKFILL
        val calls = named.joinToString(" \u00b7 ") { "${it.name} ${st.callsPerHour[it] ?: 0}" } + " \u00b7 OTHER $other"
        val dropped = k.droppedNudges.entries.sortedBy { it.key }.joinToString(" \u00b7 ") { "${it.key} ${it.value}" }.ifEmpty { "none" }
        val live = k.livePipeline?.let { Line("kf_live_pipeline", "Live pipeline: ${it.name} v${it.version} \u00b7 ${if (it.live) "Live" else "Shadow"} \u00b7 ${it.fallbacks}/${it.interpreted} fell back") }
        val plan = k.capturePlan?.active?.let { a -> listOf(Line("kf_capture_plan", "Capture plan: ${a.name} v${a.version} (${a.mode.lowercase()}) \u00b7 ${a.state}")) + k.capturePlan.rows.mapIndexed { i, r -> Line("kf_plan_row_$i", r) } }.orEmpty()
        return plan + listOfNotNull(
            live,
            Line("kf_backlog", "Backlog ${st.backlog} \u2014 $states"),
            Line("kf_rate", "Keyframes per hour: ${st.keyframesPerHour}"),
            Line("kf_shed", "Skipped to keep up: ${st.shed}"),
            Line("kf_clusters", "Scene clusters: ${CLUSTER_ORDER.filter { (st.clusters[it] ?: 0) > 0 }.joinToString(" \u00b7 ") { "$it ${st.clusters[it]}" }.ifEmpty { "none" }}"),
            Line("kf_scene_photos", "Photos covered by scene summaries (last 24 h): ${st.scenePhotos24h}"),
            Line("kf_scene_calls", "Scene calls (last 24 h): ${st.sceneCalls24h}"),
            Line("kf_caption_queue", "Caption queue: ${st.captionQueue}"),
            Line("kf_unrouted", "Unrouted: ${st.unrouted}"),
            Line("kf_calls", "AI calls per hour: $calls"),
            Line("kf_latency", "Capture to memory: p50 ${ms(st.latencyP50Ms)} \u00b7 p95 ${ms(st.latencyP95Ms)}"),
            Line("kf_dropped", "Dropped nudges: $dropped"),
            Line("kf_sidecar", "Lost sidecars: ${k.sidecarLost}"),
            // 2026-11-08: the night queue's progress in plain words (it drains only on a charger, at most 60 calls of <= 3 photos a run).
            Line("kf_describe_waiting", "Photos waiting to be described: ${k.retention.describeWaiting}" + if (k.retention.describeWaiting > 0) " \u00b7 described while charging, up to 180 a run" else ""),
            Line("kf_stranded", "${k.retention.strandedPhotos} photos kept: only canvas/derived memories cite them"),
            Line("kf_retention", "Photos deleted: skipped ${k.retention.skippedDeleted} \u00b7 described first ${k.retention.describedBeforeDelete} \u00b7 undescribed ${k.retention.deletedUndescribed} \u00b7 waiting ${k.retention.describeWaiting} \u00b7 describeFailed ${k.retention.describeFailed}"),
        )
    }
}
