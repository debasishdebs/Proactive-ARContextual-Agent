# Emulator trip rehearsal and smoke 4, 2026-11-18

Branch feat/v101-ask-eval. Build under test: `~/Desktop/rokid/backups/apks-v110-d9eb0f47/` (phone-debug.apk, glasses-debug.apk). Old build: `apks-v100-final-candidate-20261113/pca-phone-debug-1.0.0.apk` (versionCode 4). Emulator `MyEmulator` (API 34, headless, wiped with `-wipe-data` at the start, 1080x2400 @420dpi via `wm`, reset at the end). Every adb call was `adb -s emulator-5554 ...`; `adb devices` showed only the emulator the whole time; no real device was addressed. No app code was changed, nothing pushed/stashed/tagged. Emulator stopped with `emu kill` (no qemu process left).

## Verdict

| Part | Result |
|---|---|
| 1 Install rehearsal, encrypted DB path | Installer ran end to end up to the log scan, then FAILED with exit 1 on a false positive (finding B1, important for the real run). Backup, install -r, encrypted DB reopen all fine |
| 2 Smoke 4 | No crash, no ANR, no PCA StrictMode. One build-content problem (B2: the Offline map card is not in the final APK), one installer false positive (B1), minor notes |

## Part 1: install rehearsal (encrypted DB)

Setup: v1.0.0 phone APK installed, onboarding, Settings > Memory > Encrypt memory ON (hint "Turns on after Proactive Context restarts"), force-stop and relaunch: `context.db` header became random bytes (`9afa 951d 65dc ...`, not `SQLite format 3`), `shared_prefs/db_key.xml` present (1334 bytes). Data: an offline typed Ask ("I don't have anything on that yet - I'll look again in your next photos" shown; v1.0.0 stores no row for it, Activity showed Answers 0, so the encrypted DB held only schema plus app state; real rows could not be written because the DB is encrypted and v1.0.0 has no zones or Place Guide).

Two-device limit: the script refuses equal serials (`REFUSED: phone and glasses serials are equal`, exit 2) and requires `--glasses` without `--self-test` (`REFUSED: --glasses <serial> is required`). Only one emulator was run (8 GB host, a second 5 GB AVD was impractical), so I used the documented `--self-test` mode (phone only, emulator only). In that mode the encrypted-DB checks are NOT skipped unless `--allow-plaintext-db` is given, and I did not give it, so the header and `db_key.xml` validation ran. NOT exercised: the glasses install step of the script, the real-serial identity/alias logic against two distinct devices (only the 98 built-in fixtures, all passed), the signature check on a real phone (it matched on the emulator: `signature matches installed (SHA-256 61a83182...)`), real call-state guard (emulator reports 0).

Dry run (`--self-test --phone emulator-5554 --apks .../apks-v110-d9eb0f47 --allow-emulator`), exit 0, nothing written:

```
fixtures: 98 passed, 0 failed
device identity (preflight): all supplied serials are distinct physical devices
mode: DRY-RUN tag=trip-20261118-1727 self-test=1
phone: phone-debug.apk package=com.rokid.context versionCode=4 sha256=97e08b43...
phone: installed versionName=1.0.0 versionCode=4 -> new versionCode=4
phone: signature check will run with --go (dry-run pulls nothing)
call guard (preflight): every mCallState is 0
disk preflight: app data ~1588 KB, host free >= 4764 KB (3x) for backup dir and temp dir
STEP backup: ... -> .../backups/pca-phone-pre-trip-20261118-1727.tar (0600; validated with tar -t, listing, size, header, sha256)
STEP install phone: adb -s emulator-5554 install -r phone-debug.apk
STEP verify phone: clear logcat, launch com.rokid.context, wait 60s, report counts of FATAL / Migration / Room / SQLCipher errors
DRY-RUN complete: nothing was changed or written.
```

Real run: same flags plus `--tag emu-rehearsal-20261118 --go`, WITHOUT `--allow-plaintext-db`:

