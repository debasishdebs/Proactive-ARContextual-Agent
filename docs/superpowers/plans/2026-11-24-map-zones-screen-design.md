# Map Zones screen: one map for pause zones, seed areas, pins and offline areas (design)

Status: design only, 2026-11-24, branch `feat/v101-ask-eval`. Author: Opus (designer). Nothing was built, run or installed. Paths are relative to `android-companion/` unless they start with `docs/`. Claims I did not check in code are marked UNVERIFIED.

## 0. The request and what it means

User, 24 Nov: *"In pause zones I need to see the map. For seeding also it should show me the map. A map selector via pan & zoom where I can drag a square/circle on the map. Then I can mark it as seed or pause. That way the same screen handles both. If I keep zooming out I can see all zones marked in different colours for different types of mark, like Google Photos shows photos on a map."*

Reference screenshots (viewed): `Screenshot_20261124_045112_Photos.jpg` is the Google Photos map: a dark base map, a purple/orange density layer over India, a bottom sheet with "27,397 photos" and a date range "January 1970 - November 2026". `Screenshot_20261124_045130_Maps.jpg` is Google Maps "Download a map of this area?": the map dimmed outside a rectangle, "Download will use up to 345 MB" and one Download button. (The other two Photos screenshots, `_045053` and `_045102`, were not opened, to save budget. From the file names and the brief they show the same density map at other zooms.)

What this design delivers:

1. **One screen**, Settings > Privacy > **Map zones** (it replaces "Pause zones" as the entry and is also opened from Place Guide > "Pick an area on the map"). It has a pan/zoom map. You draw a rectangle (or a circle) and choose **Pause** or **Seed** in a bottom sheet. **Note** comes later.
2. **An overview when zoomed out**: every pause zone, seed area, pin and downloaded map area, each type in its own colour and pattern, with a legend and counts. A **Moments density layer** like Google Photos comes in v2. It needs the planned location-per-Moment field and is behind a setting.
3. **"Download this area for offline"**, shaped like the Google Maps screen. It reuses `OnlineTileSource` and its consent, cap, Offline only and data saver rules.
4. **The safety rules stay as they are.** Pause zones still fail closed. Seeding still keeps to `MAX_SEED_CELLS` and Wi-Fi only.

## 1. What exists today (read for this design)

