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

    private fun vm() = MemorySettingsViewModel(
        MemorySettingsStore(ctx.getSharedPreferences("mem_settings_vm", Context.MODE_PRIVATE)), encryption, usedBytes = { 42L * 1024 * 1024 },
        wipe = { wiped++ }, export = { File(ctx.cacheDir, "e.json").apply { writeText("{}") } }, share = { shared += it }, reviewCount = flowOf(3),
    )

    @Test fun settingsEncryptionExportAndTheDoubleConfirm() = runBlocking {
        val vm = vm()
        val s0 = withTimeout(5_000) { vm.state.first { it.storage.isNotEmpty() } }
        assertEquals("42 MB of 100 MB", s0.storage); assertEquals(3, s0.reviewCount)
        vm.setShort(48); vm.setQuota(200)
        assertEquals(48, vm.state.value.settings.shortHours); assertEquals(200, vm.state.value.settings.quotaMb)
        vm.setEncryption(true)
        assertEquals("Turns on the next time Proactive Context starts", withTimeout(5_000) { vm.state.first { it.encryptionNote != null } }.encryptionNote)
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

    @Test fun screenShowsTheSecondConfirm() {
        var confirmed = 0
        compose.setContent { PcTheme { MemorySettingsScreen(MemorySettingsUiState(confirmStep = 2), onBack = {}, onShort = {}, onMedium = {}, onLong = {}, onQuota = {},
            onEncryption = {}, onExport = {}, onAskDeleteAll = {}, onConfirmDeleteAll = { confirmed++ }, onCancelDeleteAll = {}, onOpenReview = {}) } }
        compose.onNodeWithText("This can't be undone. Delete every memory, photo and answer?").assertExists()
        compose.onNodeWithTag("delete_confirm").performClick()
        assertEquals(1, confirmed)
    }
}
