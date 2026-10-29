# PCA watch app: design note (2026-10-29)

Status: design only, no code. Rulings (user, 2026-10-29): Proactive Context gets its own thin Wear OS app so watch motion reaches PCA without LiveFit. It is a third app beside phone and glasses, laid out like LiveFit's phone+watch pair. PCA aggregates in its own phone app; PCA never writes Health Connect. When it lands, the LiveFit->PCA push path is removed and one "active watch source" setting stops double feeding.

Facts checked: PCA `settings.gradle.kts` has `:phone`, `:glasses`, `:core:*`, `:services:*` (AGP 8.13.2, Kotlin 2.0.21, Gradle 8.13, compose BOM 2024.09.00; phone `applicationId com.rokid.context`, minSdk 29, targetSdk 35, compileSdk 36). Phone has no Wearable dependency today. The `watch_livefit` source, `WatchSource`, `WatchMotionIntake` (gate + 1 s coalescing) and `WatchMotionProvider` exist (spec 2026-10-21 §8). LiveFit's watch already has the detector and passive service (`watch/.../motion/*`, 4 files, ~340 lines) and a ManifestPolicyTest.

## 1. Reuse map (copy vs shared module)

| LiveFit item | Action | Note |
|---|---|---|
| `MotionStateDetector` (+ `MotionMemory`, `MotionEvent`, `UserActivity`, `ExerciseKind`) | copy, adapt | Pure Kotlin, 141 lines; hold 30 s, stale 120 s, refresh 10 min, cadence 60/140. Change only the output type. |
| `PassiveMotion`, `MotionPassiveService`, `MotionReceiver` | copy, adapt | Replace `WatchRuntime.send(...)` with a small `PhoneLink.send`; keep boot/replace re-registration, 10-min inexact alarm and `commit()` in the IO scope. |
| `MotionReport`, `MotionState`, `/lf/motion` | do NOT port | LiveFit-specific (protocolVersion 4). PCA already has `WatchMotionMessage` + `WatchState` in `:core:model`; the watch module depends on `:core:model` and emits that. |
| `WatchClient`/`WatchRuntime` (Data Layer plumbing) | do NOT port | Far larger than needed; write ~60 lines (`MessageClient.sendMessage` + capability lookup). |
| `ManifestPolicyTest` | port, tighten | See §2. |

Recommendation: COPY, with a one-line provenance comment per file. A shared module needs a third repo or a git submodule, and LiveFit's side is being deleted anyway, so the copy never has to be kept in sync. About 300 lines to port, tests ported with them (detector tests ~15 cases).

## 2. Module layout, manifest, policy test

