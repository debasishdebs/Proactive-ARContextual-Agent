# Tasks 8 and 9 report (slice 3a, stream B, branch s3a/b)

Status: DONE. Commits: c01a25c5 (Task 8), 08140f3c (Task 9 data), 2a243b6e (Task 9 backup).

## Schema (Room v7, identityHash 419d8cb94d4a35afcd392852e07fc341)
- 7 nullable columns on canvas_publish: groupId, component, planJson, configHash, gen, state, trialEndsAt.
- 9 new tables: canvas_trace (+2 idx), canvas_trace_event (+1), canvas_replay (+2), canvas_sweep (+1), canvas_label (+2), canvas_meta, canvas_calibration (+1), canvas_group_state, canvas_helper_job (+1).
- MIGRATION_6_7 is additive (ALTER ADD COLUMN ... DEFAULT NULL, CREATE ... IF NOT EXISTS), statements copied from the KSP export, registered in build and buildDeferred. 1..6.json and MIGRATION_1_2..5_6 are untouched.
- No other branch has a 7.json (checked all local branches; s2/int is v6). Renumbering procedure is in the MIGRATION_6_7 KDoc (S64: never rename or edit; rebase, write a new latest->next migration, regenerate schema, rerun all upgrade paths).
- Names and shapes not fixed by the brief (round 1 Task 7 text was not available): replayClass (EXACT|COUNTERFACTUAL), fromMs/toMs source envelope on replay and sweep, tombstoned/revision/parentTraceId/installId/deletionCounter/exact on canvas_trace. Later tasks should read the entities.

## Fenced DAO (CanvasSlice3Dao, DaoFence, DeletionEpoch, TraceSources)
insertLabelFenced (also drops a mark with atMs < wipedAtMs), bumpLabelRev inside every label write/delete/correct, acceptTraceFenced (epoch match, tombstone), appendEventsFenced, tombstoneTrace (stub row for unseen ids), commitReplayFenced / commitSweepFenced (generation, persisted epoch, tombstone, source revision, labelRev, still RUNNING and not invalidated), invalidateBySources, markRunningAborted, group state, calibration, helper jobs.

## Deletion coverage
- Delete all: new epoch committed first (DeletionEpochStore.commitWipe, throws if not committed, nothing deleted then); WipeDao wipes trace, trace_event, replay, sweep, label, calibration, helper_job; folders canvas_traces, canvas_import, canvas_tmp.
- Kept by design (no user data), pinned by a classification test (everyCanvasTableIsClassifiedForDeleteAll fails for any unclassified future table): canvas_meta (labelRev bumped, monotonic) and canvas_group_state (S62 recovery record).
- Forget and photo retention (ForgetService.dropCanvasOutputs): delete the photo's labels, bump labelRev, invalidate dependent results, void helper jobs carrying it.
- Start-up (ContextApp thread, CanvasStartup): sweep canvas_tmp, abort RUNNING replay/sweep jobs.
- Backup: phone backup_rules.xml and both data_extraction sections exclude prefs deletion_epoch, canvas_plan_state, canvas_collect, mark_settings and folders canvas_traces, canvas_import, canvas_tmp; BackupRulesTest classifies them, checks all 3 sections, checks the 9 tables are in 7.json (context.db is excluded with -wal/-shm), pins names to the code, and that none is in ConfigExport.PREFS_FILES. Glasses: capture_plan, mark_queue, imu_trace, imu_trace_tmp named on top of the blanket excludes (blanket kept and still asserted).

## Tests (all green at the end)
memory :testDebugUnitTest 417/0 failed; orchestration canvas.* + MemoryWipeTest + DeleteAllFenceTest 254/0; phone canvas.* + BackupRulesTest green; glasses GlassesBackupRulesTest green; compileDebugAndroidTestKotlin memory and phone green. No device tests, no adb.
New: Migration6To7Test (5), DatabaseFactoryUpgradeTest v1/v3/v5/v6 -> v7 through both builders, CanvasSlice3DaoTest (26), DeletionEpochStoreTest (6), FencedFilesTest (7), DeleteAllEpochTest (5), WipeDaoTest/ForgetCanvasTest additions, backup tests. SqlCipherMigrationDeviceTest extended to v7 (shapeAs drops the v7 shape, v6 fixture added, integrity_check), COMPILE-ONLY; MigrationFixtureGuard untouched.

## RED evidence
Real: DatabaseFactoryUpgradeTest (7 vs 6 assertion) before the bump; 13 CanvasSlice3DaoTest assertion failures against stubbed fenced writes.
Mutation (each reverted): tombstone check removed -> replayCommitBlockedByTombstone (+ sweep test) fail; trialEndsAt ALTER removed -> Migration6To7Test schema validation fails; wipe of labels / Forget hook removed -> wipe and 2 Forget tests fail; epoch commit moved after deletes -> epochCommittedBeforeDelete fails; fence ignored -> fencedPublishSkipsAfterWipe fails; sweep no-op -> tmpSweptAtStartup fails; XML exclusions removed -> phone (3) and glasses (2) backup tests fail.
Caveat: the epoch store, wipe/forget hooks, FencedFiles and backup XML were implemented in the same step as their tests (no stub-first run); their REDs are the mutations above.

