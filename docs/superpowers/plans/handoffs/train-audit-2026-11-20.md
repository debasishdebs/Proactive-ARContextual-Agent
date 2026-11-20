# Train audit 2026-11-20 (cross-cutting, read-only)

Scope: `git diff 18c1b76a..HEAD` on `feat/v101-ask-eval` (112 commits, 217 files). I changed no files except this one. I ran no Gradle and no adb. The method was static reading of the diff plus the code around it. Anything marked UNVERIFIED needs a device or a test to confirm.

## Prioritised findings

### P1

**P1-1. A rollback to the previous APK silently removes Offline only and the data saver, and the parked backlog then uploads over roaming.**
- Where: the installer rollback (`tools/install-trip-build.sh:45-60`, `print_guidance`) restores the tar and runs `install -r $PREV_APK`, which is 18c1b76a or the conservative candidate 4436d1a5. Neither APK reads `ai_backend.xml` or `data_saver.xml`.
- What the old build does with parked frames: they are ordinary `PENDING` rows with `lastError='data_saver'` (`FrameDaos.kt`, `parkPendingForDataSaver`). The old pipeline claims `PENDING` rows whatever their `lastError` and describes them on any validated network. Its `shedOldestNonDwell` does not protect them either.
- Scenario: in Sri Lanka on roaming, something breaks and the rollback is used. The old build at once sends every waiting photo (up to the whole backlog) over roaming. If the user had chosen Offline only, every remote model call resumes with no notice.
- The rollback text says nothing about this. Fix (procedural, cheap): before any rollback, add "turn mobile data and roaming off, and stay on Wi-Fi you trust, until you reinstall the train build". For an Offline-only user, add "unbind the remote endpoints first". The trip quickref should say the same.

### P2

**P2-1. After a rollback, Delete all misses the new log segments: up to about 290 MB, including the place-id trail.**
- The old `AsyncLineWriter` clears only `listOf(file, older)`, i.e. `pca.log` and `pca.log.1` (18c1b76a `AsyncLineWriter.kt:189`). The new build writes `pca.log.2` ... `pca.log.59` (`PhoneLogSink.MAX_BYTES = 300_000_000`, `SEGMENT_BYTES = 5_000_000`).
- The tar restore is `tar x` over the live folder. It never removes files that are missing from the archive, so the segments survive the rollback.
- Scenario: roll back, then Delete all. The old build deletes two files and leaves up to 58 segments of `place attempt ... ids=wd:Q… chosen=…` lines with timestamps. Those segments stay until a train build is installed again and its Delete all runs.
- Fix: the rollback guidance should first move `files/logs/pca.log.[2-9]*` into the quarantine folder. Alternatively, the trip quickref should say that Delete all is incomplete on a rolled-back build.

**P2-2. Android's own Data Saver at home now blocks every photo and bulk call, whatever the PCA mode.**
- Where: `DataSaver.kt:49` and `:55` map the case where Android restricts background data to `NetworkCost.RESTRICTED`. `:218` then refuses PHOTO and BULK_OTHER under every mode, including "Anywhere" (`R_ANDROID_SAVER`).
- Before 18c1b76a..HEAD, PCA sent photo descriptions whenever the network was validated.
- Android lets foreground and foreground-service processes use data under its Data Saver. The hub runs as an FGS, so PCA is now stricter than Android. UNVERIFIED: from memory of NetworkPolicyManager; check on the device.
- Scenario: a user with system Data Saver ON on home mobile data. The backlog parks for 30-minute stretches, Ask photos and Place Guide matching stop, and describe waits for Wi-Fi. The user changed nothing in PCA.
- Fix: decide whether RESTRICTED should block only while PCA has no foreground or FGS state. At least, the Data saver card should state the cause ("Android Data Saver is on").

