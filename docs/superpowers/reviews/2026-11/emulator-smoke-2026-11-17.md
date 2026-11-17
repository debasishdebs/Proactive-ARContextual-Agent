# Emulator smoke, 2026-11-17

Branch feat/v101-ask-eval at 3fde50eb. Emulator `MyEmulator` (Android 34 google_apis arm64, headless), every adb call targeted `emulator-5554`. No real device touched. Debug APKs built from HEAD with `:phone:assembleDebug :glasses:assembleDebug` (phone 12:00 build; glasses APK was up to date).

## Verdict

| Goal | Result |
|---|---|
| 1 Migration v16 encrypted -> v17 | PASS (indirect evidence, see limits) |
| 2 Phone feature smoke | PARTIAL, one crash bug (Pause zones screen) |
| 3 Glasses APK launch | PASS |

## BUG 1 (real, not fixed): Settings > Privacy > Open pause zones crashes the app

Repro: any fresh state, Settings > Privacy > scroll > "Open pause zones". Process dies immediately. Reproduced twice: 320x640 @160dpi and 1080x2400 @420dpi (so not a small-screen artefact), with and without a stored zone not needed (zero zones).

```
FATAL EXCEPTION: main  Process: com.rokid.context
java.lang.IllegalArgumentException: maxHeight(-34) must be >= than minHeight(0)   (-39 on the 1080x2400 run)
  at androidx.compose.ui.text.TextPainterKt.drawText-TPWCCtM(TextPainter.kt:228)
  at ...zones.ZonesScreenKt.drawGrid(ZonesScreen.kt:291)
  at ...zones.ZonesScreenKt.ZoneMap$lambda...(ZonesScreen.kt:253)
```
Likely cause (read, not verified by a fix): `drawGrid` latitude loop starts at `floor(br.lat/step)*step`, which is at or below the bottom edge, so `y + 1f` can exceed the canvas height and `drawText(measurer, ..., Offset(2f, y+1f))` gets a negative max height. The longitude labels use `size.height - 14.sp` and lines with x up to the edge, which may fail the same way on the right edge. Fix idea for the owner: skip labels whose offset is outside the canvas, or pass an explicit `size` to drawText. Consequence: the pause-zone UI (add zone by typed bounds, state chip) is unreachable, so that part of goal 2 could NOT be exercised through the UI. PcExit afterwards shows `reason=4` (crash) in the exit history.

## Goal 1: migration

1. Installed `backups/apks-v100-final-candidate-20261113/pca-phone-debug-1.0.0.apk` (versionName 1.0.0, versionCode 4).
2. Finding: the DB is NOT encrypted by default. After onboarding, `databases/context.db` had the plain header `SQLite format 3` and `PRAGMA user_version = 16` (read from a pulled copy). Encryption is opt-in: Settings > Memory > "Encrypt memory", applied after a force-stop and relaunch.
3. Turned Encrypt memory on, force-stopped, relaunched: `context.db` header became random bytes (`12a8 4822 76f7 ...`), no `SQLite format 3` string. This is the real encrypted DB with a Keystore key.
4. `adb install -r` of the HEAD debug APK (same package, same debug signature, versionCode 4 equal). Result Success; `context.db` kept the same inode (328279) and size 688128, header still ciphertext, wal grew from 57712 to 98912 bytes after launch (the app opened and wrote to it).
5. After the upgrade: no `FATAL`, no Migration, Room, `SQLiteException`, `InvalidKeyException` or SQLCipher errors in logcat. The only SQLCipher lines are `W Long monitor contention ... SQLiteOpenHelper.getWritableDatabase()` (parallel opens at startup, warnings). A new `places.db` (PlacesDatabase v1) was created at first launch. Settings > Diagnostics rendered DB-backed numbers (`Backlog 0 - none`, `Keyframes per hour: 0`, scene clusters, photos covered) so reads through the encrypted Room worked.
6. Later reboot and relaunches reopened the DB fine (wal 304912 bytes at the end).

