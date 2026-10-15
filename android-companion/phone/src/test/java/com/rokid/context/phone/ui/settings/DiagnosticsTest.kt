package com.rokid.context.phone.ui.settings

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.ui.theme.PcTheme
import com.rokid.context.model.CallClass
import com.rokid.context.phone.ui.FakePipelineControl
import com.rokid.context.phone.ui.MainDispatcherRule
import com.rokid.context.phone.ui.cancelScope
import com.rokid.context.work.frames.KeyframeStats
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Settings > Diagnostics > Keyframes: counts only, from the pipeline's stats. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class DiagnosticsTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val compose = createComposeRule()

    private val stats = KeyframeStats(
        counts = mapOf("PENDING" to 3, "EXTRACTED" to 40, "FAILED_FINAL" to 2), callsPerHour = mapOf(CallClass.USER to 1, CallClass.EXTRACT to 7, CallClass.BACKFILL to 2),
        keyframesPerHour = 55, backlog = 3, latencyP50Ms = 4_000, latencyP95Ms = 12_000,
    )

    private fun show(rows: KeyframeRows, onRetry: () -> Unit = {}) {
        compose.setContent { PcTheme { DiagnosticsScreen(DiagnosticsUiState(), {}, {}, {}, {}, {}, {}, {}, {}, {}, keyframes = rows, onRetryAll = onRetry) } }
    }

    @Test fun showsBacklogAndLatency() {
        show(KeyframeRows(stats, droppedNudges = mapOf("SHOWN_GAP" to 2), sidecarLost = 4))
        compose.onNodeWithTag("kf_backlog").assertTextContains("Backlog 3", substring = true).assertTextContains("PENDING 3", substring = true).assertTextContains("FAILED_FINAL 2", substring = true)
        compose.onNodeWithTag("kf_rate").assertTextContains("55", substring = true)
        compose.onNodeWithTag("kf_calls").assertTextContains("USER 1", substring = true).assertTextContains("EXTRACT 7", substring = true).assertTextContains("OTHER 2", substring = true)
        compose.onNodeWithTag("kf_latency").assertTextContains("p50 4 s", substring = true).assertTextContains("p95 12 s", substring = true)
        compose.onNodeWithTag("kf_dropped").assertTextContains("SHOWN_GAP 2", substring = true)
        compose.onNodeWithTag("kf_sidecar").assertTextContains("4", substring = true)
    }

    @Test fun retryAllCallsDao() = runBlocking {
        var retried = 0
        val kf = object : KeyframeDiagnostics by KeyframeDiagnostics.None {
            override val stats: StateFlow<KeyframeStats> = MutableStateFlow(this@DiagnosticsTest.stats)
            override suspend fun retryAllFinal(): Int { retried++; return 2 }
        }
        val vm = DiagnosticsViewModel(object : DiagnosticsActions {
            override suspend fun summarizeNow() = ""; override suspend fun captureNow() = ""; override suspend fun reprocessOldPhotos() = ""
        }, FakePipelineControl(), keyframes = kf)
        vm.retryAll()
        assertEquals("2 photos queued to retry", withTimeout(5_000) { vm.state.first { it.message != null } }.message)
        assertEquals(1, retried)
        vm.cancelScope()
    }

    @Test fun retryAllRowIsEnabledOnlyWithFinalFailures() {
        var clicks = 0
        show(KeyframeRows(stats), onRetry = { clicks++ })
        compose.onNodeWithText("Retry all").assertIsEnabled().performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(1, clicks)
    }

    @Test fun retryAllRowIsDisabledWithNone() {
        show(KeyframeRows(KeyframeStats(counts = mapOf("EXTRACTED" to 5))))
        compose.onNodeWithText("Retry all").assertIsNotEnabled()
    }

    @Test fun outdatedPromptWarningShown() {
        show(KeyframeRows(stats, promptCurrent = false))
        compose.onNodeWithText(KeyframeLines.PROMPT_OUT_OF_DATE).assertExists()
    }

    @Test fun currentPromptShowsNoWarning() {
        show(KeyframeRows(stats, promptCurrent = true))
        compose.onNodeWithText(KeyframeLines.PROMPT_OUT_OF_DATE).assertDoesNotExist()
    }
}
