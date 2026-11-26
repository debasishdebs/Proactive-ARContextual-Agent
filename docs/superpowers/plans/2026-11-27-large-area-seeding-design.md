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

## 8. Measured 27 Nov (Mac, `tools/measure/measure_blocks.py`; aggregates only)

Method: serial, one request every 2 s, descriptive User-Agent with the repository address, Retry-After honoured, stop on two 429s in a row, hard cap 400. About 195 requests in total over three runs; every Wikimedia/Wikidata answer was HTTP 200, no 429 at any point. A first run used 20.0 km boxes and was refused by the API as too big (areas just over 400 km2 after rounding), so the final sample uses 19.8 x 19.8 km blocks (this refines the "20 x 20 km" claim in section 2: 20.0 km exactly is over the limit).

1. Geosearch over 60 blocks of 19.8 x 19.8 km, deterministic grid sample across a desert/semi-arid state (no failures):
   - 35% of blocks empty (0 hits); 0% at the 500 cap; non-empty blocks: median 2, mean 3.5, p90 7 hits; mean over all blocks 2.3 hits.
   - Median 620 ms per call (p90 700 ms).
   - Consequence: the planning figure of about 15 hits per block (sections 3(7)) was far too high for this kind of area; the real figure is about 2 per block.
2. Wikidata SPARQL (app rule: P31 in the 47 admitted classes or a curated id, plus an English Wikipedia article; no person check, no subclass walk):
   - 100 km box: 1.4 s, 5 rows, 1.3 KB. 200 km box: 9.2 s, 19 rows, 4.7 KB. 300 km box (run for the intro sample): 10.4 s, 72 rows, 17 KB. No timeout, no 429, no row limit reached (all inside the 60 s limit; the time grows faster than the area but is far from the limit at 200 to 300 km).
   - Same 100 km box through geosearch: 25 blocks (25 calls, plus 12 calls to read the classes of 259 hits) found 259 articles, of which 4 pass the class rule (1.5%). Overlap: 4 in both, 1 SPARQL only, 0 geosearch only. So SPARQL missed nothing the app's path finds; it found one more (a place whose Wikidata coordinate is inside the box, but whose article coordinate is not or is missing). The "house or building with heritage listing" count for a US tile was not measured (no US sample was run).
