package com.rokid.context.phone.canvas.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rokid.context.ai.AiGraph
import com.rokid.context.ai.canvas.Pipeline
import com.rokid.context.ai.canvas.PipelineFlows
import com.rokid.context.core.ui.components.ScreenHeader
import com.rokid.context.core.ui.theme.PcColors
import com.rokid.context.memory.retrieval.RetrievalSettingsStore
import com.rokid.context.phone.canvas.CanvasHost
import com.rokid.context.phone.ui.settings.flows.FlowViewModel
import com.rokid.context.phone.ui.settings.flows.FlowViewScreen

/** The read-only DAG of one pipeline: the existing flow view over [PipelineFlows.toAgentFlow]; taps do nothing. */
@Composable
fun CanvasPipelineRoute(id: String, host: CanvasHost, ai: AiGraph, retrieval: RetrievalSettingsStore, onBack: () -> Unit) {
    val loaded by produceState<Loaded>(Loaded.Loading, id) { value = host.pipeline(id)?.let { Loaded.Found(it) } ?: Loaded.Missing }
    when (val l = loaded) {
        Loaded.Loading -> Message("Loading\u2026", onBack)
        Loaded.Missing -> Message("This pipeline can't be shown", onBack)
        is Loaded.Found -> PipelineFlowView(id, l.pipeline, ai, retrieval, onBack)
    }
}

private sealed interface Loaded {
    data object Loading : Loaded
    data object Missing : Loaded
    data class Found(val pipeline: Pipeline) : Loaded
}

@Composable
private fun PipelineFlowView(id: String, pipeline: Pipeline, ai: AiGraph, retrieval: RetrievalSettingsStore, onBack: () -> Unit) {
    val vm = viewModel(key = "canvas-dag-$id") {
        FlowViewModel(listOf(id), ai, retrieval, registry = { pid -> if (pid == id) PipelineFlows.toAgentFlow(pipeline) else null })
    }
    val state by vm.state.collectAsStateWithLifecycle()
    FlowViewScreen(state, onBack = onBack, onNode = {}, onLink = {})
}

@Composable
private fun Message(text: String, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().background(PcColors.SurfaceSoft)) {
        ScreenHeader("Pipeline", onBack)
        Text(text, style = MaterialTheme.typography.bodyLarge, color = PcColors.InkSoft, modifier = Modifier.padding(16.dp))
    }
}
