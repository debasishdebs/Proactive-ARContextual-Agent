# Agent flows — design

**Date:** 2026-10-14 · **Status:** draft for review · **Builds on:** `2026-10-12-v1-redesign-design.md` (§4.2 roles, §4.3–4.5 prompts/test bed, retrieval settings)

## 1. Intent

What the user said:
- Show the app's agentic pipeline as DAGs. A node is one step. Tapping a node opens that step's editor as a popup. The test bed keeps working as it does today.
- Add a full-screen prompt editor.
- Make model tuning intuitive in the same way.
- Agents will multiply (fitness/food reminders, work reminders, groceries/day-to-day…). One canvas holding every flow gets too heavy, so show a **list of flows** and let the user pick several to render and edit.
- "AI steps" means **every** configurable intelligent step: LLM prompts, **retrieval**, embeddings, and **future ML models**, not just prompts.
- Scope is the steps that exist today (decision A). New agents come later as separate work.
- Build it modular and reuse the LiveFit generic list pattern. It should be a modest change.

Success: in two taps from Settings, a user sees the chain a step sits in, opens that exact step, changes its prompt, model or retrieval settings, tests the step, and saves it. Adding a future agent means adding one flow definition. No screen changes.

Non-goals (v1 of this feature): editing the graph's structure (adding/removing/reordering nodes), zoom/pan, new pipeline stages, changes to Room or production behaviour.

## 2. Model: flow registry (pure Kotlin, `:services:ai`, package `com.rokid.context.ai.flow`)

```kotlin
data class AgentFlow(val id: String, val title: String, val purpose: String, val nodes: List<FlowNode>, val edges: List<FlowEdge>)
data class FlowEdge(val from: String, val to: String, val label: String? = null)   // label e.g. "if shouldNudge"
data class FlowNode(val id: String, val title: String, val kind: NodeKind, val facets: List<Facet> = emptyList())
enum class NodeKind { INPUT, STEP, OUTPUT, LINK } // INPUT/OUTPUT/LINK and facet-less STEPs render as muted pills
sealed interface Facet {
    data class Prompt(val key: PromptKey) : Facet            // LLM prompt: editor, test bed, versions
    data class Model(val role: Role) : Facet                 // endpoint/model/temperature/fallback binding
    data object Retrieval : Facet                            // lexical/semantic/hybrid + fusion settings
    data class Custom(val editorId: String) : Facet          // future ML models: an editor registered by id
}
object AgentFlows { val all: List<AgentFlow> }               // one file per flow; `all` lists them
```

A node is an **"AI step"** (rendered as a bold card) iff it has at least one facet. A node can have several facets. For example, *Answer* has Prompt(ANSWER) + Model(ANSWER), and *Search memories* has Retrieval + Model(EMBED).

Day-one flows. They match current execution, as verified on 6171393 against `QueueDrainWorker` (processBatchedSummarization :160–216, processPriorityReprocess :240–259, processCriticReview :262+, explicit remember :287+), `ResummarizeWorker`, `AnswerService`, `NudgeTrigger`/`NudgeWorker`/`NudgeEngine`. Conditional edges carry their condition as the edge label. The build re-verifies each edge against those files, and a mismatch is a finding.
1. **Capture → memory**
   - Photo (INPUT) → Facts from frames [Prompt VISION_EXTRACT, Model VISION_EXTRACT] (one call per photo) → Confidence check (STEP; local validator, no model).
   - Confidence check → Save memory (STEP), label "trusted".
   - Confidence check → Critic [Prompt CRITIC, Model CRITIC], label "low confidence". This runs only when the local check fails and CRITIC is bound.
   - Critic → Save memory, label "pass".
   - Critic → Save memory (needs review), label "fail / unbound" → Reprocess [Prompt SUMMARIZE, Model SUMMARIZE] → Save memory.
   - Save memory → Embed [Model EMBED] → Memory (OUTPUT).
   - Save memory → **Nudge flow** (cross-flow link node, see below), label "after a trusted pass or reprocess".
   - Side inputs:
     - "Remember this" voice (INPUT) → Remember this [Prompt/Model EXPLICIT_REMEMBER] → Save memory.
     - 👎 on an answer (INPUT) → Critic review [Prompt/Model CRITIC] → Memory (OUTPUT), label "pass → OK / fail → stays in review".
     - Forget (INPUT) → Re-summarise derived memories [Prompt/Model SUMMARIZE] (ResummarizeWorker) → Save memory.
