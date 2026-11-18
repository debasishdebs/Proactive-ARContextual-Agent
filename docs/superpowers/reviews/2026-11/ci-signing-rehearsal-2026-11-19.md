# CI signing rehearsal, 2026-11-19

Emulator only (`emulator-5554`, MyEmulator, API 34). Throwaway keys (PKCS12, RSA 3072, password `throwaway1`) in a scratch dir inside the worktree (`.scratch-sign/`, never added to git); nothing secret committed. Build-tools 36.0.0. Base commit `9033c5ae`. The workflow itself was not run or pushed.

## Result: PASS (the single-signer CI form works end to end on API 34)

| Step | Result |
|---|---|
| 1. Throwaway old and new keystores + lineage (`apksigner rotate`, old -> new) | PASS |
| 2. Input APK | PASS, with a substitution (see below) |
| 3. Old-key install on the emulator | PASS (cert `d0aceb29...`, no past signatures) |
| 4a. Workflow signing form, exactly as written | PASS on a release APK; FAILS on a debug APK by design |
| 4b. `apksigner verify --print-certs -v` | PASS: v3 true, v1/v2/v3.1/v4 false, 1 signer, new cert `2afda2d5...` |
| 4c. `release/verify-release.sh` | PASS with a throwaway pin (`PIN_FILE`); fails closed on a debug APK and on the placeholder pin |
| 4d. `adb install -r` over the old-key install, no uninstall | PASS: `Success`, signatures `[cee4999d]`, past signatures `[e1f5c67 flags 17, cee4999d flags 17]` |
| 4e. Data survives | PASS: data dir inode 311974, `files/` 321033 and `files/marker.txt` 321040 unchanged, marker content intact (emulator rooted via `adb root`, because the installed app is non-debuggable so `run-as` is unavailable) |

## Commands and differences from the workflow text

```
keytool -genkeypair -keystore old.p12 -storetype PKCS12 -alias pcaold -keyalg RSA -keysize 3072 -validity 10000 -dname "CN=throwaway old" ...   (same for new)
apksigner rotate --out pca.lineage --old-signer --ks old.p12 ... --new-signer --ks new.p12 ...          # rc 0
zipalign -c -P 16 4 <apk> || zipalign -c 4 <apk>
apksigner sign --ks new.p12 --ks-key-alias pcanew --ks-pass env:KS_PASS --lineage pca.lineage --rotation-min-sdk-version 28 \
  --v1-signing-enabled false --v2-signing-enabled false --v3-signing-enabled true --v4-signing-enabled false \
  --debuggable-apk-permitted false --out pca-phone-9.9.9.apk phone.apk
```

1. **Debug APK input fails (by design).** The supplied `phone-debug.apk` is debuggable and debug-signed. The exact form fails:
   `SignatureException: APK is debuggable (see android:debuggable attribute) and this engine is configured to refuse to sign debuggable APKs` (rc 1). So a debug APK cannot exercise the CI form. Substitute: the release-variant `phone-release-unsigned.apk` from the 2026-11-18 smoke build (versionCode 4, package `com.rokid.context`, not debuggable). The old-key install was that same APK signed with the old key. versionCode was equal (4); `install -r` accepts that.
2. **`zipalign -c -P 16 4` fails on the release APK** (rc 1, native libs not 16 KB aligned); the workflow's `|| zipalign -c 4` fallback passes (rc 0). Not a workflow bug, but the 16 KB check is advisory only.
3. **`verify-release.sh` needs a pin.** `release/signer-sha256.txt` is still a placeholder, so the script (correctly) fails closed with "still a placeholder". Locally, `PIN_FILE=<file with the new cert sha256>` works. Output on the CI-form APK: `package=com.rokid.context versionCode=4 versionName=1.0.0`, `signer=2afda2d5... (matches pin)`, `v3 verified, no v3.1 block`, `OK`. On the debug-signed APK: `FAIL: APK is debuggable`.
4. A first `keytool` call ran in the wrong cwd and wrote a throwaway `new.p12` at the worktree root; it was deleted at once (and `*.p12` is git-ignored).

Workflow/README text fixed (comments and checklist only, no command changed): `release.yml` header and the signing-step comment now say "rehearsed 2026-11-19" instead of "UNREHEARSED" and note that the form cannot sign a debug build; `release/README.md` status line, checklist ticked, notes on release-variant input, the `-P 16` fallback and `PIN_FILE`.

## Still unverified

- A real CI runner (ubuntu-24.04, sdkmanager build-tools install path, `$ANDROID_HOME` layout, base64 secret decoding, `umask 077`, the cleanup step).
- The protected GitHub Environment `release`, required reviewers, secrets scoping, tag and main-ancestry checks.
- The real release key, the real lineage (old signer is the real debug key) and the real pin value.
- Devices below API 33 (glasses minSdk 28; the phone may also be below 33). This API 34 emulator cannot show whether `--rotation-min-sdk-version 28` makes the lineage apply on API 28-32.
- Glasses and nexus-bridge APKs (only the phone APK was signed), Wear Data Layer and Rokid CXR signature behaviour, Keystore-held DB key survival on the real phone.
- A higher versionCode than the installed one (the real flow always raises it; equal was accepted here).
