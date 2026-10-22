# Task 23a report (s3a/c, base 9ff34e3e)

Status: DONE with findings. Clean-module counts: :core:capture 325 tests, :glasses 706, :services:orchestration plan.* 12, 0 failures/skipped; :phone IdentityPlanEndToEndTest 3 green. No adb, no background processes left.

## Built
- glasses/.../capture/parity/TraceParityTest.kt (4 tests), DecisionGoldenRecorder gets an optional `trace` param (goldens unchanged).
- phone/.../hub/plan/IdentityPlanEndToEndTest.kt (3): real PlanCompiler -> real PlanSender (fake link) -> wire message -> real glasses PlanReceiver -> STAGED -> EFFECTIVE; effectiveHash == compiled configHash (f5782e0d8610), phoneInstall matches, ack closes the loop on the sender; one changed byte -> REJECTED.
- sessionRecordingReplaysAsRecordedWithinTheApproximateRule (renamed per ruling): real SamplingSession + Task 16 recorder over all 8 Task 1 scripts, replayed with CaptureSimulator. 8 traces, passed=182, failed=0, inexact=256 (reasons BIND_FAILURE, ASYNC_CALLBACK, CAPTURE_NOW; the latter two scoped to their scripts), 443 recorded opens. Gates equal in exact windows, opens match by cause within 250 ms.
- droppingAScoreEventFailsTheReplay, identityPlanSessionEqualsSettingsGoldens (TRIAL identity plan + recorder on = frozen goldens, all 8), shadowCountsEqualCounterfactualOpens.

## Mutation REDs (observed)
- one byte of planJson changed after compile -> the E2E test fails (STAGED expected, REJECTED).
- one Score dropped -> the replay test fails (open_extra, failed window).

## Findings
1. REAL simulator divergence (fixed, test-first `anOpenThatFailsAtTheSameInstantIsStillRecomputed`, RED then GREEN): a failed open (busy/bind) is recorded as ep open + ep close at the same instant; the replay ran its tick after the close's back-off and decided nothing, so the exact window ending at that open FAILED (bind_backoff script, 425180). Fix: the tick due at an open's instant runs before the open is applied; candidate mode no longer queues a recorded open that its own decision already matched. :core:capture 325 green.
2. mismatches list also contains entries from inexact windows (expected); only `failed` is the verdict.
3. Shadow vs counterfactual: SETTLE-wake scripts shadow=147, counterfactual=140 (exactly 5.0 %, boundary; per script flight 16/14, bind 20/17, others within 1). Shadow counts every admitted glance regardless of camera state/back-off (L1 point estimate). MOTION-wake scripts (unknown_walk, capture_now_mix) are excluded: a plan forces SETTLE but ReplayCandidate keeps the recorded wake, so the counterfactual streams and has 0 glance opens (shadow 24 / 14). Task 21/22 should add a wake override to the candidate for plans.
4. Tick body remains mirrored, not shared; this test is the guard (now passing on 8 real scripts).

## Follow-up 1: ReplayCandidate.forceSettle
`ReplayCandidate(forceSettle = true)` replaces the recorded Profile with the SETTLE wake and the SETTLE tier gaps as floors (row minima kept), like ActiveConfig.forceSettle. Test `aPlanCandidateForcesTheSettleProfile` (first candidate check 5 s vs 10 s after the last brief); mutation (no-op) RED. :core:capture 326 green, parity test green.
Finding: MOTION-wake scripts still cannot be included. The candidate replay follows the RECORDED episodes (a continuous STREAM episode is open through the glances), so it decides almost nothing (unknown_walk: shadow 24 vs counterfactual 0 glance opens, capture_now_mix: 14 vs 0). Needs a candidate-owned episode model (Task 24/21 scope), not a profile override. SETTLE-wake numbers unchanged: shadow 147 vs counterfactual 140 (5.0 %).
