# Privacy

Proactive Context Agent (PCA) keeps your memory on your phone. This page says what is stored, what can leave the phone and to whom, what is never sent, how long things last, and what Forget and Delete all remove. Every claim points to code or a design doc in the table at the end. Anything I could not confirm is marked **UNVERIFIED**.

PCA has no server of its own and no account. There is no analytics or crash upload that I found in the code (**UNVERIFIED** for third-party libraries; check the merged manifest if this matters to you).

**Which build this page describes** (2026-11-20): the full train (git `166d3fd4`). The conservative trip candidate (git `4436d1a5`) has the street map, the trip log and pin-to-Moment, but **not** the roaming data saver (section 15) or Model privacy (section 16). The build installed on 19 Nov (git `18c1b76a`) has none of these. Nothing on this page has been checked on real hardware yet.

## 1. What PCA stores on your phone

- **Memories, photos, Moments, marks, answers, nudges**: in an encrypted Room database (SQLCipher). The key lives in the Android Keystore and never leaves the phone. Photos are JPEG files in the app's private storage.
- **Locations**: PCA does not store a precise location history. Pause-zone and Place Guide fixes are used in memory and dropped (`TripLocation`: "never stored, logged or sent"). Exceptions you create on purpose:
  - **Pins** (`place_pins`): names, notes and exact coordinates you pin. They stay on the phone and are excluded from every Android backup and device transfer. A pin may also hold a **reference Moment** (the id of one of your Moments, so you can check later what was captured there; the matcher never reads it) and, for pins made since pin-to-Moment, the time it was made (`pinnedAt`). A new pin asks for a Moment (the latest one from the last 30 minutes, or one picked by day); "Save without a Moment" is an explicit choice and the pin then shows "Needs a Moment". A pin is kept until you delete it, **or until you Forget its reference Moment (or Delete all)**: see section 5. Two things can carry pins: the explicit settings export (which never contains the Moment link or `pinnedAt`), and the install helper's safety backup to your own Mac, which archives the whole app directory (section 13).
  - **Offline map choice**: see section 10.
  - **Pause zones** (`pause_zones`): the boxes you draw, with names and buffers.
  - **Place memories**: a photo that matched a monument stores the monument (id, name), not your coordinates. A memory's own context line may carry a coarse place label if you switched the Location context source on (off by default).
- **Place pack** (`places.db`): public text about monuments fetched for 5 km cells, plus when you last looked at each (recency). Recency is a kind of location history, so Delete all clears it.
- **Voice**: recognition is on the device and audio is never stored or uploaded. Voice marks only save a mark for the last 10 seconds.
- **Suggestions**: ids, tokens, a delivery ledger and feedback counters, see section 7.
- **Logs**: see section 8.
- **Street map**: the switch, whether you accepted its explanation (`online_map`), and downloaded tiles in the cache folder: see section 10.
- **Data saver** (`data_saver`): the mode, "I'm abroad", "Small requests on roaming", the optional daily limit, the Wi-Fi-as-metered switch, today's skipped counts and today's mobile-data byte counters (numbers only): see section 15.
- **Model privacy** (`ai_backend`, `ai_backend_init`): Automatic or Offline only, and a marker that a choice was made: see section 16.

## 2. What leaves the phone, and to whom

| What | To whom | When |
|---|---|---|
| Prompts: your question, memory text, nudge and summary requests, time-word and search-word helpers | The **model endpoint you configured** per role (Ask, Summarise, Nudge, etc.) | Only if that endpoint is remote. A local on-device model sends nothing. |
| Query rewriting (`CONDENSE_QUERY`): **only your question text and today's date**, never memory content | The model endpoint configured for that role | Only in the opt-in `dated-query` pipeline or when the Developer flag `multilingualAsk` is on (both off by default). Not sent while a pause zone is approached or entered, or when the Ask zone fence refuses. If the call fails, the original question is used and the answer is not blocked. An offline fallback (a small built-in Hindi/Hinglish word list) sends nothing. |
| Photos for captions and scene summaries (`VISION_EXTRACT`, `CLUSTER_SUMMARY`, `INTENT_CLASSIFY`) | Your configured **vision endpoint** | Every captured photo that is described, if the vision role points to a remote endpoint. On roaming or an unknown network the data saver keeps them on the phone until the policy allows (section 15). Under Offline only they never go to a remote endpoint (section 16). |
| One extra photo plus a numbered list of candidate monument names (`PLACE_MATCH`) | The same vision endpoint | Place Guide on, a glance (dwell) photo, candidate monuments nearby, not in a pause zone. The list holds monuments only, never people. This is a second upload of the same photo; bystanders may be in it. |
| Photos that match a question | Your configured **Answer endpoint** | Only if "Send matching photos with questions" is on (off by default, max 2 photos, never while paused or in quiet hours, switched off again by Delete all). |
| Embedding requests | Your embedding endpoint, if you chose a remote one; otherwise on-device | Text/image embedding role binding. |
| The 5 km cell box (not your position), then public page IDs and Wikidata QIDs | `en.wikipedia.org`, `www.wikidata.org`, and the Overpass mirrors `overpass-api.de`, `overpass.kumi.systems`, `overpass.private.coffee` (tried in that order) | Place Guide seeding (including the trip presets) or a glance with no fresh cell. Wikipedia geosearch and Overpass receive the cell's bounds; the later Wikipedia and Wikidata requests receive only public page IDs and QIDs. No precise fix, pin or memory content is in any of them. Each request is checked against pause zones first. The User-Agent is a fixed `ProactiveContextAgent/1.1 (...)` string with no personal data. If Overpass answers on no mirror, the cell is kept as "seeded without OSM extras" (Wikipedia/Wikidata only); seed again later to add them. Under Offline only nothing is fetched, a seed you start included. With the data saver, a seed needs free Wi-Fi, and on a costly network a stored cell inside a seeded area (or a partial one) is used as it is instead of being fetched again. |
| Model file download | `huggingface.co` | Only when you download an on-device model. **Not blocked by Offline only** (the screen says so: the download uses the internet once and sends nothing of yours). "Allow mobile data" for a download never includes roaming. |
| Street-map tile requests (the tile numbers of the area on screen, plus your IP address) | The tile provider: **MapTiler** (`api.maptiler.com`) when the build has a key; in a debug build with no key, `tile.openstreetmap.org`. The trip builds are debug builds (`phone-debug.apk`); whether the staged ones carry a key is **UNVERIFIED** (no key was configured in `local.properties` when this was written, so expect OpenStreetMap). The first-use explanation names the provider the build really uses. | Only if you turn on "Show a street map" on the Pause zones screen (**off by default**, explained before the first use), only while that screen is open, and never under Offline only (tiles already cached are still drawn). A tile number reveals roughly where you are looking on the map. NOT sent: your zones, their names, coordinates as such, any memory, your account or any identifier. The User-Agent is the same fixed string as above, with no personal data. A release build without a map key makes no such request. See section 10. |
| Notice and status text (nudge, place card, answer, mark text, capture status) | The **Rokid Nexus hub app on the same phone**, via the optional bridge | Only when you pick Nexus as the HUD host. See section 6. |

