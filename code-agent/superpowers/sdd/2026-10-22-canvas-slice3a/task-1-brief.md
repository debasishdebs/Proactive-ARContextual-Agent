### Task 1: Freeze pre-change decision goldens from `s2/int` (S76, S83) [CORE 1.5 h]
**Stream C · 1.5 h · depends on: base.** It runs **before** Task 2 and touches no main code.

**Files:**
- create `glasses/src/test/java/com/rokid/context/glasses/capture/golden/DecisionGoldenRecorder.kt` (test-only)
- create `glasses/src/test/resources/golden/decisions-*.txt`, one per script
- create `GoldenScripts.kt` with seeded scripts:
  - `unknown_walk_20min`, `rest_still_30min`, `flight_settings_20min`, `workrest_rest_30min`;
  - `budget_t2_20min`, `heat_slow_10min`, `bind_backoff_10min`, `capture_now_mix_10min`.

**Implement:**
- Drive the real `SamplingSession` with `SamplingTestKit` (a fake camera, a fake clock, scripted IMU and frames) under **settings mode** (wave B's `ContextPolicies.DEFAULT` per scripted context state).
- Write one line per decision: `t kind cause detail`, covering glances, novelty and cap skips, opens with their cause, keeps and skips with their reason, and timer gaps.
- Also record, per script, the `MotionClassifier` input count and each glance-trigger-to-open latency (for S84's test).
- `expectedResv`: from the recorded opens, compute where the gap since the last open would exceed `reservedBoundMs` (S83) and list those times. Task 12 must add a `resv` open exactly there and nowhere else.

**Tests:** `DecisionGoldenTest.goldensReproduce`, which replays each script and compares with its file. After Task 2 this test must still pass unchanged.

**Command:** `gw.sh :glasses:testDebugUnitTest --tests '*DecisionGoldenTest'`.

**Commit:** `test(glasses): freeze pre-slice-3a capture decision goldens (settings mode, 8 scripts, expected reserved-attempt points)`

