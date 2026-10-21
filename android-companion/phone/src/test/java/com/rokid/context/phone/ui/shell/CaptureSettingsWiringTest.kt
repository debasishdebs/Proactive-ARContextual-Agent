package com.rokid.context.phone.ui.shell

import android.app.Application
import android.content.Context
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.ai.Capability
import com.rokid.context.core.model.MotionLabel
import com.rokid.context.core.ui.theme.PcTheme
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.orchestration.AskOutcome
import com.rokid.context.phone.ui.FakePipelineControl
import com.rokid.context.phone.ui.Fixtures
import com.rokid.context.phone.ui.glasses.FakeGlassesStatusSource
import com.rokid.context.phone.ui.home.AskService
import com.rokid.context.phone.ui.onboarding.OnboardingStore
import com.rokid.context.phone.ui.settings.DiagnosticsActions
import com.rokid.context.phone.ui.settings.FakeHudSettings
import com.rokid.context.phone.ui.settings.QueueActions
import com.rokid.context.phone.ui.settings.TestSettings
import com.rokid.context.ui.queues.QueueSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Wave A3 final review I1: the production SETTINGS_CAPTURE route (not test lambdas) must reach the stored settings. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h2400dp")
class CaptureSettingsWiringTest {
    @get:Rule val compose = createComposeRule()
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val hud = FakeHudSettings()
    private lateinit var nav: NavHostController

    private fun deps() = PhoneDeps(
        memory = MemoryGraph.get(ctx), glasses = FakeGlassesStatusSource(), pipeline = FakePipelineControl(), onboarding = OnboardingStore(ctx),
        askService = AskService { AskOutcome.Failed("offline in tests") }, rateAsk = { _, _ -> true },
        diagnostics = object : DiagnosticsActions {
            override suspend fun summarizeNow() = "ok"; override suspend fun captureNow() = "ok"; override suspend fun reprocessOldPhotos() = "ok"
        },
        queues = object : QueueActions {
            override suspend fun snapshot() = QueueSnapshot(0, 0, 0, 0, 0, 24, 0, 0, 0, "none")
            override suspend fun queueExplicitWrite(text: String) = "ok"; override suspend fun queueReadContext(query: String) = "ok"; override suspend fun drainNow() = "ok"
        },
        settings = TestSettings.deps(ctx, hud = hud),
        storagePct = flowOf(10), clock = flowOf(Fixtures.NOW), zone = { Fixtures.ZONE },
    )

    @Before fun setUp() { MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx)) }
    @After fun tearDown() = MemoryGraph.resetForTests(null)

    private fun open() {
        compose.setContent { nav = rememberNavController(); PcTheme { AppShell(deps(), Routes.HOME, nav, MutableStateFlow(false)) } }
        compose.waitForIdle()
        compose.runOnIdle { nav.navigate(Routes.SETTINGS_CAPTURE) }
        compose.waitUntil(5_000) { nav.currentDestination?.route == Routes.SETTINGS_CAPTURE }
        compose.waitForIdle()
    }

    @Test fun workRestSwitchOnTheProductionRouteReachesTheStoredSettings() {
        open()
        assertTrue(!hud.settings.value.workRestEnabled)
        compose.onNodeWithTag("work_rest").performClick(); compose.waitForIdle()
        assertTrue(hud.settings.value.workRestEnabled)
    }

    @Test fun motionLabelOnTheProductionRouteReachesTheStoredSettings() {
        open()
        compose.onNodeWithTag("motion_label").performSemanticsAction(SemanticsActions.OnClick); compose.waitForIdle()
        compose.onNodeWithTag("motion_label_4").performClick(); compose.waitForIdle()
        assertEquals(MotionLabel.EATING, hud.settings.value.motionLabel)
    }
}
