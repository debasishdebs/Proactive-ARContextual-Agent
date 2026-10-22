# Tasks 8 and 9 review: Room v7 migration A, fenced DAOs, deletion epoch, FencedFiles, backup (c01a25c5, 08140f3c, 2a243b6e; base f66682d4)

Read-only review. No Gradle, no adb, no edits outside this file. The diff (`review-f66682d4..2a243b6e.diff`) was read in two passes: first 6.json and 7.json through a schema script, then the code and tests. Outside the diff, I ran one focused check per named risk, listed under "Checks run".

## Verdicts
- **Task 8:** spec compliant **no** (one schema gap against S60, given "one migration with every table"); quality **Needs fixes** (that one Important).
- **Task 9:** spec compliant **yes**; quality **Approved** (Minor findings only).

### Spec Compliance

- ❌ **Task 8, schema completeness against S60** (Important I1). `canvas_replay` and `canvas_sweep` have no `configHash` column (`CanvasSlice3Entities.kt:3803-3835` in the diff; 7.json).
  - S60 says: "Every piece of evidence (replays, sweeps, trial counts, calibration rows, acks) is keyed by `configHash`."
  - Task 27 (CORE) gate 3 needs "an exact-hash replay".
  - The Round 3 core row for 8+9 says "**one migration with every table** (no second migration later)".
- ✅ **Everything else in the briefs is built.**
  - **MIGRATION_6_7 is purely additive and exactly matches 7.json** (script over 6.json and 7.json, then over the migration source):
    - No entity was removed, and no existing column or index changed. The only change to an existing table is `canvas_publish`, which gets 7 nullable columns appended at the end (`groupId`, `component`, `planJson`, `configHash`, `gen`, `state`, `trialEndsAt`).
    - 9 tables are added. Every `CREATE TABLE`/`CREATE INDEX` in the migration equals 7.json's `createSql` with `${TABLE_NAME}` substituted: the script found 0 missing and 0 extra.
    - Every NOT NULL column in the new tables either has a default or is set by every writer. The `ALTER ... ADD COLUMN` statements are all nullable (`DEFAULT NULL`, the same pattern as MIGRATION_5_6), so ADD COLUMN is legal.
    - Views are unchanged.
  - **PK, index names and defaults match the entities:**
    - `canvas_trace_event` PK is (traceId, seq).
    - `canvas_calibration` has an autoGenerate `id`.
    - The index names are `index_canvas_<table>_<col>` throughout, and `Migration6To7Test.newTablesExistEmptyWithTheirIndices` asserts all 10.
  - **Identity hash:** 7.json `419d8cb94d4a35afcd392852e07fc341` equals the hash in the KSP output `services/memory/build/generated/ksp/debug/.../ContextDatabase_Impl.java:181,1040`, which was generated at 13:33, after c01a25c5 at 13:32. 6.json's `226a473a…` is unchanged.
  - **History untouched:**
    - `git diff --stat` under `schemas/` shows only 7.json added.
    - The `ContextDatabase.kt` hunk removes only the `version = 6` line, and MIGRATION_1_2..5_6 are byte-identical.
    - MigrationFixtureGuard is not in the diff.
  - **Both builders:** MIGRATION_6_7 is registered once each in `build` and `buildDeferred` (`DatabaseFactory.kt:4023,4034` in the diff).
  - **Chains:**
    - `DatabaseFactoryUpgradeTest` covers v1, v3, v5 and v6 to v7 through both builders.
    - `Migration6To7Test.migratesFromEachCanonicalSchema` covers 1..6 to 7 with `runMigrationsAndValidate`.
    - `SqlCipherMigrationDeviceTest` is extended: `dropV7`, `shapeAs(6)`, a v6 seed and `integrity_check`. It is compile-only, as the report says.
  - **Renumbering (S64):**
    - No branch other than `s3a/b` has a 7.json. ctx/int is at v5, s2/int and s3a/int at v6.
    - The KDoc on MIGRATION_6_7 states the S64 procedure.
  - **Fenced DAO set:** all present.
    - `bumpLabelRev` runs inside every label write, delete, correction and photo-Forget.
    - `invalidateBySources(traceIds, fromMs, toMs, labelRevChanged)` is there, and so is `markRunningAborted`.
    - The named tests (`labelWriteBumpsLabelRev`, `replayCommitBlockedWhenLabelRevMoved`, `startupAbortsRunningJobs`, `groupStateRoundTrip`, `replayCommitBlockedByTombstone`) are all present. The tombstone check sits in `sourcesHold` (`!it.tombstoned`), so the mutation RED is real.
  - **Task 9:**
    - `DeletionEpochStore` uses prefs `deletion_epoch`, with `installId` (a UUID), `counter` and `wipedAtMs`, and synchronous `commit()`.
    - `commitWipe` is the first statement of `MemoryWipe.deleteAll`, before the generation bump and any delete (`MemoryWipe.kt:5031` in the diff). It throws `IOException` on failure, so it fails closed.
    - `WipeDao` deletes the 7 tables, and Delete all removes the 3 folders.
    - `FencedFiles.publish`/`sweepTmp` and `CanvasStartup` are there.
    - The phone XML has 4 prefs and 3 folders in `backup_rules.xml` and in both data-extraction sections. The glasses XML has `capture_plan`, `mark_queue`, `imu_trace` and `imu_trace_tmp` in all 3 sections, and the blanket excludes are kept.
    - All named tests are present.