Whatever your own endpoint (OpenAI-compatible, Azure, a box on your LAN) receives is governed by that provider's terms, not by PCA. Endpoint keys are stored encrypted on the phone, excluded from backup, and entered in the app, never in the repo.

Every model call declares which photos and memories it carries (`PayloadSources`). Text built from a photo (captions, scene and place-match text, derived facts) passes one gate (`ProvenanceGate`): if the photo or any source was forgotten, or the answer cannot be checked, it is not sent, shown, exported or kept (fail closed).

The reverse-geocode label for the Location context source uses Android's `Geocoder`; which service answers is decided by your Android system (**UNVERIFIED**: on most phones it is a remote service, so your coordinates go to it). That lookup is gated: it is never made under Model privacy "Offline only" (no place name is then shown), and not when the data saver refuses a small remote call (roaming or an unknown network, unless you allowed small requests there). Model downloads stay user-started and are outside what "Offline only" blocks, by design.

## 3. What never leaves

- The database, its key, and memory contents (except the prompts above that you chose to send).
- Raw audio. Precise fixes. Your exact position (only the 5 km cell box goes to Wikimedia/Overpass).
- Anything while PCA is paused, or inside a pause zone, for the Place Guide fetch and Ask.
- Suggestions (section 7): no network, no model call, template text only.
- Your pins, in any Android backup or transfer (section 9). The one exception is the install helper's backup to your own Mac (section 13). A pin's Moment link and `pinnedAt` are not in the settings export either.
- Under Offline only: anything to a remote model, embedding or endpoint test, any Place Guide fetch or seed, any new street-map tile (section 16, with its limits).
- Your SIM and network countries: the data saver keeps only whether they differ (section 15).

## 4. Retention and expiry

Defaults from `MemorySettings` (changeable in Settings, Memory):

- Photos: **7 days** (skipped photos 1 day). Captions, scene summaries and image vectors: **kept** until you Forget them.
- Memory tiers: short 24 h, medium 7 d, long 30 d (`RetentionWorker`; which kinds use which tier: **UNVERIFIED**).
- Soft storage quota: 250 MB.
- Canvas live trace: 7 days. Suggestion records: 14 days. Suggestion delivery ledger: 48 h. Place pack: 50 MB budget with least-recently-used eviction (design doc; pinned places are never evicted).
- Place pack cells older than 30 days are refetched.

## 5. Forget and Delete all

**Forget** (a fact, photo, Moment, answer, nudge, or a time range) removes the item and the copies derived from it: captions it produced, scene and Moment summaries, cached exports, run outputs, lineage. Answers that cited it are redacted. It also withdraws what is on the glasses and cancels linked alarms and notifications. A forgotten photo is tombstoned so a late write cannot bring it back. Retention expiry deletes quietly and does not wipe Nexus history.

**Pins and their reference Moment.** An **explicit** Forget of a Moment (single, scoped, multi-select or a time range) also **deletes every pin that uses it as its reference Moment**. You are asked first: the dialog names the pins ("Forget Moment and pin(s)"). If the pin removal cannot be prepared, nothing is forgotten. If the Forget went through but the pin removal could not be finished, the app says so; the pins are then removed at the next start or, if even that cannot be recorded, they stay and show "Needs a Moment". Anything that is **not** an explicit Forget (photo expiry, the suspect-photo sweep, the invalid-Moment purge) never deletes a pin: it only clears the link, and the pin shows "Needs a Moment" and keeps matching by location.

**Delete all** removes every memory, all photos and frame copies, the queue, exports, canvas traces, the diagnostic logs, the photo test data, suggestion settings, shadow records, delivery ledger, feedback counters and the never list, fetched place data and recency, the street map switch, its consent and its tile cache, **every pin that has a reference Moment** (the confirmation says "also deletes N pins" and names them), and withdraws the "send photos with questions" consent. It then vacuums the database. An interrupted Delete all finishes these at the next start.

**Kept by Delete all** (these are your own configuration, listed in the deletion registry):

- Endpoints, keys, prompts and other settings.
- **Pause zones** (`pause_zones`): never deleted by Forget or Delete all.
- **Pins without a reference Moment** (`place_pins`). A pin with one is deleted with its Moment, as described above.
- **Data saver** (`data_saver`) and **Model privacy** (`ai_backend`, `ai_backend_init`): privacy and cost protections, so a wipe never silently re-opens uploads or remote calls.
- Seeded Place Guide areas and the place records they seeded (Delete all clears recency and removes fetched records that are neither seeded nor pinned), the Nexus host choice (`nexus_host`), and your offline map choice and imported map copy (**UNVERIFIED** whether Delete all removes the `files/offline_maps` copy; use "Delete the copy" in the map settings).

