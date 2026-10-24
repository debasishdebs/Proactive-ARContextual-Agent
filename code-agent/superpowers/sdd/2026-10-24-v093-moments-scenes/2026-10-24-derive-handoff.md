# Derive step handoff (branch feat/derive, from v0.9.3 tip 9f5622d5)

Fills the SUMMARY and CAPTION of Moments and Scenes so `input.scenes` `text` is no longer empty. Spec: moment-scene-design-decisions sections 2 and 4 item 7.

## Built
- `TextDerivePorts` (SummarizeInput + CaptionInput) registered in the existing `DeriveRegistry` for kinds MOMENT and SCENE. Fixed short prompt, deterministic shape: `<task> this moment|scene, using only the notes below. Reply with the text only.\nNotes:\n- ...`. Reply is whitespace-normalised and capped (summary 280, caption 80 chars); blank or null reply writes nothing.
- `DeriveInputs`: the ONLY text the model sees. Moment: its memories with review state OK plus its marks' names. Scene: per member Moment its FRESH (not hidden) SUMMARY, else that Moment's own visible text. Max 40 lines of 300 chars. No photo bytes, paths, ids, hidden or stale fields.
- `DeriveText` port (prompt in, text out) is the fake-model seam. Production `RouterDeriveText` uses the existing `AiGraph.router` with the user's SUMMARIZE role binding, `CallClass.BACKFILL`, temperature 0.2, maxTokens 160. No new network client.
- `DerivePass`, called from the existing `MomentCadenceWorker` (after the Scene rebuild, errors swallowed): writes only through `DeriveRunner` -> `writeDerivedGuarded` (generation + epoch, parent exists, input revision). Max 10 objects per run (a model call per missing field, SUMMARY and CAPTION), Scenes first then Moments, newest first (look-back 7 days for Scenes, 24 h for Moments, scan 200). Objects with no readable input are skipped without a call. The first model exception ends the pass; fields stay missing; the next DUE run retries. Rejected (obsolete) answers write nothing. PcLog gets counts only.
- `DeriveGate` (review fixes): before EVERY model call the stored switch is re-read and `admit()` is asked (production: SUMMARIZE bound AND `KeyframeGraph.admitBackgroundCall()`: Battery Saver / low battery, validated network, power policy and the shared one-call-per-30-s allowance). The first refusal stops the pass; a pass that never got a permit does not mark the run done (stays due). After the call the switch is read again: if it went OFF meanwhile nothing is written.
- Inputs of one object are read in ONE transaction together with its input revision (`DeriveInputs.snapshot`); immediately before the model call the fence is re-checked and the snapshot is re-read: if anything moved (Forget, review change, Delete all) the call is dropped, never made.
- `DeriveCadence` in `canvas_meta`: `derive_enabled` (default OFF), `derive_per_day` (default 4, clamp 1..96), `derive_last_run_ms`. Separate from the Scene build cadence (`scene_rebuilds_per_day`, 96).

## Files
- `services/orchestration/src/main/java/com/rokid/context/canvas/moment/DerivePass.kt` (new, everything above)
- `.../MomentCadenceWorker.kt` (one call + kdoc)
- `services/orchestration/src/test/java/com/rokid/context/canvas/moment/DerivePassTest.kt` (8 tests)
No schema, PROTOCOL_VERSION or TRACE_SCHEMA change; no DAO change.

## Tests and mutations (each turned a test red, then restored)
- (earlier) reader filter `reviewState == OK` removed -> hiddenAndStaleFieldsAreNeverFedToThePrompt
- kill switch check removed -> theCadenceIsOffByDefault...
- `objects >= maxObjects` removed -> aRunIsBoundedByObjects
- `!admit()` removed -> notAdmittedSkipsAndStaysDue
- `isDue` always true -> theCadenceIsOffByDefault...
- default per day 4 -> 96 -> theCadenceIsOffByDefault...
- recheck before the call removed -> aForgetBetween...StopsTheCall; `stillOn` check removed -> theKillSwitchIsReRead...; one permit per pass instead of per call -> everyModelCallNeedsItsOwnAdmission...
- rejected not counted (`else -> {}`) -> aPhotoForgotten... and aStaleInputRevisionIsRefused
- Moment `builtRev = req.inputSeq` -> 99 -> sceneTextBecomesNonEmpty...
Note: the Scene builtRev is forced to the input revision inside `writeDerivedGuardedTx`, so that mutation is only observable on Moments. A mutation of the guard itself (generation, input revision, parent) belongs to the existing DerivedGuardTest; here the forgotten-photo and changed-parts tests prove the pass goes through it.

## Decisions I made
- Scene input falls back to member Moments' own visible text when they have no fresh summary (a Moment exists per second, so deriving every member first would burn the budget; Scenes first is what fills the empty text).
- Derive is OFF by default and uses `CallClass.BACKFILL` (lowest budget class).
- A mark's text is its label name.
- First failure ends the pass (model likely down) instead of burning calls.

## NOT done
- EmbedInput stays unimplemented (no embedding model for text yet); the registry has no embedder for MOMENT/SCENE, so EMBEDDING is never written.
- No settings UI; no warning text for the cadence (spec 4.7 asks for a warning when the user raises it; do this with the settings screen).
- No catch-up for Moments older than 24 h / Scenes older than 7 days.
- Not exercised against a real model or on a device.

## How to enable for a test
Set the stored values in `canvas_meta` (key/value table) of the app database, then let the 15-minute `MomentCadenceWorker` run:
`derive_enabled` = `1`, and optionally `derive_per_day` = `96` (every worker tick). In code: `DeriveCadence(db).setEnabled(true); setPerDay(96)`. A SUMMARIZE role binding must exist and the device must pass the background gates (not Battery Saver, battery above 30% or charging, validated network). Reset `derive_last_run_ms` to force a run now.

## Merge steps
Branch feat/derive is based on 9f5622d5 (v0.9.3 tip); merge into the next train branch (no conflicts expected: only the worker file is shared). Run `:services:ai`, `:services:orchestration`, `:phone` clean tests after merge.