- `include(":watch")` in `android-companion/settings.gradle.kts`; dir `android-companion/watch`; namespace/package `com.rokid.context.watch`.
- `applicationId = "com.rokid.context"` (the same as the phone: the Data Layer only pairs apps with the same id and signing key, as LiveFit does with `com.livear.fit`). minSdk 30 (Health Services passive needs Wear OS 3), targetSdk 35 and compileSdk 36 (as the phone), versionName from the phone's scheme, own versionCode range (+1 on the form factor, as LiveFit does).
- Dependencies (versions copied from LiveFit): `health-services-client:1.1.0-alpha05`, `play-services-wearable:19.0.0`, `androidx.wear.compose:compose-material/foundation:1.4.0`, `wear:1.3.0`, `kotlinx-coroutines-guava`, BOM 2024.09.00, `:core:model`. The phone gains `play-services-wearable:19.0.0`.
- Build/signing: debug builds use the shared debug key, so no secrets are needed. A release key is out of scope (no store listing planned); if one is wanted later, phone and watch must use the same key.
- Manifest, strict allowlist: `uses-feature android.hardware.type.watch`, `ACTIVITY_RECOGNITION`, `RECEIVE_BOOT_COMPLETED`; `meta-data com.google.android.wearable.standalone=false`; `allowBackup=false`; one exported activity (LAUNCHER), `MotionPassiveService` (exported, `permission=...PASSIVE_DATA_BINDING`), `MotionReceiver` (BOOT_COMPLETED, MY_PACKAGE_REPLACED, own tick action). No INTERNET, location, BODY_SENSORS, health, no provider.
- `WatchManifestPolicyTest` (JVM, parses the manifest): (a) `uses-permission` set equals the allowlist exactly (stricter than LiveFit's forbid-list); (b) none of ACCESS_*_LOCATION, BODY_SENSORS*, READ_HEALTH_DATA*, INTERNET; (c) the passive service is bound only by PASSIVE_DATA_BINDING; (d) no `<provider>`, `allowBackup=false`; (e) capability res `wear.xml` declares only `pca_watch`.

## 3. Wire contract and phone receiver

- Transport: Wearable `MessageClient`, path `/pca/motion`, watch -> phone only. Body: the existing `WatchMotionMessage` JSON, `{"v":1,"epoch":E,"seq":N,"state":"WALKING","confidence":0.8,"changedAtMs":T1,"sentAtMs":T2,"source":"pca_watch"}` (<=2 KB, no steps, heart rate or location). States as today, never IN_VEHICLE. `epoch` random per watch process start, `seq` +1 per send including retries. The gate requires `source == "pca_watch"`.
- Rate and retry: send on state change (30 s hold already in the detector), on the 10-min refresh and on boot/replace; at least 1.1 s between sends; on failure (no node, `ApiException`) the detector's `unsent` flag keeps the latest state and the next tick (<=10 min) resends; a short backoff (1.1 s doubling to 30 s, 6 tries) as in LiveFit's plan. Always the latest state, never a queue. Data Layer messages are fire and forget: there is no reply, so `rate/stale_seq` reasons are visible only on the phone.
- Phone: new `PcaWatchListener : WearableListenerService`, manifest filter `MESSAGE_RECEIVED` on path prefix `/pca/motion`. It hands the JSON to `WatchMotionIntake.current` (null = hub or source off = dropped; the watch refresh redelivers). The Data Layer authenticates the sender (same package + signature), so the intake loses `callingPackage`/`bad_caller`/`bad_cert`/pinned digest. It keeps `not_enabled, too_big, malformed, version, stale_seq, rate` and the 1 s coalescing, `(epoch, seq)` persistence and epoch-reset hook unchanged.
- Hub consumption is unchanged: accepted messages become `Evidence.WatchMotion(clock(), state, confidence)` through the same sink, so `ContextFusion`, `watchTtlMs` (30 min), `WatchSource.events` and the `ContextHubStats` counters `watch` / `watchRej` (the "watch msgs N, rejected M" diagnostics line, `PcCtx watch=... watchRej=...`) keep their meaning; the hub-side `watch=177`-style totals continue across the switch. `WatchSource` id becomes `watch_app` (one-time migration: the old `watch_livefit` flag and cert pin are deleted, so the source starts off and the user opts in again).
- Receipt time is stamped on the phone; the watch clock is never trusted (as today).

## 4. "Active watch source" setting and status row

- `ContextSourceSettings.activeWatchSource`: `PCA_WATCH | NONE`, default NONE (all sources are opt-in). It replaces the `watch_livefit` switch in Settings -> Context sources -> "Watch and photos" with a two-option row. During the transition (slice B before C) the intake accepts a message only from the transport that matches the setting; LiveFit is simply not selectable, so a LiveFit forward gets `not_enabled`: nothing double-feeds. After slice C the row still exists (one real choice) and is the hook for any later source.
- Status row under it (phone side, polled when the screen is visible via `NodeClient.getConnectedNodes` + `CapabilityClient.getCapability("pca_watch")`, plus an in-memory `lastWatchAtMs` in `ContextHubStats`; counts only, no node names):
  - "Watch not paired" (no connected node).
  - "Watch app not installed" (a node, no `pca_watch` capability), with the hint "Install the PCA watch app on your watch".
  - "Waiting for the watch: no watch data in the last N min" (N = 15, 1.5x the refresh; amber).
  - "Watch: WALKING, 3 min ago" (green), plus the existing counters line.
  - Rejections with zero accepts: "Messages are being rejected (reason: ...)" using the top reason from `onReject`.
- Source OFF shows no status poll (no GMS call).

## 5. Removal steps

PCA (slice C, in this repo): delete `WatchMotionProvider.kt` and its test; manifest `<provider ...motionsource>` and `<queries><package com.livear.fit/>`; `WatchMotionIntake.LIVEFIT_PACKAGE`, caller/cert gate and `hexToBytes`; `LiveFitCertLookup`/`PackageLiveFitCertLookup`, `liveFitCertSha256`, `multiSigner`, `watchMissing`, `reToggleHint` in `ContextSourcesSettings`; the `watch_livefit` pref keys (migrate away); amend spec 2026-10-21 §8 to "superseded by this note".

LiveFit (repo `live-fitness-tracker`, each step separate. **Needs the user's OK before any edit or push there**):
1. Phone: remove `motion/ContextForwarder.kt`, `motion/MotionRelay.kt`, `ContextForwarderTest.kt` and their wiring in `ServiceGraph.kt`, `WatchListener.kt` (motion branch) and the "share with Proactive Context" toggle in `SettingsScreen.kt`.
2. `services/watch-link`: drop the `WatchInbound.Motion` case in `DataLayerWatchLink`/codec (keep the path constant until old watches are updated; `PROTOCOL_VERSION` stays 4).
3. Watch (separate OK, see open question 2): remove `watch/.../motion/*` and the passive-service manifest entries so two apps do not both register Health Services passive listeners.
4. LiveFit CHANGELOG + README line; the `code-agent` note that mentions how PCA consumes motion.

## 6. Watch UI sketch (Compose for Wear, `ScalingLazyColumn`)

Title "Context". Row 1: status chip "Sending" / "Phone not reachable" / "Permission needed". Row 2: last state big text ("Walking") with "sent 2 min ago". Row 3: one Toggle "Share motion with PCA" (off = unregisters the passive listener and stops the alarm). A "Grant motion access" button appears only when ACTIVITY_RECOGNITION is missing. No settings, no history, no tiles or complications in v1. Last state and send time are read from the `MotionMemory` prefs, not computed in the UI.

## 7. Test plan

- JVM unit: ported detector tests; wire round-trip with `source=pca_watch`; intake tests (drop of caller/cert cases, kept stale_seq/rate/epoch); source-setting migration; status-row reducer (pure, with a fake node/capability state); `WatchManifestPolicyTest`; removal compile gate (`grep` test that no `com.livear.fit` string remains in phone main sources).
- Device, per watch (Galaxy Watch 4, then 6 Classic), by wireless adb selected by transport id (serials can contain spaces; `tools/install-all.sh` in LiveFit is the pattern): `:watch:assembleDebug`, `adb -s <watch> install -r`, `adb -s <watch> shell pm grant com.rokid.context android.permission.ACTIVITY_RECOGNITION`, install the phone app, set the active source to PCA watch, then read `adb logcat` tag `PcaWatch` (watch) and the `PcCtx` line (phone). Not during a phone call; one Gradle run at a time. Likely a Galaxy Wearable pairing switch is needed between the two watches (a phone usually pairs one Samsung watch at a time: verify).
- User, worn: wear the watch 10+ min; walk ~3 min (expect WALKING after the 30 s hold, `watch` counter rises, status row green); sit for 3+ min (STILL after the stale rule); start a run or bike workout in Samsung's app (RUNNING/CYCLING, if the workout reports through Health Services); reboot the watch and confirm it re-registers without opening the app; leave the phone out of range 15 min and see "no watch data" appear then clear. ASLEEP needs sleep tracking on; treat it as best effort.

## 8. Slices (an "agent run" = one Sonnet implementer with a spec-and-quality review; sizes are estimates)

| Slice | Content | Size | Sonnet cost (rough) |
|---|---|---|---|
| A | `:watch` module, port detector + passive service, sender + capability, Wear UI, policy test, install on one watch | 5-7 runs | $8-15 |
| B | Phone: wearable dep, `PcaWatchListener`, intake refactor, `watch_app` source + migration, setting + status row, tests | 5-6 runs | $8-14 |
| C | PCA removal + docs/spec amendments; LiveFit removal steps (after the OK) | 3-4 runs | $4-8 |

Total about 13-17 runs, $20-37. Honest caveat: A is the riskiest (Health Services behaviour on Samsung hardware, Gradle/Wear plumbing); expect one extra debug run. Given the budget, do A first and test on a watch before spending B.

## 9. Decisions (user, 2026-10-29; supersede the former open questions)

1. Source id renamed to `watch_app`, with a one-time re-opt-in (the old `watch_livefit` flag and pin are deleted).
2. LiveFit's watch motion sender is removed later, in slice C, and only after the user's OK. Until then `/Users/debasishkanhar/Desktop/rokid/live-fitness-tracker` is not touched.
3. Primary test watch: Galaxy Watch 6 Classic.
4. Debug-signed sideload only; no release key.
5. No runtime phone -> watch ack or heartbeat. Instead, the phone app's first-run setup (slice B) gets a one-time "watch link confirmed" step.
6. Staleness line in the status row: fixed 15 min, UI only, no setting.
