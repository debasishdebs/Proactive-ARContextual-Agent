# Agent Flows Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. This is a **lean plan**, per the user's standing preference: exact interfaces, behaviour bullets and named tests, not full code. Streams A, B and C run in parallel worktrees, and D runs after A and B merge.

**Goal:** Settings → AI → Agent flows. A searchable, multi-select list of agent flows. Each selected flow renders as a DAG. Tapping an AI step opens a sheet with Prompt / Model / Retrieval / Custom tabs, and the prompt editor gains a full-screen mode.

**Architecture:** A pure-Kotlin flow registry and layered layout live in `:services:ai` (package `com.rokid.context.ai.flow`). The phone gets a generic list screen ported from LiveFit, a Canvas-drawn flow view, and a step sheet that reuses the existing Roles, Retrieval and PromptEditor code. There is no new write path, no Room change, and no production-behaviour change.

**Tech Stack:** Kotlin, Jetpack Compose (Material3), Navigation-Compose, Robolectric (`@Config(sdk=[34])`), JUnit4 (`org.junit.Assert`) in `:services:ai`.

**Spec:** `docs/superpowers/specs/2026-10-14-agent-flows-design.md` (approved 2026-10-14, at 2248495)

## Global Constraints

- Agent flows lives **only** under Settings → AI: list, graph view, step sheet and the editors it opens sit in the Settings nav stack. Back returns step by step to Settings → AI. There is no entry from Home, Activity, notifications or the HUD.
- No Room schema change (schema v1 is frozen), no new write path. Saves go through the existing `ai.bindings`, `ai.prompts`, `s.retrieval` repositories and validation.
- SYSTEM prompt save exception is preserved: no test run, Variables gate only (`SaveGates.kt:26`, `PromptEditor.kt:207`). Role prompts re-lock Save after any edit, including a full-screen Done.
- Model and Retrieval tabs are labelled as **shared**: "Model for <role label> · shared by N steps" (listing the steps) and "Retrieval settings · shared by every search step".
- No raw error text in UI (§6.3). An unknown flow id is skipped. Empty selection shows "No flows selected". An unknown Custom editor id shows "Not available in this version".
- Android ICU regex: no unescaped `}` in any Regex (`AndroidRegexRulesTest`). Keep `\uXXXX` escapes as escapes, and grep each diff for accidental non-ASCII in `.kt` files.
- Gradle: `./gradlew --no-daemon …` from `android-companion/`. Never `gradlew --stop`. One Gradle run per worktree at a time.
- Never print secrets, never push. Commit messages end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- FAST MODE: fix only Critical and Important findings inside a task. Minors go to the ledger.

## Review Focus

1. **A flow edge that doesn't match the code.** A person tunes the step the graph shows, so if the graph's order is wrong they tune the wrong thing. A1's `AgentFlowsMatchCodeTest` pins each conditional edge to a source anchor (see A1).
2. **Back-stack depth.** Settings → AI → Agent flows → view → sheet → Open editor → full screen. Back must unwind one level at a time, and never land on Home or lose an unsaved editor draft without the editor's existing discard behaviour. D2 owns `backFromEditorReturnsToFlowViewThenListThenSettings`.
3. **An edit in the sheet while the card is visible.** The card's facet line must update live, not after reopening. D1 owns `facetLineUpdatesAfterModelChange`.
4. **Rotation or process death in the full-screen editor.** Text typed in full screen must survive a configuration change, either via rememberSaveable or by writing to the VM draft on every keystroke. C1 owns `fullScreenTextSurvivesRecreation`.
5. **Long node titles and narrow phones.** Cards ellipsize at 2 lines, and wide layers scroll horizontally inside their section, never pushing the page off-screen. D1 owns `wideLayerScrollsInsideSection`.

---

## Stream A: flow registry + layout (`:services:ai`)

### Task A1: Flow model and the day-one flows

