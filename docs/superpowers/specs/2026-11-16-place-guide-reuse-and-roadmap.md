# Place Guide + pause zones: reuse inventory, architecture, trip schedule, version roadmap

Status: PLANNING (2026-11-16, Mon). Branch `feat/v101-ask-eval`. Paths are relative to `android-companion/`. Room v16, `PROTOCOL_VERSION = 1`
(`core/model/.../Protocol.kt:7`), default plan hash `f5782e0d8610` (`services/ai/.../canvas/DefaultPipelines.kt:92`); all three stay unchanged.
Deadlines: Hyderabad field test on day 8 (Tue 24 Nov), Andaman on day 12 (Sat 28 Nov), Sri Lanka on day 35 (Mon 21 Dec). Effort is in Sonnet-days (Sd), with a Codex review per WP.

## A. Reuse inventory

| Capability | What exists today (file:line) | Verdict | Sd |
|---|---|---|---|
| Glance detection | `SamplingSession.onDwell` admits a glance, `onOnset` fires at the first movement after it (`glasses/.../capture/session/SamplingSession.kt:315,325`); `FrameMeta.dwell` marks a glance photo on the wire (`Protocol.kt:560-580`) | REUSE AS IS | 0 |
| Photo arrival on the phone | `HubGraph.onFrameStored` commits the receipt and then wakes the pipeline (`phone/.../hub/HubGraph.kt:101`); captions are batched and may wait (`KeyframePipeline.kt:152,438`), so they are too slow for a live card | REUSE (as a hook point) | 0 |
| Per-photo side call outside the canvas | `IntentSampler`: one photo in flight, spacing, fences for the source switch, wipe generation, Forget tombstone and the power/thermal gate (`services/orchestration/.../frames/intent/IntentSampler.kt:34-45`), registered at `HubGraph.kt:189` | EXTEND (copy the pattern as `PlaceSampler`) | 1 |
| Vision call | `ModelRouter` (`services/ai/.../ModelRouter.kt:37`, image-dispatch guard, fallback); `RoleRunner` (`run/RoleRunner.kt:75`, contract check + one retry); precedent `PromptKey.INTENT_CLASSIFY` on `Role.VISION_EXTRACT`, one photo in, strict JSON out (`contract/PromptKey.kt:26`, `RoleContracts.kt:49`) | EXTEND: new `PromptKey.PLACE_MATCH` on `Role.VISION_EXTRACT` (no new Role, no new binding UI), `PlaceMatchOutput` contract | 1 |
| HUD card | `Overlay.Nudge(text)` (`Protocol.kt:131`); `NudgeToast` (`core/hud/.../render/Overlays.kt:78`); shown only at MAX verbosity (`HudPresenter.kt:94`), 8 s (`:82,97`); minimised pop-up (`glasses/.../overlay/PopupOverlay.kt:83`, `PopupGate.kt:7`); phone side: `pickOverlay(voice, actions, nudge)` (`Hub.kt:613,646`) | EXTEND: additive `place: PlaceCard? = null` on `Overlay.Nudge`, a `PlaceCard` composable, its own gate and dismissal | 1.5 |
| HUD position | `HudPosition` 9 positions (`Protocol.kt:166`), `HudSettings.position` (`:200`), `alignment()` (`render/Format.kt:24`) | REUSE (fixed position now, coarse L/C/R later) | 0 |
| NudgeEngine | `services/orchestration/.../nudge/NudgeEngine.kt:57` (LLM nudges written to the nudge table) | NOT USED: place cards are not nudges, never in the nudge table or the Activity feed | 0 |
| Location | `SpeedSource`: fused BALANCED 30 s, only while a vehicle is suspected, the fix is never stored (`phone/.../context/SpeedSource.kt:3-15,217-229`); `LocationSource`: last-known network fix + Geocoder label for context (`services/connectors/.../sources/LocationSource.kt`); FGS type `location` is added only while wanted (`ContextHubService.kt:120-128`, `locationHeld` at `HubGraph.kt:185`); manifest has FINE/COARSE + `FOREGROUND_SERVICE_LOCATION`, no BACKGROUND (`phone/src/main/AndroidManifest.xml:25-28,55`); `play-services-location:21.3.0` (`phone/build.gradle.kts:111`) | EXTEND: new `TripLocation` (same FGS-held rule, rate set by the distance to a zone); store zone STATE, never the fix | 1 |
| Pauses (phone) | `HubPauses` in Room (`pause_record`, `pause_interval`), quiet hours minted per occurrence (`phone/.../hub/HubPauses.kt:19,33-48`), resume by name only (`:64`) | EXTEND: zone records `zone:<zoneId>:<n>`, origin PHONE (no wire or Room change); phone Resume skips them | 0.5 |
| Pauses (glasses) | `GlassesPauses.toggle` (a double-tap) **resumes every active record, phone ones too** (`glasses/.../pause/GlassesPauses.kt:97-108`); `mergeFromPhone` (`:126`); `PauseSource.USER_PHONE` (`PauseSource.kt:11`) | EXTEND: toggle never clears a `zone:` record (it shows "Pause zone: capture off") | 0.5 |
| Mark photo during a pause | **A mark photo deliberately bypasses pauses** on the glasses (`glasses/.../mark/MarkPhotoCapture.kt:33`) and on the phone (`services/glasses-link/.../FrameAdmission.kt:77-88`) | EXTEND: a zone interval beats the mark allowance on both sides | 0.5 |
| Frame admission | Drops frames captured inside any pause interval kept for 7 days (`FrameAdmission.kt:62-95`) | REUSE AS IS (the second gate for zones) | 0 |
| Memory with a place | `MemoryEntity.frameId`, `type` (free string), `tags`, `searchText` in FTS (`services/memory/.../db/MemoryEntities.kt:27-48`); `frame_source` provenance (`FrameEntities.kt:56`); `object_derived` per Moment (`ObjectEntities.kt:28`) | REUSE: a `type="place"` memory row linked to the photo (no migration). A `place_visit` table would be v17 (question 2) | 0.5 |
| Forget / export | `DataOpGate` export-vs-delete (`services/orchestration/.../DataOpGate.kt:16`); the v1 cascade follows `frameId`/`frame_source`; Delete all (`WipeDao.kt:8`) | REUSE: the place memory is photo-derived (the photo chose it), so it is forgotten with its photo. Pack recency is location history: Delete all clears it. Pause zones are never deleted by Delete all | 0.5 |
| Settings | Root rows (`phone/.../ui/settings/SettingsRoot.kt:97-123`), Privacy "Pause, quiet hours" (`:104`, `privacy/PrivacySettings.kt`), Agent flows description (`services/ai/.../flow/flows/CaptureFlow.kt:13`) | EXTEND: a "Trips" row (map, packs, Place Guide toggle); Privacy shows the zones; Capture flow gets a descriptive "place" step (not a canvas node) | 0.5 |
| Canvas Query/Nudge flows | `DefaultPipelines.kt:92-161` | NOT TOUCHED (plan hash stays `f5782e0d8610`) | 0 |
| Map view, two layers | none | NEW: osmdroid view (no key), red PAUSE boxes and blue DOWNLOAD boxes | 1.5 |
| Place pack (cache-aside) | none | NEW: separate Room DB `places.db` v1 (not a ContextDatabase migration) | 1 |
| Geo sources | none | NEW: Wikipedia geosearch + extracts, Wikidata classes/part-of, OSM Overpass | 1.5 |
| Zone rules engine | none (quiet hours are the closest pattern) | NEW: `ZoneEvaluator` (enter, buffer, hysteresis, fail closed) + Jarawa preset | 1.5 |
| Offline map tiles | none | NEW, later (v1.3.0) | 2 |

