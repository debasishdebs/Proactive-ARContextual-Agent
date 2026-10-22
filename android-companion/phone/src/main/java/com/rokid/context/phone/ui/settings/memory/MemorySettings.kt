package com.rokid.context.phone.ui.settings.memory

import android.util.Log
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.rokid.context.work.frames.scene.WatchListStore
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
    /** Prefs-backed (lazy in SettingsDeps): reached only on [work], never in the ViewModel factory on Main (review M7). */
    private val store: () -> MemorySettingsStore,
    private val encryption: EncryptionControl,
    private val usedBytes: suspend () -> Long,
    private val wipe: suspend () -> Unit,
    private val export: suspend () -> File,
    private val share: (File) -> Unit,
    reviewCount: Flow<Int>,
    /** Prefs reads/commits, file sizes, the wipe and the export run here, never on Main. */
    private val work: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val _state = MutableStateFlow(MemorySettingsUiState())
    val state: StateFlow<MemorySettingsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { withContext(work) { store() }.settings.collect { s -> _state.update { it.copy(settings = s) }; refreshStorage() } }
        viewModelScope.launch { reviewCount.collect { n -> _state.update { it.copy(reviewCount = n) } } }
        viewModelScope.launch { refreshEncryption() }
    }

    fun setShort(h: Int) = edit { it.copy(shortHours = h) }
    fun setMedium(d: Int) = edit { it.copy(mediumDays = d) }
    fun setLong(d: Int) = edit { it.copy(longDays = d) }
    fun setQuota(mb: Int) = edit { it.copy(quotaMb = mb) }
    fun setPhotoDays(d: Int) = edit { it.copy(photoDays = d) }
    fun setSkippedPhotoDays(d: Int) = edit { it.copy(skippedPhotoDays = d) }
    fun setCaptionDays(d: Int) = edit { it.copy(captionDays = d) }
    fun setClusterSummaryDays(d: Int) = edit { it.copy(clusterSummaryDays = d) }
    fun setImageVectorDays(d: Int) = edit { it.copy(imageVectorDays = d) }

    private fun edit(transform: (MemorySettings) -> MemorySettings) { viewModelScope.launch(work) { store().update(transform) } }

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
            2 -> {
                // Review M5: an export still running blocks the wipe; say so rather than closing silently.
                if (_state.value.busy) { _state.update { it.copy(confirmStep = 0, notice = "Export in progress — try again") }; return }
                _state.update { it.copy(confirmStep = 0) }
                busy { withContext(work) { wipe() }; _state.update { it.copy(notice = "Everything was deleted") }; refreshStorage() }
            }
        }
    }

    private suspend fun refreshEncryption() {
        val s = withContext(work) { encryption.status() }
        val target = s.pendingTarget
        _state.update {
            it.copy(
                encryptionOn = target ?: (s.encrypted == true),
                encryptionNote = when {
                    target == true -> ENCRYPTION_ON_NOTE
                    target == false -> ENCRYPTION_OFF_NOTE
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

    companion object {
        private const val TAG = "MemorySettings"
        /** Task 14 applies the change when the database next opens; the hub service keeps the process alive (review M6). */
        private const val RESTART_HINT = "Restart the app to apply: Android Settings › Apps › Proactive Context › Force stop, then open it again."
        const val ENCRYPTION_ON_NOTE = "Turns on after Proactive Context restarts. $RESTART_HINT"
        const val ENCRYPTION_OFF_NOTE = "Turns off after Proactive Context restarts. $RESTART_HINT"

        /** S1: the daily RetentionWorker enforces these windows (by creation time) and the per-kind ones below (spec §P6.1). */
        fun retentionNote(s: com.rokid.context.memory.MemorySettings): String =
            "Once a day, short-term memories older than ${s.shortHours} h, medium-term older than ${s.mediumDays} days and " +
                "long-term older than ${s.longDays} days are removed. " +
                (if (s.photoDays == MemorySettings.FOREVER) "Photos are kept" else "Photos are removed ${keepLabel(s.photoDays)} after they were taken") +
                " (skipped photos ${when (s.skippedPhotoDays) { 0 -> "at the next clean-up"; 1 -> "after 1 day"; else -> "after ${s.skippedPhotoDays} days" }}); " +
                "captions and scene summaries stay until their own setting says otherwise."

        const val UPGRADE_NOTE = "Photos you already have are kept at least 7 days from this update."

        /** Ruling 13 / fix round 1 I2: every photo taken before the update is kept 7 days from the stamp, whatever its kind; shown while that holds. */
        fun upgradeNote(s: MemorySettings, nowMs: Long = System.currentTimeMillis()): String? =
            if (s.perKindSince == 0L || nowMs < s.perKindSince + 7 * 24 * 3_600_000L) UPGRADE_NOTE else null

        /** Image vectors drive photo search: shorter than captions, old photos are findable only by their text. */
        fun vectorsShorterThanCaptions(s: MemorySettings): Boolean =
            s.imageVectorDays != MemorySettings.FOREVER && (s.captionDays == MemorySettings.FOREVER || s.imageVectorDays < s.captionDays)

        const val VECTOR_WARNING = "Image vectors expire before captions: older photos can then only be found by their caption text."

        /** "7 days", "Forever", and "Next clean-up" for a skipped-photo window of 0. */
        fun keepLabel(days: Int, zeroLabel: String = "0 days"): String = when (days) {
            MemorySettings.FOREVER -> "Forever"; 0 -> zeroLabel; 1 -> "1 day"; else -> "$days days"
        }
    }
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
    onPhotoDays: (Int) -> Unit = {},
    onSkippedPhotoDays: (Int) -> Unit = {},
    onCaptionDays: (Int) -> Unit = {},
    onClusterSummaryDays: (Int) -> Unit = {},
    onImageVectorDays: (Int) -> Unit = {},
    watchList: List<String> = WatchListStore.DEFAULTS,
    onWatchListSave: (List<String>) -> Unit = {},
    onWatchListReset: () -> Unit = {},
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
                NoticeText(MemorySettingsViewModel.retentionNote(s), tag = "retention_note")
            }
        }
        item {
            SettingsCard {
                Text("Keep by kind", style = MaterialTheme.typography.titleMedium)
                val photo = MemorySettingsStore.PHOTO_DAYS; val skipped = MemorySettingsStore.SKIPPED_DAYS; val kind = MemorySettingsStore.LONG_KIND_DAYS
                fun label(d: Int, zero: String = "0 days") = MemorySettingsViewModel.keepLabel(d, zero)
                ChoiceRow("Photos", photo.map { label(it) }, photo.indexOf(s.photoDays), { onPhotoDays(photo[it]) }, tag = "retention_photo")
                ChoiceRow("Skipped photos", skipped.map { label(it, "Next clean-up") }, skipped.indexOf(s.skippedPhotoDays), { onSkippedPhotoDays(skipped[it]) }, tag = "retention_skipped")
                ChoiceRow("Captions", kind.map { label(it) }, kind.indexOf(s.captionDays), { onCaptionDays(kind[it]) }, tag = "retention_caption")
                ChoiceRow("Scene summaries", kind.map { label(it) }, kind.indexOf(s.clusterSummaryDays), { onClusterSummaryDays(kind[it]) }, tag = "retention_summary")
                ChoiceRow("Image vectors", kind.map { label(it) }, kind.indexOf(s.imageVectorDays), { onImageVectorDays(kind[it]) }, tag = "retention_vector")
                if (MemorySettingsViewModel.vectorsShorterThanCaptions(s)) NoticeText(MemorySettingsViewModel.VECTOR_WARNING, tag = "retention_vector_warning")
                MemorySettingsViewModel.upgradeNote(s)?.let { NoticeText(it, tag = "retention_upgrade_note") }
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
        item { SettingsCard { WatchListRow(watchList, onWatchListSave, onWatchListReset) } }
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
            text = { Text(if (state.confirmStep == 1) "Every memory, photo, answer and nudge will be removed. The Watch and Image intent sources and \"Send matching photos with questions\" also turn off, and your My car list is cleared." else "This can't be undone. Delete every memory, photo and answer?") },
            confirmButton = { TextButton(onClick = onConfirmDeleteAll, modifier = Modifier.testTag("delete_confirm")) { Text(if (state.confirmStep == 1) "Continue" else "Delete everything") } },
            dismissButton = { TextButton(onClick = onCancelDeleteAll, modifier = Modifier.testTag("delete_cancel")) { Text("Cancel") } },
        )
    }
}

/** "Watch list": things worth a photo, as one comma-separated field. Save normalises (trim, dedupe, caps); Reset restores the defaults. */
@Composable
fun WatchListRow(items: List<String>, onSave: (List<String>) -> Unit, onReset: () -> Unit) {
    var text by remember(items) { mutableStateOf(items.joinToString(", ")) }
    Text("Watch list", style = MaterialTheme.typography.titleMedium)
    NoticeText("Photos of these things are kept as important. Separate items with commas.", tag = "watch_list_note")
    OutlinedTextField(value = text, onValueChange = { text = it }, modifier = Modifier.testTag("watch_list_field"))
    SecondaryAction("Save", { onSave(text.split(',')) }, tag = "watch_list_save")
    SecondaryAction("Reset to defaults", onReset, tag = "watch_list_reset")
}
