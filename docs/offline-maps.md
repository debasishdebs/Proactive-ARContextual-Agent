# Offline map tiles (Settings > Privacy > Pause zones > Offline map)

The pause-zones map can draw raster tiles from a local `.pmtiles` file under the zone boxes, buffer rings and your position. With no file selected the plain grid is used.

## What the app does (and never does)

- Reads a PMTiles v3 archive with a small pure-Kotlin reader (`phone/.../ui/settings/privacy/zones/tiles/`). Supported: PNG, JPEG or WebP tiles; directory and tile compression none or gzip. Not supported (refused with a plain message, grid used instead): vector tiles (MVT), brotli, zstd, PMTiles v1/v2.
- **Choose a .pmtiles file**: the file is opened through the Storage Access Framework with a persisted read-only grant. No storage permission, no copy.
- **Import (copy) into the app**: the only way the file is copied. The copy lives in `files/offline_maps/` (app-private, excluded from cloud backup and device transfer), its size is shown, and "Delete the copy" removes it.
- **No network, ever**: no tile fetching, no API keys, no new permissions. `INTERNET` stays declared for other features; `MapNoNetworkTest` fails the build if the map code names a network class or URL scheme.
- The choice is stored in `offline_map.xml` (a document URI; excluded from backup because the grant does not survive a restore).
- Attribution: if the archive's metadata has an `attribution`, it is shown on the map. Keep it when you build an extract (OpenStreetMap-derived tiles need it).

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

Suggested regions (bboxes are rough and UNVERIFIED; check them on the map): Hyderabad `78.2,17.2,78.7,17.6`; Sikkim and Darjeeling `88.0,26.9,89.0,28.2`; Sri Lanka sites per the Place Guide spec.

## Size guidance (estimates from the roadmap spec, not measured)

256 px raster tiles are about 20 KB each. Andaman-sized areas (about 400 km2) at z10-16 are about 1,450 tiles (about 30 MB); z17 adds about 85 MB. Sri Lanka's cultural-triangle and south-coast sites (about 750 km2) at z10-16 are about 55 MB, plus the whole island at z0-13 for about 70 MB. Prefer maxzoom 16 unless you need street detail.

## Hostile-file limits (fail closed)

The archive is untrusted. Directories inflate to at most 4 MiB (counting stream, plus a 1000:1 ratio cap) and list at most 100k entries (count must also fit bytes/4; all columns are validated before any array is allocated). Header offsets and lengths must lie inside the file. Leaf recursion is at most 3 deep and a leaf that revisits itself is refused. A tile is at most 2 MiB compressed and 2 MiB inflated; it is decoded after a bounds-only read and subsampled to at most 16 megapixels. The leaf-directory cache and the decoded-tile cache are each bounded to 16 MiB; an out-of-memory during one tile decode leaves that tile absent (plain grid).
