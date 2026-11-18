# Release-build smoke test, 2026-11-19 (roadmap v1.1 item 5)

Base commit 6a5036b0. Build type tested: `release` of `:phone` and `:glasses` (BuildConfig.DEBUG=false, not debuggable), unsigned output re-signed with a throwaway keystore, installed on `emulator-5554` only (Android 14, arm64).

## 1. Static pass: code that only runs when DEBUG=false / not debuggable

| # | Site | In release | Test coverage |
|---|---|---|---|
| 1 | `phone/.../hub/HubGraph.kt:147` `CxrGlassesLink(probe = BuildConfig.DEBUG)` | PcLink rx/tx probe off | The flag is not unit-tested at the call site. `GlassesSessionPolicyTest` covers link policy, not the probe flag. Emulator: link starts (`link=CONNECTING`), no probe lines. |
| 2 | `phone/.../ui/preview/PreviewSection.kt:198` | "Copy frame id" button not composed | None (no Compose test for the DEBUG branch). Emulator: not reachable without photos. |
| 3 | `PreviewRoute.kt:104`, `SettingsDestinations.kt:348`, `:377` via `PhotoDimEval.rowVisible(debug, devOn)` | Photo-dim row, labels and route hidden | `PhotoDimEvalTest:221-223` covers the pure predicate for debug=false. Emulator: Diagnostics shows no photo-dim row (developer mode off). |
| 4 | `glasses/.../capture/CaptureService.kt:185`, `:717` (`BuildConfig.DEBUG && EXTRA_SKIP_DEDUPE`) | skip-dedupe extra ignored: every capture deduped | `CaptureServiceModeTest` and `CaptureKeepAliveTest` test `captureNow(skipDedupe=...)` directly, not the DEBUG gate. The gate itself is untested. Not exercised on the emulator (no camera/hardware). |
| 5 | `MemoryExport.defaultProductionBuild` (FLAG_DEBUGGABLE==0) used by `ForgetActions.kt:207`, `:271` | An undeletable cached export turns DONE into `CACHE_CLEANUP_FAILED` | JVM only, via the `productionBuild` seam: `ForgetOutcomeFixesTest`, `RangeForgetCleanupTest`, `ScopedForgetActionsTest`. The real default lambda (flag read) is not unit-tested; it was exercised on the emulator (section 4). |

Watch, core and services contain no other DEBUG branches (grep over all `.kt/.kts/.xml/.pro`). `Class.forName("com.rokid.sprite.aiapp...AuthorizationHelper")` in `CxrGlassesLink.kt:389` is the only reflection and targets a foreign app's obfuscated class (not ours, so no keep rule can help; already wrapped in try/catch).

## 2. Minification / R8

- `isMinifyEnabled = false` in `phone`, `glasses` (and `nexus-bridge`); watch has no release block. `shrinkResources` is not set. So R8 does not run on any release APK today: no keep-rule gap can bite yet.
- `phone/build.gradle.kts` lists `proguard-rules.pro` but the file does not exist (harmless while minify is off, an error the day it is turned on). `glasses/proguard-rules.pro` contains only `# no-op for debug/mvp`. No module sets `consumerProguardFiles`.
- If minify is ever enabled, rules would be needed for: SQLCipher (JNI, `net.zetetic.database.**`), the llama.cpp / ONNX Runtime JNI classes (`ai.onnxruntime.**`, our `LlamaJni`-style native-method holders), the CXR SDK (`com.rokid.cxr.**`, `com.rokid.sprite.**`, AIDL/Parcelable stubs, reflective AuthorizationHelper), kotlinx.serialization (companion/`$serializer` of every `@Serializable` class; consumer rules ship with the plugin but verify `@SerialName` polymorphic types), Room (generated, `RoomDatabase` subclasses; consumer rules ship), WorkManager (workers are created by class name; consumer rules ship for `ListenableWorker` subclasses, check custom `WorkerFactory`), Wearable Data Layer service (`PcaWatchListener`), and the Nexus AIDL interfaces. Currently NONE of these has an app-level rule, so enabling R8 requires a dedicated pass plus an emulator run. Not changed here (no behaviour change warranted, nothing to verify against).

