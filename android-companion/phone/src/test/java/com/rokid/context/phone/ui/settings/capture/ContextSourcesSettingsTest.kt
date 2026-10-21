package com.rokid.context.phone.ui.settings.capture

import android.app.Application
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.model.context.ContextDecision
import com.rokid.context.core.model.context.ContextState
import com.rokid.context.core.ui.theme.PcTheme
import com.rokid.context.phone.context.ContextHubStats
import com.rokid.context.phone.context.ContextSourceSettings
import com.rokid.context.phone.context.SourceStatus
import com.rokid.context.phone.ui.MainDispatcherRule
import com.rokid.context.phone.ui.cancelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h2400dp")
class ContextSourcesSettingsTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val compose = createComposeRule()

    private companion object { val n = AtomicInteger() }
    private val ctx = ApplicationProvider.getApplicationContext<Application>()
    private val settings = ContextSourceSettings(ctx.getSharedPreferences("css_${n.incrementAndGet()}", 0).also { it.edit().clear().commit() })
    private val decision = MutableStateFlow(ContextDecision(ContextState.UNKNOWN, 0f, 0L, emptySet(), null, ContextState.UNKNOWN, false))
    private val status = MutableStateFlow(mapOf("watch_livefit" to SourceStatus.OFF))
    private val stats = ContextHubStats { 0L }
    private val digest = "ab".repeat(32)

    private fun vm(cert: String? = digest) = ContextSourcesViewModel(settings, LiveFitCertLookup { cert }, decision, status, stats)

    private fun show(vm: ContextSourcesViewModel) {
        compose.setContent {
            PcTheme {
                val s = vm.state.collectAsState().value
                ContextSourcesScreen(s, onBack = {}, onApply = vm::setApply, onWatch = vm::setWatch)
            }
        }
        compose.waitForIdle()
    }

    @Test fun bothSwitchesRenderWatchOffApplyOn() {
        val v = vm(); show(v)
        compose.onNodeWithTag("ctx_apply").assertIsOn()
        compose.onNodeWithTag("ctx_watch_livefit").assertIsOff()
        compose.onNodeWithTag("ctx_watch_missing").assertDoesNotExist()
        v.cancelScope()
    }

    @Test fun liveFitMissingShowsNoteAndStaysOff() {
        val v = vm(cert = null); show(v)
        compose.onNodeWithTag("ctx_watch_livefit").performClick(); compose.waitForIdle()
        compose.onNodeWithTag("ctx_watch_missing").assertTextContains("Install LiveFit on this phone first.")
        compose.onNodeWithTag("ctx_watch_livefit").assertIsOff()
        assertFalse(settings.enabled("watch_livefit")); assertNull(settings.liveFitCertSha256)
        v.cancelScope()
    }

    @Test fun liveFitPresentPinsDigestAndTurnsOn() {
        val v = vm(); show(v)
        compose.onNodeWithTag("ctx_watch_livefit").performClick(); compose.waitForIdle()
        compose.onNodeWithTag("ctx_watch_livefit").assertIsOn()
        assertTrue(settings.enabled("watch_livefit")); assertEquals(digest, settings.liveFitCertSha256)
        compose.onNodeWithTag("ctx_watch_missing").assertDoesNotExist()
        v.cancelScope()
    }

    @Test fun turningWatchOffClearsDigest() {
        val v = vm(); show(v)
        compose.onNodeWithTag("ctx_watch_livefit").performClick(); compose.waitForIdle()
        compose.onNodeWithTag("ctx_watch_livefit").performClick(); compose.waitForIdle()
        compose.onNodeWithTag("ctx_watch_livefit").assertIsOff()
        assertFalse(settings.enabled("watch_livefit")); assertNull(settings.liveFitCertSha256)
        v.cancelScope()
    }

    @Test fun applySwitchWritesTheSetting() {
        val v = vm(); show(v)
        compose.onNodeWithTag("ctx_apply").performClick(); compose.waitForIdle()
        assertFalse(settings.applyToCapture); compose.onNodeWithTag("ctx_apply").assertIsOff()
        compose.onNodeWithTag("ctx_apply").performClick(); compose.waitForIdle()
        assertTrue(settings.applyToCapture)
        v.cancelScope()
    }

    @Test fun stateLineShowsDecisionWatchStatusAndCounts() {
        decision.value = decision.value.copy(state = ContextState.VEHICLE_DRIVING)
        status.value = mapOf("watch_livefit" to SourceStatus.FAILED)
        stats.onWatchRejected(); stats.onWatchRejected()
        val v = vm(); show(v)
        compose.onNodeWithTag("ctx_state").assertTextContains("Driving", substring = true)
            .assertTextContains("FAILED", substring = true).assertTextContains("watchRej 2", substring = true).assertTextContains("watch 0", substring = true)
        v.cancelScope()
    }

    @Test fun stateWordsCoverEveryState() {
        assertEquals(listOf("Unknown", "Resting", "Walking", "Passenger", "Driving", "Flying"), ContextState.entries.map { ContextSourcesUiState.word(it) })
    }

    @Test fun viewModelStateFollowsSettingsChangesMadeElsewhere() = runBlocking {
        val v = vm()
        assertTrue(withTimeout(5_000) { v.state.first() }.applyToCapture)
        settings.applyToCapture = false
        assertFalse(withTimeout(5_000) { v.state.first { !it.applyToCapture } }.applyToCapture)
        v.cancelScope()
    }
}