**Files:**
- Create: `services/ai/src/main/java/com/rokid/context/ai/flow/AgentFlow.kt` (the model types)
- Create: `services/ai/src/main/java/com/rokid/context/ai/flow/AgentFlows.kt` (`object AgentFlows { val all: List<AgentFlow>; fun byId(id: String): AgentFlow? }`)
- Create: `services/ai/src/main/java/com/rokid/context/ai/flow/flows/CaptureFlow.kt`, `AskFlow.kt`, `NudgeFlow.kt` (one `val` each)
- Test: `services/ai/src/test/java/com/rokid/context/ai/flow/AgentFlowsTest.kt`, `AgentFlowsMatchCodeTest.kt`

**Interfaces (Produces):**
```kotlin
data class AgentFlow(val id: String, val title: String, val purpose: String, val nodes: List<FlowNode>, val edges: List<FlowEdge>)
data class FlowEdge(val from: String, val to: String, val label: String? = null)
data class FlowNode(val id: String, val title: String, val kind: NodeKind, val facets: List<Facet> = emptyList(), val linkTo: String? = null) {
    val isAiStep: Boolean get() = facets.isNotEmpty()
}
enum class NodeKind { INPUT, STEP, OUTPUT, LINK }
sealed interface Facet {
    data class Prompt(val key: PromptKey) : Facet
    data class Model(val role: Role) : Facet
    data object Retrieval : Facet
    data class Custom(val editorId: String) : Facet
}
object AgentFlows {
    const val CAPTURE = "capture"; const val ASK = "ask"; const val NUDGE = "nudge"
    val all: List<AgentFlow>
    fun byId(id: String): AgentFlow?
    /** Every (flowId, node) that has Facet.Model(role) — for "shared by N steps". */
    fun nodesUsing(role: Role): List<Pair<AgentFlow, FlowNode>>
}
```

**Behaviour:**
- Encode spec §2 exactly, as three flows.
  - **Capture** nodes (ids in brackets):
    - photo[INPUT];
    - extract "Facts from frames" [Prompt VISION_EXTRACT, Model VISION_EXTRACT];
    - confidence "Confidence check" [STEP, no facets];
    - critic "Critic" [Prompt CRITIC, Model CRITIC];
    - save "Save memory"[STEP];
    - save_review "Save memory (needs review)"[STEP];
    - reprocess "Reprocess" [Prompt SUMMARIZE, Model SUMMARIZE];
    - embed "Embed" [Model EMBED];
    - memory "Memory"[OUTPUT];
    - to_nudge "→ Nudge"[LINK, linkTo=nudge];
    - remember_in "\"Remember this\""[INPUT];
    - remember "Remember this" [Prompt/Model EXPLICIT_REMEMBER];
    - thumbs_in "👎 on an answer"[INPUT];
    - critic_review "Critic review" [Prompt/Model CRITIC];
    - forget_in "Forget"[INPUT];
    - resummarize "Re-summarise derived memories" [Prompt/Model SUMMARIZE].
  - **Capture** edges, with labels per spec:
    - photo→extract;
    - extract→confidence;
    - confidence→save "trusted";
    - confidence→critic "low confidence";
    - critic→save "pass";
    - critic→save_review "fail / unbound";
    - save_review→reprocess;
    - reprocess→save;
    - save→embed;
    - embed→memory;
    - save→to_nudge "after a trusted pass or reprocess";
    - remember_in→remember→save;
    - thumbs_in→critic_review;
    - critic_review→memory "pass → OK / fail → stays in review";
    - forget_in→resummarize→save.
  - **Ask:**
    - nodes: question[INPUT], search "Search memories" [Retrieval, Model EMBED], answer "Answer" [Prompt/Model ANSWER], shown "Answer shown / spoken"[OUTPUT], propose "Propose action"[STEP];
    - edges: question→search→answer→shown, and answer→propose "if proposedAction".
  - **Nudge:**
    - nodes: trigger "After summarizer pass"[INPUT], recent "Recent memories"[STEP], nudge "Nudge" [Prompt/Model NUDGE], shown "Nudge shown"[OUTPUT], propose "Propose action"[STEP];
    - edges: trigger→recent→nudge, nudge→shown "if shouldNudge", nudge→propose "if proposedAction".
  - Purposes (one line each):
    - Capture: "Turns glasses photos and things you say into memories."
    - Ask: "Answers your questions from your memories."
    - Nudge: "Suggests something useful after new memories land."
