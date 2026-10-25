# Handoff: user rulings 2026-10-25 afternoon (branch feat/rulings, worktree ~/Desktop/rokid/pca-v095g)

From the v0.9.5 tip incl. the catch-up diagnostics (33590ffe). No PROTOCOL_VERSION, TRACE_SCHEMA, Room schema, plan, hash change (Default capture f5782e0d8610 and the pinned plan bytes are untouched). `CanvasAssetsTest.jsUnderSizeBudget` stays 4632: no JS line was added (item 1 changed one condition in place; item 2 changed one existing line in `views.js` in place). New settings live in SharedPreferences (`capture_timing`) and the existing `canvas_meta` row `scene_rebuilds_per_day`. Every log line added is a literal.

## 1. Palette shows `input.moments`
- Built: the palette rule in `editorlogic.js` `paletteGroups` (`t.liveable === false && executor !== 'PYTHON' && type !== 'input.scenes'` hides) now also lets `input.moments` through. It still is not liveable and not publishable; it sits in the Source family next to Scenes; Photo (`palette:false`) and Aggregate etc. are unchanged. No Kotlin change: `NodeTypes` already had `palette = true` for Moments.
- Files: `phone/src/main/assets/canvas/editorlogic.js`, `phone/src/test/js/editorlogic.test.js` (test renamed `paletteOffersTheMomentsAndScenesInputsUnderSource`).
- Dependents found: only the palette rule (grep `input.scenes` in the JS: `views.js` run-page routing and `editorlogic.js`); no graph needed.
- Mutation: the old rule restored makes the test fail (`[{"type":"input.scenes",...}] !== [... moments, scenes]`). RED before the change was seen the same way.

## 2. A/B arms use their OWN Photo settings
- Built: `CanvasBackendImpl.estimateAb/startAb` call `scoped(arm pipeline, frameIds)` per arm; the intersection helper `scopedAb` is gone. `CanvasRunner.estimateAb(a, b, idsA, idsB, labels)` and `startAb(spec, idsA, idsB, token, scope)` take one frame list per arm (the old single-list overloads stay and pass the same list twice, so the other callers and tests are unchanged). Run A is run over `idsA`, run B over `idsB`; a frame an arm's filter excludes never reaches that arm's model.
- Estimate: `Estimate.Ok.arms` = per-arm `ArmEstimate(photos, nominal, max, models, tokens)`; the JSON of `POST /api/runs/estimate-ab` carries `arms` (A first); the top-level `photos` counts the DISTINCT frames of both arms, `nominal/max/models/tokens` are the sums as before. Limits: 50 photos PER ARM, 200 nominal calls in total. The confirm sheet (`views.js` `confirmStart`) shows "Arm A: n photos ... | Arm B: ..." (one line edited in place).
- Typed errors: estimate refuses `["arm A: no photos"]` / `["arm B: no photos"]` (and `arm X: more than 50 photos`). Start: an empty arm is `CanvasRunner.AbArmEmpty(arm)` (HTTP 422 `ab_arm_empty`); an arm whose scoped set is not the one the estimate priced is `AbArmMoved(arm)` (HTTP 409 `ab_arm_moved`, message names the arm); the token is spent in both cases. A token for other pipelines is still `bad_token`. The token binds `a:<id>` / `b:<id>` per arm.
- Both modes work: identical Photo settings give identical frames (tested); different filters give each arm its own set (tested).
- Files: `services/orchestration/.../canvas/CanvasRunner.kt`, `phone/.../canvas/api/{CanvasBackendImpl,CanvasApi}.kt`, `phone/src/main/assets/canvas/views.js`; tests `CanvasBackendImplTest` (3 A/B tests rewritten), `CanvasRunnerTest` (2 adjusted, 1 added). The `harden-b` handoff got a SUPERSEDED note.
- Dependents: grep `estimateAb|startAb`: `CanvasRunner`, `CanvasBackend(Impl)`, `CanvasApi`, `views.js`, and tests in memory (`HelperDeletion*Test`), orchestration (`PyFnRunHarness`, `CanvasRunnerTest`) which use the single-list overloads.
- Mutation: arm B scoped with arm A's pipeline (`scoped(pa...)` twice): `CanvasBackendImplTest` fails 3 tests (`expected:<[2, 3]> but was:<[2, 2]>`, `expected:<[1, 2]> but was:<[1, 1]>`).

