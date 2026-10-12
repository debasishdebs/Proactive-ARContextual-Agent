package com.rokid.context.ui.status

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

data class StatusSnapshot(
    val mode: String,
    val provider: String,
    val policyState: String,
    val queueHealth: String,
)

object StatusScreen {
    @Composable
    fun Render(snapshot: StatusSnapshot, onBack: () -> Unit) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Runtime Status", style = MaterialTheme.typography.headlineMedium)
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Mode: ${snapshot.mode}")
                    Text("Provider: ${snapshot.provider}")
                    Text("Policy: ${snapshot.policyState}")
                    Text("Queue Health: ${snapshot.queueHealth}")
                }
            }
            Button(onClick = onBack) { Text("Back") }
        }
    }
}
