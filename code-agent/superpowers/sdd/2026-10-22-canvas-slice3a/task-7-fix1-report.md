# Task 7 fix round 1

Status: DONE. Goldens unchanged: identity f5782e0d8610, section 1 golden 5ca1e1c3c69f (both asserted by PlanCompilerTest, green; no regenerated golden).

- Imp 1: second node of any non-threshold chain type refused ("unsupported wrapper order: T before T"); signal.in ctx needs exactly one out-edge to a policy; a ctx-fed policy with no chain edge refused ("... policy.capture_rate is not on the chain"). Tests duplicatePolicyRefused, ctxMustHaveExactlyOneOutEdge, policyOffTheChainRefused.
- Imp 2: absent cell in a present row = identity row value; only explicit null is blank (cap null, gap 0). All four PlanState rows always emitted (PlanState has exactly REST, VEHICLE_PASSENGER, VEHICLE_DRIVING, FLIGHT). Tests blankCapIsNull, blankGapIsZero updated; partialRowKeepsIdentityCells, allFourRowsAreAlwaysEmittedFromIdentity added.
- Min 3: boutMs and boutRad exact-boundary replays through GlanceDetector (test uses maxDtMs 250 for exact float rad).
- Min 4: validator's pre-fold "releaseRadS must not exceed onsetRadS" filtered in the compiler; post-fold check owns it (/validate unchanged). Tests added both ways.
- Min 5 dead branch removed. Min 6 weak tests strengthened (async g2 exact texts, no-op let removed, staticCost exact + async not counted, one-leaf now covers FLIGHT/VEHICLE rows, walk/stillCap gaps, nudges). Min 8 KDoc corrected. Min 9 ColSpec KDoc + NodeTypesV2Test.nullGapCellIsAcceptedAndNullBoolIsNot.
- Min 7: left as is, OPEN (sparse graphs compile; policy without signal.in compiles with ctxBound=false).

Counts (after clean): PlanCompilerTest 36 tests (was 27); :core:capture:test 196/0 failed; :services:ai:testDebugUnitTest 535/0; :services:orchestration:testDebugUnitTest 1033/0.
Note: tests and fix were written in one pass, no separate RED run recorded.
