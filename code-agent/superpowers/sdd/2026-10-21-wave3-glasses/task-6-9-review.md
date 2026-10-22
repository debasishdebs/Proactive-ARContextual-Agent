# Review: wave 3 glasses stream, Tasks 6-9 (dd2bac5..321695f, branch w3g/int)

Read-only review. No Gradle, no adb, no test runs. One pass over the review package; focused checks outside the diff are named under "Checks run".

## Verdicts

| Task | Spec compliant | Quality approved |
|---|---|---|
| 6 WorkRest persistence (S13) | yes | yes |
| 7 Hidden HUD + StartDecision (S21) | yes | yes (minor follow-ups) |
| 8 Exit classifier + phone text (S14, S20, rulings 2 and 5) | yes | yes (minor follow-ups) |
| 9 One-liners (S15) | no: A20 only partly done; D13/D26/D30 skipping needs confirming | no: needs the A20 fix |

## Spec compliance

- Task 6: matches S13 and R13. Snapshot `bc` key, the discard rule (`WorkRest.kt:47`: boot count decides when both are known, uptime when either is -1), lazy restore, saving only on change, and clearing once when disabled are all there. All required tests exist, including `rebootWithLongerUptimeDiscards`, `unknownBootCountFallsBackToUptime` and `disabledClearsStore`.
- Task 7: matches R14 and S21. `RefClass`, `StartDecision.line`, `decide`/`onStart` and `hudHidden` are set before `swipeTimer.post { minimise() }` (`MainActivity.kt:172-176`). The decision table uses a separate gate for each call. The `onAutoOpenMarker` path is unchanged.
- Task 8: matches S14 as amended by user ruling 2 (separate "unverified" category, wording "unverified") and ruling 5 (counts on the phone through additive Hello fields; `PROTOCOL_VERSION` stays 1). Stale-exit window, the pid-reuse check, gap from the exit timestamp, gap stored for Rokid only, and the count-only `restart rokid=N unknown=M other=K ...` line are all present.
- Task 9: A3-minor, A19, A21, A22 and A23 are done. **A20 is only partly done** (see I1). Not verifiable from the diff: the report says D13, D26 and D30 were "skipped by instruction". The plan's Task 9 table lists them, and only D36/D39 were moved (ruling 4). The controller should confirm that the phone stream owns D13, D26 and D30, otherwise they are Missing. The commit message also drops "canvas hardening".

## Strengths

- WorkRest saves only when `(workStartMs, pausedAtMs, restUntil)` changes (`WorkRest.kt` `persist`). The tick runs every 250 ms (`SamplingSession.kt:290`), so writes happen only on phase or block-reason changes. `noWritePerTick` checks this over 600 ticks. A disabled store is cleared at most once per process.
- Restoring a stale state can never hold capture for more than one rest period R. The existing clamp `until - now > restMs()` and the `now < pausedAtMs/workStartMs` resets cover a wrong restore (BOOT_COUNT -1 plus a long uptime). The worst case is a rest of at most R minutes, which the spec accepts.
- Privacy holds. `ExitFacts.description`, the pid, the caller name and the referrer package never reach a log or the hello. `refClassNeverLeaksPackage`, `restartCountsLineWrittenAndHasNoDescription` and `startLineWrittenToDiagLog` assert this. `Heartbeat.guarded` logs the class name only.
- `ProcCaller` parses stat correctly: field 22 is index 19 after the last `)`. Since kernel 3.17 the starttime is boot-based, so `now - elapsedRealtime` is the right base.
- The old phone tolerates the new fields (`Wire.kt:20` `ignoreUnknownKeys = true`), and a new phone reads 0 from old glasses (tested).
- `closesSummary` is shared by the glasses and the phone, so the wording cannot drift.

## Issues

### Critical
None.

### Important
- **I1 (Task 9, A20), `CaptureService.kt:303-308`:** only the `Heartbeat(...)` constructor runs inside `Heartbeat.guarded`. `settingsCache.readNormalized()`, `uplink.bytesSent` and `screenWakes.take()` run before the guard. A throw there still skips the `status` line at :324, which is exactly what A20 must prevent. `screenWakes.take()` also resets its counters before a possible throw. The plan's test (`CaptureServiceTest.heartbeatThrowStillPublishesStatus`, a fake heartbeat that throws) was replaced by a unit test of the helper (`HeartbeatTest.heartbeatThrowStillPublishesStatus`), so nothing proves the CaptureService behaviour. Fix: move lines 302-308 inside the `guarded { }` lambda. If a CaptureService seam is too costly, at least rename the test so it does not claim a publish.

