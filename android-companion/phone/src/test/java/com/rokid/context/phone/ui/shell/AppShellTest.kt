package com.rokid.context.phone.ui.shell

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.hasTestTag
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.ui.theme.PcTheme
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.orchestration.AskOutcome
import com.rokid.context.phone.ui.FakePipelineControl
import com.rokid.context.phone.ui.Fixtures
import com.rokid.context.phone.ui.activity.ActivityModel
import com.rokid.context.phone.ui.activity.Period
import com.rokid.context.phone.ui.glasses.FakeGlassesStatusSource
import com.rokid.context.phone.ui.home.AskService
import com.rokid.context.phone.ui.onboarding.OnboardingStore
import com.rokid.context.phone.ui.settings.DiagnosticsActions
import com.rokid.context.phone.ui.settings.QueueActions
import com.rokid.context.ui.queues.QueueSnapshot
import kotlinx.coroutines.awaitCancellation
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
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class AppShellTest {
    @get:Rule val compose = createComposeRule()
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var deps: PhoneDeps
    private lateinit var nav: NavHostController

    private fun deps(askService: AskService = AskService { AskOutcome.Failed("offline in tests") }) = PhoneDeps(
        memory = MemoryGraph.get(ctx), glasses = FakeGlassesStatusSource(), pipeline = FakePipelineControl(), onboarding = OnboardingStore(ctx),
        askService = askService, rateAsk = { _, _ -> true },
        diagnostics = object : DiagnosticsActions {
            override suspend fun summarizeNow() = "ok"; override suspend fun captureNow() = "ok"; override suspend fun reprocessOldPhotos() = "ok"
        },
        queues = object : QueueActions {
            override suspend fun snapshot() = QueueSnapshot(0, 0, 0, 0, 0, 24, 0, 0, 0, "none")
            override suspend fun queueExplicitWrite(text: String) = "ok"; override suspend fun queueReadContext(query: String) = "ok"; override suspend fun drainNow() = "ok"
        },
        settings = com.rokid.context.phone.ui.settings.TestSettings.deps(ctx),
        storagePct = flowOf(10), clock = flowOf(Fixtures.NOW), zone = { Fixtures.ZONE },
    )

    @Before fun setUp() {
        MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx))
        deps = deps()
    }

    // ViewModels are cleared when the compose rule tears the activity down, which happens after @After;
    // so the in-memory DB is left open (GC'd) rather than closed under live collectors.
    @After fun tearDown() = MemoryGraph.resetForTests(null)

    private fun launch(start: String, bluetoothMissing: Boolean = false) = compose.setContent {
        nav = rememberNavController(); PcTheme { AppShell(deps, start, nav, MutableStateFlow(bluetoothMissing)) }
    }

    /** Room emits on its own threads, which Compose idling does not track — wait for data-driven text explicitly. */
    private fun waitForText(text: String, substring: Boolean = false) =
        compose.waitUntil(5_000) { compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty() }

    /** Walks the Phase 7 wizard to its end; every step after Welcome is optional. */
    private fun walkWizardToDone() {
        compose.onNodeWithText("Get started").performClick()
        compose.onNodeWithText("Continue").performClick() // Permissions
        compose.onNodeWithText("Set up later").performClick() // Glasses
        compose.onNodeWithText("Set up later").performClick() // AI
        compose.onNodeWithText("Continue").performClick() // Persona (stores off Main)
        waitForText("Glasses gestures")
        compose.onNodeWithText("Continue").performClick() // Gestures
        waitForText("You're set")
    }

    @Test fun firstRunShowsWelcomeWithoutPillNavThenHome() {
        launch(Routes.startRoute(deps.onboarding.isComplete()))
        compose.onNodeWithText("Proactive Context").assertIsDisplayed()
        compose.onNodeWithText("Home").assertDoesNotExist()
        walkWizardToDone()
        compose.onNodeWithText("Open Home").performClick()
        waitForText("Ask about your day")
        compose.onNodeWithText("Ask about your day").assertIsDisplayed()
        compose.onNodeWithText("Home").assertIsDisplayed()
        assertTrue(deps.onboarding.isComplete())
    }

    /** Integration carry 4: the shell provides LocalOpenActionsSettings, so a Home nudge card's "Enable in Settings" opens Settings > Actions. */
    @Test fun homeNudgeEnableInSettingsOpensActionsSettings() {
        kotlinx.coroutines.runBlocking {
            val mem = MemoryGraph.get(ctx)
            mem.nudges.addIgnore(com.rokid.context.memory.db.NudgeEntity("nudge_1", Fixtures.NOW - 60_000, "Milk is nearly out", "why", "[]", 1, "h", "glance", false))
            mem.proposals.addIgnore(
                com.rokid.context.memory.db.ProposalEntity(
                    "prop_NUDGE_nudge_1", "NUDGE", "nudge_1", "SHOPPING_ADD", "{\"items\":[{\"name\":\"milk\",\"qty\":null,\"unit\":null}]}",
                    "SUPPRESSED", Fixtures.NOW, null, null, Fixtures.NOW,
                ),
            )
        }
        launch(Routes.HOME)
        // The feed loads off Main and sits below the fold: scroll until the card has composed.
        compose.waitUntil(5_000) {
            runCatching { compose.onNodeWithTag("home_list").performScrollToNode(hasText("Enable in Settings")); true }.getOrDefault(false)
        }
        compose.onNodeWithText("Enable in Settings").performClick()
        compose.runOnIdle { assertEquals(Routes.SETTINGS_ACTIONS, nav.currentDestination?.route) }
    }

    @Test fun pillNavSwitchesBetweenTheThreeTabs() {
        launch(Routes.HOME)
        compose.onNodeWithText("Activity").performClick()
        compose.onNodeWithText("What your glasses noticed").assertIsDisplayed()
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("DEVICES").assertIsDisplayed()
        compose.onNodeWithText("Home").performClick()
        compose.onNodeWithText("Ask about your day").assertIsDisplayed()
    }

    @Test fun frameViewerHidesThePillNavAndBackRestoresIt() {
        launch(Routes.HOME)
        compose.runOnIdle { nav.navigate(Routes.frames(Fixtures.TODAY)) }
        waitForText("No photos for this day")
        compose.onNodeWithText("Home").assertDoesNotExist()
        compose.runOnIdle { nav.popBackStack() }
        compose.onNodeWithText("Home").assertIsDisplayed()
    }

    @Test fun staleAskLinkShowsNotFound() {
        launch(Routes.HOME)
        compose.runOnIdle { nav.navigate(Routes.ask("ask_missing")) }
        waitForText("This answer is no longer available")
    }

    @Test fun homeShowsTheDisconnectedBannerAndDiagnosticsReachesTheTracker() {
        launch(Routes.HOME)
        waitForText("Glasses disconnected") // OFFLINE fake → disconnected banner (Home state waits for its Room flows)
        compose.runOnIdle { nav.navigate(Routes.SETTINGS_DIAGNOSTICS) }
        compose.onNodeWithText("Summarizer tracker").performClick()
        compose.onNodeWithText("No summary jobs yet").assertIsDisplayed()
    }

    /** Every Phase 4 route resolves to its screen (carried wiring requirement). */
    @Test fun everyPhaseFourRouteOpensItsScreen() {
        launch(Routes.HOME)
        compose.runOnIdle { nav.navigate(Routes.activity(Period.WEEK)) }
        waitForText("Week of", substring = true)
        val today = deps.today()
        compose.runOnIdle { nav.navigate(Routes.day(today)) }
        waitForText(ActivityModel.dayLabel(today, today))
        compose.runOnIdle { nav.navigate(Routes.memory("mem_missing")) }
        waitForText("This memory is no longer available")
        compose.runOnIdle { nav.navigate(Routes.SETTINGS_ENDPOINTS) }
        waitForText("Model endpoints")
        compose.runOnIdle { nav.navigate(Routes.DIAGNOSTICS_QUEUES) }
        waitForText("Queues")
    }

    /** Final-review I1: the editor route wires the EMBED reindex trigger, so re-pointing the EMBED endpoint starts a new space. */
    @Test fun savingTheEmbedEndpointsNewAddressStartsAReindex() {
        val ai = deps.settings.ai
        kotlinx.coroutines.runBlocking {
            ai.endpoints.save(com.rokid.context.ai.Endpoint("ol", "Ollama", com.rokid.context.ai.EndpointKind.OLLAMA, "http://localhost:11434", null,
                listOf("nomic-embed-text"), setOf(com.rokid.context.ai.Capability.EMBEDDINGS), 2), null).getOrThrow()
            ai.bindings.set(com.rokid.context.ai.RoleBinding(com.rokid.context.ai.Role.EMBED, "ol", "nomic-embed-text")).getOrThrow()
        }
        val before = com.rokid.context.phone.ui.settings.TestSettings.embedChanges
        launch(Routes.HOME)
        compose.runOnIdle { nav.navigate(Routes.endpoint("ol")) }
        waitForText("http://localhost:11434")
        compose.onNodeWithTag("endpoint_url").performTextReplacement("http://localhost:11435")
        compose.onNodeWithTag("endpoint_editor").performScrollToNode(hasTestTag("endpoint_save"))
        compose.onNodeWithTag("endpoint_save").performClick()
        waitForText("Saved") // the save ran (its main-thread continuation is idled by the node wait)
        compose.runOnIdle { assertEquals(before + 1, com.rokid.context.phone.ui.settings.TestSettings.embedChanges) }
    }

    /** HomeViewModel is scoped to the Home back-stack entry: popping Home cancels an in-flight ask. */
    @Test fun leavingHomeCancelsAnInFlightAsk() {
        val started = AtomicBoolean(false)
        val cancelled = AtomicBoolean(false)
        deps = deps(AskService { started.set(true); try { awaitCancellation() } finally { cancelled.set(true) } })
        launch(Routes.HOME)
        compose.onNode(hasSetTextAction()).performTextInput("What did I see?")
        compose.onNodeWithContentDescription("Send question").performClick()
        compose.waitUntil(5_000) { started.get() }
        compose.runOnIdle { nav.navigate(Routes.SETTINGS) { popUpTo(Routes.HOME) { inclusive = true } } }
        compose.waitUntil(5_000) { cancelled.get() }
    }

    @Test fun missingBluetoothPermissionShowsAHintAndTheAppStillWorks() {
        launch(Routes.HOME, bluetoothMissing = true)
        compose.onNodeWithText(BluetoothPermission.HINT).assertIsDisplayed()
        compose.onNodeWithText("Activity").performClick()
        compose.onNodeWithText("What your glasses noticed").assertIsDisplayed()
    }

    /** Two quick Back taps on a detail screen (the exiting screen is still composed) must never pop the root entry. */
    @Test fun doubleBackOnADetailScreenKeepsHome() {
        launch(Routes.HOME)
        compose.runOnIdle { nav.navigate(Routes.ask("ask_missing")) }
        waitForText("This answer is no longer available")
        compose.mainClock.autoAdvance = false
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.mainClock.autoAdvance = true
        compose.onNodeWithText("Ask about your day").assertIsDisplayed()
        compose.runOnIdle { assertEquals(Routes.HOME, nav.currentDestination?.route) }
    }

    /** A double-tapped "Get started" must not stack two Home entries. */
    @Test fun doubleTappedGetStartedLeavesOneHome() {
        launch(Routes.ONBOARDING)
        compose.mainClock.autoAdvance = true
        walkWizardToDone()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("Open Home").performClick()
        compose.onNodeWithText("Open Home").performClick()
        compose.mainClock.autoAdvance = true
        waitForText("Ask about your day")
        compose.onNodeWithText("Ask about your day").assertIsDisplayed()
        compose.runOnIdle { assertEquals(listOf(Routes.HOME), nav.currentBackStack.value.mapNotNull { it.destination.route }.filter { it != nav.graph.route }) }
    }

    /** While the hint shows, the content inset grows with it: the end of a long list scrolls clear of the banner. */
    @Test fun bluetoothHintNeverCoversTheEndOfAList() {
        launch(Routes.SETTINGS, bluetoothMissing = true)
        compose.onNodeWithTag("settings_list").performScrollToNode(hasText("Queues, summarizer tracker, manual runs"))
        val last = compose.onNodeWithText("Queues, summarizer tracker, manual runs").getBoundsInRoot()
        val hint = compose.onNodeWithTag(BluetoothPermission.TAG).getBoundsInRoot()
        assertTrue("last row bottom ${last.bottom} must be above the hint top ${hint.top}", last.bottom <= hint.top)
    }
}
