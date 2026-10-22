# Task 12 fix round 1
Status: DONE (clean build). Counts: :core:capture 287, :glasses 701, :services:glasses-link 137; 0 failures/skipped. (Task 16's tests included.)
1. PlanController tracks the RUNNING plan (PlanId install+gen+mode, set when activation is persisted, cleared on any return to settings; lock-free read). planLive/status/runningId describe it, not the staged replacement; revertIf(id) refuses a stale verdict; the running TRIAL's own deadline is enforced even while a replacement keeps failing to activate (the receiver's clock moves to the replacement).
2. AutoRevert hits carry PlanId; hits with no running plan are dropped; check counts only the running plan's hits.
3. KeyframeGate reports ceiling refusals itself (setCeilingListener; covers offer and flushWindow, uses its own keep history incl. forced keeps whose encode failed); session glance path uses gate.keptInMinute.
4. SamplingSession.effectiveWake() (after forRest) feeds the heartbeat; test runs WorkRest on the real session.
Also: within* tests check the final gap; SensingUnchangedTest now makes the recorder's feed obey the SensorRatePolicy decisions (FULL/STILL/LOW rates) and compose the profile like CaptureService (forced SETTLE), asserting the policy reached STILL.
Test-first: the fixes were coded before their tests this round; RED shown by mutation instead (all killed): planLive from receiver (2 AutoRevert tests), hits without identity, gate silent (GateCeilingTest x2, failed-encode session test), wake ignoring forRest, deadline dropped.
Concern: PlanController.traceKey() (Task 16) still reads the receiver's current state, so the IMU trace can record the staged plan during a handover; not changed here.
Follow-up: PlanController.traceKey() now returns the RUNNING plan's key (full config hash kept in the running record), so the IMU trace never records a staged plan during a handover. Test traceKeyDescribesTheRunningPlanNotAStagedReplacement: RED first, then fixed.
Counts after follow-up: :core:capture 287, :glasses 702, :services:glasses-link 137; 0 failures.
