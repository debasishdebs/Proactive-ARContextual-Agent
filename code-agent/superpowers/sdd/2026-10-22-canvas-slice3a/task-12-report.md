# Task 12 report (stream C, branch s3a/c, base 84bc7349)

Status: DONE. Counts (clean build, real XML totals): :core:capture 199 (was 196, +3), :glasses 649 (was 602, +47), :services:glasses-link 135; 0 failures, 0 skipped.

## Built
- SamplingSession: `lastPhotoAttemptAtMs` (first frame of an episode, = episode start; a continuous episode counts until it ends), cause `resv` (new wake key), `appliedState` provider, `onCeilingHit` callback. Reserved attempt bypasses timerHoldUntil, gaps, novelty; gate dedupe/blur/ceiling still apply; busy/bind back-off and every block reason still hold; active in every mode, uses the existing 250 ms tick. A due, unheld timer keeps cause `timer` (so goldens do not change).
- MotionClassifier.addTap/removeTap + SampleTap (throws swallowed). ShadowRunner (second GlanceDetector + GlanceAdmission, counts only). PlanController.shadow/status()/planLive(). AutoRevert (auto:ceiling: 3 hits in 10 min, only for an effective TRIAL/LIVE). Heartbeat tail: plan gen planMode ruleSkip1m=0 exprErr=0 planRevert resv shOpen shSkip worn charging (before csvFail). CaptureService wiring (tap, ceiling revert on the 1 s supervisor, status fields).
- Carry-ins: (a) heartbeat `wake` is now ActiveConfig.effectiveWake = the composed profile's wake (SETTLE under a plan). (b) no code change was needed: a TRIAL resumed from disk after a failed revert write already ends at persisted start + trialMax (or auto:reboot); locked with 4 tests (AutoRevertTest).
- Trimmed per plan table: no `auto:opens`, no planHash12/persistLog/chargeUah.

## RED then GREEN
RED: test compile failure (missing APIs) for all new tests, then behavioural GREEN. The tap was implemented before its test (SampleTapTest), so its RED is the mutation below.
Mutations (each killed, then reverted): suppress resv -> 11 ReservedAttemptTest fail incl. within300sInRestWithGap300s; dwell waits for gap -> glanceNeverWaitsForTimerGap; plan changes sensing (status reason) -> timerPhotoGapLeavesSensingUnchanged; resv bound halved -> goldensPlusExactlyExpectedResv, DecisionGoldenTest, within300s*; tap not called -> SampleTapTest x2; ceiling not reported -> aGlanceRefusedByTheCeiling...; bootstrap without tick -> 2 restart tests; wake = settings wake -> theHeartbeatWakeIsTheEffectiveOne.

## Notes / concerns
- Goldens: all `expectedResv` lists are empty and still are; the session adds zero resv opens on every script.
- "Round 1" names/rules were not on disk; AutoRevertTest and CaptureNowPrecedenceTest use names I chose. No wipe/forget seam exists in the glasses session, so the "Capture now during a wipe" precedence test is not written (heat STOP, pause, not worn, busy are).
- Ceiling hit = a glance or frame refused while keeps in the last minute >= 2 x cap (forced keeps count toward it). Plain timer keeps cannot reach it under a hard cap; the rule mostly fires via forced keeps and big-turn glances.
- shOpen/shSkip/resv print raw counts since the previous heartbeat (not per minute). SHADOW counts glance admissions only (timer opens not modelled).
- SensingUnchangedTest observes rates via SensorRatePolicy fed with the session status (as CaptureService does) plus a tap counting inputs; the recorder feeds sensors itself, so input-count equality is weak by construction.
- CaptureService wiring (autoRevert on the supervisor, heartbeat fields) has no unit test (service not JVM-testable); compiled and reviewed only. Two foreign gradle daemons (other worktrees) are running; none of mine.
