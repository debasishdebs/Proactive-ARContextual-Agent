# Trip build: install and field-test checklist (prepared 2026-11-18, trip: Charminar 22 Nov, Ramoji 23 Nov)

Nothing is installed yet. Phone = eval build 394667e0, glasses = 1.0.0, watch = 1.0.0-rc1. The install helper is `tools/install-trip-build.sh` (Codex READY at e1337bea; emulator-tested only). Rollback and backup details: `v17-upgrade-recovery.md`. Full device checks: `device-checklist-2026-11-17.md`.

## 0. Decide / confirm first (5 minutes)
- [ ] Build to install: the FINAL verified folder `~/Desktop/rokid/backups/apks-v110-d9eb0f47/` (git d9eb0f47; do not use an older folder). Production code differs from `apks-v110-4f110dca` (later: offline map tiles, Suggestions shadow/delivery code, all OFF by default).
- [ ] What is ON in this build: hub restart fix, pause zones (you add zones), Place Guide (OFF until you switch it on), Ask date-window/last-state fixes, Activity pagination, Ask zone fence (ON). What is OFF: forgetV2 and multi-select Forget, Nexus bridge, Try-it, Suggestions (all switches).
- [ ] Room v17 migration (adds `ask.sourceStates`) runs on first phone launch. It was verified on the emulator (encrypted v16 -> v17). A phone backup is mandatory; there is no downgrade (a v16 binary cannot open a v17 DB: rollback = restore the backup + reinstall the same-signature APK).
- [ ] Watch: skip for the trip (the watch APK shares the phone's applicationId; installing it on the wrong serial replaces the phone app).

## 1. Preconditions
- [ ] No call active (the helper also refuses). Phone plugged in or > 50% battery, glasses > 50%.
- [ ] `adb devices -l`: note the PHONE serial and the GLASSES serial (a device can appear twice: USB serial and a wireless `adb-...-tls-connect` endpoint: pass ONE serial per device; the helper resolves identities and refuses aliases).
- [ ] Phone unlocked, USB debugging authorized, glasses awake and connected on USB or Wi-Fi adb.
- [ ] Free disk space on the Mac >= 3x the phone app data size (the helper checks).

## 2. Dry run (changes nothing: no backup, no pull, no install)
```
cd ~/Desktop/rokid/proactive-context-agent
tools/install-trip-build.sh --phone <PHONE_SERIAL> --glasses <GLASSES_SERIAL> --apks ~/Desktop/rokid/backups/apks-v110-d9eb0f47
```
Read the output: device identities distinct, APK package names correct, versionCode not a downgrade, call state 0, the exact backup path, the install order. Anything that says REFUSED: stop and tell Claude.

## 3. Real run (only with the user's explicit go)
```
tools/install-trip-build.sh --phone <PHONE_SERIAL> --glasses <GLASSES_SERIAL> --apks ~/Desktop/rokid/backups/apks-v110-d9eb0f47 --go
```
Order enforced by the script: preflight -> phone backup (force-stop, tar incl. -wal/-shm, validate, sha256) -> phone install (`install -r`, never uninstall) -> 60 s log scan (FATAL / Migration / Room / SQLCipher counts) -> glasses install -> log scan. Exit 0 = installed and verified, exit 3 = installed but NOT verified (do the manual checks below), exit 2 = refused (nothing changed), other = failure (follow the printed rollback steps; never uninstall the phone app).

## 4. Post-install checks (about 20 minutes, with Claude)
Phone:
- [ ] App opens, onboarding does not wipe anything, Home/Activity show your existing asks and memories; Settings > Memory shows encryption still ON.
- [ ] Settings > Capture schedule: background exemption still granted; indexing battery level as before.
- [ ] Settings > Privacy > Pause zones opens (no crash); add a zone around your own street (typed bounds); chip shows Outside; add a tiny zone around where you stand: chip Inside, Home shows Paused, glasses mark photo refused; delete it. (Zones cannot be removed while inside: step out first.)
- [ ] Ask a question about today: it answers; ask "what time did I last ..." style question: it states the last state seen with the time.
- [ ] Reboot the phone: after boot the process starts (check Settings > Diagnostics for the hub state; background FGS start may need the app opened once: record what you see).
Glasses:
- [ ] Glasses app starts, pairs, capture runs; double-tap pause/resume; voice mark + undo; mark refused inside a zone.
- [ ] Wear check: take them off for 10+ minutes: capture stops (not-worn fallback).
- [ ] Connect/disconnect the case: the hub recovers within a few minutes (Settings > Diagnostics logs), and the glasses are not re-launched during a call.

## 5. Place Guide prep for 22 Nov (needs internet, before leaving the house)
- [ ] Settings > Place Guide (Developer mode on): leave the switch OFF while seeding. Seed this area: Hyderabad preset (Charminar/Golconda; 17.34-17.41 N, 78.38-78.52 E). Check the pack size and count shown.
- [ ] Pin Golconda sub-places you care about with 'Pin a place here' ON SITE (needs a fresh fix; pins stay on the phone and are never backed up).
- [ ] Switch Place Guide ON only when you are at the first monument; the card shows at MEDIUM/MAX HUD level; offline it shows name + distance 'nearby, not checked'.
- [ ] Confirm zones: remove any test zones; add a zone only where you must not capture (Jarawa preset is not relevant to this trip).

## 6. Freeze and fallback
- [ ] After 21 Nov: only fixes for things found on your devices. Keep `~/Desktop/rokid/backups/apks-v100*` (1.0.0) for glasses rollback.
- [ ] If the phone misbehaves on the trip: Settings > Pause (stops capture), then restore per `v17-upgrade-recovery.md` section 4 only if the DB fails to open; otherwise leave it.

## What Claude has NOT verified on real hardware
Background hub start from boot/Bluetooth, real GPS zone transitions, camera/glasses card rendering, battery and heat over a day, the encrypted DB upgrade on YOUR phone (the emulator test used a seeded encrypted DB), and the installer's real-device refusal paths.
