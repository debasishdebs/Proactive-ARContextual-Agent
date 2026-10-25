# Handoff: WATCH lane, tunable Process node, frequency preview, watch starter (v0.9.4), 2026-10-25

Branch `feat/watch-lane` (worktree ~/Desktop/rokid/pca-v094f), from v0.9.4 tip 692afcd1. Conservative form: nothing acts. No PROTOCOL_VERSION, TRACE_SCHEMA, Room schema, PlanCompiler, glasses or watch code change, no new permission, no new endpoint, no execution change for an existing node (only descriptors of two existing nodes grew, see Type changes).

## Commits
- 84419b85  1. WATCH lane + `src.watch_motion` (+ hub ring, watch run path in the runner)
- 350d6b6e  2. `proc.motion_model`
- 8e3f56c5  3. `out.set_frequency_preview`
- a0955cba  4. built-in `starter-watch-motion`
- 6f2815cf  fix: `WatchMotionRingTest` first used JUnit imports, which core:model does not have on its test classpath (it did not compile; my first "green" claim for it was wrong, found by the final clean run). Now `kotlin.test`.
- (this file) handoff

## Built
1. **WATCH lane.** `Domain.WATCH` (first in the enum), served as `domain` by `/api/node-types`. `src.watch_motion` ("Motion on watch"): family Source, root, no params, output new `PortType.WATCH`, `sinceSchema = 2`, no model call. It reads the watch motion the phone hub already holds: new `WatchMotionRing` (core:model, in memory only, 120 values, oldest drops first, emptied when the `watch_livefit` source is switched off; fed in `ContextHub.onEvidence` after the "is the source running" check, so a stopped source adds nothing). `WatchMotion.items` (services:ai) makes one run item per value: `tsMs`, `state`, `confidence`, `score` 0..1, `sensor.watchMotion` (= score). `score` = per-state level x confidence (STILL/ASLEEP 0, IN_VEHICLE 0.2, WALKING 0.5, CYCLING 0.7, EXERCISE 0.8, RUNNING 1.0); STILL is a REAL 0, UNKNOWN has no score and is left out. An empty window gives ONE item that is the error `NO_DATA` (never a zero). `agg.window` now accepts WATCH input and has the feature `watchMotion`.
   Runner path (the only way a watch pipeline runs): `CanvasRunner.estimateWatch(p, fromMs, toMs, labels)` / `startWatch(...)` with a `WatchRunSource` (fun interface; production = the hub's ring through `CanvasBackendImpl.create`), same token/lock/fence shape as Scene runs. The token binds the window id and a binding of every key + score, so a value that arrived after the estimate refuses the start (`WatchMoved`, nothing runs). Cap 50 values per run (MAX_PHOTOS), refused beyond. Photo/Moment/Scene runs and A/B refuse a watch pipeline.
   Drawn: lanes strip, palette lane colour and merged view get a "Watch" lane band placed BEFORE glasses (`LANES` in flowadapter.js edited in place; colour `--watch` in both themes, CSS only for chip, node-card border and palette border).
   Validator: one new cross-domain edge (WATCH source -> ASYNC node, "the ping to the phone"); the watch source counts as the async half's one input root (a second root, Photo/Moments/Scenes + watch, is refused with a fixed text); every other WATCH crossing still says "crosses domains".
2. **`proc.motion_model`** ("Motion model", Process, domain ASYNC = the phone lane as for Summary). Input NUMBER (an `agg.window` item: `value`) or WATCH (`score`); output NUMBER. Params (strict schema, defaults in the descriptor): `kind` enum `heuristic` (only value; documented as the slot for a learned model), `threshold` 0..1000 default 0.3 (moving when number >= threshold), `hysteresis` 0..1000 default 0 (off; once moving, stays moving until number < threshold - hysteresis; must not exceed threshold, validator text "hysteresis must not exceed threshold"). Pure and deterministic; ONE state per run starting `still`, items decided in run order (one chunk); an item with no number is `BAD_INPUT` and leaves the state. Output item: `label` (`moving`|`still`), `value` (1.0|0.0, for `gate.compare`), `score` (the number), `kind`, `threshold`, `exit`. `route.switch` reads `label` (its input set gained NUMBER). Expanded composite card (descriptor-driven, no JS): `Number in -> At least the threshold? -> Already moving? ... -> moving | still`, with `kind`, `threshold`, `hysteresis` as editable boxes and two note lines.
3. **`out.set_frequency_preview`** ("Set frequency (preview)", Outcome, domain ASYNC, input NUMBER, output PREVIEW). Params: `row` (REST|VEHICLE_PASSENGER|VEHICLE_DRIVING|FLIGHT, default REST), `movingCapPerMin` 1..4 default 4, `stillCapPerMin` 1..4 default 2 (the capture table takes 1..4). Per item it reads `label` and outputs `{preview:true, applied:false, label, targetRow, field:"capPerMin", value, today, change: raise|lower|same|set, facts:[one sentence "... Preview only, nothing applied."]}`. `today` comes from `ContextPolicies.DEFAULT` (REST 2, FLIGHT 1, the vehicle rows null = unchanged). `facts` is a string list, which the run page already shows as bullets (no JS). It never publishes: `PlanShape.tier3Nodes` (the one guard every publish/compile/shadow route calls) now also lists `out.set_frequency_preview` and every node of domain WATCH, so the fixed S6 refusal answers; PlanCompiler is untouched and never sees it.
4. **`starter-watch-motion`** ("Starter: watch motion to capture rate"): `watch (src.watch_motion) -> win (agg.window, watchMotion, max, last 5) -> model (defaults) -> freq (defaults)` + the Default capture nodes and wires exactly as `defaultCapture()`. Seeded by `ensureBuiltIns` after the others, read-only, clonable, validator-clean, never active, can never publish, not an interpreter. Listed last in `DefaultPipelines.all`.

## Type changes (each tested)
- `Domain` += WATCH; `PortType` += WATCH (enums only; names are served; the JS reads `domain` generically).
- `agg.window`: inputs += WATCH, `FEATURES` += `watchMotion` (existing tests `WindowAggregateTest` updated to the new lists; behaviour of the old features unchanged).
- `route.switch`: inputs += NUMBER (descriptor only; `SwitchExecutor` untouched). Only reachable after a NUMBER producer, which no photo path has, so the interpreter shape rules are unaffected.
- `Executors.all()` 9 -> 11 (`MotionModelExecutor`, `SetFrequencyPreviewExecutor`); `ExecutorsTest` updated. Built-ins 5 -> 6 (`CanvasRepositoryTest`, `DefaultPipelinesTest`, `StarterMomentSummaryTest` list).
- `CompositeInnerTest` and `CanvasBackendImplTest` pin the set of types that serve an `inner`: `proc.motion_model` added.

## Files
- core:model: `WatchMotionRing.kt` (new: `WatchSample`, `WatchMotionScore`, `WatchMotionRing`).
- services:ai: `WatchMotion.kt`, `MotionModel.kt`, `SetFrequencyPreview.kt` (new); `NodeTypes.kt`, `PipelineValidator.kt`, `ParamSchema.kt` (one cross rule), `WindowAggregate.kt`, `DefaultPipelines.kt`, `plan/PlanShape.kt`.
- services:orchestration: `WatchRunSource.kt` (new), `CanvasRunner.kt` (watch run), `Executors.kt`, `CanvasRepository.kt` (seed).
- phone: `ContextHub.kt` (ring), `CanvasBackendImpl.kt` (one param to the runner), assets `flowadapter.js` (in place), `style.css`.
- Tests (new): `WatchMotionRingTest`, `WatchSourceTest`, `MotionModelTest`, `SetFrequencyPreviewTest`, `StarterWatchMotionTest`, `WatchRunTest`, `MotionModelRunTest`, `StarterWatchRunTest`; edited: `NodeFamiliesTest`, `WindowAggregateTest`, `CompositeInnerTest`, `DefaultPipelinesTest`, `StarterMomentSummaryTest`, `GlassesCompositeTest` (pin), `ExecutorsTest`, `CanvasRepositoryTest`, `ContextHubTest`, `CanvasBackendImplTest`, `CanvasPlanPublishApiTest`, JS `fixtures.js`, `flowadapter.test.js`, `mergedflow.test.js`.

## Dependents found
`graphify affected "ContextHub" --depth 2` (v0.9.3 graph): `HubGraph.build`, `ContextHubTest`, `WatchSourceTest`, `ImageIntentSourceTest`, the six `*Source.start()` callers; none needed a change (the ring is additive), all run in the final :phone run. For the node registry: `PlanShape`, `PipelineValidator`, `InterpretableShape`, `PlanCompiler` (verified untouched), `CanvasBackendImpl` (`roleOf` returns null for the new nodes: no model, so estimates show no model label), `CanvasRunner`.

## Goldens
Pinned and unchanged: pipeline hashes live-extract 0f331f401992, intent-draft df5b4cbe2fa3, default-capture 5f4b63ac85c4, starter-capture-extract 1557fc30f2ff; plan hash f5782e0d8610 of default-capture, starter-capture-extract, starter-moment-summary AND the new starter (its plan config equals the identity plan and Default capture's); compiled plan bytes sha of the five old built-ins unchanged (`GlassesCompositeTest`), new starter pinned f5782e0d8610:28c9cf15a753d921 (differs from default-capture's bytes only by the pipeline id/name, as for the other starters). `CanvasRepositoryTest` asserts the stored JSON of every earlier built-in equals the code's encoding after the new one is seeded.

## Tests
Final clean run, one Gradle, `--continue`: :core:model 229, :services:ai 729, :services:orchestration 1705, :phone 1851 tests, 0 failures, 0 skipped, no flaky rerun needed. (Earlier in the session a "green" for core:model was wrong: the build had failed at compileTestKotlin and I read only the exit of a tail command; the final run is the evidence.)

## Mutations (each proven red, then restored from a byte copy)
- Validator: watch->ASYNC edge removed (`watchToAggregateToCompareIsValid`, and the four runs through the real runner); tier-3 guard without WATCH (`anyGraphHoldingTheWatchNeverPublishes`); `items` turns a no-score value into 0.0 (`anUnknownValueIsNotAMeasurement...`); NO_DATA item no longer an error (`anEmptyWindowIsOneNoDataItem...`); ring not cleared when the source stops (`theHubKeepsTheRecentWatchValues...`); JS `LANES` without WATCH (`aWatchNodeGetsTheWatchLane...`, `aWatchPipelineAddsTheWatchLane...`).
- Ring: STILL level 0.1, no bound, NaN confidence allowed (`aStillWatchIsARealZero...`, `theOldestDropsWhenFull`).
- Motion model: hysteresis ignored (`hysteresisKeeps...`, `aMissingNumber...`, the run test); watch value read from `value` instead of `score` (`theNumberIsTheWatchScore...`); cross rule removed (`validatedRanges`); Switch without NUMBER (`theSwitchReadsItsLabel...`, chain validation).
- Preview: moving/still values swapped, `applied` true, MAX_CAP 9, tier-3 entry removed (4 tests in `SetFrequencyPreviewTest` and the run test).
- Starter: a glasses param tuned (threshold 0.7) and the win->model edge dropped: `GlassesCompositeTest` pins, `StarterWatchMotionTest` (3 tests).
- NOTE on RED order: the tests were written with or right after the code in each commit, not run red before it (the first run of each file was green); the evidence is the mutations above, as in the earlier handoffs.

## JS lines and budget
`CanvasAssetsTest.jsUnderSizeBudget` stays 4628: net 0 JS lines. `LANES` (one line) and the `where` map in `bindingsOf` (one line) were edited in place, plus one comment line reworded. The palette, inspector, composite card, run view and preview all come from the served descriptors. CSS: +5 lines (a `--watch` colour per theme and 3 border rules).

## Decisions taken (please review)
1. **Lanes = domains, WATCH is a real `Domain`.** The brief says proc.motion_model is "lane PHONE"; the phone-side nodes that exist (Summary, agg.window) are ASYNC (labelled "Async (phone)"), and RT_PHONE needs the hub root and signals, so the Process and the Outcome are ASYNC. The lane strip therefore reads Watch / Glasses / Phone (real time) / Async (phone) for the starter.
2. **A run over watch values needs a window and a confirm token like Scene runs; there is no HTTP route or page for it yet** (see NOT done). The runner API is tested through the real runner with a fake source and with the real ring.
3. **One item per value, not one per window**, so `agg.window` (trailing window over items) works unchanged; the window aggregate of a starter run therefore sees at most 5 values.
4. **Score table is a fixed description** (not a tunable): the tuning lives in the model's threshold and hysteresis. Change `WatchMotionScore.LEVEL` if you want other levels.
5. **Unpublishable through the existing tier-3 guard**, so the refusal text is the S6 one ("generated or laptop-run code never publishes"), which is not literally true for these nodes. A dedicated text would change a pinned fixed text; say if you want one.
6. **Hysteresis cannot exceed the threshold** (it would mean a negative exit level), validated.
7. **`gate.compare` works on `value` 1.0/0.0**, `route.switch` on `label`; both are tested through a run. `route.switch` got NUMBER in its inputs for this.
8. **The preview's default values**: moving 4, still 2 (still = REST today, so "same" is the baseline). They are previews and are not tied to the policy defaults beyond the `today` field.
9. **Ring size 120, memory only**, cleared when the watch source is switched off (a privacy-leaning choice); no persistence, so a phone restart empties it.

## Conservative stand-ins
- The glasses are NEVER touched: the preview is a run result; "Set frequency" is not applied anywhere.
- No ML model: `kind` has the single value `heuristic`.
- The "ping phone" (Send/Receive signal) step of the spec example is not modelled as nodes: the WATCH-to-ASYNC edge IS the hand-over (the watch values are already on the phone).
- Watch values come only from what the hub accepted (rate-limited by `WatchMotionIntake`, MIN_GAP); no new data from the watch.

## NOT done
- Anything only a real browser shows: the Watch lane band and its colour (light and dark) in the editor and the merged view, the palette entry colour, the amber expandable "Motion model" card (three editable boxes, note lines), the starter drawn with four lanes (layout columns for a 14-node, four-lane graph), the run result card of a frequency preview (facts bullets) and of a NO_DATA root.
- HTTP route + New run page entry to start a watch run (estimate/start with a window), the run view label for `watch:` item ids (it shows an item that is not a photo, Moment or Scene with a photo thumbnail request: needs a one-line JS change where `moment:`/`scene:` are listed), the run list "kind".
- Any real watch data: nothing here was run against a LiveFit watch or device; the ring is fed in tests through `hub.accept`. No install, no Codex review, no LIVE/TRIAL.
- A dedicated refusal text; a Send/Receive "ping" node pair for the watch; a rolling window over TIME; persistence of the ring; the learned model kind.

## Merge steps
1. In ~/Desktop/rokid/pca-int1 (v0.9.4 line): `git merge --no-ff feat/watch-lane`. Shared files likely to conflict: `NodeTypes.kt` (new constants, types, the Switch/agg inputs), `DefaultPipelines.kt` (`all` list, appended builder), `Executors.kt` (the `all()` list and its count 11), `CanvasRepository.kt` (`built`), `CanvasRunner.kt` (constructor, `execute`), `PlanShape.kt`, `PipelineValidator.kt`, `CanvasBackendImplTest.kt` (the pinned inner set), `flowadapter.js` (the `LANES` line); reconcile executor and built-in counts with other branches, and re-check `jsUnderSizeBudget` (this branch adds 0 lines).
2. From android-companion, one Gradle: `./gradlew --no-daemon -Dorg.gradle.workers.max=2 --offline :core:model:cleanTest :core:model:test :services:ai:cleanTestDebugUnitTest :services:ai:testDebugUnitTest :services:orchestration:cleanTestDebugUnitTest :services:orchestration:testDebugUnitTest :phone:cleanTestDebugUnitTest :phone:testDebugUnitTest`; rerun known flaky ones alone.
3. Browser pass from the NOT-done list; rebuild the APK; ask for "go" and back up the phone before installing.