### Minor
- **M1 (Task 6), `WorkRest.kt:47`:** when both boot counts are known and equal, the uptime check is skipped. In the same boot `savedAt > now` cannot happen, so adding `|| saved.savedAtElapsedMs > now` can never discard wrongly. It closes any case where BOOT_COUNT is read before the system increments it. This is defence in depth, cheap, and needs a one-line spec note.
- **M2 (Task 6), `WorkRestTest.kt`:** `unknownBootCountFallsBackToUptime` covers only saved=-1 / current=7. Add saved=7 / current=-1, and a both-equal case with `savedAt > now` if M1 is taken.
- **M3 (Task 7), `MainActivity.kt:299-330` with `hudHidden`:** `dispatchKeyEvent` → `handle()` votes on the presenter's Confirm whether or not the HUD is hidden. A key that is queued ahead of the posted `minimise()`, or that arrives while the HUD stays in front, could answer a Confirm the wearer cannot see. Fix: when `hudHidden.value` is true, pass keys to `super`, or let only the exit gesture through.
- **M4 (Task 7), `MainActivity.kt:415-419`:** `moveTaskToBack(true)`'s return value is ignored. If it returns false (for example in a lock-task launcher), the app stays in front, black (invisible on the glasses), and `minimised = true`. No `onStart` clears `hudHidden`. Keys still route, so the triple-tap exit works, but the app is stranded out of sight. Fix: clear `hudHidden` (and `minimised`) when it returns false. I found no other stranding path: every return to the front (launcher, Confirm `startActivity`, icon tap) goes through `onStart`, which clears the flag on a start that does not minimise. Voice and wake work at system level and are not affected.
- **M5 (Task 7), `MainActivityTest.kt` `killRestartStartDrawsHiddenHudBeforeMinimise`:** `ActivityScenario.launch` idles the looper, so the posted `minimise()` has already run. The test proves the HUD is hidden after the minimise, not that it was hidden before the first frame, which is what the plan asked for ("before idling the main looper"). It does fail on the old code, so it is not vacuous. Ordering is correct by construction (:173 runs before the post).
- **M6 (Task 7), `AutoStartGateTest.decisionTable`:** `want` copies the production expression word for word, so the 32-row loop checks only that `decide` equals `onStart` and that the reasons are echoed. It cannot catch a wrong rule. `namedRows` pins the five user-facing cases, which mitigates this.
- **M7 (Task 8), `RestartDiagnostics.kt:90-92`:** the `if (reason == ...)` guards inside `when (kind)` are dead code (a non-null kind already implies the matching reason). Remove them.
- **M8 (Task 8):** stale KDoc. `HubState.kt:34-36` still says "(last gap Xs)" and "Android cannot tell them apart", and `RestartDiagnostics.kt:16` still says "Only a forcestop exit is counted".
- **M9 (Task 8):** on devices upgraded from before wave 3, `rokid_closes` still holds the old count, which included all force-stops. The new count then mixes two meanings. Either note this in the device checklist or reset the key once.
- **M10 (Task 8, behaviour to confirm on the device):** an untrusted app usually cannot read another uid's `/proc/<pid>` (hidepid and SELinux). The AssistServer pid will then read as hidden, and in practice every Rokid stop becomes "unverified" while `rokid` stays 0. This follows S14 and ruling 2 as written. Task 10 step 9 should record it explicitly so nobody reads `rokid=0` as "Rokid never closed it".
- **M11 (Task 8), `HubTest.kt:361`:** `runCatching { runCurrent() }` swallows any exception the hub throws while it handles the hello. The other hello test calls `runCurrent()` directly. Remove the `runCatching`.
- **M12 (Task 8), `ClosesSummary.kt`:** "Rokid closed the app 0 times (3 other force-stops)" is technically right but awkward. Consider leading with whichever count is non-zero. Wording is a product call.
- **M13 (Task 9), `battery-run.sh:87`:** `grep -v` without `-a`. A diag file with a NUL or bad byte (for example a tail truncated by a kill) makes grep print "Binary file matches" instead of the lines, and `; true` hides it, so the pulled log silently comes out empty. Use `grep -a -v -i -E`. The same applies to the cpu.raw filter, which predates this change.
- **M14 (Task 9), `test-battery-run.sh:1825-1827`:** the `filter_diag` wiring check is a source scan. It is acceptable here, because a behavioural filter test sits beside it.
- **M15 (Task 9), `WearingStatus.describe`:** null and the raw string "unknown" both log as `unknown`. A raw "off" logs `off` while `parse` gives false. That is fine, but any new SDK value now shows only as `other`, so the logs can no longer reveal it (the trade-off the plan chose).
- **M16 (Task 9), `CameraXSamplingCamera.kt`:** a module-level mutable `fpsProvider` feeds the `Cache` lambda. Passing the provider into `get(...)` would be cleaner. There is no behavioural issue (main thread only).
- **M17 (process):** commit trailers read `Co-Authored-By: Claude Sonnet 5.5`, while the plan's global constraint asks for `Claude Opus 5.5`. Sonnet is accurate for the implementer, so this needs a controller decision, not a code fix.

## Checks run (named risks)

- **Battery (A3):**
  - WorkRest callers are `SamplingSession.kt:290` (each 250 ms tick) and `CaptureService.kt:268` (stop). Save on change means writes only on block-reason or phase transitions. `clear()` on an empty prefs file causes no disk write.
  - No new timers, coroutines or wakeups.
  - The heartbeat cadence is unchanged (`HEARTBEAT_MS` 30 s, the CaptureService heartbeat every 60 ticks).
  - The start diag line is written once per `onStart`.
  - `hudHidden` recomposes once per toggle. While hidden, HudContent's 1 s ticker is not composed.
  - An fps read that failed is retried once per bind, which costs one characteristics read.
- **Old log format:** a grep of the repo (code, tools, phone) for `restart gap`, `reason=forcestop` and `<other len` finds no parser. `battery-run.sh` reads `restartGapSec` from the heartbeat, which is unchanged.
- **Ledger force-add:** `.superpowers/` is in `.gitignore:12`. `git ls-files .superpowers` already tracks ledgers from battery-wave-a2 and wave-b, so this follows an existing precedent. The files hold only reports, no secrets or paths. They will land on main when merged; drop them before the merge if main should stay free of ledgers.
- **A22 deny filter:** `DENY` (`battery-run.sh:14`) is non-empty and includes deviceSecret, deviceKey, token, secret, password and DevCmdHelper. With `|AssistServer` added it covers the logcat list. run-as stderr goes to `/dev/null`. The test proves that denied lines are dropped and good lines kept.
- **A21:** `batt.log` is always `ARGV[1]` (`battery-run.sh:184`). `summary_minutes` has no `fileno` routing.
