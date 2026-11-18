# Trip seed presets and coverage check, 2026-11-18

Branch feat/v101-ask-eval. Trips: Gangtok/Darjeeling 1-6 Dec 2026, Sri Lanka 23-31 Dec 2026 (cultural triangle and south coast). Same method as `hyderabad-seed-coverage-2026-11-18.md`: read-only HTTP GETs from the Mac, the app's query shapes (cell box `gsbbox`, `gslimit=500`, extracts in batches of 20, `wbgetentities` in batches of 50), the preset's own cell enumeration (`Geohash5.cellsCovering`), the class allowlist `PlaceCellSource.MONUMENT_CLASSES`. Scripts (outside the repo): `~/Desktop/rokid/scratch/seed-check/{coords,ents,wp,trip,report}.py`, results `trip_cells.json`, `ents.json`. No personal data sent. Wikipedia/Wikidata answered HTTP 429 with `retry-after` several times during the run; the scripts honoured it and ran one cell at a time (3 s apart). Overpass was NOT replayed (unreachable from this Mac on 2026-11-18, see the Hyderabad report).

Everything below is **approximate: UNVERIFIED on the ground**. Coordinates are Wikidata P625 values read back from wikidata.org on 2026-11-18; nothing was typed from memory.

## Presets (Place Guide settings, "Seed this area")

Box = bounding box of the sites' P625 plus 0.01 degree, rounded outward to 3 decimals. This is tighter than the planned 0.05 to 0.1 degree on purpose: a cell is 0.044 degree (about 4.9 km), a 0.1 degree box touches 9 to 16 cells, and the whole cell is fetched anyway. A preset with distant sites has two boxes, seeded one after the other (never in parallel). The cell count is on the button before seeding; each preset has the note "Approximate: UNVERIFIED." (Sri Lanka ones add "Check local photography rules.").

| Preset | Boxes (S, W, N, E) | Cells | Sites (Wikidata) |
|---|---|---|---|
| Gangtok and surroundings | 27.278, 88.551, 27.361, 88.638 | 9 | Gangtok Q186141, Enchey Q5375417, Do-drul Chorten Q5285886, Rumtek Q1546234, Banjhakri Falls Q23038925 |
| Darjeeling | 27.001, 88.237, 27.059, 88.274 + 26.984, 88.275, 27.005, 88.296 | 5 | Darjeeling Q169997, Batasia Loop Q4868648, Ghum Monastery Q3320284, Happy Valley Q5653142, Peace Pagoda Q7157413, station Q24949017; Tiger Hill Q16901632 |
| Pelling/Yuksom (West Sikkim) | 27.29, 88.223, 27.315, 88.263 + 27.359, 88.198, 27.384, 88.233 | 8 | Pelling Q7161579, Pemayangtse Q7161856; Yuksom Q839046, Khecheopalri Q3214979 |
| Sri Lanka: Sigiriya/Dambulla | 7.946, 80.749, 7.967, 80.77 + 7.846, 80.638, 7.867, 80.659 | 6 | Sigiriya Q272153; Dambulla cave temple Q45690 (the two are 15 km apart: one box would be 16+ cells) |
| Sri Lanka: Anuradhapura | 8.325, 80.386, 8.362, 80.421 | 4 | Anuradhapura Q5724, Ruwanwelisaya Q3534755, Jetavanaramaya Q1961579, Sri Maha Bodhi Q944175 |
| Sri Lanka: Polonnaruwa | 7.923, 80.99, 7.976, 81.015 | 4 | Polonnaruwa Q394443, Gal Vihara Q5517744, Vatadage Q7225955 |
| Sri Lanka: Kandy | 7.261, 80.585, 7.304, 80.652 | 6 | Kandy Q203197, Temple of the Tooth Q289175, Peradeniya Q3119056 |
| Sri Lanka: Galle and south coast | 6.017, 80.207, 6.038, 80.228 + 5.935, 80.419, 5.985, 80.47 | 8 | Galle Fort Q2507050; Weligama Q3534843, Mirissa Q1345652 |

