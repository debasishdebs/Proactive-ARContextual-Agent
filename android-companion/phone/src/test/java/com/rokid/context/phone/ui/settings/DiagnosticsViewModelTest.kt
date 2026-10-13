package com.rokid.context.phone.ui.settings

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.phone.ui.FakePipelineControl
import com.rokid.context.phone.ui.MainDispatcherRule
import com.rokid.context.phone.ui.cancelScope
import kotlinx.coroutines.flow.first
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
}
