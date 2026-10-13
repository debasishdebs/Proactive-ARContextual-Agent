package com.rokid.context.phone.ui.settings.memory

import android.util.Log
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rokid.context.memory.DbEncryption
import com.rokid.context.memory.MemorySettings
import com.rokid.context.memory.MemorySettingsStore
import com.rokid.context.phone.ui.settings.ChoiceRow
import com.rokid.context.phone.ui.settings.NoticeText
import com.rokid.context.phone.ui.settings.PrimaryAction
import com.rokid.context.phone.ui.settings.SecondaryAction
import com.rokid.context.phone.ui.settings.SettingsCard
import com.rokid.context.phone.ui.settings.SettingsPage
import com.rokid.context.phone.ui.settings.SwitchRow
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

/** The phone's view of the encryption toggle (Task 14 applies it at the next start). */
interface EncryptionControl {
    fun status(): DbEncryption.Status
    fun request(encrypted: Boolean)
}

data class MemorySettingsUiState(
    val settings: MemorySettings = MemorySettings(),
    val storage: String = "",
    val encryptionOn: Boolean = false,
    val encryptionNote: String? = null,
    val reviewCount: Int = 0,
    val busy: Boolean = false,
    /** 0 = none, 1 = first "Delete all?" dialog, 2 = second, final confirm. */
    val confirmStep: Int = 0,
    val notice: String? = null,
)

class MemorySettingsViewModel(
    private val store: MemorySettingsStore,
    private val encryption: EncryptionControl,
    private val usedBytes: suspend () -> Long,
    private val wipe: suspend () -> Unit,
    private val export: suspend () -> File,
    private val share: (File) -> Unit,
    reviewCount: Flow<Int>,
    /** Prefs reads/commits, file sizes, the wipe and the export run here, never on Main. */
    private val work: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val _state = MutableStateFlow(MemorySettingsUiState(settings = store.settings.value))
    val state: StateFlow<MemorySettingsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { store.settings.collect { s -> _state.update { it.copy(settings = s) }; refreshStorage() } }
        viewModelScope.launch { reviewCount.collect { n -> _state.update { it.copy(reviewCount = n) } } }
        viewModelScope.launch { refreshEncryption() }
    }

    fun setShort(h: Int) = store.update { it.copy(shortHours = h) }
    fun setMedium(d: Int) = store.update { it.copy(mediumDays = d) }
    fun setLong(d: Int) = store.update { it.copy(longDays = d) }
    fun setQuota(mb: Int) = store.update { it.copy(quotaMb = mb) }

    fun setEncryption(on: Boolean) = viewModelScope.launch {
        withContext(work) { encryption.request(on) }
        refreshEncryption()
    }

    fun export() = busy {
        val f = withContext(work) { export.invoke() }
        share(f)
        _state.update { it.copy(notice = "Export ready") }
    }

    fun askDeleteAll() = _state.update { it.copy(confirmStep = 1, notice = null) }
    fun cancelDeleteAll() = _state.update { it.copy(confirmStep = 0) }
    fun confirmDeleteAll() {
        when (_state.value.confirmStep) {
            1 -> _state.update { it.copy(confirmStep = 2) }
            2 -> { _state.update { it.copy(confirmStep = 0) }; busy { withContext(work) { wipe() }; _state.update { it.copy(notice = "Everything was deleted") }; refreshStorage() } }
        }
    }

    private suspend fun refreshEncryption() {
        val s = withContext(work) { encryption.status() }
        val target = s.pendingTarget
        _state.update {
            it.copy(
                encryptionOn = target ?: (s.encrypted == true),
                encryptionNote = when {
                    target == true -> "Turns on the next time Proactive Context starts"
                    target == false -> "Turns off the next time Proactive Context starts"
                    s.lastChangeFailed -> "The last change didn't finish — your memory is unchanged"
                    else -> null
                },
            )
        }
    }

    private suspend fun refreshStorage() {
        val used = try { withContext(work) { usedBytes() } } catch (c: CancellationException) { throw c } catch (e: Exception) {
            Log.i(TAG, "storage size unavailable (${e.javaClass.simpleName})"); 0L
        }
        _state.update { it.copy(storage = "${used / (1024 * 1024)} MB of ${it.settings.quotaMb} MB") }
    }

    private fun busy(block: suspend () -> Unit) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, notice = null) }
        viewModelScope.launch {
            try { block() } catch (c: CancellationException) { throw c } catch (e: Exception) {
                Log.i(TAG, "memory action failed (${e.javaClass.simpleName})")
                _state.update { it.copy(notice = "That didn't work — try again") }
            } finally { _state.update { it.copy(busy = false) } }
        }
    }

    private companion object { const val TAG = "MemorySettings" }
}

