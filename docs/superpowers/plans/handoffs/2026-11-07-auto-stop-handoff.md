# AutoStop handoff (2026-11-07, branch feat/v099-watch)

User ruling 2026-11-07: with the glasses off-head or disconnected the phone's background hub must stop by itself, and start again when the glasses are put on or the app is opened. Frozen: PROTOCOL_VERSION 1, TRACE_SCHEMA 1, plan hash f5782e0d8610, Room v15. Glasses app untouched. No device check (no adb).

## What changed
- `BackgroundPolicy` (pure): `autoStop(AutoStopInput)`, `away(link, worn)`, `AUTO_STOP_DEFAULT_MIN = 20` (N), range 5..120 and `AUTO_STOP_NEVER = 0`, `FRONT_HOLD_MS = 10 min` (M: app foreground or tapped Reconnect), `wakeDue`, `WAKE_LADDER_MS` (60/120/240 min), `stoppedNote`.
  - Present = link reaches the glasses and wear is not `false` (wear unknown with the link up counts as present). Away = link down (OFFLINE, PAUSED_BT, NOT_AUTHORIZED, CONNECTING) or `worn == false`. Stop when away >= N min AND not in front AND no front/Reconnect in the last M min AND no ask in flight AND no active collect session (user-started run).
  - `hubNeeded` no longer takes a context source: `activeWork || (!manualStop && !autoStopped && enabled && paired)`. Watch/phone sources do NOT keep the hub; Context sources card says "Watch signals pause while the glasses are off."
- `AutoStopController` (HubGraph, process lifetime): sleeps to the exact due time, re-evaluated on link/wear change, front, setting change. Stop = `ContextHubService.stop` (service onDestroy -> `Hub.stop`: link close cancels reconnect/probe/relauncher; watchdog loop sees hub not needed). Log tokens: `auto_stop_not_worn`, `auto_stop_disconnected` (content-free). WorkManager 15 min jobs untouched.
- `AutoStopStore` (prefs `hub_auto_stop`, excluded from backup/transfer in backup_rules.xml and data_extraction_rules.xml, ruled in BackupRulesTest): manual stop, auto-stop time and reason, wake ladder.
- Manual stop wins: `ContextHubService.onDestroy` while running and not asked by the app (`stop()` sets `expectedStop`) = the user stopped it from Android; remembered until a user start (app opened = `ensureRunning(force)`, tapped Reconnect/notification). `ensureRunning(force)` clears manual stop, auto stop and wake ladder.
- Setting: Settings > Capture > "Stop when the glasses are away" (5 min .. 2 h, Never), stored in `CaptureTimingStore` (`auto_stop_min`, already excluded from backup; value clamped 5..120, 0 stays Never). Card "Run in the background": explains, button fires `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` only on tap (manifest permission added; Play policy may question it for store builds, fine for the sideloaded rc). A test pins that only that card builds the request.
- Truthful UI: `HubState.autoStopNote` wins over the watchdog note in `GlassesStatus.note`: the existing disconnect banner reads "Stopped: glasses not worn since 14:05. Tap to start" (or "not connected"); its button is Reconnect, which starts the hub.

## Restart paths: exactly what works
1. App opened (activity resume, foreground start): WORKS always. Clears manual and auto stop.
2. Tapped Reconnect / banner "Tap to start" / "Open on glasses" notification: WORKS (foreground or user-initiated; `force`).
3. Battery-exemption wake (only when the user granted the exemption on the card, never a manual stop, setting not Never, link enabled+paired): (a) process already alive: `AutoStopController` timer; (b) cold process started by the platform (a 15 min WorkManager job, a Data Layer watch message): `AutoStopWake.onProcessStart` in `ContextApp.onCreate`. Ladder 60, 120, 240 min (never a loop, persisted); a woken hub looks for the glasses 5 min (`WAKE_PROBE_MS`) and stops again if away; seeing the glasses present resets the ladder. Android 12+ may still refuse: logged `auto_stop wake refused`, state stays stopped. Not verified on a device.
4. Glasses put on / connect events while the hub is stopped: DOES NOT EXIST, nothing invented. Wear status comes only from the Rokid CXR session (`onWearingStatusChanged`, watcher snapshots) which exists only while the hub holds the link; there is no manifest receiver, no Rokid broadcast, no ACL/BLE scan in the code, and the glasses app cannot start the phone app. So "restarts when the glasses are put on" is true only when the hub is running or the wake ladder fires (with exemption) or the user opens the app. Without the exemption a worn pair of glasses is not noticed until the user opens the app or taps the banner.
5. Watch messages while the hub is down: still dropped (`hub_down`), by ruling (watch signals pause while the glasses are off). The listener may start the process, which only triggers path 3(b).
- The watchdog never restarts an auto-stopped hub (`hubNeeded` false); its own persisted ladder and 60 s minimum are unchanged for an unexpectedly dead hub.

## Tests
`BackgroundPolicyTest` (hubNeeded matrix, away matrix, durations around N, custom N and clamp, Never, ask/run/front/Reconnect holds, wake conditions and ladder, banner text), `AutoStopControllerTest` (gating, reasons, presence resets, setting change, no exemption no wake, ladder, refused wake no loop, manual stop/Never/no link stop the wake, user start), `AutoStopWakeTest` (prefs persistence, setting range and reload), `BackgroundRunCardTest` (card model, intent only for this app, request built only in the card, choices), BackupRulesTest updated for `hub_auto_stop`. LogContentScanTest allow-lists the controller's forwarder.

## Open items
- Banner copy for a manual stop is the existing "Glasses disconnected / Reconnect".
- A system low-memory service destroy is indistinguishable from a manual stop (onDestroy without our stop): it is treated as manual until the app is opened.
- Wake cost: with the exemption, at worst a 5 min probe per ladder step (1 h, 2 h, 4 h cap).