| Piece | Where | What matters here |
|---|---|---|
| Pause zones screen | `phone/.../ui/settings/privacy/zones/ZonesScreen.kt` (`ZonesRoute`, `ZonesScreen`, private `ZoneMap`, `drawGrid`, `drawZone`) | The map is a 300 dp Compose `Canvas` inside a settings list. **Draw a box** switches the pointer from `detectTransformGestures` (pan/zoom) to `detectDragGestures` (corner to corner). It has +/-/Fit buttons, typed S/N/W/E fields, a zone list with state chips, the Jarawa preset and a delete dialog. |
| Zone VM | `zones/ZonesModel.kt` (`ZoneEditor`, `ControllerZoneEditor`, `ZoneDraft`, `ZoneDrafts.fromDrag/parse`, `ZonesViewModel`, `ZoneWords.refusal`) | The draft is text in memory until Save. `save()` calls `editor.upsert`. A refusal (`BLOCKED_INSIDE`, `PROTECTED`, `NOT_READY`, `STORE_FAILED`, `INVALID`) shows as a message and nothing changes. `MIN_SPAN_DEG = 0.0005`. |
| Viewport math | `zones/MapViewport.kt` (`MapViewport.toPx/toLatLon/panned/zoomed/fit/ringOf/grown`, `LatLon`) | Web Mercator in pixels per degree (`ppd`). It is pure and unit tested. |
| Tiles | `zones/tiles/TileMath.kt` (`TileKey`, `TileMath.visible/screenRect/zoomFor`, `TileCache`), `PmTiles.kt` (`PmTilesReader`), `OfflineMap.kt`, `OnlineMap.kt` (`RasterTileSource`, `OnlineMapStore`, `OnlineMapModel`, `OnlineMapWipeListener`), `tiles/README.md` | There is no third-party map library. MapLibre was evaluated on 2026-11-18 and rejected (15.3 MB AAR, native code). `MapNoNetworkTest` keeps `tiles/` free of network code. |
| Online tiles | `zones/online/OnlineTileSource.kt` (`OnlineProvider.select`, `UrlConnectionTileTransport`, `OnlineTileDiskCache` 50 MB LRU / 7 day TTL in the cache folder, `OnlineTileSource`) | HTTPS to one host only (MapTiler, or OSM in unkeyed debug builds). It allows `SESSION_CAP = 400` requests per visit, 4 at a time, 512 KiB per tile, image-magic check, backoff, requests only between `start()`/`stop()` (screen foreground), `offlineOnly()` and `DataGate.decide(BULK_OTHER)` checked before every request and redirect hop, and bytes counted to `BULK_OTHER`. Tile numbers, URLs and the key are never logged. |
| Zone model | `core/model/.../zone/PauseZone.kt`, `ZoneEvaluator.kt` | A zone is an axis-aligned box plus `bufferM` (0..50 000, default 300). APPROACH is within `bufferM + APPROACH_MARGIN_M` (2 km). A fix is stale after `STALE_MS` (3 min). It fails closed: no fix, a stale fix or location off counts as INSIDE. |
| Zone store/engine | `phone/.../zone/ZoneStore.kt` (`pause_zones` prefs, JSON list, `corrupt` → protected, `write` refuses while unreadable, synchronous commit), `ZoneController.kt` (`upsert/setEnabled/remove`, `BLOCKED_INSIDE` when a zone you are in, or cannot prove you are out of, would change) | A saved zone is judged at once. An unreadable list pauses capture everywhere. Zones are user configuration: exported, backed up, and never touched by Delete all. The glasses app has no `PauseZone` code (`glasses/src/main` has no hits), so zone geometry is phone-only. |
| Place Guide fence | `services/memory/.../place/ZoneFence.kt` | Place Guide side effects close while any zone is approached, entered or unreadable. Seeding is an "unfenced caller" (`currentZoneTicket()` is null). |
| Seeding | `services/memory/.../place/PlacePack.kt` (`seedArea(blueZoneId, bbox, onProgress)`, `removeArea`, `areas()`, `MAX_SEED_CELLS = 4_000`, `RETRY_BACKOFF_MS`), `PlacesDb.kt` (`places.db` **Room v1**, separate from `context.db`; `AreaEntity(blueZoneId, box, seededAt, status)` with `AREA_DONE/PARTIAL/SEEDING`; `CellEntity(geohash5, fetchedAt, status OK/PARTIAL)`), `Geo.kt` (`GeoBox`, `Geohash5.cellsCovering/bounds`) | Seeding goes one 5 km cell at a time, with one retry pass and progress callbacks. **`removeArea` and `areas()` are not used by any screen today**, so seeded areas cannot be seen or removed. Delete all keeps seeded areas and their places. |
| Seed UI | `phone/.../ui/settings/place/PlaceGuideSettings.kt` (`SeedPreset`, `PlaceGuideModel.TRIP_PRESETS` incl. GANGTOK/DARJEELING/WEST_SIKKIM, `parseBox`, `seedMessage` "N of M areas failed", `PlaceGuideViewModel.seed/seedPreset`, typed S/W/N/E fields) and `phone/.../place/AndroidPlaceGuideControl.kt` (refuses under Offline only, refuses unless on free Wi-Fi, id `seed:%.3f,%.3f,%.3f,%.3f`) | The cell count shows on the preset button. There is no time or byte estimate. The id comes from the box, so seeding the same box again updates that one row. |
| Pins | `services/memory/.../place/UserPlace.kt` (`user:<uuid>`, lat/lon, `radiusM` 10..1500, kind, note, optional `momentId`), `PlaceGuideControl.pinning` | They are points with a radius. They are made on the Place Guide screen from a fresh fix. |
| Data saver | `core/model/.../core/net/DataSaver.kt` (`DataKind.BULK_OTHER`: "model download, seeding, night re-describe sweeps"; never "sent once"), `docs/superpowers/plans/2026-11-20-roaming-data-saver-design.md` | Bulk work is blocked on roaming, unknown networks, Android Data Saver or a used-up daily cap. |
| Privacy text | `PRIVACY.md` §1, §10 (offline map, street map, Centre on me), §15, §16 | The street map is off by default, asks first (Cancel is the default), is capped, and is cleared when switched off and by Delete all. |
| Why it matters | `docs/superpowers/plans/handoffs/trip-gap-analysis-opus-2026-11-24.md`, `trip-analysis-2026-11-24.md` | On the Hyderabad trip no zone was stored. No frame or Moment stores a location. Place cards depend on a fresh, accurate fix and a pack that is already seeded. Seeding the right areas before you go, and being able to see them, is what makes cards appear offline. |

## 2. Screen design

### 2.1 Entry points and routes

- Settings > Privacy > **Map zones** (route `map_zones`, `SettingsDestinations.kt`) opens in **All** mode. The old route `zones` points to the same screen, so existing deep links and tests keep working.
- Place Guide > "Seed this area" card > new button **Pick on the map** opens `map_zones?mode=seed`. Seed is preselected in the sheet and the legend highlights seed areas. The typed S/W/N/E fields and the presets stay on the Place Guide screen.
- The screen is **full height**. It is not a 300 dp card inside a list: the map gets the whole viewport, and the list moves into a draggable bottom sheet, like the Google Photos layout.

### 2.2 Layout (text mockups)

Browse state, zoomed out (overview):

```
+------------------------------------------+
| <  Map zones                    [Layers] |   Layers: Pause / Seed / Pins / Offline areas / (v2) My Moments
|                                          |
|        [5]  <- cluster chip (mixed)      |
|                     ##                   |   ## pause zone (red, hatched, solid edge)
|   ..:..             ##                   |   ..:.. seed area (blue, dotted edge)
|   :   :    o o                           |   o  pin (purple dot)
|   ..:..      o                           |   [ ] offline map area (grey outline)
|          [ ]                             |
|                                 [+][-]   |
|                                 [Fit]    |
|                                 [Me]     |
|  (c) MapTiler (c) OpenStreetMap contrib. |
+==========================================+
|  ----                                     |   bottom sheet, peek height
|  2 pause  .  4 seed  .  17 pins  .  1 map |   counts = legend; each chip toggles its layer
|  [ + Draw area ]   [ Download this area ] |
+------------------------------------------+
```

Draw state: tap **+ Draw area**, or long-press the map and drag.

```
+------------------------------------------+
| x  Cancel            Rectangle | Circle  |
|                                          |
|        o------------------o              |   o = 48 dp corner handles
|        |                  |              |   drag inside = move
|        |      (drag)      |              |   circle: centre handle + one radius handle
|        o------------------o              |
|   2.1 km x 1.4 km . 6 cells (5 km)       |   live size readout
|                                          |
+==========================================+
|  Use this area for:                      |
|  ( ) Pause capture here                  |
|  ( ) Seed places for offline cards       |
|  ( ) Note  (later, disabled)             |
|              [ Next ]                    |
+------------------------------------------+
```

