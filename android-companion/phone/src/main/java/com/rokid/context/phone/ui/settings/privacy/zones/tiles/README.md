# Offline map tiles (pure Kotlin PMTiles reader)

No third-party map library. MapLibre Native (`org.maplibre.gl:android-sdk`, BSD-2-Clause) was evaluated and not adopted: version 11.8.0 is a 15.25 MB AAR with native libraries, against about 250 KB of class files here. Nothing was added to Gradle, so builds stay offline.

- `PmTiles.kt`: PMTiles v3 header, Hilbert tile id, varint directories, gzip/none, leaf directories, raster tile types only.
- `TileMath.kt`: zoom choice, visible tiles and screen rectangles over the canvas's Web Mercator (`MapViewport.ppd`), plus the decoded-tile LRU.
- `OfflineMap.kt`: the saved choice (`offline_map` prefs), link/import/remove logic and its state.
- `AndroidMapFiles.kt`: Storage Access Framework access (read-only grant, no storage permission).

If MapLibre is wanted later: add it in `phone/build.gradle.kts` with a pinned version, do ONE online `./gradlew --refresh-dependencies :phone:dependencies` to fill the cache, then build with `--offline` as usual. Re-check the APK size and min SDK 29 first.

How to build a `.pmtiles` extract and the size guidance: `docs/offline-maps.md`. The map code makes no network calls (`MapNoNetworkTest`).

Online street map (opt-in): `OnlineMap.kt` holds the source interface (`RasterTileSource`), the switch state machine and its prefs. The network code is in the separate package `zones/online/` (`OnlineTileSource`, disk cache, wiring); this package stays network-free.
