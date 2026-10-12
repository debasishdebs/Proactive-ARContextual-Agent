package com.rokid.context.phone.ui.settings

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rokid.context.core.ui.components.ChipRow
import com.rokid.context.core.ui.components.ScreenHeader
import com.rokid.context.core.ui.components.SectionLabel
import com.rokid.context.core.ui.components.SoftCard
import com.rokid.context.core.ui.theme.PcColors
import com.rokid.context.phone.ui.status.PipelineControl
import com.rokid.context.work.OrchestrationScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

data class DiagnosticsUiState(val busy: Boolean = false, val message: String? = null)

class DiagnosticsViewModel(private val actions: DiagnosticsActions, private val pipeline: PipelineControl) : ViewModel() {
    private val _state = MutableStateFlow(DiagnosticsUiState())
    val state: StateFlow<DiagnosticsUiState> = _state.asStateFlow()

    fun summarizeNow() = run(requiresPipeline = true) { actions.summarizeNow() }
    fun captureNow() = run(requiresPipeline = true) { actions.captureNow() }
    fun reprocess() = run(requiresPipeline = false) { actions.reprocessOldPhotos() }

    private fun run(requiresPipeline: Boolean, block: suspend () -> String) {
        if (_state.value.busy) return
        if (requiresPipeline && !pipeline.enabled.value) { _state.value = DiagnosticsUiState(message = PAUSED); return }
        _state.value = DiagnosticsUiState(busy = true)
        viewModelScope.launch {
            val msg = try { block() } catch (c: CancellationException) { throw c } catch (e: Exception) {
                Log.i(TAG, "diagnostics action failed (${e.javaClass.simpleName})")
                FAILED
            }
            _state.value = DiagnosticsUiState(message = msg)
        }
    }

    companion object {
        const val PAUSED = "Capture is paused — resume it from Home first"
        const val FAILED = "That didn't work — try again"
        private const val TAG = "Diagnostics"
    }
}

@Composable
fun DiagnosticsScreen(
    state: DiagnosticsUiState,
    onBack: () -> Unit,
    onOpenQueues: () -> Unit,
    onOpenTracker: () -> Unit,
    onSummarizeNow: () -> Unit,
    onCaptureNow: () -> Unit,
    onReprocess: () -> Unit,
) {
    Column(Modifier.fillMaxSize().background(PcColors.SurfaceSoft).verticalScroll(rememberScrollState())) {
        ScreenHeader("Diagnostics", onBack)
        SoftCard(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
            Column {
                ChipRow(Icons.Outlined.Inbox, PcColors.ChipSky, "Queues", "Pending, deferred and failed jobs", onClick = onOpenQueues)
                ChipRow(Icons.Outlined.Timeline, PcColors.ChipViolet, "Summarizer tracker", "Recent summary jobs", onClick = onOpenTracker)
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
    }
}