**P2-3. Data the saver does not meter, allowed on roaming by default: Place Guide cell fetches and street-map tiles.**
- Place cells: `PlaceSamplers.kt:99-101` decides `fetchOk` as `DataKind.SMALL`, and `smallOnRoaming` defaults to `true` (`DataSaver.kt:119`). One new cell means Wikipedia, Wikidata and Overpass requests, each capped at 4 MB (`PlaceHttp.kt:36`). None of this counts toward the daily byte limit (PRIVACY section 15: "place and map requests are not counted yet").
- Street map: `OnlineTileSource.kt:196-211` checks only Offline only, never the data saver. That contradicts the design table ("Online tiles: cached tiles only unless FREE", roaming design doc line 151). It is user-started and opt-in: at most 400 tiles of up to 512 KB per visit, about 200 MB worst case per visit and typically a few MB.
- Scenario: walking through new 5 km cells on roaming with Place Guide on. Each new cell can cost several MB that are not on the "today" counter, and the daily limit gives no protection against them.
- Fix: count `PlaceHttp` and tile bytes in the ledger. Optionally gate tiles to cache-only on costly networks. Either way, the Data saver screen text should say these are outside the limit.

**P2-4. The Detailed log is ON by default with a 300 MB cap: a weeks-long place trail that survives Forget and is mirrored to logcat.**
- Changes by default: `DeveloperModeStore` `detailed_log` defaults to `true`. Every I and D line of the whole app (not only the new ones) now goes to disk, where before this happened only in Developer mode. Retention grew from 6 MB to 300 MB.
- The new `place attempt` line (`PlaceSampler.kt:205`) carries `ids=` (up to 12 candidate ids, `:317`) and `chosen=` (`:351`, `:440`). These are `wd:`, `osm:` or `user:<uuid>` ids, and together they form a timestamped place-level trail.
- The trail is documented (PRIVACY section 8, "What the log can reveal"). Still, a single Forget of a place memory does not remove its lines, and I-level lines also go to logcat.
- Log volume: I expect under about 1 MB/h in normal use. Capture is at least 30 s apart (`CaptureCadence.MIN_INTERVAL_MS`), giving at most about 120 pokes/h with 2-4 place lines each. DiagProbe lines change only on change, plus one per hour. PcData lines come one per blocked call, and the pipeline parks for 30 min. That works out to about 10-25 MB/day, so 300 MB holds about 2 weeks.
- UNVERIFIED: pre-existing I and D lines that used to be dev-only may dominate the volume. No line I found is emitted per frame-preview or per second.
- Fix: measure `du files/logs` after 1 h of real use with post-install-check. Make the hash-the-ids decision (PRIVACY marks it open) before Sri Lanka.

### P3

1. **The PRIVACY.md Geocoder statements contradict each other.**
   - Line 209 says the `Geocoder` is "Not covered" by Offline only.
   - Line 44 and the code (`SourcesGraph.kt:67-68`, `LocationSource.gated`) gate it under Offline only and the data saver.
   - Fix: delete the Geocoder from line 209.