## Concerns
1. canvas_group_state and canvas_meta survive Delete all on purpose; flip is a one-line change in WipeDao plus the test lists if the controller disagrees.
2. Forget of a TIME RANGE, trace Forget and retention of traces/events are Task 34 (the DAO primitives deleteLabelsBetween, tombstoneTrace, invalidateBySources exist). Only photo Forget/retention are wired here.
3. Phone prefs canvas_plan_state, canvas_collect, mark_settings and the glasses prefs/folders are ruled in the XML ahead of the tasks that create them; if those tasks pick other file names the XML and tests must follow.
4. Encrypted device suite (Task 10) was only compiled, never run; the controller must run it and the migration rehearsal (Task 38) before installing.
5. commitWipe failing aborts Delete all with IOException (fail closed); the Settings caller already propagates failures.

# Fix round (review task-8-9-review.md)
Commit: see `git log` tip ("fix(db): v7 review fixes ..."). v7 was still unmerged and uninstalled, so MIGRATION_6_7 and 7.json were regenerated (still additive; 1..6.json and MIGRATION_1_2..5_6 untouched; identity 419d8cb9... -> e4d08479d8fe8aa33ed957ed423c9b5d).

Schema diff vs the previous 7.json: canvas_replay +configHash (+index_canvas_replay_configHash); canvas_sweep +configHash (+index_canvas_sweep_configHash); canvas_group_state +mode, +phoneInstall, +sentAtMs (all nullable; S62 matching tuple is gen, publishId, mode, configHash, phoneInstall; sentAtMs is the SENT time for the 10 min UNKNOWN_REMOTE timer).

Fixed: I1; M2 (idempotent duplicate mark, no labelRev bump); M3; M6 (both [from,to]; null-range results invalidated by any range Forget); M9 (wipedAtMs is the plain commit clock, the counter is the real fence, documented); M10 (decode null, commits refuse unreadable stored sourcesJson); M11 documented (memory has no kotlinx.serialization dependency; org.json is the platform parser, already used by ForgetService); M13 (concurrent Delete all vs commits, installId fallback, real-DaoFence publish test, literal asserts and constant test dropped); M15 and the M8 KDoc name. `component` is `capture_plan` in core; states DESIRED, PREPARED, SENT, EFFECTIVE, FAILED, UNKNOWN_REMOTE, REVERTED; modes SHADOW, TRIAL, LIVE.
New API: commitReplayFenced/commitSweepFenced(id, resultJson, configHash, sources, expectedLabelRev, fence, now); validExactReplays(configHash).
Not covered: a commit failing AFTER a successful move cannot be injected in a JVM test; the code deletes the orphan final file in that path.
Tests (all green): memory full, orchestration canvas.* + MemoryWipeTest + DeleteAllFenceTest, phone canvas.* + BackupRulesTest, glasses GlassesBackupRulesTest, compileDebugAndroidTestKotlin memory and phone.

## Task 31 proposal (not implemented): labelId and provenance
labelId = "<src>:<installId>:<epochCounter>:<seq>" (src = tap|voice|phone|retro; seq is the per-source mark seq from MarkMessage), so a re-sent mark is idempotent (the DAO now ignores a duplicate id without a labelRev bump). provenance = "listen:<listenId>" for the listen join (UNDO deletes the label whose provenance matches), "" otherwise. atMs must be phone wall time mapped from atElapsedMs via the S42 clock offset, never the raw glasses wall time. deletionCounter should be the glasses' known counter; note the entity default 0 vs the wire default -1: map -1 to 0 or the current counter explicitly.

## Carry to later tasks (from the review's "For the controller")
- Tasks 19 and 22: send the persisted epoch (TraceControl, installId + deletionCounter) on every glasses onReady, not only during Collect, or the glasses keep IMU traces and the mark queue after a Delete all.
- Task 30: clear the mark queue on a new epoch BEFORE re-sending queued marks on onReady. Task 31: store phone-mapped time only; the DAO deliberately does not check a mark's deletionCounter (S57).
- Task 21: when a commit returns false (invalidated or fenced) the runner must set CANCELLED; the DAO leaves the row RUNNING until the next start marks it ABORTED. Tasks 24 and 27: compare a row's labelRev with the current one at read time, do not rely only on the invalidated flag (a new mark does not eagerly invalidate DONE results).
- Task 34: pass the overlapped trace ids as well as the range; photo Forget over-invalidates by labelRev (safe direction). Start-up sweep and abort are not limited to pre-process-start items (safe direction, loses data only).
- Task 18/19: no file path is stored on canvas_trace: pin the traceId to file name rule under canvas_traces and canvas_import; optionally sweep files with no row at start-up.
- Settings UI must show the IOException from commitWipe as a Delete all failure. Task 10/38: run the encrypted suite and rehearsal on a DB copy. canvas_meta is kept by Delete all: only labelRev may live there (pin allowed keys if another key is added).
