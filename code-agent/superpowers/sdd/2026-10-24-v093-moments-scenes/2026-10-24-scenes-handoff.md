# Scenes (v0.9.3 scene layer) handoff, 2026-10-24

Branch `feat/scenes` (worktree `~/Desktop/rokid/pca-scenes`), from v0.9.2 tip 2fa8833d. Spec: `docs/superpowers/specs/2026-10-24-moment-scene-design-decisions.md` sections 2, 4, 5, 6 item 3.

## What is built
1. **Room v11, additive** (`10.json` untouched, `11.json` generated): tables `scene` and `scene_part`. `SceneEntity` is a `MomentObject` of kind SCENE (id, buildRev, hourStartMs, startMs, endMs, boundaryRule, startTrigger, endTrigger, activity, membershipRev, builtAt); `scene_part` holds references (`MOMENT`, `AUDIO` and `SENSOR` reserved) with partRev and partTimeMs. `MIGRATION_10_11` is 6 CREATE ... IF NOT EXISTS statements. Both tables are in `DeletionGraph` (WIPED) and `WipeDao`; Delete all empties them.
   - **Forget shrink:** `MomentDao.remove(ids, step = "forget")` (the one path every Moment removal takes: Forget, source cleanup, builder) now calls `DerivedStaleDao.shrinkScenesHolding` in the same transaction: part removed, Scene window and `membershipRev` updated, audit PART_REMOVED (+ FORGOTTEN when step is `forget`), then INVALIDATED + DERIVED_STALE/HIDDEN per field (`markStaleCore(force = true)`). A Scene left with no part is deleted with its audit and derived rows. `MomentDao` now extends `DerivedStaleDao`.
   - Audit rows stay content-free (closed op/step lists, `refKind` MOMENT or FIELD, short ids only).
2. **SceneBuilder** (`services/orchestration/.../canvas/moment/`): `SceneConfig` (all thresholds: hash fraction 0.25 = 16 of 64 bits, cosine 0.75, cap 5 min, activity states STILL/WALKING/VEHICLE), `SceneCutter` (pure, deterministic, order independent), `SceneSource` port + `DaoSceneSource` (stored `moment` rows, `frame_meta` phash/motion, optional `frame_vector` embedding), `SceneBuilder.rebuildWindow` (whole clock hours, one transaction, idempotent, audit BUILT/REBUILT/PART_ADDED/PART_REMOVED/INVALIDATED), `SceneCadence` (stored `canvas_meta` keys `scene_rebuilds_per_day` default 96, clamp 1..96, and `scene_last_rebuild_ms`), `SceneTriggers`, hooked into `MomentCadenceWorker` after the Moment rebuild. Precedence of cuts: hour, then activity change (when both sides logged one) or frame change, then the 5-minute cap. Scene id = `S` + first Moment id.
3. **`input.scenes`**: `NodeTypes.INPUT_SCENES` (ASYNC, Inputs, root, `PortType.SCENES`, not liveable, palette true), `PipelineValidator` (one input root across photos/moments/scenes; new message only when a scenes root is involved, the moments message is unchanged), `PlanShape.tier3Nodes` (never published; PlanCompiler untouched), `SceneInputExecutor` (stored Scenes overlapping a window, oldest first, max 500, with ids, window, rule, activity, triggers, membershipRev, Moment refs; derived fields only through `ObjectAudit.readDerived`, so never stale/hidden; `toJson`), palette entry in `editorlogic.js` (net 0 asset lines; `CanvasAssetsTest.jsUnderSizeBudget` stays at 4500, not raised).
4. **Forget hides the Scene summary at once**: `SceneInputExecutorTest.forgettingOnePhotoHidesTheSceneSummaryAtOnceAndNoReaderSeesIt` (real `MomentForget`), plus `SceneBuilderTest.aForgottenMomentShrinksTheSceneAndARebuildDoesNotBringItBack` and the shrink tests in `SceneStoreTest`.

## Commits (oldest first)
d7e8ad6b (Room v11 and shrink) then the SceneCutter, SceneBuilder, cadence, input.scenes registry, executor and palette commits; see `git log feat/scenes`.

## Mutations done (each turned a test red, then restored with git checkout)
- Shrink without hiding derived fields -> `SceneStoreTest` (2 red). Remove `scenes()`/`sceneParts()` from the wipe -> `DeletionGraphWipeTest`. FORGOTTEN written for builder removals too -> `aBuilderRemovalIsPartRemovedButNotForgotten`. Drop one migration index -> 5 `MigrationV10to11Test` red.
- Cutter: hash `>` to `>=`, cap `>=` to `>`, hour rule removed, cosine `<` to `>`, activity mode also checking frames: each red in `SceneCutterTest`.
- Builder: always rewrite (idempotence), no stale on content change, membershipRev always +1, vanished Scenes not removed, builder removal using the forget step: each red in `SceneBuilderTest`.
- Cadence: worker hook removed, interval check removed, default 96 changed (this one survived until the test asserted the literal 96; fixed): `SceneCadenceTest`.
- Node: tier-3 entry removed, input root filter without scenes, `liveable = true`: `SceneNodeTypesTest`.
- Executor: stale rows readable, window overlap changed to start-in-window, shrink without hiding: `SceneInputExecutorTest`.
- Palette filter reverted: `JsEditorTest` (editorLogicTests).
- RED for new API was a compile failure (unresolved symbols) at the start of each slice; for behaviours added to existing code (worker hook, palette) it was a real assertion failure.