2. **Ask → answer**: Question (INPUT) → Search memories [Retrieval, Model EMBED] → Answer [Prompt/Model ANSWER] → Answer shown/spoken (OUTPUT). Answer → Propose action (STEP), label "if proposedAction".
3. **Nudge**: After summarizer pass (INPUT; the trigger is `NudgeTrigger.afterSummarizerPass`, not a schedule) → Recent memories (STEP) → Nudge [Prompt/Model NUDGE] → Nudge shown (OUTPUT), label "if shouldNudge". Nudge → Propose action (STEP), label "if proposedAction".

**Cross-flow links.** A node of kind `LINK(flowId)` is drawn as a muted pill "→ Nudge". Tapping it adds that flow to the current view, or scrolls to it if it is already shown. The model adds `NodeKind.LINK` and `FlowNode.linkTo: String?`. A LINK node needs a `linkTo` that names a registered flow.

The shared **System preamble** (PromptKey.SYSTEM) is not a node. Every flow view shows it as one chip with a Prompt facet.

Registry rules (unit-tested):
- every PromptKey with a role, and every Role, appears as a facet in at least one flow;
- node ids are unique within a flow;
- every edge endpoint is a node of that flow;
- each flow is acyclic and has at least one INPUT;
- every `Facet.Custom.editorId` is registered in the phone's editor map (§4). There are none on day one, and the test still runs.

## 3. Layout: `FlowLayout` (pure Kotlin, `:core:ui` or `:services:ai`, no Compose)

`layout(flow): FlowLayout` runs three passes and returns positions in abstract grid units. It is deterministic.
1. **Layering.** Every node except a side input gets its longest-path depth from the flow's **primary INPUT**: the first INPUT declared. Each non-primary INPUT is a **side input** and is left out of this pass.
2. **Side-input alignment.** A side input is placed at `layer(target) − 1`, where `target` is its first successor. A side input with several successors uses the minimum over their layers. If that layer would be < 0 (the target is at layer 0), every node is shifted down one layer, so layer 0 holds only side inputs. A side input's own chain (e.g. Remember this → Save memory) is laid out before its join. Each node on the chain sits one layer above the next, so the chain ends directly above the node it joins.
3. **Columns.** Nodes are ordered within each layer by declaration order, then by one barycentre sweep down and one up to reduce edge crossings. Side inputs go to the outer columns, on the side of their target.

Tests:
- a linear chain gives one column;
- a branch gives two columns in the same layer;
- a side input sits exactly one layer above its target, including when the target is at layer 0, where the shift applies;
- a two-node side chain ends one layer above its join;
- no two nodes share a cell;
- along every edge the layer strictly increases;
- the output is identical across two runs.

## 4. Phone UI (`:phone`, package `ui.settings.flows`)

**Generic list (port from LiveFit `ui/list`).** Bring over `ListItem` / `ListSource` / `ListScreen`: search, an optional status filter, A–Z sort, row actions. Adapt it to this app's theme and `core:ui` components, and add **multi-select** (a checkbox per row, plus a bottom bar "Show selected (N)"). This becomes the app's reusable list screen. Existing screens are not migrated now.

**Placement.** Agent flows lives **only** under Settings → AI. Its list, graph view, step sheet and the editors it opens all sit inside the Settings navigation stack: Back from any of them returns step by step to Settings → AI. There is no entry from Home, Activity, notifications or the HUD.

**Agent flows list.** Route `settings/flows`, reached from Settings → AI as "Agent flows". It shows one row per `AgentFlows.all` entry: the title, the purpose, and the subtitle "N AI steps · <distinct model names>". Tapping a row opens that flow alone. With rows selected, **Show selected** opens them together. The selection is remembered for the session.

**Flow view.** Route `settings/flows/view?ids={ids}`, where ids is comma-separated. The System preamble chip sits at the top. Each flow below it is a titled section, all in one vertical scroll, and each section draws its `FlowLayout` on a Compose `Canvas`: edges are lines with arrowheads, and edge labels are small text.
- **AI-step cards** show the title and one line per facet: "Prompt v3", "gpt-4o · t 0.2", "Hybrid · RRF".
- **Muted pills** are INPUT, OUTPUT and facet-less STEP nodes.
- Wide layers scroll horizontally inside their section.
- The facet line reads live values, so it updates after an edit.