## B. Architecture

### B1. One map, two layers (phone)
`Settings > Trips > Map`: osmdroid `MapView` in `AndroidView`, online OSM tiles while drawing (normal interactive use, a proper User-Agent). The user draws axis-aligned rectangles:
- **Red PAUSE zone**: `{id, name, bbox, bufferM (default 300 m; the Jarawa preset 5 km), enabled, preset}`.
- **Blue DOWNLOAD zone**: `{id, bbox, seedPlaces: true, tileZooms: null | 12..16}`.
Both are stored in a small JSON DataStore (no Room). Red always wins over everything.
Bulk tile download from `tile.openstreetmap.org` breaks the OSM tile policy, so offline tiles wait for v1.3.0 (MapLibre + a PMTiles extract the user builds on the Mac, or a source whose terms allow downloads). Zone matching is pure geometry and needs no tiles.

### B2. Pause zones (the safety feature, fail safe)
- `TripLocation` asks for fixes only while zones exist: BALANCED every 5 min when the user is more than 25 km from every zone, every 30 s within 25 km, HIGH accuracy every 10 s within buffer + 2 km. It runs under the hub FGS `location` type, while-in-use, like `SpeedSource`. **No `ACCESS_BACKGROUND_LOCATION` and no Geofencing API**: geofences need background location, have minute-scale latency and are cleared by a reboot or a data clear. The fix is never stored. Only `zoneState ∈ {FAR, APPROACH, INSIDE}` plus a time are saved.
- `ZoneEvaluator`: the phone enters a zone at bbox+buffer and leaves it at bbox+buffer+200 m, after 2 consecutive fixes outside with accuracy ≤ 100 m. On entry `HubPauses.add(PauseRecord("zone:<id>:<n>", PHONE, now, null))`; on exit it resumes by name.
- **Fail closed**: a stale fix (> 3 min) or location off/revoked while `zoneState ≥ APPROACH`, or no fix at all after a hub recovery (a background FGS start gets no location; see the hub doc), all count as INSIDE. A phone notification says why.
- **Glasses side**: a double-tap never clears `zone:` records; a mark photo is refused while one is active; the additive `HudSettings.zoneApproach: Boolean = false` makes the glasses pause locally when the link drops while the phone is in APPROACH, so the camera stops even without the phone.
- **Phone gate**: `FrameAdmission` drops any frame captured inside a zone interval, mark or not, before any model call.
- **Jarawa preset**: the Andaman Trunk Road from the Jirkatang check post to Middle Strait, about 11.80-12.15 N, 92.60-92.85 E (**approximate, UNVERIFIED**: the user checks it on the map before day 11), with a 5 km buffer. Suggested user zones: Port Blair airport and naval areas.
- A zone cannot be disabled while the phone is inside it (question 4). Delete all never deletes zones.

