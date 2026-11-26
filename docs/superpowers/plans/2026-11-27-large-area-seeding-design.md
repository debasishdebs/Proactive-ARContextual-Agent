# Large-area seeding: design (27 Nov 2026)

Status: DESIGN ONLY. Nothing here is built. Owner ruling 27 Nov: box size is not the fix, handling is. The goal is to seed a region as big as Rajasthan (about 13,700 cells of 5 km) and the 2,100-cell Sikkim + Darjeeling box without a storm of failures.

## 1. What the code does today (read from the code, 27 Nov)

- `PlacePack.seedArea` walks the box one 5 km cell at a time, in order, one cell at a time. It refuses boxes over `MAX_SEED_CELLS` = 4,000, so Rajasthan is refused today.
- Per cell, `PlaceCellSource.fetchCell` makes these calls, one after another:
  1. Wikipedia geosearch for the cell's box (`list=geosearch`, `gsbbox`, `gslimit=500`): 1 call.
  2. Wikipedia intro text, 3 sentences, 20 pages per call (`prop=extracts`): one call per 20 hits, fetched for EVERY hit, before the class filter.
  3. Wikidata classes (`wbgetentities`), 50 items per call: one call per 50 items.
  4. Overpass, up to 3 mirrors in turn, 25 s timeout each; after all three fail, Overpass is skipped for a fixed 3 minutes, then probed again.
  - An empty cell costs 2 calls (1 if Overpass is cooling down). A busy cell costs 4 or more. The laptop run used 109 calls for 25 city cells.
- Wikipedia or Wikidata failing makes the cell FAILED (nothing stored). Only Overpass failing makes it PARTIAL (places stored, `complete=false`).
- A new seed, or "Retry failed cells", fetches every PARTIAL cell again in full (all of Wikipedia and Wikidata too). From the owner's phone Overpass failed on every cell, so every cell is PARTIAL, the area always reads "FAILED: N cells did not seed", and every retry redoes the whole box.
- `PlaceNetGuard` pauses ALL place requests after any 429 or 503, from any server. An Overpass 503 (common, it means "busy") therefore also stops Wikipedia.
- Resume across runs already works: cells stored OK are skipped for 30 days (the cells table is the record, no schema change needed).
- The seed runs as a `dataSync` foreground worker, target SDK 35. Android 15 limits that kind of work to about 6 hours per day (platform rule, not checked on his phone), so a run longer than that must resume the next day.
- Coverage note: besides Wikipedia + monument-class places, the code also stores OSM-only named historic, heritage and tourism objects when Overpass answers. The offline-maps design says only the first kind is stored; that sentence is slightly wrong.

## 2. What the servers allow

Verified today with 5 single requests from the laptop (no app code involved):
- Wikipedia geosearch takes a box up to about 400 km2: a 20 x 20 km box answered (29 hits); 22 x 20 km and larger were refused with "Bounding box is too big". The radius form is capped at 10 km. At most 500 hits per call. A dense 11 x 11 km city centre box gave 72 hits, far below 500.
- Intro text: at most 20 pages per call.

From the code or docs, NOT checked today (assumptions):
- Wikidata `wbgetentities`: 50 items per call (the code uses 50).
- Geosearch has no "next page" when it hits 500 (none of the probes reached the cap).
- Wikimedia throttles clients with a generic User-Agent quickly; the app's agent string still has a placeholder contact. The 429 after about 6 cells fits this.
- Wikidata SPARQL (query.wikidata.org): 60 s per query, about 60 s of query time per minute per client, 429 with Retry-After when exceeded.
- Overpass main server: 2 queries at once per client, 429 when busy. The phone's failures were connection errors (SocketException) on all 3 mirrors, which points to the phone's network path, not to rate limiting.

## 3. The questions, answered

