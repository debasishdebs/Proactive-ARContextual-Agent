package com.rokid.context.phone.ui.settings.memory

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.ui.theme.PcTheme
import com.rokid.context.memory.DbEncryption
import com.rokid.context.memory.MemorySettingsStore
import com.rokid.context.phone.ui.MainDispatcherRule
import com.rokid.context.phone.ui.cancelScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class MemorySettingsTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val compose = createComposeRule()
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private var wiped = 0
    private val shared = mutableListOf<File>()
    private var status = DbEncryption.Status(encrypted = false, pendingTarget = null, lastChangeFailed = false)
    private val encryption = object : EncryptionControl {
        override fun status() = status
        override fun request(encrypted: Boolean) { status = status.copy(pendingTarget = encrypted) }
    }

    private var exportGate: CompletableDeferred<Unit>? = null
    private var storeReads = 0
    private val store by lazy { MemorySettingsStore(ctx.getSharedPreferences("mem_settings_vm", Context.MODE_PRIVATE)) }

    /** [work] = the test's Main dispatcher: off-Main work runs inline, so every step is deterministic. */
    private fun vm() = MemorySettingsViewModel(
        { storeReads++; store }, encryption, usedBytes = { 42L * 1024 * 1024 },
        wipe = { wiped++ }, export = { exportGate?.await(); File(ctx.cacheDir, "e.json").apply { writeText("{}") } }, share = { shared += it }, reviewCount = flowOf(3),
        work = main.dispatcher,
    )

    @Test fun settingsEncryptionExportAndTheDoubleConfirm() = runBlocking {
        val vm = vm()
        val s0 = withTimeout(5_000) { vm.state.first { it.storage.isNotEmpty() } }
        assertEquals("42 MB of 100 MB", s0.storage); assertEquals(3, s0.reviewCount)
        vm.setShort(48); vm.setQuota(200)
        assertEquals(48, vm.state.value.settings.shortHours); assertEquals(200, vm.state.value.settings.quotaMb)
        vm.setEncryption(true)
        assertEquals(MemorySettingsViewModel.ENCRYPTION_ON_NOTE, withTimeout(5_000) { vm.state.first { it.encryptionNote != null } }.encryptionNote)
        vm.export()
        withTimeout(5_000) { vm.state.first { !it.busy && shared.isNotEmpty() } }
        vm.askDeleteAll(); assertEquals(1, vm.state.value.confirmStep)
        vm.confirmDeleteAll(); assertEquals(2, vm.state.value.confirmStep); assertEquals(0, wiped)
        vm.confirmDeleteAll()
        assertEquals("Everything was deleted", withTimeout(5_000) { vm.state.first { it.notice != null } }.notice)
        assertEquals(1, wiped)
        vm.askDeleteAll(); vm.cancelDeleteAll(); assertEquals(0, vm.state.value.confirmStep)
        vm.cancelScope()
    }

    /** S1: retention runs daily since Phase 7; the note describes the windows in force, not "arrives with Forget". */
    @Test fun retentionNoteDescribesTheActiveWindows() {
        assertEquals(
            "Once a day, short-term memories older than 24 h, medium-term older than 7 days and long-term older than 30 days are removed. " +
                "Photos are removed 7 days after they were taken (skipped photos after 1 day); captions and scene summaries stay until their own setting says otherwise.",
            MemorySettingsViewModel.retentionNote(com.rokid.context.memory.MemorySettings()),
        )
        val s = com.rokid.context.memory.MemorySettings(shortHours = 48, mediumDays = 14, longDays = 365)
        compose.setContent { PcTheme { MemorySettingsScreen(MemorySettingsUiState(settings = s), onBack = {}, onShort = {}, onMedium = {}, onLong = {}, onQuota = {},
            onEncryption = {}, onExport = {}, onAskDeleteAll = {}, onConfirmDeleteAll = {}, onCancelDeleteAll = {}, onOpenReview = {}) } }
        compose.onNodeWithText(MemorySettingsViewModel.retentionNote(s)).assertExists()
        compose.onNodeWithText("older than 365 days", substring = true).assertExists()
        compose.onNodeWithText("Automatic clean-up arrives together with Forget.").assertDoesNotExist()
    }

    @Test fun screenShowsTheSecondConfirm() {
        var confirmed = 0
        compose.setContent { PcTheme { MemorySettingsScreen(MemorySettingsUiState(confirmStep = 2), onBack = {}, onShort = {}, onMedium = {}, onLong = {}, onQuota = {},
            onEncryption = {}, onExport = {}, onAskDeleteAll = {}, onConfirmDeleteAll = { confirmed++ }, onCancelDeleteAll = {}, onOpenReview = {}) } }
        compose.onNodeWithText("This can't be undone. Delete every memory, photo and answer?").assertExists()
        compose.onNodeWithTag("delete_confirm").performClick()
        assertEquals(1, confirmed)
    }

    /** Review M6: the note says a restart applies it and how (the hub service keeps the process alive). */
    @Test fun theEncryptionNoteSaysToRestartTheApp() {
        assertTrue(MemorySettingsViewModel.ENCRYPTION_ON_NOTE.startsWith("Turns on after Proactive Context restarts. Restart the app to apply"))
        assertTrue(MemorySettingsViewModel.ENCRYPTION_OFF_NOTE.startsWith("Turns off after Proactive Context restarts. Restart the app to apply"))
        assertTrue(MemorySettingsViewModel.ENCRYPTION_ON_NOTE.contains("Force stop"))
    }

    /** Review M5: the final confirm during an export says so instead of closing silently; nothing is wiped. */
    @Test fun theFinalConfirmDuringAnExportSaysSo() {
        exportGate = CompletableDeferred()
        val vm = vm()
        vm.export(); assertTrue(vm.state.value.busy)
        vm.askDeleteAll(); vm.confirmDeleteAll(); vm.confirmDeleteAll()
        assertEquals("Export in progress — try again", vm.state.value.notice); assertEquals(0, vm.state.value.confirmStep); assertEquals(0, wiped)
        exportGate!!.complete(Unit)
        vm.cancelScope()
    }

    /** Review M7: the prefs-backed store is reached on the work dispatcher, not in the ViewModel factory on Main. */
    @Test fun theStoreIsNotReadWhenTheViewModelIsBuilt() {
        val vm = MemorySettingsViewModel({ storeReads++; store }, encryption, usedBytes = { 0L }, wipe = {}, export = { File("x") }, share = {},
            reviewCount = flowOf(0), work = kotlinx.coroutines.test.StandardTestDispatcher(main.dispatcher.scheduler))
        assertEquals(0, storeReads)
        main.dispatcher.scheduler.advanceUntilIdle()
        assertTrue(storeReads > 0)
        vm.cancelScope()
    }
}