Pause sheet (after Next):

```
Pause zone
Name        [ Grandma's house           ]
Ring        [ 300 ] m   (0 to 50,000)    the dashed ring on the map follows the value
What it does: capture pauses inside the box and the ring. Within 2 km more,
the glasses hold capture if the link drops. If location is off or stale,
capture pauses (fail closed).
[ Edit coordinates ]   (S/W/N/E fields, for TalkBack and precision)
[ Save pause zone ]      [ Back ]
```

Seed sheet:

```
Seed places
Name        [ Pelling monastery          ]
6 cells (5 km each) . about 1 min . Wi-Fi only
Only the 5 km cell keys leave the phone, never your position.
[x] Also download the street map for this area (opens the estimate)    // only when the street map is on
[ Seed now ]      [ Back ]
progress: 4 of 6 cells . 1 failed (will retry once)
```

Selected existing item (tap a shape or a list row): the sheet shows its name, type, state chip (pause: the existing `ZoneWords.chip`; seed: Done, "2 of 6 areas failed" or Seeding n/m), and **Edit shape**, **Rename**, **Delete**, plus **Seed again** (seed) or the on/off switch (pause). Seed areas also offer **Show cells**, which draws each 5 km geohash cell coloured ok / partial / failed.

Download sheet: see §4.2.

### 2.3 Gestures

| Gesture | Browse | Draw / edit shape |
|---|---|---|
| One-finger drag | pan | on a handle: resize; inside the shape: move; outside: pan |
| Pinch | zoom about the centroid (`MapViewport.zoomed`) | same |
| Double tap | zoom in x2 | same |
| Long press + drag | starts a rectangle from the press point (power-user path, as today) | n/a |
| Tap shape | select it, open its sheet | n/a |
| Tap cluster chip | zoom to fit its members (`MapViewport.fit`) | n/a |

A single `pointerInput` handler hit-tests the handles first, then the shape, then the map. That removes today's modal "Draw a box" switch, where drawing and panning could not both happen. Handles are 48 dp touch targets and draw at 12 dp.

**Circle.** A circle is a drawing aid only in v1. A **pause** circle is saved as the **circumscribed box**: the box that fully contains the circle, so it covers more, never less. The sheet says "Saved as the square around your circle". A **seed** circle seeds the 5 km cells covering that box, as seeding is cell based anyway. True circle zones (a `shape` field on `PauseZone` and `ZoneEvaluator.distanceToCircleM`) are a later option (ruling R4). They would change the export JSON and the evaluator, which this design avoids.

### 2.4 Overview rendering: zoomed out, Photos-like

- **Colours with patterns as well**, so colour-blind users can tell types apart: pause = `ZoneRed` 0xFFD32F2F, 25 % fill, solid 2 dp edge, diagonal hatch, dashed buffer ring. Seed = blue (`PcColors.Sky` or a new `ZoneBlue`), 15 % fill, dotted edge. An area with failed or partial cells gets an amber `!` badge, and a Seeding area a moving dash. Pin = purple 8 dp dot with a white rim, and its radius circle only at z ≥ 15. Offline map area = grey 1 dp outline, no fill. Disabled pause zone = 40 % alpha, as today.
- **Minimum size**: a shape smaller than 16 dp on screen is drawn as a 16 dp type chip, so a 300 m zone still shows at country zoom.
- **Clustering**: when chips overlap, a pure screen-space grid cluster (`MapClusters.cluster(items, viewport, cellPx = 48)`) draws one chip with the count. It is red if any pause zone is inside (pause wins, the safety signal), otherwise blue or purple.
- **Legend = counts row** in the sheet: "2 pause · 4 seed · 17 pins · 1 offline map". Each chip toggles its layer. The pause layer cannot be hidden while any pause zone is INSIDE/APPROACH, so the active zone never vanishes from view.
- The sheet expands into the list. It has sections Pause zones (the existing `ZoneRow` with its state chip and switch), Seed areas, Pins and Offline map areas, and tapping a row centres the map on it.
- The "you are here" dot stays as now: only with a fresh fix (`ZoneEvaluator.STALE_MS`), and never stored.

### 2.5 v2 density layer: "My Moments on the map"

- **Depends on** the location-with-each-Moment field (trip-analysis fix C; `2026-11-24-offline-trip-replay-harness.md` §"needs the location with each Moment field": local only, a privacy class, inside the Forget cascade, `fixSource`). That needs `context.db` **v18**, so it is not part of this design's build. It also needs a new setting **Show my Moments on the map**, default **OFF**, in Settings > Privacy.
- Rendering: aggregate in memory per visible zoom. Count Moments per geohash cell (precision 3/4/5/6 by zoom) with one `GROUP BY substr(geohash, 1, p)` query over the visible box and date range, then draw radial-gradient blobs (purple at low density to orange at high, as in the screenshot). Nothing is cached or stored. Every visit recomputes, so a Forget takes effect at once.
- The sheet shows "1,153 Moments · 19 Nov 2026 - 24 Nov 2026" with a date-range bar, as in Photos. Tapping a hotspot lists those Moments (keyset paging, reusing `momentsBetween` style paging).
- Privacy: the data never leaves the phone. The tile provider sees only the viewport, as with any panning. While Offline only is on, the density layer still works over the grid. A Moment inside a pause zone should not exist; if a legacy one does, it is shown like any other (no special case).

