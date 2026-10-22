### Task 2: `:core:capture` move-only, the `GlanceAdmission` extract, module deps (A1, S50, P4, S71) [CORE 2.5 h]
**Stream A · 2.5 h · depends on: 1.** It is the same as round 1 Task 1, plus:
- `services/ai/build.gradle.kts` and `services/orchestration/build.gradle.kts` get `api(project(":core:capture"))`.
- `DecisionGoldenTest` (Task 1) is green after each of the two commits.

**Files, Implement:** round 1 Task 1, unchanged:
- `git mv` of `GlanceParams`, `GlanceDetector`, `MotionClassifier`, `MotionModel.kt`, `ImageHashes`, `KeyframeGate` (with `PrefsGateStore` split out), `CameraPolicy` and `CaptureProfile` (split out of `BatteryBudget.kt`), with packages unchanged;
- the moved tests;
- `public` visibility only where needed, each with a comment;
- Commit 2 extracts `GlanceAdmission.decide`.

**Tests:**
- the moved tests are unchanged;
- `GlanceAdmissionTest` (round 1 names and the 64-case truth table);
- `DecisionGoldenTest` green.
- **Mutation RED:** flip `turned < noveltyMinRad` to `<=` → `matchesOnDwellTruthTable` fails.

**Commands:** `gw.sh :core:capture:test`, `gw.sh :glasses:testDebugUnitTest`, `gw.sh :services:ai:testDebugUnitTest --tests 'com.rokid.context.ai.canvas.*'`.

**Commits:**
1. `refactor(capture): move the pure glance, motion, keyframe-gate and camera-policy code into :core:capture (move-only, packages unchanged)`
2. `refactor(capture): extract GlanceAdmission from SamplingSession.onDwell (behaviour-identical, truth-table tested)`

