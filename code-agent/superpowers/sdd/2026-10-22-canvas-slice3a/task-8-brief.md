### Task 8: Room v7 schema and DAOs (A8 part, S75, S77) [CORE, with 9: 4.5 h]
**Stream B · 3 h · depends on: base.**

**Files:** round 1 Task 7's schema part:
- `CanvasSlice3Entities.kt`, the DAOs, `MIGRATION_6_7`, `7.json`;
- `Migration6To7Test`, `DatabaseFactoryUpgradeTest`, `CanvasSlice3DaoTest`.

**Changes over round 1:**
- `canvas_publish` gains `configHash` instead of `planHash`.
- `canvas_trace` gains `revision`, `parentTraceId`, `installId`, `deletionCounter`, `exact`.
- `canvas_replay` and `canvas_sweep` gain `sourcesJson`, `labelRev`, and a `jobState` of `RUNNING|DONE|CANCELLED|ABORTED`.
- New `canvas_meta(key PK, value)` holds the `labelRev` counter.
- The `canvas_calibration` columns are `configHash`, `artifactJson`, `verified`, and so on.
- New `canvas_group_state(groupId PK, gen, publishId, configHash, component, state, updatedAt)`, the S62 recovery table.
- `canvas_label`: there is no text column (as round 1).

**Fenced DAO writes:** round 1's set, plus:
- `bumpLabelRev()`, inside every label write;
- `invalidateBySources(traceIds, fromMs, toMs, labelRevChanged)`;
- `markRunningAborted()`, called at start-up.

**Tests:**
- round 1's, plus:
  - `labelWriteBumpsLabelRev`;
  - `replayCommitBlockedWhenLabelRevMoved`;
  - `startupAbortsRunningJobs`;
  - `groupStateRoundTrip`.
- **Mutation RED:** remove the tombstone check from `commitReplayFenced` → `replayCommitBlockedByTombstone` fails.

**Commands:** `gw.sh :services:memory:testDebugUnitTest`.

**Commit:** `feat(db): Room v7 migration A (capture-plan publish columns, traces with revisions, events, replays and sweeps with provenance and job states, canvas_label, calibration, group state, helper jobs), fenced DAOs`

