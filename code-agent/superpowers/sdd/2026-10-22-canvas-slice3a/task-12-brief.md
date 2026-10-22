### Task 12: Reserved timer photo attempt, SHADOW counts, auto-revert, heartbeat, sensing-unchanged golden (A5 rest, S61, S67, S70, S83, S84) [CORE, trimmed 3 h]
**Stream C · 4 h · depends on: 11.**

**Files:**
- create `ShadowRunner.kt`, `AutoRevert.kt`
- modify:
  - `MotionClassifier`: `addTap(SampleTap)`, as round 1;
  - `SamplingSession`:
    - `lastPhotoAttemptAtMs` = the start of the last episode that delivered a first frame;
    - a distinct cause `resv`: when eligible and `now − lastPhotoAttemptAtMs ≥ reservedBoundMs(applied, resting)`, start a brief episode with cause `resv`;
    - it bypasses `timerHoldUntil`, the policy gaps and novelty; dedupe, blur and the hard ceiling still apply;
    - it is active in **every mode** and uses no new timer (the existing tick);
  - `Heartbeat`: ` plan=<hash8|none> planHash12=<…|none> gen=… planMode=… ruleSkip1m=0 exprErr=0 planRevert=… resv=… shOpen=… shSkip=… worn=0|1 charging=0|1 persistLog=0|1 chargeUah=<n|na>`.
- tests: `ReservedAttemptTest.kt`, `ShadowRunnerTest.kt`, `AutoRevertTest.kt`, `CaptureNowPrecedenceTest.kt`, `SensingUnchangedTest.kt`; extend `HeartbeatTest`, `DecisionGoldenTest`

**Implement:**
- **SHADOW:** as round 1 (a second detector through the tap; would-open and would-skip counts only).
- **Auto-revert:** round 1's rules. The p95 comes from `meta.expectOpensP95PerMin`.
- **Glance and manual triggers:** `pendingDwellAt` and `pendingNowAt` keep starting episodes regardless of timer photo gaps (today's code). A test locks it in.

**Tests:**
- `ReservedAttemptTest`, through the real session and camera owner fakes:
  - `within60sInUnknownUnderRestrictivePlan`, `within60sWhileWalking`;
  - `within300sInRestWithGap300s`, `within300sInFlight`, `within300sWhileWorkRestResting`;
  - `continuousHeadMotionStillBounded`, `stillBackoffBounded`, `heatSlowBounded`, `bindBackoffNotEligibleThenFires`, `failedOpenDoesNotResetDeadline`;
  - `notWhilePausedNotWornHeatStoppedCameraBusy`;
  - `ignoresCapHoldButNotHardCeiling`;
  - **`goldensPlusExactlyExpectedResv`** (Task 1's `expectedResv` points, and no others).
- **`SensingUnchangedTest.timerPhotoGapLeavesSensingUnchanged`** (S84 (4)): the identity plan against the same plan with every REST, VEHICLE and FLIGHT gap at 300 s, over `rest_still_30min` and `unknown_walk_20min`:
  - identical sensor registration calls and rates;
  - an identical `MotionClassifier` input count;
  - identical glance-trigger-to-open latencies;
  - only the timer-cause opens differ.
- `glanceNeverWaitsForTimerGap`, `captureNowNeverWaitsForTimerGap`.
- `ShadowRunnerTest`: `countsWouldOpen`, `neverOpensCamera`, **`shadowCaptureEqualsSettingsUnderRestrictiveFlightAndRest`**.
- `AutoRevertTest` (round 1's names).
- `CaptureNowPrecedenceTest` (round 1's names).
- `HeartbeatTest`: the key order and defaults.
- **Mutation REDs:** suppress `resv` → `within300sInRestWithGap300s` fails; gate the dwell on the timer gap → `glanceNeverWaitsForTimerGap` fails; let the plan change `SENSOR_DELAY` → `timerPhotoGapLeavesSensingUnchanged` fails.

**Commands:** `gw.sh :core:capture:test`, `gw.sh :glasses:testDebugUnitTest --tests '*ReservedAttemptTest' --tests '*SensingUnchangedTest' --tests '*ShadowRunnerTest' --tests '*AutoRevertTest' --tests '*CaptureNowPrecedenceTest' --tests '*HeartbeatTest' --tests '*DecisionGoldenTest'`, then the whole module.

**Commit:** `feat(glasses): reserved timer photo attempt (60 s active, 300 s at rest, every mode), SHADOW counts, auto-revert, verification heartbeat fields; plan never touches sensing`