```
signature matches installed (SHA-256 61a83182...)
call guard (backup): every mCallState is 0
app process confirmed gone (pidof: no process)
backup OK: .../backups/pca-phone-pre-emu-rehearsal-20261118.tar bytes=1369088 entries=38 wal/shm=2 context.db=688128 sha256=73258ff6...
previous phone APK kept: .../pca-phone-pre-emu-rehearsal-20261118.apk sha256=6685d58e...
device identity (before phone install): ... distinct;  call guard (before phone install): ... 0
phone install: Success
phone: running=yes
phone log scan: running=yes fatal_count=0 migration_room_sqlcipher_error_count=34
FAILED: phone: 34 Migration/Room/SQLCipher error lines     (exit 1, phase=installed; printed the restore steps)
```

Backup validation (tar -t, db and `db_key.xml` listed, size multiple of 4096, encrypted header, sha256) passed; `install -r` passed. The tar and APK of this rehearsal are left in `~/Desktop/rokid/backups/` (names contain `emu-rehearsal`; emulator data only).

### B1 (MEDIUM, installer; will likely hit the real phone too) The 60 s log scan false-fails on an encrypted DB

The 34 "errors" are not errors: 31 lines `D sqlcipher: ERROR MEMORY sqlcipher_mlock: mlock() returned -1 errno=12` (SQLCipher's debug line when mlock is not allowed, printed on every connection open) and 3 lines `Long monitor contention with owner arch_disk_io_0 ... net.zetetic.database.sqlcipher.SQLiteConnectionPool...` (a 100 ms lock wait on first open). The scan pattern `mre` in `tools/install-trip-build.sh` (line 433) contains the bare word `SQLCipher` and is matched with `grep -iE`, so any encrypted-DB open matches. Independent checks over the whole log: `FATAL EXCEPTION` 0, `ANR in` 0, no `Migration didn't`, `SQLiteException`, `not a database`, `InvalidKey`, `Room` error. The app was running and the DB kept ciphertext (header `9afa 951d ...`), WAL grew 32992 to 86552 bytes, `db_key.xml` untouched (mtime unchanged).

Effect on the trip: after a successful install the script would exit 1 ("FAILED ... phase=installed"), print the restore recipe, and SKIP the glasses install. Whether a real phone prints the mlock line depends on its RLIMIT_MEMLOCK (the same SQLCipher build prints it on this Android 14 image); it was not verified on hardware. Suggested fix (not applied, tooling is outside this task): drop bare `SQLCipher` from `mre` or exclude `sqlcipher_mlock` and `Long monitor contention`; keep `not a database`, `Migration didn.t`, `SQLiteException`, `file is not a database`, `InvalidKey`. Until fixed, if the real run ends with exactly this signature, do NOT restore: check `adb -s <phone> logcat -d | grep -iE "Migration didn.t|SQLiteException|not a database|InvalidKey"` and the app opening, then install the glasses APK by hand.

### Upgraded app opens the encrypted DB

After the script: `context.db` same size/inode, ciphertext header, `db_key.xml` present; app started (Welcome screen again: the known OnboardingGuard behaviour with no AI endpoint), Home, Activity (Today tiles), Settings load; Settings > Memory > Encrypt memory still ON; the Pause zones screen exists (new build). No Room/Migration error. The v17 schema version itself cannot be read under SQLCipher (same limit as smoke 2). Because there were no rows, "data present after the upgrade" was NOT verified (see "could not verify").

Glasses APK (outside the script): `adb -s emulator-5554 install` of `glasses-debug.apk` (`com.rokid.context.glasses`, 1.0.0 vc 4) succeeded, `MainActivity` started and stayed alive 12 s (only `flora-cli connect failed: Connection refused`, expected without Rokid OS), no FATAL. It does not replace the phone app (different package).

## Part 2: smoke 4 on the final build (d9eb0f47 APK, upgraded in place from the encrypted v1.0.0 state unless noted)

| Item | Result |
|---|---|
| Add zone by typed bounds | "Charminar" S 17.3600 N 17.3630 W 78.4730 E 78.4760 buffer 300: list card; first state `zone pause added state=INSIDE reason=NO_FIX` (fail closed) |
| Fix inside (`emu geo fix 78.4747 17.3616`, location permissions `pm grant`ed, no restart needed this time) | chip `Inside: capture is paused`, `pause_zone_state.xml` memo INSIDE with `lastFixWallMs` about 50 s old; Home `Paused` / `Resume capture`; glasses side: none attached (the glasses sink logs `zone publish failed cls=IllegalStateException` as in smoke 2/3), so "nothing on the glasses" was NOT observable |
| Ask offline in the zone | typed Ask answered `Paused: you're in a pause zone` |
| Pin a place here inside | red text "A pause zone is active or near, or its state is unknown: no place can be pinned here."; `PcPlace pin refused zone`; no dialog |
| Place Guide Seed Hyderabad (real network; host reaches Wikimedia, ping ok) | 17:35:32 to 17:37:23 (about 1 min 50 s): `seed done cells=12 fetched=11 skipped=0 failed=1 places=40`; UI `40 places in the pack (17 KB)`, `40 seeded, 0 fetched, 0 pinned`; Wikipedia hits per cell 4 to 72 kept 0 to 9. Overpass failed on every cell (`overpass failed cls=PlaceHttpException` x10, once `SocketTimeoutException`, `osm=0`, `complete=false`): the host itself also cannot reach `overpass-api.de` (curl timeout 20 s from the Mac), so OSM enrichment is unavailable from this network; the app degrades as designed (Wikipedia/Wikidata places kept). Requests carry only the cell box and the app User-Agent (read in `PlaceSources.kt`/`PlaceHttp.kt`; no body capture was done) |
| Pack size and budget | `0.0 MB of 50 MB used` (17 KB rounds to 0.0), budget picker 25/50/100/200, choosing 25 gave `0.0 MB of 25 MB used`, `PcPlace budget set evicted=0` |
| Move outside (`geo fix 78.5200 17.4000`) | chip `Outside, 6.2 km from the box`; `zone pause ended n=1` |
| Pin a place here outside | dialog opens (Name, Kind, Radius 60, Part of, Note); typed `Rehearsal_pin`; rotated to landscape and back with the dialog open: dialog and text SURVIVE (smoke 2 finding 1 is fixed); Save: `Saved.`, card `Rehearsal_pin - Monument, 60 m`, `PcPlace pin created count=1` |
| Offline map card | NOT PRESENT (B2) |
| Settings > Suggestions (shadow) | master switch OFF, Developer replay OFF, Place recall switch greyed/off; driving line `Unknown: no recent motion data: suggestions are paused`; Counters `None yet.` |
| Diagnostics > Developer card | Developer mode switch (turned on by tapping it), `Pause zones also pause Ask`, Record raw glasses sensors Off, Keep canvas pairing Off, Photo search dimension test (debug); Context hub card `State UNKNOWN`, counters 0 |
| Activity | Today tiles; Sort menu (Latest first, Oldest first, A-Z, Z-A); with 6 seeded memories (ids `rokid_reh1-6`, inserted into an unencrypted fresh install of the same APK while the app ran) the Memories list shows 6 rows (`Today 17:28` ...), the Evening timeline shows them, `See all moments` opens Moments with `List | By time`; By time: `Today · 6 moments`, `Collapse day`, `No activity 18:00-24:00`, hour rows `17:00 2`, `16:00 3`, `15:00 ...`, `Expand hour`. Landscape rotation in By time: no crash. Seeded MARK-type `moment` rows (6) did not appear in the Marks section or counts (the memory rows did); not investigated (my insert may lack columns the Marks query needs) |
| Reboot (`adb reboot`, encrypted state) | `sys.boot_completed` 1; app process came up on its own at 17:44:17, about 9 s after `BOOT_COMPLETED` was posted (`PcExit n=0 exempt=n hubLive=n`); first watchdog tick `verdict=Off why=no_pair`, `watchdog hub down, starting it`; zone memo restarted fail-closed (`zone pause added state=INSIDE reason=NO_FIX`), then `zone pause ended n=1` 15 s later from the emulator's last location; Diagnostics Context hub `State UNKNOWN`, counters 0 (no glasses). Process-from-boot on the emulator says nothing about a real phone's background FGS rules |

### B2 (MEDIUM, build content) The final trip APK does not contain the "Offline map" card

The Pause zones screen on the installed d9eb0f47 APK shows Map, Draw a box, New zone, Your zones, Preset and no Offline map card (checked top to bottom). Reading the APK directly: none of its 35 dex files contains the strings `OfflineMap`, `OfflineMapCard`, `PMTiles`, `AndroidMapFiles` or `offline_none`, while the source at d9eb0f47 does (`ZonesScreen.kt` references `OfflineMapCard`, `SettingsDeps.kt:186` builds `OfflineMapDeps`, commits 09a9f2c2, 6808791d, ba611c64 are ancestors of d9eb0f47), and other late code is present (`Pause zones also pause Ask`, `Suggestions (shadow)`, `Place recall`, `proactive_shadow`). So `backups/apks-v110-d9eb0f47/phone-debug.apk` (README says "all APK builds green at this commit") appears to be built from a tree older than the offline-map commits, or a stale incremental build. Impact for the trip: the map has no tile option (plain grid), which the checklist treats as optional, but the README/checklist claim "later: offline map tiles" for this folder. Check with `git`/gradle before the real install: rebuild `:phone:assembleDebug` at HEAD and compare, or confirm which commit the folder really holds. Not fixed here.

### Other notes (low)

- N1 (INFO) After an Ask was typed once, Home showed `Paused` with `Resume capture` with no zone and no pause switch of mine; log `ContextHub: phone pause added` at that moment. I cannot exclude my own tap (the soft keyboard shifts the layout and the Pause button sits at the top); it was resumed from Home and did not recur. Not reproduced, not claimed as a bug.
- N2 (INFO) The "Pin a place here" red refusal text stays on screen after moving outside until the screen is re-entered (it still read refused while the zone memo already said OUTSIDE); tapping the button then worked. Cosmetic/stale state.
- N3 (INFO, known) Welcome/onboarding appears at every cold start without an AI endpoint (OnboardingGuard), which also hid the first screen after each reboot or force-stop.
- N4 (INFO) The Bluetooth-permission banner overlays the lower part of every scrollable screen (the last item of the Pause zones page sits right above it); harmless here.
- N5 (INFO) Log noise: 31 `sqlcipher_mlock` lines per DB open burst; ORT telemetry `UnsatisfiedLinkError` (caught, WARN) at every process start.

## Logs

Logcat sweeps (`-b all`) before the reboot and after: `FATAL EXCEPTION` 0, `ANR in` 0. `StrictMode` 41 matching lines, all pid 1763 (a system process `FinalizerDaemon`, `SqliteObjectLeakedViolation` of a system cursor), none from `com.rokid.context`. No Room/Migration/SQLiteException/InvalidKey lines. No tokens, keys, pairing codes or user text appear here (the encrypted-prefs keyset that `cat` printed from `proactive_shadow.xml` is not reproduced).

## Could not be verified here (needs the real devices)

Encrypted DB upgrade with real rows on the real phone (the emulator DB held no rows; Keystore survival, real-size migration time); the installer's glasses step, real serial identity/alias refusals with two physical devices, the real signature check and the real call guard; whether the real phone prints the `sqlcipher_mlock` line (B1); background hub start from boot and Bluetooth, real GPS transitions, the glasses side of a zone (mark refused, pause published), Place Guide card on the HUD, pins "fresh fix" on a real phone, the Offline map with a real PMTiles file (and B2: which APK carries it); real OSM (Overpass) enrichment from a network that can reach it; photo-backed Frames, marks (MARK moments seeded here did not show), Ask answers from a real AI endpoint, battery/heat.

## Cleanup

Emulator stopped with `adb -s emulator-5554 emu kill`; `wm size`/`density` reset, auto-rotate on. No timers or background processes left. Host files left: `~/Desktop/rokid/backups/pca-phone-pre-emu-rehearsal-20261118.tar` and `.apk` (emulator data).