(1) Coarse-to-fine. Geosearch is not limited to 10 km in box mode: a box of 16 cells (4 x 4, about 20 x 20 km, under 400 km2 at every latitude) is accepted. So the simplest form is: one geosearch per 16-cell block, then sort the hits into their cells on the phone. Empty blocks cost one call for 16 cells; busy blocks no longer pay one geosearch per cell. If a block returns the full 500, split it into four 2 x 2 blocks, and if one of those is full, fall back to per cell (as today). The intro and Wikidata calls are also batched per block (20 and 50 per call), so a block with 15 hits costs about 3 Wikimedia calls instead of about 30. Cells outside the drawn box are dropped, not stored. Only the block's box is sent, which is coarser than a cell: no privacy loss.

Larger tiles for the coarse pass? Not with Wikipedia: the 400 km2 box limit (verified) and the 10 km radius limit (verified) make the 16-cell block the largest tile geosearch accepts. Larger tiles are possible only with the other two servers: Wikidata SPARQL takes a box of any size, bounded by its 60 s query time (assumed, to be measured), and Overpass takes any box, bounded by the query's own timeout and size limit. So: 16-cell blocks for Wikipedia; bigger tiles only on the SPARQL path below.

(2) Bulk Wikidata (SPARQL). One query per large tile can return, for every item in the box with an English Wikipedia article and an admitted class, its id, class, parent, coordinate, article title and its count of language editions (a cheap importance score). It cannot return the intro text: that still needs the Wikipedia intro call, 20 pages per call, but only for the places that qualify. Costs: it is a second discovery path (it finds places by Wikidata's coordinate, not Wikipedia's; the curated list, the extra-coordinate table and the 1 km coordinate check must be redone on it), the endpoint is often overloaded, and its limits are unmeasured here. For Rajasthan the per-block geosearch is probably enough. For a whole country it is the only workable discovery path (section 3b). Measure it on the Mac first; never use it for the glance path.

(3) Overpass. For seeding, a cell where Wikipedia and Wikidata answered should count as done, even without Overpass: the owner's ruling is that seed data is the Wikipedia + monument-class place with its 3-sentence intro, and OSM only adds a bigger radius and extra unnamed-in-Wikipedia sites. Keep storing such cells as PARTIAL (no schema change), but:
- a seed or retry does not fetch a PARTIAL cell again while it is younger than 30 days (same as OK);
- the area ends Done, with a separate line "N cells without OSM extras" instead of "FAILED";
- "Retry failed cells" retries only cells that stored nothing;
- OSM is filled in where it matters: at a glance on free Wi-Fi the existing rule already refetches a partial cell (5 min up to 1 h backoff; kept as is on mobile data);
- later, one Overpass query per 16-cell block instead of per cell, and an "OSM fill-in" pass that asks only Overpass for partial cells at the end of a seed, if a probe answers.
- When Overpass keeps failing, its cool-down should grow (3, 10, 30 minutes, reset on success) instead of re-probing all 3 mirrors every 3 minutes (up to 75 s of timeouts each time).

(4) Adaptive pacing. Two small changes first: pause per server group (Wikimedia separately from Overpass), and a real User-Agent contact. Full AIMD (gap doubles after a 429, shrinks by a small step per success, floor 400 ms, ceiling about 5 s) is a later, small add-on, only if the logs still show repeated 429s after blocks land: blocks cut the call count so much that the steady 400 ms gap plus the pause is likely enough.

(5) Budget, resume, warning. No time budget by default: the seed runs in the background on the Wi-Fi rule until done, stops when Android or the network stops it, and resumes on its own (cells already stored are skipped). A system stop or a network wait must not use up the 8 worker attempts (check how `runAttemptCount` counts a system stop; not verified). Before starting, show the estimate, and above a size threshold ask "This will take about N hours and continues in the background on Wi-Fi. Start?". Keep the 4,000-cell cap until blocks land, then raise it (see decisions).

(6) Priority. The order never depends on where the phone is: the owner may seed Sikkim from Hyderabad, or the USA before flying.
- Default, importance first: scan every block with geosearch only (Rajasthan: about 860 calls, 15 to 25 minutes), then fetch details for the blocks with the most articles first. On the SPARQL path, rank by the qualified places per tile and their language-edition counts instead. Article density follows towns and tourist sites, so the useful places land first, and empty blocks are done after the scan.
- Optional "around these places first": the owner names anchors (towns from an itinerary, or chosen pins). Blocks are ordered by distance to the nearest anchor, then by importance. No radius, no user position. A typed town name is resolved once to its Wikipedia article's coordinate; a pin already has one. The work data carries pin ids or names, never coordinates.
- Scan counts and anchor positions stay in memory for the run (a resumed run scans again; it is cheap). Never stored or logged.
- Not in the first slice.

