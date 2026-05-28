package com.rokid.context.ui.diagnostics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

object DiagnosticsScreen {
    fun verboseLogsRetentionHours(enabled: Boolean): Int = if (enabled) 72 else 0

    @Composable
    fun Render(verboseLogsEnabled: Boolean, onBack: () -> Unit) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Diagnostics", style = MaterialTheme.typography.headlineMedium)
            Text("Verbose logs: $verboseLogsEnabled")
            Text("Retention hours: ${verboseLogsRetentionHours(verboseLogsEnabled)}")
            Button(onClick = onBack) { Text("Back") }
        }
    }
}