## 3. Export vs delete: one `DataOpGate`
- Built: `orchestration/DataOpGate.kt` (`DataOpGate.shared`). `export { }` = shared side, try-acquire, throws `DataOpGate.DeleteRunning` ("busy: a delete is running") while any delete runs OR waits (a waiting delete never starves). `delete { }` = exclusive side: waits for a running export, bounded 30 s, then throws `DataOpGate.ExportInProgress` ("export in progress, try again") without running the block. Deletes do not exclude each other (they never did) and nest.
- Wired: export = `MemoryExport.write` (the internal DAO overload, so tests can inject a gate). Deletes = `ForgetActions.run` (Forget fact, Forget photo; typed `ForgetActions.Outcome { DONE, EXPORT_IN_PROGRESS, FAILED }` via the new `forgetFactOutcome/forgetMomentOutcome`; the Boolean forms are `== DONE`), `MemoryWipe.deleteAll` (throws `ExportInProgress`, nothing deleted), `RetentionWorker` (the exception ends in the existing `Result.retry()`), `OutboxDrainer.drain` (gate wait fails: rows stay pending, `Report.NONE`, one literal log line, the next trigger retries), `CanvasForgetCoordinator.forgetRange`. `MemorySettings.busy` shows "A delete is running - try again" / "Export in progress - try again".
- Subsumes? Mostly. The old `publishLock` + generation check in `MemoryExport` is KEPT as the cheap defensive check, because a memory edit (`MemoryDetailRoute.onTextEdited`) calls `deleteCached` and is not a gated delete; its existing race test still passes. The file-level lock was never separate.
- Tests: `DataOpGateTest` (5 deterministic interleavings with deferreds), `DataOpGateIntegrationTest` (real entry points: Forget during export fails typed and forgets nothing; Forget waits then runs; export during Forget refused with no file; Delete all during export fails typed; retention retries), `OutboxDrainerTest` (+1).
- Mutation: export ignores the gate AND Forget ignores the gate: `DataOpGateIntegrationTest` fails `anExportDuringAForgetIsRefusedTypedAndWritesNothing` and `aForgetDuringAnExportFailsTypedAndForgetsNothing` (`expected:<EXPORT_IN_PROGRESS> but was:<DONE>`); the "waits" test then hangs (its loop is bounded by a 10 s timeout since).
- NOT gated (decision): `ConfigExport` / diagnostics export (settings and logs, no memory text), canvas run deletion and pipeline deletes (not user memory), startup sweeps (`FrameSweep`).

## 4. Watchdog settings
- Built: `GlassesWatchdog` takes `silentMs: () -> Long = { SILENT_MS }` and `periodMs: () -> Long = { TICK_MS }`; the silence threshold is read at every tick, the period before every sleep (and for the late-tick warning). `start(scope, periodMs: Long? = null)` keeps the fixed-period test form. Off while an explicit pause is active: unchanged (`captureActive`).
- Settings: `CaptureTimingStore` (prefs `capture_timing`): `watchdog_silent_min` presets 2/3/5/10 default 3; `watchdog_tick_sec` presets 60/150/300 default 150. A value that is no preset falls back to the default. UI: Settings > Capture > "Glasses watchdog", two choice rows (tags `watchdog_silent`, `watchdog_tick`). `HubGraph` passes `CaptureTimingStore.get(app)::silentMs / ::tickMs`.
- Backup: `capture_timing.xml` is excluded in `backup_rules.xml` and both sections of `data_extraction_rules.xml` and listed in `BackupRulesTest` (per-install, like `ai_call_pace`).
- Tests: `GlassesWatchdogTest` (the whole old suite is unchanged and runs on the defaults; new: defaults are the old constants and act at exactly 3 min, a changed threshold applies on the next tick, a changed tick applies on the next sleep, pause still silences it), `CaptureSettingsTest` (store, VM, rows).
- Mutation: threshold ignored (`SILENT_MS` used): `aChangedThresholdAppliesOnTheNextTick` fails; tick ignored (`TICK_MS` used): `aChangedTickAppliesOnTheNextSleep...` fails.

