# Seed-area selector, offline map screens, "Centre on me" and reference captions: design (26 Nov 2026)

Status: DESIGN ONLY, nothing built. Owner model (26 Nov): map tiles are only a LIVE selector, to see what area is being chosen. The app stores coordinates (pause zones, seed areas) and the places plus their text fetched for a seed area, NEVER tiles. Moments, Scenes and pause zones run on the phone's GPS, not on the map. Sikkim/Darjeeling (1-6 Dec) can lose network, so everything a card needs must be on the phone before leaving. Reference screenshots: Google Maps "Download a map of this area?" (fixed rectangle, size estimate, one button) and the Google Photos heat map (locate-me button).

Paths below are under `android-companion/`; `phone/.../ui/` means `phone/src/main/java/com/rokid/context/phone/ui/`.

## 1. What a seeded place stores today (checked) and the gaps for offline cards

- Seeding: `PlacePack.seedArea` (`services/memory/.../place/PlacePack.kt:218`) fetches the box 5 km cell by cell (`Geohash5`, at most 4,000 cells) from `PlaceCellSource` (`services/connectors/.../place/PlaceCellSource.kt`): Wikipedia geosearch and 3-sentence intro (`PlaceSources.kt:90`), Wikidata class and hierarchy, Overpass joined on `wikidata`. Only cell keys are sent, never a position.
- Per place (`PlaceEntity`, `PlacesDb.kt:22-39`): id, parent id (site and sub-place), name, kind, lat/lon, radius, `summary` (the Wikipedia intro), source, revision, fetchedAt, `seeded`, `pinned`, LRU day, bytes. Per area (`AreaEntity`, `PlacesDb.kt:48`): box, seededAt, status (done, partial, seeding), `name` (v18 WP11b, no UI yet). Seeded places are never evicted by the 50 MB budget and survive Delete all (`PlacePack.kt:83-86`).
- Offline use: with no network `ensureCell` uses whatever is stored (`PlacePack.kt:178-179`); on a costly network a seeded cell is not refetched (`PlacePack.kt:164-176`). Admission: Offline-only privacy and FREE Wi-Fi are enforced before every cell and request (`phone/.../place/AndroidPlaceGuideControl.kt:38-58`, `SeedGate`): Wi-Fi only is already mandatory, not just a default.

Gaps (cards must work offline in Sikkim):
1. **Offline card is name + distance only** ("nearby · not checked", `services/orchestration/.../place/PlaceCard.kt:104-105`): the stored summary is not shown because no photo check ran. The fix is the planned offline tier (caption + text match, "Maybe <place>", owner round 6), not this doc; section 5 improves its matching.
2. **Coverage**: only places with an English Wikipedia article AND a Wikidata monument class are stored (`PlaceCellSource.kt:14-17`). Monasteries or viewpoints without an article are absent. Not fixed here; the owner should know before relying on the pack.
3. **Seed dies with the screen**: both seed paths run in `viewModelScope` (`ui/settings/privacy/zones/SeedOverlays.kt:195`, `ui/settings/place/PlaceGuideSettings.kt:232`). Leaving the screen cancels; the area row written as "seeding" at the start (`PlacePack.kt:248`) is only rewritten on a `SeedStopped` (`PlacePack.kt:227-229`), so it can stay "(seeding)" forever. Seeding again resumes (fresh cells are skipped, `PlacePack.kt:215`).
4. **Partial cells** (Overpass did not answer) have no OSM extras: re-seed on Wi-Fi until the area shows no "(some cells failed)".
5. **Estimate**: `SeedEstimate.line` shows cells, time (10 s a cell, UNVERIFIED) and "Wi-Fi only" (`SeedOverlays.kt:49-92`), not places or MB. "Remove" keeps the places as ordinary fetched places (`SeedOverlays.kt:40`).

## 2. "Seed this area?" selector (built on the existing seed code)

