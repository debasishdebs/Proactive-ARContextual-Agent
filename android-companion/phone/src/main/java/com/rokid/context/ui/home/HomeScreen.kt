package com.rokid.context.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

data class HomeSnapshot(
    val mode: String,
    val provider: String,
    val queueDepth: Int,
    val trustedMemories: Int,
    val deferredItems: Int,
    val batteryPolicy: String,
    val liveState: String,
    val summarizerState: String,
    val summarizerWorkIdShort: String,
    val summarizerEvent: String,
    val summarizerSucceeded: Int,
    val summarizerFailed: Int,
    val recentFrames: List<String>,
)

data class HomeCard(
    val id: String,
    val title: String,
    val subtitle: String,
    val enabled: Boolean,
)

object HomeScreen {
    fun buildDefaultCards(
        showMigrationCard: Boolean,
        snapshot: HomeSnapshot,
    ): List<HomeCard> = listOf(
        HomeCard(id = "live", title = "Live Pipeline", subtitle = "Live=${snapshot.liveState}, Queue=${snapshot.queueDepth}, Deferred=${snapshot.deferredItems}", enabled = true),
        HomeCard(
            id = "summarizer_tracker",
            title = "Summarizer Tracker",
            subtitle = "Req=${snapshot.summarizerWorkIdShort}, State=${snapshot.summarizerState}, OK=${snapshot.summarizerSucceeded}, Fail=${snapshot.summarizerFailed}",
            enabled = true,
        ),
        HomeCard(id = "status", title = "Status", subtitle = "Mode=${snapshot.mode}, Provider=${snapshot.provider}", enabled = true),
        HomeCard(
            id = "recent_frames",
            title = "Recent Frames",
            subtitle = "Last ${snapshot.recentFrames.size} captured",
            enabled = true,
        ),
        HomeCard(id = "queues", title = "Queues", subtitle = "Depth=${snapshot.queueDepth}, Deferred=${snapshot.deferredItems}", enabled = true),
        HomeCard(id = "memory", title = "Memory", subtitle = "Trusted summaries=${snapshot.trustedMemories}", enabled = true),
        HomeCard(id = "config", title = "Config", subtitle = "LLM, personas, policies", enabled = true),
        HomeCard(id = "diagnostics", title = "Diagnostics", subtitle = "Logs, queue failures, retries", enabled = true),
        HomeCard(id = "migration", title = "Migration", subtitle = "Encryption migration status", enabled = showMigrationCard),
    )

    @Composable
    fun Render(
        snapshot: HomeSnapshot,
        lastAction: String,
        showMigrationCard: Boolean,
        onLive: () -> Unit,
        onTracker: () -> Unit,
        onStatus: () -> Unit,
        onQueues: () -> Unit,
        onFrames: () -> Unit,
        onMemory: () -> Unit,
        onConfig: () -> Unit,
        onDiagnostics: () -> Unit,
        onMigration: () -> Unit,
        onRunSmoke: () -> Unit,
        onTriggerSummarizer: () -> Unit,
    ) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(span = { GridItemSpan(2) }) {
                Text("Proactive Context", style = MaterialTheme.typography.headlineMedium)
            }

            item(span = { GridItemSpan(2) }) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text("Current Mode: ${snapshot.mode}")
                        Text("Provider: ${snapshot.provider}")
                        Text("Battery Policy: ${snapshot.batteryPolicy}")
                        Text("Queue: ${snapshot.queueDepth} pending, ${snapshot.deferredItems} deferred")
                        Text("Trusted Memories: ${snapshot.trustedMemories}")
                    }
                }
            }

            item(span = { GridItemSpan(1) }) {
                Button(
                    onClick = onRunSmoke,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                ) { Text("Run Smoke") }
            }
            item(span = { GridItemSpan(1) }) {
                Button(
                    onClick = onTriggerSummarizer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                ) { Text("Trigger Summarizer") }
            }

            if (lastAction.isNotBlank() || snapshot.summarizerEvent.isNotBlank()) {
                item(span = { GridItemSpan(2) }) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (lastAction.isNotBlank()) {
                                Text("Last action: $lastAction")
                            }
                            if (snapshot.summarizerEvent.isNotBlank()) {
                                Text("Summarizer: ${snapshot.summarizerEvent}")
                            }
                        }
                    }
                }
            }

            items(buildDefaultCards(showMigrationCard, snapshot)) { card ->
                val gradient = gradientFor(card.id)
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(150.dp)
                        .clickable(enabled = card.enabled) {
                            when (card.id) {
                                "live" -> onLive()
                                "summarizer_tracker" -> onTracker()
                                "status" -> onStatus()
                                "recent_frames" -> onFrames()
                                "queues" -> onQueues()
                                "memory" -> onMemory()
                                "config" -> onConfig()
                                "diagnostics" -> onDiagnostics()
                                "migration" -> onMigration()
                            }
                        },
                    colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Brush.linearGradient(gradient))
                            .padding(12.dp),
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = card.title,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                            )
                            Text(
                                text = card.subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFF4F7FF),
                            )
                            if (card.id == "recent_frames") {
                                snapshot.recentFrames.take(3).forEach {
                                    Text(
                                        text = "• $it",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color(0xFFE8F1FF),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun gradientFor(cardId: String): List<Color> = when (cardId) {
        "live" -> listOf(Color(0xFF0F9B8E), Color(0xFF1F6FA8))
        "status" -> listOf(Color(0xFFE0894A), Color(0xFFCA6B2E))
        "recent_frames" -> listOf(Color(0xFFAB4FCF), Color(0xFF6E3C9D))
        "queues" -> listOf(Color(0xFF4B7CC7), Color(0xFF3C61A4))
        "memory" -> listOf(Color(0xFF5FAF62), Color(0xFF498B4D))
        "config" -> listOf(Color(0xFF7A7DCD), Color(0xFF5E62B2))
        "diagnostics" -> listOf(Color(0xFF46A9A0), Color(0xFF367D77))
        "migration" -> listOf(Color(0xFF6F74A8), Color(0xFF5A5F90))
        else -> listOf(Color(0xFF5A5A5A), Color(0xFF3C3C3C))
    }
}