- ⚠️ **Cannot verify from this diff (for the controller):**
  - **Glasses-side deletion after Delete all:**
    - The new epoch reaches the glasses only through `TraceControl` (Tasks 5, 16, 19, 30).
    - Nothing in 3a so far pushes `TraceControl` on link-up after a Delete all when no collection is running. Until it does, the glasses keep their IMU traces and mark queue.
    - Tasks 19 and 22 should send the persisted epoch on every `onReady`, not only during Collect.
  - **Mark time domain (Task 31):**
    - `insertLabelFenced` compares `label.atMs` with the phone's `wipedAtMs` (`System.currentTimeMillis()` at the wipe). The fence is correct only if Task 31 stores the mark's mapped **phone** wall time, through the S42 clock offset from `atElapsedMs`, and never the raw glasses wall `atMs`.
    - Task 30 must clear the queue on a new epoch **before** re-sending queued marks on `onReady`. Otherwise a pre-wipe mark from glasses whose clock is ahead can pass the time fence.
    - The DAO deliberately does not check `deletionCounter`, which is right per S57: a post-wipe mark from glasses that have not yet learned the epoch is legitimate.
  - **Delete all failure path:** the Settings caller must show the `IOException` from `commitWipe` as a failure. I did not trace the UI.
  - **Encrypted suite:** `SqlCipherMigrationDeviceTest` was only compiled. The controller must run it, together with the encrypted migration rehearsal on a DB copy (Task 38), before installing on the user's phone.
  - **Task 21:** on a `false` commit (invalidated or fenced), the job runner must set CANCELLED. The DAO leaves an invalidated job RUNNING, and only the next start marks it ABORTED.

### Strengths
- **The fence ordering has no gap between check and write.**
  - `DaoFence.holds()` reads the in-process generation and the persisted epoch inside the Room `@Transaction`, which holds the single writer connection.
  - Delete all commits the epoch first (`MemoryWipe.kt:5031` in the diff), then bumps the generation, then runs `wipeMemory` in its own transaction.
  - So a commit either serialises before the wipe, and the wipe deletes it, or sees the new counter.
  - The app has one process (no `android:process` in any manifest), so the SharedPreferences cache is coherent.
- **Delete all is covered completely and pinned.**
  - `everyCanvasTableIsClassifiedForDeleteAll` (`WipeDaoTest.kt:4833-4838` in the diff) compares the set of real `canvas\_%` tables to the wiped and kept lists, so it really fails for an unclassified table.
  - `wipeClearsSlice3TablesAndKeepsTheRecoveryRecord` asserts "seeded X = 1" for every wiped table before the wipe. A table added to the wiped list without a seed therefore fails too, so the test is not vacuous.
- **The kept tables hold no user-derived data.**
  - `canvas_group_state` holds groupId, gen, publishId, configHash, component, state and updatedAt.
  - `canvas_meta` holds the `labelRev` counter, which is bumped by the wipe so it stays monotonic.
  - Keeping both is acceptable on privacy grounds. The only signal they leave is the number of label edits and the publish times.
  - `canvas_calibration` is wiped, as the spec says ("kept unless Delete all").