- Entry: "Seed an area" on the map-zones screen and Place Guide. Full-screen live map with a FIXED rectangle inset 10% from each edge (Google style); the user pans and zooms the map under it. Box = viewport unprojection of the rectangle (`MapViewport`). The tiles are only the live street map (MapTiler, existing switch); with none the plain grid plus the existing seed overlays.
- Estimate shown BEFORE any fetch, under the rectangle: "About 680 cells (5 km), about 300 places, about 0.3 MB of text, about 2 h on Wi-Fi". Cells exact (`SeedEstimate.cells`). Places and MB from the owner's own pack (seeded places per seeded cell, bytes per place, from `PackStats` and `areas()`), shown as "about"; before 20 seeded cells exist, a default of 0.5 place per cell and 1 KB per place, marked "rough". Over `CONFIRM_CELLS` (100) the existing yes/no; over 4,000 refused (unchanged). A Sikkim-sized box is about 650-700 cells.
- Button "Seed" (disabled offline, under Offline only, or off free Wi-Fi, with the existing reason text). Seeding runs in a `CoroutineWorker` (WorkManager is a dependency, `phone/build.gradle.kts:123`; dataSync foreground type declared, `AndroidManifest.xml:14`), unique per area id, so it survives leaving the screen. Progress "cells 120 / 680" in a notification and on the area row. Cancel (notification or row) stops it and writes the area as partial. Resume = seed the same area again (fresh cells skipped). Wi-Fi only stays mandatory (`SeedGate`); no mobile-data option.
- Seeded areas list (same screen and Place Guide): name (rename uses the WP11b column), status, places, text size (sum of `bytes` of seeded places in the box), date; "Seed again" and Delete with two choices: "Remove the area only (places stay as fetched places)" (today's behaviour) or "Remove the area and its places" (new pack call, cascades section 5 rows).

## 3. Map screens with NO network

| Screen | Shows offline | Change needed |
| --- | --- | --- |
| Pause zones (`ui/settings/privacy/zones/ZonesScreen.kt`) | Plain grid, zone boxes and rings, your dot; "You are offline. Go online to draw or change zones." and Draw disabled (`ZonesScreen.kt:250-262`, `ZonesModel.kt:60`) | see ruling Q3 |
| Map zones editor (`ui/settings/mapzones/MapZonesScreen.kt`) | Plain grid, zones, seed areas, pins | NO offline notice and drawing NOT blocked: inconsistent with Pause zones; add the same notice and the same rule |
| Activity map (`ui/activity/ActivityMapView.kt`) | Plain grid + heat cells from the phone | `MomentHeat.tileNotice` (`ActivityMap.kt:135`) only knows "no key" / "switch off": add "No network: plain grid. Your Moments are shown from the phone." |
| Seed selector (section 2) | Plain grid, seed overlays | Seed disabled with the reason |

Nothing functional depends on tiles: pause zones are stored coordinates checked against GPS by the zone guard (`phone/.../zone/TripLocation.kt`, `services/memory/.../place/ZoneFence.kt`); cards read places.db (section 1); the Moment location is the zone guard's last GPS fix (`phone/.../hub/HubGraph.kt:204-205`). The street-map cache (`cache/online_tiles`, 50 MB, 7 days) may show recently viewed tiles offline; that is the permitted personal cache, not an offline map.

## 4. "Centre on me"

- Exists on Pause zones and the map-zones editor ("Centre on me" `ZonesScreen.kt:259`, "Me" `MapZonesScreen.kt:208`) via `AndroidCentreFix` (`ui/settings/privacy/zones/online/OnlineMapGraph.kt:65-85`): ONE fused fix per tap, 12 s timeout, not stored, not logged. Missing on the Activity map, which passes `rememberOnlinePanel(d, { false }, {})` (`ActivityMapView.kt:75`), and in the new seed selector.
- Add a crosshair button (bottom right) to both. Permission: existing foreground `ACCESS_FINE_LOCATION`/`ACCESS_COARSE_LOCATION` (`AndroidManifest.xml:35,37`), asked only on the tap; no background location, no listener, one fix per tap. Works with no map key and no network.
- Offline hills: `PRIORITY_BALANCED_POWER_ACCURACY` leans on Wi-Fi/cell positioning; with no validated network ask `PRIORITY_HIGH_ACCURACY` (GPS) with a 20 s timeout and "Finding you..." plus Cancel.
- Result: a dot, view about 500 m across (zones, selector) or 2 km (Activity). Screen state only: never stored, never logged (log lines stay literal), never sent. Denied: "Allow location to centre the map on you." and the system ask; permanently denied: "Location is off for this app. Allow it in Android settings." with no repeat prompt. No fix: the existing `OnlineMapWords.NO_FIX`. Coarse only: draw the accuracy circle.

## 5. Public reference captions (one extra matching level; images NOT stored)

- At seed time, after a cell's places are stored, a SECOND worker phase per area: for each new place with a Wikipedia page, ask the page's lead image (Wikipedia `pageimages`, 512 px thumbnail) and its Commons licence and author (`imageinfo` extmetadata); skip non-free (fair-use) images and images without a free licence. Download to a temp file in `cache/`, caption ONCE, store the text, delete the file in a `finally` (and sweep the temp folder at start). New host `upload.wikimedia.org` (plus `commons.wikimedia.org` if the metadata needs it), same identifying User-Agent, same `SeedGate`, Offline-only refusal and zone fence as the text seed. API field names to verify in the slice.
- Backend: the offline SmolVLM2-500M captioner (`:services:vision-local`, flag offlineCaption) by default, because the offline tier captions the user's photo with the SAME model and like-for-like captions match best; the online vision model only if the captioner is not downloaded and Model privacy allows. Runs only while charging AND on free Wi-Fi (worker constraints), one image at a time, model released after the batch (owner rule), cancelable like the seed.
- Estimate in the seed dialog grows by: "Reference captions: about 300 photos, about 25 min on charge (about 5 s each, spike median), about 20 MB downloaded and deleted, about 0.1 MB of text". Time is the cost, not storage.
- Storage (smallest option): ONE table in places.db, folded into the UNSHIPPED version 3 (`PlacesDb.kt:117`, migration 2->3 at `:137`, installed phones are on v2): `place_ref(placeId PK, caption TEXT, imageUrl TEXT, author TEXT, licence TEXT, capturedAt INTEGER, bytes INTEGER)`. It sits next to the place, so every pack delete removes it in the same transaction. Model name and version go to the existing `model_attribution` table (`services/memory/.../db/ModelAttribution.kt`, context.db) as a new kind `PLACE_REF`, refId = place id; it is in another database, so a start-up sweep deletes `PLACE_REF` rows whose place has no `place_ref` row. Cost: the Friday encrypted-DB rehearsal of places.db 2->3 must include the new table.
- Offline card use: the offline tier's candidate text for a pack place becomes name + reference caption (instead of name only); the card wording stays "Maybe <place>". Place details show "Reference: photo by <author>, <licence>, Wikimedia Commons" (the caption describes an image we no longer hold; keeping author and licence is the cautious choice, not a legal opinion).
- Cascade: removing an area with its places, `clearFetched`, budget eviction of fetched places, Delete all (fetched places only; seeded ones stay, as today) and the Place-object delete (`2026-11-26-place-object-design.md`) all delete the place's `place_ref` row and, through the sweep, its `PLACE_REF` attribution.

## 6. Attribution (one note)

Displayed tiles: MapTiler needs its logo text and "(c) MapTiler (c) OpenStreetMap contributors" while its tiles show; the app already draws both on the zones map and the Activity map (`OnlineTileSource.kt:32`, `MapCanvas.kt:316-318`, `ActivityMapView.kt:141-143`; terms in `handoffs/maptiler-terms-2026-11-25.md`). Stored place data: the app shows "Wikipedia · fetched <date>" on cards (`PlaceCard.kt:125`) and the seed note names Wikipedia, Wikidata and OpenStreetMap (`PlaceGuideSettings.kt:153`), but no licence appears anywhere. Add one line on the Place Guide and seed screens: "Place text from Wikipedia (CC BY-SA), Wikidata (CC0) and (c) OpenStreetMap contributors (ODbL); reference captions from Wikimedia Commons photos (author and licence kept per place)." Licence names from the projects' standard terms, not re-checked today.

## 7. Slices (one Sonnet agent each, one Gradle job at a time, tests per slice)

| # | Slice | Tests | Agent time |
| --- | --- | --- | --- |
| S1 | Offline notices: map-zones editor matches Pause zones; Activity offline wording | screen tests with network off | 1 h |
| S2 | Centre on me on the Activity map and seed selector; GPS priority and 20 s when offline (all screens) | fake `CentreFix`: denied, no fix, success | 1-1.5 h |
| S3 | Seed in a worker: survives leaving, notification progress, Cancel writes partial, no stuck "seeding" | worker test with fake pack; admission refusals | 2.5-3 h |
| S4 | Selector screen: fixed rectangle, places/MB estimate | `SeedEstimate` math, estimate from stats | 2 h |
| S5 | Seeded-areas list: size, rename, two-way delete; attribution line | pack delete-with-places test | 2 h |
| C1 | `place_ref` table in places.db v3 + cascades + `PLACE_REF` kind + sweep | migration 2->3 test, cascade tests | 2 h |
| C2 | Lead image fetch, licence filter, temp file, caption backend, worker (charging + Wi-Fi) | fake HTTP and captioner; temp file always deleted | 3-4 h |
| C3 | Offline tier uses name + reference caption; place-detail credit | ranking test with captions | 1 h (needs offline tier V2) |

Order for Friday: S1, S2, S3, then S4 and S5; Codex review (medium) over the batch, about 1 h. About 9-10 h in all: S4 and S5 can slip (draw-a-box seeding works today); S3 can slip if the owner keeps the screen open and seeds again until done. C1 must land before the v18 install or wait for a later schema; C2 and C3 (about 5 h) fit only after the Monday go/no-go, or post-trip.

## 8. Owner rulings needed

- **Q1 Reference captions before Sikkim?** Default: fold the `place_ref` table (C1) into places.db v3 now so no later migration is needed; build C2/C3 after the Monday go/no-go only if time allows.
- **Q2 Caption backend.** Default: the offline SmolVLM2 captioner always (same model as the offline tier); online model only when it is not downloaded.
- **Q3 Drawing zones offline.** Default: keep "draw online only" on both editors (with Centre on me a box around you could be allowed offline later).
- **Q4 Delete a seeded area.** Default: ask each time; "area and its places" preselected.

## 9. Storing map tiles: not needed, not planned

The app never stores tiles beyond the street map's existing personal cache (50 MB LRU, 7 days, deleted by switch-off and Delete all), which MapTiler's Cloud terms 5.7 allow. No offline basemap, area tile download or tile archive is planned. The PMTiles sideload reader (`ui/settings/privacy/zones/tiles/`) stays as it is, unused. Bulk tile download stays out (MapTiler terms 6.3; OSM tile policy).
