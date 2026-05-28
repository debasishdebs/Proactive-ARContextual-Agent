package com.rokid.context.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

data class HomeCard(
    val id: String,
    val title: String,
    val enabled: Boolean,
)

object HomeScreen {
    fun buildDefaultCards(showMigrationCard: Boolean): List<HomeCard> = listOf(
        HomeCard(id = "status", title = "Status", enabled = true),
        HomeCard(id = "queues", title = "Queues", enabled = true),
        HomeCard(id = "memory", title = "Memory", enabled = true),
        HomeCard(id = "config", title = "Config", enabled = true),
        HomeCard(id = "diagnostics", title = "Diagnostics", enabled = true),
        HomeCard(id = "migration", title = "Migration", enabled = showMigrationCard),
    )

    @Composable
    fun Render(
        showMigrationCard: Boolean,
        onConfig: () -> Unit,
        onDiagnostics: () -> Unit,
        onMigration: () -> Unit,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Proactive Context", style = MaterialTheme.typography.headlineMedium)
            buildDefaultCards(showMigrationCard).forEach { card ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = card.enabled) {
                            when (card.id) {
                                "config" -> onConfig()
                                "diagnostics" -> onDiagnostics()
                                "migration" -> onMigration()
                            }
                        },
                ) {
                    Text(
                        text = card.title,
                        modifier = Modifier.padding(14.dp),
                    )
                }
            }
        }
    }
}