- **Photo Forget and retention reach the new tables.** `dropCanvasOutputs` is called on every path: `forgetMoment`, `forgetFacts` with lockFrames, `expire` via `cascade`, and `expirePhotos` (ForgetService lines 60, 65, 115, 140). Each path:
  - deletes the photo's labels;
  - bumps `labelRev`;
  - invalidates the results;
  - voids the helper jobs, dropping their frame ids and lease.

  `retentionPhotoExpiryReachesTheSameTables` tests this.
- **`FencedFiles` is sound.**
  - The path guard uses `canonicalFile` with a separator-suffixed prefix, so `..`, symlinks and a sibling such as `canvas_traces2` are refused (tested).
  - The tmp prefix is whitelisted.
  - The fence is proven to run inside the transaction (`theFenceRunsInsideTheDatabaseTransaction`).
  - `ATOMIC_MOVE` is safe at minSdk 29.
  - Every failure path deletes the tmp file.
- **`epochCommittedBeforeDelete` is a real crash model.**
  - A seam throws after the commit. A fresh store then sees `counter + 1`, and the label and the trace file still exist.
  - Moving the commit after the deletes would fail it.
- **The migration tests start from real exported schemas.**
  - `noOtherTableChanged` pins column order for every pre-existing table.
  - The v6 seed proves that slice 2 rows survive.
- **Privacy:**
  - The only new log line is `ContextApp`'s, and it logs only the exception class name.
  - `canvas_label` has no text column, and a test asserts it.
  - `TraceSources.needle` cannot match a prefix of another id (tested with `t1` vs `t10`).

### Issues

#### Critical (Must Fix)
None.

#### Important (Should Fix)
1. **I1, `canvas_replay` and `canvas_sweep` lack `configHash`** (`CanvasSlice3Entities.kt:3803-3835` and `ContextDatabase.kt:3991,3994` in the diff).
   - **What's wrong:** S60 keys every piece of evidence (replays and sweeps by name) by `configHash`. Task 27 gate 3 must find "an exact-hash replay over ≥ 30 min of COMPLETE traces" for the plan being published.
   - **Today's workaround:** with only `paramsJson`, gate 3 would need an `instr()` scan or a JSON parse of every row, and Task 24's "Use these params" has no indexed link from a sweep point to its config.
   - **Why it matters:** the Round 3 ruling is one migration with every table. Adding the column after merge means a v8, against that ruling.
   - **Fix:** add `configHash TEXT` (nullable) and `Index("configHash")` to both entities while v7 is still unmerged and uninstalled. Then regenerate 7.json, update MIGRATION_6_7 from the export and extend `noOtherTableChanged`.
   - **S64 does not block this:** v7 has not reached `s3a/int` or a device. The controller must confirm that no APK with this v7 was installed.

#### Minor (Nice to Have)
1. **`canvas_group_state` does not hold the full S62 matching tuple** (`CanvasSlice3Entities.kt:3880-3889`). This gap is plan-mandated: the brief lists exactly these columns.
   - **What's missing:** `mode` and `phoneInstall` are absent, and there is no send time or deadline for the 10-min UNKNOWN_REMOTE timer.
   - **Workaround:** both can be derived, through `publishId` → `canvas_publish.mode` and the constant epoch `installId`, with `updatedAt` serving as the SENT time.
   - **Action:** Task 26 should confirm before 7.json is final. Otherwise add `mode TEXT` and `sentAt INTEGER` now, while it is cheap.
2. **`canvas_label` stores only `atMs`** (`CanvasSlice3Entities.kt:3841-3855`).
   - **What's missing:** there is no window and no mark identity or listen-join column. In core the window is fixed at [t − 10 s, t] (S85), so it can be derived.
   - **What Task 31 must still define:**
     - `labelId` as a stable key (for example `<src>:<installId>:<epoch>:<seq>`) for idempotency;
     - `provenance` holding the listen join, for "UNDO deletes this listen's mark".
   - **Later:** LA15's dragged retro windows would need columns, and a v8.
   - **Duplicates bump `labelRev`:** `insertLabel` is REPLACE, so a re-sent duplicate mark bumps `labelRev` again and refuses every in-flight replay commit. Use IGNORE plus an existence check in `insertLabelFenced`.
   - **Default mismatch:** the entity defaults `deletionCounter` to 0, while the wire `MarkMessage.deletionCounter` defaults to −1.