**Step sheet.** Tapping an AI-step card opens a `ModalBottomSheet` with one tab per facet:
- **Prompt:** the active version and its variables, plus **Open editor**, which goes to the existing `settings/prompts/{id}`. The test bed, versions and save gating are unchanged.
- **Model:** that role's binding controls (endpoint, model, temperature, max tokens, fallback). These reuse the code behind `settings/roles` and keep its capability filter (§4.2).
- **Retrieval:** the controls from `settings/retrieval` (mode, fusion, weights). They are shared and reused, not duplicated.
- **Custom:** looks up `FacetEditors[editorId]`, a map from id to composable. An unknown id shows "Not available in this version" and never crashes.

Saving from a tab uses the same repositories and validation as the existing screens. The sheet adds no new write path.

**Shared settings, labelled as shared.** Model and Retrieval settings belong to a role or to the app, not to the tapped node. The Model tab's header reads "Model for <role label> · shared by N steps" and lists those steps, e.g. "EMBED · used by Embed, Search memories". The Retrieval tab's header reads "Retrieval settings · shared by every search step". A prompt is shared when several steps use the same prompt key (CRITIC: Critic and Critic review; SUMMARIZE: Reprocess and Re-summarise derived memories). Its Prompt tab then reads "Prompt <prompt label> · shared by N steps" with a "Used by: …" line, as the Model tab does; a prompt with one step keeps the plain header. A change made in a shared tab updates every card that uses it, and a test checks this (§6).

**Full-screen prompt editor.** In `PromptEditor`, an expand icon on the template field opens a full-screen dialog. It has a monospace multi-line field, the `{{variable}}` chips for the prompt's allowed variables (tap a chip to insert at the cursor), and Done/Cancel. Done writes the text back into the draft through the same path as an inline edit, so the editor's existing gate rules apply unchanged:
- **Role prompts:** Done counts as an edit. All save gates re-disable until a new test run on the unmodified draft.
- **System preamble (SYSTEM):** keeps its existing exception. It has no test run and no schema, and only the Variables gate applies (`SaveGates` :26, `PromptEditor` gateLines/:207). Done re-evaluates Variables only. Save is enabled as soon as the variables are valid, and no test run is required.

Cancel leaves the draft untouched. Role prompts also get the expand icon on the schema field. SYSTEM has no schema field.

The existing **Prompts** and **Models by role** screens stay as they are.

## 5. Errors and edge cases
- A role with no binding: the facet line says "Not set", and the Model tab opens on the picker.
- A missing prompt row (should not happen; there are seeds): "Prompt missing — Reset to default".
- An unknown flow id in the route is skipped, and an empty selection shows "No flows selected". No raw error text appears (§6.3).
- Large flows: layout is O(nodes + edges). Sections render lazily.

## 6. Testing
- **Unit:** the registry rules (§2), the FlowLayout cases (§3), and the subtitle builder (distinct models, unset roles).
- **Compose (Robolectric):**
  - the list filters by search;
  - selecting 2 rows and tapping Show selected renders both sections;
  - tapping *Answer* opens a sheet with Prompt and Model tabs showing ANSWER's prompt version and model;
  - tapping *Search memories* opens Retrieval and Model(EMBED) tabs;
  - a model change in the sheet is persisted and reflected in the card;
  - a full-screen edit round trip on a role prompt updates the draft and re-locks Save until a new test run, and Cancel keeps the draft;
  - a full-screen edit round trip on SYSTEM updates the draft, re-evaluates only Variables, and allows Save with no test run; an unknown `{{variable}}` blocks Save;
  - after changing EMBED's model in the sheet opened from *Search memories*, both the *Search memories* and *Embed* cards show the new model, and the header lists both;
  - tapping the "→ Nudge" LINK pill adds the Nudge flow to the view;
  - each conditional edge from §2 renders with its label;
  - an unknown Custom editor id shows the fallback.
- **Smoke:** `AllRoutesSmokeTest` picks up the two new routes automatically (fixture `{ids}` → all flow ids).

## 7. Delivery
Lean plan, three parallel streams over disjoint files:
- **A:** registry + layout, in `:services:ai` / `:core:ui`.
- **B:** the generic list port + Agent flows list.
- **C:** the full-screen editor in `PromptEditor`.

Then **D**, the flow view + step sheet. D depends on A, and on B's route.
