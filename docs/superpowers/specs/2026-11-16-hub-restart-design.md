# v1.1 WP: hub comes back after process death (+ AutoStop leftovers)

Status: DESIGN (2026-11-16). Branch `feat/v101-ask-eval`. Paths are relative to `android-companion/`; `hub/` = `phone/src/main/java/com/rokid/context/phone/hub/`.
Evidence: `~/Desktop/rokid/backups/logs-20261116/` (`phone-pca.log`, `phone-pca-1220.log`, `glasses-capture_diag.log`). There is no phone system logcat for 15 Nov.

## 1. Root cause of the 15 Nov failure

**What the logs show**

| Time | Line | Meaning |
|---|---|---|
| 21:58:50 | `PcRelaunch relaunch: link MINIMISED->CLOSED_ON_GLASSES` / `attempt in 8 s` | glasses app closed, a reopen is queued |
| 21:58:58 to 22:14:00 | `relaunch: blocked (call active) audioMode=3 callState=2 ... for=901 s` | the call guard works as intended (`GlassesRelauncher.kt:243`) |
| 22:14:20 | `IndexMaintenance text index ...` | last line from the old process |
| 22:15:33 | `PcPipeline released=0 at process start` (`KeyframePipeline.kt:197`) | a new process |
| 22:15 to 01:18 | 17 × `at process start`, 13 × `PcCtx watch drop reason=hub_down`, 9 Moment cadences, 10 QueueDrain runs, **0 × PcWatchdog, 0 × PcRelaunch** | the process kept being started, but nothing built the hub |
| 01:18:33 (16 Nov) | `PcWatchdog watchdog hub down, starting it` | the user opened the app (`HubActivityHook.kt:48`, `force = true`) |

The 22:19 heartbeat (`BLOCK_HEARTBEAT_MS` = 300 s) never arrived, so the process died between 22:14:20 and 22:15:33. No `ContextHub manual_stop` line was logged (`ContextHubService.kt:146`), so `onDestroy` never ran: the process was killed, the service was not stopped. **Why it was killed is unknown.** The phone app does not log its own `ApplicationExitInfo`, and there is no system log for that time.

**Why nothing restarted the hub**
1. Only `HubGraph.get` (`HubGraph.kt:28`) builds the hub, and the watchdog, the relauncher and AutoStop are built inside it (`HubGraph.kt:256-286`). It is reached from the UI, the service's `onStartCommand` (`ContextHubService.kt:85`), Reconnect/banner, or a wake.
2. A process start only submits `AutoStopEvent.ProcessStarted` (`ContextApp.kt:103`). Its reducer returns `Claim.NONE` unless the hub had *auto-stopped* (`AutoStopMachine.kt:194`). A hub killed with its process is neither auto-stopped nor manually stopped, so no path covers this state. The 17 process starts after 22:15 were all wasted.
3. `START_STICKY` (`ContextHubService.kt:117`) did not bring the service back. If `onStartCommand` had run, the logs would show one of these: watchdog ticks within 150 s (hub built), `foreground (...) refused` (`:133`), or nothing at all, from the silent `stopSelf` at `:80`. Since `hubNeeded` was true (enabled, paired, no auto or manual stop; `BackgroundPolicy.kt:72`), the silent path is unlikely, but the logs cannot rule it out. Why the platform did not restart the service is unknown.

**Why the pending relaunch was lost.** The relauncher's state lives only in memory: `step`, `attempts`, `job` and `blockedSince` (`GlassesRelauncher.kt:100-114`),; the attempt loop is a hub-scope coroutine (`:171-192`). Only the stand-down is saved (`PrefsStandDown`, `:57-67`). The process death removed the loop. The new process never built a relauncher, so it never saw the call end. Even when a relauncher exists, it acts only on a *transition* into `CLOSED_ON_GLASSES` (`:124`).

## 2. Design

### 2.1 A "hub expected" flag, checked at every process start
- Add `hubLive: Boolean` to `AutoStopSnapshot` (`AutoStopStore.kt:15`). The engine sets it on `HubForeground` (`AutoStopMachine.kt:98`). It clears it on a new `ExpectedStopped` event, sent from `onDestroy` when `expectedStop` is true (`ContextHubService.kt:146`), and on an auto stop or a manual stop. A process kill leaves it set.
- New branch in `processStart` (`AutoStopMachine.kt:192`): `hubLive && !autoStopped && !manualStop && linkAllowed && !serviceRunning` triggers `Recover(via)`, which calls `ensureRunning(wake = true)` with `wakeProbe = true`. A recovered hub with no glasses then stops after `WAKE_PROBE_MS` (5 min), not after N. Recovery is rate-limited by the persisted watchdog ladder (`GlassesWatchdog.kt:195,273`: at least 60 s apart, then 5/15/30/60 min).
- Log: `hub_recover attempt|started|refused (via, exempt=…)`.