Rumtek has two P625 values on Wikidata (27.2886, 88.5614 and 27.3319, 88.6019); the first, which matches Wikipedia, is used. Pelling/Yuksom is 8 cells in two boxes, judged reasonable (Hyderabad is 12). Not in any preset: Tsomgo Lake (Wikidata 27.375, 88.764, cell tuz5e), Nathu La (27.387, 88.831, tuz5v), Ritigala (8.109, 80.656, tc3q5), the Darjeeling Himalayan Railway line (Q463874 sits near Siliguri). They need their own preset or a typed box if wanted; none was added (not asked).

## Result per monument (allowlist as of this change)

"In preset" = the monument's cell is among the preset's cells. "Kept" = returned by Wikipedia geosearch AND its P31 is in the allowlist.

| Place | In preset | Result | Notes |
|---|---|---|---|
| Rumtek Monastery | Gangtok | FIXED, kept | P31 vihara Q844619, Tibetan Buddhist monastery Q54074585 were missing |
| Enchey Monastery | Gangtok | FIXED, kept | Q54074585 |
| Tsomgo Lake | none | NOT kept | P31 glacial lake Q211302: a natural feature, not admitted; outside every preset |
| Nathu La | none | NOT kept | P31 mountain pass + boundary point; restricted area; the geosearch returns 2 items in its cell, the pass is not kept |
| Ban Jhakri Falls (Banjhakri Falls and Energy Park) | Gangtok | NOT kept | P31 waterfall Q34038, not admitted |
| Do Drul Chorten | Gangtok | NOT kept | Wikidata Q5285886 has no P31 at all: nothing an allowlist can match; only Overpass extras could add it |
| Batasia Loop | Darjeeling | NOT kept | P31 horseshoe curve Q1395089, a railway geometry feature, not admitted |
| Darjeeling Himalayan Railway / Darjeeling station | Darjeeling | NOT kept | station is P31 railway station Q55488 (every station would enter); the line itself is far from the preset |
| Ghum Monastery (Yiga Choeling) | Darjeeling | FIXED, kept | Q54074585 |
| Tiger Hill | Darjeeling | NOT kept | P31 geographical feature Q618123 + mountain Q8502, not admitted |
| Happy Valley Tea Estate | Darjeeling | NOT kept | P31 tea plantation Q70780172 + village: an agricultural business, not admitted |
| Peace Pagoda (Darjeeling) | Darjeeling | FIXED, kept | P31 peace pagoda Q1456873 ("Buddhist stupa; a monument"), kind stupa |
| Pemayangtse Monastery | West Sikkim | FIXED, kept | Q844619 |
| Khecheopalri | West Sikkim | NOT kept | P31 lake Q23397: not admitted |
| Sigiriya | Sigiriya/Dambulla | NOT kept | The item Q272153 is P31 human settlement Q486972; the neighbour "Pidurangala Vihara" is kept (temple) |
| Dambulla Cave Temple | Sigiriya/Dambulla | kept | Buddhist temple |
| Ritigala | none | NOT returned | Q19719626 (mountain) has no coordinates on its Wikipedia page, so the geosearch cannot return it |
| Ruwanwelisaya | Anuradhapura | kept | Also kept in the same cells: Thuparamaya, Lankarama, Dakkhina Stupa, Vessagiriya, the city (archaeological site), Anuradhapura Museum |
| Jetavanaramaya | Anuradhapura | NOT returned | Wikipedia page has no coordinates (P31 stupa would be kept) |
| Sri Maha Bodhi (Anuradhapura) | Anuradhapura | NOT kept | P31 remarkable tree Q811534, not admitted (a tree) |
| Gal Vihara | Polonnaruwa | kept | with Rankoth Vehera |
| Polonnaruwa Vatadage | Polonnaruwa | FIXED, kept | P31 ruins Q109607, kind archaeological site; Satmahal Prasada was already kept |
| Temple of the Tooth (Kandy) | Kandy | kept | also Malwathu/Asgiri Viharas, National Museum, churches, mosques |
| Peradeniya Botanical Garden | Kandy | FIXED, kept | P31 botanical garden Q167346, kind attraction |
| Galle Fort | Galle and south coast | kept | also Galle Lighthouse, cathedral, Groote Kerk, mosque, two museums (8 kept in cell tc143) |
| Mirissa, Weligama | Galle and south coast | not kept | towns/settlements (Q486972, Q3957); nothing monument-like there in Wikipedia |

