package com.rokid.context.phone.ui.settings.flows

import android.app.Application
import android.content.Context
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.ai.AiGraph
import com.rokid.context.ai.Capability
import com.rokid.context.ai.Endpoint
import com.rokid.context.ai.EndpointKind
import com.rokid.context.ai.Role
import com.rokid.context.ai.RoleBinding
import com.rokid.context.ai.contract.PromptKey
import com.rokid.context.ai.flow.AgentFlow
import com.rokid.context.ai.flow.AgentFlows
import com.rokid.context.ai.flow.Facet
import com.rokid.context.ai.flow.FlowEdge
import com.rokid.context.ai.flow.FlowNode
import com.rokid.context.ai.flow.NodeKind
import com.rokid.context.ai.flow.flows.CaptureFlow
import com.rokid.context.ai.flow.flows.NudgeFlow
import com.rokid.context.core.ui.theme.PcTheme
import com.rokid.context.memory.retrieval.RetrievalSettingsStore
import com.rokid.context.phone.ui.MainDispatcherRule
import com.rokid.context.phone.ui.TestGraph
import com.rokid.context.phone.ui.cancelScope
import com.rokid.context.phone.ui.settings.TestSettings
import com.rokid.context.prompt.PromptDraft
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** A test-only flow: one input fanning out to six AI steps in one layer (Review Focus 5). */
internal val WIDE_FLOW = AgentFlow(
    "wide", "Wide", "Six steps side by side.",
    listOf(FlowNode("in", "In", NodeKind.INPUT)) +
        (1..6).map { FlowNode("n$it", "A rather long step title number $it that needs two lines", NodeKind.STEP, listOf(Facet.Model(Role.ANSWER))) },
    (1..6).map { FlowEdge("in", "n$it") },
)

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class FlowViewTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val compose = createComposeRule()
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var ai: AiGraph
    private lateinit var store: RetrievalSettingsStore
    private val vms = mutableListOf<FlowViewModel>()

    @Before fun setUp() = runBlocking {
        TestGraph.fresh(ctx)
        ai = TestSettings.ai(ctx)
        ctx.getSharedPreferences("flow_view_test", 0).edit().clear().commit()
        store = RetrievalSettingsStore(ctx.getSharedPreferences("flow_view_test", 0))
        ai.endpoints.save(Endpoint("az", "Azure", EndpointKind.AZURE_OPENAI, "https://az.example", null, listOf("gpt-4o", "mini"), setOf(Capability.CHAT), 1), "k").getOrThrow()
        Unit
    }

    @After fun tearDown() { try { vms.forEach { it.cancelScope() } } finally { TestGraph.close(ctx) } }

    private fun vm(vararg ids: String, registry: (String) -> AgentFlow? = AgentFlows::byId) =
        FlowViewModel(ids.toList(), ai, store, registry).also { vms += it }

    private fun show(vm: FlowViewModel) {
        compose.setContent { PcTheme { val s by vm.state.collectAsState(); FlowViewScreen(s, onBack = {}, onNode = {}, onLink = vm::addFlow) } }
        compose.waitUntil(5_000) { vm.state.value.loaded }
    }

    private fun exists(tag: String) = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    /** Scrolls the page down to [tag] (performScrollTo only scrolls the section's horizontal scroller), then across. */
    private fun bringIntoView(tag: String) {
        compose.onNodeWithTag("flow_view").performScrollToNode(hasTestTag(tag))
        val top = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot().top
        val px = with(compose.density) { (top - 120.dp).toPx() }
        compose.onNodeWithTag("flow_view").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, px) }
        compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
    }

    private fun line(flow: String, node: String, facet: String) = compose.onNodeWithTag("flow_line_${flow}_${node}_$facet", useUnmergedTree = true)

    @Test fun rendersOneSectionPerKnownId() {
        val vm = vm("capture", "ask")
        show(vm)
        assertEquals(listOf("capture", "ask"), vm.state.value.sections.map { it.flow.id })
        compose.onNodeWithTag("flow_section_capture").assertExists()
        compose.onNodeWithTag("flow_view").performScrollToNode(hasTestTag("flow_section_ask"))
        compose.onNodeWithTag("flow_section_ask").assertExists()
        assertTrue(!exists("flow_section_nudge"))
    }

    @Test fun unknownIdsAreSkippedAndEmptyShowsNoFlowsSelected() {
        val mixed = vm("nope", "ask", "")
        val s = runBlocking { withTimeout(5_000) { mixed.state.first { it.loaded } } }
        assertEquals(listOf("ask"), s.sections.map { it.flow.id })
        assertTrue(!s.empty)
        val none = vm("nope")
        show(none)
        assertTrue(none.state.value.empty)
        compose.onNodeWithText("No flows selected").assertExists()
    }

    @Test fun conditionalEdgesShowTheirLabels() {
        val vm = vm("capture", "nudge")
        show(vm)
        val labelled = { f: AgentFlow -> f.edges.filter { it.label != null } }
        assertTrue(labelled(CaptureFlow).size >= 6)
        labelled(CaptureFlow).forEach { e -> compose.onNodeWithTag("flow_edge_label_${e.from}_${e.to}", useUnmergedTree = true).assertTextEquals(e.label!!) }
        compose.onNodeWithTag("flow_view").performScrollToNode(hasTestTag("flow_section_nudge"))
        labelled(NudgeFlow).forEach { e -> compose.onNodeWithTag("flow_edge_label_${e.from}_${e.to}", useUnmergedTree = true).assertTextEquals(e.label!!) }
        compose.onNodeWithTag("flow_edge_label_nudge_propose", useUnmergedTree = true).assertTextEquals("if shouldNudge and proposedAction")
    }

    @Test fun aiStepsAreCardsOthersArePills() {
        show(vm("capture"))
        CaptureFlow.nodes.forEach { n ->
            val card = exists("flow_node_capture_${n.id}")
            val pill = exists("flow_pill_capture_${n.id}")
            assertEquals("${n.id} is a card", n.isAiStep, card)
            assertEquals("${n.id} is a pill", !n.isAiStep, pill)
        }
        compose.onNodeWithTag("flow_node_capture_extract", useUnmergedTree = true).assertExists()
        line("capture", "extract", "Prompt").assertTextEquals("Prompt v1")
        line("capture", "extract", "Model").assertTextEquals("Not set")
    }

    /** Review Focus 3: the card's facet line follows the binding live. */
    @Test fun facetLineUpdatesAfterModelChange() {
        runBlocking { ai.bindings.set(RoleBinding(Role.ANSWER, "az", "gpt-4o", temperature = 0.2)).getOrThrow() }
        show(vm("ask"))
        compose.waitUntil(5_000) { runCatching { line("ask", "answer", "Model").assertTextEquals("gpt-4o \u00b7 t 0.2") }.isSuccess }
        line("ask", "search", "Retrieval").assertTextEquals("Hybrid \u00b7 Weighted")
        runBlocking { ai.bindings.set(RoleBinding(Role.ANSWER, "az", "mini")).getOrThrow() }
        compose.waitUntil(5_000) { runCatching { line("ask", "answer", "Model").assertTextEquals("mini") }.isSuccess }
    }

    @Test fun linkPillAddsNudgeFlow() {
        val vm = vm("capture")
        show(vm)
        assertTrue(!exists("flow_section_nudge"))
        bringIntoView("flow_pill_capture_to_nudge")
        compose.onNodeWithTag("flow_pill_capture_to_nudge").performClick()
        compose.waitUntil(5_000) { vm.state.value.sections.size == 2 }
        assertEquals(listOf("capture", "nudge"), vm.state.value.sections.map { it.flow.id })
        compose.waitUntil(5_000) { exists("flow_section_nudge") }
        compose.onNodeWithTag("flow_section_nudge").assertIsDisplayed()
        // Tapping it again only scrolls: the flow is not added twice.
        vm.addFlow(AgentFlows.NUDGE)
        assertEquals(2, vm.state.value.sections.size)
    }

    @Test fun systemChipShowsActiveVersion() {
        show(vm("ask"))
        compose.waitUntil(5_000) { exists("flow_system") }
        compose.onNodeWithTag("flow_system", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("System preamble \u00b7 v1").assertExists()
        runBlocking {
            ai.prompts.settings.setKeepVersions(true)
            ai.prompts.save(PromptKey.SYSTEM, PromptDraft("Be kind.", null), "n", testedOk = false)
        }
        compose.waitUntil(5_000) { runCatching { compose.onNodeWithText("System preamble \u00b7 v2").assertExists() }.isSuccess }
    }

    /** Review Focus 5: a layer wider than the phone scrolls inside its section; the page never grows past the screen. */
    @Test fun wideLayerScrollsInsideSection() {
        show(vm("wide", registry = { if (it == "wide") WIDE_FLOW else null }))
        val screen = compose.onRoot().getUnclippedBoundsInRoot().let { it.right - it.left }
        val section = compose.onNodeWithTag("flow_section_wide").getUnclippedBoundsInRoot().let { it.right - it.left }
        assertTrue("section $section fits the screen $screen", section <= screen)
        compose.onNodeWithTag("flow_node_wide_n6").performScrollTo().assertIsDisplayed()
    }

    /** D1 swaps B's stand-in for the registry: the list's counts and roles come from AgentFlows. */
    @Test fun listSummariesComeFromTheRegistry() {
        val all = AgentFlowSummaries.summaries()
        assertEquals(listOf("capture", "ask", "nudge"), all.map { it.id })
        val byId = all.associateBy { it.id }
        assertEquals(7, byId.getValue("capture").aiSteps)
        assertEquals(listOf(Role.VISION_EXTRACT, Role.CRITIC, Role.SUMMARIZE, Role.EMBED, Role.EXPLICIT_REMEMBER), byId.getValue("capture").roles)
        assertEquals(2, byId.getValue("ask").aiSteps)
        assertEquals(listOf(Role.EMBED, Role.ANSWER), byId.getValue("ask").roles)
        assertEquals(1, byId.getValue("nudge").aiSteps)
        assertEquals(listOf(Role.NUDGE), byId.getValue("nudge").roles)
    }
}
