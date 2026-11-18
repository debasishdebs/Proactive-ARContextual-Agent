# PCA release signing

Status: DRAFT, not exercised. Planned for after Sri Lanka (1.3.0 lineage). Design: `docs/superpowers/specs/2026-11-16-privacy-and-release-design.md` (B2). Rehearsal evidence: `docs/superpowers/reviews/2026-11/key-rotation-rehearsal-2026-11-18.md`.

Rules: PKCS12 release key in GitHub Actions secrets, signer SHA-256 pinned in `release/signer-sha256.txt`, CI builds a DRAFT release, publication is manual. No debug-signed APK ever goes on GitHub. Never commit key material (`.jks`, `.keystore`, `.p12`, `.pem`, lineage files are all kept out of the repo).

## One-time key and lineage (on your machine, outside the repo)

```
keytool -genkeypair -keystore release.jks -storetype PKCS12 -alias pca -keyalg RSA -keysize 3072 -validity 10000 -dname "CN=..." ...
apksigner rotate --out pca.lineage \
  --old-signer --ks debug.keystore --ks-key-alias androiddebugkey --ks-pass pass:android \
  --new-signer --ks release.jks   --ks-key-alias pca             --ks-pass pass:...
```

`debug.keystore` is a copy of `~/<private backup dir>/keys/debug.keystore`. Keep `release.jks`, its password and a second backup somewhere safe and offline: losing the key means no more updates for installed apps.

1. Put the new certificate SHA-256 (64 hex) in `release/signer-sha256.txt` and commit it.
2. Create the repository secrets (names only here):
   - `PCA_RELEASE_KEYSTORE_B64` = `base64 < release.jks`
   - `PCA_RELEASE_KEYSTORE_PASSWORD`
   - `PCA_RELEASE_KEY_ALIAS` (`pca`)
   - `PCA_LINEAGE_B64` = `base64 < pca.lineage`
3. Rehearse the CI signing form once on an emulator. The workflow uses one signer (the new key) plus `--lineage pca.lineage --rotation-min-sdk-version 28`, because CI never holds the debug key. The rehearsal verified the two-signer form (`--ks debug.keystore ... --next-signer --ks release.jks ... --lineage ... --rotation-min-sdk-version 28`); the single-signer form was NOT run. Check that `adb install -r` over a debug-signed build succeeds and `dumpsys package` lists past signatures `[debug, release]`.

## Run

Push a `v*` tag or run the workflow manually (input `tag`, optional `with_bridge`). It checks the pin and that versionCode rose over the previous `v*` tag, builds `:phone` and `:glasses` release (and optionally `:nexus-bridge`), signs, verifies (one signer equal to the pin, not the debug key, v3 present, no v3.1 block, not debuggable), writes `SHA256SUMS` and `PROVENANCE.txt`, and creates a DRAFT release. You publish it by hand.

## Verify a downloaded APK

```
sha256sum -c SHA256SUMS
BUILD_TOOLS=$ANDROID_HOME/build-tools/36.0.0 release/verify-release.sh pca-phone-1.3.0.apk com.rokid.context
```

It is read-only: not debuggable (aapt2 dump badging), signer SHA-256 equals the pin, v3 present and no v3.1 block, prints package and versionCode. apksigner does not print the lineage; confirm it on a device (past signatures below).

## Rotation procedure

Phone (`com.rokid.context`, minSdk 29):

1. Back up first (the design doc's pre-switch backup: `run-as` copy of the database, `db_key.xml`, prefs, photos). Debug builds only; `run-as` stops working on non-debuggable builds.
2. `adb install -r pca-phone-<v>.apk` over the debug-signed app. NEVER uninstall: that destroys the encrypted database and its Keystore key.
3. Check: `adb shell dumpsys package com.rokid.context` shows the new signature and past signatures `[debug, release]`, versionCode up; launch, and verify the database opens (Home and Memory load, marks, Moments, asks and photos present).

Glasses (`com.rokid.context.glasses`, minSdk 28, below API 33): the APK must carry `--rotation-min-sdk-version 28`. Without it, API 24-32 sees a v3 block signed by the old key with no lineage, so no rotation happens (the emulator at API 34 hides this). Not tested on a real below-33 device: run `adb shell getprop ro.build.version.sdk`, `install -r`, then `dumpsys package` on the glasses. The phone may also be below 33; check it the same way.

Do it after Sri Lanka, not during the trip (install freeze applies).

## Caveats

- Watch: shares applicationId `com.rokid.context` with the phone and the same signing key. The watch is not in this workflow; if it is rotated, do it with the phone in the same step. The Data Layer (`/pca/motion`) check on whether Play Services compares the current signer or the lineage is UNVERIFIED.
- Rokid CXR signature checks (glasses/phone link) are UNVERIFIED; test the paired chain after rotating both.
- Keystore-held database key survival on the real phone (StrongBox differs from the emulator) is unverified.
- A wrong lineage published later silently rewrites an already-rotated device's remembered past signatures; always sign with the one lineage file.

## Rollback limits

With default lineage capabilities the debug key is not rolled back: a debug-signed build cannot update a rotated install (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`, also with `-d`). The only way back is uninstall, which loses data. The pre-switch backup is the rollback, and there is no import for it yet, so treat rotation as one-way.

## Release build smoke

2026-11-18, `./gradlew --no-daemon --offline :phone:assembleRelease` (under the lock): BUILD SUCCESSFUL in 4m 16s, 397 tasks, output `phone-release-unsigned.apk` (build dir, git-ignored, not copied; unsigned, so not installable as is). Only Kotlin warnings (e.g. FlowPreview opt-ins). `:glasses:assembleRelease` and `:nexus-bridge:assembleRelease` were NOT tried. The behaviour of the DEBUG=false sites from the design (HubGraph probe, PreviewSection, CaptureService line 185, MemoryExport.productionBuild) is a runtime matter and is not exercised by a build; the device checklist in the design still applies. No app code was changed.