## 5. Scene/Forget settings
- (a) Photo Forget radius: `memory/forget/ForgetRadius.kt` is the ONE accessor (`ForgetRadius.ms()`, provider bound in `ContextApp` to `CaptureTimingStore.forgetRadiusMs()`, presets 5/10/15/30 s, default 10 s = `ForgetService.PHOTO_LINK_MS` which is kept as the default constant, a non-preset value falls back to 10 s). Used once, in `ForgetService.dropCanvasOutputs`; grep found `PHOTO_LINK_MS` ONLY there (every photo Forget, a retention photo expiry and `MomentForget` per-photo cascades go through it). Pref key `forget_radius_sec`. Row "Photo Forget radius" (tag `forget_radius`).
- (b) Scene cadence: `SceneCadence.DEFAULT_PER_DAY` 96 -> 24; `canvas_meta` key `scene_rebuilds_per_day` unchanged, so every stored value (including an explicit 96) is respected; only an UNSET key reads 24 now. Presets `SceneCadence.PRESETS_PER_DAY` 12/24/48/96 (a custom stored value stays selectable). New `storedPerDay/storePerDay` companion helpers, `SceneRebuildControl` / `StoredSceneRebuilds` in the phone (async, reads `canvas_meta`). Row "Scene rebuilds per day" (tag `scene_rebuilds`) with the note "More often = more compute." (the DerivePass cadence is a different key and untouched: 4 a day, OFF).
- (c) Activity definition unchanged (STILL, WALKING, VEHICLE): no code touched.
- Tests: `ForgetRadiusTest` (default 10 s keeps m12/m20 of marks 3/7/12/20 s away; 5 s, 15 s, 30 s; bad values), `SceneCadenceTest` (default 24, stored 96 respected, junk falls back), `DerivePassTest` (line adjusted), `CaptureSettingsTest`.
- Mutations: radius ignored (`PHOTO_LINK_MS` used): `ForgetRadiusTest` smaller/larger/30 s fail; default still 96: `SceneCadenceTest.theDefaultIsHourly...` and `DerivePassTest` fail.

## 6. Recorded
`docs/superpowers/specs/2026-10-25-itemref-forget-design.md` section 7 "Rulings 2026-10-25 afternoon" and the decision list at the end of `2026-10-25-v094-status.md`. No range-Forget screen was built.

## Final run
Clean `:services:memory`, `:services:orchestration`, `:phone` unit tests: all green after the `BackupRulesTest` fix above (phone 1892 tests, the one failure was the missing backup ruling, rerun alone green). `:glasses` is untouched, so not run.

## Settings keys and defaults
| Setting | Store / key | Presets | Default |
|---|---|---|---|
| Watchdog silence | prefs `capture_timing` / `watchdog_silent_min` | 2, 3, 5, 10 min | 3 |
| Watchdog tick | `watchdog_tick_sec` | 60, 150, 300 s | 150 |
| Photo Forget radius | `forget_radius_sec` | 5, 10, 15, 30 s | 10 |
| Scene rebuilds per day | `canvas_meta` `scene_rebuilds_per_day` | 12, 24, 48, 96 | 24 (was 96) |

## Decisions for the human
- The Forget UI (`MemoryDetailViewModel`) still takes a Boolean, so an "export in progress" Forget shows the existing "Couldn't forget that - try again". The typed outcome exists in `ForgetActions` (`Outcome.EXPORT_IN_PROGRESS`); say if the dialog should name the reason (a small VM change).
- Outbox drain during an export defers silently (rows are kept); it does not surface an error.
- Existing installs that never stored the Scene cadence move from 96 to 24 a day on upgrade (the ruling's intent); set 96 in Settings to keep the old rate.

## NOT done
- No range-Forget screen; the two range-Forget implementations are not reconciled (recorded as a ruling).
- No glasses change, no install, no Codex review.
- `ConfigExport` / diagnostics export are not gated (see item 3).

## Merge steps
In the integration worktree: `git merge --no-ff feat/rulings`. Likely conflicts: `CaptureSettings.kt` (state fields, VM constructor, rows), `SettingsDeps.kt`, `SettingsDestinations.kt` (the `CaptureSettingsViewModel(` call), `ContextApp.kt`, `HubGraph.kt` (the watchdog constructor), `CanvasRunner.kt`/`CanvasBackendImpl.kt` if harden-b was merged earlier (the intersection `scopedAb` is removed here), `ForgetActions.kt`, the tests of those. Then run :services:memory, :services:orchestration, :phone clean tests.
