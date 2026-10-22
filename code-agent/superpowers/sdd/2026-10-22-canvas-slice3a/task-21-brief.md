### Task 21: Counterfactual replay, battery line, replay API, job lifecycle (A10 rest, S77, S80) [CORE, trimmed 2 h]
**Stream A · 3 h · depends on: 8, 20.**

**Implement:**
- round 1 Task 15's counterfactual replay and warnings;
- **`BatteryLine`:** `pctPerMin = 0.433 + 0.0239 · camOpenSPerMin`; **hours = 100 / pctPerMin / 60**;
- **`ReplayService`:**
  - one job at a time (a mutex), with `jobState` persisted;
  - cancelled on thermal SEVERE, service shutdown or Developer mode off;
  - `ABORTED` after process death (`markRunningAborted`);
  - `sourcesJson` and `labelRev` recorded;
  - committed through the fenced DAO;
- `ReplayRoutes.kt` is written here and registered in Task 22.

**Tests:**
- round 1's, plus:
  - `a3Hours3_40` (2.4 s/min → 0.49 %/min → 3.40 ± 0.01 h), `a2Hours2_78`;
  - `oneJobAtATime`;
  - `cancelledOnDeveloperModeOff`, `abortedAfterRestart`;
  - `sourcesRecorded`.

**Commands:** `gw.sh :core:capture:test`, `gw.sh :services:orchestration:testDebugUnitTest --tests 'com.rokid.context.canvas.plan.*'`.

**Commit:** `feat(canvas): counterfactual replay (point-estimate keeps, unobserved share, fusion warning), battery line in hours, single-job replay service with persisted job states`

