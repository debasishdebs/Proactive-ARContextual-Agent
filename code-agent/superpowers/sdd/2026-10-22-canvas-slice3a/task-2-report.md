# Task 2 report: :core:capture move-only + GlanceAdmission

Status: DONE. Commits on s3a/c: 0bedf427 (pure moves + module wiring), then the GlanceAdmission extract commit (see git log).
Moved (git mv, packages unchanged): GlanceParams, GlanceDetector, MotionClassifier, MotionModel, ImageHashes, KeyframeGate, CameraPolicy, FeatureLogger (+ tests ImageHashes, KeyframeGate, GlanceDetector, MotionClassifier, MotionModel, MotionModelTrainedTree, CameraPolicy, FeatureLogger).
Splits: PrefsGateStore stays in :glasses (new file, same package); CaptureProfile+BudgetTier split out of BatteryBudget.kt into :core:capture.
Wiring: settings.gradle.kts include; core/capture/build.gradle.kts (kotlin jvm, api :core:model, test-junit + junit 4.13.2); glasses implementation, services:ai and :orchestration api. No ProGuard changes (rules are a no-op file). No Android deps in :core:capture.
Tests: :core:capture:test 122/0 fail; :glasses:testDebugUnitTest 543/0 fail (DecisionGoldenTest 3/3, goldens untouched); :glasses:assembleDebug, :phone compile + unit-test compile, :services:ai/:orchestration compile OK (phone does not use these classes).
Mutation RED: `<` -> `<=` on noveltyMinRad fails matchesOnDwellTruthTable and noveltyBoundary test; reverted.
Proof: `git diff -M --stat 0bedf427~1 0bedf427` (15 renames at 0 changed lines); `git diff -M --diff-filter=R --numstat` non-zero rows: 1 (KeyframeGate, -24 PrefsGateStore cut); removed vs added code lines across main sources are identical multisets except 2 new `package` headers.
Concern: FeatureLogger (+test) also moved, not in the plan list, because MotionClassifierTest references it and tests must stay unchanged. No visibility changes were needed (internal members only used by moved tests).
