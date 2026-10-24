# Handoff: composite-card spacing fix and merged multi-pipeline view (2026-10-24)

Branch `feat/merged-view` (worktree /Users/debasishkanhar/Desktop/rokid/pca-merged), from v0.9.2. Front end only (android-companion/phone/src/main/assets/canvas). No PROTOCOL_VERSION, Room, publish or S6 change; no new endpoints.

## Commits
- 2590e104  Task 1, bug: the collapsed amber composite card was taller than its layout cell, so its Expand button sat on the next node and hid the edge labels. Now: marker and Expand button share one row (`.comp-row`) inside a card pinned to CARD_H=96px (style.css); CARD_H + MIN_GAP(24) = CELL_H(120). flowadapter.toFlow takes opts.expanded and stacks layers by real height (layer y = max(CELL_H, tallest node + MIN_GAP)), so an expanded composite pushes successors and the pending row down. floweditor keeps the expanded set (UI state, closure `exp`) and passes it through editorlogic.flowFor(ed, nodeTypes, expanded). Node data gains cardW/cardH.
- 9d492b6e  Task 2, feature: merged view. mergedflow.js (pure), mergedview.js (read-only SVG page), views.js (checkbox per pipeline row, "View merged flow" button disabled until one is ticked, "Show with capture" on each row except default-capture and on the pipeline page), app.js route `#/merged?ids=a,b`, style.css (.merged-*, per-pipeline colour classes pl0..pl5). flowadapter exports nodeDomain, titleOf, laneLabel for reuse. CanvasAssetsTest.jsUnderSizeBudget raised 4400 -> 4500 (+100; the JS is now ~4600 lines incl. vendor excluded: 4439 + merged files).
- (third commit) this handoff file.

## Tests (run as `./gradlew --no-daemon -Dorg.gradle.workers.max=2 --offline :phone:cleanTestDebugUnitTest :phone:testDebugUnitTest` from android-companion, one at a time)
- Final full run: 1813 tests, 0 failures, 0 skipped. No flaky failure appeared in the full runs.
- New JS tests: flowadapter.test.js (+5: constants leave room, collapsed chain no overlap and label room, expanded composite pushes successors, collapse restores, pending row below), mergedflow.test.js (13), merged-ui.test.js (6: list checkboxes/buttons, Show with capture, merged page read-only and GET-only, per-pipeline error, empty selection, hostile text). Kotlin: CanvasAssetsTest.collapsedCompositeCardFitsItsLayoutCell; JsEditorTest registers mergedFlowTests and mergedUiTests.
- The GraalVM fake DOM cannot measure layout, so "collapsed card height <= cell" is asserted on constants: CSS height equals CARD_H (parsed from flowadapter.js) and CARD_H + MIN_GAP <= CELL_H. Real rendering is NOT measured by any test.

## Mutations (each proven red, then restored; run against the JsEditorTest/JsViewsTest/CanvasAssetsTest subset)
Task 1: heightOf ignores expansion (expanded === "never") -> flowAdapterTests red; collapsed CSS height 96px -> 130px -> CanvasAssetsTest red.
Task 2: M1 id prefix removed -> 7 failures; M2 lane order reversed -> lanes test red; M3 signal name match replaced by true -> 2 links instead of 1; M4 frames link also to own pipeline -> 3 failures; M5 failed pipelines not filtered out -> 3 failures (crash); M6 empty flag forced false -> red; M7 merged button never disabled -> red; M8 withCapture drops default-capture -> 2 failures.

## Decisions
- Merged view is plain SVG/DOM (dom.js), not React Flow: testable under the fake DOM, no editor machinery, no way to edit. Collapsed amber composites are drawn static (amber, "composite" label); NOT expandable in the merged view.
- Layers are recomputed in mergedflow (longest path over edges), not taken from the server layout, so it works for any pipeline. Inside a lane each pipeline gets its own column band; y = layer * CELL_H.
- Node ids are "<pipelineId>::<nodeId>". Signal links: every Send x every Receive with the same name (also inside one pipeline, flagged cross=false); the unmatched list reuses bindingsOf over the union. Frames links go only to OTHER pipelines (a pipeline's own photos edge is its ordinary edge).
- User-dragged positions: nodes are not draggable in the editor (nodesDraggable=false), positions come from layout cells, so nothing to preserve.

## NOT done
- Expanding a composite inside the merged view; zoom/pan (the view scrolls); merged view of versions (only the draft json from GET /api/pipelines/{id}); an expanded composite is wider than a column (220px) and can overlap neighbours in the same layer (only vertical push is handled); edge routing between lanes is plain curves and can cross nodes; no mobile-specific layout work.

## What a human must check in a real browser (not covered by tests)
1. Pipeline page of Live (has Confidence check): the collapsed amber card sits entirely inside its cell: title, type, then "expandable" marker and Expand button on one row; the next node (Critic) is not covered; the edge labels "trusted" and "unsure" are readable in the gap. Check with a long node title (should ellipsize) and at phone width.
2. Click Expand: inner flow opens, Critic and Save memory move DOWN (no overlap); Collapse puts them back. Double click and Enter still toggle.
3. Pipelines list: a checkbox on every row; "View merged flow" disabled until one is ticked, then opens #/merged?ids=...; "Show with capture" on rows (not on Default capture) opens that pipeline plus Default capture.
4. Merged page: three lane bands (Glasses, Phone, Async), each pipeline in its own colour with its name on a pill on each node; solid accent links for matching Send/Receive signal names; dashed "captured frames" link from Capture image to the Photo (and Moments) nodes of the other pipeline; links list under the picture; a bad id in the URL shows a dashed red chip for that pipeline and still draws the rest; #/merged with no ids says "Select at least one pipeline". Dark mode colours (pill text is white on the pipeline colour).

## Next steps for whoever merges
1. In ~/Desktop/rokid/pca-int1 (on v0.9.2): `git merge --no-ff feat/merged-view`.
2. Run the full tests from android-companion: `./gradlew --no-daemon -Dorg.gradle.workers.max=2 --offline :phone:cleanTestDebugUnitTest :phone:testDebugUnitTest` (rerun alone: PromptEditorTest, LocalModelsPageTest, JsEditorTest, StepSheetTest, CanvasServerTest port tests if flaky).
3. Rebuild the phone APK.
4. Ask the user for "go" before installing on a device.
