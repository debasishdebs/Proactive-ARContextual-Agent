package com.rokid.context.ui.migration

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

data class MigrationStatus(
    val active: Boolean,
    val progressPercent: Int,
)

object MigrationStatusCard {
    fun isVisible(status: MigrationStatus): Boolean = status.active

    @Composable
    fun Render(status: MigrationStatus, onBack: () -> Unit) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Migration", style = MaterialTheme.typography.headlineMedium)
            if (isVisible(status)) {
                val progress = (status.progressPercent.coerceIn(0, 100) / 100f)
                LinearProgressIndicator(progress = { progress })
                Text("Progress: ${status.progressPercent}%")
            } else {
                Text("No active migration")
            }
            Button(onClick = onBack) { Text("Back") }
        }
    }
}