To remove those, delete them yourself in Settings.

**Forget policy table**: the full train contains one table that states, for each source, what a Forget does to each kind of child (deleted, link cleared, decided by the existing rules, untouched). It is types and tests only and is not wired into any dialog yet, so it changes nothing you see; this section describes what the code does today.

**Forget status**: multi-select Forget is behind the Developer flag `forgetV2` (OFF by default). When a source of an answer is forgotten, the answer text is removed, the question stays and is marked "Answered from a forgotten fact"; asking again is your choice, never automatic.

## 6. Rokid Nexus bridge (optional, off by default)

Same words as the in-app text (`NexusStatus.TRUST_LINES`):

- Rokid Nexus and the bridge are separate apps. Nexus is third-party code.
- When Nexus is the HUD host, the bridge sees every card PCA sends to the glasses: nudge, place card, answer and mark texts, and the capture status. It can draw and hide them, and it tells PCA when you tap Dismiss, Mark or More. It cannot read your memories or photos and stores nothing. The bridge APK has no INTERNET, microphone, camera, location or storage permission.
- Nexus can keep the notices it showed in its hub history for up to 24 hours. PCA asks Nexus to wipe that history when you Forget, Delete all, or switch Nexus off (mode off). Retention expiry does not trigger a wipe. When you pause, or a pause zone is near, PCA hides what is on the glasses at once.
- Nothing is sent while PCA is paused, a pause zone is near, or the glasses are not worn.
- Bridge identity: PCA checks the bridge's signing certificate on every bind and package change, and trusts a bridge only if it is signed with PCA's own key, a key in the accepted rotation lineage of PCA's key, or a fingerprint explicitly pinned in the build (`SignerTrust`; the pin list is empty by default). There is no trust on first use: anything else is refused. If the history wipe has not been confirmed, PCA keeps owing it.
- Rokid-Coder and Nexus run one at a time on one pair of glasses. PCA never installs, starts or takes over either of them.

**Voice permissions**: approving the plugin in the hub can pre-select speech-to-text, text-to-speech and "replace the glasses assistant". If you do not use Nexus voice, untick those three in Nexus, Settings, Plugin access before tapping Allow on glasses. PCA's own switches (voice, speak answers, skills) are all off by default, and a hub grant alone opens no speech session.

**Residual window**: PCA stops sending within about 750 ms of a pause, zone hold or Forget and discards anything queued. But a call that is already inside a hung Nexus hub cannot be interrupted. That notice may still appear on the glasses later, once the hub unblocks, then be hidden and wiped. PCA keeps owing the wipe until the hub confirms it. No PCA code can shorten that window.

## 7. Proactive suggestions (v3.0 shadow, v3.1 delivery)

Off by default. Suggestions are **local only**: no network request, no model call, and the text is a fixed template over your own places, pins, notes and marks. They never recognise people. This is a property of Suggestions, not of the whole app (the LLM nudges keep their own settings).

- **Shadow by default**: with Suggestions on, PCA works out what it *would* show and lists it in Settings. It delivers nothing until you flip a delivery switch.
- **Three separate OFF switches**: phone feed, glasses HUD, and Nexus. Each needs the master switch and is off by default. Nothing is delivered while paused, in quiet hours, in a pause zone, while driving, or (HUD, Nexus) when the glasses are not worn.
- **Stored** (encrypted, app-private, excluded from backup): ids and tokens, the **delivery ledger** (timestamp, suggestion family and kind only, no content, kept 48 h), **feedback counters** (dismiss, snooze, thumbs), and the **never list** (keys that encode places or habits). Delete all clears all of it; Forget withdraws records built from what you forgot.
- **Review status**: delivery (v3.1) was reviewed in seven rounds; the last (`docs/superpowers/reviews/2026-11/codex-v31-r7.md`) is READY. It has **never been seen on real glasses**. The delivery switches stay off until you have supervised a first worn test (`docs/superpowers/plans/handoffs/device-checklist-2026-11-17.md`, section G). A known limit: if every write of a dismissal fails and the app restarts, the suppression for that dismissal is lost.

## 8. Logs

The diagnostic log (`files/logs/pca.log` plus `pca.log.1` ... `pca.log.59`: up to 60 files of 5 MB, about 300 MB in all, oldest dropped first) holds literal event names, counts, state names and durations (no memory text, answers, place names or coordinates). It is built so it cannot hold your content: `LogContentScanTest` fails any log call whose message is not a literal or an approved count/state/class name. It is excluded from backup and device transfer and cleared by Delete all (every segment).

**The log is larger and longer-lived than before.** So that a whole trip can be analysed afterwards, the trip build keeps a detailed log by default (Settings > Diagnostics > Detailed log, **ON by default**; Developer mode also turns detailed logging on; turning the switch off keeps only warnings and a few key events). It records what the app decided and why:

