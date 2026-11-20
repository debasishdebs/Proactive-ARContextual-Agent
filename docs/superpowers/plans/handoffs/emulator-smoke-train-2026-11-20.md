# Emulator smoke: v1.1 train APKs (2026-11-20)

Emulator only (emulator-5554, AVD MyEmulator, Android 14 / SDK 34). APKs from backups/apks-v110-train-166d3fd4 (phone versionName 1.0.0 code 4, sha256 matches staged). Phone and glasses APKs both installed (uninstalled first on the emulator for a true fresh install). Screenshots: /private/tmp/claude-501/-Users-debasishkanhar/5f738bb7-228a-40ad-8dd1-9f72f0041163/scratchpad/emu-smoke/*.png

| # | Check | Result |
|---|-------|--------|
| 1 | Fresh install + launch, 60 s: FATAL / AndroidRuntime / ANR / StrictMode in logcat | PASS (none) |
| 1 | PcDiag / PcData startup (DiagProbe, data saver graph, workers) | PASS, no exception |
| 2 | files/logs/pca.log via run-as; "app start" milestone; DiagProbe network line | PASS: `PcDiag app start cold process sdk=34 ...`, `PcDiag network online=true validated=true metered=true roaming=false transport=cellular` |
| 3 | force-stop relaunch, `am kill` (after HOME) relaunch | PASS: both COLD starts, no FATAL/ANR/StrictMode, `PcData net cost=FREE` after restart; ai_backend.xml absent (mode=missing = AUTO), UI shows "Automatic (default)" |
| 4 | Network toggles (wifi off, data on, airplane on/off, wifi on) | PASS: app alive, no exception; lines changed as below |
| 5 | tools/post-install-check.sh --phone emulator-5554 --apks <train folder> | PASS=28 WARN=1 FAIL=1 UNKNOWN=2 (see below) |
| 6 | Screenshots: onboarding, Home, Settings, Data saver, Model privacy | PASS, all render, no crash |

## Network lines during toggles (pca.log)
```
PcData net cost=OFFLINE      / PcDiag network online=false validated=false metered=false roaming=false transport=none
PcData net cost=HOME_MOBILE  / PcDiag network online=true validated=true metered=true roaming=false transport=cellular
PcData net cost=FREE         / PcDiag network online=true validated=true metered=false roaming=false transport=wifi
```
(also a transient `validated=false` cellular line right after airplane-off, then validated=true).

## post-install-check
FAIL: `phone runtime perms 0 granted, 10 denied` (expected: nothing granted per the test brief). WARN: battery exemption not whitelisted (emulator). UNKNOWN: detailed log heartbeat (app ran under 1 min), glasses checks (script refuses identical phone/glasses serials; glasses not checked). Everything else PASS: sha256 and signature vs staged APK, no fatal/ANR/db errors, db user_version=17 integrity ok, data saver and model privacy (mode=missing), log forbidden shapes 0.

## Observations (not failures)
- `PcZone zone publish failed cls=IllegalStateException` (info line in pca.log, at startup, every cold start; likely no zone/hub state without a paired glasses). Not a crash; worth a look if place zones matter on device.
- Early run on the pre-existing AVD data showed `EndpointSeeder AI seed rejected the legacy config: IllegalArgumentException(kind=TRANSIENT)` (old 11-19 install data, not reproduced on the fresh install).
- Launch immediately shows the BLUETOOTH_CONNECT runtime dialog over the onboarding; `am kill`/force-stop while that dialog was up left the stale dialog without a live process until tapped (artifact of the test, not a crash).
- UI: Data saver screen shows "Now: Wi-Fi", default "Wi-Fi and home mobile data", "I'm abroad" off, "Small requests on roaming" on, Blocked today counters 0. Model privacy shows "Automatic (default)". Home shows "Glasses offline" banner (expected, no glasses).
- Not exercised: blocked-mark/parking restart with real blocked entries (counters were 0 since no photos), glasses app runtime.

Emulator shut down (`emu kill`), Gradle lock released. No real device touched.