3. **`FencedFiles.publish` commits the transaction when the move fails** (`FencedFiles.kt:4948` in the diff).
   - **Move fails:** the `IOException` is caught inside `withTransaction` and `false` is returned, so a row written by the fence lambda (the natural way to make the row and the file atomic in Tasks 18 and 19) is committed without its file.
   - **Commit fails after the move:** the reverse also happens. If the commit fails after `Files.move` succeeded, the file sits in `canvas_traces` with no row. A range Forget, which finds traces by row, then misses it until Delete all.
   - **Fix:** throw inside the transaction on a move failure so it rolls back. Document that the fence may write the row. Optionally, at start-up, sweep files in `canvas_traces` and `canvas_import` that have no row.
4. **Start-up races** (`ContextApp.kt:178-181`, `CanvasStartup.kt`).
   - `sweepTmp` and `markRunningAborted` run on a background thread concurrently with app start. A tmp file being written, or a job created in this process before the thread runs, would be deleted or aborted. The direction is safe: it loses data and leaks none.
   - **Fix:** restrict both to items created before the process start time (tmp `lastModified`, job `createdAt`).
5. **Invalidation semantics are uneven** (`CanvasSlice3Dao.kt:3728-3733`).
   - **Over-reach:** a photo Forget or a retention expiry of any labelled photo invalidates **every** result not computed under the new `labelRev`, including results unrelated to that photo. The direction is safe.
   - **No eager invalidation:** a new mark (`insertLabelFenced`) does not invalidate DONE results at all.
   - **Consequence:** Tasks 24 and 27 must compare a row's `labelRev` with the current one at read time, and not rely only on the `invalidated` flag.
6. **Range Forget and range invalidation disagree on bounds.**
   - `deleteLabelsBetween` is half-open on `atMs` [from, to), while the range invalidation is inclusive.
   - Rows with a null `fromMs`/`toMs` escape the range invalidation (as tested). Task 34 must therefore also pass the ids of the overlapped traces.
7. **`canvas_meta` is a generic key/value table kept by Delete all.** A later task that stores any user-derived key there would silently survive a wipe. Have the wipe delete every key except `labelRev`, or pin the allowed keys in `WipeDaoTest`.
8. **The classification test only scans `canvas\_%`.**
   - A slice 3 table with another prefix (`frame_detection` in 3c, or `moment_label` if P2 changes) escapes it.
   - The entity KDoc says "SchemaTest pins the classification", but the test is in `WipeDaoTest` (`CanvasSlice3Entities.kt:3756`).
9. **`wipedAtMs = max(now, previous)`** (`DeletionEpochStore.kt:4132`). One wipe taken while the phone clock was far in the future drops every later mark until real time catches up. This is fail-safe, but nothing surfaces it. A count of dropped marks in Diagnostics would help.
10. **`TraceSources.decode` turns corrupt JSON into an empty list** (`DeletionEpoch.kt:4088-4091`). A later caller that decodes the row's own `sourcesJson` for `sourcesHold` would treat it as "no sources", and the fence would hold. Today the commit uses caller-passed sources, so this is latent. Return `null` or throw instead.
11. **`org.json` is used in `:services:memory`** (`DeletionEpoch.kt:4085`). The Global Constraints say "kotlinx `JsonElement` by hand in `:services:*`".
12. **Commits and scope:**
    - Task 9 shipped as two commits (08140f3c, 2a243b6e), whose messages differ from the single mandated one.
    - `labelRev` was implemented although the Round 3 core row says "Not in core: `labelRev`". The brief and core Tasks 27 and 31 require it, so this is a plan inconsistency, not an implementer error.
    - Extending `SqlCipherMigrationDeviceTest` (Task 10, LATER) was necessary once VERSION became 7.
13. **Tests that cannot fail on their own, or that test little:**
    - `GlassesBackupRulesTest.theBlanketExcludesStayAndTheSlice3aNamesAreAdded` is implied by the strengthened equality in `everySectionExcludesEveryDomainAndIncludesNothing`.
    - `BackupRulesTest.slice3aTablesLiveInTheExcludedDatabase` is near-tautological: Room tables are always in `context.db`.
    - The first two `assertEquals` in `slice3aPrefsAndFoldersAreExcludedEverywhere` compare a literal with a literal.
    - `DeletionEpochStoreTest.prefsNameIsTheBackupRuledOne` is a constant check.
    - `fencedPublishSkipsAfterWipe` passes a hand-written fence lambda, not a `DaoFence`, so it proves only that `FencedFiles` honours `false`.
    - **Missing tests:**
      - a concurrent Delete all against an in-flight fenced commit or publish;
      - the `installId` fallback path when the first `commit()` fails.
