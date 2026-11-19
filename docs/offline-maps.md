# Offline map tiles (Settings > Privacy > Pause zones > Offline map)

The pause-zones map can draw raster tiles from a local `.pmtiles` file under the zone boxes, buffer rings and your position. With no file selected the plain grid is used.

## What the app does (and never does)

- Reads a PMTiles v3 archive with a small pure-Kotlin reader (`phone/.../ui/settings/privacy/zones/tiles/`). Supported: PNG, JPEG or WebP tiles; directory and tile compression none or gzip. Not supported (refused with a plain message, grid used instead): vector tiles (MVT), brotli, zstd, PMTiles v1/v2.
- **Choose a .pmtiles file**: the file is opened through the Storage Access Framework with a persisted read-only grant. No storage permission, no copy.
- **Import (copy) into the app**: the only way the file is copied. The copy lives in `files/offline_maps/` (app-private, excluded from cloud backup and device transfer), its size is shown, and "Delete the copy" removes it.
- **No network in the offline code**: no tile fetching, no API keys, no new permissions. `INTERNET` stays declared for other features; `MapNoNetworkTest` fails the build if the offline map code (`tiles/`, `ZonesScreen.kt`, `MapViewport.kt`) names a network class or URL scheme. The only network code of the map is the opt-in street map below, in its own package (`zones/online/`).
- The choice is stored in `offline_map.xml` (a document URI; excluded from backup because the grant does not survive a restore).
- Attribution: if the archive's metadata has an `attribution`, it is shown on the map. Keep it when you build an extract (OpenStreetMap-derived tiles need it).

## Online street map (optional, off by default)

Settings > Privacy > Pause zones > "Show a street map (downloads map tiles from MapTiler)" draws MapTiler raster tiles (`streets-v2`, 256 px) under the same canvas, so pan, zoom, Fit and drawing a box work as with an offline file. An offline `.pmtiles` file you select wins over it. With the switch off, no key, or no network, the grid shows as before. "Centre on me" (same screen) takes one location fix on tap and moves the map to a view about 500 m across.

