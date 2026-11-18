# Privacy

Proactive Context Agent (PCA) keeps your memory on your phone. This page says what is stored, what can leave the phone and to whom, what is never sent, how long things last, and what Forget and Delete all remove. Every claim points to code or a design doc in the table at the end. Anything I could not confirm is marked **UNVERIFIED**.

PCA has no server of its own and no account. There is no analytics or crash upload that I found in the code (**UNVERIFIED** for third-party libraries; check the merged manifest if this matters to you).

## 1. What PCA stores on your phone

- **Memories, photos, Moments, marks, answers, nudges**: in an encrypted Room database (SQLCipher). The key lives in the Android Keystore and never leaves the phone. Photos are JPEG files in the app's private storage.
- **Locations**: PCA does not store a precise location history. Pause-zone and Place Guide fixes are used in memory and dropped (`TripLocation`: "never stored, logged or sent"). Exceptions you create on purpose:
  - **Pins** (`place_pins`): names, notes and exact coordinates you pin. Kept until you delete them.
  - **Pause zones** (`pause_zones`): the boxes you draw, with names and buffers.
  - **Place memories**: a photo that matched a monument stores the monument (id, name), not your coordinates. A memory's own context line may carry a coarse place label if you switched the Location context source on (off by default).
- **Place pack** (`places.db`): public text about monuments fetched for 5 km cells, plus when you last looked at each (recency). Recency is a kind of location history, so Delete all clears it.
- **Voice**: recognition is on the device and audio is never stored or uploaded. Voice marks only save a mark for the last 10 seconds.
- **Logs**: see section 8.

## 2. What leaves the phone, and to whom

| What | To whom | When |
|---|---|---|
| Prompts: your question, memory text, nudge and summary requests, time-word and search-word helpers | The **model endpoint you configured** per role (Ask, Summarise, Nudge, etc.) | Only if that endpoint is remote. A local on-device model sends nothing. |
| Photos for captions and scene summaries (`VISION_EXTRACT`, `CLUSTER_SUMMARY`, `INTENT_CLASSIFY`) | Your configured **vision endpoint** | Every captured photo that is described, if the vision role points to a remote endpoint. |
| One extra photo plus a numbered list of candidate monument names (`PLACE_MATCH`) | The same vision endpoint | Place Guide on, a glance (dwell) photo, candidate places nearby, not in a pause zone. This is a second upload of the same photo; bystanders may be in it. |
| Photos that match a question | Your configured **Answer endpoint** | Only if "Send matching photos with questions" is on (off by default, max 2 photos, never while paused or in quiet hours, switched off again by Delete all). |
| Embedding requests | Your embedding endpoint, if you chose a remote one; otherwise on-device | Text/image embedding role binding. |
| The 5 km cell box (not your position) | `en.wikipedia.org`, `www.wikidata.org`, `overpass-api.de` | Place Guide seeding or a glance with no fresh cell. Each request is checked against pause zones first. |
| Model file download | `huggingface.co` | Only when you download an on-device model. |
| Notice and status text (nudge, place card, answer, mark text, capture status) | The **Rokid Nexus hub app on the same phone**, via the optional bridge | Only when you pick Nexus as the HUD host. See section 6. |

Whatever your own endpoint (OpenAI-compatible, Azure, a box on your LAN) receives is governed by that provider's terms, not by PCA. Endpoint keys are stored encrypted on the phone, excluded from backup, and entered in the app, never in the repo.

Every model call declares which photos and memories it carries (`PayloadSources`); a forgotten photo, and text derived from it, is blocked from being sent, shown, exported or kept.

The reverse-geocode label for the Location context source uses Android's `Geocoder`; which service answers is decided by your Android system (**UNVERIFIED**).

## 3. What never leaves

- The database, its key, and memory contents (except the prompts above that you chose to send).
- Raw audio. Precise fixes. Your exact position (only the 5 km cell box goes to Wikimedia/Overpass).
- Anything while PCA is paused, or inside a pause zone, for the Place Guide fetch and Ask.
- Suggestions (section 7): no network, no model call in v3.0.

## 4. Retention and expiry

Defaults from `MemorySettings` (changeable in Settings, Memory):

- Photos: **7 days** (skipped photos 1 day). Captions, scene summaries and image vectors: **kept** until you Forget them.
- Memory tiers: short 24 h, medium 7 d, long 30 d (`RetentionWorker`; which kinds use which tier: **UNVERIFIED**).
- Soft storage quota: 250 MB.
- Canvas live trace: 7 days. Suggestion records: 14 days. Place pack: 50 MB budget with least-recently-used eviction (design doc; pinned places are never evicted).
- Place pack cells older than 30 days are refetched.

## 5. Forget and Delete all

**Forget** (a fact, photo, Moment, answer, nudge, or a time range) removes the item and the copies derived from it: captions it produced, scene and Moment summaries, cached exports, run outputs, lineage. Answers that cited it are redacted. It also withdraws what is on the glasses and cancels linked alarms and notifications. A forgotten photo is tombstoned so a late write cannot bring it back. Retention expiry deletes quietly and does not wipe Nexus history.

**Delete all** removes every memory, all photos and frame copies, the queue, exports, canvas traces, the diagnostic logs, the photo test data, suggestion settings and shadow records, fetched place data and recency, and withdraws the "send photos with questions" consent. It then vacuums the database.

**Kept by Delete all** (these are your own configuration, listed in the deletion registry):

