### Task 9: Persisted deletion epoch, Delete all, tmp sweep, backup rules (S57, S58, S81) [CORE, with 8]
**Stream B · 2.5 h · depends on: 8.**

**Files:**
- create `services/memory/.../db/DeletionEpochStore.kt` (phone prefs `deletion_epoch`: `installId` (a random UUID on first use), `counter`, `wipedAtMs`)
- modify `WipeDao`/`ForgetService` Delete all: **commit `counter + 1` and `wipedAtMs` first**, then delete the new tables and the folders `files/canvas_traces`, `files/canvas_import`, `files/canvas_tmp`
- create `services/orchestration/.../canvas/trace/FencedFiles.kt`: `publish(tmp, final, fence: suspend () -> Boolean)` renames only when the fence holds inside the transaction, and `sweepTmp()` runs at start-up
- extend `phone/src/main/res/xml/backup_rules.xml` and the data-extraction rules (cloud and device-transfer) with `deletion_epoch`, `canvas_plan_state`, `canvas_collect`, `mark_settings`, `canvas_traces`, `canvas_import`, `canvas_tmp`; extend `BackupRulesTest` (its classification and both XML sections)
- extend `glasses/src/main/res/xml/backup_rules.xml` and the existing `GlassesBackupRulesTest` with `capture_plan`, `mark_queue`, `imu_trace` and `imu_trace_tmp`, keeping its blanket exclusions

**Tests:**
- `epochCommittedBeforeDelete` (a crash injected after the commit → the next start sees the new counter);
- `installIdStable`;
- `deleteAllRemovesFolders`;
- `fencedPublishSkipsAfterWipe`, `tmpSweptAtStartup`;
- the backup tests.

**Commands:** `gw.sh :services:memory:testDebugUnitTest`, `gw.sh :services:orchestration:testDebugUnitTest --tests 'com.rokid.context.canvas.trace.*'`, `gw.sh :phone:testDebugUnitTest --tests 'com.rokid.context.BackupRulesTest'`, `gw.sh :glasses:testDebugUnitTest --tests '*GlassesBackupRulesTest'`.

**Commit:** `feat(data): persisted pairing-scoped deletion epoch committed before Delete all, fenced file publication, tmp sweep, slice 3a backup exclusions`