- **Key (setup)**: put `PCA_MAP_TILES_KEY=<your MapTiler key>` in `android-companion/local.properties` (untracked, git-ignored) or export it as an environment variable before building. It is compiled into `BuildConfig.MAP_TILES_KEY` (default empty). **Never commit a key.** The key is embedded in the APK and is NOT secret: restrict it in the MapTiler dashboard (allowed origins/app) and expect it to be extractable. The key is exempt from the generic secret-value guard and is instead held to a stricter rule: Gradle accepts only letters, digits, `-` and `_` (up to 80 characters), which cannot hold `://`, `@`, `=` or `&`; `NoSecretsInBuildConfigTest` names this exact field (`MAP_TILES_KEY`) as the one reviewed exception, and every other secret-like name stays blocked.
- **Without a key**: a release build has no provider and shows the grid only (the switch is replaced by a note). A DEBUG build without a key falls back to `tile.openstreetmap.org` under the OSM tile usage policy (https://operations.osmfoundation.org/policies/tiles/): fixed identifying User-Agent, on-disk caching, light interactive use only, no prefetching. Do not ship that build.
- **Attribution**: "(c) MapTiler (c) OpenStreetMap contributors" and the name "MapTiler" are drawn on the map whenever its tiles show.
- **Limits**: HTTPS and one fixed host (a redirect off that host or to http is refused), 512 KiB per tile, only PNG/JPEG/WebP, zoom up to 19, 4 requests at a time, 400 per visit to the screen, backoff on 429/5xx, requests only while the screen is in the foreground (in-flight ones are cancelled on pause), cache at most 50 MB in the app cache folder (least recently used first), deleted when the switch goes off and by Delete all.
- **Privacy**: see `PRIVACY.md` sections 2 and 10. The first time you turn it on, a dialog states exactly what is sent; Cancel is the default and leaves it off.

## Why not MapLibre Native

Decision 2026-11-18: `org.maplibre.gl:android-sdk:11.8.0` is a 15.3 MB AAR (native renderer libraries for every ABI) and needs a style/source, while the zones map only needs flat raster tiles under a canvas it already draws. The pure-Kotlin reader adds about 250 KB of class files (no native code), is unit tested against generated archives, and keeps the "zero network calls" rule easy to assert. If vector styling is wanted later (v1.4+), MapLibre can be added behind the same `Choose` / `Import` setting; it was not fetched into the Gradle cache.

## Building an extract on the Mac (UNVERIFIED: commands from the Protomaps documentation, not run here)

The app needs a RASTER archive. Protomaps' own daily planet builds are vector, so use one of:

1. A raster PMTiles you already have (for example made from MBTiles with `pmtiles convert`).
2. Cut a region out of a hosted raster archive you are licensed to use:
   ```
   brew install pmtiles
   pmtiles extract <source-raster-archive>.pmtiles sri-lanka-sites.pmtiles --bbox=79.6,5.8,81.9,9.9 --maxzoom=16
   ```
   `--bbox` is `west,south,east,north`. For several small areas run one extract per area, or one bbox that covers them (size grows with area).
3. From MBTiles: `pmtiles convert region.mbtiles region.pmtiles`.

Check the result: `pmtiles show region.pmtiles` must report tile type `png`, `jpeg` or `webp` (not `mvt`) and compression `gzip` or `none`. Copy it to the phone (AirDrop-style transfer, USB, or cloud drive) and pick it with **Choose a .pmtiles file**.

Tile licences: do not bulk-download from `tile.openstreetmap.org` (it breaks the OSM tile policy). Use a source whose terms allow offline use, and keep its attribution in the archive metadata.

Suggested regions: see the trip how-to below. All bboxes in this file are rough and UNVERIFIED; check them on the map.

## Remaining trips: Sikkim/Darjeeling and Sri Lanka (UNVERIFIED: bboxes, sizes and commands are approximate, none were run or measured here)

Bbox order is `west,south,east,north` (longitude first).

| Trip | Approx bbox (UNVERIFIED) | Suggested zoom | Rough size (UNVERIFIED) |
| --- | --- | --- | --- |
| Sikkim / Darjeeling | `88.0,26.9,88.9,28.2` (about 88.0-88.9 E, 26.9-28.2 N) | z8-15 (z16 only for a few town boxes) | tens of MB at z8-14; z15 can add a few hundred MB over an area this size (mountainous, but the bbox is about 100 x 145 km) |
| Sri Lanka, whole island | `79.5,5.9,81.9,9.9` (about 79.5-81.9 E, 5.9-9.9 N) | z0-12 for the island | roughly 70 MB at z0-13 per the roadmap estimate below; not measured |
| Sri Lanka, site clusters | one small bbox per site cluster | z10-16 | roughly 55 MB for the cultural-triangle and south-coast sites per the roadmap estimate; not measured |

Guidance:
- Tile count grows about 4x per zoom level, so size does too. If a file is too big, lower `--maxzoom` by one before shrinking the bbox. The reader accepts zoom up to 26, but z17+ is rarely worth it for pause zones.
- A pause zone is a box plus a buffer ring, so z13-15 is enough to see which streets or compound a zone covers. Use a tight second extract at z16 only for the few places you really need.
- Do not build one huge file for both trips. Make one `.pmtiles` per trip and pick the right one in Settings before travelling; this keeps each file small and well inside the reader caps.
- Extract commands follow the recipe above, for example (replace the source):
  ```
  pmtiles extract <source-raster-archive>.pmtiles sikkim-darjeeling.pmtiles --bbox=88.0,26.9,88.9,28.2 --maxzoom=15
  pmtiles extract <source-raster-archive>.pmtiles sri-lanka.pmtiles --bbox=79.5,5.9,81.9,9.9 --maxzoom=12
  pmtiles show sikkim-darjeeling.pmtiles   # tile type must be png/jpeg/webp, compression gzip/none
  ```
- Roadmap size basis (not measured): 256 px raster tiles are about 20 KB each; Andaman-sized areas (about 400 km2) at z10-16 are about 1,450 tiles (about 30 MB); z17 adds about 85 MB.

### Loading it on the phone

1. Copy the file to the phone (USB, AirDrop-style transfer or cloud drive). Do it before the install freeze and before leaving connectivity.
2. Open Settings > Privacy > Pause zones, then the Offline map section.
3. Tap **Choose a .pmtiles file** (read-only grant, no copy) or **Import (copy) into the app** (app-private copy; shown size; "Delete the copy" removes it). Prefer Import for a trip so the file survives a cloud-drive or SD-card change.
4. Open the map: tiles should appear under the zone boxes and rings. If a plain message appears instead (vector, brotli, zstd, v1/v2, or a cap below), rebuild the file.

### What the map shows without tiles

With no file selected, or a refused/damaged file, the map falls back to the plain grid: your zone boxes, buffer rings and your position are still drawn, just without a street background. Individual tiles that are missing or fail to decode (for example outside the extract's bbox or zoom range) are left empty over the grid. Nothing is fetched from the network.

### Check locally (no legal claims made here)

- Sri Lanka: check locally what the photography rules are at the sites you plan to visit (temples, archaeological sites, military or government areas) before using the glasses camera.
- Sikkim/Darjeeling: check locally for border-area restrictions and any permit requirements near the border regions before the trip.

## Hostile-file limits (fail closed)

The archive is untrusted. Directories inflate to at most 4 MiB (counting stream, plus a 1000:1 ratio cap) and list at most 100k entries (count must also fit bytes/4; all columns are validated before any array is allocated). Header offsets and lengths must lie inside the file. Leaf recursion is at most 3 deep and a leaf that revisits itself is refused. A tile is at most 2 MiB compressed and 2 MiB inflated; it is decoded after a bounds-only read and subsampled to at most 16 megapixels. The leaf-directory cache and the decoded-tile cache are each bounded to 16 MiB; an out-of-memory during one tile decode leaves that tile absent (plain grid).

Quoted from `PmTiles.kt` (read 2026-11-18): `MAX_DIR_BYTES = 4L shl 20` (4 MiB), `MAX_ENTRIES = 100_000`, `DIR_MAX_RATIO = 1000`, `MAX_DEPTH = 3`, `MAX_TILE_BYTES = 2 shl 20` (2 MiB), `MAX_LEAF_CACHE_BYTES = 16L shl 20`, `MAX_ZOOM = 26`, metadata at most 1 MiB. There is no cap on total file size in these constants, but keep each trip file to a few hundred MB at most for the phone.