3. Intro text, 2 calls of 20 titles (qualifying titles, `exsentences=3`): median 1,260 ms per call, median 289 characters per intro (mean 305; the app caps it at 400).
4. Overpass, one try per mirror, one block-sized query each, no retry: first mirror HTTP 502 after 0.6 s; the other two gave no answer within the 30 s client timeout. All three failed, as expected, from this Mac too, so the failure is not only the phone's network path.
5. Implied cost to seed (calls = geosearch + intro + class calls; time = calls x (measured call time + the app's 0.4 s gap), Overpass down; the class call is taken as 620 ms, not measured separately; hit counts from the sample above, so they are Rajasthan-like; a denser mountain area will cost more):

| Area | Per-cell design (today) | 16-cell block design |
|---|---|---|
| Rajasthan, 13,700 cells, about 860 blocks | about 17,600 calls (13,700 geosearch + up to 3,900 for the at most 1,950 non-empty cells), about 5.3 h; with Overpass up add 13,700 calls (about 3.5 h more) | about 2,000 calls (860 geosearch + 2 per each of about 560 non-empty blocks), about 40 min |
| Sikkim + Darjeeling, 2,100 cells, 131 blocks | about 2,700 calls, about 50 min | about 300 calls, about 6 min (if every block were non-empty: about 390 calls, 8 min) |

Block design is about 9 times fewer calls and 8 times less time for Rajasthan. Not a 429 risk in this sample at 1 request per 2 s; the app's 0.4 s gap was not tested against throttling here.

What this changes in the design: the 500-hit split is rarely needed in sparse areas (none of 60 blocks, none of 25 more); the saving comes from empty and near-empty blocks, which are the large majority. SPARQL is fast and complete enough at 100 to 300 km for a state, but gives only about 5 to 70 places per tile in this area, so for a state-sized area it would replace about 25 geosearch calls with one; it still needs the intro call. The estimate of time in section 3(7) for Rajasthan (1 to 2 h) was pessimistic: about 40 min is the measured-rate figure.

## 9. Sikkim coverage 27 Nov (Mac, `tools/measure/measure_region.py`; counts and landmark yes/no only)

Method as section 8 (serial, 1 request per 2 s, the app's User-Agent, no 429, every answer HTTP 200). Box: the trip region, 27.0 to 28.2 N, 87.9 to 89.1 E (about 133 x 118 km), cut into 4 sub-boxes of about 67 x 59 km. The query shape that works is `SERVICE wikibase:box`, then the English-Wikipedia sitelink join, then `OPTIONAL { ?item wdt:P31 ?cls }`, `ORDER BY ?item`, no sub-select (a first draft with a sub-select timed out at 65 s with HTTP 504 on all four sub-boxes; the fixed shape needs 0.9 to 4.1 s).

Counts (items with an English Wikipedia article and a Wikidata coordinate inside the box):

| Measure | Count |
|---|---|
| Items with an article | 532 (9 have no P31 at all) |
| Pass the APP rule (P31 in the 47 admitted classes, or one of the 19 curated ids, and no person class) | 53 (10%) |
| Pass a LOOSER rule (app rule plus 26 extra class ids: monastery/gompa, viewpoint, lake, peak/summit/mountain, protected area, national park, wildlife refuge, hill station, bridge, museum, park, waterfall, mountain pass) | 142 (27%) |

Landmarks (matched by article title in the SPARQL result; "app" = would the app's class rule admit it; the 1 km column is the app's Wikipedia-versus-Wikidata coordinate check, section 9.2):

| Landmark | In SPARQL result | Passes app rule | Passes looser rule |
|---|---|---|---|
| Rumtek Monastery | yes | yes | yes |
| Pemayangtse Monastery | yes | yes | yes |
| Tsomgo (Changu) Lake | yes | yes | yes |
| Nathu La | yes | no | yes (mountain pass) |
| Tiger Hill (Darjeeling) | no (its Wikidata point is 27.0 N minus 0.005 degrees, just outside the box) | yes (curated id) | yes |
| Batasia Loop | yes | yes (curated) | yes |
| Himalayan Mountaineering Institute | yes | no (class school) | no |
| Kangchenjunga (mountain, South summit, conservation area) | yes | no | yes |
| Namchi Char Dham | no (no article found under the names tried) | no | no |
| Ravangla Buddha Park | yes | no (colossal statue + park) | yes |
| Yuksom | yes | no (human settlement) | no |
| Kalimpong Durpin Dara | no (article "Durpin Hill" has no Wikipedia coordinate) | no | no |
| Enchey Monastery | yes | yes | yes |
| Ghum Monastery | yes | yes | yes |
| Darjeeling Peace Pagoda | yes | yes | yes |
| Khecheopalri Lake | yes | yes (curated) but dropped by the 1 km rule | yes, same drop |
| Rabdentse ruins | yes | yes | yes |
| Do-drul Chorten | yes | yes (curated) but dropped by the 1 km rule | yes, same drop |
| Banjhakri Falls | yes | yes (curated) | yes |
| Padmaja Naidu Himalayan Zoological Park | yes | no (zoo) | no |
| Pelling | yes | no (human settlement) | no |
| Gangtok | yes | no (city) | no |

Of 22 landmarks listed: 19 are in the SPARQL result, 1 (Tiger Hill) is just outside the box edge, 2 are not found (Namchi Char Dham, Durpin Dara). 12 pass the app rule (10 after the 1 km rule, which drops Khecheopalri Lake and Do-drul Chorten); the looser rule adds Nathu La, Kangchenjunga and Ravangla Buddha Park. The remaining misses are settlements, a school and a zoo, which no class list should admit.

### 9.1 What the 89 extra items are
Looser rule adds 89 items: mountain 27, tea garden 20 (matched by the word "garden"; not recommended), protected area 9, mountain pass 7, summit 4, lake 4 (+1 proglacial), waterfall 3, national park 3, gompa 3, wildlife refuge 3, sacred mountain 2, road bridge 2, and about 8 singles (museum, park, ridge, sanctuary, bird sanctuary, range, mountain railway).

### 9.2 The 1 km coordinate rule
For the 142 looser-passing titles one Wikipedia `prop=coordinates` call per 50 titles (3 calls): 16 disagree with Wikidata by more than 1 km (5 of them pass the app rule, 11 only the looser rule) and 12 have no Wikipedia coordinate at all. The app drops a place on disagreement, so a bulk seed that copies this rule drops those 16 (two of the headline sights, Khecheopalri Lake and Do-drul Chorten, are among them). This is a decision for the owner: the rule was built for geosearch, where the Wikipedia point is what finds the place; in a bulk seed, the Wikidata point is the one used and the check is optional (3 calls saved, 5 more app places kept).

### 9.3 Per-cell geosearch path against SPARQL on 8 sample blocks of 19.8 km
Blocks: the 6 densest app-rule areas plus 2 sparse corners. Totals: geosearch returned 255 articles, 34 pass the app rule; SPARQL in the same blocks found 40 passing the app rule. Both: 31; geosearch only: 3; SPARQL only: 9. So bulk finds about 18% more app-rule places than the geosearch path, and misses 3 (places whose Wikipedia coordinate is in the block but whose Wikidata coordinate is not, or none). Cost of the geosearch path in these blocks: 26 calls for 8 blocks (1 geosearch plus 2 class calls where there were hits); per-cell with 5 km cells it would be up to 16 times the geosearch calls.

## 10. Bulk Wikidata end-to-end 27 Nov (Mac; counts only)

53 requests in the run (plus about 14 in two earlier probes and one title check), all HTTP 200, no 429, no timeout, no row limit.

SPARQL timing and size (one query each, all inside the 60 s limit by a large margin):

| Query | Rows | Items | Time | Size |
|---|---|---|---|---|
| Trip region, 4 sub-boxes (67 x 59 km) | 322, 237, 42, 35 | 532 together | 2.9, 4.1, 0.9, 1.0 s | 206, 152, 27, 22 KB |
| Same sub-box 1 with 200-row pages | 322 in 2 pages (200 + 122) | same | 2.2 + 1.3 s | 206 KB; equals the one-page result |
| Whole trip region as one box (133 x 118 km) | 630 | 532 | 2.0 s | 403 KB |
| 200 km box centred on the region | 1,201 | 1,022 | 3.8 s | 766 KB |
| Dense: a very large metro area, 100 km box | 2,500 | 1,947 (109 app, 137 looser) | 6.0 s | 1.6 MB |
| Semi-arid state sample, 200 km box A | 129 | 122 (3 app, 6 looser) | 2.1 s | 83 KB |
| Same, 200 km box B | 293 | 258 (20 app, 28 looser) | 6.0 s | 187 KB |
| Same, 300 km box | 762 | 697 (22 app, 32 looser) | 2.8 s | 486 KB |
| Same, 400 km box (includes a large city) | 1,636 | 1,476 (78 app, 104 looser) | 6.7 s | 1.05 MB |

- No row limit appears (largest page 2,500 rows with a 5,000 limit set by us); times are not monotone with area (cache, density), the worst was 6.7 s at 400 km, so the largest box that stays under 60 s was not reached: at least 400 km x 400 km is safe in this kind of area. A dense metro box is 3 times slower than a sparse one of the same size and 8 times larger in bytes. Plan with 100 km boxes in dense cities and 200 to 300 km elsewhere; paging works (ORDER BY ?item, LIMIT/OFFSET) but is not needed below about 5,000 rows.
- Intro text for qualifying items only: 142 titles (looser rule) in 8 calls of 20, median 2.4 s per call, mean 254 characters (after the 400 cap). For the app rule alone: 53 titles, 3 calls.
- 1 km coordinate check: 3 calls (see 9.2).
- Class label call (only for this measurement, not needed in the app): 3 calls.

### Trip region, whole seed simulated
| | calls | wall time (measured, plus the app's 0.4 s gap) |
|---|---|---|
| Bulk Wikidata, app rule: 4 SPARQL + 3 intro + 2 coordinate | 9 | about 25 s |
| Bulk Wikidata, looser rule: 4 SPARQL + 8 intro + 3 coordinate | 15 | about 60 s |
| Stored size at 0.7 KB per place | 53 places: about 37 KB; 142 places: about 100 KB | |

### Comparison table
| | Per-cell (today) | 16-cell block geosearch | Bulk Wikidata |
|---|---|---|---|
| Trip region (about 650 cells of 5 km, 42 blocks of 19.8 km) calls | about 1,300 to 2,000 (2 per empty cell, 4 or more per busy one) | about 250 (42 geosearch + about 2 to 4 per non-empty block) | 9 (app rule) or 15 (looser) |
| Time | about 25 to 35 min | about 5 min | under 1 min |
| Places found, app rule | about 34 per 8 sample blocks (same hits as block design) | same as per-cell | 40 per the same 8 blocks (+18%); 53 in the whole region |
| Places missed (landmarks, app rule, 20 tested) | Khecheopalri and Do-drul dropped by the 1 km rule in all three; Tiger Hill found by geosearch only if its Wikipedia point is in a cell (it is in the next cell south of the box edge) | same | same two dropped if the 1 km check is kept; none if it is dropped |
| Large semi-arid state (13,700 cells, about 342,000 km2): calls | about 17,600 (13,700 geosearch + class calls), 5.3 h | about 2,000, 40 min | about 10 SPARQL (200 km boxes, 16 if the bounding box is used) + about 15 to 20 intro + 6 coordinate = about 30 to 45 calls, about 2 to 3 min |
| Places found | about 100 to 300 (app rule, from the sample boxes: 3 to 22 per 200 to 300 km box, 78 in a box with a large city) | same | same set or 18% more |

What bulk does NOT do: it finds only places that have a Wikidata coordinate (the geosearch path also finds articles with only a Wikipedia coordinate: the 3 geosearch-only), and it does not replace Overpass (OSM-only objects stay as today).

### Recommendation
1. Build bulk Wikidata first (one request replaces 25 to 400 geosearch calls; every query in this run was under 7 s); keep the per-cell/block geosearch only as the fallback and for the 3 geosearch-only places. The first slice: SPARQL in 100 km sub-boxes (200 to 300 km in sparse country, 100 km around a big city), the existing class rule, intro for qualifying items only, and an owner decision on the 1 km check (keep for parity, or drop since the Wikidata point is the one stored).
2. Loosen the class rule modestly (below), and only for the bulk path where a missed class costs nothing but a larger result.

Classes to add (expected size increase in the trip region, from the counts above): lake Q23397 and glacial/proglacial lake (+5), waterfall Q34038 (+3), mountain pass Q133056 (+7), gompa Q1559394 (+3), national park Q46169 and wildlife refuge Q1377575 and sanctuary/bird sanctuary (+8), protected area Q473972 (+9; these are large areas, give them a radius cap), road bridge Q537127 (+2), park Q22698 and Tibet museum Q1139682 (+2): about +39 places, 53 to about 92 (+74%). A second step, mountain Q8502 and summit Q207326 and sacred mountain Q1595289 (+33, includes Kangchenjunga and Nathu La's range), is worth it in a mountain trip but noisy elsewhere: make it a toggle. Do not add: tea garden Q1827414 (+20, tea estates are mostly not visitor sights), villages, human settlements, constituencies, schools (these are the 479 non-passing items and are what the class rule rightly excludes). Everything together is the 142 figure.

## Class survey Rajasthan 27 Nov (Mac, `tools/measure/measure_classes.py`; class ids, labels and counts only)

Method: the measure_region SPARQL shape (box + English-Wikipedia sitelink join + OPTIONAL P31, ORDER BY ?item, paging, no sub-select), 8 boxes of 220 to 250 km spread over a large semi-arid state (the dense tourist cities, the south with hills and lakes, the east with a tiger reserve, a northern town belt, and two desert boxes, one of them nearly empty), all deduplicated by item; plus the trip box (27.0-28.2 N, 87.9-89.1 E) in one query. 17 SPARQL/label requests for the state survey and trip run, plus 8 label lookups and 9 id searches to verify class ids; every call HTTP 200, no 429, no timeout, serial, 1 request per 2 s, repo-contact User-Agent, hard cap 120. Boxes are rectangles, so a few items of neighbouring countries and states are inside (about 3% of rows are constituencies of other states, districts of another country): the proportions are what matters. Class lists are those of the committed `PlaceCellSource.kt` (HEAD) plus the proposals in sections 9 and 10; the planned classes without a verified id in the repo (proglacial lake, sanctuary, bird sanctuary) were matched by their English label and their ids read back from the data: proglacial lake Q1320719, sanctuary Q29553, bird sanctuary Q2714144.

### 1. Totals (items with an English article and a coordinate, no person)
| | Rajasthan sample (8 boxes) | Trip box (Sikkim/Darjeeling) |
|---|---|---|
| items | 2,745 | 532 |
| items with no P31 at all | 42 | 9 |
| pass the strict rule (today: classes + curated) | 208 (7.6%) | 87 (16%) |
| strict + loose (adds mountain classes, proglacial lake, sanctuary, bird sanctuary) | 212 | 119 |
| + owner additions (mountain, tea garden, stream, glacier, hill, railway station, bus stop, hydroelectric power station, stadium, mountain ridge, mountain range, mountain railway) | 537 | 172 |
| + owner-approved place types (see 4) | 612 | 177 |
| still not admitted | 2,133 (78%) | 355 (67%) |

Reading: the strict rule is the right size for Rajasthan (a class list tuned in Sikkim finds Hindu temples, forts, palaces, lakes, museums, archaeological sites, protected areas there). The owner additions mostly add one class in Rajasthan: railway station (314 items, plus 72 more carry "station located on surface"): 58% of the 537 are stations. Without it the additions are 11 stream, stadium and hill style items. Decision for the owner: stations as a toggle, off by default, in a state with thousands of small halts. In Sikkim the additions are mountains (27), tea gardens (20), streams (9), glaciers (6), stations (6), hills (4), stadiums (4), hydroelectric stations (4). Per-class counts are items carrying the class; an item with two classes is counted in both.

### 2. Rajasthan: top 60 classes among items NOT admitted (strict + loose + owner additions + owner-approved place types)
| # | QID | Class | Items | Visitor value | Recommendation |
|---|---|---|---|---|---|
| 1 | Q56436498 | village in India | 806 | noise | skip |
| 2 | Q486972 | human settlement | 354 | noise | skip |
| 3 | Q54375498 | constituency of the Rajasthan Legislative Assembly | 159 | noise | skip |
| 4 | Q515 | city | 45 | low | skip |
| 5 | - | (no P31 at all) | 42 | unknown | skip by default; curated QIDs cover the known ones |
| 6 | Q1149652 | district of India | 41 | noise | skip |
| 7 | Q3957 | town | 37 | low | skip |
| 8 | Q3918 | university | 32 | low | skip |
| 9 | Q131389368 | state private university | 27 | low | skip |
| 10 | Q4022 | river | 26 | low | optional toggle (area-like, 'rivers') |
| 11 | Q3914 | school | 26 | noise | skip |
| 12 | Q122987735 | tehsil of Rajasthan | 26 | noise | skip |
| 13 | Q532 | village | 26 | noise | skip |
| 14 | Q56061 | administrative territorial entity | 25 | noise | skip |
| 15 | Q47481352 | Lok Sabha constituency | 24 | noise | skip |
| 16 | Q7603893 | state public university | 23 | low | skip |
| 17 | Q15642541 | human-geographic territorial entity | 22 | noise | skip |
| 18 | Q1336152 | princely state | 21 | noise | skip |
| 19 | Q3024240 | historical country | 20 | noise | skip |
| 20 | Q54375446 | constituency of the Haryana Legislative Assembly | 20 | noise | skip |
| 21 | Q4671277 | academic institution | 19 | low | skip |
| 22 | Q24398318 | religious building | 18 | medium | include (generic religious building; mosques, Jain, Sikh shrines) |
| 23 | Q189004 | college | 14 | low | skip |
| 24 | Q1248784 | airport | 12 | low | skip |
| 25 | Q928830 | metro station | 11 | low | skip |
| 26 | Q54375438 | constituency of the Gujarat Legislative Assembly | 11 | noise | skip |
| 27 | Q1549591 | big city | 10 | low | skip |
| 28 | Q1663017 | engineering school | 9 | noise | skip |
| 29 | Q178561 | battle | 9 | low | skip (event, not a place) |
| 30 | Q11670533 | elevated station | 8 | low | skip |
| 31 | Q618123 | geographical feature | 8 | low | skip |
| 32 | Q41176 | building | 8 | low | skip (too generic) |
| 33 | Q2732840 | gram panchayat | 7 | noise | skip |
| 34 | Q94993988 | commercial traffic aerodrome | 7 | low | skip |
| 35 | Q6558431 | coal-fired power station | 7 | low | skip |
| 36 | Q54375465 | constituency of the Madhya Pradesh Legislative Assembly | 7 | noise | skip |
| 37 | Q125907350 | division of Rajasthan | 6 | noise | skip |
| 38 | Q47531586 | Institute of National Importance | 6 | low | skip |
| 39 | Q125450892 | former constituency of the Lok Sabha | 6 | noise | skip |
| 40 | Q16917 | hospital | 6 | low | skip |
| 41 | Q494230 | medical school | 6 | noise | skip |
| 42 | Q31855 | research institute | 5 | low | skip |
| 43 | Q34442 | road | 5 | low | skip |
| 44 | Q58339518 | town in India | 5 | noise | skip |
| 45 | Q494829 | bus station | 5 | medium | optional toggle with bus stop (transport) |
| 46 | Q3257686 | locality | 5 | noise | skip |
| 47 | Q269770 | boarding school | 4 | noise | skip |
| 48 | Q682943 | cricket field | 4 | low | optional toggle with stadium |
| 49 | Q17072837 | medical college in India | 4 | noise | skip |
| 50 | Q6958514 | Municipal council (India) | 4 | noise | skip |
| 51 | Q124339643 | former constituency of the Haryana Legislative Assembly | 4 | noise | skip |
| 52 | Q811979 | built structure | 3 | low | skip |
| 53 | Q12443800 | state of India | 3 | noise | skip |
| 54 | Q22808403 | underground station | 3 | low | skip |
| 55 | Q188055 | siege | 3 | low | skip |
| 56 | Q3146899 | Diocese of the Catholic Church | 3 | noise | skip |
| 57 | Q3520135 | deemed university | 3 | noise | skip |
| 58 | Q62079009 | constituent college | 3 | noise | skip |
| 59 | Q22808404 | station located on surface | 3 | low | skip (rides with railway station) |
| 60 | Q124339660 | former constituency of the Rajasthan Legislative Assembly | 3 | noise | skip |

What this says: 90% of what is left is administrative or settlement noise (villages, settlements, assembly constituencies, districts, universities, schools). The heritage classes a traveller wants are all small in Wikidata's P31 (one or two items per class: haveli 1, chhatri 1, ghat 1, castle 1, mausoleum 1, cenotaph 2, dargah 2, gate 2), because most heritage items carry a generic class (palace, fort, Hindu temple, building) or none; the class list cannot be made much richer by adding classes. The remaining visitor-relevant leftovers are religious building (18), fair (2), salt lake, desert, clock tower, fortress, paleontological site, urban park.

### 3. Trip box: top 60 classes among items NOT admitted (same admitted set)
| # | QID | Class | Items | Visitor value | Recommendation |
|---|---|---|---|---|---|
| 1 | Q56436498 | village in India | 104 | noise | skip |
| 2 | Q486972 | human settlement | 46 | noise | skip |
| 3 | Q54375505 | constituency of the Sikkim Legislative Assembly | 32 | noise | skip |
| 4 | Q1530705 | ward of Nepal | 18 | noise | skip |
| 5 | Q124339661 | former constituency of the Sikkim Legislative Assembly | 11 | noise | skip |
| 6 | Q1149652 | district of India | 9 | noise | skip |
| 7 | - | (no P31 at all) | 8 | unknown | skip by default; curated QIDs cover the known ones |
| 8 | Q3914 | school | 8 | noise | skip |
| 9 | Q4671277 | academic institution | 6 | low | skip |
| 10 | Q1352019 | gewog of Bhutan | 5 | noise | skip |
| 11 | Q12413618 | international border | 5 | noise | skip |
| 12 | Q133346 | border | 5 | noise | skip |
| 13 | Q15104814 | land boundary | 5 | noise | skip |
| 14 | Q122987743 | community development block in West Bengal | 5 | noise | skip |
| 15 | Q39816 | valley | 4 | medium | optional toggle (valley, area-like) |
| 16 | Q64062731 | higher secondary school | 4 | noise | skip |
| 17 | Q4022 | river | 4 | low | optional toggle (area-like, 'rivers') |
| 18 | Q29467088 | rural municipality of Nepal | 4 | noise | skip |
| 19 | Q2723540 | Jawahar Navodaya Vidyalaya | 4 | noise | skip |
| 20 | Q16830604 | census town of India | 4 | noise | skip |
| 21 | Q56061 | administrative territorial entity | 3 | noise | skip |
| 22 | Q13100073 | village of the People's Republic of China | 3 | noise | skip |
| 23 | Q123705 | neighborhood | 3 | low | skip |
| 24 | Q515 | city | 3 | low | skip |
| 25 | Q3957 | town | 3 | low | skip |
| 26 | Q15642541 | human-geographic territorial entity | 3 | noise | skip |
| 27 | Q131389368 | state private university | 3 | low | skip |
| 28 | Q34442 | road | 2 | low | skip |
| 29 | Q47490604 | constituency of the West Bengal Legislative Assembly | 2 | noise | skip |
| 30 | Q47481352 | Lok Sabha constituency | 2 | noise | skip |
| 31 | Q18918041 | municipality of West Bengal | 2 | noise | skip |
| 32 | Q1549591 | big city | 2 | low | skip |
| 33 | Q22713629 | girls' school | 2 | noise | skip |
| 34 | Q41176 | building | 2 | low | skip (too generic) |
| 35 | Q4826838 | autonomous administrative division of India | 2 | noise | skip |
| 36 | Q811979 | built structure | 2 | low | skip |
| 37 | Q189004 | college | 2 | low | skip |
| 38 | Q13058507 | subdivision of West Bengal | 2 | noise | skip |
| 39 | Q19953632 | former administrative territorial entity | 2 | noise | skip |
| 40 | Q67388747 | urban school | 2 | noise | skip |
| 41 | Q1713379 | boys' school | 2 | noise | skip |
| 42 | Q269770 | boarding school | 2 | noise | skip |
| 43 | Q44377 | tunnel | 1 | low | skip |
| 44 | Q11707 | restaurant | 1 | low | skip |
| 45 | Q1289426 | county of China | 1 | noise | skip |
| 46 | Q11631078 | athletic training facility | 1 | low | skip |
| 47 | Q1500350 | township of China | 1 | noise | skip |
| 48 | Q179700 | statue | 1 | medium | include (statue; statues are photo stops) |
| 49 | Q1361100 | skyway | 1 | low | skip |
| 50 | Q39614 | cemetery | 1 | low | skip |
| 51 | Q1663017 | engineering school | 1 | noise | skip |
| 52 | Q1080794 | public school | 1 | noise | skip |
| 53 | Q1143635 | business school | 1 | noise | skip |
| 54 | Q43229 | organization | 1 | noise | skip |
| 55 | Q3918 | university | 1 | low | skip |
| 56 | Q17009433 | Mankhim | 1 | noise | skip |
| 57 | Q9096832 | paleontological site | 1 | medium | include (fossil park, area-like) |
| 58 | Q532 | village | 1 | noise | skip |
| 59 | Q38723 | higher education institution | 1 | low | skip |
| 60 | Q47531586 | Institute of National Importance | 1 | low | skip |

### 4. Owner-approved place types: resolved ids (read from the real items of the two samples; ids verified through wbgetentities/wbsearchentities)
| Type | QID(s) | Rajasthan | Trip | Suggested PlaceKinds | areaLike | Note |
|---|---|---|---|---|---|---|
| monastery (generic) | Q44613 | 0 | 0 | TEMPLE | no | id verified by search, no item in the samples uses it (Tibetan monasteries use Q54074585, vihara Q844619, already in) |
| Jain temple | Q2613100 | 19 | 0 | TEMPLE | no | no separate Digambara or Svetambara temple class in the samples (TODO-verify if they exist) |
| gurdwara | Q337986 | 0 | 1 | TEMPLE | no | |
| dargah | Q2639699 | 2 | 0 | TOMB (a Sufi saint's tomb) | no | no "Sufi shrine" class found |
| shrine | Q697295 | 0 | 0 | TEMPLE | no | id by search; no sample item |
| synagogue | Q34627 | 0 | 0 | none fits: propose WORSHIP (or CHURCH as a stop-gap) | no | |
| castle | Q23413 | 1 | 0 | FORT | no | |
| mausoleum | Q162875 | 1 | 0 | TOMB | no | |
| stepwell | Q1473950 | 9 | 0 | HERITAGE_BUILDING | no | |
| haveli | Q7882520 | 1 | 0 | HERITAGE_BUILDING | no | |
| cenotaph | Q321053 | 2 | 0 | MEMORIAL | no | |
| chhatri | Q936941 | 1 | 0 | MONUMENT | no | cenotaph/chhatri/memorial are three separate classes in the data |
| ghat | Q1010155 | 1 | 0 | ATTRACTION | yes (a river bank, wide limit) | |
| dam | Q12323 (+ gravity dam Q3497167) | 17 (+1) | 1 | ATTRACTION | no for the point, radius default | the 17 are mostly small irrigation dams: noisy, but each has an article |
| reservoir | Q131681 (+ artificial lake Q3215290, intermittent reservoir Q55470036) | 8 (+2, +1) | 0 | ATTRACTION | yes | add all three to the area-like list |
| city gate | Q53060 "gate" (+ gate building Q2785355) | 2 (+1) | 0 | MONUMENT | no | a dedicated "city gate" class was not seen: TODO-verify |
| zoo | Q43501 | 4 | 1 | ATTRACTION | no | |
| heritage hotel | Q27686 "hotel" | 9 | 2 | HERITAGE_BUILDING (or a new LODGING kind) | no | there is no heritage-hotel class (a search finds only individual hotels); all hotels with an article are admitted by Q27686, a few are not heritage |
Totals in the Rajasthan sample: these types add 75 items on top of the 537 (stepwell 9, Jain temple 19, dam 18, hotel 9, reservoir family 11, zoo 4, gate 3, others 1 to 2). Noisy ones: dam (many small irrigation dams), hotel (not all heritage), zoo (few), gates. No PlaceKinds value fits a synagogue (propose WORSHIP) or a hotel (propose LODGING if the owner wants the guide to mention stays); everything else maps to an existing kind. Proposed new kinds: WORSHIP, LODGING (both optional).

### 5. Recommended class set (merged; default ON unless stated)
| QID | Label | Category | areaLike | Default | Rajasthan / Trip items |
|---|---|---|---|---|---|
| Q4989906 | monument | monument | no | on | 2 / 0 |
| Q44539, Q842402, Q5393308 | temple, Hindu temple, Buddhist temple | religious | no | on | 19+53 / 1+5 |
| Q2613100 | Jain temple | religious | no | on (approved) | 19 / 0 |
| Q337986 | gurdwara | religious | no | on (approved) | 0 / 1 |
| Q844619, Q54074585, Q1559394, Q44613 | vihara, Tibetan Buddhist monastery, gompa, monastery | religious | no | on | 0 / 14+21+3 |
| Q180987, Q12539846, Q1456873, Q1091037 | stupa, dagoba, peace pagoda, chorten | religious | no | on (chorten added) | 0 / 1+1 |
| Q16970, Q2977, Q32815 | church building, cathedral, mosque | religious | no | on | 1+3 / 1 |
| Q2639699, Q697295, Q34627 | dargah, shrine, synagogue | religious | no | on (approved) | 2 / 0 |
| Q24398318 | religious building (generic) | religious | no | on (new, 18 in Rajasthan) | 18 / 0 |
| Q57821, Q1785071, Q57831, Q23413 | fortification, fort, fortress, castle | fort | no | on (fortress new, castle approved) | 3+21+3+1 / 0 |
| Q16560 | palace | palace | no | on | 20 / 0 |
| Q839954, Q109607, Q9096832 | archaeological site, ruins, paleontological site | heritage | no | on (paleontological new) | 15+1+2 / 0+1+1 |
| Q358, Q1081138, Q1473950, Q7882520 | heritage site, historic site, stepwell, haveli | heritage | no | on | 2+3+9+1 / 0 |
| Q5003624, Q321053, Q936941 | memorial, cenotaph, chhatri | memorial | no | on | 1+2+1 / 0 |
| Q381885, Q162875 | tomb, mausoleum | tomb | no | on | 1+1 / 0 |
| Q53060, Q2785355, Q853854 | gate, gate building, clock tower | landmark | no | on (new) | 2+1+2 / 0 |
| Q179700 | statue | landmark | no | optional toggle with Q1395089 horseshoe curve | 0 / 1 |
| Q39715 | lighthouse | landmark | no | on | 0 / 0 |
| Q33506, Q207694, Q17431399, Q1139682, Q787934 | museum, art museum, national museum, Tibet museum, automobile museum | museum | no | on | 16+2 / 1 |
| Q10500763 | prison museum | museum | no | on | 0 / 0 |
| Q22698, Q22746, Q167346, Q2416723 | park, urban park, botanical garden, theme park | leisure | yes for parks | on | 8+2 / 1+2 |
| Q43501 | zoo | leisure | no | on (approved) | 4 / 1 |
| Q27686 | hotel (covers heritage hotels) | stay | no | on (approved; owner can tune) | 9 / 2 |
| Q23397, Q211302, Q1320719, Q188025 | lake, glacial lake, proglacial lake, salt lake | water | yes | on | 18+1 / 5+1+1 |
| Q131681, Q3215290, Q55470036 | reservoir, artificial lake, intermittent reservoir | water | yes | on (approved) | 8+2+1 / 0 |
| Q12323, Q3497167 | dam, gravity dam | water | no | on (approved; noisy) | 17+1 / 1 |
| Q34038 | waterfall | water | yes | on | 0 / 4 |
| Q1010155 | ghat | water | yes | on (approved) | 1 / 0 |
| Q46169, Q1377575, Q473972, Q29553, Q2714144 | national park, wildlife refuge, protected area, sanctuary, bird sanctuary | nature | yes (radius cap) | on | 4+7+15 / 3+3+9+1+1 |
| Q133056 | mountain pass | nature | yes | on | 1 / 7 |
| Q8502, Q207326, Q1595289, Q54050, Q46831, Q740445 | mountain, summit, sacred mountain, hill, mountain range, mountain ridge | nature | yes (radius cap) | toggle "mountains and peaks" (on for a mountain trip) | 4+1+2 / 27+4+2+4+1+1 |
| Q47521, Q35666 | stream, glacier | nature | yes | toggle "mountains and peaks" | 3 / 9+6 |
| Q8514, Q170321, Q39816 | desert, wetland, valley | nature | yes | optional toggle, off | 2 / 0+0+4 |
| Q1827414 | tea garden | leisure | yes | owner decided to include; measured as mostly not a sight, a toggle "tea estates" is the safer default | 0 / 20 |
| Q55488 (+ Q22808404) | railway station | transport | no | toggle, off by default (314+72 in Rajasthan = 58% of the admitted set) | 314 / 6 |
| Q494829 + bus stop | bus station, bus stop | transport | no | toggle with stations, off | 5 / 0 |
| Q420962, Q1497019, Q1395089 | heritage railway, mountain railway, horseshoe curve | transport | no | on | 0 / 1+1+1 |
| Q1411996, Q15911738 | run-of-river and other hydroelectric power station | infrastructure | no | toggle, off | 0 / 4+1 |
| Q483110 | stadium | leisure | no | toggle with cricket field Q682943, off | 5 / 4 |
| Q13409250, Q537127 | aerial lift (ropeway), road bridge | landmark | no | on | 0 / 1+2 |
| Q288514 | fair | event | no | optional toggle | 2 / 0 |
Not recommended: villages, human settlements, constituencies, districts, tehsils, universities, schools, colleges, hospitals, airports, metro stations, roads, border classes, battles and sieges, power stations (coal, gas, solar), princely states. Class ids marked TODO-verify: a "city gate" class, Digambara and Svetambara temple, bus stop (Q953806 was not checked) and the heritage-hotel class (none exists).
