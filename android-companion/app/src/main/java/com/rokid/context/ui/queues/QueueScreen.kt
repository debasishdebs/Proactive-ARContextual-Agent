package com.rokid.context.ui.queues

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

data class QueueSnapshot(
    val readContext: Int,
    val writeContextExplicit: Int,
    val writeContextBatched: Int,
    val summarizer: Int,
    val retry: Int,
    val deferredTtlHours: Int,
    val totalSummarizerTriggered: Int,
    val totalSummarizerSucceeded: Int,
    val totalFramesCaptured: Int,
    val lastQueueEvent: String,
)

object QueueScreen {
    @Composable
    fun Render(
        snapshot: QueueSnapshot,
        explicitWriteInput: String,
        readQueryInput: String,
        onExplicitWriteInputChange: (String) -> Unit,
        onReadQueryInputChange: (String) -> Unit,
        onQueueExplicitWrite: () -> Unit,
        onQueueReadContext: () -> Unit,
        onRunDrainNow: () -> Unit,
        onBack: () -> Unit,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Queue Dashboard", style = MaterialTheme.typography.headlineMedium)

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Live Counts", fontWeight = FontWeight.Bold)
                    Text("readContext: ${snapshot.readContext}")
                    Text("writeContext (explicit): ${snapshot.writeContextExplicit}")
                    Text("writeContext (batched): ${snapshot.writeContextBatched}")
                    Text("summarizer pending/running: ${snapshot.summarizer}")
                    Text("retry/deferred: ${snapshot.retry}")
                    Text("TTL queue window: ${snapshot.deferredTtlHours}h")
                    Text("total summarizer triggered: ${snapshot.totalSummarizerTriggered}")
                    Text("total summarizer succeeded: ${snapshot.totalSummarizerSucceeded}")
                    Text("total frames captured: ${snapshot.totalFramesCaptured}")
                    Text("last event: ${snapshot.lastQueueEvent}")
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Manual Queue Actions", fontWeight = FontWeight.Bold)
                    OutlinedTextField(
                        value = explicitWriteInput,
                        onValueChange = onExplicitWriteInputChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Explicit remember input") },
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onQueueExplicitWrite, enabled = explicitWriteInput.isNotBlank()) {
                            Text("Queue writeContext")
                        }
                    }

                    OutlinedTextField(
                        value = readQueryInput,
                        onValueChange = onReadQueryInputChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("ReadContext query") },
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onQueueReadContext, enabled = readQueryInput.isNotBlank()) {
                            Text("Queue readContext")
                        }
                        Button(onClick = onRunDrainNow) {
                            Text("Run now")
                        }
                    }
                }
            }

            Button(onClick = onBack) { Text("Back") }
        }
    }
}
