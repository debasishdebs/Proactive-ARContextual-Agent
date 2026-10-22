### Task 11: Glasses staged atomic apply, local TRIAL, provider params (A5 part, S59, S67, S68, S69) [CORE 3 h]
**Stream C · 3.5 h · depends on: 4, 5.**

**Files:**
- create `glasses/.../capture/plan/PrefsPlanPersistence.kt` (prefs `capture_plan`, `commit()`), `PlanController.kt`, `ActiveConfig.kt`
- modify:
  - `MotionClassifier`: `@Volatile var config` replaces the constructor `params` reads everywhere; `setConfig` takes effect at the safe boundary;
  - `KeyframeGate`: the `GateParams` provider;
  - `SamplingSession`: `glanceNow`;
  - `CaptureService`: the profile composition per S68, and `ContextPolicies.of(applied, plan.rows ?: DEFAULT)`;
  - `GlassesRuntime`: wire `PlanController` and send the caps ack on ready;
  - `HudController`: `planAware`.
- tests: `PlanControllerTest.kt`, `PlanLiveApplyTest.kt`, `StagedActivationTest.kt`; extend `MotionClassifierTest`, `KeyframeGateTest`

**Implement:**
- **`PlanController`** wraps `PlanReceiver`. On a staged plan it builds one `ActiveConfig` and activates it when the detector is QUIET **and** no episode is open; after 10 s it aborts the bout instead. Then it calls `receiver.activated(gen)` and sends EFFECTIVE.
- **`planAware = false`** with a persisted plan → revert `auto:old_phone`.
- **Apply:** LIVE and TRIAL only. The camera wake is SETTLE. The rows go through `withContext`, then `forRest()`, then heat and battery (S68). Row `capPerMin` null → the context cap is null (no hard flag).
- **SHADOW and SETTINGS** leave the real capture inputs untouched.

**Tests:**
- `settingsPathEqualsGoldens` (Task 1 files; diffs only at `expectedResv`, which Task 12 adds);
- `identityPlanLiveEqualsGoldens` (every script, including REST 120 s, FLIGHT 300 s, WorkRest, T2 and heat-SLOW);
- `liveCapOneLimitsKeeps`, `onsetFromPlanChangesBoutStart`;
- `vetoedVehicleGetsFullRateRow`, `speedLiftsVeto`, `ctxUnboundAppliesUnknown`;
- `heatStopBeatsPlan`, `userPauseBeatsPlan`;
- `settingsEditAfterPublishDoesNotChangeLivePlan`;
- `stagedActivationWaitsForQuiet`, `activationAbortsBoutAfter10s`, `effectiveOnlyAfterActivation`, `noMixedOldDetectorNewCaps`;
- `oldPhoneRevertsPersistedPlan`;
- `shadowLeavesCaptureInputsUntouched`.
- **Mutation REDs:** apply the gate params before activation → `noMixedOldDetectorNewCaps` fails; drop the WALKING default row → `vetoedVehicleGetsFullRateRow` fails.

**Commands:** `gw.sh :core:capture:test`, `gw.sh :glasses:testDebugUnitTest --tests '*PlanControllerTest' --tests '*PlanLiveApplyTest' --tests '*StagedActivationTest' --tests '*DecisionGoldenTest'`, then `gw.sh :glasses:testDebugUnitTest`.

**Commit:** `feat(glasses): capture plan via PlanReceiver with staged atomic activation, local TRIAL limit, S68 profile composition (settings path equals the frozen goldens)`

