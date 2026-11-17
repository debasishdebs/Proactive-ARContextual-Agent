package com.rokid.context.phone.ui.settings.flows

import android.app.Application
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.ai.AiGraph
import com.rokid.context.ai.Capability
import com.rokid.context.ai.Endpoint
import com.rokid.context.ai.EndpointKind
import com.rokid.context.ai.Role
import com.rokid.context.ai.RoleBinding
import com.rokid.context.ai.flow.AgentFlow
import com.rokid.context.ai.flow.AgentFlows
import com.rokid.context.ai.flow.Facet
import com.rokid.context.ai.flow.FlowEdge
import com.rokid.context.ai.flow.FlowNode
import com.rokid.context.ai.flow.NodeKind
import com.rokid.context.core.ui.theme.PcTheme
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.retrieval.RetrievalSettingsStore
import com.rokid.context.orchestration.AskOutcome
import com.rokid.context.phone.ui.FakePipelineControl
import com.rokid.context.phone.ui.Fixtures
import com.rokid.context.phone.ui.MainDispatcherRule
import com.rokid.context.phone.ui.cancelScope
import com.rokid.context.phone.ui.glasses.FakeGlassesStatusSource
import com.rokid.context.phone.ui.home.AskService
import com.rokid.context.phone.ui.onboarding.OnboardingStore
import com.rokid.context.phone.ui.settings.DiagnosticsActions
import com.rokid.context.phone.ui.settings.QueueActions
import com.rokid.context.phone.ui.settings.TestSettings
import com.rokid.context.phone.ui.settings.ai.RolesViewModel
import com.rokid.context.phone.ui.settings.retrieval.RetrievalViewModel
import com.rokid.context.phone.ui.shell.AppShell
import com.rokid.context.phone.ui.shell.PhoneDeps
import com.rokid.context.phone.ui.shell.Routes
import com.rokid.context.ui.queues.QueueSnapshot
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/** A test-only flow whose step has a Custom facet no editor is registered for (never the registry). */
private val CUSTOM_FLOW = AgentFlow(
    "custom", "Custom", "A future ML model.",
    listOf(FlowNode("in", "In", NodeKind.INPUT), FlowNode("ml", "Food classifier", NodeKind.STEP, listOf(Facet.Custom("nope")))),
    listOf(FlowEdge("in", "ml")),
)

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class StepSheetTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var ai: AiGraph
    private lateinit var store: RetrievalSettingsStore
    private val vms = mutableListOf<ViewModel>()

    @Before fun setUp() = runBlocking {
        MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx))
        ai = TestSettings.ai(ctx)
        ctx.getSharedPreferences("step_sheet_test", 0).edit().clear().commit()
        store = RetrievalSettingsStore(ctx.getSharedPreferences("step_sheet_test", 0))
        ai.endpoints.save(Endpoint("az", "Azure", EndpointKind.AZURE_OPENAI, "https://az.example", null, listOf("gpt-4o", "mini"), setOf(Capability.CHAT), 1), "k").getOrThrow()
        ai.endpoints.save(Endpoint("ol", "Ollama", EndpointKind.OLLAMA, "http://localhost:11434", null, listOf("nomic-embed-text", "mxbai-embed"), setOf(Capability.EMBEDDINGS), 2), null).getOrThrow()
        ai.bindings.set(RoleBinding(Role.ANSWER, "az", "gpt-4o")).getOrThrow()
        ai.bindings.set(RoleBinding(Role.EMBED, "ol", "nomic-embed-text")).getOrThrow()
        Unit
    }

    // As AppShellTest: AppShell's ViewModels are cleared after @After, so the in-memory DB is left open (GC'd), not closed under collectors.
    @After fun tearDown() { try { vms.forEach { it.cancelScope() } } finally { MemoryGraph.resetForTests(null) } }

    private fun host(vararg ids: String, registry: (String) -> AgentFlow? = AgentFlows::byId, navigate: (String) -> Unit = {}): FlowViewModel {
        val vm = FlowViewModel(ids.toList(), ai, store, registry).also { vms += it }
        compose.setContent {
            PcTheme {
                FlowViewRoute(
                    vm,
                    roles = { remember { RolesViewModel(ai) {}.also { vms += it } } },
                    retrieval = { remember { RetrievalViewModel(store, ai.retriever, MemoryGraph.get(ctx).spaces, ai, {}).also { vms += it } } },
                    onBack = {}, navigate = navigate,
                )
            }
        }
        compose.waitUntil(5_000) { vm.state.value.loaded }
        return vm
    }

    private fun exists(tag: String) = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun node(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true)

    private fun open(flow: String, step: String) {
        val tag = "flow_node_${flow}_$step"
        compose.onNodeWithTag("flow_view").performScrollToNode(hasTestTag(tag))
        val top = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot().top
        val px = with(compose.density) { (top - 120.dp).toPx() }
        compose.onNodeWithTag("flow_view").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, px) }
        compose.onNodeWithTag(tag).performScrollTo().performClick()
        compose.waitUntil(5_000) { exists("step_sheet") }
    }

    /**
     * A click inside the step sheet. Robolectric does not deliver injected touches to the ModalBottomSheet's window
     * (the same click works through its semantics action), so sheet controls are clicked through OnClick.
     */
    private fun tap(tag: String) { node(tag).performSemanticsAction(SemanticsActions.OnClick); compose.waitForIdle() }

    /** Same assertion, a ceiling that survives a full-suite run on 2 workers (the test passes alone in seconds). */
    private fun waitText(tag: String, text: String) = compose.waitUntil(20_000) { runCatching { node(tag).assertTextEquals(text) }.isSuccess }

    @Test fun answerOpensPromptAndModelTabs() {
        host("ask")
        open("ask", "answer")
        node("step_tab_Prompt").assertExists()
        node("step_tab_Model").assertExists()
        assertEquals(false, exists("step_tab_Retrieval"))
        waitText("step_prompt_version", "Active v1")
        node("step_prompt_variables").assertExists()
        node("step_open_editor").assertExists()
        tap("step_tab_Model")
        val answerUsers = AgentFlows.nodesUsing(Role.ANSWER).map { it.second.title }
        waitText("step_model_header", "Model for Answer \u00b7 shared by ${answerUsers.size} steps")
        node("step_model_users").assertTextEquals("Used by: ${answerUsers.distinct().joinToString(", ")}")
        compose.waitUntil(5_000) { exists("role_ANSWER_model") }
        node("role_ANSWER_model").assertContentDescriptionEquals("Model: gpt-4o")
    }

    @Test fun searchMemoriesOpensRetrievalAndModelEmbedTabs() {
        host("ask")
        open("ask", "search")
        node("step_tab_Retrieval").assertExists()
        node("step_tab_Model").assertExists()
        assertEquals(false, exists("step_tab_Prompt"))
        node("step_retrieval_header").assertExists() // the first facet's tab is open
        tap("step_tab_Model")
        compose.waitUntil(5_000) { exists("role_EMBED_model") }
        node("role_EMBED_model").assertContentDescriptionEquals("Model: nomic-embed-text")
    }

    @Test fun modelHeaderSaysSharedAndListsUsers() {
        host("ask")
        open("ask", "search")
        tap("step_tab_Model")
        waitText("step_model_header", "Model for Embeddings \u00b7 shared by 2 steps")
        node("step_model_users").assertTextEquals("Used by: Embed, Search memories")
    }

    /** Spec section 6: a shared Model change made from one step shows on every card that uses the role. */
    @Test fun changingEmbedFromSearchMemoriesUpdatesBothCards() {
        host("capture", "ask")
        open("ask", "search")
        tap("step_tab_Model")
        compose.waitUntil(5_000) { exists("role_EMBED_model") }
        tap("role_EMBED_model")
        tap("role_EMBED_model_1")
        runBlocking { withTimeout(20_000) { while (ai.bindings.get(Role.EMBED)?.model != "mxbai-embed") delay(10) } }
        waitText("flow_line_ask_search_Model", "mxbai-embed")
        // The Capture section is scrolled off (and not composed); bring its Embed card back under the sheet.
        compose.onNodeWithTag("flow_view").performScrollToNode(hasTestTag("flow_node_capture_embed"))
        waitText("flow_line_capture_embed_Model", "mxbai-embed")
        node("step_model_users").assertTextEquals("Used by: Embed, Search memories")
    }

    @Test fun criticPromptTabSaysSharedAndListsBothSteps() {
        host("capture")
        open("capture", "critic")
        node("step_prompt_header").assertTextEquals("Prompt Critic \u00b7 shared by 2 steps")
        node("step_prompt_users").assertTextEquals("Used by: Critic, Critic review")
    }

    @Test fun answerPromptTabHasNoSharedLabel() {
        host("ask")
        open("ask", "answer")
        node("step_prompt_header").assertTextEquals("Answer")
        assertEquals(false, exists("step_prompt_users"))
    }

    @Test fun retrievalHeaderSaysShared() {
        host("ask")
        open("ask", "search")
        node("step_retrieval_header").assertTextEquals("Retrieval settings \u00b7 shared by every search step")
        node("fusion").assertExists() // the Settings > Retrieval controls, reused
    }

    @Test fun unknownCustomEditorShowsFallback() {
        host("custom", registry = { if (it == "custom") CUSTOM_FLOW else null })
        open("custom", "ml")
        node("step_tab_Custom").assertExists()
        node("step_custom_unavailable").assertTextEquals("Not available in this version")
    }

    @Test fun openEditorNavigatesToPromptRoute() {
        val routes = mutableListOf<String>()
        host("ask", navigate = { routes += it })
        open("ask", "answer")
        tap("step_open_editor")
        compose.waitForIdle()
        assertEquals(listOf(Routes.prompt("ANSWER")), routes)
        assertEquals("settings/prompts/ANSWER", routes.single())
        compose.waitUntil(5_000) { !exists("step_sheet") }
    }

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
        settings = TestSettings.deps(ctx, ai),
        storagePct = flowOf(10), clock = flowOf(Fixtures.NOW), zone = { Fixtures.ZONE },
    )

    /** Review Focus 2: Back unwinds one level at a time, inside the Settings stack, never to Home. */
    @Test fun backFromEditorReturnsToFlowViewThenListThenSettings() {
        lateinit var nav: NavHostController
        val deps = deps()
        compose.setContent { nav = rememberNavController(); PcTheme { AppShell(deps, Routes.SETTINGS, nav, MutableStateFlow(false)) } }
        fun route() = compose.runOnIdle { nav.currentDestination?.route }
        fun waitRoute(r: String) = compose.waitUntil(5_000) { route() == r }
        // System Back goes to the topmost window: the sheet's dialog while it is showing, else the activity.
        fun back() = compose.runOnIdle {
            val dialog = ShadowDialog.getShownDialogs().lastOrNull { it.isShowing } as? androidx.activity.ComponentDialog
            (dialog?.onBackPressedDispatcher ?: compose.activity.onBackPressedDispatcher).onBackPressed()
        }

        compose.onNodeWithTag("settings_list").performScrollToNode(hasText("Agent flows"))
        compose.onNodeWithText("Agent flows").performClick()
        waitRoute(Routes.SETTINGS_FLOWS)
        compose.waitUntil(5_000) { exists("list_row_ask") }
        compose.onNodeWithText("Ask").performClick()
        waitRoute(Routes.SETTINGS_FLOWS_VIEW)
        compose.waitUntil(5_000) { exists("flow_node_ask_answer") }
        open("ask", "answer")
        back() // the sheet closes first; the view stays
        compose.waitUntil(5_000) { !exists("step_sheet") }
        assertEquals(Routes.SETTINGS_FLOWS_VIEW, route())
        open("ask", "answer")
        tap("step_open_editor")
        waitRoute(Routes.SETTINGS_PROMPT)

        back()
        waitRoute(Routes.SETTINGS_FLOWS_VIEW)
        compose.waitUntil(5_000) { exists("flow_node_ask_answer") }
        assertEquals(false, exists("step_sheet"))
        back()
        waitRoute(Routes.SETTINGS_FLOWS)
        back()
        waitRoute(Routes.SETTINGS)
        compose.onNodeWithText("Agent flows").assertIsDisplayed()
    }
}