### B3. Place pack (cache-aside store)
`places.db` (Room, its own DB at v1, SQLCipher with the same Keystore key):
- `place(id PK "wd:Q…"|"osm:w…"|"user:…", parentId, name, kind, lat, lon, radiusM, summary ≤ 400 chars, source "wikipedia:en"|"wikidata"|"osm"|"user", sourceRev, fetchedAt, seeded, pinned, lastUsedDay, bytes)`
- `cell(geohash5 PK, fetchedAt, status)`
- `area(blueZoneId, bbox, seededAt, status)`

Behaviour:
- **Seed**: before the trip, with internet, each blue zone is fetched cell by cell.
- **Cache-aside**: at a glance with no fresh cell (older than 30 days) and network up, the 5 km cell is fetched and written into the pack. Only the cell key leaves the phone, never the exact position.
- **Text only**: no images. Optional thumbnails (≤ 10 KB) are off by default.
- **Budget**: 50 MB by default, with LRU eviction by `lastUsedDay`. Seeded and pinned places are never evicted.
- **Sources**:
  - Wikipedia `list=geosearch` (gsbbox, up to 500 per call), then `prop=extracts|pageprops|coordinates` (`exintro`, plain text, 3 sentences, 20 per batch);
  - Wikidata `wbgetentities` (50 per batch) for P31 (kept only if the class is in a monument allowlist: monument, temple, stupa, fort, palace, prison museum, archaeological site, memorial, church, mosque, lighthouse, heritage building), P361/P527 (part of / has part, which gives the site → sub-place hierarchy) and P625;
  - Overpass: `historic=*`, `heritage=*`, `tourism=attraction|museum|viewpoint`, joined on `wikidata=*`.
  - Google Places: optional, behind a key, v1.3+.
  - People are never candidates (they have no coordinates, and the class allowlist excludes them).