(7) Time estimates (ranges, because hit density per block outside cities is an assumption, about 15):

| Area | Today (per cell) | Recommended (16-cell blocks) |
|---|---|---|
| Sikkim + Darjeeling, 2,100 cells (131 blocks) | 1 to 2.5 h on good Wi-Fi with Overpass down (most mountain cells are near empty, about 1 s; town cells 3 to 9 s); up to about 4 h at the laptop mean of 7.4 s | 15 to 35 min |
| Rajasthan, 13,700 cells (about 860 blocks) | refused (cap 4,000); if allowed, 8 to 28 h, i.e. 2 to 5 days under the 6 h daily limit | 1 to 2 h with Overpass down, 2 to 4 h with Overpass up |

Add up to half again for 429 pauses. A single rectangle drawn around Rajasthan is about twice the state's area (about 27,000 cells, half outside the state), so double those figures for one rectangle. All "recommended" figures are estimates until the Mac measurement (section 4, step 1) reports real hit rates.

## 3b. Country scale (USA, about 9.8 million km2, about 390,000 cells)

- Per cell is impossible: 390,000 cells at the measured 3 s median is about two weeks of nonstop fetching, and two months under the 6 h daily limit.
- 16-cell blocks are still too many: about 24,000 geosearch calls for the scan alone (8 to 12 h). US cities will also hit the 500-hit cap often, so blocks split further. Unmeasured.
- Bulk Wikidata regional paging is the only workable discovery: tiles sized so that each query finishes well inside 60 s (start at about 100 km and halve a tile that times out or returns too many rows), then the intro text for the qualified places only, 20 per call. The number of calls is then about tiles + places / 20, so the place count decides the time. That count is UNMEASURED. Note also that many US historic buildings are classed in Wikidata as "house" or "building" with a heritage listing, which the P31-only class rule does not admit; the measurement should report how many.
- Staged seeding: stage 1, major cities and national parks (picked from a short list, or the anchors above); stage 2, states or corridors the owner picks before a trip. No "whole country" button.
- Time and size budget: before starting, show the estimated places, megabytes and hours; ask above the warning threshold; run in the background on Wi-Fi and resume across days.
- Store size per N places: from the code, a place's counted text is capped (name 160 characters, intro 400 characters, plus ids and an 80-byte allowance per row), so the counted size is at most roughly 0.7 KB per place, and about 0.2 KB for a place with no intro. The real on-disk size of the encrypted database with its indexes is UNMEASURED. Seeded places are never evicted and are not limited by the 50 MB budget, so a country-sized seed needs its own size check.
- Option only, not designed: a downloadable prebuilt region pack (built on a laptop from SPARQL or dumps, one signed file per region). It would make country scale minutes instead of days, but raises hosting, updates, file integrity and licence questions (Wikipedia text needs attribution and share-alike; OSM data is under the ODbL).

## 4. Do this first / later / never

