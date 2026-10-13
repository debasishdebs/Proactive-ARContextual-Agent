package com.rokid.context.phone.ui.shell

import android.app.Application
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.ui.theme.PcTheme
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.orchestration.AskOutcome
import com.rokid.context.phone.ui.FakePipelineControl
import com.rokid.context.phone.ui.Fixtures
import com.rokid.context.phone.ui.glasses.FakeGlassesStatusSource
import com.rokid.context.phone.ui.home.AskService
import com.rokid.context.phone.ui.home.FakePhoneVoice
import com.rokid.context.phone.ui.onboarding.OnboardingStore
import com.rokid.context.phone.ui.settings.DiagnosticsActions
import com.rokid.context.phone.ui.settings.QueueActions
import com.rokid.context.voice.PhoneVoiceState
import com.rokid.context.ui.queues.QueueSnapshot
import com.rokid.context.voice.VoiceStatus
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Integration carry 7 (Phase 6 rule F2): Home's RECORD_AUDIO result re-checks the voice gate (which probes) only while
 * the activity is RESUMED. A result that lands while the app is only STARTED leaves it to the resume hook.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class HomeMicResultTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private var rechecks = 0
    private var launchedRc: Int? = null
    private val registry = object : ActivityResultRegistry() {
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) { launchedRc = requestCode }
    }
    private val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }

    @Before fun setUp() {
        MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx))
        val deps = PhoneDeps(
            memory = MemoryGraph.get(ctx), glasses = FakeGlassesStatusSource(), pipeline = FakePipelineControl(), onboarding = OnboardingStore(ctx),
            askService = AskService { AskOutcome.Failed("offline in tests") }, rateAsk = { _, _ -> true },
            diagnostics = object : DiagnosticsActions {
                override suspend fun summarizeNow() = "ok"; override suspend fun captureNow() = "ok"; override suspend fun reprocessOldPhotos() = "ok"
            },
            queues = object : QueueActions {
                override suspend fun snapshot() = QueueSnapshot(0, 0, 0, 0, 0, 24, 0, 0, 0, "none")
                override suspend fun queueExplicitWrite(text: String) = "ok"; override suspend fun queueReadContext(query: String) = "ok"; override suspend fun drainNow() = "ok"
            },
            settings = com.rokid.context.phone.ui.settings.TestSettings.deps(ctx),
            storagePct = flowOf(10), clock = flowOf(Fixtures.NOW), zone = { Fixtures.ZONE },
            voice = FakePhoneVoice(PhoneVoiceState(status = VoiceStatus.NEEDS_MIC_PERMISSION)),
            onMicGranted = { rechecks++ },
        )
        compose.setContent { CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) { PcTheme { AppShell(deps, Routes.HOME) } } }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Ask about your day").fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Allow the microphone to ask by voice").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Ask by voice").performClick()
        compose.runOnIdle { check(launchedRc != null) { "the mic permission was not requested" } }
    }

    @After fun tearDown() = MemoryGraph.resetForTests(null)

    @Test fun aResultWhileOnlyStartedDoesNotProbe() {
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        compose.activityRule.scenario.onActivity { registry.dispatchResult(launchedRc!!, true) }
        assertEquals(0, rechecks)
    }

    @Test fun aResultWhileResumedReChecks() {
        compose.activityRule.scenario.onActivity { registry.dispatchResult(launchedRc!!, true) }
        assertEquals(1, rechecks)
    }
}