## Decisions I made on my own
- **Table names `scene`, `scene_part`**; the inventory test regexes now also scan the `scene` prefix.
- **Which rule is recorded:** the rule that ENDED the Scene; the last Scene of an hour window is `HOUR_END` (also for the still-open current hour; a later rebuild changes it).
- **"Activity logged"** = frame motion in {STILL, WALKING, VEHICLE}; HEAD_MOVING and UNKNOWN count as not logged. A Moment's activity is the dominant one of its photos. When only one of two neighbouring Moments has an activity, the frame rule decides (activity mode needs both sides). The spec said "built-in motion classifier states"; the choice of the set is mine (`SceneConfig.activityStates`).
- Frame comparison is between NEIGHBOURS (last photo of one Moment against first photo of the next), not against the Scene start. Embedding is used only when both vectors exist and have the same size; a missing hash with no embedding means no cut.
- **Cap:** a Scene covers at most 300 seconds (the 301st second cuts).
- **Scene id** `S`+first Moment id: a Moment added before the first one gives the Scene a new id (old one removed with its derived fields). Stable ids would be a later decision.
- `membershipRev` bumps only when the member SET changes (and on a Forget shrink); a member whose content changed rebuilds and hides derived fields without bumping it.
- Cadence default **96/day** (every worker tick), because the build is cheap pure DB work; the costly derive step will need its own cadence. Stored in `canvas_meta` (KEPT table, no user data) to avoid a new prefs file in the deletion inventory.
- **Forget of one photo also removes everything within 10 s of it** (existing `PHOTO_LINK_MS` rule), so Scenes shrink by more than one Moment in that case.
- Activity on a surviving Scene is not recomputed on a Forget shrink (it is a motion label, not photo content); the next rebuild corrects it.
- `MomentBuilder` removal now passes step `build` (audit says PART_REMOVED without FORGOTTEN).
- Palette: only `input.scenes` was added to the unliveable types the JS offers. **Finding:** `input.moments` is registered `palette = true` but `paletteGroups` hides every unliveable non-Python type, so `input.moments` does NOT appear in the palette today. I did not change that (not asked).
- Two existing tests were adjusted for v11: `MigrationV9to10Test` (additive scan now ends at `MIGRATION_10_11`), `MomentRunTest` (version 11, scene tables empty), `DatabaseFactoryUpgradeTest` (version 11).

## NOT done
- No run machinery for scenes: nothing accepts `PortType.SCENES` (not even py.fn), no `/api/scenes` route, no run page. The executor is the read API only.
- Start/end trigger fields exist but nothing writes them (no head-flip/dwell source). The separate Scene `sensor` field is not added.
- No derive (summary/caption/embedding) implementations, so Scene derived fields are only written by tests; no rebuild-cadence settings UI or warning; no audio part kind.
- SQLCipher/androidTest migration test not written or run (`SqlCipherMigrationDeviceTest` untouched).
- Thresholds untuned (starting values only); no replay/tuner hookup.
- `SceneBuilder` rebuilds only the previous and current hour on the cadence; no backfill of older hours.
- No device install, no push, no Codex review run.

## Merge steps
1. `git merge feat/scenes` into v0.9.2 (or the v0.9.3 branch); no conflicts expected apart from `ContextDatabase.kt`/`DatabaseFactory.kt` if another branch also claims v11 (then add a NEW migration, do not edit `MIGRATION_10_11`, regenerate the schema).
2. Run `:services:memory`, `:services:ai`, `:services:orchestration`, `:phone` tests (clean). Known flaky: LocalModelsPageTest (passed alone).
3. Bump versionName/tag as per the version train; ask the user for "go" and take a phone backup before any install (Room v11 migrates the on-device DB).

## Codex fixes (three Important defects), after the first handoff
1. **Member change hides Scene derived fields.** `DerivedStaleDao.markStaleCore` for a MOMENT now also marks every Scene holding it stale (INVALIDATED, DERIVED_STALE/HIDDEN) in the same transaction, so a mark undo/correct/retag, a memory edit and a Moment rebuild that changes the Moment all reach the Scene before any Scene rebuild. A Scene's derived row now records the input revision it was built from (`ObjectDao.writeDerivedGuardedTx` stores `builtRev = expectedSeq` for kind SCENE) and `SceneInputExecutor` returns a field only if it is fresh AND `builtRev == inputSeq(scene)`.
2. **Input revision monotonic across recreation.** `purgeScenes` keeps the highest audit seq of the deleted Scenes in `canvas_meta` key `scene_seq_floor` (a counter, no user data); `appendAuditRow` gives a SCENE a seq above it. Test: `SceneBuilderTest.aSceneRecreatedUnderTheSameIdNeverReusesAnInputRevisionOfItsEarlierLife` (exactly the SA/SX/X/B scenario; the old derive ends as STALE_INPUT).
3. **One transaction for the executor read** (`db.withTransaction`: Scenes, parts, input revision and derived fields). Test interleaves a real `MomentForget` via the `testBetweenReads` seam; the answer is either the whole old state or the whole new state.
Mutations (each red, restored): moment hook removed, floor removed, transaction removed, revision check removed. Test seeds that wrote derived fields with a fixed builtRev now use the Scene's current input revision (`put` helper). Full runs: memory, ai, orchestration, phone green; HistoricalBackfillTest and PromptEditorTest failed once in the full run and passed alone (flaky).