@Composable
fun MemorySettingsScreen(
    state: MemorySettingsUiState,
    onBack: () -> Unit,
    onShort: (Int) -> Unit,
    onMedium: (Int) -> Unit,
    onLong: (Int) -> Unit,
    onQuota: (Int) -> Unit,
    onEncryption: (Boolean) -> Unit,
    onExport: () -> Unit,
    onAskDeleteAll: () -> Unit,
    onConfirmDeleteAll: () -> Unit,
    onCancelDeleteAll: () -> Unit,
    onOpenReview: () -> Unit,
) {
    val s = state.settings
    SettingsPage("Memory", onBack, tag = "memory_settings") {
        item {
            SettingsCard {
                Text("Keep memories", style = MaterialTheme.typography.titleMedium)
                val short = MemorySettingsStore.SHORT_HOURS; val medium = MemorySettingsStore.MEDIUM_DAYS; val long = MemorySettingsStore.LONG_DAYS
                ChoiceRow("Short-term", short.map { "$it h" }, short.indexOf(s.shortHours), { onShort(short[it]) }, tag = "retention_short")
                ChoiceRow("Medium-term", medium.map { "$it days" }, medium.indexOf(s.mediumDays), { onMedium(medium[it]) }, tag = "retention_medium")
                ChoiceRow("Long-term", long.map { "$it days" }, long.indexOf(s.longDays), { onLong(long[it]) }, tag = "retention_long")
                NoticeText("Automatic clean-up arrives together with Forget.")
            }
        }
        item {
            SettingsCard {
                Text("Storage", style = MaterialTheme.typography.titleMedium)
                NoticeText(state.storage, tag = "storage_used")
                val quotas = listOf(50, 100, 250, 500, 1_000)
                val options = (quotas + s.quotaMb).distinct().sorted()
                ChoiceRow("Soft limit", options.map { "$it MB" }, options.indexOf(s.quotaMb), { onQuota(options[it]) }, tag = "quota")
                SwitchRow("Encrypt memory", "Protects the memory database with a key kept in this phone", state.encryptionOn, onEncryption, tag = "encryption")
                state.encryptionNote?.let { NoticeText(it, tag = "encryption_note") }
            }
        }
        item {
            SettingsCard {
                SecondaryAction(if (state.reviewCount > 0) "Needs review (${state.reviewCount})" else "Needs review", onOpenReview, tag = "open_review")
                SecondaryAction("Export memories", onExport, tag = "export", enabled = !state.busy)
                PrimaryAction("Delete all", onAskDeleteAll, tag = "delete_all", enabled = !state.busy)
                state.notice?.let { NoticeText(it, tag = "memory_notice") }
            }
        }
    }
    if (state.confirmStep > 0) {
        AlertDialog(
            onDismissRequest = onCancelDeleteAll,
            title = { Text(if (state.confirmStep == 1) "Delete all memories?" else "Are you sure?") },
            text = { Text(if (state.confirmStep == 1) "Every memory, photo, answer and nudge will be removed." else "This can't be undone. Delete every memory, photo and answer?") },
            confirmButton = { TextButton(onClick = onConfirmDeleteAll, modifier = Modifier.testTag("delete_confirm")) { Text(if (state.confirmStep == 1) "Continue" else "Delete everything") } },
            dismissButton = { TextButton(onClick = onCancelDeleteAll, modifier = Modifier.testTag("delete_cancel")) { Text("Cancel") } },
        )
    }
}
