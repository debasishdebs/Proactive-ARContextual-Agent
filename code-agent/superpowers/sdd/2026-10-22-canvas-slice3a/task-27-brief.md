### Task 27: Evidence gates (S11, S60, S75) [CORE, with 28: 2 h; LIVE gate LATER LA9]
**Stream B · 2.5 h · depends on: 21, 24, 25, 26, M1.**

**Implement:**
- round 1 Task 19's gates 1–5, with all evidence keyed by **`configHash`**;
- gate 3 needs replay sources whose `labelRev` and revisions are still current;
- the plan's `meta.expectOpensP95PerMin` is filled from the gate-3 replay **without changing `configHash`**;
- TRIAL sends `trialMaxMs = 3_600_000`;
- the TRIAL count is per `configHash`.

**Tests:**
- round 1's gate tests, plus:
  - **`configHashStableFromReplayToLive`** (compile → replay → TRIAL → calibration import → LIVE);
  - `staleSourcesFailGate3`;
  - `metaFillDoesNotChangeHash`;
  - `liveRefusedAt043`, `liveAllowedAt042`.
- **Mutation RED:** hash `meta` together with `config` → `configHashStableFromReplayToLive` fails.

**Commit:** `feat(canvas): publish evidence gates keyed by configHash (SHADOW, 60-min TRIAL, verified LIVE ≤ 0.42 %/min)`