## 3. Safety (fail-closed rules carried over)

### 3.1 Pause zones

1. **No half-saved zone.** The drawn shape, the handles and the sheet fields live only in `MapZonesViewModel` memory (a `ZoneDraft`, extended with an optional circle). Nothing reaches `ZoneStore` until **Save pause zone**, which makes one `ZoneEditor.upsert(zone)` call. That is one synchronous `commit()` of the whole list (`ZoneStore.write`). Cancel, back, process death or rotation drop the draft. That is safe: losing an unsaved draft never loses protection.
2. **A saved zone takes effect immediately.** `ZoneController.upsert` re-judges the zones before it returns (fences close before the disk writes, `ZoneController.kt:156`). The sheet closes **only** on `ZoneChange.OK`. On any refusal it stays open with `ZoneWords.refusal(...)` and the map keeps showing the stored zone, not the draft. After OK the new zone's chip comes from the engine snapshot (`Not judged yet: counted as inside` until the first check). Without location permission the existing `requestPermission` flow runs.
3. **Edit = replace, guarded.** Moving or resizing an existing zone makes a draft copy with the same id. The stored zone stays in force until `upsert` returns OK. `BLOCKED_INSIDE` (inside, or not provably outside) refuses any change, larger or smaller, as today. Delete goes through the existing confirm dialog and `remove`, so the same refusal applies.
4. **No type change on an existing pause zone.** There is no "convert pause to seed", which would be a delete with a create. Instead the pause sheet offers **Also seed this area**, which creates a separate seed area and leaves the pause zone untouched.
5. **Unreadable state blocks.** When `snapshot.protectedMode` is true (`ZoneStore.corrupt`), the Pause choice is disabled with `ZoneWords.PROTECTED_BANNER`, existing pause zones are read-only, and the red banner is pinned at the top of the map. Seeding and downloading stay available, because they do not create capture (ruling R6). A corrupt seed-area or offline-area store never affects pause enforcement: they are separate stores (§5).
6. **The engine still never learns from the UI.** The screen only calls `ZoneEditor`. It never writes pauses or records.
7. The Jarawa preset stays, as a "Presets" row in the sheet list, with its UNVERIFIED label.

### 3.2 Seed areas

