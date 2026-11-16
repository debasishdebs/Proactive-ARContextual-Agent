Reviewed the combined state at `81eb9738` against the design.

1. **P1 — Recovery can launch glasses during a call and bypass the 6/h cap.** Recovery starts the service, whose `Hub.start()` calls [`link.connectOnce()` directly](android-companion/phone/src/main/java/com/rokid/context/phone/hub/Hub.kt:185). That path opens a CUSTOM_APP session without consulting `callIdle()` or recording a persisted relaunch attempt. The guarded pending-close timer does not protect this initial connect. Route automatic recovery connects through the same guards and quota.

2. **P2 — Recovery uses the wrong ladder and lacks the required minimum interval.** [`recover()`](android-companion/phone/src/main/java/com/rokid/context/phone/hub/AutoStopMachine.kt:225) schedules its first retry after **120 minutes**, then 240 minutes, instead of using the persisted watchdog’s 5/15/30/60-minute ladder. Additionally, `present()` clears that deadline: foreground → present → process death can permit another immediate recovery. Share the watchdog ladder, including its last-start timestamp and 60-second minimum.

3. **P2 — Cancelling a pending stop through `UserStarted` leaves `hubLive=false`.** AutoStop’s pending mark clears the flag, but [`userStarted()`](android-companion/phone/src/main/java/com/rokid/context/phone/hub/AutoStopMachine.kt:95) discards `pendingStop` without restoring `prevHubLive`. When the service already runs, no new `HubForeground` arrives. A later process death is consequently ignored. Restore the pending mark before clearing user-start bookkeeping.

4. **P2 — Pending-stop rollback can undo `ExpectedStopped`.** [`ExpectedStopped`](android-companion/phone/src/main/java/com/rokid/context/phone/hub/AutoStopMachine.kt:110) clears the flag but retains `pendingStop`. If it arrives during the mark’s commit, the subsequent confirm can observe the stopped hub and [`cancel()` restores `prevHubLive=true`](android-companion/phone/src/main/java/com/rokid/context/phone/hub/AutoStopMachine.kt:185), clearing the auto-stop mark too. A later process start can misclassify this intentional stop as lost. Stop events must invalidate pending rollback state.

5. **P2 — Attempt persistence has a process-death window.** [`PrefsRelaunchStore.saveAttempts()`](android-companion/phone/src/main/java/com/rokid/context/phone/hub/GlassesRelauncher.kt:101) uses asynchronous `apply()`, and `doReopen()` immediately launches. Killing the process before the sixth attempt reaches disk lets a rebuilt relauncher load five and allow a seventh. Persist the quota durably on IO before performing the launch.

6. **P3 — Tests miss the production recovery path and lifecycle interleavings.** The [new relaunch tests](android-companion/phone/src/test/java/com/rokid/context/phone/hub/GlassesRelauncherTest.kt:314) use an immediately persistent in-memory store and instantiate only the relauncher. Add service/hub recovery tests with an active call and exhausted quota, pending-stop tests interleaving `UserStarted`/`ExpectedStopped`, and production-store persistence coverage. The “call and its settle” test toggles a Boolean guard; it does not verify 15 seconds.

`52a16e7e` addresses the previous diagnostics findings: the added exit-history collection runs on IO, classification and sub-cause share one lookup, and foreground refusal has coverage. No new privacy leak found in the added logging.

Tests were inspected but not run in the read-only environment. Deferred T4/T6/T7 work was excluded because these commits cover T3/T5 and prior-review fixes.

NOT READY