14. **Backup name drift.**
    - For phone prefs, `everyPrefsFileIsRuledOn` catches a later task that picks a different prefs name. But the stale names would stay in the XML, and a DataStore file (`files/datastore/*.preferences_pb`) would escape both the scan and the XML.
    - Phone folders are pinned only through `FencedFiles` constants.
    - On the glasses, drift is harmless because the blanket excludes cover everything.
15. **Doc mismatch:** the `CanvasPublishEntity.component` KDoc says `phone`, `glasses`, …, while core Tasks 26 and 28 name the component `capture_plan`.

### Column names later tasks will trip on (guessed in Task 8; read the entities)
- **Task 20/21:** the column is `replayClass` (EXACT|COUNTERFACTUAL), not the spec's `class`. `fromMs`/`toMs` are the **source envelope** (nullable) and double as the L1 range columns. Job states are RUNNING/DONE/CANCELLED/ABORTED; an invalidated RUNNING row must be cancelled by the runner. There is no `configHash` (I1).
- **Task 24:** `canvas_sweep.splitDaysJson` (default `'[]'`). Replays link to their sweep through `canvas_replay.sweepId`. There is no `configHash` per sweep point (I1).
- **Task 26:** `canvas_group_state(groupId, gen, publishId, configHash, component, state, updatedAt)`, with no `mode`/`phoneInstall`/`sentAt` (Minor 1). The publish columns are `groupId`, `component`, `planJson`, `configHash`, `gen`, `state` and `trialEndsAt`, all nullable.
- **Task 19 (and 18):** `canvas_trace` has `source` (IMU|HUB), `status` (COMPLETE|PARTIAL), `manifestJson`, `wipeGen`, `tombstoned` (not `tombstone`), `revision`, `parentTraceId`, `installId`, `deletionCounter`, `exact`, `startedAtMs`/`endedAtMs` and `createdAt`. No file path is stored, so the traceId-to-file name rule under `canvas_traces`/`canvas_import` is still to be pinned. `acceptTraceFenced` refuses any trace whose `installId`/`deletionCounter` differs from the current epoch.
- **Task 31:** `canvas_label(labelId, kind, source, name, atMs, frameId, traceId, provenance, deletionCounter, createdAt)`. There are no window, seq or listen columns (Minor 2), and `atMs` must be phone wall time.
- **Task 13 and Tasks 14/16:** no schema dependency. The glasses prefs names `capture_plan`/`mark_queue` and the folders `imu_trace`/`imu_trace_tmp` are guessed. Task 16's `imu_trace_tmp` and the spec's `files/imu_trace/` match.

### Checks run (named risk → check)
- **Hash drift between 7.json and the entities:** grepped the KSP `ContextDatabase_Impl.java` for the identity hashes.
- **Migration SQL against the export:** a Python diff of 6.json, 7.json and the `execSQL` strings.
- **A v7 collision with another branch:** `git cat-file` over every local branch, plus `ls-tree` of ctx/int, s2/int and s3a/int.
- **A multi-process stale prefs cache breaking the epoch fence:** grepped `android:process` in the manifests; there is none.
- **A Forget path missing the new cascade:** grepped `dropCanvasOutputs` and `withTransaction` in `ForgetService.kt`.
- **Whether the existing prefs scan catches backup name drift:** read `BackupRulesTest.everyPrefsFileIsRuledOn`.
- **A crash mid-wipe being resumed:** read `MemoryWipe.deleteAll`. There is no resume, and that was already true before this change. The epoch makes late writes fail, but data left by an interrupted wipe stays until the user retries.
- **`ATOMIC_MOVE` availability:** minSdk is 29 (orchestration and phone `build.gradle.kts`).

### Assessment
**Task quality:** Task 8 needs fixes (I1); Task 9 is approved.

**Reasoning:** The migration is additive, matches the export byte for byte with the KSP hash, and leaves the history untouched. The fences, the epoch-first Delete all, the Forget and retention hooks and the backup rules are correct, and their tests are real. The one blocker is schema completeness: under the one-migration ruling, replays and sweeps must carry `configHash` (S60) before v7 merges or is installed.
