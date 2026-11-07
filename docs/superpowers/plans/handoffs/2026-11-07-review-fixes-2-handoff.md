# Review fixes 2 (2026-11-07), branch feat/v099-watch

Inputs: the Codex findings (concurrent wake, main-thread disk waits), the failing `VoiceMarksDocTest.readmeBlockEqualsRenderCompact`, the README privacy line and the release notes. I checked every finding against the code before changing anything, and all of them were correct.
Frozen values untouched: PROTOCOL_VERSION 1, TRACE_SCHEMA 1, plan hash f5782e0d8610, Room v15.

## 1. Concurrent wake (phone/hub)

- Confirmed: `AutoStopWake.onProcessStart` (WorkManager thread) and `AutoStopController.evaluate` (Main.immediate) each ran check deadline, consume rung, set probe and start, with no lock. `ensureRunning` returns false when the service already runs, so the loser took that as a refusal and cleared the winner's probe.
- New `AutoStopWake.claim(...)` is the one owner. It runs everything under one process-wide lock (`claimLock`) and blocks, so it is never called on main:
  re-check (auto stopped, no manual stop, `wakeAllowed`, `deadlinePassed`) -> `wakeAttempted` + `setWakeProbe(true)` (both committed) -> start -> settle.
  - The result is `Claim.NONE / NOT_DUE / STARTED / ALREADY_RUNNING / REFUSED`.
  - The loser of a race finds the auto stop cleared (the winner started) or the deadline moved (the winner was refused). It gets NONE or NOT_DUE and touches nothing.
  - A start that returns false while `running()` is true is ALREADY_RUNNING, not a refusal. The probe flag is kept, the auto stop is cleared and nothing is scheduled.
- `onProcessStart` keeps its unlocked cheap exit, then maps the claim result. NOT_DUE and REFUSED schedule the remaining time.
- The controller calls the claim through `withContext(io)`. It has a new `serviceRunning` parameter, which `HubGraph` wires to `ContextHubService::isRunning`. When the controller loses (NONE), it re-evaluates after `BUSY_RECHECK_MS`.
- `InMemoryAutoStopStore` fields are now `@Volatile`.
- Tests (`AutoStopWakeTest`) use a deterministic interleaving: a fixed clock and latches. The winner parks inside its start. The controller loser parks after it has read the old deadline, and is released only when the winner is in its start. The test then waits until the loser's thread is BLOCKED on the lock.
  - Controller wins: the worker is a no-op and the probe is kept.
  - Worker wins: the controller is a no-op, the probe is kept and it returns BUSY_RECHECK.
  - A refused winner: the loser consumes nothing and waits 120 min, and only the winner schedules.
  - A start that finds the service running is not a refusal.

## 2. Main-thread disk waits

- Confirmed: `PrefsHubRestartStore.saveStart`, `PrefsAutoStopStore.markAutoStopped`, `wakeAttempted` and `setWakeProbe(true)` all `commit()`. They ran on the HubGraph scope, which uses `Dispatchers.Main.immediate`.
- `GlassesWatchdog` has a new `io` parameter (default `Dispatchers.IO`). `hubStarted` is now suspend: it updates the in-memory ladder and `lastStartAt` first, runs `withContext(io) { saveStart }`, then calls `startHub()` back on the caller thread.
  The loop calls `tickNow()`, which is serialised by a `Mutex`. `tick()` is now an internal `runBlocking` wrapper used by tests only. `hubStartAllowed` reads the in-memory `lastStartAt`, so a commit still in flight can never let a second attempt through.
- `AutoStopController` has a new `io` parameter. `evaluateNow()` is serialised by a `Mutex`. The stop record is committed with `withContext(io)` before `stopHub()`, and the wake claim runs on `io`. `evaluate()` is now an internal test wrapper.
- Tests:
  - `GlassesWatchdogTest`: two ticks back to back on a fake-main thread while the first is parked in its commit. Exactly one save then one start; the save runs off fake-main and the start on it.
  - `AutoStopControllerTest`: a recording store. Order is mark then stop, and attempt, probe, start; no commit runs on fake-main.
  - The virtual-time loop test passes `io = EmptyCoroutineContext`.
- Not changed: `ContextApp.onCreate` still calls `onProcessStart` on main. It commits there only when a rung is due, and it may wait on the claim lock while a worker is in its claim. `ContextHubService.ensureRunning(force)` calls `userStarted()` outside the claim lock.

## 3. VoiceMarksDocTest

- The README rewrite dropped the `<!-- voice-marks-readme:begin/end -->` block. The "Voice marks" section from before 63b6585d is restored as it was.

## 4. README privacy

- "Everything stays on the device" is replaced. The new text says data is stored on the phone (SQLCipher, key in the Keystore), the glasses and watch stream only to the user's phone, and a remote provider receives prompts. It also receives images when photos for Ask are on (`AskPhotoPolicy`). Local models stay on the device.

## 5. Release notes

- `pca-v1.0.0-rc1.md`: the removed battery-hook limitation is gone. The new line says replies never claim or deny the glasses' low-battery hold, a resume says "Your phone pause was cleared ...", and the glasses are named only when the push was accepted. This matches the final-fixes-x handoff and `Utterance.kt`.
- `pca-v0.9.9-beta.md`: the same correction ("cannot confirm glasses state" was wrong: the push acceptance is confirmed).
- `pca-v0.9.8-beta.md`: the preview migration count was "4 P1 and 5 P2" and is now "4 P1 and 2 P2 findings fixed" (`2026-10-29-preview-migration-handoff.md`).
- Other claims were checked against the code and the handoffs and hold: the watch messages dropped while the hub is down, no restart after a reboot, 11 fusion keys, the Room versions, AutoStop 5 to 120 min or Never with a default of 20, the 5/15/30/60 back-off, the 200 page size, the 1,000 and 200 caps (`RepoActivityData.RECENT`, `MarkRepository.MAX_ROWS`), the 10 s radius and 24 rebuilds.

## Tests

`:phone:testDebugUnitTest :services:voice:test`: 2152 phone tests, 2 failures on the full run. `LogContentScanTest` failed because the new `AutoStopWake` forwarder was not allow-listed; it is now listed like `AutoStopController`. `LocalModelsPageTest` is a known flaky test. Both passed on a rerun alone, together with `:services:voice:test` (including `VoiceMarksDocTest`).
