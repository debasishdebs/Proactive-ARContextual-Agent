# Emulator smoke: train12 046bc165 (2026-11-20)

Emulator only (emulator-5554, AVD MyEmulator, started with -wipe-data; Android 14, 320x640). APKs: backups/apks-v110-train12-046bc165 (phone sha256 d528ff0e19d3...; versionName 1.0.0 code 4). Screenshots: /private/tmp/claude-501/-Users-debasishkanhar/5f738bb7-228a-40ad-8dd1-9f72f0041163/scratchpad/emu-smoke2/*.png

| # | Check | Pass 1 fresh | Pass 2 upgrade (old installed base -> install -r train12) |
|---|-------|---|---|
| 1 | No FATAL EXCEPTION / ANR / app StrictMode, 90 s launch | PASS (0) | PASS (0) |
| 1 | force-stop + relaunch; `am kill` + relaunch | PASS (both cold starts, pid alive) | not repeated; launch + 90 s PASS |
| 1 | wifi off, data on, airplane on/off, wifi on | PASS (app alive, 0 fatal) | not repeated |
| 2 | pca.log: app start milestone / PcDiag network / http / lat-lon pairs / email-like | 3 / 9 / 0 / 0 / 0 | 1 / 1 / 0 / 0 / 0 |
| 3 | shared_prefs names | see below; Model privacy "Automatic (default)", check `model privacy mode=missing` PASS | same |
| 4 | tools/post-install-check.sh --phone emulator-5554 --apks train12 | PASS=28 WARN=1 FAIL=1 UNKNOWN=2 | PASS=28 WARN=1 FAIL=1 UNKNOWN=2 |
| 5 | Upgrade: launches, no migration crash, db user_version=17, Settings / Data saver / Model privacy render | n/a | PASS (sha256 and signature match staged; same cert as old build, install -r succeeded) |
| 6 | Screenshots Home, Settings, Data saver, Model privacy | PASS | PASS (up-*.png) |
| 6 | Place Guide pins screen / pin editor "Needs a Moment" | NOT REACHED: not found under Settings (checked Data sources, Memory); skipped per brittle-UI rule | not reached |

post-install-check non-PASS (identical both passes): FAIL `phone runtime perms 0 granted, 10 denied` (expected, test denied/never granted); WARN battery exemption not whitelisted (emulator); UNKNOWN detailed log heartbeat (ran under 70 min); UNKNOWN glasses not checked (no glasses serial). Forbidden shapes: coordinate-like pairs=0 http=0 @=0.

shared_prefs created (fresh, after onboarding use): ai_seed, deletion_epoch, endpoint_keys, frame_adoption, hub_restart, hub_settings, legacy_import, memory_graph, memory_settings, onboarding, pause_zone_state, persona_settings, proactive_shadow. NOT present: data_saver.xml, ai_backend.xml, ai_backend_init.xml, pc_dev.xml, place_pins (absent = defaults, nothing written until the user changes a setting). After upgrade: same set minus proactive_shadow. files: logs, profileInstalled, queue_runtime.log, rokid_archive, rokid_inbox; databases: context.db.

## Excerpts / observations (no failures)
- `PcDiag app start cold process sdk=34 uptimeS=155 tzOffsetMin=330`; `PcZone zone publish deferred reason=no_link` every cold start (earlier smoke saw "publish failed IllegalStateException"; now a clean deferral); `PcMoments cadence rebuilt: stored=0 deleted=0 rejected=0`.
- logcat StrictMode `NonSdkApiUsedViolation ... getUserId` is from com.google.android.gms.persistent (pid not ours); AndroidRuntime lines are from the adb `monkey` helper only.
- Onboarding reappears on every cold launch: onboarding.xml stays an empty `<map />` even after reaching "Open Home" (same on fresh and upgraded install; pre-existing behavior of the build under test, worth confirming whether completion is meant to persist on a real device).
- Old installed build has no Data saver / Model privacy screens; after upgrade both appear, Data saver "Wi-Fi and home mobile data (default)", Model privacy "Automatic (default)".
- Old build created no user data beyond onboarding/db/persona prefs (no photos/pins), so migration of real rows is not exercised.

Emulator shut down, Gradle lock released. No real device touched.
