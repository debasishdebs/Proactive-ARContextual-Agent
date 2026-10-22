# Task 1 report: frozen decision goldens

Status: DONE. Commit 2827261a on s3a/c (test-only, no production change).

Files (glasses/src/test): capture/golden/{GoldenScripts,DecisionGoldenRecorder,DecisionGoldenTest}.kt and resources/golden/decisions-*.txt (8 files).

Tests (DecisionGoldenTest, 3 run, 0 failures): goldensReproduce (replays 8 scripts vs files), recordingIsDeterministic (runs two scripts twice), goldensCoverTheDecisionKinds.
Command: gw.sh :glasses:testDebugUnitTest --tests '*DecisionGoldenTest'. Re-record only deliberately with GOLDEN_RECORD=1.

Method: real SamplingSession via the shared Rig; own 50 Hz gyro+accel feed and heat poll; a 20 ms poll reads camera events, keyframe sink, diagSnapshot (open cause, novel/cap/same skips, glances), motion flow, status, stillGapMs. Context state applied as CaptureService does (ContextPolicies.DEFAULT -> withContext + contextCap). Lines: `t kind cause detail`. Footer: motionClassifierInputs, glanceTriggerToOpenLatencyMs, opens summary, expectedResv.
expectedResv: from successful opens, points where gap exceeds reservedBoundMs (300 s REST/VEHICLE/FLIGHT or WorkRest resting, else 60 s), chaining after each point. Nonempty: unknown_walk (7 points), bind_backoff (1), capture_now_mix (1); others empty.

Concerns:
- Skip reasons blur/dark are not separately observable without a hook; they show only via close `analysed=` counts.
- Skip and glance lines are stamped at the 20 ms poll time, not exact; opens/closes/keeps are exact.
- unknown_walk and capture_now_mix (MOTION wake) have no dwell opens, so glanceTriggerToOpenLatency is empty there; other scripts are mostly 0 ms (bind_backoff has 1000/2000/2250).
- Resv bound is re-implemented in the test (no main code allowed); Task 4 should keep it identical.

## Fix round 1
- expectedResv now follows S83 "while eligible": swept on the 250 ms tick; an open camera episode satisfies the bound at every tick it is open; an attempt is an ok open or an open that failed while the camera was busy (bind failures do not count); the bound uses the applied state at the tick tested. Result: empty for all 8 scripts (matches S83). Rule and the one-tick (250 ms) tolerance for Task 12 are in each golden's header comment.
- S70 interpretation ("last episode start that delivered a first frame"): an attempt is an episode start, plus a start that failed only because the camera was busy; an episode already open and delivering frames satisfies the bound for as long as it is open. Task 12's lastPhotoAttemptAtMs must therefore advance while an episode is open and on busy failures.
- Re-record: diff shows zero changed non-comment lines; only header comments, the expectedResv footer and the classifier line changed.
- motionClassifierInputs removed (it only counted the test's own feed). Replaced by classifierFingerprint: sha256/16 over the motion, dwell and glance lines plus the classifier's own features() once per second (rotation, gyro max, accel variance, step Hz, state, glances). It changes if the classifier input stream or output changes; it does not observe the real SENSOR rate (that lives in CaptureService/SensorRatePolicy, outside the Rig), so Task 12 must assert the sensor registration profile separately.
- GOLDEN_RECORD=1 now fails after writing (seen: BUILD FAILED "recorded 8 goldens; rerun without GOLDEN_RECORD").
- GlanceAdmissionTest: 96-case table (turned 0.1/min/0.5/big x kept cap-1/cap/2cap x since x walked x hard). Mutation `<` to `<=` on noveltyMinRad: 2 of 3 tests RED, reverted, green; recorded in that commit body (1fb2ee19).
- git diff s2/int 138ebb27 -- android-companion/glasses/src/main: empty.
- Final runs: :glasses:testDebugUnitTest 543 tests, 0 failures; :core:capture:test 122, 0 failures.
- Known gaps (skipped): no VEHICLE_* script; blur/dark skips not observable; skip/glance lines stamped at 20 ms poll; ok/failed read at poll time.
