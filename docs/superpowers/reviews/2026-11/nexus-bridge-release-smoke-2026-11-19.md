# Nexus bridge release smoke, 2026-11-19

Base commit 2fa70f85 (`feat/v101-ask-eval`). Everything here used throwaway keys in an uncommitted scratch dir and `emulator-5554` only (API 34, arm64). No real key, hub, glasses or CI runner was involved.

## 1. Build: PASS
`:nexus-bridge:assembleRelease :phone:assembleRelease` through the Gradle lock (`--offline`, workers 2): BUILD SUCCESSFUL in 4m 46s, 443 tasks. The `llama.cpp` submodule dir was empty, so the main checkout's copy was rsynced in (untracked; `git status` shows only `scratch-keys/`). No release signing config exists, so both outputs are `*-release-unsigned.apk`. The bridge builds as a release for the first time: 6.3 MB, `io.github.pca.nexusbridge`, versionCode 1, `2.0.0-dev`, minSdk 29, targetSdk 35, not debuggable. `zipalign -c 4` passes; `-c -P 16 4` also passes for the bridge (the phone fails 16 KB, as already documented). A debug bridge (`:nexus-bridge:assembleDebug`, 19 s) was also built for the mismatch test.

## 2. Signing and verify-release.sh: PASS
Exact CI form (`--v1/v2/v4-signing-enabled false --v3-signing-enabled true --debuggable-apk-permitted false`), `release/verify-release.sh` with `PIN_FILE` pointing at the throwaway key's SHA-256 (the committed pin is still a placeholder):

| APK | Result |
|---|---|
| bridge, key A, expected package given | OK (pkg, versionCode=1, signer matches pin, v3, no v3.1) |
| PCA phone release (`com.rokid.context`, versionCode 4, 1.0.0), key A | OK |
| bridge, key B (different) | FAIL "signer SHA-256 does not match" (expected) |
| debug bridge | FAIL "APK is debuggable" (expected) |
| bridge key A, committed placeholder pin | FAIL "is still a placeholder" (expected, fail closed) |

Not run: `verify-release.sh` against a lineage-signed phone; the bridge was also signed with a throwaway lineage (debug key -> key A, `--lineage ... --rotation-min-sdk-version 28`): verify-release OK.

## 3. Emulator: PASS
Old `com.rokid.context` uninstalled (emulator only; no `io.github.pca.nexusbridge` was present). Installed phone (key A) and bridge (key A); `dumpsys`: both `signatures:[3793acfb]`. Granted the phone's runtime permissions, completed onboarding, enabled Developer mode (Diagnostics) to reveal Settings > Rokid Nexus.

| Step | Settings > Rokid Nexus status | Notes |
|---|---|---|
| bridge key A (same as phone) | "Nexus not installed" (the hub is absent) | NOT "Bridge not trusted" and not "Bridge not installed": the bridge was accepted as installed and trusted. This is TRUSTED, observed indirectly |
| bridge key B (after `uninstall`; `install -r` fails INSTALL_FAILED_UPDATE_INCOMPATIBLE) | "Bridge not trusted" with the "not signed with the same key as PCA ... Uninstall it, then install the bridge that came with this PCA release" text | UNTRUSTED observed |
| debug-signed bridge (debug key) | "Bridge not trusted" | UNTRUSTED observed |
| key A again | back to "Nexus not installed" | TRUSTED |

Log counts: the trust decision lines (`nexus bind untrusted`, `nexus bridge untrusted`) are emitted only when the provider tries to bind, which needs host = Nexus, and the host choice is disabled without the hub app ("Install the PCA Nexus bridge and Rokid Nexus first"). So 0 PcaSignerTrust log lines were produced and the verdict comes from the Settings status (`NexusProbe`), not the bind path. 0 FATAL EXCEPTION entries in logcat. The Settings text was read through uiautomator dump and a PNG.

Same-key release bridge over a debug-signed bridge:
- plain `adb install -r bridge-keyA.apk` (also with `-d`): FAIL INSTALL_FAILED_UPDATE_INCOMPATIBLE. The bridge must be uninstalled first (safe: it stores nothing), or signed with a lineage.
- bridge signed in the CI form WITH a lineage (debug -> key A) and `--rotation-min-sdk-version 28`: `install -r` over the debug bridge succeeded; `dumpsys`: `signatures:[3793acfb], past signatures:[c48b4ca, 3793acfb]`. This closes the "release bridge installs over the debug bridge: UNVERIFIED" item of `nexus-install-uninstall.md` for the API 34 emulator, with a throwaway lineage.

Manifest (aapt2 badging/xmltree of the release bridge): no launcher activity and no `launchable-activity`; no icon. Permissions: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`, and the self-defined `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` (androidx); defines and uses the signature permission `BIND_NEXUS_PORT`. No INTERNET, mic, camera or location. Components: `SettingsActivity` (exported, no intent filter on the manifest so not launchable from the launcher), `NexusPortService` (exported, guarded by `BIND_NEXUS_PORT`), `BridgeNexusService` (exported, FGS specialUse "glasses-session", intent filter `com.anezium.rokidbus.action.PLUGIN`), androidx startup provider (not exported) and profileinstaller receiver (exported, DUMP permission). Runtime grants on the emulator were only the install-time FGS ones.

## 4. release.yml and the bridge
- Built: yes, only when the `with_bridge` input is true (`workflow_dispatch`). A tag push never builds the bridge (`WITH_BRIDGE` is `'false'` on push). That is a gap if a release is expected to carry it from a tag push.
- Collected, signed (same form, same key and lineage as phone and glasses, `pca-nexus-bridge-<v>.apk`), verified by `verify-release.sh` and included in `gh release create "$OUT"/*.apk`, `SHA256SUMS`, `PROVENANCE.txt` (draft). Yes.
- Pin file: ONE pin (`release/signer-sha256.txt`) for every APK, not per APK. That matches the trust design (bridge must be signed like the phone) and `verify-release.sh` would reject a bridge signed with a different key (checked above). Per-APK pins would only be needed if the bridge ever used a separate key (then `PcaSignerTrust.PINNED` is also needed; it is empty).
- BUG FIXED (small): the versionCode step ran `git show <prev>:android-companion/nexus-bridge/build.gradle.kts` for the bridge; that file does not exist at any earlier tag, so under `set -euo pipefail` the first `with_bridge` run would fail with a bare git error. Now the bridge's comparison is skipped when the module is absent at the previous tag (YAML re-parsed OK; the script change was reasoned and not run, since the classifier blocked a test harness).
- Remaining gaps (not fixed): bridge version is still `2.0.0-dev`/code 1 (bump before the real release); `PcaSignerTrust.PINNED` empty (fine while same key); the bridge `release` build type has no `signingConfig`, so a local `assembleRelease` is always unsigned (correct for CI); `:glasses:assembleRelease` was not re-run here.

## 5. NOT verified
Real Nexus hub (the bind path, `nexus bind untrusted` log lines, approval, rendering: none ran); real glasses; the real release key, lineage and pin; a real CI run and the protected Environment; devices below API 33 (lineage rotation of the bridge); trust through a lineage on the phone side (`PcaSignerTrust` rule 2: only unit-tested; here both packages carried the same single key); whether a rotated phone still trusts a bridge signed only with the old debug key (expected UNTRUSTED, not run).
