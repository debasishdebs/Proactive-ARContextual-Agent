# Privacy

Proactive Context Agent (PCA) keeps your memory on your phone. This page says what is stored, what can leave the phone and to whom, what is never sent, how long things last, and what Forget and Delete all remove. Every claim points to code or a design doc in the table at the end. Anything I could not confirm is marked **UNVERIFIED**.

PCA has no server of its own and no account. There is no analytics or crash upload that I found in the code (**UNVERIFIED** for third-party libraries; check the merged manifest if this matters to you).

## 1. What PCA stores on your phone

- **Memories, photos, Moments, marks, answers, nudges**: in an encrypted Room database (SQLCipher). The key lives in the Android Keystore and never leaves the phone. Photos are JPEG files in the app's private storage.
- **Locations**: PCA does not store a precise location history. Pause-zone and Place Guide fixes are used in memory and dropped (`TripLocation`: "never stored, logged or sent"). Exceptions you create on purpose:
  - **Pins** (`place_pins`): names, notes and exact coordinates you pin. They stay on the phone, are excluded from every Android backup and device transfer, and are kept until you delete them. Two things can carry them: the explicit settings export (which you choose to make and share), and the install helper's safety backup to your own Mac, which archives the whole app directory (section 13).
  - **Offline map choice**: see section 10.
  - **Pause zones** (`pause_zones`): the boxes you draw, with names and buffers.
  - **Place memories**: a photo that matched a monument stores the monument (id, name), not your coordinates. A memory's own context line may carry a coarse place label if you switched the Location context source on (off by default).
- **Place pack** (`places.db`): public text about monuments fetched for 5 km cells, plus when you last looked at each (recency). Recency is a kind of location history, so Delete all clears it.
- **Voice**: recognition is on the device and audio is never stored or uploaded. Voice marks only save a mark for the last 10 seconds.
- **Suggestions**: ids, tokens, a delivery ledger and feedback counters, see section 7.
- **Logs**: see section 8.

## 2. What leaves the phone, and to whom

| What | To whom | When |
|---|---|---|
| Prompts: your question, memory text, nudge and summary requests, time-word and search-word helpers | The **model endpoint you configured** per role (Ask, Summarise, Nudge, etc.) | Only if that endpoint is remote. A local on-device model sends nothing. |
| Query rewriting (`CONDENSE_QUERY`): **only your question text and today's date**, never memory content | The model endpoint configured for that role | Only in the opt-in `dated-query` pipeline or when the Developer flag `multilingualAsk` is on (both off by default). Not sent while a pause zone is approached or entered, or when the Ask zone fence refuses. If the call fails, the original question is used and the answer is not blocked. An offline fallback (a small built-in Hindi/Hinglish word list) sends nothing. |
| Photos for captions and scene summaries (`VISION_EXTRACT`, `CLUSTER_SUMMARY`, `INTENT_CLASSIFY`) | Your configured **vision endpoint** | Every captured photo that is described, if the vision role points to a remote endpoint. |
| One extra photo plus a numbered list of candidate monument names (`PLACE_MATCH`) | The same vision endpoint | Place Guide on, a glance (dwell) photo, candidate monuments nearby, not in a pause zone. The list holds monuments only, never people. This is a second upload of the same photo; bystanders may be in it. |
| Photos that match a question | Your configured **Answer endpoint** | Only if "Send matching photos with questions" is on (off by default, max 2 photos, never while paused or in quiet hours, switched off again by Delete all). |
| Embedding requests | Your embedding endpoint, if you chose a remote one; otherwise on-device | Text/image embedding role binding. |
| The 5 km cell box (not your position), then public page IDs and Wikidata QIDs | `en.wikipedia.org`, `www.wikidata.org`, and the Overpass mirrors `overpass-api.de`, `overpass.kumi.systems`, `overpass.private.coffee` (tried in that order) | Place Guide seeding (including the trip presets) or a glance with no fresh cell. Wikipedia geosearch and Overpass receive the cell's bounds; the later Wikipedia and Wikidata requests receive only public page IDs and QIDs. No precise fix, pin or memory content is in any of them. Each request is checked against pause zones first. The User-Agent is a fixed `ProactiveContextAgent/1.1 (...)` string with no personal data. If Overpass answers on no mirror, the cell is kept as "seeded without OSM extras" (Wikipedia/Wikidata only); seed again later to add them. |
| Model file download | `huggingface.co` | Only when you download an on-device model. |
| Street-map tile requests (the tile numbers of the area on screen, plus your IP address) | The tile provider: **MapTiler** (`api.maptiler.com`); in a developer debug build with no key only, `tile.openstreetmap.org` | Only if you turn on "Show a street map" on the Pause zones screen (**off by default**, explained before the first use), only while that screen is open. A tile number reveals roughly where you are looking on the map. NOT sent: your zones, their names, coordinates as such, any memory, your account or any identifier. The User-Agent is the same fixed string as above, with no personal data. A release build without a map key makes no such request. See section 10. |
| Notice and status text (nudge, place card, answer, mark text, capture status) | The **Rokid Nexus hub app on the same phone**, via the optional bridge | Only when you pick Nexus as the HUD host. See section 6. |

