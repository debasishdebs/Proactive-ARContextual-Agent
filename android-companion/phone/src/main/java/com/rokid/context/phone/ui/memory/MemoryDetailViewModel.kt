package com.rokid.context.phone.ui.memory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rokid.context.memory.MemoryEdits
import com.rokid.context.memory.TimelineRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.ZoneId

class MemoryDetailViewModel(
    private val memoryId: String,
    private val timeline: TimelineRepository,
    private val edits: MemoryEdits,
    zone: () -> ZoneId,
) : ViewModel() {
    private data class Local(val draft: String? = null, val message: String? = null)
    private val local = MutableStateFlow(Local())

    val state: StateFlow<MemoryDetailUiState> = combine(
        timeline.memory(memoryId).map { m -> m?.let { MemoryDetailModel.build(it, it.frameId?.let { id -> timeline.frame(id) }, zone()) } ?: MemoryDetailModel.NOT_FOUND },
        local,
    ) { base, l -> if (base.found) base.copy(draft = l.draft, message = l.message) else base }
        .stateIn(viewModelScope, SharingStarted.Eagerly, MemoryDetailUiState())

    fun trust() {
        viewModelScope.launch { if (edits.trust(memoryId)) local.update { it.copy(message = "Marked as trusted") } }
    }

    fun startEdit() = local.update { it.copy(draft = state.value.text, message = null) }
    fun updateDraft(text: String) = local.update { it.copy(draft = text.take(500)) }
    fun cancelEdit() = local.update { it.copy(draft = null, message = null) }

    fun saveEdit() {
        val draft = local.value.draft ?: return
        viewModelScope.launch {
            if (edits.editText(memoryId, draft)) local.update { it.copy(draft = null, message = "Saved") }
            else local.update { it.copy(message = "Text can't be empty") }
        }
    }
}
