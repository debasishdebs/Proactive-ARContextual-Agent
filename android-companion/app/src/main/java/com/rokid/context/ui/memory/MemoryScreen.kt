package com.rokid.context.ui.memory

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

data class MemoryItem(
    val id: String,
    val type: String,
    val confidence: String,
    val content: String,
    val createdAt: String,
)

data class MemorySnapshot(
    val shortTermCount: Int,
    val mediumTermCount: Int,
    val longTermCount: Int,
    val trustedCount: Int,
    val needsReviewCount: Int,
    val items: List<MemoryItem>,
)

private data class MemoryTypeFilter(
    val key: String,
    val label: String,
    val count: Int,
)

object MemoryScreen {
    @Composable
    fun Render(
        snapshot: MemorySnapshot,
        actionMessage: String,
        onReprocessHistorical: () -> Unit,
        onBack: () -> Unit,
    ) {
        var searchQuery by rememberSaveable { mutableStateOf("") }
        var selectedFilter by rememberSaveable { mutableStateOf("all") }

        val filters = remember(snapshot) {
            listOf(
                MemoryTypeFilter("all", "All", snapshot.items.size),
                MemoryTypeFilter("short", "Short", snapshot.shortTermCount),
                MemoryTypeFilter("medium", "Medium", snapshot.mediumTermCount),
                MemoryTypeFilter("long", "Long", snapshot.longTermCount),
                MemoryTypeFilter("review", "Review", snapshot.needsReviewCount),
            )
        }

        val filteredItems = remember(snapshot.items, searchQuery, selectedFilter) {
            snapshot.items.filter { item ->
                val typeMatch = when (selectedFilter) {
                    "all" -> true
                    "short" -> item.type.contains("short", ignoreCase = true)
                    "medium" -> item.type.contains("medium", ignoreCase = true)
                    "long" -> item.type.contains("long", ignoreCase = true)
                    "review" -> item.type.contains("review", ignoreCase = true)
                    else -> true
                }
                val query = searchQuery.trim()
                val queryMatch = query.isBlank() ||
                    item.id.contains(query, ignoreCase = true) ||
                    item.content.contains(query, ignoreCase = true) ||
                    item.createdAt.contains(query, ignoreCase = true) ||
                    item.type.contains(query, ignoreCase = true)
                typeMatch && queryMatch
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Memory Overview", style = MaterialTheme.typography.headlineMedium)
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Short-term: ${snapshot.shortTermCount}")
                    Text("Medium-term: ${snapshot.mediumTermCount}")
                    Text("Long-term: ${snapshot.longTermCount}")
                    Text("Trusted: ${snapshot.trustedCount}")
                    Text("Needs Review: ${snapshot.needsReviewCount}")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onReprocessHistorical) { Text("Reprocess Old Photos") }
                        if (actionMessage.isNotBlank()) {
                            Text(actionMessage, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                label = { Text("Search memory content / image id / time") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                items(filters) { filter ->
                    FilterChip(
                        selected = selectedFilter == filter.key,
                        onClick = { selectedFilter = filter.key },
                        label = { Text("${filter.label} (${filter.count})") },
                    )
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Row(modifier = Modifier.fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Image ID / Timestamp",
                        modifier = Modifier.weight(0.9f),
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "Memory Content",
                        modifier = Modifier.weight(1.5f),
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            if (filteredItems.isEmpty()) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text("No memory records found", modifier = Modifier.padding(12.dp))
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(filteredItems) { item ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(10.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Column(modifier = Modifier.weight(0.9f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(item.id.take(18), fontWeight = FontWeight.SemiBold)
                                    Text(item.createdAt, style = MaterialTheme.typography.bodySmall)
                                    Text(item.type, style = MaterialTheme.typography.bodySmall)
                                    Text(item.confidence, style = MaterialTheme.typography.bodySmall)
                                }
                                Text(item.content, modifier = Modifier.weight(1.5f))
                            }
                        }
                    }
                }
            }

            Button(onClick = onBack) { Text("Back") }
        }
    }
}
