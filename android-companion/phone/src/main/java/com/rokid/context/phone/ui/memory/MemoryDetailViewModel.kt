package com.rokid.context.phone.ui.memory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rokid.context.memory.MemoryEdits
import com.rokid.context.memory.TimelineRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.coroutines.cancellation.CancellationException
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
    private val forget: MemoryForget = MemoryForget.None,
    /** After a saved edit: the cached memory export still holds the old text, so the route deletes it (GAP-1). */
    private val onTextEdited: () -> Unit = {},
) : ViewModel() {
    private data class Local(
        val draft: String? = null, val message: String? = null, val confirm: ForgetKind? = null,
        val forgetting: Boolean = false, val forgotten: Boolean = false,
        val preview: ForgetPreview? = null, val previewing: Boolean = false, val previewFailed: Boolean = false, val blockedByCount: Boolean = false,
    )
    private var previewJob: kotlinx.coroutines.Job? = null
    private val local = MutableStateFlow(Local())

    val state: StateFlow<MemoryDetailUiState> = combine(
        timeline.memory(memoryId).map { m -> m?.let { MemoryDetailModel.build(it, it.frameId?.let { id -> timeline.frame(id) }, zone()) } ?: MemoryDetailModel.NOT_FOUND },
        local,
    ) { base, l ->
        // `forgotten` survives the row disappearing (the Forget itself deletes it).
        if (base.found) base.copy(draft = l.draft, message = l.message, confirm = l.confirm, forgetting = l.forgetting, forgotten = l.forgotten,
            forgetPreview = l.preview, forgetPreviewing = l.previewing, forgetPreviewFailed = l.previewFailed, forgetConfirmable = !l.previewing && !l.blockedByCount)
        else base.copy(forgotten = l.forgotten, message = if (l.forgotten) l.message else base.message) // the cached-export notice outlives the row
    }
        .stateIn(viewModelScope, SharingStarted.Eagerly, MemoryDetailUiState())

    fun trust() {
        viewModelScope.launch { if (edits.trust(memoryId)) local.update { it.copy(message = "Marked as trusted") } }
    }

    fun startEdit() = local.update { it.copy(draft = state.value.text, message = null) }
    fun updateDraft(text: String) = local.update { it.copy(draft = text.take(500)) }
    fun cancelEdit() = local.update { it.copy(draft = null, message = null) }

    fun saveEdit() {
        val draft = local.value.draft ?: return
        if (draft.isBlank()) { local.update { it.copy(message = "Text can't be empty") }; return }
        viewModelScope.launch {
            if (edits.editText(memoryId, draft)) { runCatching(onTextEdited); local.update { it.copy(draft = null, message = "Saved") } }
            else local.update { it.copy(message = "This memory can't be edited") }
        }
    }

    /** Opens the dialog. Forget fact needs a visible memory; Forget moment needs a frame. */
    fun askForget(kind: ForgetKind) {
        val s = state.value
        val allowed = s.found && !s.forgetting && when (kind) { ForgetKind.FACT -> s.editable; ForgetKind.MOMENT -> s.frameId != null }
        if (!allowed) return
        previewJob?.cancel()
        local.update { it.copy(confirm = kind, message = null, preview = null, previewFailed = false, blockedByCount = false, previewing = true) }
        val frameId = s.frameId
        previewJob = viewModelScope.launch {
            var preview: ForgetPreview? = null; var failed = false
            try { preview = forget.preview(kind, memoryId, frameId) } catch (c: CancellationException) { throw c } catch (e: Exception) { failed = true }
            // A Moment or Scene scope never runs without its counts (the most destructive levels); the Photo scope, today's behaviour, still can.
            val blocked = failed && forget.activeScope() != com.rokid.context.memory.forget.ForgetScope.PHOTO
            local.update { l -> if (l.confirm != kind) l else l.copy(previewing = false, preview = preview, previewFailed = failed, blockedByCount = blocked) }
        }
    }

    fun dismissForget() { previewJob?.cancel(); local.update { it.copy(confirm = null, preview = null, previewing = false, previewFailed = false, blockedByCount = false) } }

    /** Runs once: a second tap while running (or after success) is ignored. Failure shows fixed copy, never the error. */
    fun confirmForget() {
        val kind = local.value.confirm ?: return
        if (local.value.previewing || local.value.blockedByCount) return // counts first: nothing runs while they are being counted or when a Moment/Scene scope could not count
        val preview = local.value.preview
        val frameId = state.value.frameId
        var start = false
        local.update { l -> if (l.forgetting || l.forgotten || l.confirm == null) l else { start = true; l.copy(confirm = null, forgetting = true, message = null, preview = null) } }
        if (!start) return
        viewModelScope.launch {
            var exportBlocked = false; var saveBlocked = false; var cacheLeft = false
            val ok = try {
                if (preview != null) forget.forgetScoped(kind, memoryId, frameId, preview.requested) // the scope the dialog showed
                else when (kind) { ForgetKind.FACT -> forget.forgetFact(memoryId); ForgetKind.MOMENT -> frameId != null && forget.forgetMoment(frameId) }
            } catch (c: CancellationException) {
                throw c
            } catch (e: com.rokid.context.orchestration.DataOpGate.WriteInProgress) {
                saveBlocked = true; false
            } catch (e: com.rokid.context.orchestration.DataOpGate.ExportInProgress) {
                exportBlocked = true; false
            } catch (e: CacheCleanupFailed) {
                cacheLeft = true; true // the Forget itself committed
            } catch (e: Exception) {
                false
            }
            local.update {
                if (ok) it.copy(forgetting = false, forgotten = true, message = if (cacheLeft) ForgetCopy.CACHE_LEFT else null)
                else it.copy(forgetting = false, message = if (saveBlocked) ForgetCopy.SAVE_RUNNING else if (exportBlocked) ForgetCopy.EXPORT_RUNNING else ForgetCopy.FAILED)
            }
        }
    }
}
