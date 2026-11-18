# Key-rotation rehearsal and androidTest run, 2026-11-18

Emulator only (`MyEmulator`, API 34, arm64, headless). The real devices were never addressed (`adb -s emulator-5554` everywhere, `ANDROID_SERIAL=emulator-5554` for Gradle). Branch `feat/v101-ask-eval`, HEAD `85986a44`. Keys were throwaway and live in `~/Desktop/rokid/scratch/rotation-rehearsal/` (outside the repo). The debug key was a COPY of `~/Desktop/rokid/backups/keys/debug.keystore`; the original was not touched and not printed. Build-tools 36.0.0.

## JOB A: release-key rotation (APK Signature Scheme v3)

**Result: rotation works.** `adb install -r` of the rotated APK over the debug-signed app succeeded with no uninstall, the app data directory and database files kept their inodes, and the encrypted database opened and the zone survived.

### Commands that worked

```
# one-off: new key and lineage (debug key is the old signer)
keytool -genkeypair -keystore release.jks -storetype PKCS12 -alias pca -keyalg RSA -keysize 3072 -validity 10000 -dname "CN=..." ...
apksigner rotate --out pca.lineage \
  --old-signer --ks debug.keystore --ks-key-alias androiddebugkey --ks-pass pass:android \
  --new-signer --ks release.jks   --ks-key-alias pca             --ks-pass pass:...

# sign the update (the flag set the design doc proposed, verified)
apksigner sign \
  --ks debug.keystore --ks-key-alias androiddebugkey --ks-pass pass:android \
  --next-signer --ks release.jks --ks-key-alias pca --ks-pass pass:... \
  --lineage pca.lineage --rotation-min-sdk-version 28 \
  --out app2.apk app-unsigned.apk
```

- The flag name `--rotation-min-sdk-version` is correct in build-tools 36.0.0 (`apksigner help sign` lists it).
- The first (old) signer is the debug key, `--next-signer` is the release key. apksigner also accepted the reversed order and signed with the release key as current signer; do not rely on that.
- `apksigner verify -v --print-certs` on the result: v3 true, v3.1 false, one signer, SHA-256 `ba4523...c649` (the new key). The lineage holds debug `61a831...a5a6` then new `ba4523...c649`.

### Is `--rotation-min-sdk-version` needed?

Yes, for any device below API 33. Without it (build-tools 36 default), `apksigner verify` shows two signer blocks: v3.1 for API 33+ with the NEW key, and a plain v3 block for API 24-32 signed with the OLD (debug) key and no lineage. On such a device the update would verify, but as a debug-signed app: no rotation happens there. With `--rotation-min-sdk-version 28` the APK carries a single v3 block with the lineage, and API 28+ devices apply the rotation. Without the flag on this API 34 emulator the update also installed and rotated (past signatures [debug, release]), so the difference only shows below API 33. **Not tested on a real below-33 device: the emulator image is API 34.** The glasses (minSdk 28, targetSdk 32) are such a device; the phone (minSdk 29) may be too. The checklist must test one.

Project minSdks: phone 29, watch 30, glasses 28, nexus-bridge 29. All at or above 28, so v3 lineage is supported on all of them.

### Rehearsal procedure and observations

APK 1: the project's `:phone:assembleDebug` (versionCode 4), re-signed with the debug key copy, v3 only. APK 2: the same code with `versionCode = 5` (temporary edit, reverted with `git checkout`; the file is clean), signed as above.

1. Fresh install of APK 1. Onboarding, then Settings > Memory > "Encrypt memory" on, force-stop and relaunch. `context.db` is migrated to an encrypted file (header is random bytes, new inode 385073, no "SQLite format" magic). `db_key.xml` (Keystore-wrapped key) is written.
2. Added pause zone "Rehearsal" by typed bounds (12.97/12.98, 77.59/77.60, buffer 300 m); granted location. Zones screen lists it. Place Guide pin was not exercised (not reachable without a location fix and map interaction on a headless emulator).
3. Before: `dumpsys package` signature `c48b4ca`, no past signatures, versionCode 4. App data dir inode 311952, `databases` 385038, `shared_prefs` 385049, `files` 385050, `no_backup` 385039.
4. `adb install -r app2.apk`: **Success**, no uninstall. After: versionCode 5, signatures `9e28d1e6`, past signatures `[c48b4ca flags 17, 9e28d1e6 flags 17]`. All five inodes identical, `context.db` inode 385073 unchanged, `db_key.xml` unchanged (mtime 13:09).
5. Launch of APK 2: no crash (crash log buffer empty, no AndroidRuntime/SQLite/KeyStore exceptions), the SQLCipher keyed-open log lines appear (the key was unwrapped from the Keystore and the encrypted file opened), Home, Activity, Settings > Memory load, the zones screen still lists "Rehearsal" with buffer 300 m. DB file stays encrypted (header unchanged).
6. Two honest limits: the zones live in SharedPreferences (`ZoneStore`), not the database, so the zone proves prefs and the data dir survive, not the Keystore; the Keystore evidence is that the encrypted DB opened (keyed-open log lines, no `file is not a database`, UI that reads the DB loads, `db_key.xml` untouched). I did not read rows out of the encrypted DB. Also, onboarding re-appears after every restart on this emulator because `OnboardingGuard` (BUG-R3-1) rejects "complete" when no chat endpoint is usable AND the DB is empty; that is the guard working, not a rotation effect.
7. The installed lineage flags `17` = installed-data + auth; rollback is NOT granted to the debug key (default). So the default already matches the revoke option of the doc's Q3 (the debug key cannot update a rotated install).

### Failure modes (all on the emulator, API 34)

