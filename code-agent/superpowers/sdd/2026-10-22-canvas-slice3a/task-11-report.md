# Task 11 report: glasses staged atomic apply, local TRIAL, provider params

Status: DONE (see concerns). Branch s3a/c.
Commits: ad9cc15d (GateParams/ScoreListener/offerScored, classifier config), then the receiver-state codec + BootId commit, then the glasses commit (PlanController, ActiveConfig, PrefsPlanPersistence, wiring, tests).

Design: PlanController owns a private PlanReceiver behind one lock (acks sent after the lock is released); ActiveConfig is one immutable set (glance, gate, 6-row table, ctxBound) swapped whole when the detector is QUIET and no camera episode is open, or after 10 s by aborting the bout; revert is immediate; persisted state is ticked before it is applied on load; staged-on-load waits for the first supervisor tick (crash mid-apply = SETTINGS); LIVE refused by the receiver and a persisted LIVE reverted unsupported:live; SHADOW/SETTINGS map to ActiveConfig.SETTINGS. Absent editable row = UNKNOWN row; UNKNOWN/WALKING locked default rows always present. bootId = BOOT_COUNT (fallback boot instant). No new timers: plan.tick rides the 1 s supervisor loop.
Mutation REDs: gate/active applied at staging -> noMixedOldDetectorNewCaps (PlanControllerTest and StagedActivationTest) fail; WALKING/UNKNOWN rows dropped -> vetoedVehicleGetsFullRateRow fails; also onset ignored, cap ignored, SHADOW applied, lock removed all RED.
Tests: :core:capture:test, :services:glasses-link, :glasses:testDebugUnitTest green (goldens unchanged).