- Before writing the flows, re-verify each edge against the code. Read `services/orchestration/.../work/QueueDrainWorker.kt` (processBatchedSummarization, processPriorityReprocess, processCriticReview, explicit remember), `ResummarizeWorker.kt`, `AnswerService.kt`, `nudge/NudgeTrigger.kt`, `NudgeWorker.kt` and `NudgeEngine.kt`. A mismatch with the spec means: encode the code's behaviour, and write `Ruling: <edge> — <code anchor>` in the task report.

**Tests:**
- `AgentFlowsTest`:
  - `everyRoleAndRolePromptAppearsInSomeFlow`: each `Role.entries` value appears as a `Facet.Model`, and each `PromptKey` with non-null role appears as a `Facet.Prompt`;
  - `nodeIdsAreUniquePerFlow`;
  - `edgesReferenceNodesOfTheirFlow`;
  - `flowsAreAcyclic`;
  - `everyFlowHasAnInput`;
  - `linkNodesNameARegisteredFlow`;
  - `nonLinkNodesHaveNoLinkTo`;
  - `byIdReturnsNullForUnknown`;
  - `nodesUsingEmbedListsEmbedAndSearchMemories`.
- `AgentFlowsMatchCodeTest` (a source-anchor test: it reads the Kotlin source files as text from the repo, as `AndroidRegexRulesTest` does). It asserts the anchors the conditional edges depend on still exist. If one disappears, the test fails and points at the edge to re-check. Anchors:
  - `criticWorker.validate(` and `|| modelCriticPasses(` in QueueDrainWorker → confidence→critic is conditional;
  - `QueuePriority.PRIORITY_REPROCESS` enqueue in the not-trusted branch → save_review→reprocess;
  - `PromptKey.SUMMARIZE` in processPriorityReprocess;
  - `NudgeTrigger.afterSummarizerPass` called at least twice in QueueDrainWorker → save→to_nudge;
  - `KIND_CRITIC_REVIEW` → thumbs_in→critic_review;
  - `PromptKey.EXPLICIT_REMEMBER`;
  - NudgeTrigger enqueues `NudgeWorker` with no `PeriodicWorkRequest` anywhere in orchestration main → the Nudge INPUT is a trigger, not a schedule.
  - Each failure message names the edge.

- [ ] Write both test files. Run `./gradlew --no-daemon :services:ai:testDebugUnitTest --tests '*AgentFlows*'` and expect a compile failure (RED: types missing). Record it.
- [ ] Implement the model + registry + three flows. Run the same command and expect PASS.
- [ ] Commit `feat(flows): agent flow registry with the capture, ask and nudge flows`.

### Task A2: `FlowLayout`

**Files:**
- Create: `services/ai/src/main/java/com/rokid/context/ai/flow/FlowLayout.kt`
- Test: `services/ai/src/test/java/com/rokid/context/ai/flow/FlowLayoutTest.kt`

**Interfaces (Produces):**
```kotlin
data class Cell(val layer: Int, val column: Int)
data class FlowLayout(val cells: Map<String, Cell>, val layers: Int, val columns: Int) // columns = max columns in any layer
object FlowLayouts { fun layout(flow: AgentFlow): FlowLayout }
```