| Case | Result |
|---|---|
| Install APK 2 (rotated) over APK 1 (debug) | Success, data kept |
| Install APK 2 on a device that never had APK 1 (fresh) | Success; signature `9e28d1e6`, past signatures `[debug, release]` come from the lineage |
| Reinstall APK 2 over APK 2 (same versionCode) | Success |
| Update installed rotated app with the **debug-signed** build, same versionCode 5 | `INSTALL_FAILED_UPDATE_INCOMPATIBLE: ... signatures do not match newer version` (debug key is not rolled back) |
| Same, `install -r -d` with debug-signed versionCode 4 | `INSTALL_FAILED_UPDATE_INCOMPATIBLE` (without `-d`: `INSTALL_FAILED_VERSION_DOWNGRADE`) |
| Update rotated app with an unrelated key, no lineage | `INSTALL_FAILED_UPDATE_INCOMPATIBLE` |
| Over a debug-signed install: APK signed by unrelated key | `INSTALL_FAILED_UPDATE_INCOMPATIBLE` |
| Over a debug-signed install: APK with lineage `other -> release` (lineage mismatch, debug not in it) | `INSTALL_FAILED_UPDATE_INCOMPATIBLE` |
| Over an already-rotated install: APK with that same wrong lineage | **Success**, and the stored past signatures became `[other, release]`, losing the debug ancestor. Only the current signer is compared there. Harmless to data, but a bad lineage published later silently rewrites what the device remembers; always sign with the one lineage file. |
| `apksigner sign` where the lineage tail is not the `--next-signer` key | Fails at signing: `IllegalStateException: Provided signer configs do not match the provided SigningCertificateLineage` |
| Rotation signed without `--rotation-min-sdk-version`, installed on API 34 | Success (v3.1 block used); on API 24-32 the v3 block has only the old key |

Rollback: with the default lineage capabilities, once a device has taken the rotated build the debug-signed build can no longer update it; only an uninstall (data loss) goes back. The pre-switch backup in the design doc remains the rollback.

### Not verified, needs the real devices

- A device below API 33 (glasses, and the phone if it runs API 29-32): check `getprop ro.build.version.sdk`, install the rotated APK over the debug one, confirm `dumpsys package` shows the new signature and past signatures.
- Wear Data Layer with phone and watch rotated: whether Play Services compares the current signer or the lineage (design doc UNVERIFIED stays).
- Rokid CXR signature checks (design doc UNVERIFIED stays).
- Keystore-held key survival on the real phone (Samsung/other StrongBox behaviour differs from the emulator).
- The Place Guide pin.
- AGP lineage DSL: not examined; the `tools/sign-release.sh` plan (post-`assembleRelease` apksigner) is confirmed workable.

## JOB B: androidTest suites on the emulator

Found (7 files): `services/memory` (`EncryptedMigrationV16to17DeviceTest`, `SqlCipherMigrationDeviceTest`), `phone` (`KeystoreTlsDeviceTest`), `services/ai` (`NomicReferenceDeviceTest`, `NomicVisionDeviceTest`, `RealNomicDeviceRuntime` helper), and a llama.cpp example under `services/ai/src/main/cpp/` (third-party, not part of the build). All Gradle runs went through the lock, offline, with `ANDROID_SERIAL=emulator-5554`; each run listed exactly one device (`MyEmulator(AVD) - 14`).

| Task | Result |
|---|---|
| `:services:memory:connectedDebugAndroidTest` | 11 tests: **4 pass, 7 fail**. Pass: `EncryptedMigrationV16to17DeviceTest.encryptedV16UpgradesToV17KeepingEverything`, `aWrongKeyCannotOpenTheEncryptedFile`, and from `SqlCipherMigrationDeviceTest`: `plaintextToEncryptedAndBackKeepsRowsAndSearch`, `theDeferredFactoryOpensAnEncryptedDatabaseOffMain`. Fail: `SqlCipherMigrationDeviceTest.encryptedDbMigratesTo8FromV1Shape` ... `V7Shape` (7 tests), all with `SQLiteException: duplicate column name: momentType` from `MIGRATION_12_13`. |
| `:phone:connectedDebugAndroidTest` | **5 of 5 pass** (`KeystoreTlsDeviceTest`) |
| `:services:ai:connectedDebugAndroidTest` | **0 tests ran**. `build.gradle.kts` sets no `testInstrumentationRunner` for this library module and both Nomic tests `assumeTrue` on a downloaded model bundle, so nothing executes without both. Not fixed. |

Note: Gradle's JUnit XML for the memory module folds all 11 tests under the first suite name (`EncryptedMigrationV16to17DeviceTest`, tests=11 failures=7); the split above comes from the per-test names.

**The 7 failures are a stale test, not an app bug** (recorded, not fixed). `SqlCipherMigrationDeviceTest` was written for Room v8 (commit `3130407c`, 2026-10-23): it builds a database at the CURRENT schema (now v17), drops the v8 tables/columns, and relabels `PRAGMA user_version` to 1-7, then expects the app to migrate to `version == 8`. Since v9-v17 the `moment` table already has `momentType` at that point, so `MIGRATION_12_13`'s `ALTER TABLE moment ADD COLUMN momentType` fails; the production path (real v16 -> v17) is covered by `EncryptedMigrationV16to17DeviceTest`, which passes. The suite needs rebuilding on the frozen schema JSON per version (`schemas/*.json`), and the assertion `version == 8` updated. Worth a ticket; not a release blocker.

Side effects on the emulator only: the phone app was installed, rotated, then uninstalled; the connected-test runs reinstalled the phone and memory test APKs. The emulator was stopped with `adb -s emulator-5554 emu kill`.