### 2.2 Triggers and their cost

| Trigger | Status | Extra wakes | Android 12+ background FGS start |
|---|---|---|---|
| Process start from existing jobs or Data Layer messages | new branch (2.1) | 0 (uses starts that already happen: 17 in 3 h on 15 Nov) | only with the battery exemption (`AutoStopWake.kt:19-22`) |
| `BOOT_COMPLETED`, `MY_PACKAGE_REPLACED` | new phone receiver; today `BootReceiver` only re-arms reminders (`services/connectors/.../BootReceiver.kt:23-38`) | 1 per boot or update | listed as exempt in the platform docs: verify on device |
| Bluetooth `ACTION_ACL_CONNECTED` for the paired glasses | new manifest receiver | 1 per BT connect, no polling | the docs list Bluetooth broadcasts as exempt: verify on device |
| CompanionDeviceManager presence | not in v1.1 | 0 (system scan) | exempt, but needs a pairing dialog |

### 2.3 Resuming after a call
- Save the pending close as `{closedAtMs, why}` and the hourly `attempts` in `relaunch_state` (the existing prefs, `GlassesRelauncher.kt:65`). A rebuilt relauncher keeps the 6/h cap (`:252`) across process deaths, and logs `relaunch: resumed pending close (age …)`.
- The hub itself may restart during a call (Q3). Reopening the glasses app still waits for `callIdle()`, which needs 15 s of settle time after the call ends (`:349`). There is still no install or launch during a call.
- When the rebuilt link reaches `CLOSED_ON_GLASSES`, the existing transition path (`:124`) reopens the app after the call.

### 2.4 Wear and connect events restarting a stopped hub
The wear signal exists only inside a running Rokid session (`AutoStopWake.kt:24`). A stopped hub cannot see it, so this WP does not promise wear-based restarts. The BT ACL connect of the paired glasses is the observable event. It counts as a positive signal: it resets the ladders, and when the hub is `autoStopped` and not manually stopped it starts a probe hub (5 min). Wear is then checked inside the session, and AutoStop decides again. This changes the 2026-11-07 rule that wakes need the battery exemption (Q2).

### 2.5 No 12 wakes/h when no glasses are around
Three periodic 15-minute jobs run all the time: FrameIngest (`OrchestrationScheduler.kt:41`), QueueDrain (`:59`) and MomentCadence (`:247`). Every process start also runs index maintenance (`ContextApp.kt:97`). Proposal:
- On an *expected* stop (auto, manual or watchdog), cancel FrameIngest and QueueDrain when the inbox and the queue are empty, and swap MomentCadence for one catch-up run. Re-enqueue them on hub start (`ContextHubService.kt:87`) and on a frame being stored (`HubGraph.kt:102`).
- A *lost* hub (`hubLive` still set) keeps its jobs, which are its recovery chances (2.1).
- Expected result with the hub stopped and no glasses: about 0 periodic wakes/h, plus the AutoStop wake ladder (60/120/240 min, `BackgroundPolicy.kt:142`) when exempt.

## 3. Glasses self-restarts (20:41, 21:17): what to log next time
What is known: `forcestop_unverified` means the newest `ApplicationExitInfo` had `REASON_USER_REQUESTED` (a force-stop) and recent timestamps, but its caller pid was missing, unreadable or reused (`ExitClassifier.kt:20-26`, `RestartDiagnostics.kt:86`). The diag log stopped 75 s and 103 s before the restarts (20:40:02, 21:15:40) with no shutdown line. `killedTraces=1` at both boots shows that a trace was recording, but recording was on for the whole session, so it does not point to a cause. Who called force-stop is unknown.