Limits: `user_version` and rows cannot be read under SQLCipher, so "Room is at v17" is inferred (v16 before, the new build declares `version = 17` in ContextDatabase, Room opened and wrote with no migration exception). The DB held no memories or frames (no glasses, no AI endpoint), so "reads existing rows" is not proven beyond empty-table stats. The pre-upgrade sequence was not exercised with data rows.

Side observation (by design, not a bug): `OnboardingGuard` re-runs onboarding on every launch when no AI endpoint is usable and the DB is empty (BUG-R3-1 defence), even after the wizard marked itself complete. Ollama with an unreachable address ("Can't reach that address") did not count as usable.

## Goal 2: phone smoke (HEAD build)

- App launch, onboarding, Home, Activity: OK, no crash.
- Settings > Place Guide: opens (it is Developer-visible, shown after turning Developer mode on): shows "0 places in the pack (0 KB)", S/W/N/E fields, "Seed this area", "Seed Hyderabad (Charminar, Golconda)" preset. No network exercise.
- Settings > Diagnostics > Developer card: renders ("Developer mode" switch, "Shows Experiment canvas in Settings..."). Turning it on reveals "Experiment canvas" and "Keep canvas pairing".
- Diagnostics also showed a Context hub card: `State UNKNOWN`, counters all 0.
- Hub: after launch `ContextHubService isForeground=true foregroundId=31 types=0x10` (0x18 once location permission was granted); log `PcWatchdog watchdog hub down, starting it`. `PcExit n=3 ... exempt=n hubLive=y`. No `start_refused` or `hub_recover` line appeared.
- BOOT_COMPLETED: as shell (non-root) the broadcast is refused (`SecurityException ... not allowed to send broadcast`). With `adb root` on the emulator it is delivered, but not to a force-stopped app (expected Android behaviour, no log). After a real emulator reboot `HubBootReceiver` started the process (`Start proc ... for broadcast HubBootReceiver`), logged `PcExit n=0 exempt=n hubLive=y` and `PcPipeline released=0 at process start`. No hub FGS was running afterwards (no ServiceRecord), no crash, no explicit refusal line.
- Pause zones (seeded, since the UI is crashed): wrote `shared_prefs/pause_zones.xml` via run-as with one zone around 17.3616,78.4747 (buffer 300 m) while the app was stopped. Relaunch log: `PcZone zone pause added state=INSIDE reason=NO_FIX`, Home shows `Paused` and Privacy shows an active pause "On phone - until you resume". This is the fail-closed behaviour (zone present, no fix = INSIDE). `emu geo fix 78.4747 17.3616` (four times, system `last location` confirmed 17.3616,78.4747) produced no state change in 60 s+, so INSIDE/APPROACH/clear transitions were NOT observed: the app uses Play Services fused location (TripLocation) and no fix reached it on the emulator, cause unknown (not investigated further). Repeating `PcZone zone publish failed cls=IllegalStateException` lines are the sink to the glasses with no glasses link (retried every tick, expected).
- State chip text was not seen (screen crashes, see Bug 1).

## Goal 3: glasses APK

`com.rokid.context.glasses` 1.0.0, minSdk 28, installed on the same emulator. `am start .MainActivity`: process stays alive, HUD draws (REC, battery 100%, NEXT), then the camera permission dialog appears; no FATAL. `PcPause pause loaded active=0 src=none`. CXR and Rokid hardware paths were not available and not tested (`AuthorizationHelper ... Hi rokid app not available` appears on the phone side, expected).

## Could not be verified here

Real glasses pairing/CXR, Bluetooth, camera capture, hub FGS start under a background-start exemption, zone transitions with real GPS, the pause-zone screen, v17 row-level data after upgrade, Place Guide seeding (needs network and AI), any battery or thermal behaviour.

## Cleanup

Emulator stopped with `adb -s emulator-5554 emu kill`; `wm size/density` reset; no background processes left. Only this file was committed.
