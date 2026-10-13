package com.rokid.context.phone.ui.settings.memory

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rokid.context.core.ui.components.ConfidenceChip
import com.rokid.context.memory.MemoryEdits
import com.rokid.context.phone.ui.settings.NoticeText
import com.rokid.context.phone.ui.settings.SecondaryAction
import com.rokid.context.phone.ui.settings.SettingsCard
import com.rokid.context.phone.ui.settings.SettingsPage
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

data class ReviewItemUi(val id: String, val text: String, val confidence: Double, val time: String)
data class ReviewQueueUiState(val items: List<ReviewItemUi> = emptyList())

/** Facts the critic did not trust (reviewState NEEDS_REVIEW): hidden from feeds and retrieval until kept here. */
class ReviewQueueViewModel(private val edits: MemoryEdits, private val zone: () -> ZoneId = ZoneId::systemDefault) : ViewModel() {
    private val fmt = DateTimeFormatter.ofPattern("d MMM HH:mm", Locale.ENGLISH)
    val state: StateFlow<ReviewQueueUiState> = edits.needsReview(100).map { list ->
        ReviewQueueUiState(list.map { ReviewItemUi(it.id, it.text, it.confidence, fmt.format(Instant.ofEpochMilli(it.createdAt).atZone(zone()))) })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReviewQueueUiState())

    fun keep(id: String) = viewModelScope.launch { edits.approve(id) }
}

@Composable
fun ReviewQueueScreen(state: ReviewQueueUiState, onBack: () -> Unit, onKeep: (String) -> Unit) {
    SettingsPage("Needs review", onBack, tag = "review_queue") {
        if (state.items.isEmpty()) item { SettingsCard { NoticeText("Nothing to review") } }
        state.items.forEach { r ->
            item(key = r.id) {
                SettingsCard {
                    Column {
                        Text(r.text, style = MaterialTheme.typography.bodyMedium)
                        NoticeText(r.time)
                    }
                    ConfidenceChip(r.confidence)
                    SecondaryAction("Keep", { onKeep(r.id) }, tag = "keep_${r.id}")
                }
            }
        }
    }
}