Log next time:
1. Glasses, at process start, for the last 3 exit records (`getHistoricalProcessExitReasons(pkg, 0, 3)`, `GlassesRuntime.kt:240`): reason, status, importance, pss/rss, timestamp. Also why the caller is unverified (`no_pid|proc_hidden|pid_reused`) and the pid number. Still never the description text.
2. Glasses: `onTrimMemory` levels and `usedMB` once a minute in `icon` mode.
3. Phone: the same exit-record line for its own process at start, plus `exempt=` and `hubLive=`. This would have explained 22:15.
4. Phone: a log line at `ContextHubService.kt:80/81` naming the branch and whether it was a sticky start (null intent).
5. Device procedure: run `adb logcat -G 16M` on the glasses at session start and collect `logcat -b main,events` (`am_proc_died`, `am_kill`, ActivityManager `Force stopping ... from pid`) so the force-stop caller is captured.

## 4. Work breakdown (Sonnet-sized)

| # | Task | Files | Tests | Acceptance |
|---|---|---|---|---|
| T1 | Phone exit diagnostics + exemption log at process start | new `hub/PhoneExitDiagnostics.kt`, `ContextApp.kt:103` | unit: formatter over fake `ExitFacts` (no description in output) | one `PcExit` line per cold start |
| T2 | Log the silent `onStartCommand` branches | `ContextHubService.kt:75-82` | Robolectric: `ContextHubServiceTest` with the `hubNeededNow` seam, null intent | each branch logs once, with `sticky=` |
| T3 | `hubLive` flag + `Recover` branch | `AutoStopStore.kt`, `AutoStopMachine.kt:98,192`, `AutoStopController.kt` (log lines), `ContextHubService.kt:146` | unit: reducer cases for lost hub, manual stop, auto-stopped (unchanged), refused, rate limit; prefs round-trip | the reducer emits `StartWake` only for a lost hub; old snapshots load with `hubLive=false` |
| T4 | Boot/update receiver | new `hub/HubBootReceiver.kt`, `phone/src/main/AndroidManifest.xml` | Robolectric: the broadcast submits `ProcessStarted("boot"\|"update")` | after reboot with `hubLive`, `hub_recover attempt (boot)` (device check) |
| T5 | Save pending relaunch and attempts | `GlassesRelauncher.kt:57-67,100-114,195` | unit: `GlassesRelauncherTest` with fake prefs (cap holds across a rebuild; resumed log; call still blocks) | no 7th reopen within an hour across a process death |
| T6 | BT ACL connect trigger | new `hub/GlassesAclReceiver.kt` (matches the paired address), manifest, `AutoStopMachine.kt` (new `GlassesNearby` event) | Robolectric: wrong address ignored; manual stop is never overridden | ACL connect while auto-stopped gives a probe hub (device check) |
| T7 | Stop periodic jobs on expected stop | `services/orchestration/.../OrchestrationScheduler.kt:41-69,245-276`, `ContextHubService.kt:87,146` | `work-testing`: cancelled after an expected stop with empty queues, kept for a lost hub, re-enqueued on start | `dumpsys jobscheduler`: no 15-minute PCA jobs while stopped |
| T8 | Glasses exit diagnostics (3.1, 3.2) | `glasses/.../RestartDiagnostics.kt`, `ExitClassifier.kt`, `GlassesRuntime.kt:236-245` | unit: sub-cause classifier | new fields in the `restart` line |

Order: T1, T2, T8 (diagnostics, low risk), then T3, T5, T4, T6, T7. Robolectric cannot test real FGS limits. T4, T6 and T7 each need a device check on the user's phone, never during a call.

## 5. Risks and open questions

Risks: (a) without the battery exemption, recovery from process starts is refused on Android 12+. It is then logged, but the hub stays down. (b) A recover loop if the process keeps dying at start: bounded by the persisted ladder (2.1). (c) The BT and boot exemptions are taken from the platform docs, and OEMs differ: the T4 and T6 device checks decide. (d) T7 makes Moments for frames that already arrived wait until the next hub start.

Questions for the user:
1. **Battery exemption:** is it granted on your phone? After the 03:54 auto stop on 16 Nov, 11 process starts produced no `auto_stop wake` line. That suggests it is not granted (an inference; T1 will confirm). Should setup ask for it, since recovery depends on it?
2. **BT connect ends an AutoStop without the exemption** (2.4): accept this change to the 2026-11-07 ruling?
3. **Hub recovery during an active call** (the glasses reopen still waits): allow it, or wait for the call to end?
4. **Background jobs while stopped** (2.5): cancel them fully, or keep a 60-minute fallback?