"FIXED" = missing in the allowlist of 2026-11-18 morning, kept after the additions below (verified by a fixture test mirroring the real P31 values; the live pack was not re-seeded here). Wikipedia hits per cell range from 0 to 45; places kept per cell 0 to 8 (Gangtok 2, Darjeeling 4, West Sikkim 0 before the fix, Sigiriya/Dambulla 2, Anuradhapura 7, Polonnaruwa 3, Kandy 15, Galle 8).

## Allowlist additions (each id and English label read back from wikidata.org on 2026-11-18)

Q844619 vihara, Q54074585 Tibetan Buddhist monastery (kind temple); Q1456873 peace pagoda (stupa); Q109607 ruins (archaeological site); Q167346 botanical garden (attraction). Pinned by `monumentClassIdsAreTheOnesVerifiedAgainstWikidata` and `tripSitesAreKeptAndNonMonumentClassesAreNot` (PlaceFetchTest). Seen and deliberately NOT added (pinned by `tripClassesThatAreNotMonumentsStayOut`): lake Q23397, glacial lake Q211302, waterfall Q34038, mountain pass Q133056, geographical feature Q618123, railway station Q55488, horseshoe curve Q1395089, tea plantation Q70780172 (a business), remarkable tree Q811534, human settlement Q486972. People (Q5) are in no entry.

Consequence: for Rumtek-like sites the Wikipedia/Wikidata path now works; the places in the NOT kept rows above rely on the Overpass extras (OSM `tourism=attraction|viewpoint`, `historic=*`), which could not be checked from this Mac.

## Curated individual-landmark allowlist, version 1 (2026-11-18)

`PlaceCellSource.CURATED_LANDMARK_QIDS` (version `CURATED_LANDMARKS_VERSION` = 1): an item is admitted when its P31 is in `MONUMENT_CLASSES` OR its QID is in the curated set. Items whose P31 includes a person class (`PERSON_CLASSES`: human Q5, fictional human Q15632617, character Q95074, human whose existence is disputed Q21070568) are rejected even for a curated QID; coordinates are still required. Each id, label, P31 and P625 below was read back from wikidata.org on 2026-11-18 (`wbgetentities`, labels en, claims; found with `wbsearchentities`; requests one at a time, 3 s apart, no 429 seen). Pinned by `curatedLandmarkIdsAreTheOnesVerifiedAgainstWikidata`; behaviour by `curatedQidIsAdmittedAndTheSameClassWithoutTheQidIsNot`, `aPersonIsRejectedEvenWhenItsQidIsCurated`, `aCuratedQidWithoutAWikidataEntityOrPageIsRejected`.

| Place | QID | Wikidata label | P31 | P625 | Kind | Status |
|---|---|---|---|---|---|---|
| Sigiriya (rock fortress) | Q272153 | Sigiriya | Q486972 human settlement | 7.957, 80.760 | archaeological_site | curated, now kept |
| Tiger Hill | Q16901632 | Tiger Hill, Darjeeling | Q618123, Q8502 | 26.995, 88.285 | viewpoint | curated, now kept |
| Batasia Loop | Q4868648 | Batasia Loop | Q1395089 horseshoe curve | 27.017, 88.247 | attraction | curated, now kept |
| Banjhakri Falls | Q23038925 | Banjhakri falls and park | Q34038 waterfall | 27.351, 88.604 | attraction | curated, now kept |
| Khecheopalri Lake | Q3214979 | Khecheopalri Lake | Q23397 lake | 27.373, 88.208 | attraction | curated, now kept |
| Sri Maha Bodhi (Jaya Sri Maha Bodhi) | Q944175 | Sri Maha Bodhi | Q811534 remarkable tree | 8.345, 80.397 | historic_site | curated, now kept |
| Happy Valley Tea Estate | Q5653142 | Happy Valley Tea Estate | Q70780172, Q56436498 | 27.048, 88.251 | attraction | curated, now kept |
| Tsomgo Lake | Q542864 | Lake Tsongmo | Q211302 glacial lake | 27.375, 88.764 | attraction | curated; still outside every preset (cell tuz5e) |
| Do Drul Chorten | Q5285886 | Do-drul Chorten | none | 27.313, 88.605 | stupa | curated, now kept |
| Darjeeling station | Q24949017 | Darjeeling railway station | Q55488, Q22808404 | 27.038, 88.263 | heritage_building | curated, now kept |
| Ghum station | Q60181073 | Ghum railway station | Q55488 | 27.009, 88.254 | heritage_building | curated, now kept |
| Rumtek, Pidurangala | | | | | | already covered by classes, not curated |
| Mirissa, Weligama | | | | | | settlements, deliberately skipped |
| Jetavanaramaya | Q1961579 | Jetavanaramaya | Q180987 stupa | 8.351667, 80.403611 | stupa | curated in version 2 (2026-11-19), found through the verified-coordinate table below |
| Ritigala | Q19719626 | Ritigala | Q8502 mountain | 8.108611, 80.656111 | archaeological_site | curated in version 2 (2026-11-19), found through the verified-coordinate table below; still outside every preset (cell tc3q5) |
| "Ban Jhakri Falls", "Tsomgo Lake", "Happy Valley" by name | | | | | | `wbsearchentities` gave no hit for those spellings; the ids above come from the 2026-11-18 coverage run and were re-read by id |