Whatever your own endpoint (OpenAI-compatible, Azure, a box on your LAN) receives is governed by that provider's terms, not by PCA. Endpoint keys are stored encrypted on the phone, excluded from backup, and entered in the app, never in the repo.

Every model call declares which photos and memories it carries (`PayloadSources`). Text built from a photo (captions, scene and place-match text, derived facts) passes one gate (`ProvenanceGate`): if the photo or any source was forgotten, or the answer cannot be checked, it is not sent, shown, exported or kept (fail closed).

The reverse-geocode label for the Location context source uses Android's `Geocoder`; which service answers is decided by your Android system (**UNVERIFIED**).

## 3. What never leaves

- The database, its key, and memory contents (except the prompts above that you chose to send).
- Raw audio. Precise fixes. Your exact position (only the 5 km cell box goes to Wikimedia/Overpass).
- Anything while PCA is paused, or inside a pause zone, for the Place Guide fetch and Ask.
- Suggestions (section 7): no network, no model call, template text only.
- Your pins, in any Android backup or transfer (section 9). The one exception is the install helper's backup to your own Mac (section 13).

## 4. Retention and expiry

Defaults from `MemorySettings` (changeable in Settings, Memory):

- Photos: **7 days** (skipped photos 1 day). Captions, scene summaries and image vectors: **kept** until you Forget them.
- Memory tiers: short 24 h, medium 7 d, long 30 d (`RetentionWorker`; which kinds use which tier: **UNVERIFIED**).
- Soft storage quota: 250 MB.
- Canvas live trace: 7 days. Suggestion records: 14 days. Suggestion delivery ledger: 48 h. Place pack: 50 MB budget with least-recently-used eviction (design doc; pinned places are never evicted).
- Place pack cells older than 30 days are refetched.

## 5. Forget and Delete all

**Forget** (a fact, photo, Moment, answer, nudge, or a time range) removes the item and the copies derived from it: captions it produced, scene and Moment summaries, cached exports, run outputs, lineage. Answers that cited it are redacted. It also withdraws what is on the glasses and cancels linked alarms and notifications. A forgotten photo is tombstoned so a late write cannot bring it back. Retention expiry deletes quietly and does not wipe Nexus history.

**Delete all** removes every memory, all photos and frame copies, the queue, exports, canvas traces, the diagnostic logs, the photo test data, suggestion settings, shadow records, delivery ledger, feedback counters and the never list, fetched place data and recency, and withdraws the "send photos with questions" consent. It then vacuums the database.

**Kept by Delete all** (these are your own configuration, listed in the deletion registry):

- Endpoints, keys, prompts and other settings.
- **Pause zones** (`pause_zones`): never deleted by Forget or Delete all.
- **Pins** (`place_pins`): never deleted by Forget or Delete all.
- Seeded Place Guide areas and the place records they seeded (Delete all clears recency and removes fetched records that are neither seeded nor pinned), the Nexus host choice (`nexus_host`), and your offline map choice and imported map copy (**UNVERIFIED** whether Delete all removes the `files/offline_maps` copy; use "Delete the copy" in the map settings).

To remove those, delete them yourself in Settings.

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