## 3. Build

`:phone:assembleRelease :glasses:assembleRelease` via the Gradle lock: BUILD SUCCESSFUL in 10m 6s. First attempt failed in `:services:ai:configureCMake` because the worktree's `llama.cpp` submodule dir was empty; copied the main checkout's tree into the worktree (untracked, `git status` clean) and re-ran. No signing config exists for release, so outputs are `phone-release-unsigned.apk` (235 MB) and `glasses-release-unsigned.apk` (44 MB). Both re-signed with a throwaway keystore in the scratchpad (not committed). `aapt2 dump badging`: no debuggable flag; phone versionCode 4 / 1.0.0, targetSdk 35.

## 4. Emulator result

Phone release (existing leftover com.rokid.context uninstalled on the emulator first, then installed, 9 runtime permissions granted with `pm grant`, `pkgFlags` has no DEBUGGABLE, `run-as` refused as expected):
- Cold start, full onboarding (permissions, glasses "Set up later", watch skip, AI later, persona, gestures) and Home render with no crash. Settings, Privacy, Memory, Diagnostics (Queues area, Logs viewer), Activity tab all render.
- Typed "Where did I leave my keys?" suggestion tapped: no crash (no AI endpoint configured, so no answer expected).
- Export memories and Export settings: the share sheet opens with the JSON file (serialization works in release). The sharesheet logs `ChooserPreview` "could not read content://com.rokid.context.files/exports/..." (preview only, same in debug; see unverified).
- Delete all (the release-only Forget path with a cached export present): "Everything was deleted" shown, no `CACHE_CLEANUP_FAILED`, SuspectSweep and NightIndex workers SUCCESS.
- Logcat, 3 minutes clean after launch (03:57:19-04:01:36): 0 FATAL, 0 ClassNotFound / NoSuchMethod / SerializationException / NoClassDefFound. The only `UnsatisfiedLinkError` is an `ORTTelemetry` W log (ONNX Runtime telemetry), identical 20 lines in the debug baseline. Hub starts (`watchdog hub down, starting it`, `link=CONNECTING`), CXR AuthorizationHelper errors (no Hi Rokid app on the emulator, ActivityNotFoundException) identical to debug.
- Debug baseline: an existing `phone-debug.apk` from the main checkout (built 03:30 today, probably a nearby commit, not rebuilt from 6a5036b0) ran the same flow: same screens, same Delete-all result, same log shape (E/W by tag counts within noise: TraceManager, system A, PageIndicator). No release-only error.

Glasses release: installs (targetSdk 32, package com.rokid.context.glasses, not debuggable), CAMERA and RECORD_AUDIO granted, `MainActivity` launches and the process stays alive; no FATAL / ClassNotFound / NoSuchMethod in logcat. No camera or CXR hardware on the emulator, so capture, CaptureService:185 and any link behaviour are not exercised.

Fixes made: none (nothing to fix with evidence; minify is off).

## 5. Still unverified

- Real hardware: Rokid glasses CXR link in release (probe=false), CaptureService touchpad and intent capture with DEBUG=false, phone to glasses frame flow.
- Real signing key and in-place update from the debug-signed installs (lineage/key rotation, design B2). All emulator installs here used a throwaway key after uninstalling.
- Forget of a range and of a single photo with real photos and a real cached export (emulator had no data, so only Delete all and Export were hit); the CACHE_CLEANUP_FAILED branch cannot be forced on a non-debuggable device (JVM-only coverage).
- Export share-sheet preview warning (`ChooserPreview ... setClipData`): cosmetic, present in debug as well, not investigated.
- R8: untested by design (minify off). A future minified build needs the rule set in section 2 plus a new smoke run.
- Watch release build (not built, no release block).
- Debug-vs-release comparison used a prebuilt debug APK, not a fresh build from 6a5036b0.
