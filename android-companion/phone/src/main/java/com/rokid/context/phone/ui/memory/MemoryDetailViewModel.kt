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
        /** The frame viewer's photo being forgotten (no memory): Forget moment on that frame. */
        val photo: String? = null,
        val preview: ForgetPreview? = null, val acked: Boolean = false, val previewing: Boolean = false, val previewFailed: Boolean = false, val blockedByCount: Boolean = false,
    )
    private var previewJob: kotlinx.coroutines.Job? = null
    private val local = MutableStateFlow(Local())

    val state: StateFlow<MemoryDetailUiState> = combine(
        timeline.memory(memoryId).map { m -> m?.let { MemoryDetailModel.build(it, it.frameId?.let { id -> timeline.frame(id) }, zone()) } ?: MemoryDetailModel.NOT_FOUND },
        local,
    ) { base0, l ->
        val base = if (!base0.found && l.photo != null) base0.copy(loading = false, found = true, frameId = l.photo) else base0
        // `forgotten` survives the row disappearing (the Forget itself deletes it).
        if (base.found) base.copy(draft = l.draft, message = l.message, confirm = l.confirm, forgetting = l.forgetting, forgotten = l.forgotten,
            forgetPreview = l.preview, forgetPreviewing = l.previewing, forgetPreviewFailed = l.previewFailed, forgetConfirmable = !l.previewing && !l.blockedByCount && (l.preview?.needsAck != true || l.acked), forgetAcked = l.acked)
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

    /** The frame viewer's Forget: this photo (and what the Forget scope setting adds), through the same dialog and port as Forget moment. */
    fun askForgetPhoto(frameId: String) {
        if (state.value.forgetting) return
        local.update { it.copy(photo = frameId, forgotten = false) }
        openDialog(ForgetKind.MOMENT, frameId, keepMessage = false)
    }

    /** The viewer showed "Forgotten": clear it so the next photo can be forgotten. */
    fun forgetShown() = local.update { it.copy(forgotten = false, photo = null, message = null) }

    /** Opens the dialog. Forget fact needs a visible memory; Forget moment needs a frame. */
    fun askForget(kind: ForgetKind) {
        val s = state.value
        val allowed = s.found && !s.forgetting && when (kind) { ForgetKind.FACT -> s.editable; ForgetKind.MOMENT -> s.frameId != null }
        if (!allowed) return
        openDialog(kind, s.frameId, keepMessage = false)
    }

    private fun openDialog(kind: ForgetKind, frameId: String?, keepMessage: Boolean = true) {
        previewJob?.cancel()
        local.update { it.copy(confirm = kind, message = if (keepMessage) it.message else null, preview = null, acked = false, previewFailed = false, blockedByCount = false, previewing = true) }
        previewJob = viewModelScope.launch {
            var preview: ForgetPreview? = null; var failed = false
            try { preview = forget.preview(kind, memoryId, frameId) } catch (c: CancellationException) { throw c } catch (e: Exception) { failed = true }
            // A Moment or Scene scope never runs without its counts (the most destructive levels); the Photo scope, today's behaviour, still can.
            val blocked = (failed && forget.activeScope() != com.rokid.context.memory.forget.ForgetScope.PHOTO) || preview?.tooLarge == true
            local.update { l -> if (l.confirm != kind) l else l.copy(previewing = false, preview = preview, previewFailed = failed, blockedByCount = blocked) }
        }
    }

    /** The explicit acknowledgement of a large rebuild; the delete sends the preview's ackHash only once it is given. */
    fun acknowledgeForget(on: Boolean) = local.update { it.copy(acked = on) }

    fun dismissForget() { previewJob?.cancel(); local.update { it.copy(confirm = null, preview = null, previewing = false, previewFailed = false, blockedByCount = false) } }

    /** Runs once: a second tap while running (or after success) is ignored. Failure shows fixed copy, never the error. */
    fun confirmForget() {
        val kind = local.value.confirm ?: return
        if (local.value.previewing || local.value.blockedByCount) return // counts first: nothing runs while they are being counted or when a Moment/Scene scope could not count
        val preview = local.value.preview
        if (preview?.needsAck == true && !local.value.acked) return // a large rebuild is never confirmed without the explicit acknowledgement
        val frameId = state.value.frameId
        var start = false
        local.update { l -> if (l.forgetting || l.forgotten || l.confirm == null) l else { start = true; l.copy(confirm = null, forgetting = true, message = null, preview = null) } }
        if (!start) return
        viewModelScope.launch {
            var exportBlocked = false; var saveBlocked = false; var cacheLeft = false; var changed = false; var tooLarge = false
            val ok = try {
                if (preview != null) forget.forgetScoped(kind, memoryId, frameId, preview.requested, (if (preview.needsAck) preview.ackHash else preview.planHash).ifEmpty { null }) // the scope the dialog showed
                else when (kind) { ForgetKind.FACT -> forget.forgetFact(memoryId); ForgetKind.MOMENT -> frameId != null && forget.forgetMoment(frameId) }
            } catch (c: CancellationException) {
                throw c
            } catch (e: com.rokid.context.orchestration.DataOpGate.WriteInProgress) {
                saveBlocked = true; false
            } catch (e: ContentChanged) {
                changed = true; false
            } catch (e: TooLarge) {
                tooLarge = true; false
            } catch (e: com.rokid.context.orchestration.DataOpGate.ExportInProgress) {
                exportBlocked = true; false
            } catch (e: CacheCleanupFailed) {
                cacheLeft = true; true // the Forget itself committed
            } catch (e: Exception) {
                false
            }
            local.update {
                if (ok) it.copy(forgetting = false, forgotten = true, message = if (cacheLeft) ForgetCopy.CACHE_LEFT else null)
                else it.copy(forgetting = false, message = if (changed) ForgetCopy.CONTENT_CHANGED else if (tooLarge) ForgetScopeCopy.TOO_LARGE else if (saveBlocked) ForgetCopy.SAVE_RUNNING else if (exportBlocked) ForgetCopy.EXPORT_RUNNING else ForgetCopy.FAILED)
            }
            if (changed) openDialog(kind, frameId) // review again: the dialog opens once more with the NEW counts, the message stays
        }
    }
}