The diagnostic log (`files/logs`, two files, 6 MB cap) holds literal event names, counts, state names and durations (no memory text, answers, place names or coordinates). It is built so it cannot hold your content: `LogContentScanTest` fails any log call whose message is not a literal or an approved count/state/class name. It is excluded from backup and cleared by Delete all.

## 9. Backups

The phone allows Android backup, but excludes (cloud backup, device transfer, and the older rules): the database and its key, endpoint keys, photos and every copy of memory text, the place pack, pins, the offline map choice and imported map copy, the Suggestions switches and store, logs, models, set-up and device state, the Developer mode flag, every per-device privacy switch (send photos, Place Guide, Nexus host, Suggestions, helper grants), and pause-zone runtime state. What is restored is plain preferences (hub, retrieval, prompt, persona and voice settings) and your **pause zone definitions**. The watch and the glasses app turn backup off entirely. `BackupRulesTest` pins this list, so a new file cannot slip into a backup unnoticed.

Consequence: an uninstall deletes the database (the key goes with it), and a restore onto a new phone brings back no memories. The explicit settings export (which you choose to make and share) is the only way to carry pins to a new phone.

## 10. Pause zones and the law

A pause zone stops capture while you are inside it or near it (a buffer). It uses foreground location only: no background-location permission and no geofencing API. Fixes are never stored. If the zone list is unreadable, or the fix is stale or location is off, PCA fails closed and treats you as inside. Location off applies immediately only for a zone in the approach or inside state; for a zone you are outside of, PCA keeps treating you as outside until the last fix is older than 15 minutes (a fix older than 3 minutes counts as stale for the other states). A mark photo does not bypass a zone.

**Ask zone fence** (`AskZoneFence`, Developer-visible switch `askZoneFence`, ON by default): inside or near a zone, an Ask model call and delivery of its answer to the glasses are refused, because stored text can reveal private context. Browsing your own data on the phone is not blocked. Until the zones have been judged after a restart, it stays closed if any zone may apply.

**Offline map** (optional, `docs/offline-maps.md`): the zones map can draw tiles from a `.pmtiles` file you choose. The file is read in place through a persisted read-only grant: no storage permission. You may also import a copy into `files/offline_maps` (app-private, excluded from backup; "Delete the copy" removes it). The offline code never touches the network (`MapNoNetworkTest`). The file is treated as hostile: sizes, directory depth and entry counts are capped and anything bad falls back to the plain grid. An offline file you select is used instead of online tiles.

**Street map** (optional, **off by default**, `docs/offline-maps.md`): the switch "Show a street map (downloads map tiles from MapTiler)" draws online tiles under the zone boxes so you can place a zone by eye. Turning it on the first time shows what is sent, with Cancel as the default; Cancel leaves it off. When on: the app asks the tile provider for tiles of the area you are looking at, over HTTPS to one fixed host (a redirect to another host is refused), only while the Pause zones screen is in the foreground (requests are cancelled when it is paused or closed), at most 512 KiB per tile, at most 4 at a time and 400 per visit, backing off on HTTP 429 and 5xx. What the provider sees: tile numbers (which reveal roughly where you are looking), your IP address and the app's fixed User-Agent. What it does not receive: your zones or their names, your coordinates as such, memories, photos or any identifier. Tiles are cached in the app cache folder (at most 50 MB, least recently used first; never backed up); turning the switch off, or Delete all, deletes the cache, and Delete all also resets the switch to off and forgets that you accepted the explanation. The switch is stored in `online_map.xml`, excluded from backup and device transfer. The map shows the provider's attribution (and its name as the logo text) whenever its tiles are drawn. Logs name events and counts only, never a tile number, a coordinate, a URL or the key. The key is a public client key embedded in the app, not a secret; without one a release build shows the grid only.

**Centre on me**: a button on the same screen asks for ONE foreground location fix with the existing location permission (no new permission), only when you tap it, then moves the map to a view about 500 m across and shows your position. The fix is held on screen only: it is not stored, logged or sent anywhere (the tiles requested afterwards are for the area now shown, as with any panning).

