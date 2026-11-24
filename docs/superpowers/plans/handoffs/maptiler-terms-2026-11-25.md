# MapTiler terms and offline tile options (researched 2026-11-25)

Sources fetched live 2026-11-25. Quotes are as returned by page fetches (summarised extraction, not byte-verified; re-read the pages before relying on them legally).

| Question | Answer | Quoted clause | URL |
|---|---|---|---|
| (a) Online tiles, personal non-commercial, free plan | Allowed | "Suitable for testing, PoC, prototyping, personal, or non-commercial use." / "Usage of the Free Plan is limited to non-commercial use and research & development for commercial products applications." | maptiler.com/cloud/pricing/ ; maptiler.com/terms/cloud/ |
| (b) Device cache (50 MB LRU / 7 d) | Allowed (personal cache, single user) | "Results of requests can be stored in a temporary personal cache (browser cache, mobile app cache, etc.) for use by a single end-user only." No max duration or size stated. | maptiler.com/terms/cloud/ |
| (c) Bulk/area download for offline, free plan | NOT allowed on Cloud tile API without written agreement | "Unless otherwise agreed in writing with MapTiler, it is prohibited to batch or excessive bulk download of map tiles" | maptiler.com/terms/cloud/ |
| (c) Paid plans | Not stated. The pricing page has no offline row. The MapTiler Mobile SDK has an Offline Pack API (region/route/polygon, "default is 30 days" expiry) but its docs state no plan requirement. Offline is otherwise sold as self-hosted MapTiler Data/Server. | (no plan clause found) | docs.maptiler.com/mobile-sdk/android/examples/offline-get-started/ ; maptiler.com/data/pricing/ |
| (d) Free quotas | "5k/month" map sessions, "1k/month" search sessions, "2k/month" 3D sessions, "100k/month" API requests. On exceeding: "service will pause until the next month without an upgrade to paid plans." Terms: "Usage of the Service beyond these limits may result in temporary suspension of the Service for your account." No tile-count quota stated. | as quoted | maptiler.com/cloud/pricing/ ; maptiler.com/terms/cloud/ |
| (e) Attribution | "MapTiler attribution is required to be shown on screen while the map is displayed, unless otherwise agreed in writing." General terms: "add '© MapTiler' (with Free Account the MapTiler logo)" and, for OSM-based data, "'© OpenStreetMap.'" So show: MapTiler logo + "© MapTiler © OpenStreetMap contributors". | as quoted | maptiler.com/terms/cloud/ ; maptiler.com/terms/ |
| (f) Commercial vs non-commercial | Not defined in the fetched terms ("not stated"). The pricing page lists personal use as suited to Free and "Commercial use" as a Flex/Custom feature. A private hobby app on your own phone fits the plain reading. | "Suitable for testing, PoC, prototyping, personal, or non-commercial use." | maptiler.com/cloud/pricing/ |
| Other | Use only your own key ("You must only use your own API keys."). No multi-account quota gaming ("Attempts to unduly gain additional usage ... is considered as breach"). Server-side caching/redistribution prohibited. The unrestricted key is not a terms issue, but restrict it by package/SHA. | as quoted | maptiler.com/terms/cloud/ ; maptiler.com/terms/ |

## Verdict: offline download on the free plan
NOT ALLOWED for bulk or area pre-fetch from the Cloud tile API (explicit prohibition, only lifted by written agreement). Whether the paid-plan Mobile SDK Offline Pack API is allowed on Free is UNCLEAR (no plan stated); ask support@maptiler.com before using it.

## Alternatives for offline personal use
| Option | Rules (quoted) | URL |
|---|---|---|
| Protomaps PMTiles (OSM-derived) | ODbL Produced Work, OSM attribution required. Regional cutouts via `pmtiles extract`. Do not hotlink: "copy tilesets to their own cloud storage" (planet is ~120 GB). Fine to bundle or sideload a city extract on-device. | docs.protomaps.com/basemaps/downloads |
| Self-built PMTiles/MBTiles from Geofabrik/OSM extracts (Planetiler + OpenMapTiles/Protomaps schema) | OSM data is ODbL: attribute "© OpenStreetMap contributors". Verify Geofabrik and OpenMapTiles code/design terms (not fetched, so not stated here). | openstreetmap.org/copyright |
| OpenFreeMap (online, with user-initiated offline) | ToS allows offline saving: "at most 3,000 tiles and at most 50 MB" per download, "at most 4 parallel requests", unique User-Agent, larger areas as separate user-started downloads. ToS states no attribution rule, but OSM/OpenMapTiles attribution is still advisable. | openfreemap.org/tos/ |
| NOT allowed: tile.openstreetmap.org | "Download city/country for offline use" or "Save area for later" features are prohibited. "Prefetch/offline patterns ... will be blocked without notice." Caching: honour headers or at least 7 days. Attribution "© OpenStreetMap contributors". | operations.osmfoundation.org/policies/tiles/ |

## Recommendation: what we may build now
1. Keep online MapTiler tiles plus the 50 MB / 7 d personal cache (explicitly permitted). Add the MapTiler logo and "© MapTiler © OpenStreetMap contributors" on screen. Add an on-screen note and stop fetching when a 403/429 quota-pause response arrives (5k map sessions/month is ample).
2. Do NOT build a bulk or area "download for offline" feature against MapTiler Cloud tiles on Free. Cache only tiles the user actually viewed.
3. If offline is wanted: bundle or sideload a small PMTiles extract of the seed-area city (Protomaps build, ODbL attribution), or use OpenFreeMap's per-session limits. Both are separate from MapTiler.
4. Optional: email support@maptiler.com for written permission or Offline Pack on Free. Also restrict the API key to the app's package and signing SHA.
