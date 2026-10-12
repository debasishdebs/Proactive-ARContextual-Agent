package com.rokid.context.ui.live

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

data class LivePipelineState(
    val enabled: Boolean,
    val runtimeLive: String,
    val queueDepth: Int,
    val deferredCount: Int,
    val summarizerState: String,
    val frameInboxPath: String,
    val bridgeListening: Boolean,
    val bridgeMessage: String,
)

object LivePipelineScreen {
    @Composable
    fun Render(
        state: LivePipelineState,
        onToggleEnabled: (Boolean) -> Unit,
        onRunFrameNow: () -> Unit,
        onRunSummarizerNow: () -> Unit,
        onToggleBridge: (Boolean) -> Unit,
        onBack: () -> Unit,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Live Pipeline", style = MaterialTheme.typography.headlineMedium)

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Brush.linearGradient(listOf(Color(0xFF0F9B8E), Color(0xFF1F6FA8))))
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Pipeline Control", color = Color.White, fontWeight = FontWeight.Bold)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("Enabled", color = Color.White)
                        Switch(checked = state.enabled, onCheckedChange = onToggleEnabled)
                    }
                    Text("Runtime Live: ${state.runtimeLive}", color = Color(0xFFEAF6FF))
                    Text("Queue: ${state.queueDepth} pending / ${state.deferredCount} deferred", color = Color(0xFFEAF6FF))
                    Text("Summarizer: ${state.summarizerState}", color = Color(0xFFEAF6FF))
                    Text("Rokid Inbox: ${state.frameInboxPath}", color = Color(0xFFEAF6FF))
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Rokid Bridge", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(if (state.bridgeListening) "Listening" else "Stopped")
                        Switch(checked = state.bridgeListening, onCheckedChange = onToggleBridge)
                    }
                    Text(state.bridgeMessage)
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onRunFrameNow) { Text("Capture/Process Now") }
                Button(onClick = onRunSummarizerNow) { Text("Summarize Now") }
            }

            Button(onClick = onBack) { Text("Back") }
        }
    }
}