**Photographing people**: some people and places are legally protected from being photographed or filmed. You are responsible for knowing and following the law where you are. A zone is a safety aid, not legal advice or a guarantee: GPS can be wrong. The **Jarawa preset** (Andaman Trunk Road) uses an **approximate, UNVERIFIED** box. Check it on the map yourself, and also pause or take the glasses off in sensitive areas.

## 11. Place Guide and people

Place Guide names monuments and buildings only. Candidates come from three places: Wikidata classes in a monument allowlist, a curated list of landmark QIDs (for example some lakes and viewpoints that no allowlisted class covers), and OpenStreetMap places tagged historic, heritage or tourism (attraction, museum, viewpoint). Wikidata items whose class is a person (human and fictional human classes) are rejected explicitly, even if the QID is curated, and an item whose class cannot be read is also dropped. So people are never candidates; the prompt says "never identify or describe people"; and the card text is a template over the stored Wikipedia extract, not model-written. A photo-derived place-match is second-checked by the provenance gate and the zone fence. PCA does not recognise faces or voices.

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
- `MapNoNetworkTest`: the map code names no network class.

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
| Delete all, kept items | `services/orchestration/.../orchestration/MemoryWipe.kt`; `services/memory/.../forget/DeletionGraph.kt` (`pause_zones`, `place_pins` KEPT); `.../forget/lifecycle/Families.kt` |
| Forget design | `docs/superpowers/specs/2026-11-16-forget-lifecycle-design.md`; `services/memory/.../forget/` |
| Nexus trust text, residual window, 24 h | `phone/.../nexus/NexusStatus.kt` (`TRUST_LINES`); `nexus-bridge/README.md`; `NexusChokePointTest` |
| Nexus wipe on Forget/Delete all, not on retention | `phone/.../nexus/NexusGraph.kt` |
| Suggestions shadow, local only | `docs/superpowers/specs/2026-11-18-v3-proactive-loop-design.md` section 3; `MemoryWipe.kt` (`PROACTIVE_*`) |
| Logs literal-only, wiped | `phone/.../log/PhoneLogSink.kt`, `PhoneLogging.kt`; `phone/src/test/.../log/LogContentScanTest.kt` |
| Developer mode | `phone/.../canvas/DeveloperModeStore.kt`; `phone/.../ui/settings/Diagnostics.kt` |
| Offline map, hostile-file limits | `docs/offline-maps.md`; `phone/.../ui/settings/privacy/zones/tiles/PmTiles.kt`, `OfflineMap.kt`; `MapNoNetworkTest`; `backup_rules.xml` (`offline_map.xml`, `offline_maps`) |
| Place Guide mirrors, User-Agent, partial seed | `services/connectors/.../place/PlaceSources.kt` (`DEFAULT_ENDPOINTS`), `PlaceHttp.kt` (`DEFAULT_USER_AGENT`); `phone/.../ui/settings/place/PlaceGuideSettings.kt` (presets, "seeded without OSM extras") |
| Pins excluded from backup | `phone/src/main/res/xml/backup_rules.xml` (`place_pins.xml`); `services/memory/.../place/UserPlace.kt` |
| Suggestions switches, ledger, never list | `phone/.../ui/settings/suggestions/SuggestionsSettings.kt`; `services/orchestration/.../proactive/DeliveryLedger.kt`, `SuggestionStore.kt`, `ProactiveForget.kt`; `docs/superpowers/decisions/2026-11-18-codex-proxy-rulings-3.md`; `docs/superpowers/reviews/2026-11/codex-v31-r3.md` (open P1s) |
| Ask zone fence, provenance gate | `services/orchestration/.../orchestration/AskZoneFence.kt`; `services/orchestration/.../provenance/ProvenanceGate.kt` |
| Forget status in Ask | `phone/.../ui/home/HomeScreen.kt` (`ANSWER_FROM_FORGOTTEN`); `phone/.../ui/list/ListRoute.kt` (`forgetV2`) |
| Nexus signer check, wipe debt | `nexus-port/src/main/kotlin/io/github/pca/nexusport/SignerTrust.kt`; `phone/.../nexus/NexusHostStore.kt` |
| Install helper backup | `tools/install-trip-build.sh`; `tools/INSTALL-TRIP-BUILD.md` |
