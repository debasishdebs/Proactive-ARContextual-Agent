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
        keyframesPerHour = 55, backlog = 3, latencyP50Ms = 4_000, latencyP95Ms = 12_000, shed = 28,
    )

    private fun show(rows: KeyframeRows, onRetry: () -> Unit = {}) {
        compose.setContent { PcTheme { DiagnosticsScreen(DiagnosticsUiState(), {}, {}, {}, {}, {}, {}, {}, {}, {}, keyframes = rows, onRetryAll = onRetry) } }
    }

    @Test fun showsBacklogAndLatency() {
        show(KeyframeRows(stats, droppedNudges = mapOf("SHOWN_GAP" to 2), sidecarLost = 4))
        compose.onNodeWithTag("kf_backlog").assertTextContains("Backlog 3", substring = true).assertTextContains("PENDING 3", substring = true).assertTextContains("FAILED_FINAL 2", substring = true)
        compose.onNodeWithTag("kf_rate").assertTextContains("55", substring = true)
        compose.onNodeWithTag("kf_shed").assertTextContains("Skipped to keep up: 28")
        compose.onNodeWithTag("kf_calls").assertTextContains("USER 1", substring = true).assertTextContains("EXTRACT 7", substring = true).assertTextContains("OTHER 2", substring = true)
        compose.onNodeWithTag("kf_latency").assertTextContains("p50 4 s", substring = true).assertTextContains("p95 12 s", substring = true)
        compose.onNodeWithTag("kf_dropped").assertTextContains("SHOWN_GAP 2", substring = true)
        compose.onNodeWithTag("kf_sidecar").assertTextContains("4", substring = true)
    }

    @Test fun showsRetentionCounters() {
        show(KeyframeRows(stats, retention = RetentionCounters(skippedDeleted = 12, describedBeforeDelete = 5, deletedUndescribed = 0, describeWaiting = 7, describeFailed = 2)))
        compose.onNodeWithTag("kf_retention").assertTextContains("skipped 12", substring = true).assertTextContains("described first 5", substring = true).assertTextContains("undescribed 0", substring = true).assertTextContains("waiting 7", substring = true).assertTextContains("describeFailed 2", substring = true)
    }

    @Test fun showsRokidClosedLineOnlyWhenReported() {
        compose.setContent { PcTheme { DiagnosticsScreen(DiagnosticsUiState(), {}, {}, {}, {}, {}, {}, {}, {}, {}, glassesRestarts = "Rokid closed the app 2 times (last gap 38s)") } }
        compose.onNodeWithTag("glasses_restarts").assertTextContains("Rokid closed the app 2 times (last gap 38s)")
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

    @Test fun outdatedClusterPromptWarningShown() {
        show(KeyframeRows(stats, clusterPromptCurrent = false))
        compose.onNodeWithText(KeyframeLines.CLUSTER_PROMPT_OUT_OF_DATE).assertExists()
        compose.onNodeWithText(KeyframeLines.PROMPT_OUT_OF_DATE).assertDoesNotExist()
    }

    @Test fun currentClusterPromptShowsNoWarning() {
        show(KeyframeRows(stats))
        compose.onNodeWithText(KeyframeLines.CLUSTER_PROMPT_OUT_OF_DATE).assertDoesNotExist()
    }

    @Test fun showsClusterAndQueueCounts() {
        val st = stats.copy(clusters = mapOf("CLOSED" to 2, "SUMMARIZED" to 5), scenePhotos24h = 120, sceneCalls24h = 9, captionQueue = 4, unrouted = 3)
        show(KeyframeRows(st))
        compose.onNodeWithTag("kf_clusters").assertTextContains("CLOSED 2", substring = true).assertTextContains("SUMMARIZED 5", substring = true)
        compose.onNodeWithTag("kf_scene_photos").assertTextContains("Photos covered by scene summaries (last 24 h): 120")
        compose.onNodeWithTag("kf_scene_calls").assertTextContains("Scene calls (last 24 h): 9")
        compose.onNodeWithTag("kf_caption_queue").assertTextContains("Caption queue: 4")
        compose.onNodeWithTag("kf_unrouted").assertTextContains("Unrouted: 3")
    }
}
