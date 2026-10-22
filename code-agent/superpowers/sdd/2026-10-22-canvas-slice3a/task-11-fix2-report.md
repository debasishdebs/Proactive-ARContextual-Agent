# Task 11 fix round 2 report

Status: DONE. Counts (clean): :core:capture 196, :glasses 602, :services:glasses-link 135, :phone *Hub* 180; 0 failures.

Done: R1 (onMessage catch now calls failClosed; test: TRIAL held, SETTINGS gen 2 with failNextCommit -> SETTINGS active); stamp = maxOf(session, ...) with a test; PrefsPlanPersistence.load never throws (a mistyped pref is no plan; this covers the receiver constructor's load); :core:capture test revertFailsClosedWhenTheWriteFails; the misnamed test is now theStampNeverMovesBackwardsAndTheFirstMessageTeachesIt.

Report only (no code)
- REJECTED reason "persist" is not in the S24 reason list. The gen is NOT terminal on the glasses; the same gen is applied on the next resend. Task 26 must treat it as retryable (resend on the next onReady/heartbeat), never as a final verdict.
- Heartbeat `wake` (CaptureService, `settings.cameraWake.name`) reports the settings wake while a plan runs SETTLE. Not a capture defect (capture uses the composed SETTLE profile); it is a diagnostics mislabel that can mislead A/B analysis during a TRIAL. Suggested fix: report the composed profile wake (plan.active.profile(...).wake). Not changed here.
