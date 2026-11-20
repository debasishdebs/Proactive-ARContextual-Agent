package com.rokid.context.phone.ui.settings

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.phone.ui.FakePipelineControl
import com.rokid.context.phone.ui.MainDispatcherRule
import com.rokid.context.phone.ui.cancelScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

// Robolectric because the failure path logs through android.util.Log.
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class DiagnosticsViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val calls = mutableListOf<String>()
    private var failWith: Exception? = null
    private val actions = object : DiagnosticsActions {
        override suspend fun summarizeNow(): String { calls += "summarize"; failWith?.let { throw it }; return "Summarizer queued" }
        override suspend fun captureNow(): String { calls += "capture"; return "Capture pipeline queued" }
        override suspend fun reprocessOldPhotos(): String { calls += "reprocess"; return "Historical backfill queued." }
    }
    private val pipeline = FakePipelineControl()
    private val vm = DiagnosticsViewModel(actions, pipeline)

    @After fun tearDown() = vm.cancelScope()

    @Test fun runsActionsAndShowsTheirMessage() = runBlocking {
        vm.summarizeNow()
        assertEquals("Summarizer queued", withTimeout(5_000) { vm.state.first { it.message != null } }.message)
        vm.reprocess()
        assertEquals("Historical backfill queued.", withTimeout(5_000) { vm.state.first { it.message?.startsWith("Historical") == true } }.message)
        assertEquals(listOf("summarize", "reprocess"), calls)
    }

    @Test fun pausedPipelineBlocksSummarizeAndCaptureButNotReprocess() = runBlocking {
        pipeline.setEnabled(false)
        vm.summarizeNow(); vm.captureNow()
        assertEquals(DiagnosticsViewModel.PAUSED, vm.state.value.message)
        assertEquals(emptyList<String>(), calls)
        vm.reprocess()
        withTimeout(5_000) { vm.state.first { it.message?.startsWith("Historical") == true } }
        assertEquals(listOf("reprocess"), calls)
    }

    /** Phase 7 fix round 1 (M2): a capture pause is capture-only; Summarize now follows the processing flag. */
    @Test fun aCapturePauseDoesNotBlockSummarize() = runBlocking {
        val capturePaused = object : com.rokid.context.phone.ui.status.PipelineControl {
            override val enabled = kotlinx.coroutines.flow.MutableStateFlow(false)
            override val processingEnabled = kotlinx.coroutines.flow.MutableStateFlow(true)
            override fun setEnabled(enabled: Boolean) = Unit
            override fun toggle() = Unit
        }
        val v = DiagnosticsViewModel(actions, capturePaused)
        try {
            v.summarizeNow()
            assertEquals("Summarizer queued", withTimeout(5_000) { v.state.first { it.message != null } }.message)
            capturePaused.processingEnabled.value = false
            v.captureNow()
            assertEquals(DiagnosticsViewModel.PAUSED, v.state.value.message)
        } finally { v.cancelScope() }
    }

    @Test fun failureShowsAFixedMessage() = runBlocking {
        failWith = IllegalStateException("No Rokid frames available for summarization sk-live-1")
        vm.summarizeNow()
        assertEquals(DiagnosticsViewModel.FAILED, withTimeout(5_000) { vm.state.first { it.message != null } }.message)
    }

    @Test fun exportSharesTheFileAndPushResendsSettings() = runBlocking {
        val shared = mutableListOf<java.io.File>(); var pushed = 0
        val v = DiagnosticsViewModel(actions, pipeline, exportDiagnostics = { java.io.File.createTempFile("diag", ".txt") }, share = { shared += it }, pushSettings = { pushed++ })
        v.exportDiagnostics(); v.pushSettings()
        withTimeout(5_000) { v.state.first { !it.busy && shared.isNotEmpty() } }
        assertEquals(1, pushed); assertEquals("Settings sent to the glasses", v.state.value.message)
    }

    /** G19: Export settings writes the JSON and shares it through the same share path. */
    @Test fun exportSettingsSharesTheJson() = runBlocking {
        val shared = mutableListOf<java.io.File>()
        val json = java.io.File.createTempFile("settings", ".json")
        val v = DiagnosticsViewModel(actions, pipeline, share = { shared += it }, exportSettings = { json })
        v.exportSettings()
        assertEquals(DiagnosticsViewModel.SETTINGS_READY, withTimeout(5_000) { v.state.first { !it.busy && it.message != null } }.message)
        assertEquals(listOf(json), shared)
        v.cancelScope()
    }

    /** Fix round 1 (m6): Push during an export keeps the busy guard; the export's own result still lands. */
    @Test fun pushDuringAnExportKeepsItBusy() = runBlocking {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>(); var pushed = 0
        val shared = mutableListOf<java.io.File>()
        val v = DiagnosticsViewModel(actions, pipeline, exportDiagnostics = { gate.await(); java.io.File.createTempFile("diag", ".txt") }, share = { shared += it }, pushSettings = { pushed++ })
        v.exportDiagnostics()
        assertTrue(v.state.value.busy)
        v.pushSettings()
        assertTrue("still busy", v.state.value.busy)
        assertEquals(1, pushed)
        gate.complete(Unit)
        assertEquals("Diagnostics ready to share", withTimeout(5_000) { v.state.first { !it.busy } }.message)
        assertEquals(1, shared.size)
        v.cancelScope()
    }

    @Test fun pushWithoutLinkedGlassesSaysItWillSyncLater() {
        var pushed = 0
        val v = DiagnosticsViewModel(actions, pipeline, pushSettings = { pushed++ }, glassesConnected = { false })
        v.pushSettings()
        assertEquals(1, pushed)
        assertEquals("Glasses not connected — settings will sync when they connect", v.state.value.message)
        v.cancelScope()
    }

    /**
     * Codex r20 P1: a suspect's snippet must leave Diagnostics as soon as its memory is deleted (RetentionWorker, Forget, Delete all), even while the screen stays
     * open and nothing else emits: the list observes the database, and no snippet is held outside that observation.
     */
    @Test fun aDeletedSuspectLeavesTheOpenScreenAtOnce() = runBlocking {
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = com.rokid.context.memory.db.DatabaseFactory.inMemory(ctx)
        val dao = db.forgetDao()
        val before = com.rokid.context.memory.db.ForgetDao.TEXT_RETRY_FIXED_MS - 1
        db.frameDao().insertIgnore(com.rokid.context.memory.db.FrameEntity("p", 1, "/p.jpg", "rokid_glasses"))
        db.memoryDao().insert(com.rokid.context.memory.db.MemoryEntity("rokid_kf_old_c0", "p", "A desk with keys", "note", "", com.rokid.context.memory.db.MemoryTier.SHORT, 0.8,
            com.rokid.context.memory.db.ReviewState.OK, false, before, false, "A desk with keys", null, null))
        val keyframes = object : KeyframeDiagnostics {
            override val stats = kotlinx.coroutines.flow.MutableStateFlow(com.rokid.context.work.frames.KeyframeStats())
            override suspend fun droppedNudges() = emptyMap<String, Int>()
            override suspend fun sidecarLost() = 0
            override suspend fun promptIsCurrent() = true
            override suspend fun retryAllFinal() = 0
            override val suspects = suspectDescriptionsOf(dao)
        }
        val v = DiagnosticsViewModel(actions, pipeline, keyframes = keyframes)
        try {
            val shown = withTimeout(5_000) { v.keyframeRows.first { it.suspects.total == 1 } }
            assertEquals("A desk with keys", shown.suspects.items.single().text)
            // every emission from now on is checked, not just the one that ends the wait: a count of zero must never sit beside a leftover snippet.
            // The watcher starts BEFORE the delete so no emission between the delete and the watcher goes unchecked.
            val seen = java.util.Collections.synchronizedList(mutableListOf<SuspectDescriptions>())
            val watcher = launch(kotlinx.coroutines.Dispatchers.Unconfined) { v.keyframeRows.collect { seen += it.suspects } }
            dao.deleteMemories(listOf("rokid_kf_old_c0")) // what RetentionWorker does to an expired SHORT memory
            withTimeout(5_000) { v.keyframeRows.first { it.suspects.total == 0 } }
            watcher.cancel()
            assertTrue("no snippet of the deleted memory is left: $seen", seen.all { it.total != 0 || it.items.isEmpty() })
            assertTrue("no snippet of the deleted memory is left", v.keyframeRows.value.suspects.items.isEmpty())
        } finally { v.cancelScope(); db.close() }
    }

    /** One database snapshot is one SuspectDescriptions: a total of 0 never appears beside snippets, whatever the rows' timing. */
    @Test fun suspectCountAndSnippetsComeFromOneSnapshot() = runBlocking {
        fun row(id: String, total: Int) = com.rokid.context.memory.db.SuspectObservedRow(id, "p", "note", 1L, "text $id", total)
        val snapshots = kotlinx.coroutines.flow.flowOf(listOf(row("a", 2), row("b", 2)), emptyList(), listOf(row("c", 1)))
        val out = suspectDescriptionsFrom(snapshots).toList()
        assertEquals(listOf(2 to 2, 0 to 0, 1 to 1), out.map { it.total to it.items.size })
        assertTrue(out.all { it.total != 0 || it.items.isEmpty() })
    }
}
