# Handoff: composite phase B (async black boxes), 2026-10-25

Branch `feat/composite-b` (worktree ~/Desktop/rokid/pca-canvas2), from v0.9.3 tip 7c2f83c4. No PROTOCOL_VERSION, TRACE_SCHEMA, Room schema, plan hash, PlanCompiler, validator, saved-pipeline or executor-behaviour change. No new file in the canvas web assets, no new endpoint.

## Commits
- dac67855  1. Facts from frames (`ai.vision_extract`)
- 9d80aee7  2. Critic (`ai.critic`)
- b6dba0c4  3. Save memory (preview) (`memory.write`)
- (this file) handoff

## Built
Each type now has `inner` (served in `/api/node-types`), so the editor draws the amber "expandable" card and the inner flow with the phase A mechanism (`compositeview.js`). Inner flows are DESCRIPTIONS of the real executors, never run.
1. **Facts from frames**: `Frames in -> Frames per call {batch} -> Vision prompt -> Model call -> (bad batch) Retry one by one / (valid) Parse facts`. Only `batch` is editable inline; default 1 = the executor's fallback for an absent batch (now the one constant `ExtractComposite.DEFAULT_BATCH`, used by the executor and served as the descriptor `default`). Range stays 1..3 (validator and `ExtractSave.MAX_BATCH`). The type got NO `ParamSchema` (kept the backend's own descriptors and the PipelineValidator's batch/prompt checks), so the inspector is unchanged except batch now shows "(default 1)".
2. **Critic**: `Facts in -> Any facts? (none: pass, no model call) -> Critic model set? (no: fail "unbound") -> Critic prompt -> Ask the model -> pass | fail (not ok or error)`. Read only, nothing exposed.
3. **Save memory (preview)**: facts/captions drop blanks, `Trusted flag -> Review state -> Fact row (SHORT)`, `Caption row (LONG)` (always OK), `Preview rows`. Read only, no params.
Mechanism addition (needed to say "read only" truthfully): `InnerFlow.note: List<String>` (default empty, served as `note` only when non-empty so `gate.local_critic` JSON is unchanged). `compositeview.js` draws each line as SVG text under the boxes (`.comp-note`); `layout()` adds `noteLine` (18) per line to its height, so `flowadapter.heightOf` (CARD_H + inner height + pad) and the merged-view spacing include it automatically.

## Files
- services/ai: `CompositeNodes.kt` (InnerFlow.note, ExtractComposite, CriticComposite, MemoryWriteComposite), `NodeTypes.kt` (three `inner =`).
- services/orchestration: `Executors.kt` (literal 1 -> `ExtractComposite.DEFAULT_BATCH`, same value).
- phone: `CanvasBackendImpl.kt` (serves note; batch `default`), assets `compositeview.js`, `style.css` (`.comp-note`).
- Tests: `CompositeInnerTest`, `ExtractCompositeTest` (new, golden), `ExecutorsTest.previewDropsBlank...`, `CanvasBackendImplTest` (served composites + every exposed param is served with a default), JS `composite-ui.test.js`, `flowadapter.test.js`, `mergedflow.test.js`, `fixtures.js`.
- CanvasAssetsTest.jsUnderSizeBudget NOT raised (4550; passes, ~5 lines headroom left).

## Tests
Final clean run, one Gradle: :services:ai 648, :services:orchestration 1635, :phone all tests; the only failure was PromptEditorTest.savingCreatesVersionTwo... (known flaky), which passed alone. 0 other failures.

## Mutations (each proven red, then restored)
- DEFAULT_BATCH 1 -> 2: ExtractCompositeTest (theDefaultIsWhatAnAbsentBatchDoes, theLargestExposed...).
- layout ignores the note height: composite-ui `aNoteIsDrawn...` and flowadapter `anExpandedFactsFromFramesCardCountsItsNote...`.
- note drawing removed; note not served by the backend: composite-ui note test; `everyExposedParamOfACompositeIsServedWithItsDefault`.
- RED before code for feature 1: inner not registered (CompositeInnerTest, 2 CanvasBackendImplTest red; JS tests were written with the JS, proven by the two JS mutations above).
- Critic: model wired from the first shortcut instead of after both (`criticShowsItsFixedShortcuts...`).
- Save memory: caption row fed by the review state (`saveMemoryShowsItsRealRowRules...`); executor changed to dedupe instead of dropping blanks (`previewDropsBlankText...`).

## Decisions taken (please review)
1. **Prompt is not exposed inline** in Facts from frames or Critic: it is an object (live or draft with template/schema) whose editor needs the prompt list; the box is read only and a note says to use the inspector. Model choice does not exist as a node param today (it is a role binding), so it is not shown.
2. **Critic and Save memory are read-only composites** (nothing exposed), with a visible note, since they have no settings other than the prompt.
3. **No dedupe/novelty box in Save memory**: the task mentioned one, but the executor has none; the note says so. The live save path (`ExtractSave.save`) is not what this preview node does; the card is about the preview node.
4. No `ParamSchema` for vision_extract (see above); `batch` default is also in the served descriptor.
5. Inner box labels are kept under about 20 characters (the pill is clipped to the box width).

## NOT done
- Anything a real browser must show: the three cards amber and "expandable"; the wide Facts from frames flow (6 columns, horizontal scroll inside the card); the note lines under the boxes (colour in dark theme, text longer than the SVG width runs on inside the scroll area, not wrapped); the batch input inside its box (default shown, an out-of-range value gives the inspector's own error text); spacing of the next node below an expanded card (tests cover numbers, not pixels); the merged view cards (collapsed only, unchanged).
- Phase C/D (glasses lane, hub.context). No Codex review, no device install.

## Merge steps
1. In ~/Desktop/rokid/pca-int1 (on v0.9.3): `git merge --no-ff feat/composite-b` (shared files: NodeTypes.kt, CompositeNodes.kt, CanvasBackendImpl.kt, compositeview.js, fixtures/tests; canvas-a edits NodeTypes and CanvasBackendImpl too, resolve by keeping both).
2. From android-companion, one Gradle: `./gradlew --no-daemon -Dorg.gradle.workers.max=2 --offline :services:ai:cleanTestDebugUnitTest :services:ai:testDebugUnitTest :services:orchestration:cleanTestDebugUnitTest :services:orchestration:testDebugUnitTest :phone:cleanTestDebugUnitTest :phone:testDebugUnitTest`; rerun flaky ones alone. If both branches changed the JS count, the budget test may need a minimal raise.
3. Browser pass from the NOT-done list; rebuild APK; ask for "go" and back up the phone before installing.