**Behaviour (spec §3, three passes):**
1. **Primary input.** The primary INPUT is the first node with kind INPUT. Every other node's layer is its longest-path depth from the primary, over edges, using only nodes reachable from the primary. A node reachable only from side inputs belongs to its side chain (pass 2).
2. **Side inputs.** For each non-primary INPUT, walk its chain: follow single successors until reaching a node already layered by pass 1 (the *join*). Place the chain's last node at `layer(join) − 1`, each earlier chain node one layer higher, and the INPUT at the top. If any resulting layer is < 0, shift **all** nodes down by `−min`. If the join has several layered successors through the chain, use the minimum layer.
3. **Columns.** Within each layer, order nodes by declaration order. Then run one barycentre sweep (by mean column of predecessors) top-down and one bottom-up (by successors), with stable ties. Side-input chains go to the outermost column on the side nearer their join's column, alternating right first.

The output is deterministic, and no two nodes share a cell.

**Tests (`FlowLayoutTest`):**
- `linearChainIsOneColumn`;
- `branchPutsSiblingsInOneLayerTwoColumns`;
- `sideInputSitsOneLayerAboveItsTarget`;
- `sideInputAboveALayerZeroTargetShiftsEverythingDown`;
- `twoNodeSideChainEndsOneLayerAboveItsJoin` (uses Capture's remember_in→remember→save);
- `noTwoNodesShareACell`, checked for all `AgentFlows.all`;
- `layerStrictlyIncreasesAlongEveryEdge`, also for all flows. The capture→… edges into `save` from side chains satisfy this because chains end at `layer(save) − 1`. The `reprocess→save` back-edge is the exception: `save` is reached from `confidence`, while reprocess sits below `save_review`, which is below critic. Rule here: an edge whose target's layer ≤ source's layer is a **back-edge**. It is drawn routed around the side and excluded from this assertion, and `FlowLayout` exposes `val backEdges: Set<FlowEdge>`. Add `reprocessToSaveIsTheOnlyBackEdgeInCapture`.
- `layoutIsDeterministic`.

- [ ] Write the tests, then RED (compile).
- [ ] Implement, add `backEdges` to `FlowLayout`, then GREEN.
- [ ] Commit `feat(flows): layered layout with side-input alignment and back-edges`.

---

## Stream B: generic list + Agent flows list (`:phone`)

### Task B1: Generic `ListScreen` with multi-select (LiveFit port)

**Files:**
- Create: `phone/src/main/java/com/rokid/context/phone/ui/list/ListSource.kt` and `ListScreen.kt`. Port these from `/Users/debasishkanhar/Desktop/rokid/live-fitness-tracker/phone/src/main/java/com/debasish/livefit/phone/ui/list/ListSource.kt` and `ListScreen.kt`, and read both first.
- Test: `phone/src/test/java/com/rokid/context/phone/ui/list/ListScreenTest.kt`

**Interfaces (Produces):** the LiveFit `ListItem`, `ItemStatus`, `ItemAction`, `ActionResult` and `ListSource` with these changes:
- Package `com.rokid.context.phone.ui.list`.
- Drop `ListSources.create` (this app wires sources at the call site), `filter: JSONObject?` (sources take constructor params) and `glyph`/flag specifics.
- Add `val selectable: Boolean get() = false` and `val selectLabel: (Int) -> String get() = { "Show selected ($it)" }` to `ListSource`.
- The screen signature is `@Composable fun ListScreen(source: ListSource, onBack: () -> Unit, onMessage: (String) -> Unit, onOpenSelected: (List<String>) -> Unit = {}, initiallySelected: Set<String> = emptySet(), onSelectionChange: (Set<String>) -> Unit = {})`.

**Behaviour:**
- Use this app's theme and components (`PcColors`, `SettingsPage`/`ScreenHeader` patterns from `ui/settings/SettingsWidgets.kt` and `core/ui` components) instead of LiveFit's.
- Search filters by title and subtitle (case-insensitive). A–Z sort shows only when `sortable`. The status filter shows only when `statusLabels` is non-empty.
- When `selectable`:
  - each row shows a checkbox (test tag `list_select_<id>`);
  - tapping the checkbox toggles selection, and tapping the row body runs `actionFor`;
  - a bottom bar `list_show_selected` shows `selectLabel(n)` and is enabled only when n ≥ 1;
  - tapping it calls `onOpenSelected` with the ids in list order.
- Selection survives recreation (`rememberSaveable`).
- Empty list shows "Nothing here yet". Searching with no matches shows "No matches".

**Tests (`ListScreenTest`, Robolectric):**
- `searchFiltersByTitleAndSubtitle`;
- `selectionBarAppearsOnlyWhenSelectableAndCountsSelection`;
- `showSelectedPassesIdsInListOrder`;
- `rowTapRunsActionNotSelection`;
- `selectionSurvivesRecreation` (use `StateRestorationTester`);
- `noMatchesShowsEmptyText`.

- [ ] Tests, then RED, then port + adapt, then GREEN. Commit `feat(ui): generic list screen with multi-select (ported from LiveFit)`.

### Task B2: Agent flows list, route, Settings → AI entry

**Files:**
- Create: `phone/src/main/java/com/rokid/context/phone/ui/settings/flows/FlowsListSource.kt`
- Modify:
  - `phone/src/main/java/com/rokid/context/phone/ui/shell/Routes.kt`: add `const val SETTINGS_FLOWS = "settings/flows"`, `const val SETTINGS_FLOWS_VIEW = "settings/flows/view?ids={ids}"` and `fun flowsView(ids: List<String>) = "settings/flows/view?ids=" + ids.joinToString(",")`.
  - `phone/src/main/java/com/rokid/context/phone/ui/settings/SettingsRoot.kt` (AI section, line ~98): insert `SettingRow(Icons.Outlined.AccountTree, PcColors.ChipViolet, "Agent flows", "See and tune each step of every agent", Routes.SETTINGS_FLOWS)` as the **first** AI row.
  - `phone/src/main/java/com/rokid/context/phone/ui/shell/SettingsDestinations.kt`: a `composable(Routes.SETTINGS_FLOWS)` that hosts `ListScreen(FlowsListSource(...))`. Its `onOpenSelected` navigates to `Routes.flowsView(ids)`, and a row tap navigates to `Routes.flowsView(listOf(id))`. Also register `SETTINGS_FLOWS_VIEW` with a temporary placeholder composable that renders `Text("Flows: $ids")` and tag `flows_view_placeholder`; D2 replaces it.
- Test: `phone/src/test/java/com/rokid/context/phone/ui/settings/flows/FlowsListTest.kt`

**Interfaces:**
- Consumes `AgentFlows.all` (A1). Stream B may start before A merges: define a local `interface FlowSummaries { fun summaries(): List<FlowSummary> }` and `data class FlowSummary(val id: String, val title: String, val purpose: String, val aiSteps: Int, val roles: List<Role>)`, backed in production by `AgentFlows` once A is merged. Until then, back it with a fixture in tests. The integration step at D start swaps in the real registry.
- Produces: `class FlowsListSource(summaries: FlowSummaries, models: suspend (Role) -> String?, onOpen: (String) -> Unit) : ListSource` with `selectable = true`, `sortable = false` (registry order), `searchHint = "Search agent flows"`, and `title = "Agent flows"`.

**Behaviour:**
- Each row shows the title, a subtitle `"<purpose>"`, and a trailingText of `"N AI steps · <distinct bound model names, comma-separated, max 2 then +k>"`.
- An unbound role contributes "Not set" once.
- Selection is remembered for the session in a `rememberSaveable` held at the `SETTINGS_FLOWS` destination.

**Tests:**
- `subtitleCountsAiStepsAndDistinctModels`;
- `unboundRoleShowsNotSetOnce`;
- `settingsAiSectionListsAgentFlowsFirst`, extending `SettingsRootTest` style;
- `rowTapOpensThatFlowAlone`;
- `showSelectedOpensViewWithIds`, which asserts the nav route string `settings/flows/view?ids=capture,ask`.

- [ ] Tests, then RED, then implement, then GREEN.
- [ ] Run `AllRoutesSmokeTest`. It must now enumerate the 2 new routes, so add the fixture `{ids}` → `capture,ask,nudge` to its placeholder map. This is allowed in B: it is a test file.
- [ ] Commit `feat(flows): Agent flows list under Settings → AI`.

---

## Stream C: full-screen prompt editor (`:phone`)

### Task C1: Full-screen template and schema editor

**Files:**
- Create: `phone/src/main/java/com/rokid/context/phone/ui/settings/prompts/FullScreenTextEditor.kt`
- Modify: `phone/src/main/java/com/rokid/context/phone/ui/settings/prompts/PromptEditor.kt`. Change only the template block (~lines 287–289) and the schema block, adding an expand `IconButton` beside each title. Do not change `PromptEditorViewModel` gating logic.
- Test: `phone/src/test/java/com/rokid/context/phone/ui/settings/prompts/FullScreenEditorTest.kt`

**Interfaces (Produces):**
```kotlin
@Composable fun FullScreenTextEditor(title: String, initial: String, chips: List<String>, onDone: (String) -> Unit, onCancel: () -> Unit, tag: String)
```
Use a full-screen `Dialog(properties = DialogProperties(usePlatformDefaultWidth = false))` with a monospace multi-line `TextField` (tag `"${tag}_field"`), a chip row (tag `"${tag}_chip_<name>"`) where tapping a chip inserts `{{name}}` at the cursor (`TextFieldValue` selection), and buttons Done (`"${tag}_done"`) and Cancel (`"${tag}_cancel"`). The text is held in `rememberSaveable(stateSaver = TextFieldValue.Saver)`.

**Behaviour:**
- **Template expand** (tag `prompt_template_expand`) opens the editor with `chips = TemplateVariables.KNOWN`. Done calls the existing `onTemplate(text)`, so it goes through the same VM path as an inline edit. Cancel closes it and leaves the draft unchanged.
- **Schema expand** (tag `prompt_schema_expand`) shows only when `state.hasSchema`, so it never appears for SYSTEM. It opens with `chips = emptyList()`, and Done calls `onSchema(text)`.
- The expand buttons are disabled while `state.saving`.

**Tests (`FullScreenEditorTest`, Robolectric):**
- `roleTemplateFullScreenDoneUpdatesDraftAndRelocksSave`: start from a tested, saveable ANSWER draft, edit in full screen, tap Done; the template shows the new text and `prompt_save` is disabled until a run.
- `cancelLeavesDraftUntouched`.
- `chipInsertsVariableAtCursor`.
- `systemFullScreenDoneReevaluatesOnlyVariablesAndAllowsSaveWithoutRun`: SYSTEM, Done with valid text gives Save enabled, no run.
- `systemUnknownVariableBlocksSave`: `{{nope}}` keeps Save disabled.
- `schemaExpandHiddenForSystem`.
- `fullScreenTextSurvivesRecreation`, using `StateRestorationTester` (Review Focus 4).

Reuse the existing `PromptEditor` test fixtures and fake runner. Find them via `grep -rln PromptEditorViewModel phone/src/test`.

- [ ] Tests, then RED, then implement, then GREEN. Commit `feat(prompts): full-screen template and schema editor`.

---

## Stream D: flow view + step sheet (`:phone`), after A and B merge

### Task D1: Flow view (Canvas) and step cards

**Files:**
- Create: `phone/src/main/java/com/rokid/context/phone/ui/settings/flows/FlowView.kt`. It holds `FlowViewModel`, `FlowViewUiState` and `@Composable FlowViewScreen`.
- Create: `phone/src/main/java/com/rokid/context/phone/ui/settings/flows/FlowGraph.kt`, the per-flow Canvas section.
- Modify: `FlowsListSource.kt` to back `FlowSummaries` with `AgentFlows`, removing the stand-in.
- Test: `phone/src/test/java/com/rokid/context/phone/ui/settings/flows/FlowViewTest.kt`

**Interfaces:**
- Consumes `AgentFlows`, `FlowLayouts.layout`, `FlowLayout.backEdges` (A), and `ai.bindings.observeAll()`, `ai.prompts` (active version per key; find the observe method in `PromptRepository`) and `s.retrieval` (current mode and fusion), all from `SettingsDeps`/`AiGraph`.
- Produces:
```kotlin
data class FacetLine(val facet: Facet, val text: String)          // "Prompt v3", "gpt-4o · t 0.2", "Not set", "Hybrid · RRF"
data class NodeUi(val node: FlowNode, val lines: List<FacetLine>)
data class FlowSectionUi(val flow: AgentFlow, val layout: FlowLayout, val nodes: Map<String, NodeUi>)
data class FlowViewUiState(val system: FacetLine?, val sections: List<FlowSectionUi>, val empty: Boolean)
class FlowViewModel(ids: List<String>, ai: AiGraph, retrieval: RetrievalStore /* exact type from SettingsDeps */) : ViewModel() {
    val state: StateFlow<FlowViewUiState>
    fun addFlow(id: String)   // LINK tap
}
```

**Behaviour:**
- Unknown ids are dropped. If no ids remain, `empty = true` and the screen shows "No flows selected".
- The System chip at the top (tag `flow_system`) shows "System preamble · v<N>".
- Each section (tag `flow_section_<id>`) has the title, then a `Box` with `horizontalScroll` containing a `Canvas` for edges, plus node composables absolutely positioned at `cell → (column*W, layer*H)`.
- Edges are straight lines with arrowheads. Back-edges route out to the right margin and back, and labels are drawn at the edge midpoint (testable as a `Text` with tag `flow_edge_label_<from>_<to>`).
- An AI-step card (tag `flow_node_<flowId>_<nodeId>`) shows the title (max 2 lines, ellipsis) and its facet lines. Other kinds render as muted pills.
- Facet line text:
  - Prompt: `"Prompt v<active>"`, or `"Prompt missing — Reset to default"`;
  - Model: `"<model>"` plus `" · t <temp>"` if set, or `"Not set"`;
  - Retrieval: `"<Mode> · <Fusion>"`;
  - Custom: `"Custom"`.
- Lines are recomputed from flows (live) (Review Focus 3).
- Tapping a LINK pill calls `addFlow(linkTo)` if the flow isn't shown, then scrolls to that section.

**Tests (`FlowViewTest`):**
- `rendersOneSectionPerKnownId`;
- `unknownIdsAreSkippedAndEmptyShowsNoFlowsSelected`;
- `conditionalEdgesShowTheirLabels`, which checks every labelled edge of Capture and Nudge;
- `aiStepsAreCardsOthersArePills`;
- `facetLineUpdatesAfterModelChange` (Review Focus 3);
- `linkPillAddsNudgeFlow`;
- `systemChipShowsActiveVersion`;
- `wideLayerScrollsInsideSection`, where a fixture flow with 6 nodes in one layer keeps the section width ≤ the screen width and lets you scroll to the last node (Review Focus 5).

- [ ] Tests, then RED, then implement, then GREEN. Commit `feat(flows): flow view with layered DAG sections`.

### Task D2: Step sheet (Prompt / Model / Retrieval / Custom tabs) + nav wiring

**Files:**
- Create: `phone/src/main/java/com/rokid/context/phone/ui/settings/flows/StepSheet.kt`
- Create: `phone/src/main/java/com/rokid/context/phone/ui/settings/flows/FacetEditors.kt`, containing `object FacetEditors { val all: Map<String, @Composable () -> Unit> = emptyMap() }`
- Modify: `phone/src/main/java/com/rokid/context/phone/ui/settings/ai/Roles.kt`. Extract the per-role card body inside `RolesScreen` into `@Composable fun RoleBindingCard(r: RoleRowUi, onEndpoint…, onClear…)` with the same params as the lambdas. `RolesScreen` then calls it. This is a pure refactor, so the existing Roles tests must stay green unchanged.
- Modify: `phone/src/main/java/com/rokid/context/phone/ui/settings/retrieval/RetrievalSettings.kt`. Extract the mode/fusion/weights/topK/recency controls into `@Composable fun RetrievalControls(state: RetrievalUiState, on… )`, which `RetrievalSettingsScreen` calls. Also a pure refactor.
- Modify: `SettingsDestinations.kt`. Replace the `SETTINGS_FLOWS_VIEW` placeholder with `FlowViewScreen` plus the sheet. The sheet hosts `RolesViewModel` and `RetrievalViewModel`, constructed as their own destinations construct them, so it reuses the same write path and validation. Its Prompt tab's **Open editor** calls `nav.navigate(Routes.prompt(key.name))`.
- Test: `phone/src/test/java/com/rokid/context/phone/ui/settings/flows/StepSheetTest.kt`

**Behaviour:**
- Tapping an AI-step card opens a `ModalBottomSheet` (tag `step_sheet`) with one tab per facet, in the node's facet order (tag `step_tab_<Prompt|Model|Retrieval|Custom>`).
  - **Prompt tab:** the label, "Active v<N>", the variables the template uses, and the **Open editor** button (`step_open_editor`).
  - **Model tab:**
    - header `"Model for <AiLabels.role(role)> · shared by N steps"` (tag `step_model_header`);
    - below it, `"Used by: <node titles from AgentFlows.nodesUsing(role)>"`;
    - then `RoleBindingCard` for that role;
    - when the role is unbound, the card shows the picker, as Roles does today.
  - **Retrieval tab:** the header `"Retrieval settings · shared by every search step"` (tag `step_retrieval_header`) above `RetrievalControls`.
  - **Custom tab:** `FacetEditors.all[editorId]`, or `"Not available in this version"` (tag `step_custom_unavailable`).
- Back closes the sheet first, then the view, then the list. The view returns to the list, and the list returns to Settings root with the AI section visible.

**Tests (`StepSheetTest`):**
- `answerOpensPromptAndModelTabs`, which shows ANSWER's version and model;
- `searchMemoriesOpensRetrievalAndModelEmbedTabs`;
- `modelHeaderSaysSharedAndListsUsers`: EMBED lists "Embed" and "Search memories";
- `changingEmbedFromSearchMemoriesUpdatesBothCards`;
- `retrievalHeaderSaysShared`;
- `unknownCustomEditorShowsFallback`, using a test-only flow with `Facet.Custom("nope")` passed via a VM param, never the registry;
- `openEditorNavigatesToPromptRoute`;
- `backFromEditorReturnsToFlowViewThenListThenSettings` (Review Focus 2), which runs the full `AppShell` NavHost as `AllRoutesSmokeTest` does.
- The existing `RolesScreen`/`RetrievalSettings` tests must still pass unchanged.

- [ ] Tests, then RED, then extract `RoleBindingCard`/`RetrievalControls` (Roles/Retrieval tests green), then implement the sheet and wiring, then GREEN.
- [ ] Run the full gate `./gradlew --no-daemon test :phone:assembleDebug :glasses:assembleDebug`. Expect BUILD SUCCESSFUL and 0 failures. `AllRoutesSmokeTest` must cover `settings/flows` and `settings/flows/view?ids=…` and render with no raw error text.
- [ ] Commit `feat(flows): step sheet with shared model/retrieval tabs and editor hand-off`.

---

## Merge order
1. A, B and C run in parallel worktrees branched from `feat/proactive-mvp-implementation`. They touch disjoint files. B and D both touch `SettingsDestinations.kt`, but D runs after B. B, C and D all live in `:phone`, while A lives in `:services:ai`.
2. Merge A, B and C into `agent-flows` (a branch off feat). Run the full gate.
3. Run D on `agent-flows`, then the full gate.
4. Run the final whole-feature review (opus) and a fix pass for Critical/Important findings. Then merge `agent-flows` into `feat/proactive-mvp-implementation` with `--no-ff`, locally.
5. Device check (controller): Settings → AI → Agent flows. Select Capture + Ask, tap *Search memories*, then Model tab: confirm the header says shared. Open the Answer prompt editor and go full screen.
