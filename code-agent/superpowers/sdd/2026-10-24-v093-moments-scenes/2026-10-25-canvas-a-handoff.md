# Handoff: canvas-a (Photo settings, palette by families, starter pipeline), 2026-10-25

Branch `feat/canvas-a` (worktree ~/Desktop/rokid/pca-canvas1), from v0.9.3 tip e9c0dc4b. No PROTOCOL_VERSION, TRACE_SCHEMA, Room schema, publish or S6 change; no new endpoint; no new file in the canvas web assets.

## Commits
- e3537df7  1. Photo node source and filter settings
- 2b76e1af  2. Palette by the five families, "Next step" wording
- 4daffffa  3. Starter pipeline with both halves
- (this file) handoff

## Built
1. **Photo settings.** `input.photos` has four params (new `PhotoSettings.kt` in services/ai): `source` glasses|all (default glasses), `sinceHours` int 0..720 (default 0 = any time), `markedOnly` bool (false), `limit` int 1..50 (50 = the runner's own cap). Defaults are today's behaviour, and an absent param means the default, so nothing is written into saved pipelines: golden test pins the hashes of `live-extract` (0f331f401992, unchanged) and `intent-draft` (df5b4cbe2fa3) and the Default capture plan hash f5782e0d8610. The schema is not strict (a saved pipeline with other keys still validates). The validator takes only the listed enum and ranges (ParamCheck, existing). The inspector needs no JS change: it is generated from the served descriptors (`/api/node-types` now carries the four params). Effect: `CanvasBackendImpl.scoped(p, frameIds)` runs in `estimate` and `startRun` (so the confirm token binds the same list); `PhotoScope.apply` (pure) keeps the chosen frames that pass the settings, `limit` keeps the newest N. At the defaults the ids pass untouched and nothing is read from the DB.
2. **Palette by families.** `Family` enum (Source, Aggregate, Process, Condition, Outcome) and `NodeType.family`, served as `family` next to the unchanged `category`. Mapping is the families-doc table; `NodeFamiliesTest` lists every type, so a new type must be added there. `paletteGroups` returns the five groups in flow order always; an empty one (Aggregate today) has `soon: true` and the palette prints "Coming soon" (text, no hidden node); a type without family goes under "Other". Each item carries its `lane`; the palette shows the lane as a coloured left bar (same colours as the node cards), a tooltip "Runs on: ...", and a lane legend above the groups. The inspector's "Connect to" / "No valid target" is now "Next step" / "No next step needed" (constants `NEXT_STEP_LABEL`, `NO_NEXT_STEP` in editorlogic.js). Nothing in saved pipelines is renamed or removed.
3. **Starter.** `DefaultPipelines.starter()`, id `starter-capture-extract`, name "Starter: capture and extract": the Default capture nodes and wires unchanged + the live extraction nodes and wires unchanged + the edge `cam -> photos` (the one existing binding between the lanes). Seeded read-only by `ensureBuiltIns`, listed in Pipelines, never active by existing, clone = editable draft. Compiles to the identity plan (hash f5782e0d8610), async half = live extraction, no tier-3 node.

## Files
- services/ai: `PhotoSettings.kt` (new), `NodeTypes.kt` (Family, family per type, photo schema), `DefaultPipelines.kt` (STARTER, starter()).
- services/orchestration: `CanvasRepository.kt` (seeds the starter).
- phone: `CanvasBackendImpl.kt` (scoped, serves family), assets `editorlogic.js`, `floweditor.js`, `style.css`.
- Tests: `PhotoSettingsTest`, `NodeFamiliesTest` (new), `DefaultPipelinesTest`, `CanvasRepositoryTest`, `CanvasBackendImplTest`, `CanvasAssetsTest.inspectorAndPaletteUseTheFamilyWording`, JS `editorlogic.test.js`, `fixtures.js` (FAMILY map, photoParams), `mergedflow.test.js`.
- CanvasAssetsTest.jsUnderSizeBudget NOT raised (4550; the JS is 4507 lines by wc, about 4543 by the test's count).

## Tests
Final run (one Gradle, clean): :services:ai 644, :services:orchestration 1631, :phone 1822 tests, 0 failures, 0 skipped. In an intermediate full phone run LocalModelsPageTest and PromptEditorTest failed once; both passed alone (known flaky). `HelperUiContractTest` scans editorlogic.js for the text `hint: t.hint`; kept.

## Mutations (each proven red, then restored)
1. Photo: since boundary `>=` -> `>` (sinceHoursDropsOlderFrames); markedOnly ignored (markedOnlyKeepsMarkedFrames); limit max 51 (onlyTheListedValuesAreAccepted); default source `all` (5 tests); no defaults short-circuit in PhotoScope.apply (defaultsKeepEveryChosenId...). Backend: `scoped` no-op (2 backend tests); start not scoped (aRunStartedOnAScopedPipeline...); mark match by frame id removed (photoSettingsNarrow...). RED before code: PhotoSettingsTest (stub returning defaults), NodeFamiliesTest (default family), starter tests (stub = liveExtract).
2. Families: JS grouping by `category` instead of `family`; soon never set; no Other bucket; NO_NEXT_STEP back to "No valid target"; lane dropped from items; floweditor old wording (CanvasAssetsTest); served family constant (CanvasBackendImplTest); local_critic family Process (NodeFamiliesTest).
3. Starter: join edge removed; a glasses param tuned inside the starter (plan hash and node-equality tests red); starter not seeded (CanvasRepositoryTest).

## Decisions taken (please review)
1. **The starter's phone half is the live extraction, NOT "Moments -> Prompt -> Summary -> Confidence check -> Save memory".** That chain cannot pass PipelineValidator on today's registry: Moments output is accepted only by `py.fn` (MomentNodeTypesTest.noOtherNodeAcceptsMoments), Prompt outputs JSON but Confidence check takes only EXTRACTION, there is no Summary node (the families doc lists it as a gap), and Moments are tier-3 (never publishable). The closest valid chain with all five node roles is Photo -> Facts from frames -> Confidence check -> Critic -> Save memory, joined to the capture lane by capture image -> Photo. Doing the exact chain needs registry work (Moments/Scenes -> Prompt, a Summary node, Prompt -> Confidence check) that was out of scope.
2. `source = all` currently selects the same frames as `glasses`: the phone store only holds `rokid*` frames (non-rokid are purged) and the loader (`PipelineRecords.existing`) drops others. The setting and the filter are real and tested; the extra reach appears when another photo source is stored.
3. "Marked" = a mark (canvas_label) names the frame, or was made within 10 s of its capture (`PhotoScope.MARK_LINK_MS`, the Scenes forget radius). Marks are read with `labels()` (small table).
4. Settings apply to runs started from the New run page (estimate, start), not to A/B runs (`startAb`, left unscoped) and not to the live path (live frames are glasses captures by construction).
5. Estimate and start each read the clock for `sinceHours`; a frame crossing the boundary between the two calls changes the bound list and the start answers `bad_token` (re-estimate). Safe failure, rare.
6. Palette: `category` is still served and unchanged; only the grouping moved to `family`. Photo remains `palette = false` (one per pipeline). Moments visibility decision from the status note is untouched.
7. The default of an unknown family is PROCESS in the Kotlin constructor; NodeFamiliesTest forces an explicit entry for every type.

## NOT done
- The exact "Moments -> Prompt -> Summary -> Confidence check -> Save memory" starter (see decision 1); a Summary node; Aggregate nodes (3c E2).
- A/B scoping, a UI note on the New run page saying a Photo node filter will narrow the choice (the estimate shows the narrowed count; nothing says why).
- Anything a real browser must show (no test measures layout or colour): palette with five groups, the "Coming soon" line under Aggregate, the lane bar colours on palette buttons and the legend (dark mode, phone width), tooltip "Runs on: ...", the inspector label "Next step" and the option "No next step needed" on the last node, the Photo node's four fields in the inspector (select, three number/checkbox controls, error text beside a bad value), the starter row in the Pipelines list (read-only, Duplicate works), its picture with three lane bands (single editor view and `#/merged?ids=starter-capture-extract`), and the "Show with capture" button on its row (it adds Default capture a second time; harmless, not changed).
- No device install, no Codex review run on these diffs.

## Merge steps
1. In ~/Desktop/rokid/pca-int1 (on v0.9.3): `git merge --no-ff feat/canvas-a` (only files above; no conflict expected with the sensor/derive work: DefaultPipelines, NodeTypes, CanvasRepository, CanvasBackendImpl, editorlogic.js and floweditor.js are the shared files, check them).
2. From android-companion, one Gradle at a time: `./gradlew --no-daemon -Dorg.gradle.workers.max=2 --offline :services:ai:cleanTestDebugUnitTest :services:ai:testDebugUnitTest :services:orchestration:cleanTestDebugUnitTest :services:orchestration:testDebugUnitTest :phone:cleanTestDebugUnitTest :phone:testDebugUnitTest` (rerun flaky tests alone).
3. Rebuild the phone APK; ask the user for "go" and take the phone backup before installing.
4. Browser pass from the NOT-done list above.
