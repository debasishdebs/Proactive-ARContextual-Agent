package com.rokid.context.phone.ui.settings.memory

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.ui.theme.PcTheme
import com.rokid.context.memory.DbEncryption
import com.rokid.context.memory.MemorySettings
import com.rokid.context.memory.MemorySettingsStore
import com.rokid.context.phone.ui.MainDispatcherRule
import com.rokid.context.phone.ui.cancelScope
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/** Task 8: the five per-kind retention rows (Photos, Skipped photos, Captions, Scene summaries, Image vectors). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class RetentionSettingsTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val compose = createComposeRule()
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val store by lazy { MemorySettingsStore(ctx.getSharedPreferences("retention_rows", Context.MODE_PRIVATE)) }

    private fun vm() = MemorySettingsViewModel(
        { store }, object : EncryptionControl {
            override fun status() = DbEncryption.Status(encrypted = false, pendingTarget = null, lastChangeFailed = false)
            override fun request(encrypted: Boolean) {}
        },
        usedBytes = { 0L }, wipe = {}, export = { File(ctx.cacheDir, "e.json") }, share = {}, reviewCount = flowOf(0), work = main.dispatcher,
    )

    private fun screen(s: MemorySettings) = compose.setContent {
        PcTheme {
            MemorySettingsScreen(MemorySettingsUiState(settings = s), onBack = {}, onRecall = {}, onQuota = {},
                onEncryption = {}, onExport = {}, onAskDeleteAll = {}, onConfirmDeleteAll = {}, onCancelDeleteAll = {}, onOpenReview = {})
        }
    }

    @Test fun rowsSaveChoices() {
        val vm = vm()
        assertEquals(MemorySettings(), vm.state.value.settings)
        vm.setPhotoDays(30); vm.setSkippedPhotoDays(0); vm.setCaptionDays(90); vm.setClusterSummaryDays(365); vm.setImageVectorDays(30)
        val s = vm.state.value.settings
        assertEquals(listOf(30, 0, 90, 365, 30), listOf(s.photoDays, s.skippedPhotoDays, s.captionDays, s.clusterSummaryDays, s.imageVectorDays))
        // persisted, and an offered value only
        assertEquals(s, MemorySettingsStore(ctx.getSharedPreferences("retention_rows", Context.MODE_PRIVATE)).settings.value)
        vm.setPhotoDays(5); vm.setSkippedPhotoDays(3)
        assertEquals(7, vm.state.value.settings.photoDays); assertEquals(1, vm.state.value.settings.skippedPhotoDays)
        vm.cancelScope()
    }

    @Test fun rowsAreShownWithTheUpgradeNote() {
        screen(MemorySettings())
        listOf("retention_recall", "retention_photo", "retention_skipped", "retention_vector").forEach { compose.onNodeWithTag(it).assertExists() }
        // retention v3 (K3): the rows that no longer delete anything are gone
        listOf("retention_short", "retention_medium", "retention_long", "retention_caption", "retention_summary", "retention_vector_warning").forEach { compose.onNodeWithTag(it).assertDoesNotExist() }
        compose.onNodeWithText("Photos you already have are kept at least 7 days from this update.").assertExists()
        compose.onNodeWithTag("retention_no_auto_delete_note").assertExists() // (the storage card is below the fold of the lazy list)
        assertEquals(true, MemorySettingsViewModel.CAP_NOTE.contains("never text") && MemorySettingsViewModel.CAP_NOTE.contains("14 days") && MemorySettingsViewModel.CAP_NOTE.contains("only when Pause clean-up is off") && !MemorySettingsViewModel.CAP_NOTE.contains("also runs"))
    }

    @Test fun theVectorWarningFunctionStillHoldsButTheRowIsGone() {
        screen(MemorySettings(imageVectorDays = 30, captionDays = 365))
        compose.onNodeWithTag("retention_vector_warning").assertDoesNotExist() // captions are no longer a setting
        assertEquals(true, MemorySettingsViewModel.vectorsShorterThanCaptions(MemorySettings(imageVectorDays = 30)))
        assertEquals(false, MemorySettingsViewModel.vectorsShorterThanCaptions(MemorySettings(imageVectorDays = 365, captionDays = 90)))
        assertEquals(false, MemorySettingsViewModel.vectorsShorterThanCaptions(MemorySettings()))
    }

    @Test fun noteSaysQuickRecallAndThatOnlyForgetRemovesText() {
        val note = MemorySettingsViewModel.retentionNote(MemorySettings(recallHours = 72))
        assertEquals(true, note.contains("stay in quick recall for 72 h"))
        assertEquals(true, note.contains("Nothing you saw is deleted by clean-up; only Forget removes text."))
        assertEquals(true, MemorySettingsViewModel.retentionNote(MemorySettings(recallHours = 168)).contains("for 7 days"))
        assertEquals("the labels follow the choices", listOf("12 h", "24 h", "48 h", "72 h", "7 days"), com.rokid.context.memory.MemorySettingsStore.RECALL_HOURS.map { MemorySettingsViewModel.recallLabel(it) })
    }

    /** I2: the note is the same whatever the photo window (skipped photos included), and goes once the 7 days are over. */
    @Test fun upgradeNoteIsExactAndTemporary() {
        val text = "Photos you already have are kept at least 7 days from this update."
        assertEquals(text, MemorySettingsViewModel.upgradeNote(MemorySettings(photoDays = 30), 1_000L))
        assertEquals(text, MemorySettingsViewModel.upgradeNote(MemorySettings(photoDays = 1, skippedPhotoDays = 0, perKindSince = 1_000L), 1_000L + 6 * 24 * 3_600_000L))
        assertEquals(null, MemorySettingsViewModel.upgradeNote(MemorySettings(perKindSince = 1_000L), 1_000L + 8 * 24 * 3_600_000L))
    }
}