## Verified-coordinate extras (B7, 2026-11-19)

`PlaceCellSource.VERIFIED_COORDINATES` (version 1) closes the discovery gap for curated places whose Wikipedia page has no coordinates. Re-read from wikidata.org on 2026-11-19 (`wbgetentities`, labels en, claims; one request at a time, 4 s apart; one HTTP 429 on Ghum, retried after the wait) and checked against English Wikipedia `prop=coordinates` the same day:

| QID | Label | P31 | P625 (stored) | English Wikipedia coordinates |
|---|---|---|---|---|
| Q1961579 | Jetavanaramaya | Q180987 stupa | 8.351667, 80.403611 | none |
| Q19719626 | Ritigala | Q8502 mountain | 8.108611, 80.656111 | none |
| Q60181073 | Ghum railway station | Q55488 | 27.008643, 88.254223 | none |

Skipped (not in the table): the other 10 curated QIDs (Q272153, Q16901632, Q4868648, Q23038925, Q3214979, Q944175, Q5653142, Q542864, Q5285886, Q24949017). Their Wikidata P625 reads back fine, but their Wikipedia pages carry coordinates, so geosearch already returns them and a second path would only duplicate. Nothing was skipped for lack of verification.

Behaviour: after geosearch, a table entry whose stored coordinate lies inside the cell and which geosearch did not return is added to the existing Wikidata batch (same request, no new endpoint) and passes the same gates: unreadable P31 rejects, a person class rejects, the kind is the P31 class or the curated kind, same default radius, same cell. The coordinate and name come from the table, never from the response; an entity that does not come back is dropped. Source is `wikidata`, ref `wd:Q...`, summary empty (no Wikipedia page). Rate limits unchanged (same sequential requests, 429 handled by the existing HTTP layer). No new log lines. Pinned by `verifiedCoordinateTableIsPinned` and five behaviour tests in `PlaceFetchTest`. Curated version is now 2 (Q1961579, Q19719626 added). Ritigala remains outside every preset (needs a typed box).

Discovery limit (superseded for the three table entries above; otherwise unchanged): the app finds candidates by Wikipedia geosearch of the cell box first, then enriches with Wikidata by the QIDs of the returned pages. A Wikidata item with P625 but no Wikipedia coordinates (Jetavanaramaya, Ritigala) is never a candidate, curated or not; only the Overpass extras (OSM tags) can add such places. Curation helps only items whose Wikipedia page is geotagged. Unverified: whether each curated place's English Wikipedia page is geotagged (Tsomgo is outside the presets anyway); the live pack was not re-seeded here.

## Sri Lanka cultural rules (UNVERIFIED, from spec section E; docs only, NOT app text)

- Sigiriya: photography of the frescoes is restricted.
- Posing with your back to a Buddha image is an offence; do not photograph in that pose.
- Several sites charge a camera fee.
- Drones are banned.

The app shows only the preset help "Check local photography rules." (test `onlyTheSriLankaPresetsCarryTheShortPhotographyHelpAndNoRuleTextIsInTheApp` fails if rule words enter the preset text). Verify with the site's ticket office on the day.

## Unchanged

Room v17, places.db v1, PROTOCOL_VERSION 1, no new permissions, canvas JS untouched, logs literal text and counts (no new log lines), only the 5 km cell key leaves the phone.
