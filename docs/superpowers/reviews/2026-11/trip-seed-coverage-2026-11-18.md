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

## Sri Lanka cultural rules (UNVERIFIED, from spec section E; docs only, NOT app text)

- Sigiriya: photography of the frescoes is restricted.
- Posing with your back to a Buddha image is an offence; do not photograph in that pose.
- Several sites charge a camera fee.
- Drones are banned.

The app shows only the preset help "Check local photography rules." (test `onlyTheSriLankaPresetsCarryTheShortPhotographyHelpAndNoRuleTextIsInTheApp` fails if rule words enter the preset text). Verify with the site's ticket office on the day.

## Unchanged

Room v17, places.db v1, PROTOCOL_VERSION 1, no new permissions, canvas JS untouched, logs literal text and counts (no new log lines), only the 5 km cell key leaves the phone.