- **Place Guide**, one `place attempt` line per attempt: the outcome and reason (fixed tokens such as `data_saver` or `offline_only_no_local_model`), whether a call was made, the ids of the candidate places and of the chosen one, how old and accurate the fix was and how far the match was (as ranges, never a position), the confidence, the timings, the dedupe and rate-limit state, the card result, and the Model privacy mode, backend and endpoint kind (for example `remote:AZURE_OPENAI`; never the endpoint's name, address or model). Never a name, note, summary, coordinate, photo or frame id. A `pack lookup` line gives counts and timings; a cell key (which is a position) is never logged.
- **Runtime lines** (`PcDiag`): app start (Android version, uptime, time-zone offset), the battery in 10 % steps with charging state and a temperature range, the network (online, validated, metered, roaming, and Wi-Fi / mobile / VPN / Bluetooth / other; never a network name or address), clock jumps and time-zone changes, which of a fixed list of permissions (camera, microphone, fine/coarse/background location, notifications, Bluetooth, calendar, contacts, activity) are granted and when that changes, the state of the app's own background workers (class names only), app foreground/background, and an hourly heartbeat (uptime, memory use, power save, idle, battery-optimisation exemption).
- **Data saver and Model privacy** (`PcData`, `OfflineOnly`): the network cost class, each blocked call as kind, category and a fixed reason token, "photo uploads open", "seed refused", and the Offline-only refusals (`offline_only_refused`, `_dns`, `_route`). Never a host, endpoint, country, position or content.

It is stored only in the app's private folder, is never uploaded by the app, is excluded from backup and device transfer, and is cleared by Delete all.

**What the log can reveal.** The Wikidata and OpenStreetMap ids it records (`wd:Q…`, `osm:…`) identify real places. Over a trip they add up to a place-level trail of where you used Place Guide, with times, even though no name or coordinate is written. Your own pins appear as `user:<uuid>`; the uuid alone means nothing, but together with your pin list (or the settings export) it names the pin and so its exact position. Whether to keep the ids as they are or hash them is an open decision. The app's lines at info level and above (and every line, debug included, while the Detailed log or Developer mode is on) are also written to Android's system log (logcat), a shared buffer that a bugreport, or a computer connected with USB debugging, can read. **Export diagnostics** (Settings > Diagnostics) does not contain the `pca.log` files. It writes the last ~400 lines of the app's own logcat output plus the 50 most recent pipeline traces (time, job state, id and note) to a file you can share; any line that looks like it holds a key is dropped from both. On a build that is not debuggable the `pca.log` files cannot be extracted from the phone at all; reading them (with `tools/place-attempt-report.sh` or `tools/link-health-report.sh`) needs a debuggable build, and the trip builds are debuggable.

## 9. Backups

The phone allows Android backup, but excludes (cloud backup, device transfer, and the older rules): the database and its key, endpoint keys, photos and every copy of memory text, the place pack, pins (with their Moment links and any pending pin-removal record), the offline map choice and imported map copy, the street map switch and consent (`online_map`; the tile cache is in the cache folder, which is never backed up), the data saver settings, daily-limit numbers and counts (`data_saver`, all in the one excluded file), the Model privacy choice (`ai_backend`, `ai_backend_init`; Automatic again after a restore), the Suggestions switches and store, logs, models, set-up and device state, the Developer mode flag, every per-device privacy switch (send photos, Place Guide, Nexus host, Suggestions, helper grants), and pause-zone runtime state. What is restored is plain preferences (hub, retrieval, prompt, persona and voice settings) and your **pause zone definitions**. The watch and the glasses app turn backup off entirely. `BackupRulesTest` pins this list, so a new file cannot slip into a backup unnoticed.

Consequence: an uninstall deletes the database (the key goes with it), and a restore onto a new phone brings back no memories. The explicit settings export (which you choose to make and share) is the only way to carry pins to a new phone; it leaves out each pin's Moment link and `pinnedAt` (a Moment id means nothing on another phone), so imported pins show "Needs a Moment".

## 10. Pause zones and the law

A pause zone stops capture while you are inside it or near it (a buffer). It uses foreground location only: no background-location permission and no geofencing API. Fixes are never stored. If the zone list is unreadable, or the fix is stale or location is off, PCA fails closed and treats you as inside. Location off applies immediately only for a zone in the approach or inside state; for a zone you are outside of, PCA keeps treating you as outside until the last fix is older than 15 minutes (a fix older than 3 minutes counts as stale for the other states). A mark photo does not bypass a zone.

**Ask zone fence** (`AskZoneFence`, Developer-visible switch `askZoneFence`, ON by default): inside or near a zone, an Ask model call and delivery of its answer to the glasses are refused, because stored text can reveal private context. Browsing your own data on the phone is not blocked. Until the zones have been judged after a restart, it stays closed if any zone may apply.

**Offline map** (optional, `docs/offline-maps.md`): the zones map can draw tiles from a `.pmtiles` file you choose. The file is read in place through a persisted read-only grant: no storage permission. You may also import a copy into `files/offline_maps` (app-private, excluded from backup; "Delete the copy" removes it). The offline code never touches the network (`MapNoNetworkTest`). The file is treated as hostile: sizes, directory depth and entry counts are capped and anything bad falls back to the plain grid. An offline file you select is used instead of online tiles.

**Street map** (optional, **off by default**, `docs/offline-maps.md`): the switch "Show a street map (downloads map tiles from MapTiler)" draws online tiles under the zone boxes so you can place a zone by eye. Turning it on the first time shows what is sent, with Cancel as the default; Cancel leaves it off. When on: the app asks the tile provider for tiles of the area you are looking at, over HTTPS to one fixed host (a redirect to another host is refused), only while the Pause zones screen is in the foreground (requests are cancelled when it is paused or closed), at most 512 KiB per tile, at most 4 at a time and 400 per visit, backing off on HTTP 429 and 5xx. What the provider sees: tile numbers (which reveal roughly where you are looking), your IP address and the app's fixed User-Agent. What it does not receive: your zones or their names, your coordinates as such, memories, photos or any identifier. Tiles are cached in the app cache folder (at most 50 MB, least recently used first; never backed up); turning the switch off, or Delete all, deletes the cache, and Delete all also resets the switch to off and forgets that you accepted the explanation. The switch is stored in `online_map.xml`, excluded from backup and device transfer. The map shows the provider's attribution (and its name as the logo text) whenever its tiles are drawn. Logs name events and counts only, never a tile number, a coordinate, a URL or the key. The key is a public client key embedded in the app (read at build time from `local.properties` or the `PCA_MAP_TILES_KEY` variable into `BuildConfig.MAP_TILES_KEY`), not a secret: anyone with the APK can extract it, so it should be restricted in the MapTiler dashboard. Without a key a release build shows the grid only and a debug build uses `tile.openstreetmap.org` (see section 2). Under Offline only (section 16) the map requests no tile at all, checked before every request and every redirect hop; tiles already in the cache are still drawn.

**Centre on me**: a button on the same screen asks for ONE foreground location fix with the existing location permission (no new permission), only when you tap it, then moves the map to a view about 500 m across and shows your position. The fix is held on screen only: it is not stored, logged or sent anywhere (the tiles requested afterwards are for the area now shown, as with any panning).

**Photographing people**: some people and places are legally protected from being photographed or filmed. You are responsible for knowing and following the law where you are. A zone is a safety aid, not legal advice or a guarantee: GPS can be wrong. The **Jarawa preset** (Andaman Trunk Road) uses an **approximate, UNVERIFIED** box. Check it on the map yourself, and also pause or take the glasses off in sensitive areas.

## 11. Place Guide and people

Place Guide names monuments and buildings only. Candidates come from three places: Wikidata classes in a monument allowlist, a curated list of landmark QIDs (for example some lakes and viewpoints that no allowlisted class covers), and OpenStreetMap places tagged historic, heritage or tourism (attraction, museum, viewpoint). Wikidata items whose class is a person (human and fictional human classes) are rejected explicitly, even if the QID is curated, and an item whose class cannot be read is also dropped. So people are never candidates; the prompt says "never identify or describe people"; and the card text is a template over the stored Wikipedia extract, not model-written. A photo-derived place-match is second-checked by the provenance gate and the zone fence. PCA does not recognise faces or voices.

**When no photo is sent.** Offline (no network), under Offline only (there is no on-device vision model yet), or when the data saver does not allow photos on the current network, Place Guide makes no match call: it shows the "nearby, not checked" card (name and distance of the nearest place in the stored pack, only if it is within the nearby range and no second place is as close) or nothing. No photo or candidate name leaves the phone in that case. (With the data saver, a missing cell may still be fetched as a small text request, the 5 km cell box as in section 2, when "Small requests on roaming" allows it; under Offline only nothing is fetched.) In Automatic mode the match keeps today's confidence floor (0.50); a stricter floor for a future on-device model (0.70) is not used yet.

### Card placement and trip presets

The card position setting (left, centre, right; Developer mode) is a display preference stored on the phone. It is sent to the glasses with the card and contains no personal data. The trip presets (Gangtok, Darjeeling, West Sikkim, Sri Lanka sites) are approximate seed boxes, **UNVERIFIED** on the ground; check them before relying on them.

## 12. Developer mode

Off by default (Settings, Diagnostics); the flag is excluded from backup. When on, it shows extra screens and lets you use the experiment canvas, a local web UI served by the phone (token-paired sessions), the developer sensor recording, Moment and Scene Forget scopes, and longer canvas sessions (optional). A Developer-only page on that canvas (`/api/proactive/shadow-eval`) prints a counts-only report of the suggestion shadow week (counts and fixed labels, no suggestion text, place names or ids); it answers 403 when Developer mode is off. Developer mode adds no new upload destination that I found. Raw sensor traces it creates are never exported or backed up, and Delete all removes them.

## 13. The install helper

`tools/install-trip-build.sh` (dry run by default) writes a validated backup of the app's data (`~/Desktop/rokid/backups/pca-phone-pre-<tag>.tar`, mode 0600) **on your Mac only**. It never uninstalls the app and never clears its data; rollback restores from the backup. The tar contains your encrypted database and its key, so treat it like the phone.

## 14. How to verify

Run these tests (`cd android-companion && ./gradlew test`), or read their names in the repo:

- `BackupRulesTest`: what is and is not backed up (phone), with `GlassesBackupRulesTest`.
- `LogContentScanTest`: logs contain no content.
- `ProvenanceChokePointTest`: derived-from-forgotten text cannot be written, shown, exported or sent outside one gate.
- `NexusChokePointTest`: the one sink is the only path from PCA to Nexus.
- `MapNoNetworkTest`: the offline map code names no network class; `OnlineTileSourceTest`: the street map's host allowlist, caps, redirects and the Offline-only check.
- `PinMomentLookupTest`, `ForgetPolicyConformanceTest`, `PinForgetConfirmTest`: pins and their reference Moment under Forget and Delete all.
- `ModelRouterDataSaverTest`, `DataSaverGraphTest`, `DataSaverTest`: the data saver decisions.
- `ModelRouterOfflineOnlyTest`, `OfflineOnlyDnsTest`, `OfflineOnlyNetTest`, `ModelPrivacyStoreTest`, `ModelPrivacyDurabilityTest`: Offline only refuses remote calls and fails closed.

## 15. Roaming data saver (full train only)

Settings > Data saver. It decides, per call, whether photos and other big work may use the current network. It protects your mobile data; it is also a privacy aid, because what is not sent is not seen.

- **How the network is judged** (fail closed: anything unclear is costly): Wi-Fi or Ethernet that is validated and unmetered is free; a metered Wi-Fi (for example a phone hotspot) counts as home mobile data; Wi-Fi that has not passed Android's internet check (a captive portal, or a network still being checked) counts as unknown, which is treated like roaming (a reconnect blip under 20 s is ignored; after 10 minutes the status line says so); mobile data is roaming when the phone says so, when the SIM country differs from the network country, or when you switch on **"I'm abroad"** (which also makes a metered Wi-Fi count as roaming); under a VPN the real Wi-Fi underneath is judged; no network (airplane mode) means nothing can leave. When Android's own Data Saver is on and PCA is not exempt, photos wait for Wi-Fi whatever the mode.
- **Modes** ("Photos and big uploads"): Wi-Fi only; Wi-Fi and home mobile data (**default**); Anywhere (roaming too, but see the daily cap below). Without any change by you, a default user (Wi-Fi and home mobile data, not abroad) has **no daily limit** on home Wi-Fi or home mobile data. Calls to a model on the phone always run; a model on your own network runs on Wi-Fi.
- **Daily cap on mobile data** (slice 2): a limit of 10 MB and 30 photo uploads a day applies only while "I'm abroad" is on or the network is roaming or unknown **and** the mode is Anywhere; or when you switch on **"Daily limit on mobile data"** (OFF by default) and pick your own numbers, which then apply on any mobile data (if both apply, the lower numbers win). It never applies on Wi-Fi. When it is reached, photo uploads and other big uploads are **parked** exactly like a data-saver hold (never thinned or deleted; the newest 300 stay protected), text requests still go (unless you turned small requests on roaming off), and the status line says "Daily limit reached". It resets at local midnight and wakes the parked uploads; setting the clock back gives no reset. A call that starts under the limit can end a little over it.
- **"Send over mobile data once"** (slice 2): when you tap Describe on a photo (Find a photo) and the data saver would hold the upload (mobile data, roaming, an unknown network or today's limit), a dialog asks "Send over mobile data once / Wait for Wi-Fi". Waiting is the default (dismissing the dialog is waiting). "Once" sends that one request, counts it, and is remembered nowhere. It covers only your own describe taps (a user-class photo call); Offline only still refuses. Questions with photos from the glasses are not covered: they go on without photos while the saver holds them.
- **"Treat Wi-Fi as metered while I'm abroad"** (slice 2, OFF by default): a phone hotspot or pocket Wi-Fi can look like free Wi-Fi. With this switch on and "I'm abroad" on, every Wi-Fi counts as mobile data, so photos wait until you turn it off on a trusted network. It is one global switch: nothing about any Wi-Fi network (name, address or a hash of them) is stored.
- **"Small requests on roaming"** (ON by default): questions, place facts and suggestions as text, never photos.
- **What waits and what is skipped**: a blocked photo description is **parked** on the phone and sent when the policy allows (Wi-Fi back, mode or switch changed); the newest 300 waiting photos are never thinned, older ones in a longer backlog are thinned like any backlog (whether a long roaming stretch can let undescribed photos reach the normal photo expiry is **UNVERIFIED**). Optional photo calls are dropped, not queued: the Place Guide match (the nearby card is shown instead), the image intent call and photos with questions. The night describe job, the suspect-photo sweep and a Place Guide seed (seeding needs free Wi-Fi) wait for a better network; on a costly network a stored place cell inside a seeded area is used as it is. A model download with "Allow mobile data" never uses roaming.
- **What is stored**: the mode, "I'm abroad", "Small requests on roaming", the daily-limit switch and the two numbers you chose, the Wi-Fi-as-metered switch, today's counts of blocked photo uploads, skipped optional photo calls and other big jobs, and (slice 2) **today's bytes sent over mobile, roaming or unknown networks (photo calls and text calls; a big job without photos is counted as text), and the number of photos sent** (numbers only, keyed by the local day, payload sizes estimated from the request and the reply, never any content; Wi-Fi is not counted; setting the clock back gives no reset). **Text bytes count toward the daily byte limit as well as photo bytes** (the limit is on total bytes; the photo count is a second limit on photos only). A call is counted on the network it was sent on (read when the call gets its turn to go out, not when it was queued). Everything but a model on the phone is counted while that network is mobile, roaming or unknown, **including a model on your own network** (a home-network host reached over mobile data, for example through a VPN, does use mobile data; on Wi-Fi it is free and not counted). A failed call counts its request bytes only when the server demonstrably received the request and answered: an HTTP error status, or a normal reply that could not be used (empty or malformed). A call that fails before the server received the request (connect, name lookup, TLS or other network error) counts nothing, and so does a read timeout after the request was sent (the request may have been received, so the byte limit can be slightly permissive). No failed call uses up the photo limit. When the time zone or the clock changes, the next day starts at the new local midnight (flying east can start the day early; flying west keeps the same day until the local date passes it). "Send over mobile data once" covers every photo call of that one describe action and nothing after it. Model downloads, place and map requests are not counted yet. Of the countries, only the boolean "SIM and network country differ" is used, and it is not stored or logged. `data_saver.xml` is excluded from backup and transfer and kept by Delete all.
- **Settings > Data saver status line** says the current network class and why ("the phone reports roaming", "the SIM country differs from the network country", "I'm abroad", "Android Data Saver is on for this app", "Wi-Fi without an internet check for over 10 minutes"), what that means for photos (including "Daily limit reached"), how many photos are waiting, "Sent today on mobile data: X MB / N photos", the limit that applies right now, and today's blocked counts.

## 16. Model privacy: Automatic or Offline only (full train only)

Settings > Capture & privacy > Model privacy. **Automatic** (default) is the behaviour before this setting existed. **Offline only** means no request to a remote model.

What Offline only does, in code:

- Every model call (all roles, through the router), every embedding call and every endpoint test is refused **before the key is read** if the endpoint is remote. Allowed: models on this phone and on your own network (a loopback, private, link-local or `.local` address; an IPv6 literal other than loopback counts as remote).
- **Name check**: every address a model client resolves must be on this phone or your own network; one public address among the answers refuses the request.
- **No proxy**: a system HTTP or SOCKS proxy is ignored for model clients (it would otherwise receive the request), and the connection actually used, pooled ones included, must be direct to an own-network address. Idle pooled connections of the model clients are closed when the mode is set.
- No Place Guide fetch and no seed (a seed you start is refused), no new street-map tile. Place Guide has no on-device vision model yet, so matching is paused and the nearby card is shown instead.
- Photos waiting for a description stay parked (not counted as "blocked today") and are sent after you switch back to Automatic (or add an on-device model). A failing on-device or home-network fallback is retried only a few times per item, then counts as an ordinary failure.
- **Fail closed**: an unreadable or lost setting reads as Offline only and the screen says "Model privacy settings were reset: check them". The screen shows "Loading" until the stored choice is read and "Saving" while it is written; "Couldn't save this choice" if the write failed. `ai_backend.xml` and `ai_backend_init.xml` are excluded from backup and transfer (Automatic again after a restore) and kept by Delete all.

Honest limits:

- **Model file downloads are not blocked** (`huggingface.co`); the screen says so. Nothing of yours is sent in them.
- **"Your own network" is an address range, not proof of ownership.** On a hotel or public Wi-Fi, a private address is someone else's network. Offline only would still let a model endpoint you configured with a private address be called there.
- Not covered: Android's `Geocoder` for the optional Location context source, the Nexus bridge (on the phone, no network), other apps and Android itself. Nothing at the system level (DNS of other apps, captive-portal checks) is changed.
- A request already on the wire when you switch is not cut off (the model client's call timeout is 90 s); the switch applies at once to every request that starts after the tap.
- **Small window**: the tap takes effect in memory at once, but if the app is killed in the few milliseconds before the first disk write, the previous stored choice is what comes back after the restart. A quick double tap can, rarely, leave the older choice on disk (a known follow-up).
- Checked by tests only: nobody has yet watched the phone's traffic (proxy or packet capture) under Offline only.

## Where this is enforced

All paths are under `android-companion/` unless stated.

| Claim | Source |
|---|---|
| Encrypted DB, backup exclusions | `phone/src/main/res/xml/backup_rules.xml`, `data_extraction_rules.xml`; `phone/src/test/java/com/rokid/context/BackupRulesTest.kt`; `glasses/.../GlassesBackupRulesTest.kt`; README.md (repo root) |
| Watch and glasses backup off | `glasses/src/main/AndroidManifest.xml`, `watch/src/main/AndroidManifest.xml` (`allowBackup="false"`) |
| What each role/prompt sends, image use | `services/ai/.../contract/PromptKey.kt`; `services/ai/.../prompt/PromptSeeds.kt` (PLACE_MATCH wording); `services/ai/.../Role.kt` |
| Forgotten data never sent | `docs/superpowers/specs/2026-11-16-privacy-and-release-design.md` section A; `services/orchestration/.../provenance/`; `ProvenanceChokePointTest` |
| Ask photos opt-in | `services/orchestration/.../orchestration/AskPhotoPolicy.kt` |
| Wikipedia / Wikidata / Overpass, cell only | `services/connectors/.../place/PlaceCellSource.kt`, `PlaceSources.kt`; `docs/superpowers/specs/2026-11-16-place-guide-reuse-and-roadmap.md` (B3, risks) |
| Fixes never stored | `phone/.../zone/TripLocation.kt`; `phone/.../zone/ZoneController.kt`; roadmap spec B2 |
| Pause zone fail closed, Jarawa preset, legal note | roadmap spec B2 and risks; `phone/.../zone/ZoneStore.kt` |
| Place Guide people rule | `PromptSeeds.kt`; `PlaceCellSource.kt` (`MONUMENT_CLASSES`) |
| Retention defaults | `services/memory/.../MemorySettingsStore.kt`; `services/orchestration/.../forget/RetentionWorker.kt`; `.../CanvasMaintenance.kt`; `.../proactive/SuggestionStore.kt` |
| Delete all, kept items | `services/orchestration/.../orchestration/MemoryWipe.kt`; `services/memory/.../forget/DeletionGraph.kt` (`pause_zones`, `place_pins` (except via its reference Moment), `data_saver`, `ai_backend`, `ai_backend_init` KEPT; `online_map` WIPED); `.../forget/lifecycle/Families.kt` |
| Forget design | `docs/superpowers/specs/2026-11-16-forget-lifecycle-design.md`; `services/memory/.../forget/` |
| Nexus trust text, residual window, 24 h | `phone/.../nexus/NexusStatus.kt` (`TRUST_LINES`); `nexus-bridge/README.md`; `NexusChokePointTest` |
| Nexus wipe on Forget/Delete all, not on retention | `phone/.../nexus/NexusGraph.kt` |
| Suggestions shadow, local only | `docs/superpowers/specs/2026-11-18-v3-proactive-loop-design.md` section 3; `MemoryWipe.kt` (`PROACTIVE_*`) |
| Logs literal-only, wiped | `phone/.../log/PhoneLogSink.kt`, `PhoneLogging.kt`; `phone/src/test/.../log/LogContentScanTest.kt` |
| Developer mode | `phone/.../canvas/DeveloperModeStore.kt`; `phone/.../ui/settings/Diagnostics.kt` |
| Offline map, hostile-file limits | `docs/offline-maps.md`; `phone/.../ui/settings/privacy/zones/tiles/PmTiles.kt`, `OfflineMap.kt`; `MapNoNetworkTest`; `backup_rules.xml` (`offline_map.xml`, `offline_maps`) |
| Place Guide mirrors, User-Agent, partial seed | `services/connectors/.../place/PlaceSources.kt` (`DEFAULT_ENDPOINTS`), `PlaceHttp.kt` (`DEFAULT_USER_AGENT`); `phone/.../ui/settings/place/PlaceGuideSettings.kt` (presets, "seeded without OSM extras") |
| Pins excluded from backup | `phone/src/main/res/xml/backup_rules.xml` (`place_pins.xml`; also `online_map.xml`, `data_saver.xml`, `ai_backend.xml`, `ai_backend_init.xml`), `data_extraction_rules.xml`; `services/memory/.../place/UserPlace.kt` |
| Suggestions switches, ledger, never list | `phone/.../ui/settings/suggestions/SuggestionsSettings.kt`; `services/orchestration/.../proactive/DeliveryLedger.kt`, `SuggestionStore.kt`, `ProactiveForget.kt`; `docs/superpowers/decisions/2026-11-18-codex-proxy-rulings-3.md`; `docs/superpowers/reviews/2026-11/codex-v31-r3.md` (open P1s) |
| Ask zone fence, provenance gate | `services/orchestration/.../orchestration/AskZoneFence.kt`; `services/orchestration/.../provenance/ProvenanceGate.kt` |
| Forget status in Ask | `phone/.../ui/home/HomeScreen.kt` (`ANSWER_FROM_FORGOTTEN`); `phone/.../ui/list/ListRoute.kt` (`forgetV2`) |
| Nexus signer check, wipe debt | `nexus-port/src/main/kotlin/io/github/pca/nexusport/SignerTrust.kt`; `phone/.../nexus/NexusHostStore.kt` |
| Install helper backup | `tools/install-trip-build.sh`; `tools/INSTALL-TRIP-BUILD.md` |
| Trip log size, Detailed log, logcat routing | `phone/.../log/PhoneLogSink.kt` (`SEGMENT_BYTES`, 60 files); `core/model/.../log/LogPolicy.kt` (logcat = level >= I or diag); `phone/.../canvas/DeveloperModeStore.kt` (`detailed_log` default true; Developer mode also turns it on); `phone/.../ui/settings/Diagnostics.kt` |
| Place attempt line, runtime lines | `services/orchestration/.../work/frames/place/PlaceSampler.kt` (`Trace`, `finish`); `phone/.../log/DiagProbe.kt` |
| Export diagnostics content | `phone/.../ui/settings/Logs.kt` (`LogReader.ownLines` 400 lines, `redact` drops secret-looking lines, `DiagnosticsExport`); `phone/.../ui/settings/SettingsDeps.kt` (50 traces) |
| Street map provider, key, caps, Offline-only check | `phone/.../ui/settings/privacy/zones/online/OnlineTileSource.kt` (`OnlineProvider.select`, `MAX_TILE_BYTES`, `SESSION_CAP`, `MAX_CONCURRENT`, `offlineOnly`), `OnlineMapGraph.kt`; `phone/build.gradle.kts` (`PCA_MAP_TILES_KEY`); `MemoryWipe.kt` (`ONLINE_MAP_PREFS`, `ONLINE_TILES_DIR`) |
| Pin reference Moment, cascade, export without link | `services/memory/.../place/UserPlace.kt` (`momentId`, `pinnedAt`, `withoutLinks`, `ARMED_KEY`); `services/orchestration/.../work/frames/place/PinMomentLinks.kt`; `ConfigExport.kt`; `DeletionGraph.kt` (`place_pins` text); `phone/.../ui/settings/place/PlaceGuideSettings.kt`; `phone/.../ui/memory/MemoryForget.kt`, `phone/.../ui/settings/memory/MemorySettings.kt` (dialogs) |
| Data saver | `core/model/.../net/DataSaver.kt` (`NetworkCosts.classify`, `DataPolicy.decide`, `SkippedLedger`); `services/ai/.../datasaver/NetworkCostMonitor.kt` (country boolean, VPN), `DataSaverGraph.kt`, `DataSaverStore.kt`; `services/memory/.../db/FrameDaos.kt` (`PROTECTED_PARKED` 300); `.../work/frames/KeyframePipeline.kt`, `KeyframeGraph.kt`; `.../work/DataSaverWorkGate.kt`; `phone/.../place/AndroidPlaceGuideControl.kt` (seed gates); `services/memory/.../place/PlacePack.kt` (`ensureCell` costly); `phone/.../ui/settings/datasaver/DataSaverSettings.kt` |
| Model privacy | `core/model/.../net/ModelBackend.kt`; `services/ai/.../resolve/ModelPrivacyStore.kt`, `OfflineOnlyGuard.kt`, `OfflineOnlyDns.kt`, `OfflineOnlyNet.kt`; `services/ai/.../Http.kt` (`modelClient` guarded, `downloadClient` not); `ModelRouter.kt`, `EmbeddingClient.kt`, `EndpointTester.kt`; `phone/.../ui/settings/modelprivacy/ModelPrivacySettings.kt`; `docs/superpowers/plans/handoffs/overnight-queue-2026-11-20.md` section I (window, double tap) |
| Forget policy table (not wired) | `services/memory/.../forget/lifecycle/ForgetPolicy.kt`, `PolicyPlan.kt`, `ForgetSource.kt` |

## Changelog

- 2026-11-20 (slice 2): data saver section 15: byte counters at the model router, daily cap (abroad/roaming under Anywhere, or your own limit, default off), "Send over mobile data once" for your own describe taps, the Wi-Fi-as-metered-abroad switch. All new values live in `data_saver.xml` (excluded from backup and transfer, kept by Delete all).
- 2026-11-20: audited against the code from `18c1b76a` to `166d3fd4`. Added: which build has what; pin reference Moment (Forget and Delete all delete linked pins, expiry only clears the link, export leaves out the link and `pinnedAt`); street map provider in debug trip builds and the Offline-only tile check; full trip-log contents (place attempt fields, `user:` ids, runtime lines, data saver and Offline-only tokens, Developer mode and logcat routing, Export diagnostics drops whole lines); backup exclusions for `online_map`, `data_saver`, `ai_backend`; sections 15 (data saver) and 16 (Model privacy, with limits); Forget policy table status; new tests.
