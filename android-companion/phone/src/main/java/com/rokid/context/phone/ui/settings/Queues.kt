package com.rokid.context.phone.ui.settings

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rokid.context.core.ui.components.ScreenHeader
import com.rokid.context.core.ui.theme.PcColors
import com.rokid.context.data.runtime.PipelineStatsStore
import com.rokid.context.data.runtime.QueueRuntimeStore
import com.rokid.context.domain.queue.QueuePriority
import com.rokid.context.ui.queues.QueueScreen
import com.rokid.context.ui.queues.QueueSnapshot
import com.rokid.context.work.OrchestrationScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

interface QueueActions {
    suspend fun snapshot(): QueueSnapshot
    suspend fun queueExplicitWrite(text: String): String
    suspend fun queueReadContext(query: String): String
    suspend fun drainNow(): String
}

/** Same counts and actions the old MainActivity wired into QueueScreen (lines 505–539), now off the main thread. */
class LegacyQueueActions(private val context: Context, private val scheduler: OrchestrationScheduler) : QueueActions {
    override suspend fun snapshot(): QueueSnapshot = withContext(Dispatchers.IO) {
        val q = QueueRuntimeStore.snapshot(context)
        val stats = PipelineStatsStore.get(context)
        QueueSnapshot(
            readContext = q.pendingByQueue[QueuePriority.READ] ?: 0,
            writeContextExplicit = q.pendingByQueue[QueuePriority.EXPLICIT_WRITE] ?: 0,
            writeContextBatched = q.pendingByQueue[QueuePriority.SUMMARIZER] ?: 0,
            summarizer = q.pendingByQueue[QueuePriority.SUMMARIZER] ?: 0,
            retry = q.totalDeferred,
            deferredTtlHours = 24,
            totalSummarizerTriggered = stats.summarizerTriggered,
            totalSummarizerSucceeded = stats.summarizerSucceeded,
            totalFramesCaptured = stats.framesCaptured,
            lastQueueEvent = QueueRuntimeStore.recent(context, 1).firstOrNull()?.let { "${it.queue} ${it.state} attempts=${it.attemptCount}" } ?: "none",
        )
    }

    override suspend fun queueExplicitWrite(text: String): String = withContext(Dispatchers.IO) { "writeContext queued: ${scheduler.enqueueExplicitWrite(rawInput = text).takeLast(10)}" }
    override suspend fun queueReadContext(query: String): String = withContext(Dispatchers.IO) { "readContext queued: ${scheduler.enqueueReadContext(query = query).takeLast(10)}" }
    override suspend fun drainNow(): String = withContext(Dispatchers.IO) { scheduler.enqueueQueueDrainNow(); "Queue drain requested" }
}

data class QueuesUiState(val snapshot: QueueSnapshot? = null, val explicitWrite: String = "", val readQuery: String = "", val message: String? = null)

/** Refreshes on open, on Refresh, and after each action — no polling (spec §1.4). */
class QueuesViewModel(private val actions: QueueActions) : ViewModel() {
    private val _state = MutableStateFlow(QueuesUiState())
    val state: StateFlow<QueuesUiState> = _state.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch { load() }
    }

    fun onExplicitWriteChange(text: String) = _state.update { it.copy(explicitWrite = text) }
    fun onReadQueryChange(text: String) = _state.update { it.copy(readQuery = text) }

    fun queueExplicitWrite() {
        val text = _state.value.explicitWrite.trim()
        if (text.isEmpty()) return
        _state.update { it.copy(explicitWrite = "") }
        act { actions.queueExplicitWrite(text) }
    }

    fun queueReadContext() {
        val query = _state.value.readQuery.trim()
        if (query.isEmpty()) return
        _state.update { it.copy(readQuery = "") }
        act { actions.queueReadContext(query) }
    }

    fun drainNow() = act { actions.drainNow() }

    private fun act(block: suspend () -> String) {
        viewModelScope.launch {
            val msg = try { block() } catch (c: CancellationException) { throw c } catch (e: Exception) { DiagnosticsViewModel.FAILED }
            _state.update { it.copy(message = msg) }
            load()
        }
    }

    private suspend fun load() {
        val snap = try { actions.snapshot() } catch (c: CancellationException) { throw c } catch (e: Exception) { null }
        if (snap != null) _state.update { it.copy(snapshot = snap) }
    }
}

@Composable
fun QueuesScreen(
    state: QueuesUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onExplicitWriteChange: (String) -> Unit,
    onReadQueryChange: (String) -> Unit,
    onQueueExplicitWrite: () -> Unit,
    onQueueReadContext: () -> Unit,
    onDrainNow: () -> Unit,
) {
    Column(Modifier.fillMaxSize().background(PcColors.Surface)) {
        ScreenHeader("Queues", onBack) { IconButton(onClick = onRefresh) { Icon(Icons.Rounded.Refresh, contentDescription = "Refresh") } }
        state.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = PcColors.MintDeep, modifier = Modifier.padding(horizontal = 20.dp)) }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            val snap = state.snapshot
            if (snap == null) {
                Text("Loading…", style = MaterialTheme.typography.bodyMedium, color = PcColors.InkSoft)
            } else {
                QueueScreen.Render(snap, state.explicitWrite, state.readQuery, onExplicitWriteChange, onReadQueryChange, onQueueExplicitWrite, onQueueReadContext, onDrainNow, onBack)
            }
        }
    }
}
