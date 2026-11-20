# Roaming data saver: design (2026-11-20)

Status: DESIGN, slice 1 built on branch `worktree-agent-af0d488758d150685` (not merged, NOT in the 22 Nov trip candidate; see section 7). Original text below is the 2026-11-20 design pass. Branch read: `feat/v101-ask-eval`, `android-companion/`. All paths below are relative to `android-companion/`.
Trips: Charminar 22 Nov and Ramoji 23 Nov (Hyderabad, home), Sikkim/Darjeeling 1-6 Dec (domestic mobile data, patchy), Sri Lanka 23-31 Dec (international roaming, costly).
Claims marked UNVERIFIED were not checked on a device or in the Android sources during this design pass.

## 0. Summary: the three findings that shape the design

1. **There is no network-cost check anywhere on the photo path.** Every model call (describe, cluster summary, intent, PLACE_MATCH, Ask with photos, describe on demand, night describe-before-delete) goes through one choke point, `ModelRouter.call` (`services/ai/.../ai/ModelRouter.kt:176-224`), but the only network test the pipeline makes is "validated or not" (`KeyframeGraph.networkValidated`, `services/orchestration/.../work/frames/KeyframeGraph.kt:218-222`, which counts *unknown as online*). Metered and roaming are only *logged* (`phone/.../log/DiagProbe.kt:107-119`). The night job (`OrchestrationScheduler.kt:320-331`) runs on charger + idle with no network constraint: **a phone charging overnight in a Sri Lanka hotel without Wi-Fi uploads that day's photos over roaming**. The only existing Wi-Fi-only control is for on-device model downloads (`LocalModelStore.kt:54-73`, `NetworkType.UNMETERED` unless "Allow mobile data"; when allowed it is `CONNECTED`, so roaming too).
2. **Seeded place cells go stale after 30 days and are then re-fetched on any network.** `PlacePack.isFresh` (`services/memory/.../place/PlacePack.kt:214-217`) applies `FRESH_MS = 30 days` to seeded cells too, and `PARTIAL_FRESH_MS = 1 day` to cells stored without Overpass extras; `ensureCell` (`:108-118`) refetches whenever `networkUp` is true. Sri Lanka presets seeded before ~22 Nov will be stale for the whole 23-31 Dec trip and re-fetched (Wikipedia + Wikidata + Overpass per 5 km cell) over roaming; partial cells re-fetch daily. Fix is small: stale-while-costly for seeded/partial cells, plus a re-seed on home Wi-Fi after ~15 Dec.
3. **Roaming detection cannot be tested before the trip and has known blind spots**, so the design fails closed and adds a manual "I'm abroad" switch. `NET_CAPABILITY_NOT_ROAMING` is per active network; it is unreliable for travel eSIMs, dual SIM, VPNs (Android 10+ VPNs are metered by default, UNVERIFIED for roaming inheritance) and phone/MiFi hotspots that look like unmetered Wi-Fi. Unknown capabilities must mean "costly" for the data saver (the opposite of today's `networkUp`).

## 1. Map of outbound call sites

Classes: **BULK-PHOTO** (one or more JPEGs in the body), **BULK-OTHER** (large or many-request traffic without photos), **SMALL** (text/JSON request, small response), **TILES** (map raster images), **LOCAL** (never leaves the phone or the LAN).

Photo size: keyframes are q75 JPEG, long side at most 640 px (`glasses/.../capture/session/AndroidKeyframeEncoder.kt:13-37`), sent Base64 in the JSON body (+33%). Estimate 50-110 KB per photo on the wire (UNVERIFIED: measure in slice 2 with the byte counter).

### 1.1 The transports (choke points)

| # | Transport | File:line | Notes |
|---|---|---|---|
| T1 | OkHttp model client | `services/ai/.../ai/Http.kt:8` (`modelClient`), `:22` (`downloadClient`) | Shared by providers, embeddings, tester, downloads. A network interceptor here can count bytes for everything model-shaped. |
| T2 | OpenAI-compatible / Azure provider | `services/ai/.../model/provider/OpenAiCompatProvider.kt:73-78` via `FencedCall.kt:16-18` | Chat completions; images as Base64 data URLs. |
| T3 | Ollama provider | `services/ai/.../model/provider/OllamaProvider.kt:52-56` | `/api/generate`; usually LAN (`OnboardingDeps.kt:42` default `http://mybox.local:11434`), but can be remote (VPN). |
| T4 | llama.cpp on device | `EndpointKind.LLAMACPP_LOCAL` (`ModelRouter.kt:157-158`) | LOCAL; `acceptsImages=false`. Never gated. |
| T5 | Embedding client | `services/ai/.../ai/embed/EmbeddingClient.kt:60-68` (embed), `:124-126` (Ollama `/api/tags` revision) | Text only. Bypasses `ModelRouter` and `CallBudget` entirely; built by `Embedders.kt:28-56`. |
| T6 | Model downloads | `services/orchestration/.../work/LocalModelDownloadWorker.kt:94-95`; URLs `services/ai/.../ai/local/LocalModels.kt:32-63` (huggingface.co) | BULK-OTHER (tens to hundreds of MB). WorkManager constraint in `services/orchestration/.../data/models/LocalModelStore.kt:66-73`. |
| T7 | Place HTTP | `services/connectors/.../place/PlaceHttp.kt:31-104` (`UrlConnectionPlaceHttp`, HTTPS only, 400 ms per-host gap, 4 MB response cap, gzip) | Built once in `services/orchestration/.../work/frames/place/PlaceGraph.kt:69-70`. |
| T8 | Online map tiles | `phone/.../ui/settings/privacy/zones/online/OnlineTileSource.kt:65-70` (HttpURLConnection), hosts `:26-35` (MapTiler with key, OSM debug) | TILES; 512 KB per tile cap, 400 requests per screen visit (`:246`, `:278-280`), disk cache 50 MB (`:149`). Zones screen only. |
| T9 | Canvas API / helper | `phone/.../canvas/api/CanvasApi.kt:199`, `phone/.../canvas/CanvasGraph.kt:71`, `phone/.../canvas/helper/HelperDevSession.kt:91` | INBOUND LAN server (Wi-Fi only). Not outbound internet. Not gated. |
| T10 | Speech | `services/voice/.../android/SpeechIntents.kt:22` (`EXTRA_PREFER_OFFLINE`), `AndroidOnDeviceStt.kt:61,90` (`createOnDeviceSpeechRecognizer`), `SpeechPacks.kt:33-59` (pack download) | On-device recognizer; pack downloads are done by the system recognizer, not our UID (UNVERIFIED whether it honours roaming). Out of scope; mention in the checklist. |

No update / release check exists (grep for `releases`, `api.github` found nothing). No WebSocket. The Nexus modules have no outbound HTTP.

### 1.2 Model calls by feature (all through `ModelRouter.call`, `ModelRouter.kt:176`, permit at `:196`)

| Feature | Caller file:line | CallClass | Sends | Class |
|---|---|---|---|---|
| Live describe (VISION_EXTRACT, up to 3 photos) | `work/frames/KeyframePipeline.kt:238,253,353,509`; extractor `KeyframeGraph.kt:225-228` -> `KeyframeExtract.kt:53` | EXTRACT | photos + prompt | BULK-PHOTO |
| Caption queue / backfill describe | `KeyframePipeline.kt:272,448,477-478`; `KeyframeGraph.kt:237,293` | CAPTION_QUEUE / BACKFILL | photos | BULK-PHOTO |
| Scene cluster summary | `KeyframeGraph.kt:270-300` (`ClusterContract.call`) | EXTRACT/BACKFILL | representative photos | BULK-PHOTO |
| Describe for Ask / on demand (user) | `KeyframePipeline.kt:149,305,317,365`; `work/frames/recall/DescribeOnDemand.kt:54,98` | USER | photos | BULK-PHOTO |
| Night describe-before-delete | `orchestration/forget/NightIndexWorker.kt:132` (schedule `work/OrchestrationScheduler.kt:320-331`) | BACKFILL | photos | BULK-PHOTO |
| Suspect sweep re-describe | `orchestration/forget/SuspectSweepWorker.kt:102` | BACKFILL | photos (re-describe) | BULK-PHOTO |
| Legacy SUMMARIZE from frames | `work/QueueDrainWorker.kt:244,264,306` (`maxImages = 3`) | EXTRACT | photos | BULK-PHOTO |
| Image intent (INTENT_CLASSIFY) | `work/frames/intent/IntentSamplers.kt:58-63` | EXTRACT | 1 photo | BULK-PHOTO |
| Place Guide PLACE_MATCH | `work/frames/place/PlaceSamplers.kt:83-89` (admit at `:74`) | EXTRACT | 1 photo + candidate names | BULK-PHOTO |
| Ask answer | `orchestration/AnswerService.kt:372` (photo gate `:508-518`) | USER | question + memory text, photos only if Ask photos is on | SMALL, or BULK-PHOTO with photos |
| Ask condense / time anchor | `AnswerService.kt:196,212` | USER | question text | SMALL |
| Critic | `work/QueueDrainWorker.kt:351`; `orchestration/ExtractFollowUp.kt:45,66-71` | EXTRACT | memory text | SMALL |
| Explicit remember | `QueueDrainWorker.kt:369` | USER | user's words | SMALL |
| Resummarize (Forget) | `orchestration/forget/ResummarizeWorker.kt:145` | BACKFILL | text | SMALL (BULK by count) |
| Moment derive | `canvas/moment/DerivePass.kt:41` | BACKFILL | text | SMALL (BULK by count) |
| Nudges | `orchestration/nudge/NudgeEngine.kt:110` | NUDGE | text | SMALL |
| Canvas experiments | `canvas/PythonExecutor.kt:46`, `canvas/NodeExecutor.kt:59`, `canvas/LiveExtract.kt:119`, `canvas/AskDebug.kt:72,157` | EXPERIMENT (no budget) | may carry photos | BULK-PHOTO (developer) |
| Test bed / role runs / diagnostics | `services/ai/.../ai/run/TestBed.kt:88`, `ai/run/RoleRunner.kt:51-54`, `phone/.../ui/settings/Diagnostics.kt:482`, `ai/EndpointTester.kt:28-32` | USER/OTHER | user-chosen | SMALL or BULK-PHOTO, user-initiated |

### 1.3 Place, tiles, embeddings, downloads

| Feature | File:line | Sends / receives | Class |
|---|---|---|---|
| Wikipedia geosearch for a 5 km cell | `connectors/place/PlaceSources.kt:77-78` | GET, bbox of the cell; up to 500 hits back | SMALL (response tens of KB, UNVERIFIED) |
| Wikipedia extracts | `PlaceSources.kt:80-84` (batches of 20) | GET page ids; 3-sentence extracts back | SMALL |
| Wikidata entities | `PlaceSources.kt:113-116` (batches of 50) | GET Q-ids; claims back (can be large) | SMALL/MEDIUM |
| Overpass (3 mirrors) | `PlaceSources.kt:148-149,183-222` | form POST query for the cell bbox | SMALL/MEDIUM (response capped 4 MB) |
| Cache-aside cell fetch while walking | `memory/place/PlacePack.kt:108-118`, called from `PlaceSampler.kt:232-235` (`networkUp()` -> `candidates`) | the four above, per stale/missing cell | SMALL per cell |
| Seed an area / trip presets | `PlacePack.kt:150-185` (max 4000 cells, `:283`); UI `phone/.../ui/settings/place/PlaceGuideSettings.kt:111-130`; control `phone/.../place/AndroidPlaceGuideControl.kt:36-39` | the four above, for every cell | BULK-OTHER (many requests) |
| Online tiles (Zones map) | `OnlineTileSource.kt:65-70,246` | GET z/x/y; PNG back | TILES |
| Remote embeddings | `EmbeddingClient.kt:60-68`; bulk callers `orchestration/EmbeddingWorker.kt:164,204`, night `IndexMaintenance` (`NightIndexWorker.kt`) | memory/query text; vectors back | SMALL per call, BULK-OTHER as backfill |
| Model downloads | `LocalModelDownloadWorker.kt:94-95` | Range GET | BULK-OTHER (download) |

### 1.4 Existing network handling (what the design reuses)

- `phone/.../log/DiagProbe.kt:99-119`: default-network callback, logs `online validated metered roaming transport` on change. Reuse its classification code; it is the only place that reads `NOT_METERED`/`NOT_ROAMING`.
- `KeyframeGraph.kt:203-213` `watchNetwork` (default-network `onAvailable` -> `pipeline.onNetworkReturn()`), `:201` `admitBackgroundCall` (battery, validated, power), `:216-222` `networkUp`.
- `KeyframePipeline.kt:74-89` `classify`: an IOException or offline is `Failed("offline", countsAttempt = false)` and pauses the loop (30 s doubling to 5 min) until `onNetworkReturn`. The data-saver block reuses exactly this "no attempt, park, resume on signal" path.
- `CallBudget.kt` (token bucket behind `CallGate`): a refusal raised *before* `gate.withPermit` spends no token.
- `NightIndexWorker.kt` header: an undescribed photo is never deleted; it is retried every night. So queuing photos for Wi-Fi is safe (storage grows, nothing is lost).
- `AnswerService.kt:508-518` `photosAllowedNow`: the Ask-photos gate; add one data-saver term.
- WorkManager constraints today: only `LocalModelStore` sets a network type (UNMETERED/CONNECTED). Frame ingest `NOT_REQUIRED` (`OrchestrationScheduler.kt:45-47`); queue drain, embeddings, nudges, proactive, place recovery, night jobs: none.
- No "offline only" mode exists yet; it is planned with the 1.4.3 model resolver (`handoffs/overnight-queue-2026-11-20.md` item 7). The resolver design doc `2026-11-20-place-guide-resolver-and-semantic-ranking.md` does not exist yet.

## 2. Policy design

### 2.1 Network cost classification (`NetworkCost`)

One process-wide `NetworkCostMonitor` (new, `services/ai` or a small `core` module so both `ai` and `connectors` can read it), fed by `registerDefaultNetworkCallback` `onCapabilitiesChanged`/`onLost` (as DiagProbe), holding a `StateFlow<NetSnapshot>`:

`NetSnapshot(transport, validated, notMetered, notRoaming, known, restrictBackground, countryMismatch, travelSwitch)`

| Cost | Rule |
|---|---|
| `FREE` | `known` and validated and `NOT_METERED` and `NOT_ROAMING` and transport Wi-Fi or Ethernet, and the "I'm abroad" switch does not force it (the switch only affects cellular) |
| `HOME_MOBILE` | `known`, metered, `NOT_ROAMING`, no country mismatch, travel switch off |
| `ROAMING` | `NOT_ROAMING` absent, or SIM country != network country, or the travel switch is on and the transport is cellular |
| `UNKNOWN` -> treated as `ROAMING` | no callback yet, null capabilities, a SecurityException, VPN with unknown underlying network |
| `OFFLINE` | no validated network (existing behaviour: queue, no attempt) |

Signals and their failure modes:
- `NET_CAPABILITY_NOT_ROAMING` (API 28, our minSdk is 29): set by the telephony stack from ServiceState and carrier config. Unreliable when: (a) travel eSIMs whose home network is foreign, reported as roaming even in their "home" country, or the reverse (UNVERIFIED); (b) carriers that mark national roaming as home, or Indian operators showing roaming in another circle (Sikkim) (UNVERIFIED either way); (c) dual SIM: the capability belongs to the data SIM's network only, which is what matters; (d) VPN as the default network: Android 10+ VPNs are metered by default and copy some underlying capabilities (UNVERIFIED for `NOT_ROAMING`); (e) hotspot: a phone or MiFi hotspot that is itself roaming shows up as Wi-Fi and may be `NOT_METERED` unless it sends the Android metered DHCP hint or the user marks it metered (UNVERIFIED for iPhone/MiFi hotspots).
- Country mismatch: `TelephonyManager.getSimCountryIso()` vs `getNetworkCountryIso()` (no runtime permission needed, UNVERIFIED). A second, independent roaming signal. Never logged or stored (it is a location).
- `ConnectivityManager.getRestrictBackgroundStatus()`: Android's own Data Saver. If ENABLED and we are not whitelisted, treat metered as ROAMING for automatic work.
- The **"I'm abroad" switch** (manual): forces every cellular network to ROAMING. This is the fail-closed backstop because roaming cannot be device-tested at home. Optional later: auto-on by trip dates (open question).
- Fail-closed choice: for the data saver, *unknown is costly* (ROAMING). This deliberately differs from `networkUp()` (unknown = online), which stays as is for "offline" classification.

### 2.2 Traffic kinds and modes

Traffic kinds (decided at the choke point, not by callers guessing):
- `PHOTO`: a model request whose images will actually be sent (`canSend` in `ModelRouter.call:189`).
- `BULK_OTHER`: seeding, tiles, model downloads, embedding backfill, BACKFILL-class text calls, canvas EXPERIMENT.
- `SMALL`: text model calls (USER, NUDGE, EXTRACT text: critic), cache-aside place cell fetch, query embeddings, endpoint Test.
- `LOCAL`: `LLAMACPP_LOCAL`; Ollama at a private/link-local/.local host *while the transport is Wi-Fi* (on cellular it is treated as a remote endpoint, which covers a Tailscale-to-home setup).

Callers that know better mark their kind with a coroutine context element `DataUse(kind)` (same pattern as `NoNudge` and the zone ticket), e.g. EmbeddingWorker marks `BULK_OTHER`.

Modes (Settings > Data saver), for PHOTO and BULK_OTHER:

| Mode | FREE | HOME_MOBILE | ROAMING |
|---|---|---|---|
| Wi-Fi only | yes | queue/drop | queue/drop |
| **Wi-Fi and home mobile data** (default, changed after review) | yes | yes, within cap | queue/drop |
| Anywhere | yes | yes, within cap | yes, within cap |

SMALL traffic: allowed on FREE and HOME_MOBILE; on ROAMING only if "Small requests on roaming" is on (default ON, within the cap; open question 1). LOCAL: always allowed.

"Offline only" (when the 1.4.3 resolver adds it) overrides everything: nothing leaves, the card says so. Precedence: Offline only > Pause/quiet hours/zones (existing) > Data saver > CallBudget pace.

### 2.3 Per-day cap

`DataLedger` (SharedPreferences, content-free counters only), keyed by the local date:
- Counters per day: `{cost in FREE/HOME_MOBILE/ROAMING} x {kind}`: calls, txBytes, rxBytes; plus `queued`, `dropped`, `sentAnyway` counts per kind.
- Caps apply to costly traffic only (HOME_MOBILE + ROAMING combined): `capBytes` (default 10 MB), `capPhotoCalls` (default 30; only relevant in modes that allow photos on mobile). Wi-Fi traffic is counted for the status card, never capped.
- Check before the call: `used + estimatedTx + reservedRx <= cap`; `estimatedTx` is the request body size (known for OkHttp `RequestBody.contentLength()` and the PlaceHttp form/URL); `reservedRx` = rolling average reply size for that kind (default 8 KB text, 64 KB place cell). Record actual bytes after the call.
- When the cap is hit: automatic PHOTO/BULK queue (or drop, per table below); SMALL automatic calls stop; the user's own Ask text still goes (counted, shown as over the cap) unless the user says otherwise (open question 3).
- Rollover: day key = `LocalDate.now(ZoneId.systemDefault())` read at each record. A later date starts new counters (yesterday's totals kept for a 7-day history, then dropped). A date *earlier* than the stored one (clock set back) keeps counting into the stored day: never a free reset. A time-zone change just changes the key; IST and Sri Lanka are both UTC+05:30, so no trip day is short.
- Honest totals: measured bytes are payload only (TLS and headers not counted); the card adds ~1 KB per call and says "about". Daily cross-check line from `TrafficStats.getUidTxBytes/RxBytes` deltas attributed to the cost class current at each network change (UNVERIFIED accuracy per device); shown only as "System count for this app".

### 2.4 Queue or drop

| Work | Blocked by data saver | Why |
|---|---|---|
| Live describe, caption queue, cluster summary, legacy SUMMARIZE-from-frames | **queue** (frames back to PENDING, no attempt; loop parked) | Memories are the product; photos stay on the phone. |
| Night describe-before-delete, suspect sweep | **queue** (photo not deleted: existing rule) | Retention never deletes an undescribed photo. |
| Image intent | **drop** (log count) | Time-sensitive, optional. |
| PLACE_MATCH | **drop** (count "place cards skipped") | A card for where you stood an hour ago is useless; the place text candidates still show offline from the pack where the HUD uses them without a match (UNVERIFIED: check PlaceSampler has a no-match path). |
| Ask with photos | **strip photos, answer from text**; reply notes "without photos (data saver)" | User asked now; text answer is cheap. |
| Describe on demand (user tap) | **ask**: "Uses about 200 KB of roaming data. Send once / Wait for Wi-Fi" | Explicit user intent; counted as `sentAnyway`. |
| Cache-aside place cell fetch | SMALL rules; **seeded or partial cells are fresh while costly** (stale-while-costly) | Fixes finding 2. |
| Seed area | **refuse** unless FREE ("Seeding needs Wi-Fi") | Hundreds of requests. |
| Online tiles | cached tiles only unless FREE; banner | User sees the offline PMTiles map anyway. |
| Embedding backfill | **defer** (worker returns success, re-kicked on FREE) | BULK by count. |
| Query embedding for Ask | SMALL | One small call. |
| Model downloads | already Wi-Fi only by default; "Allow mobile data" becomes `NetworkType.NOT_ROAMING` instead of `CONNECTED` | Never download hundreds of MB on roaming. |
| Canvas EXPERIMENT | **refuse** unless FREE | Developer work. |
| Endpoint Test | SMALL, user-initiated: allowed | |

### 2.5 Where the gate sits (code shape for the implementer)

- `ModelRouter.call` (`ModelRouter.kt:176-224`): after `canSend` (`:189`) and *before* `gate.withPermit` (`:196`), ask `DataPolicy.decide(kind, endpoint, estimatedBytes)`. A block throws `DataSaverBlockedException` (a `ModelCallException` with a new `FailureKind.DEFERRED_NETWORK`, retryable). Rules: no token spent; **no text-only retry** after a data-saver block on a photo call (the `:211-217` retry must rethrow it, like `PhotoForgottenException`); the fallback endpoint is tried only if it is LOCAL.
- `KeyframePipeline.classify` (`:74-89`): `DEFERRED_NETWORK` -> `Failed("data_saver", countsAttempt = false)`; the loop parks like offline. Resume: `KeyframeGraph.watchNetwork` also listens to `NetworkCostMonitor` and calls `pipeline.onNetworkReturn()` when the policy opens (cost becomes FREE, mode or cap changes, new day). No 5-minute polling for days on roaming.
- `PlaceSampler`: `networkUp` becomes `networkUp() && policy.allows(SMALL)`; `admit` adds `policy.allows(PHOTO)` (`PlaceSamplers.kt:74`, same for `IntentSamplers.kt:52`) so the drop happens before the photo is even loaded.
- `PlacePack.isFresh` (`:214-217`): takes a `costly` flag; seeded or partial cells count as fresh while costly.
- `UrlConnectionPlaceHttp` wrapped by `MeteredPlaceHttp` (decorator in `connectors/place`): policy check + byte count (count compressed bytes: wrap `c.inputStream` before the GZIP stream).
- `Http.modelClient`/`downloadClient`: a counting network interceptor (tx = body length, rx = counted source); `EmbeddingClient` gets the same policy check as the router.
- `AnswerService.photosAllowedNow` (`:508-518`): add `policy.allows(PHOTO)`.
- `LocalModelStore.networkType` (`:72`): `CONNECTED` -> `NOT_ROAMING`.
- WorkManager: in-process gating is the real mechanism (most uploads run in the in-process keyframe loop, not in workers). Workers keep their constraints; do not add `UNMETERED` to the night job (it also does local index work). EmbeddingWorker: on a block, `Result.success()` and a one-shot re-enqueue with `NetworkType.UNMETERED` when the mode is Wi-Fi only (UNVERIFIED that WorkManager's UNMETERED excludes metered-marked Wi-Fi: it uses `NOT_METERED`, so it should).

### 2.6 Retry and backoff

- A data-saver block never uses an attempt and never moves a frame toward FAILED_FINAL.
- Parked until a policy-open event; a safety re-check every 30 min (cheap, no network).
- Backlog drain on Wi-Fi: existing order (dwell first, then newest) and the CallBudget pace (one token per 10-15 s, up to 3 photos per call). Example: 9 days x 200 frames (UNVERIFIED rate) = 1800 frames, about 600 calls, about 2-2.5 h on hotel Wi-Fi. Hotel Wi-Fi behind a captive portal is not VALIDATED and does not count as FREE.
- Transient provider errors on Wi-Fi keep today's `RetryPolicy`.

### 2.7 Interplay with Detailed log, Forget, Delete all, Offline only

- Detailed log (`DeveloperModeStore.kt:49`, default ON): summary lines (network change, policy change, cap reached, day rollover, drain start) always; per-call `sent`/`blocked` lines only with Detailed log on. All lines content-free (section 4).
- Forget: the ledger holds counts only, no ids, so Forget does not touch it. The "waiting for Wi-Fi" number is a live DB count of PENDING frames, so a forgotten queued photo disappears from it by itself; the router's liveness re-check (`ModelRouter.kt:196-206`) still drops a photo forgotten while queued.
- Delete all: resets today's counters and the 7-day history (they are harmless, but a clean slate matches user expectation; open question 8).
- Offline only (future): data saver is subordinate; its card shows "Offline only is on: nothing goes out". When the resolver has an on-device backend for a role (offline vision), `DataPolicy` exposes `allows(kind)` so the resolver picks the local model instead of queueing (slice 3).

## 3. UI sketch and strings

### 3.1 Settings root row (`phone/.../ui/settings/SettingsRoot.kt:106-108`, next to Privacy)

`Data saver` - subtitle dynamic: `Photos on Wi-Fi only` / `Photos on Wi-Fi only · 46 waiting` / `Roaming: photos paused`.

### 3.2 Settings > Data saver

```
Data saver
Now: Roaming (phone reports roaming)          <- or "Wi-Fi", "Home mobile data", "Unknown network: treated as roaming", "Offline"

Photos and big uploads
  (o) Wi-Fi only                      Photos and other big uploads wait for Wi-Fi.
  ( ) Wi-Fi and home mobile data      Also uses mobile data at home. Never when roaming.
  ( ) Anywhere                        Any connection, roaming too, up to today's limit.

[x] I'm abroad                        Treat all mobile data as roaming, whatever the phone reports.
[x] Small requests on roaming         Questions, place facts and suggestions as text. Never photos.

Daily limit on mobile data            [5 MB] [10 MB] [25 MB] [50 MB]
Photo uploads per day on mobile data  [0] [10] [30] [100]     (shown only when the mode allows photos on mobile)

Today                                  (status card, 3.3)
Before you travel                      Seed your trip areas on home Wi-Fi: Settings > Place Guide.   [Open]
```

### 3.3 Status card (Data saver screen and a compact copy on the Status tab)

```
Today on mobile data   1.2 MB of 10 MB (about)
  Photos sent          0
  Photos waiting       46   (they upload on Wi-Fi)
  Place cards skipped  12
  Text requests        31   (0.4 MB)
Today on Wi-Fi         230 photos sent (about 14 MB)
System count for this app on mobile today: 1.4 MB
Counts only. Sizes are estimates.
```

### 3.4 Strings elsewhere

- Ask reply footer: `Answered without photos: data saver.`
- Describe on demand dialog: title `Use roaming data?`, body `This photo uses about 200 KB.`, buttons `Send once` / `Wait for Wi-Fi`.
- Seed button when not FREE: `Seeding needs Wi-Fi.`
- Zones online map: `Online map needs Wi-Fi (data saver). Showing the offline map.`
- Cap reached notice (once a day, no notification sound): `Today's mobile data limit is reached. Photos and suggestions wait for Wi-Fi.`
- On-device models (existing `LocalModelsSource.kt:356-358`): `Allow mobile data` subtitle becomes `Models are large; never over roaming`.

Compose test tags: `data_saver_mode_wifi`, `data_saver_mode_home`, `data_saver_mode_anywhere`, `data_saver_abroad`, `data_saver_small_roaming`, `data_saver_cap_bytes_<n>`, `data_saver_cap_photos_<n>`, `data_saver_status`.

## 4. Log lines and tests

### 4.1 Log lines (tag `PcData`; never a URL, host, endpoint name, cell, coordinates, country, SSID, frame/memory id, prompt or reply text)

- `PcData net cost=ROAMING transport=cellular metered=true roaming=true known=true abroad=false mismatch=true restrict=false` (on change only; `mismatch` is a boolean, never the countries)
- `PcData policy mode=WIFI_ONLY smallRoaming=true capKb=10240 capPhotos=30` (on change)
- `PcData blocked kind=PHOTO cls=EXTRACT cost=ROAMING action=queued` (Detailed log)
- `PcData sent kind=PHOTO cls=EXTRACT cost=FREE txKb=212 rxKb=3` (Detailed log)
- `PcData sendOnce kind=PHOTO cost=ROAMING txKb=198`
- `PcData cap reached usedKb=10240 capKb=10240 photos=0` (once per day)
- `PcData day rollover photosSent=230 photosQueued=46 dropped=12 costlyKb=1228 wifiKb=14336`
- `PcData drain start waiting=46 cost=FREE` / `PcData parked reason=roaming`
- `PcData sys costlyKb=1433 measuredKb=1228` (daily cross-check)
- `PcPlace cell fresh-while-costly seeded=true` (counts only, as today's PcPlace lines)

### 4.2 Tests (JVM unless marked device)

- `NetworkCostTest`: truth table over transport x validated x NOT_METERED x NOT_ROAMING x known x abroad x mismatch x restrict; null caps -> ROAMING; VPN unknown underlying -> ROAMING.
- `DataPolicyTest`: mode x cost x kind x cap matrix; LOCAL always allowed; Ollama private host on Wi-Fi LOCAL, on cellular remote.
- `DataLedgerTest`: rollover on date change; clock set back keeps counting; zone change; persistence across process restart; concurrent increments; Delete all reset.
- `ModelRouterDataSaverTest`: a blocked photo call throws `DataSaverBlockedException` before the permit (fake CallGate sees no `withPermit`), the provider is never called, no text-only retry, fallback tried only when LOCAL; `imagesDelivered` false.
- `KeyframePipelineTest`: `DEFERRED_NETWORK` -> `countsAttempt=false`, frames PENDING, loop parked, resumes on the policy-open signal.
- `PlaceSamplerTest`: PLACE_MATCH dropped on ROAMING (no photo load), cell fetch skipped; `PlacePackTest`: seeded and partial cells fresh while costly, stale on FREE.
- `MeteredPlaceHttpTest`: counts compressed bytes; blocks before connect (no byte leaves).
- `CountingInterceptorTest` (MockWebServer): tx/rx bytes for chunked replies.
- `AnswerServicePhotoGateTest`: photos stripped on ROAMING; footer text.
- `LocalModelStoreTest`: allow mobile data -> `NOT_ROAMING`.
- `PcDataLogHygieneTest`: every `PcData` line matches `^[a-z ]+( [a-zA-Z]+=[A-Za-z0-9_.-]+)*$` and contains no `http`, `.`-separated host, digits pattern of coordinates, or UUID.
- Compose: Data saver screen strings and tags; status card numbers.
- Device (Wi-Fi + SIM, no roaming possible at home): (1) Wi-Fi off, mobile on: photos queue, card counts, no `sent kind=PHOTO cost=HOME_MOBILE`; (2) Wi-Fi back: drain starts; (3) home Wi-Fi marked metered in Android settings: treated as HOME_MOBILE; (4) "I'm abroad" on, mobile: everything ROAMING; (5) hotspot from a second phone; (6) charger overnight on mobile only: night job uploads nothing; (7) emulator roaming simulation (emulator console `gsm data roaming`, UNVERIFIED) for the NOT_ROAMING path; (8) in Sri Lanka, day 1: read the `PcData net` line to confirm what the phone reports.

## 5. Slice plan

Agent-day = one Sonnet implementer day incl. tests; review by Codex medium, or Opus low effort if the Codex quota is out (resets 21 Nov 21:36).

### Slice 1: before Sikkim (target merged 26-27 Nov, installed and device-tested by 29 Nov) - about 3 agent-days

1. `NetworkCostMonitor` + `NetworkCost` (fail-closed, abroad switch, country mismatch, restrict-background) - 0.5
2. `DataPolicy` (modes, kinds, LOCAL detection) + settings store + `PcData` logs - 0.5
3. Router gate + `DataSaverBlockedException` + no-text-retry + pipeline classify/park/resume - 0.75
4. Place: admit/networkUp terms, PLACE_MATCH drop, intent drop, stale-while-costly in `PlacePack`, seed refused unless FREE - 0.5
5. Ask photos gate + footer; `NOT_ROAMING` for model downloads - 0.25
6. Minimal ledger (counts per day: photos sent/waiting/skipped by cost, no bytes yet) + Settings > Data saver screen + status card (counts) - 0.5
Then review + device tests 1-6.

Sikkim is domestic: the main value there is Wi-Fi only protecting the plan quota and patchy coverage, and confirming on device what an Indian SIM reports outside its home circle (`PcData net` line).

### Slice 2: before Sri Lanka (target installed by 15 Dec) - about 2 agent-days

1. Byte accounting: OkHttp counting interceptor, `MeteredPlaceHttp`, tiles counter, TrafficStats cross-check - 0.5
2. Per-day caps (bytes, photo calls), cap notice, rollover history - 0.5
3. Embeddings gate (query SMALL, backfill deferred + re-kick), tiles gate + banner, canvas EXPERIMENT gate - 0.5
4. Describe-on-demand "Send once" dialog; status card bytes + 7-day history; root row subtitle - 0.5

Ops on home Wi-Fi after slice 2 (no code): re-seed all Sri Lanka presets 15-21 Dec (belt and braces with stale-while-costly), seed Sikkim/Darjeeling/West Sikkim 25-30 Nov, Hyderabad box now for 22-23 Nov (Wi-Fi only default does not matter there; it is home). Also set Android's own mobile-data warning and turn on the abroad switch before boarding.

### Slice 3: with the 1.4.3 resolver / v2 - about 1-1.5 agent-days

Policy feeds the resolver: on a costly network a role with an on-device backend (offline vision, on-device embeddings) runs locally instead of queueing; "Offline only" precedence; status card shows "described on the phone".

### Risks

- Roaming flag wrong on the actual SIM/eSIM in Sri Lanka (cannot test before 23 Dec): mitigated by the abroad switch and country mismatch; tell the user to switch it on.
- Hotspot or MiFi Wi-Fi that is itself roaming looks FREE: mitigated only if the user marks it metered in Android Wi-Fi settings; consider a "Treat this Wi-Fi as mobile data" list later (needs an SSID hash; location permission already held).
- VPN on: likely everything looks metered (blocks too much, safe direction) (UNVERIFIED).
- `ModelRouter.call` is the most-reviewed code in the app (codex r9-r14 rules): keep the gate a pure pre-check before the permit; do not touch the liveness/upload-slot logic.
- Queue growth: 9 days of photos waiting; storage use grows (photos are already kept until described), and the drain needs a few hours of Wi-Fi.
- Place cards outside seeded areas are poorer on roaming (no cell fetch) unless "Small requests on roaming" is on.
- Schedule: install freeze and device testing need the user; slice 1 is tight for 1 Dec if review finds issues.

## 6. Open questions for the user

1. Defaults OK? Photos: Wi-Fi only. Small text requests on roaming: ON within the limit, or OFF?
2. Limit values: 10 MB a day and 30 photo uploads a day on mobile data?
3. When the limit is reached, should your own spoken questions (text only) still go out?
4. Tapping "describe" on roaming: ask each time ("Send once / Wait for Wi-Fi"), or always wait?
5. "I'm abroad": manual only, or switch on automatically on 23 Dec and off on 1 Jan?
6. In Sri Lanka, which data: Indian SIM roaming pack, a local SIM, or a travel eSIM? (A local SIM reports "not roaming", so with "Wi-Fi and home mobile data" photos would upload on it.)
7. Will you use a phone hotspot or a pocket Wi-Fi in Sri Lanka or Sikkim?
8. Should Delete all also reset the data counters?
9. Place cards on roaming: drop the photo match (proposed), or queue the photo and show the card later?
10. Do you use a VPN (for example to reach a home Ollama) while travelling?
11. Should the "photos waiting" count also show on the glasses, or phone only?


## 7. Slice 1 as built, and what changed after review (2026-11-20)

Built: pure policy (`core/model/.../core/net/DataSaver.kt`), `DataSaverStore` / `NetworkCostMonitor` / `DataSaverGraph` (`services/ai/.../datasaver/`), the router gate, pipeline park, Place Guide and intent admit, stale-while-costly cells, worker gates, Settings > Data saver. Not built (still slice 2): byte accounting, daily caps, embeddings / tiles / canvas gates, "Send once" dialog, Ask footer.

Changes after the Opus review:
- **Default mode is now "Wi-Fi and home mobile data"** (was Wi-Fi only). Roaming, unknown networks and everything while "I'm abroad" is on stay blocked; "Wi-Fi only" is one tap away. The earlier remark that the default "does not matter at home" was wrong: at home on mobile data the first default would have held every photo. With the new default a current user's photos still go over home mobile data; only roaming / unknown / abroad / metered-Wi-Fi-abroad change behaviour.
- **No backlog shedding while photo uploads are blocked** (`KeyframePipeline.shedAllowed`). The 30-minute recheck and network-return wakes used to run `shedBacklog`, which would thin PENDING frames to NEAR_DUP and retention would later delete them undescribed.
- **Place Guide online but photo not allowed** takes the offline path: the name-and-distance "nearby" card from the pack, no call, log `call=skipped_data_saver`.
- VPN over Wi-Fi: the real (non-VPN) Wi-Fi network decides FREE; a VPN naming no transport is unknown. UNVERIFIED on a device.
- Wi-Fi that never validates: after 10 minutes the status line says remote photos wait; LAN and on-device endpoints were never blocked.
- `onLost` only counts for the current default network; no active network at start is UNKNOWN, not OFFLINE.
- Queue drain defers a blocked job without an attempt; a blocked primary stays a `DataSaverBlockedException` even when the fallback fails; user seeds are refused unless the network is FREE ("Seeding needs Wi-Fi"); the counter line is "blocked" (calls), and the card shows the live PENDING count; the night job's local index maintenance is no longer gated (only the describe step is).

### Round 3 (2026-11-20, Opus re-review)

- **Parked frames survive restarts and races.** A frame inserted while photo uploads are blocked is born parked (`lastError = 'data_saver'`, `RokidFrameInbox`). The data saver also persists a "blocked since" mark in `data_saver.xml` whenever it sees uploads blocked; the first unblocked drain (this process or after a restart) parks everything waiting SYNCHRONOUSLY before `shedBacklog` and then clears the mark. The in-process gate-open listener still tags too, but nothing depends on its timing. The idea of also parking by "oldest PENDING older than 30 min" was NOT built (it would park ordinary frames in tests and in a long battery-saver stretch without a block).
- **Cap.** Only the newest 300 parked frames are protected from shedding (`FrameStateDao.PROTECTED_PARKED`); older parked frames in a longer backlog are shed as normal (salient ones go to the caption queue). The protected set is excluded from the shed threshold, so waiting frames do not make live frames thin at 21. This bounds the upload burst after Wi-Fi returns; the Data saver screen says so.
- Frames whose earlier error is only timeout, abandoned or offline are parked too; solo_rebuild and describe_before_delete are left alone.
- The status screen refreshes off the main thread, and "photos waiting" shows 0 once uploads are allowed again.

### Slice 2 as built (2026-11-20, branch `worktree-agent-a5a580ee93d92a18e`, not merged)

Built under the user's constraint that **nothing changes for a default user on home Wi-Fi or home mobile data**. The open questions of section 6 are still unanswered; every default below is a proposal and can be changed by editing constants (`DailyCaps.DEFAULT_MB`, `DEFAULT_PHOTOS`) or the screen's choices.

**Default behaviour statement.** With default settings (mode "Wi-Fi and home mobile data", "I'm abroad" off, "Daily limit on mobile data" off, "Treat Wi-Fi as metered" off) a user on Wi-Fi or on home mobile data sees the same decisions as slice 1: nothing is capped, however much has been sent (`DailyCaps.limitFor` returns null on FREE, OFFLINE and on HOME_MOBILE unless the user's own limit is on; tests `aDefaultUserHasNoLimitAnywhereWhateverHeHasUsed` and `aDefaultUserOnHomeMobileDataOrWifiNeverHitsAnyLimitHoweverMuchIsSent`). The only new thing a default user gets is counters and two status lines. Roaming and unknown networks stay blocked exactly as in slice 1 (the cap cannot open anything the mode does not).

What was built:

1. **Byte counter at the single choke point.** `ModelRouter.call` wraps every provider call (`send` inside the permit): request bytes = prompt UTF-8 + the Base64 photo strings as sent + 512 B envelope; reply bytes = reply text UTF-8 + 512 B. Counted only when the network class is costly (HOME_MOBILE, ROAMING, UNKNOWN, RESTRICTED; Wi-Fi and offline are not), never for an on-device endpoint, and a failed call still counts what it sent. Kind: PHOTO when the call carried photos, else SMALL (the text retry of a photo call is SMALL). `DataGate.sent(kind, tx, rx, photos)` adds to `MobileDataLedger` (`core/model/.../net/DataSaver.kt`), kept in `data_saver.xml` under `mobile_*` keys: bytes per kind and a photo count, per local day, with the same rollover and clock-set-back rule as `SkippedLedger` (a later date starts new counters; an earlier date keeps counting into the stored day). Counts only. NOT built: an OkHttp interceptor (TLS and headers are not counted: the number is payload, "about"), counting of place, tile, embedding and model-download traffic (those paths do not pass the router; still slice 3 / follow-up), the `TrafficStats` cross-check line.
2. **Cap decision** (`DailyCaps.limitFor`, pure, table-tested). A limit applies only while the network is costly AND either (i) "I'm abroad" is on or the network is ROAMING/UNKNOWN, and the mode is Anywhere: 10 MB and 30 photo uploads a day; or (ii) the user turned on "Daily limit on mobile data": their own MB (5/10/25/50) and photos (0/10/30/100), which then also apply on home mobile data. Both: the lower numbers. `DataPolicy.decide` takes today's usage and returns `daily_limit` when the byte limit is used up (photos and big jobs) or the photo limit is (photos only). Text (SMALL) is never stopped by a cap; "Small requests on roaming" off still blocks text on roaming as before. A call that starts under the limit can end a little over it (one call is up to 3 photos); no pre-estimate. `effectiveMode` no longer downgrades Anywhere while abroad: abroad + Anywhere now uploads under the cap.
3. **When the cap is reached** photos park exactly like a hold: `DataSaverGraph.heldBack()` is true (the decision includes the cap), the router throws `DataSaverBlockedException("daily_limit")`, the keyframe loop parks without an attempt, frames are marked parked (never shed, protected cap 300 unchanged), the status line on Settings > Data saver says "Daily limit reached. Photos and big uploads wait until midnight or Wi-Fi; text questions still go.", one `PcData daily limit reached` log line per day. **Midnight:** `DataSaverGraph.startMidnightWatch()` (started at creation) sleeps to the next local midnight (zone-aware, DST-safe, follows the injected clock), logs `PcData day rollover`, calls `refreshState()`; the counters read zero on the new date, the open transition fires the existing open listener, which parks and wakes the loop (`onNetworkReturn`). Raising or switching off the limit wakes it the same way (settings flow).
4. **Status lines** (Settings > Data saver): "Photos waiting for Wi-Fi now: N" stays the live parked-frame count; new "Sent today on mobile data: X MB / N photos" and "Limit today on this connection: ..." (only when one applies); "Blocked today" and its three lines stay as separate lines. The row subtitle says "Abroad: photos up to the daily limit" for abroad + Anywhere.
5. **Send once** for the user's own describe (Find a photo > Describe this photo / this scene): `PhotoSearchViewModel` asks `KeyframeGraph.photoHeldForPrompt()` (the data saver would hold a photo upload; not Offline only); if so it shows the dialog "Send over mobile data? ... [Send over mobile data once] [Wait for Wi-Fi]". Wait is the default and dismissing is waiting (status: "Not sent. Describe it again on Wi-Fi."). Once runs that single describe inside `withContext(SendOnce)`; `ModelRouter.call` honours the marker only when `callClass == USER` and the call carries photos, asks `DataGate.decideOnce`, which lifts a mode block or the daily limit for that call (reason `send_once`, log `PcData send once allowed kind=PHOTO`), still refuses Offline only, and is counted in the byte ledger. Nothing is remembered. NOT built: the same dialog for Ask-with-photo (Ask is voice/glasses driven with no phone screen in the loop; it keeps answering from text while the saver holds photos, no footer yet).
6. **Hotspot (f), decided: the global switch only.** A per-network toggle would need a persisted SSID hash (or a network identifier, which Android changes between connections): a name-derived value kept on disk, and a location-class permission read, for little gain. So: Settings > Data saver > "Treat Wi-Fi as metered while I'm abroad" (default OFF). Only with "I'm abroad" on, every WIFI transport is classified ROAMING (`NetworkCosts.classify(s, abroad, wifiMeteredAbroad)`), so photos wait and the bytes count as mobile; Ethernet is not affected. The user turns it off on hotel Wi-Fi. A per-network list remains a possible follow-up if the user wants it and accepts a stored hash.
7. **Seed** is still refused unless the network is FREE (slice 1, unchanged); **logs** carry only fixed tokens, the kind name and counts (`LogContentScanTest` passes unchanged, no new interpolated name); **prefs**: all new keys live in `data_saver.xml`: excluded from backup, cloud backup and device transfer, never restored or exported, kept by Delete all (the design's earlier idea of resetting the counters on Delete all was dropped on the user's instruction: the limit must survive a wipe).

Tests: `DailyCapsTest` (core: decision table incl. default unlimited, abroad cap, user limit, stricter-of-two, send once, hotspot classification, ledger rollover and clock set back), `DataSaverCapTest` (graph: default user, cap and park, Wi-Fi lift, own limit, decideOnce, midnight wake on virtual time, store round trip, only-numbers file), `ModelRouterDataSaverTest` (byte counter at the choke point, send-once class rule), `KeyframePipelineTest` (cap parks, midnight wakes, no attempt, no shed), `PhotoSearchViewModelTest` and `PhotoSearchScreenTest` (the dialog model and screen), `DataSaverScreenTest` (strings, rows, status lines), `BackupRulesTest`, `FamilyInventoryTest` (Delete all keeps).

Doubts: the byte counts are estimates of payload (Base64 photo strings dominate), so the cap is approximate; the 10 MB / 30 photo numbers are unreviewed proposals; the roaming flag itself is still UNVERIFIED on a Sri Lankan SIM (the abroad switch remains the backstop); a user who sets "Anywhere" at home and never turns on "I'm abroad" has no cap until the phone reports roaming (by design: no change at home); `SendOnce` relies on the describe running in the caller's coroutine context (`DescribeOnDemand` does; a future refactor that moves it to another scope would silently turn "once" into a block, which fails safe).
