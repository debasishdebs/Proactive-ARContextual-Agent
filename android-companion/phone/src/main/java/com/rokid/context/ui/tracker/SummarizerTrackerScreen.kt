package com.rokid.context.ui.tracker

import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

data class TrackerItem(
    val summaryId: String,
    val state: String,
    val updatedAt: String,
    val note: String,
)

object SummarizerTrackerScreen {
    @Composable
    fun Render(items: List<TrackerItem>, onBack: () -> Unit) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Summarizer Tracker", style = MaterialTheme.typography.headlineMedium)
            if (items.isEmpty()) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text("No summary jobs yet", modifier = Modifier.padding(12.dp))
                }
            }
            items.forEach { item ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Brush.linearGradient(listOf(Color(0xFF3D4049), Color(0xFF21242C))))
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text("${item.summaryId.take(8)} · ${item.state}", color = Color.White, fontWeight = FontWeight.Bold)
                        Text(item.updatedAt, color = Color(0xFFE3E8F2))
                        Text(item.note, color = Color(0xFFE3E8F2))
                    }
                }
            }
            Button(onClick = onBack) { Text("Back") }
        }
    }
}