First:
1. MAC-ONLY measurement script (extend `tools/measure/measure_seed_cells.py` or a sibling; no phone, no Gradle, nothing shipped). Serial requests, at most one every 2 s, the proper User-Agent with the repository contact, Retry-After honoured, stop after 3 429s in a row. The report prints numbers only, never coordinates or names. It measures:
   - coarse blocks over a Rajasthan sample (about 60 random 16-cell blocks inside the state): hits per block, share of empty blocks, share at the 500 cap, call times, and how many hits pass the class filter;
   - bulk Wikidata for a large box (a 100 km tile, then one twice as big): query time, rows, bytes, errors or 429s, and the difference against geosearch plus the class filter on the same blocks (missed and extra places, and for one US sample tile the "house or building with a heritage listing" count);
   - the intro text for those results by batched Wikipedia calls: calls, time, bytes;
   - Overpass per block from the Mac: time and size (the phone's network path may still differ).
2. Then the small phone slice (before 1 Dec only if the owner wants a build; no schema change, no new endpoint):
   - real User-Agent contact (the public repository address), one constant;
   - pause per server group: an Overpass 429/503 no longer stops Wikipedia and Wikidata, and the reverse;
   - growing Overpass cool-down (3, 10, 30 min, reset on success);
   - PARTIAL counts as done for seeding: not refetched for 30 days by a seed or retry, area ends Done with an "N cells without OSM extras" line, "Retry failed cells" retries only failed cells.

Later (after the trip, in this order, adjusted by the measurement):
3. 16-cell block fetch with the 500-hit split; equivalence test against per-cell fetch. Then raise the cell cap, add the size warning, and update `SeedEstimate` to blocks.
4. Overpass per block and the OSM fill-in pass.
5. Importance-first order and the optional anchors.
6. AIMD pacing, only if 429s persist.
7. SPARQL discovery for country scale with staged seeding, only if the measurement shows it works and the class gap is acceptable.

Never:
- Parallel requests to Wikimedia or Overpass.
- Ignoring Retry-After, or boxes over the 400 km2 geosearch limit.
- Downloading Wikidata or OSM dumps on the phone.
- SPARQL in the glance path.

## 5. Progress display: exact data

Keep the `SeedTally` rules (one definition for screen, notification and logs). Counts only, never a cell or a place.
- `total`: cells in the box.
- `processed`: cells with an outcome (ok, partial, already fresh, failed). Overall % = processed / total.
- `ok`: stored with Wikipedia + Wikidata, including already-fresh and empty cells. `partial`: the subset of ok without OSM extras (shown as a count, never added on top).
- `failed`: stored nothing after the tries. OK % and Failed % are shares of processed and add up to 100.
- `waiting`: none, network, or server pause, with the remaining pause seconds.
- `etaMin`: remaining cells times this phone's recent seconds per cell (from `SeedTimingStore`; with blocks, per block divided by 16).
- With blocks, cells finish 16 at a time, so the bar moves in small steps (0.1% per block for Rajasthan): fine.

## 6. Test plan (fake HTTP, fake clock, no network)

A fake `PlaceHttp` scripted per call kind (geosearch, intro, Wikidata, Overpass):
- 429 with and without Retry-After, 503, timeout, connection error: the pause applies only to that server group; cells end FAILED after 5 tries; a later success moves failed to ok.
- Overpass always failing: every cell PARTIAL, counted ok; area Done with the partial line; a second seed and "Retry failed cells" make zero Wikipedia calls for those cells; the cool-down grows 3, 10, 30 min and resets on success.
- Blocks (later slice): an empty block costs 1 geosearch and stores 16 empty cells; a 500-hit block splits into four; a block half outside the box stores only the inside cells; the places stored by a block fetch equal those of 16 per-cell fetches on the same fixtures.
- Resume: cancel mid-seed, re-run, stored cells are not fetched again; a system stop does not use up worker attempts.
- Progress: processed never goes down; ok + failed = processed; partial never exceeds ok; percentages match `SeedTally`.
- Unchanged guards: admission and zone fence checked before every request; logs pass `LogContentScanTest` (no position, cell or name).

## 7. Decisions the owner must make

1. Treat "Wikipedia done, OSM missing" as done for seeding (recommended: yes).
2. Size warning threshold (recommended: confirm above 2,000 cells, the size of his trip box).
3. Cell cap once blocks land (recommended: 30,000, enough for one rectangle around Rajasthan); keep 4,000 until then.
4. Time budget default (recommended: none; run on Wi-Fi until done and resume). Alternative: only while charging above the threshold.
5. OK to put the public repository address in the User-Agent.
6. Should seeds keep the OSM-only sites (no Wikipedia article) when Overpass answers? Today they are stored; the seed-data ruling does not mention them.
7. Country scale: staged seeding only (no whole-country button), and whether a prebuilt region pack is worth designing later.
8. Whether to build the small phone slice before 1 Dec at all (it needs its own install and device test), or only the Mac measurement.
