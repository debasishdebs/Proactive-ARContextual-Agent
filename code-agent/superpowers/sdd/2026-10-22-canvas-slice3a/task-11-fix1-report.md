# Task 11 fix round 1 report

Status: DONE. Branch s3a/c.

Tests (clean, real counts from the XML reports): :core:capture 195, :glasses 601, :services:glasses-link 135, :phone (Hub* tests only) 180; 0 failures, 0 skipped.
Red first: 14 new or corrected tests failed before the fix (PlanFix1Test x10, PlanLiveApplyTest x2, PrefsPlanPersistenceTest x1, HudControllerTest x1), then all green.

Fixed
- Important 1: HudController sends the gen-0 caps-only announcement, then a SEPARATE statusAck (only when a plan was ever held). PlanController remembers the phone sessionStartMs of the latest message and stamps EVERY ack (returned, sent, self-emitted, status) inside locked{}.
- Important 2: ActiveConfig.forceSettle (true for every plan, false for SETTINGS): a plan always composes the SETTLE wake. PlanLiveApplyTest assertion corrected, plus a new test.
- Important 3: PlanReceiver.revert is fail-closed (state reverted in memory even if the write fails; persistPending, retried at each tick). Controller: bootstrap, onMessage (REJECTED "persist"), tick, onPhoneSettings, revert all catch; failClosed() swaps to SETTINGS.
- Minor 1: PlanController.attach(host) installs the config in force under the lock (CaptureService uses it). 2: a failed activation write resets stagedAt (abort at most once per 10 s). 3: plan.tick has its own try in the supervisor. 4: statusAck runs the TRIAL check. 9: settingsEditAfterPublish can fail (composed profile compared before/after an edit); heatStop/userPause set the Rig profile and cap from the plan rows. 13: PrefsPlanPersistence writes a high-water mark (install, gen) with every state; an unreadable state loads as a REVERTED placeholder at that gen (fail closed), newer gens still accepted. 18: android-companion/.kotlin/ in .gitignore.
- Minor 10: not changed. PlanControllerTest.anAbsentEditableRowIsTheUnknownRowNotTheDefaultRestRow already pinned it; PlanFix1Test.anAbsentEditableRowIsExactlyTheUnknownRowPin adds a second pin. Task 7 / Task 20 must still emit all four rows or use ActiveConfig.of.

Concerns
- glasses (minSdk 28) cannot depend on services:glasses-link (minSdk 29: manifest merge fails), so the gate check is split: glasses tests assert the ack fields against a local copy of the gate rule (PhoneGateRule), and Slice3aWireTest.stampedReceiverAcksMatchAndTheCapsAckStaysCapsOnly runs the real PlanAckGate and PlanPeerTracker on the same shapes.
- The status ack at session open carries the previous session stamp (or 0 before any message); the glasses only learn the new sessionStartMs from the first plan message, so it can classify STALE until then. The phone re-sends its gen on a new session, which then MATCHes.
- PlanReceiver (core:capture) was changed (revert no longer throws on a failed write).