- **Sizes (estimates)**: about 1 KB per place. Andaman blue zones (Port Blair, Swaraj/Havelock, Shaheed/Neil, Baratang, about 400 km²): 300-1,500 places, ≤ 2 MB. Sri Lanka cultural triangle + south coast: 5-15 k places, 5-15 MB. Tiles (v1.3, 256 px raster at about 20 KB each): Andaman 400 km² at z10-16 is about 1,450 tiles (≈ 30 MB), and z17 adds about 4,300 (≈ +85 MB); the Sri Lanka sites (about 750 km²) at z10-16 come to about 55 MB, plus the whole island at z0-13 for about 70 MB. Vector PMTiles would be about 3-10× smaller. Tiles get their own budget, shown before download.
- Sri Lanka sub-places that Wikidata lacks (Mirror Wall, Lion's Paw) are pinned by the user as `user:` places (v1.2.0).

### B4. Matching and the card
`PlaceSampler`, hooked after `onFrameStored`, handles `dwell` frames only, when Place Guide is on and no zone pause is active:
1. Candidates: places whose `distance - radiusM ≤ 300 m` from a fresh fix (≤ 60 s, accuracy ≤ 50 m). The ≤ 8 nearest are kept, and when one is a site its sub-places are added. No candidates means no upload.
2. Online: `PLACE_MATCH` sends the photo plus a numbered list of candidate names and kinds (no summaries). The output is `{"choice": "<candidate id>"|"NONE", "confidence": 0-1}`, plus an optional `box` field that is ignored until v1.4. A choice outside the set fails the contract and becomes NONE. The prompt says "monuments and buildings only; never identify or describe people", and the schema has no free-text field. At most one call in flight and one call per 20 s. The same place within 2 min and 30 m skips the call.
3. Offline, or the call fails: if the nearest candidate is unique within 100 m, a **fallback card** shows only its name and distance, plus "nearby · not checked". There is never a summary from a guess.
4. The card is `PlaceCard(placeId, title, line, sourceLabel, confidence "photo"|"nearby", frameId)`. `line` is the first sentence of the pack summary (≤ 140 chars) and `sourceLabel` is something like "Wikipedia · fetched 12 Nov". The model never writes card text.
5. Delivery: the phone has a `PlacePort.overlay`, and `pickOverlay` takes `place ?: nudge`. The glasses show it at MEDIUM and MAX (question 6) at the fixed `HudSettings.position`. A head-motion onset after at least 2.5 s dismisses it, and so does a 12 s limit or the next card.
6. Memory: one `type="place"` memory linked to the photo, with `frameId`, `frame_source`, tags `place,wd:Q…,<kind>,<parent name>` and the text "Looked at <name> (<parent>) · photo match 0.86". It goes through the existing writer, so "what was that temple on day 2" works through FTS. Better date handling arrives with retrieval quality in v1.2.0. Forgetting the photo removes the row.

Latency: dwell → BT → model is about 5-10 s. That is fine at a monument but not on a quick glance; day 8 measures it.

Not a canvas node: a capture-canvas node changes plan hash `f5782e0d8610` and drags Place Guide into plan review; the `IntentSampler` pattern is already accepted for per-photo side calls.

## C. Schedule

Two parallel worktrees (A, B) plus a third for the pack (C); Codex reviews each WP.

| Day (date) | A: hub + install | B: pause zones | C: Place Guide |
|---|---|---|---|
| 1-2 (17-18 Nov) | Hub doc T1, T2, T8 (diagnostics), T3 (`hubLive` recover), T5 | `ZoneEvaluator`, preset, HubPauses ids, glasses toggle lock, mark/admission gate, `zoneApproach` | pack DB + fetchers, tested on recorded API fixtures |
| 3-4 (19-20) | T4 boot, T6 BT ACL, T7 jobs | map view (two layers), `TripLocation` rates, fail-closed paths | `PLACE_MATCH` prompt + contract, `PlaceSampler`, `PlaceCard` wire + HUD |
| 5 (21) | Codex rounds | Codex rounds | place memory, Trips settings, diagnostic counts |
| 6 (22, Sun) | integrated build, install on phone + glasses (watch stays on v1.0 unless trivial) | | seed Hyderabad blue zones |
| 7 (23) | reduced worn-test list (#11) | a red zone over a local road: drive through it and check the logs | home check: airplane mode, fallback card |
| **8 (24)** | **Field test at Charminar + Golconda** | red zone around a stretch of the approach road | criteria: ≥ 70 % correct top-1 on dwell glances online, NONE on non-monuments, p50 card ≤ 8 s, zero zone leaks (no frame admitted, mark refused, double-tap refused) |
| 9-10 (25-26) | fixes, Codex re-review, **freeze + final install day 10** | | |
| 11 (27) | backups (`run-as`, `debug.keystore`) | user confirms the Jarawa preset on the map | seed Andaman, offline test |
| 12 (28) | Andaman | | |

Total ≈ 15 Sd across 9 working days. **Cut line if late**: the hub fix and pause zones always ship. If Place Guide slips, it ships as the GPS-only card + place memory (no vision call), and the vision step moves to v1.1.1.

**After Andaman, toward Sri Lanka** (assuming a return around day 20, 6 Dec; freeze day 31, install day 32, seed day 33):
- Forget lifecycle M0-M5, shadow first; M6-M9 only if shadow is clean;
- the photo-derived choke point (A tasks);
- retrieval quality;
- eval tooling (#7);
- suspect descriptions (#8);
- Place Guide v2.

**Moved after Andaman**: the Forget redesign (a large delete-path change, too risky 12 days before a trip without a laptop fix loop). **Moved after Sri Lanka**: release-key rotation (one irreversible install-over step that needs a spare-install rehearsal; it is not needed for a trip).

## D. Version roadmap

| Version | Name | Contents |
|---|---|---|
| **1.1.0** (day 10) | Trip build | hub restart (T1-T8), glasses restart diagnostics, pause zones + Jarawa preset, Place Guide MVP (seeded + cache-aside pack, vision choice, fallback card, fixed position, place memory), plus the done items (index battery, not-worn fallback) |
| 1.1.x | Trip patches | only safety or crash fixes found in Andaman |
| **1.2.0** (day 32) | Sri Lanka build | the rest of old v1.1 (Forget lifecycle, photo-derived choke point, retrieval quality, eval tooling, suspect descriptions); Place Guide v2: site → sub-place hierarchy, user-pinned sub-places, LRU budget + pinning UI, a heading spike (the glasses code reads no magnetometer today, so the hardware is UNVERIFIED) |
| **1.3.0** | Signed + offline maps | release-key rotation (v3 lineage, never uninstall), old v1.2 (multi-select Forget, try-it preview), offline tiles (MapLibre/PMTiles) under both layers, thumbnails option, optional Google Places key |
| **1.4.0** | Scale + spike | old v1.3: Activity pagination, Nexus spike, multilingual retrieval; Place Guide placement (left/centre/right from the model `box`, coarse only: the camera and display fields of view differ, UNVERIFIED by how much) |
| **2.0.0** | Nexus plugin | HUD surfaces (place cards as a Nexus surface), stt/tts, assist button, skills |
| **3.0.0** | Proactive agent loop on the phone | the place history feeds proactive suggestions |

## E. Risks

- **Legal (India)**: photographing or filming the Jarawa is a criminal offence (ANPATR; the 2012 amendment adds a buffer zone, details UNVERIFIED). The mitigations are layered: phone zone pause, a glasses lock, `zoneApproach` local pause, the admission drop and mark refusal. GPS can still be wrong in the forest, so the user should also double-tap-pause or take the glasses off in the ATR convoy. Defence areas around Port Blair (airport, naval) are also no-photo.
- **Sri Lanka rules to check**: photography of the Sigiriya frescoes is restricted; posing with your back to Buddha images is an offence; some sites charge a camera fee; drones are banned. Add red zones where needed.
- **Privacy**: a second upload of the same glance photo goes to the vision provider (tourists may appear in it). Dwell-only, candidate-gated and rate-limited; no people output. The Wikipedia/Overpass fetch reveals a 5 km cell.
- **Battery in tropical heat**: extra radio use and GPS on top of capture. The existing thermal gate applies through `PlaceSampler` admission, and GPS stays slow far from zones.
- **Flaky data on Havelock/Neil**: offline cards give the name and distance only; seed before the ferry; no queued live cards.
- **Schedule and tooling**: 9 tight days (hub T4/T6 need device checks; the cut line protects safety), about 15 Sd plus Codex rounds against the usage budget, and the osmdroid maintenance status is UNVERIFIED (MapLibre is the swap).

## F. Open questions (decisions)

1. **Version numbers**: 1.1.0 trip build and 1.2.0 Sri Lanka build, with your old v1.2/v1.3 items moving to 1.3.0/1.4.0. Accept?
2. **Place memory storage**: a `type="place"` memory row (no migration, recommended), or a Room v17 `place_visit` table / `frame_meta.placeId` column (needs your migration ruling)?
3. **Location**: stay on the while-in-use FGS location (recommended), or also add `ACCESS_BACKGROUND_LOCATION` + Geofencing as a second layer?
4. **Zone authority**: no way to disable a zone or resume capture while inside it (recommended)? Jarawa buffer 5 km and user zones 300 m, OK?
5. **Second photo upload** per dwell near a site for `PLACE_MATCH` (recommended), or fold the candidates into the caption call (this changes the `VISION_EXTRACT` prompt and is slower)?
6. **Card verbosity**: show place cards at MEDIUM too, not only MAX like nudges?
7. **Delete all**: clears the pack's fetched places and recency but keeps seeded areas and never removes pause zones. OK?
8. **Release-key rotation** after Sri Lanka (1.3.0), and the Forget redesign in 1.2.0 only if shadow mode is clean. Agree?

## Rulings (user, 2026-11-16, answers to section F)
1. Versions: accepted in principle, but the user wants EVERYTHING of the old v1.1 and the Place Guide finished and INSTALLED before Andaman (freeze day 10, 26 Nov), not deferred to a Sri Lanka build. Mitigations agreed by default: the Forget redesign ships with its shadow steps (M0-M5) on and the destructive switch-over (M6-M9) behind an off-by-default flag, flipped only if the shadow run is clean on real data; phone backup before every install; 1.0.0 APKs staged for rollback; anything not Codex-READY and tested by day 10 stays off the installed build.
2. Place storage: a `type="place"` memory row, no migration (user left the default).
3. Location: the hub's existing foreground location only.
4. Zones: no override while inside, 5 km buffer on the Jarawa preset, 300 m on user zones.
5. Place match: a second photo call per glance near a site.
6. Cards shown at MEDIUM as well as MAX.
7. Delete all clears fetched places, keeps seeded areas, never removes pause zones.
8. Release-key rotation after Sri Lanka (1.3.0).
Build constraint: Gradle runs one build at a time on this laptop (it was swapping), so writing code can be parallel but building and testing is serial.