1. **Cell cap and estimate before you seed.** The seed sheet computes `Geohash5.cellsCovering(box)` live while you drag. It shows `N cells (5 km each) · about T · Wi-Fi only`, where **T ≈ N × 10 s**. That comes from the preset note "a dozen cells … in about 2 minutes" (`PlaceGuideSettings.kt:129-131`) and is UNVERIFIED: measure it on the first real seed and keep the constant in `SeedEstimate`. The bytes estimate is shown only after one measured seed (the data saver's `BULK_OTHER` counter gives bytes per cell). Until then the sheet says "data: not measured yet".
2. **Soft and hard caps.** Above `SEED_CONFIRM_CELLS = 100` (about 17 min) it asks "This is a large area (N cells, about T). Seed it?". Above `MAX_SEED_CELLS = 4 000` (about 11 hours at 10 s a cell) the Seed button is disabled with "Too large: draw a smaller area (max 4,000 cells)". Ruling R3: lower the hard cap to 400 in the UI.
3. **Same refusals as today.** `AndroidPlaceGuideControl.seed` keeps its checks: Offline only → `SeedOfflineOnlyException`, not free Wi-Fi → `SeedNeedsWifiException`. The sheet shows the same texts (`seedFailureText`) before starting where it can tell (Offline only, network class) and again if the control refuses.
4. **Per-cell status.** Progress uses `seedArea`'s `onProgress(done, total)`, which `PlaceGuideControl.seed` does not pass through today (add it). The result uses the existing `PlaceGuideModel.seedMessage`, including "N of M areas failed, will retry". After the seed, **Show cells** colours each covering cell from `CellEntity`: OK = blue, PARTIAL = amber, missing = red outline ("failed"). That is computed by a pure `SeedCoverage.of(box, cells)`. **Seed again** re-runs `seedArea` on the same id. Fresh cells are skipped, so only failed or partial ones are fetched.
5. **Overlap with a pause zone is allowed, with a note.** "Part of this area is a pause zone: place cards stay off there." Seeding sends only cell keys (R7).
6. **Delete a seed area** = `PlacePack.removeArea(id)`, which already exists and is unused. Its places become ordinary fetched places, evictable by the budget. The sheet says so.

## 4. Map source, offline and data rules

### 4.1 Source order (unchanged rule, one more layer)

`PmTilesReader` (the user's offline file) → **`OfflineAreaTiles`** (new, downloaded areas) → `OnlineTileDiskCache` (50 MB LRU) → network via `OnlineTileSource` (street map switch ON, screen in the foreground, not Offline only, `DataGate` allows, under the session cap) → the plain lat/lon grid (`drawGrid`).

- **Offline only (Model privacy) and no tiles cached**: the grid with lat/lon ticks, all shapes and the legend. There is a notice: "No map tiles: Offline only is on. Zones, seeds and pins work the same." Download is disabled with the reason. Drawing, saving pause zones and seeing seed areas all work on the grid. Seeding itself is refused under Offline only, as today.
- **Street map switch OFF**: downloaded areas are still drawn, because they were fetched with consent and drawing them sends nothing. Turning the switch off asks: "Also delete 2 downloaded map areas (31 MB)?" The default is Delete, consistent with PRIVACY §10, and there is a Keep option (ruling R5).
- `ZonesScreen.ZoneMap` is extracted into a reusable `MapCanvas` composable (`phone/.../ui/settings/mapzones/MapCanvas.kt`) that takes a `List<MapShape>`, the tile chain and gesture callbacks. The current tile and drawing code moves; it is not rewritten.

### 4.2 "Download this area for offline": the Google Maps flow

```
+------------------------------------------+
| Download a map of this area?         (x) |
|  ######################################  |   outside the rectangle dimmed (40 % black)
|  ###+------------------------------+###  |   the rectangle = the selected area's box, or the
|  ###|                              |###  |   viewport inset 10 % if nothing is selected;
|  ###|       (pan/zoom to adjust)   |###  |   handles to resize
|  ###+------------------------------+###  |
|  Detail:  ( ) Overview z8-12  (o) Streets z8-16                     |
|  1,380 tiles . about 34 MB (up to 70 MB) . about 6 min on Wi-Fi     |
|  [x] Wi-Fi only        Roaming: never                                |
|              [ Download ]                                           |
+------------------------------------------+
progress row in the sheet: "Gangtok map: 812 of 1,380 tiles . 20 MB . Pause / Cancel"
```

- **Tile count is exact**: `TileAreaMath.tiles(box, zMin..zMax)` sums `(x1-x0+1) * (y1-y0+1)` per zoom, using the same `xOf/yOf` as `TileMath.visible`. **Size** = count × the mean bytes of this provider's tiles already in `OnlineTileDiskCache` (fallback 25 KB, UNVERIFIED). "Up to" = count × the 95th percentile (fallback 50 KB). **Time** = count ÷ (4 parallel × measured tiles per second, fallback 4/s).
  Worked example (UNVERIFIED sizes): the Gangtok preset box (0.083° × 0.087°) at z8-16 is about 16 × 17 tiles at z16, about 380 tiles in total, about 10 MB.
- **Caps**: `MAX_AREA_TILES = 20 000` per area, `MAX_OFFLINE_AREA_BYTES = 300 MB` total (ruling R2), and zoom ≤ 16 for downloads (z17-19 only interactively). Over a cap, Download is disabled with "Too large: zoom in or pick Overview".
- **Network rules**: every request goes through the **same** `OnlineTileSource` fences: host allowlist, redirect check, image check, 512 KiB cap, backoff, `offlineOnly()` and `DataGate.decide(BULK_OTHER)` before each request, and bytes counted to `BULK_OTHER`. **Wi-Fi only** is the default. Unticked, mobile data is allowed only when `DataGate` allows bulk (home mobile, under the daily cap). **Roaming, unknown networks and Android Data Saver always block** (`BULK_OTHER` is never "sent once"). A download gets its own request budget (`MAX_AREA_TILES`) instead of the interactive `SESSION_CAP = 400`. The interactive cap stays for panning.
- **OSM is never bulk-downloaded.** The OSM tile usage policy forbids bulk prefetching, so `OnlineProvider.allowsBulk` is false for `osmDebug()` and the button is hidden in unkeyed debug builds. **MapTiler's terms on offline caching or bulk download must be checked before this ships (UNVERIFIED, ruling R1).** If they do not allow it, the download flow is dropped and the screen points to the PMTiles extract route (`docs/offline-maps.md`), which is already supported and licence-clean.
- **Foreground only (v1)**: the download runs only while the Map zones screen is resumed, the same rule as today's street map. It stops on pause and can be resumed, since tiles already stored are skipped. A WorkManager background download (unmetered constraint, notification) is ruling R8. It would change the PRIVACY §10 sentence "only while the Pause zones screen is in the foreground".
- **Storage**: `noBackupFilesDir/offline_tiles/<provider.cacheId>/z_x_y.tile`, with the same file format as `OnlineTileDiskCache` but **no LRU and no 7-day TTL** (expiry 30 days, then shown as "outdated, refresh on Wi-Fi"; the TTL is UNVERIFIED against the MapTiler terms). Tiles shared by two areas are stored once. **Delete an area** = delete the tiles of its box that no other area's box covers. That set is derived from the boxes (a pure function), so there is no manifest to go out of sync. The area list is `offline_tile_areas` prefs: a JSON list of `OfflineArea(id, name, box, zMin, zMax, tiles, bytes, status, createdAt, cacheId)`. If it is unreadable, it shows "Downloaded areas cannot be read" with a Delete all tiles button, and nothing is deleted silently.
- **Delete all** purges the downloaded tiles and the area list (`OnlineMapWipeListener` extended), consistent with PRIVACY §10 "tiles … deleted by Delete all". Tile numbers reveal where you looked (ruling R5).
- **Logs**: fixed event names only ("offline area download started/finished/cancelled count=N bytes=B"). The box and tile numbers are never logged.

### 4.3 How seeded place data relates to downloaded tiles

They are **separate and independent** jobs:

| | Seed area | Offline map area |
|---|---|---|
| What | place text (Wikipedia, Wikidata, OSM Overpass) for 5 km geohash cells | raster map images z/x/y |
| Sent | 5 km cell keys only | the tile numbers of the box (reveals the area to MapTiler) |
| Store | `places.db` `area` + `cell` + `place` rows (SQLCipher) | `noBackupFilesDir/offline_tiles` + `offline_tile_areas` prefs |
| Delete all | **kept** (seeded areas and their places stay) | **deleted** (tiles) |
| Makes work offline | place cards on the glasses | the map on this screen only |
| Data saver | free Wi-Fi only (`AndroidPlaceGuideControl`) | `BULK_OTHER`, Wi-Fi default, roaming never |

The sheet can run both from one box with **Prepare this area for offline**: two rows, two progress bars, two estimates, two refusals that can each fail on their own, and two deletes. The map draws both outlines. The legend counts them apart.

## 5. Data model

**Recommendation: keep separate stores and add one read-only facade. Do not create a shared zone store.**

- Pause zones stay in `ZoneStore` (`pause_zones` prefs), unchanged. Their fail-closed semantics (corrupt → protect everything, `write` refuses while unreadable, exported, never wiped) must not be shared with regenerable seed or tile state. In a shared store, a bad seed row would either pause capture everywhere (if corruption protects) or risk being filtered away (fail open). Neither is acceptable. **No migration of pause zones, so no data loss is possible.**
- Seed areas stay in `places.db` `area`. For names: either (a) **`places.db` v1 → v2**, an additive migration with nullable `name TEXT` and `presetId TEXT` columns (Room `AutoMigration(1, 2)`, exported schema, a `MigrationTestHelper` test), or (b) a `seed_area_names` prefs map `blueZoneId → name` with no schema change. Either way, existing rows resolve their display name as stored name → a box match against `TRIP_PRESETS` (`SeedPreset.boxes`, pure) → "Seeded area 27.28 N 88.55 E". Recommended: (a). Ruling R9. **`context.db` stays v17.** Only the v2 density layer needs v18, and that belongs to the location-per-Moment work.
- New seed ids from the map: keep the box-derived `seed:%.3f,…` id, so seeding the same box again updates the same row as today.
- Pins: unchanged (`UserPlace`). The map only reads them. Making a pin on the map from a tap is out of scope here (pins need a fresh fix today; see `trip-gap-analysis` D5).
- New UI-only types (`phone/.../ui/settings/mapzones/MapZonesModel.kt`):
  ```kotlin
  sealed interface MapShape { val id: String; val box: DoubleArray /* s,w,n,e */ }
  data class PauseShape(val zone: PauseZone, val chip: ZoneChip) : MapShape
  data class SeedShape(val id: String, val name: String, val box: DoubleArray, val status: String, val cells: Int, val failed: Int) : MapShape
  data class PinShape(val place: UserPlace) : MapShape
  data class TileAreaShape(val area: OfflineArea) : MapShape
  data class DraftShape(val kind: DraftKind /* RECT, CIRCLE */, val a: LatLon, val b: LatLon) // circle: a = centre, b = edge
  ```
- `MapZonesViewModel(zones: ZoneEditor, seeds: SeedAreas, pins: PlacePinning?, tiles: OfflineAreas?)` combines the four flows into `MapZonesUiState`. New small interfaces: `SeedAreas { suspend fun list(): List<SeedShape>; suspend fun seed(box, name, onProgress): Result<SeedOutcome>; suspend fun remove(id): Boolean; suspend fun coverage(id): SeedCoverage }`, implemented in `AndroidPlaceGuideControl` over `PlacePack.areas()/seedArea/removeArea/dao.cells()`. `OfflineAreas` is implemented by `OfflineAreaDownloader` + `OfflineAreaStore` in `zones/online/` (network code stays in that package; `MapNoNetworkTest` keeps guarding `tiles/` and `mapzones/`).
- `ZonesViewModel` is not deleted at first. `MapZonesViewModel` takes over its draft, save, delete and preset logic by delegation, and `ZonesViewModelTest` keeps passing against the old type until phase 1 removes it.

## 6. Map rendering and gestures: library choice

| Option | Licence / size | Fit | Privacy / network | Verdict |
|---|---|---|---|---|
| **Existing Compose `Canvas` + `MapViewport` + `TileMath` + `PmTilesReader` + `OnlineTileSource`** | ours, about 250 KB of classes (`tiles/README.md`) | raster tiles, shapes, handles and clusters are all simple `DrawScope` work; gestures with `pointerInput` | every byte goes through the fenced source; `MapNoNetworkTest` | **Chosen.** Extend it; add no dependency. |
| osmdroid (`org.osmdroid:osmdroid-android`) | Apache-2.0, about 1 MB AAR (UNVERIFIED); View-based, Compose only through `AndroidView` | good raster map and gestures, but our shape editing would still be custom overlays | its own tile downloader and cache (`/osmdroid` folder, default User-Agent) bypass the fences unless replaced with a custom `MapTileProviderBase`; project activity is low (UNVERIFIED) | No: it adds a second network and cache path to audit for little gain. |
| MapLibre Native (`org.maplibre.gl:android-sdk:11.8.0`) | BSD-2-Clause, 15.3 MB AAR with native libraries (measured 2026-11-18, `docs/offline-maps.md`) | vector styles, offline regions built in | its own OkHttp stack and offline-region downloader; needs a style; hard to prove "only this host" | No, as ruled on 2026-11-18. Reconsider only for vector styling (v1.4+). |
| Google Maps SDK (Play services) | proprietary | full | Google receives the viewport and the device; excluded | **No** (brief: no Google Play services map). `play-services-location` stays for fixes only. |

Gestures: one `pointerInput(Unit)` with `awaitEachGesture`. Hit-test the handles, then the shape body, then the map. Drag goes to resize, move or pan, and pinch to `MapViewport.zoomed`. Long press goes to a new rectangle and double tap to zoom in. The handle and shape maths is a pure `ShapeEdit` object (§7).

## 7. Accessibility and the phone-only constraint

- **TalkBack**: the map's `contentDescription` is the legend sentence ("Map with 2 pause zones, 4 seed areas, 17 pins; you are inside none"). Each shape is a `semantics` node with a role, a name, a type, a state and `customActions` (Open, Delete). In draw mode the draft exposes custom actions "Move north/south/east/west 100 m" and "Grow/Shrink 100 m". The **Edit coordinates** fields (today's S/N/W/E `TextFieldRow`s) stay in the sheet, so a zone can be made without any gesture. **Use 1 km around me** makes a box from a one-shot fix (`CentreFix`, not stored).
- Touch targets ≥ 48 dp. Colour is never the only cue (patterns, §2.4). Text in the sheet scales with the font size. The map keeps working at 200 % font scale because labels wrap (`drawLabel` with `shift`).
- **Phone only**: no HUD, glasses APK or wire changes. Pause zones keep their current shape (a box with a buffer), so the export JSON and the evaluator are unchanged. The glasses keep getting only `HudSettings.zoneApproach`. Nothing in this design is sent to the glasses.

## 8. Test plan

Pure JVM tests (no Android):
- `ShapeEditTest`: drag A→B in any order makes a normalised box. Below `MIN_SPAN_DEG` it is refused. Handle drags clamp so a handle never crosses its opposite. Move keeps the size. Lat is clamped to ±85.05 (Mercator). A box across the 180° line is refused, as `ZoneDrafts.parse` does today.
- `CircleBoxTest`: the circumscribed box contains 360 sampled circle points at the equator, 27° N (Sikkim) and 60° N. Radius → degrees uses cos(lat). A pause circle never saves a box smaller than the circle.
- `TileAreaMathTest`: the tile count matches brute force over `TileMath.visible` for random boxes and zooms. The deletion set keeps tiles shared with another area. The estimate uses the cache mean, else the fallback.
- `SeedEstimateTest` / `SeedCoverageTest`: the cell count equals `Geohash5.cellsCovering(box).size`. The thresholds 100 and 4 000 hold. Coverage gives OK, PARTIAL or missing from a `CellEntity` list.
- `MapClustersTest`: a cluster's colour is pause first. Chips below 16 dp merge. Results are stable as you pan.
- `MapLegendTest`: counts, and the pause layer cannot be hidden while a zone is INSIDE/APPROACH.

ViewModel tests (`MapZonesViewModelTest`, with fakes extending the existing `ZonesViewModelTest` fake `ZoneEditor`):
- Draw then Cancel makes **0** `upsert` calls. Draw, Pause and Save make exactly 1 call, and the sheet closes only on `OK`.
- `BLOCKED_INSIDE`, `PROTECTED`, `NOT_READY` and `STORE_FAILED` each keep the sheet open, show the refusal text and leave the stored zone drawn, not the draft.
- In protected mode Pause is disabled and Seed and Download stay enabled.
- A seed under Offline only or off Wi-Fi shows the existing texts. 101 cells asks for confirmation. 4 001 cells disables the button. Progress events become the per-cell UI.
- Delete seed calls `removeArea`. "Also seed this area" from the pause sheet leaves the pause zone unchanged.
- A recreated VM (process death) has no draft.

Network and fence tests (`OfflineAreaDownloaderTest`, fake `TileTransport` and `DataGate`): roaming, unknown and Data Saver block before the first request. Offline only switched on mid-download stops the next request. The OSM provider refuses bulk. The host and redirect checks are reused. `MAX_AREA_TILES` is enforced. `stop()` cancels requests in flight. Bytes are counted to `BULK_OTHER`. Tiles already stored are not requested again. Delete all purges them. `MapNoNetworkTest` is extended to `mapzones/`.

Store tests: `OfflineAreaStoreTest` (corrupt JSON gives an unreadable state, nothing is deleted). `PlacesDbMigrationTest` v1→v2 if R9(a) (rows and statuses kept). `ZoneStoreTest` is unchanged, which proves the pause store was not touched.

Compose tests (existing style, `ZonesScreenTest`/`ZonesDrawTest`): test tags `mapzones_map`, `mapzones_sheet`, `mapzones_type_pause`, `mapzones_type_seed`, `mapzones_legend_*`, `mapzones_download`. There is also a semantics check of the custom actions.

**Replayable fixture**: `phone/src/test/resources/mapzones/trip-areas.json` holds the Hyderabad and Ramoji boxes, the Gangtok/Darjeeling/West Sikkim presets, the Jarawa preset, a user pause zone, 30 pins, a seed area with 2 failed and 1 partial cell, and an offline area. Next to it go 6 generated 256 px PNG tiles and a scripted gesture log (`[longPress(x,y), drag(...), tap(type_seed), tap(next), tap(seed_now)]`). `MapZonesReplayTest` drives the VM and `ShapeEdit` with the script and compares the resulting `upsert`/`seed` calls and `MapZonesUiState` against a golden JSON. The same fixture feeds the v2 density layer later, through `2026-11-24-offline-trip-replay-harness.md`.

**Device-only checks** (needed before hand-off, per the device-test rule):
1. Pinch, pan, handles and long-press-draw on the real phone. No accidental pan while resizing.
2. Draw a pause box around where you stand and save it. The chip reads "Inside: capture is paused" within one fix and the glasses stop capturing. Edit and Delete are refused while inside. Walk out (or use a mock location in Developer mode) and it clears after two fixes.
3. Seed a 1-2 cell box on Wi-Fi: measure the time and bytes for the estimate constants. Mobile data is refused.
4. With a MapTiler key in the release build: tiles show and the attribution is visible. Download a small area on Wi-Fi, check its size against the estimate, then go airplane mode: the map still draws. Delete all removes it.
5. The Offline only setting shows the grid. The street map switch off asks about downloaded areas.
6. TalkBack pass over the map, a shape and draw mode. Check at 200 % font scale.
7. Roaming cannot be tested before the trip. Use Android Data Saver ON as the proxy for "costly blocks".

## 9. Phased plan

| Phase | Content | Effort | When |
|---|---|---|---|
| **P0 (optional, before Sikkim)** | On the existing Pause zones map: draw the **seed areas** (blue, from `PlacePack.areas()`) and **pins** (dots) read-only, with a counts line. After drawing a box, add a second button **Seed places in this box** that calls the existing `PlaceGuideControl.seed(box)` with the cell count shown first. Add Place Guide **Pick on the map**, which opens that screen. The pause save path is not touched. | **S** (about 1 agent-day + review + device test) | Only if the user rules yes (R10) **and** it can be reviewed and device-tested before the Sikkim install freeze (memory still says "install freeze 26 Nov" from the dropped Andaman plan; **confirm the new freeze date**). Sikkim already has presets (GANGTOK, DARJEELING, WEST_SIKKIM), so P0 is a convenience, not a blocker. If time is tight, skip it. |
| P1 | The **Map zones** screen proper: full-height `MapCanvas`, unified gestures and handles, rectangle and circle, type sheet (Pause/Seed), edit/rename/delete, seed estimate, caps and per-cell **Show cells**, `removeArea` in the UI, overview colours, patterns, clusters, legend and layer chips, accessibility actions, `places.db` v2 names (R9), tests and fixture | **M** (3-4 agent-days, one review pass) | After Sikkim (from about 7 Dec), before Sri Lanka (23 Dec), so ad-hoc Sri Lanka sites can be seeded on the map |
| P2 | **Download this area**: `TileAreaMath`, `OfflineAreaStore`, `OfflineAreaDownloader` (foreground only), the download sheet, Delete all hook, PRIVACY §10 and `docs/offline-maps.md` updates | **M** (about 3 agent-days) | After R1 (MapTiler terms). Before Sri Lanka only if R1 is clear; otherwise use a PMTiles extract for Sri Lanka |
| P3 | **My Moments density layer** with a date-range bar, behind the setting | **L** (depends on the location-per-Moment field and `context.db` v18) | After the location field ships; v2 of the screen |
| Later | Note type (text attached to an area); true circle pause zones (R4); background download (R8); vector styling (MapLibre re-evaluation) | - | backlog |

Order inside P1: pure `ShapeEdit`/`CircleBox`/`MapClusters` with tests → `MapCanvas` extraction (no behaviour change, existing `ZonesScreenTest` green) → `MapZonesViewModel` + fakes → sheets → overview → accessibility → device checks. It runs as parallel worktrees (geometry, VM, canvas) with one final review (Codex at medium effort; Opus low if the Codex quota is out).

## 10. Rulings needed from the user

| # | Question | Proposed answer |
|---|---|---|
| R1 | May the app bulk-download MapTiler tiles for offline use (MapTiler terms and free-plan quota, UNVERIFIED)? | Check the terms first. If not allowed, drop P2 and use PMTiles extracts. |
| R2 | Offline map caps: per-area tiles and total MB | 20 000 tiles per area, 300 MB in total, zoom ≤ 16 |
| R3 | Seed caps in the UI | confirm above 100 cells; lower the hard cap from 4 000 to 400 in the UI (`MAX_SEED_CELLS` in the pack unchanged) |
| R4 | Circle pause zones: saved as the circumscribed box (v1), or real circles later? | Box in v1; real circles only if asked |
| R5 | Downloaded map areas on Delete all and on street map switch off | Delete all: delete. Switch off: ask, default Delete |
| R6 | While pause zones are unreadable, allow seeding and downloading on this screen? | Yes (they do not create capture); pause editing stays blocked |
| R7 | Allow a seed area that overlaps a pause zone? | Yes, with the note |
| R8 | Background (WorkManager) download, or foreground-only? | Foreground-only in v1 |
| R9 | Seed area names: `places.db` v2 additive columns, or a prefs map? | `places.db` v2 (`context.db` stays v17) |
| R10 | Build P0 before Sikkim? And what is the real install freeze date? | Only if review and the device test fit before the freeze; else after Sikkim |
| R11 | Rename "Pause zones" to "Map zones" in Settings > Privacy? | Yes; keep the old route as an alias |

## 11. Proposed ROADMAP.md rows (text only; ROADMAP.md is not edited)

Under `## v1.x: stability, privacy, Place Guide, answer quality`, after the data saver row:

```
  - [ ] ❌ **1.4.7** Map zones screen (design `docs/superpowers/plans/2026-11-24-map-zones-screen-design.md`): one full-height map for pause zones, seed areas, pins and offline map areas; draw a rectangle or circle and mark it Pause or Seed; zoomed-out overview in colours with a legend and counts; seed cost estimate, caps and per-cell status; pause zones still fail closed (no half-saved zone, saved = in force, unreadable = paused); no new map library. P0 (seed areas and pins on the existing map, seed from a drawn box) optional before Sikkim; P1 after Sikkim; P2 "Download this area" needs the MapTiler terms ruling; P3 "My Moments" density layer needs the location-per-Moment field (Room v18).
```

Under `## v2.x canvas line` (or v3), one line:

```
- [ ] ❌ Map zones v2: "My Moments on the map" density layer with a date range (setting, default off; local only; recomputed on every visit so Forget applies at once); needs the location-per-Moment field.
```