2. **Offline only can still send a DNS query for a name it then refuses.**
   - `OfflineOnlyDns.kt:17` resolves the hostname before checking the mode, so under Offline only the system resolver (possibly the ISP's) still sees the name.
   - Only names the router already classed as LAN reach this point (for example `box.local` over unicast DNS), so the exposure is a hostname, never content.
   - Fix: classify `.local` and other LAN names first, or accept this and document it.
3. **DiagProbe can miscount foreground state, and its receivers run on the main thread.**
   - Lifecycle race: `registerActivityLifecycleCallbacks` runs on IO (`DiagProbe.kt:59`, `:143`). If MainActivity starts before that coroutine registers, `started` goes negative. "app foreground" is then never logged and `foreground=` in the heartbeat is wrong until restart. This affects diagnostics only. UNVERIFIED how often it happens.
   - Main-thread receivers: the battery and time receivers have no Handler, so they run on the main thread. `onTimeChange` (`ContextApp.kt:134`) calls `DataSaverGraph.get()`. On a time-zone change before the graph exists, that builds it on the main thread: prefs load, two binder calls, a ModelPrivacyStore load and a network callback registration. That is roughly 10-50 ms (UNVERIFIED) and is rare.
4. **Model privacy can still read the disk on the main thread at start.**
   - `ModelPrivacyStore.mode` and `wasReset` (`ModelPrivacyStore.kt:56-59`) call `load()` on the caller's thread. A screen collected before the warm thread (`ContextApp.kt:20`) finishes blocks the main thread on the prefs file load.
   - The file is tiny and this is only a cold-start race. ANR risk is negligible.
5. **WorkManager keeps a Wi-Fi-only constraint after a rollback.**
   - `OrchestrationScheduler.kt:56/69/326` switched to `UPDATE` with `dataSaverNetwork()`. If the user chose "Wi-Fi only", the catch-up and sweep jobs are persisted with `UNMETERED`.
   - The old build enqueues with `KEEP`, so after a rollback those jobs stay Wi-Fi-only. That is safe, but differs from 18c1b76a.
6. **Pins round-trip badly through a rolled-back build.**
   - The old parser ignores `moment`, `at` and `armed` (safe). The old serializer rewrites `place_pins` without `moment` and `at` on any pin edit, so Moment links and `pinnedAt` are lost silently. The pins then show "Needs a Moment" after the train build is reinstalled.
   - The old settings export does not strip these keys, so a rolled-back build exports Moment ids and any arming record. These are ids only.
7. **Every existing pin shows "Needs a Moment" after the upgrade.** `PlaceGuideModel.needsMoment` is true when `momentId == null` (`PlaceGuideSettings.kt:190`). This is a visible default change. The editor does not require a Moment (it stays optional).
8. **`pinnedAt` survives Delete all, and the code comment about the export is wrong.**
   - Unlinked pins are kept by Delete all, as before, but now carry `pinnedAt` (`UserPlace.kt:30`). That is a "was here at time T" datum that survives Delete all.
   - The kdoc on `UserPlace.kt:30` says "Kept in the settings export", but `withoutLinks` (`:185`) removes `at`. PRIVACY line 257 agrees with the code. Fix the comment, and decide whether Delete all should clear `pinnedAt`.
9. **"Allow mobile data" downloads still run abroad on a local foreign SIM.** Model downloads use `NOT_ROAMING` (`LocalModelStore.networkType`). With a local SIM bought abroad and "I'm abroad" on, Android does not report roaming, so a hundreds-of-MB download runs on that SIM's data. The abroad switch is not consulted.
10. **A travel eSIM can make home look like roaming.** With a second, foreign SIM, `countryMismatch()` (`NetworkCostMonitor.kt`) compares the default SIM with the network country. It can classify home mobile data as ROAMING and park photos. UNVERIFIED on dual-SIM devices.
11. **DiagProbe's worker watch may cost CPU.** `DiagProbe.watchWork` collects `getWorkInfosFlow` over all states, finished ones included. Every WorkManager table change re-runs the query and iterates the whole history, which can be hundreds of rows. The log lines are deduplicated; only the CPU cost is a concern.

## Answers by question

### (1) Outbound network paths under Offline only or a data-saver block

| Path | file:line | Offline only | Data saver blocked | Class |
|---|---|---|---|---|
| Model calls (all roles) | `ModelRouter.kt:63` guard; `Http.kt:15-18` DNS, proxy and route guard | refused before the key is read | PHOTO and BULK refused; SMALL allowed on roaming by default | gated |
| Embeddings (text) | `EmbeddingClient.kt:47,127`; `AiGraph.kt:43` wires privacy | refused if the endpoint is remote | not data-gated, except the night backfill (`DataSaverWorkGate`) | gated (privacy) / ungated (saver, small) |
| Image embeddings and shadow scoring | `EndpointEmbedder.kt:35` (image only for on-device Nomic) | no network | no network | local, verified |
| Endpoint Test | `EndpointTester.kt:48` | refused | not gated | user-started |
| Place cell fetch (Wikipedia, Wikidata, Overpass) | `PlaceGraph.kt:69`; `PlaceSampler.kt:305-307`; `PlaceSamplers.kt:99-101` | no fetch (backend is never ONLINE) | allowed as SMALL; stale seeded cells kept; not counted | gated, see P2-3 |
| Place seed | `AndroidPlaceGuideControl.kt:40` | refused | refused unless FREE | user-started, gated |
| Street-map tiles | `OnlineTileSource.kt:201-220` | no request (checked before every hop) | **not gated** | user-started, opt-in, see P2-3 |
| Geocoder (Location context source, off by default) | `LocationSource.kt` `gated`; `SourcesGraph.kt:67-68` | skipped | skipped unless SMALL is allowed (default allowed) | gated |
| Model file download (huggingface) | `LocalModelDownloadWorker.kt:192` `downloadClient` (unguarded); `LocalModelStore` `NOT_ROAMING` | **allowed** (documented) | Wi-Fi only, or not roaming with "Allow mobile data" | user-started, see P3-9 |
| Fused location (`PlaceFixProvider.kt:61` HIGH_ACCURACY per dwell attempt; `OnlineMapGraph.kt:57` Centre on me; `TripLocation`, `SpeedSource`) | platform | not gated (Play services may use Google network location) | not gated | platform; Centre on me is user-started |
| ML Kit text recognition | `TextPresence.kt`; `play-services-mlkit-text-recognition` (unbundled) | on-device inference; the model is delivered by Play services | n/a | platform, pre-existing |
| Canvas WebView | `CanvasWeb.kt:83,144` (127.0.0.1, own origin only) | local | local | local |
| DNS of the guarded model client | `OfflineOnlyDns.kt:17` | the lookup happens before the refusal | n/a | see P3-2 |

There is no new socket, WebView or HTTP client beyond `OnlineTileSource`. The glasses app has no network client in `src/main`.

### (2) New log lines that can carry sensitive content

- I checked every added `PcLog` call that interpolates a value. All of them carry enum names, counts, buckets, `javaClass.simpleName`, `SafeLog.describe` (fixed tokens) or fixed literals.
- No added line carries `e.message`, `$e`, a URL, a host, a key, a path, a name, a note or a coordinate. No added call passes a throwable or uses `android.util.Log` or `println`.
- The one exception is content-identifying ids: `place attempt ids=` and `chosen=` (`wd:`, `osm:`, `user:<uuid>`). They are documented, but see P2-4.
- The resolver `label=` comes from a fixed `kind` token (`ModelResolver.kt:19`).
- Tiles log fixed literals only (`OnlineTileSource.kt:218-267`).
- `ProviderHttpException` still embeds `body.take(200)` in its message (pre-existing), but no new log prints `.message`.

### (3) New prefs and files

| Name | Content | Backup (legacy, cloud, transfer) | Delete all |
|---|---|---|---|
| `online_map.xml` | switch and consent | excluded in all 3 | wiped (`MemoryWipe.WIPED_PREFS`) |
| `cache/online_tiles/` | tile images; names `z_x_y` reveal the areas viewed | the cache folder is never backed up | wiped (`MemoryWipe`), also on switch-off |
| `data_saver.xml` | mode, abroad flag, small-on-roaming, limits, today's counts and bytes, `blockedSince` | excluded in all 3 | kept (user ruling: the limit must survive a wipe; counts only) |
| `ai_backend.xml`, `ai_backend_init.xml` | Model privacy mode and sentinel | excluded in all 3 | kept (justified: wiping would fall back to AUTO, the less private setting) |
| `pc_dev.xml` new keys `detailed_log`, `placeShadowScore` | flags | already excluded | kept (settings) |
| `place_pins.xml` new `moment`, `at`, `armed` | Moment ids, pin time, pending cascade record | already excluded; export strips them | linked pins deleted; unlinked pins keep `at` (P3-8) |
| `files/logs/pca.log.2..59` | log segments | `logs` folder excluded | every segment wiped by the new build (listing); see P2-1 for the rollback case |
| In-memory only | `FallbackParkCap`, `ShadowCaches`, `PinReferenceVectors` | none | ShadowCaches cleared |

No new directory under `files/` or `cache/` is missed by Forget or Delete all in the train build.

### (4) Room

- No schema or migration change: no `@Entity`, `@Database`, version or `Migration` in the diff, and no `schemas/` file.
- The DAO changes are new queries only: `parkPendingForDataSaver` (an UPDATE), `dataSaverParkedCount`, the shed query and the read-only Moment lookups.
- New persisted value: `frame_state.lastError='data_saver'`. Older builds treat it as an ordinary error string (safe; see P1-1 for its effect).

### (5) Permissions and manifest

- No `AndroidManifest.xml` change: no new permission, exported component, intent filter or FGS type change.
- Dynamic registrations added:
  - DiagProbe battery and time receivers (system-only protected broadcasts, so no `RECEIVER_*` flag is needed on targetSdk 35; inside `guard{}`).
  - 2 extra `registerDefaultNetworkCallback`s (DiagProbe, NetworkCostMonitor).
  - Activity lifecycle callbacks.

### (6) Startup and ANR risk (main thread, `ContextApp.onCreate`)

| Item | Cost |
|---|---|
| `OfflineOnlyDns.globalMode` lambda (`:19`) | about 0 |
| warm thread for `ModelPrivacyStore` (`:20`) | thread creation, under 1 ms |
| 3 `ForgetListeners.register` calls (`:42`, `:45`, `:46`) | about 0 (no IO) |
| `DiagProbe.start` (`:134`) | one launch, under 1 ms; all binder work is on IO |
| Log install lambda change | none |

The only main-thread work found is in the P3-3 and P3-4 races. `KeyframeGraph.photoReach` already avoids the main thread. `DataSaverGraph` construction blocks if its first caller is on the main thread: the DiagProbe time receiver for certain, and possibly a screen (UNVERIFIED).

### (7) Log volume

See P2-4. None of the new lines is per frame-preview, per second or unbounded: DiagProbe deduplicates battery, network and workers; the heartbeat is hourly; PcData logs per blocked call and the pipeline parks for 30 min; the tiles session-cap line is logged once per visit.

Highest-rate new lines: `place outcome` plus `place attempt` on every stored frame while Place Guide is on, plus `resolve` and `pack lookup` per attempt. That is at most about 480 lines/h.

No new wakelock or alarm. The heartbeat is a coroutine `delay` and does not wake the CPU.

### (8) Default-behaviour changes for a user who changes nothing (home Wi-Fi or home mobile data)

- The Detailed log is ON, with up to 300 MB on disk, and new PcDiag, PcData, PcPlace and OfflineOnly lines (P2-4).
- New Settings rows: Data saver, Model privacy, Detailed log, and the street-map switch with Centre on me.
- Every existing pin shows "Needs a Moment" (P3-7). The pin editor offers an optional Moment.
- An explicit Forget of a Moment linked to a pin shows a "Forget Moment and pin(s)" dialog, but only once the user links pins.
- Home mobile data:
  - Photos are allowed (default `WIFI_AND_HOME_MOBILE`).
  - Place seed is now refused unless on Wi-Fi.
  - Stale seeded cells are not refreshed on mobile data.
  - With Android Data Saver ON, everything that uploads photos parks (P2-2).
- An unknown network (VPN without a transport, not yet validated, at start) blocks photo calls. The 20 s blip debounce applies, and the card shows "waiting".
- The Data saver card shows the "waiting" count. The "Send over mobile data once?" prompt appears only when a photo is held.
- On Wi-Fi nothing parks. Model calls are unchanged under Automatic.

### (9) Rollback safety

- An older APK safely ignores `ai_backend*`, `data_saver`, `online_map`, the new `pc_dev` keys, the pins' `moment`, `at` and `armed` keys, `frame_state 'data_saver'` and `cache/online_tiles`. No crash path found.
- No Room change and no new Worker class, so there is no ClassNotFound risk for persisted WorkSpecs.
- Not clean:
  - P1-1: the protections and the parked backlog are gone after a rollback.
  - P2-1: orphaned log segments.
  - P3-5: the WorkManager constraint persists.
  - P3-6: pin links are lost on rewrite and leak into the old export.
- The tar overlay leaves all new files in place.

## Checked and found OK

- **Shadow scoring and pin reference vectors are on-device only.** Images are embedded only by the on-device Nomic ONNX model (`EndpointEmbedder.kt:35`). The flag is off by default, and the scoring runs only after the data-saver photo check (`PlaceSampler.kt:329` before `:339`).
- **Offline only uses the synchronous OkHttp `execute()`** (`FencedCall.kt`), so the runtime exception thrown by the DNS and route guards cannot crash an OkHttp dispatcher thread.
- **Tile fences hold:** HTTPS only, one host, redirect checks, size caps, and the Offline-only check before every hop.
- **Backup rules list the new files in all three sections:** legacy backup, cloud backup and device transfer.
- **`MemoryWipe.deleteAll` runs on IO and NonCancellable**, so the new pin arming never runs on the main thread.
