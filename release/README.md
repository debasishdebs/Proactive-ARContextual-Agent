# PCA release signing

Status: DRAFT. The signing form was rehearsed on an emulator with throwaway keys (2026-11-19); the real CI run has not happened. Planned for after Sri Lanka (1.3.0 lineage). Design: `docs/superpowers/specs/2026-11-16-privacy-and-release-design.md` (B2). Rehearsal evidence: `docs/superpowers/reviews/2026-11/key-rotation-rehearsal-2026-11-18.md`.

Rules: PKCS12 release key in the secrets of the GitHub Environment `release` (NOT repository secrets), signer SHA-256 pinned in `release/signer-sha256.txt`, CI builds a DRAFT release, publication is manual. No debug-signed APK ever goes on GitHub. Never commit key material (`.jks`, `.keystore`, `.p12`, `.pem`, lineage files are all kept out of the repo).

## One-time key and lineage (on your machine, outside the repo)

```
keytool -genkeypair -keystore release.jks -storetype PKCS12 -alias pca -keyalg RSA -keysize 3072 -validity 10000 -dname "CN=..." ...
apksigner rotate --out pca.lineage \
  --old-signer --ks debug.keystore --ks-key-alias androiddebugkey --ks-pass pass:android \
  --new-signer --ks release.jks   --ks-key-alias pca             --ks-pass pass:...
```

`debug.keystore` is a copy of `~/Desktop/rokid/backups/keys/debug.keystore`. Keep `release.jks`, its password and a second backup somewhere safe and offline: losing the key means no more updates for installed apps.

1. Put the new certificate SHA-256 (64 hex) in `release/signer-sha256.txt` and commit it.
2. Create the GitHub Environment (repository owner, Settings > Environments) named `release`:
   - Required reviewers: the user (the sign job waits for approval).
   - Deployment branches and tags: restrict to `main` and the tag pattern `v*` (the workflow additionally enforces `^v[0-9]+\.[0-9]+\.[0-9]+(-[A-Za-z0-9.]+)?$` and that the commit is reachable from `main`). Also protect `main` and add a tag ruleset for `v*`.
   - Put the secrets in that ENVIRONMENT, not in repository secrets (repository secrets are readable by any workflow code a write collaborator can run). Names only here:
     - `PCA_RELEASE_KEYSTORE_B64` = `base64 < release.jks`
     - `PCA_RELEASE_KEYSTORE_PASSWORD`
     - `PCA_RELEASE_KEY_ALIAS` (`pca`)
     - `PCA_LINEAGE_B64` = `base64 < pca.lineage`
3. Rehearse the CI signing form on an emulator before the first real release (checklist, none done yet):
   - [x] Done 2026-11-19 (`docs/superpowers/reviews/2026-11/ci-signing-rehearsal-2026-11-19.md`, API 34 emulator, throwaway keys, release-variant APK). The CI form signs with one signer (the new key) and v3 only: `--lineage pca.lineage --rotation-min-sdk-version 28 --v1-signing-enabled false --v2-signing-enabled false --v3-signing-enabled true --v4-signing-enabled false --debuggable-apk-permitted false`. It differs from the earlier two-signer rehearsal (`key-rotation-rehearsal-2026-11-18.md`).
   - [x] `adb install -r` of the CI-form APK over an old-key install succeeded with data preserved, and `dumpsys package` listed past signatures `[old, new]`.
   - [x] `release/verify-release.sh` passed on the CI-form APK (with `PIN_FILE` pointing at a throwaway pin; the committed pin is still a placeholder).
   - [ ] Still open: a real CI run, the protected environment, the real keys, a device below API 33 (glasses).
   - Rehearsal notes: use a RELEASE-variant APK as input. `--debuggable-apk-permitted false` makes apksigner fail on a debug build (`APK is debuggable ... refuse to sign`), which is the intended guard. `zipalign -c -P 16 4` fails on the current release APK (native libs not 16 KB aligned); the workflow's `|| zipalign -c 4` fallback passes. To run `verify-release.sh` locally before the real pin exists, set `PIN_FILE=/path/to/throwaway-pin.txt`.

## Run

Push a tag matching `^v[0-9]+\.[0-9]+\.[0-9]+(-[A-Za-z0-9.]+)?$` or run the workflow manually from `main` (input `tag`, optional `with_bridge`). Two jobs: `build` (no secrets, `contents: read`) checks the versionCode rose over the numerically previous valid `v*` tag, builds `:phone` and `:glasses` release (and optionally `:nexus-bridge`) unsigned and uploads them. `sign` (environment `release`, needs reviewer approval, `contents: write`) first checks the commit is an ancestor of `origin/main`, downloads the build artifacts (it never rebuilds), checks the pin, signs, verifies with `release/verify-release.sh` (one signer equal to the pin, not the debug key, v3 present, no v3.1 block, not debuggable; any tool failure or empty output fails), writes `SHA256SUMS` and `PROVENANCE.txt`, and creates a DRAFT release. You publish it by hand.

## Verify a downloaded APK

```
sha256sum -c SHA256SUMS
BUILD_TOOLS=$ANDROID_HOME/build-tools/36.0.0 release/verify-release.sh pca-phone-1.3.0.apk com.rokid.context
```

Fixture self-tests with fake tools: `release/test-verify-release.sh`. It is read-only and fails closed (missing or failing aapt2/apksigner or empty output is a failure): not debuggable (aapt2 dump badging), signer SHA-256 equals the pin, v3 present and no v3.1 block, prints package and versionCode. apksigner does not print the lineage; confirm it on a device (past signatures below).

## Supply chain

Actions are pinned to full commit SHAs (tag in a comment, resolved with `git ls-remote`); runner `ubuntu-24.04`; JDK Temurin `17.0.13`. `gradle/actions/setup-gradle` is deliberately not used (SHA could not be resolved), so there is no restored Gradle cache. Wrapper integrity: both jobs run `sha256sum -c release/gradle-wrapper-jar.sha256` before any `./gradlew`, so a change to `gradle-wrapper.jar` must be a reviewed change to that file as well. `distributionSha256Sum` is pinned in `gradle-wrapper.properties` (`20f1b117...0aed78` for gradle-8.13-bin.zip), matching both the official `.sha256` file on services.gradle.org and the gradle.org/release-checksums page; the local cache holds no zip, so the first fresh download (CI) is the end-to-end check.

Limits: `BUILD_SHA.txt` only ties the artifacts to a commit; it cannot authenticate output from a compromised builder (a hash produced by the builder adds nothing against that builder). Stronger assurance needs an independent, trusted reproducible build and hash comparison, and the signing job must not rebuild with secrets present. Omitting `setup-gradle` (and its cache) is deliberate.

Tag handling: the sign job rejects an existing tag whose peeled commit (`git rev-parse "$TAG^{commit}"`) differs from `$GITHUB_SHA`, before any secret step. Previous-tag ordering uses `release/prev-tag.sh` (semver; tests: `release/test-prev-tag.sh`).

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

## Submodule note (added 2026-11-19, found by reading, UNVERIFIED in CI)
`services/ai` builds `llama.cpp` through CMake; it is a git submodule pinned by its gitlink (`android-companion/services/ai/src/main/cpp/llama.cpp`, commit `4dd6dd4b`, url in `.gitmodules`). `actions/checkout` does not fetch submodules by default, so the build job now sets `submodules: true` (not recursive). The sign job does not build and does not need it. No local rehearsal could catch this (a local checkout already has the submodule). Confirm on the first real workflow run: the build job's native step must find `llama.cpp/CMakeLists.txt`.