- Endpoints, keys, prompts and other settings.
- **Pause zones** (`pause_zones`): never deleted by Forget or Delete all.
- **Pins** (`place_pins`): never deleted by Forget or Delete all.
- Seeded Place Guide areas, and the Nexus host choice (`nexus_host`).

To remove those, delete them yourself in Settings.

## 6. Rokid Nexus bridge (optional, off by default)

Same words as the in-app text (`NexusStatus.TRUST_LINES`):

- Rokid Nexus and the bridge are separate apps. Nexus is third-party code.
- When Nexus is the HUD host, the bridge sees every card PCA sends to the glasses: nudge, place card, answer and mark texts, and the capture status. It can draw and hide them, and it tells PCA when you tap Dismiss, Mark or More. It cannot read your memories or photos and stores nothing. The bridge APK has no INTERNET, microphone, camera, location or storage permission.
- Nexus can keep the notices it showed in its notification history for up to 24 hours. PCA asks Nexus to wipe that history when you Forget, Delete all, or switch Nexus off. When you pause, or a pause zone is near, PCA hides what is on the glasses at once.
- Nothing is sent while PCA is paused, a pause zone is near, or the glasses are not worn.
- Rokid-Coder and Nexus run one at a time on one pair of glasses. PCA never installs, starts or takes over either of them.

**Voice permissions**: approving the plugin in the hub can pre-select speech-to-text, text-to-speech and "replace the glasses assistant". If you do not use Nexus voice, untick those three in Nexus, Settings, Plugin access before tapping Allow on glasses. PCA's own switches (voice, speak answers, skills) are all off by default, and a hub grant alone opens no speech session.

**Residual window**: PCA stops sending within about 750 ms of a pause, zone hold or Forget and discards anything queued. But a call that is already inside a hung Nexus hub cannot be interrupted. That notice may still appear on the glasses later, once the hub unblocks, then be hidden and wiped. PCA keeps owing the wipe until the hub confirms it. No PCA code can shorten that window.

## 7. Proactive suggestions (v3.0)

Off by default. In v3.0 the loop runs in **shadow mode**: it works out what it would show, writes only ids and tokens to an encrypted local store, and **delivers nothing** (no notification, no HUD card). It is local only: no network request and no model call. Suggestions never recognise people. The master switches and shadow store are excluded from backup and cleared by Delete all.

## 8. Logs

The diagnostic log (`files/logs`, two files, 6 MB cap) holds literal event names, counts, state names and durations. It is built so it cannot hold your content: `LogContentScanTest` fails any log call whose message is not a literal or an approved count/state/class name. It is excluded from backup and cleared by Delete all.

## 9. Backups

The phone allows Android backup, but excludes (cloud backup, device transfer, and the older rules): the database and its key, endpoint keys, photos and every copy of memory text, the place pack, pins, logs, models, set-up and device state, the Developer mode flag, every per-device privacy switch (send photos, Place Guide, Nexus host, Suggestions, helper grants), and pause-zone runtime state. What is restored is plain preferences (hub, retrieval, prompt, persona and voice settings) and your **pause zone definitions**. The watch and the glasses app turn backup off entirely. `BackupRulesTest` pins this list, so a new file cannot slip into a backup unnoticed.

Consequence: an uninstall deletes the database (the key goes with it), and a restore onto a new phone brings back no memories. The explicit settings export (which you choose to make and share) is the only way to carry pins to a new phone.

## 10. Pause zones and the law

A pause zone stops capture while you are inside it or near it (a buffer). It uses foreground location only: no background-location permission and no geofencing API. Fixes are never stored. If the zone list is unreadable, or the fix is stale or location is off, PCA fails closed and treats you as inside. A mark photo does not bypass a zone. By default (Diagnostics switch) Ask is refused and its answer withheld inside a zone.

**Photographing people**: some people and places are legally protected from being photographed or filmed. You are responsible for knowing and following the law where you are. A zone is a safety aid, not legal advice or a guarantee: GPS can be wrong. The **Jarawa preset** (Andaman Trunk Road) uses an **approximate, UNVERIFIED** box. Check it on the map yourself, and also pause or take the glasses off in sensitive areas.

## 11. Place Guide and people

Place Guide names monuments and buildings only. The candidate list comes from Wikipedia/Wikidata classes in a monument allowlist, so people are never candidates; the prompt says "never identify or describe people"; and the card text is a template over the stored Wikipedia extract, not model-written. PCA does not recognise faces or voices.

## 12. Developer mode

Off by default (Settings, Diagnostics); the flag is excluded from backup. When on, it shows extra screens and lets you use the experiment canvas, a local web UI served by the phone (token-paired sessions), the developer sensor recording, Moment and Scene Forget scopes, and longer canvas sessions (optional). Developer mode adds no new upload destination that I found. Raw sensor traces it creates are never exported or backed up, and Delete all removes them.

## 13. How to verify

Run these tests (`cd android-companion && ./gradlew test`), or read their names in the repo:

- `BackupRulesTest`: what is and is not backed up (phone), with `GlassesBackupRulesTest`.
- `LogContentScanTest`: logs contain no content.
- `ProvenanceChokePointTest`: derived-from-forgotten text cannot be written, shown, exported or sent outside one gate.
- `NexusChokePointTest`: the one sink is the only path from PCA to Nexus.

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
| Logs literal-only, wiped | `phone/.../log/PhoneLogSink.kt`, `PhoneLogging.kt`; `LogContentScanTest` |
| Developer mode | `phone/.../canvas/DeveloperModeStore.kt`; `phone/.../ui/settings/Diagnostics.kt` |
