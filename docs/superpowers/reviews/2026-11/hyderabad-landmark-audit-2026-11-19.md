# Hyderabad and Ramoji landmark audit, 2026-11-19

Branch feat/v101-ask-eval. Rule under audit: a place enters the pack when it is a Wikipedia geosearch hit AND (its Wikidata P31 is in `MONUMENT_CLASSES` OR its QID is in `CURATED_LANDMARK_QIDS`), is not a person, and has coordinates. Items with no Wikipedia coordinates are only reachable through `VERIFIED_COORDINATES`.

Method: wikidata.org `wbsearchentities` then `wbgetentities` (labels, P31, P625, enwiki sitelink; P31 class labels in a second call) on 2026-11-19, one request at a time, generic User-Agent, no email, no 429. Wikipedia `prop=coordinates` per enwiki title. Geohash5 cell of each point computed and compared with the 12 cells of the Hyderabad preset (tepdx tepdz tepf8 tepf9 tepfb tepfc tepfd tepff tepep tepg0 tepg1 tepg4) and the Ramoji cell (tepfn). Every place below lies in a seeded cell.

## Table

"In list" = P31 in `MONUMENT_CLASSES` as of this audit start. WP coords = the English Wikipedia page has coordinates (so geosearch can return it).

| Site | QID | P31 (labels) | P625 | In list | WP coords | Verdict |
|---|---|---|---|---|---|---|
| Charminar | Q1771696 | monument, mosque | 17.36161, 78.47466 | yes | yes | ADMITTED |
| Golconda (the fort page, "Golconda Fort" redirects here) | Q4141623 | archaeological site, neighborhood | 17.38334, 78.40417 | yes | yes | ADMITTED |
| Golconda Fort (second item, no enwiki sitelink) | Q17986822 | fort | 17.383309, 78.401053 | yes | no page | NO-COORDS, not added: same fort as Q4141623, which is already admitted (a second row would duplicate it) |
| Taramati Baradari | Q7685364 | caravanserai, Baradari | 17.37608, 78.378117 | no | no | DROPPED and NO-COORDS: fixed (curated + verified coordinate) |
| Naya Qila | Q6983045 | fort | none | yes | no | NO-COORDS (no P625 either; cannot be admitted) |
| Bala Hissar (gate, India) | Q118558776 | gate | none | no | no page | NO-COORDS (no P625, no page) |
| Ibrahim Bagh | Q97142975 | none | none | no | no page | NO-COORDS (no P31, no P625, no description: cannot even be identified as the Golconda garden) |
| Fateh Darwaza | none found | | | | | NOT FOUND on Wikidata (search for "Fateh Darwaza" and "Fateh Darwaza Golconda Fort" empty; no enwiki page either) |
| Qutb Shahi tombs | Q7273006 | tomb | 17.395, 78.396 | yes (added 2026-11-18) | yes | ADMITTED |
| Mecca Masjid | Q536288 | mosque | 17.36056, 78.47333 | yes | yes (page "Makkah Masjid, Hyderabad") | ADMITTED |
| Chowmahalla Palace | Q3380670 | palace | 17.357725, 78.471705 | yes | yes | ADMITTED |
| Salar Jung Museum | Q1864572 | art museum, national museum | 17.371426, 78.480347 | yes (added 2026-11-18) | yes | ADMITTED |
| Laad Bazaar | Q6466530 | marketplace, bazaar | 17.361923, 78.472842 | no | yes | DROPPED: fixed (curated) |
| Falaknuma Palace | Q2263539 | palace, hotel, heritage structures in Hyderabad | 17.330955, 78.467504 | yes | yes | ADMITTED |
| Paigah Tombs | Q7124701 | cemetery | 17.343983, 78.504131 | no | yes | DROPPED: fixed (curated) |
| Birla Mandir, Hyderabad | Q4916532 | temple | 17.38, 78.4 | yes | yes (17.4062, 78.4691) | ADMITTED (Wikidata P625 is rounded and about 3.7 km from the Wikipedia point; `PlaceCellSource` prefers the Wikidata point, see note 2) |
| Hussain Sagar | Q3275994 | reservoir | 17.42222, 78.47389 | no | yes | DROPPED: fixed (curated) |
| Buddha Statue of Hyderabad | Q4984164 | colossal statue | 17.415556, 78.475 | no | yes | DROPPED: fixed (curated) |
| Lumbini Park | Q1650886 | park | 17.41, 78.4722 | no | yes | DROPPED: fixed (curated) |
| Nehru Zoological Park | Q3493970 | zoo | 17.351111, 78.449722 | no | yes | DROPPED: fixed (curated) |
| Ramoji Film City | Q1786944 | film studio, theme park | 17.2523, 78.6802 | yes (theme park, added 2026-11-18) | yes (17.252, 78.681) | ADMITTED |

Counts (21 rows): ADMITTED 9 (Charminar, Golconda, Qutb Shahi tombs, Mecca Masjid, Chowmahalla, Salar Jung, Falaknuma, Birla Mandir, Ramoji); DROPPED 7 (Taramati Baradari, Laad Bazaar, Paigah Tombs, Hussain Sagar, Buddha statue, Lumbini Park, Nehru Zoo), all 7 now fixed; NO-COORDS 4 (Golconda Fort duplicate item, Naya Qila, Bala Hissar gate, Ibrahim Bagh), nothing to add; NOT FOUND 1 (Fateh Darwaza). Taramati Baradari is counted once, under DROPPED.

Ramoji Film City has no sub-sites on Wikidata worth listing beyond the studio item itself (searches returned only Q1786944 and a sub post office, Q69539936, not a visit site).

## What changed

`PlaceCellSource.kt`:
- `CURATED_LANDMARKS_VERSION` 3 to 4. Added Q7685364 Taramati Baradari (historic_site), Q7124701 Paigah Tombs (tomb), Q4984164 Buddha Statue of Hyderabad (monument), Q3275994 Hussain Sagar (attraction), Q1650886 Lumbini Park (attraction), Q3493970 Nehru Zoological Park (attraction), Q6466530 Laad Bazaar (attraction). Each line carries label, P31 and P625 and the date.
- `VERIFIED_COORDINATES_VERSION` 2 to 3. Added Q7685364 Taramati Baradari at (17.37608, 78.378117). Its Wikipedia page has no coordinates, so geosearch cannot return it; the extras path in `fetchCell` adds it in cell tepdz (in the preset), through the same person, class and curated gating.
- How the point was checked to be the building and not a larger area: OpenStreetMap Nominatim "Taramati Baradari, Hyderabad" returns (17.3761241, 78.3781632), about 5 m from the Wikidata point, and Wikidata's own class is a building (caravanserai, Baradari), not a locality.

`PlaceFetchTest.kt`: the pinned-ids test (`curatedLandmarkIdsAreTheOnesVerifiedAgainstWikidata`) now lists the 7 new ids with labels and expects version 4; `verifiedCoordinateTableIsPinned` expects version 3, the three keys and the Taramati row.

Tests: `:services:connectors:test :services:memory:test` BUILD SUCCESSFUL.

Nothing person-, settlement- or district-like was added (Golconda's "neighborhood" class is on Q4141623, already admitted by its archaeological-site class, and was not touched). Room v17, places.db v1, PROTOCOL_VERSION 1, no new permissions, logs unchanged.

## Notes and not verified

1. Judgement calls: Hussain Sagar (reservoir), Lumbini Park (park), Nehru Zoo (zoo) and Laad Bazaar (market street) are attractions rather than monuments; they follow the precedent of the lakes and the energy park already curated for Sikkim. Remove the four ids from `CURATED_LANDMARK_QIDS` if only monuments are wanted.
2. Birla Mandir: Wikidata P625 (17.38, 78.4) is rounded and wrong by about 3.7 km versus Wikipedia (17.4062, 78.4691, the real temple on Naubat Pahad). Because `fetchCell` takes `ent.lat` first, the stored point is the wrong one, and 17.38, 78.4 is in cell tepfb (the Golconda cell) while the temple is in tepg1. Not changed (outside this task's scope); a follow-up should prefer the Wikipedia coordinate when the two differ by more than about 1 km, or Birla Mandir should be fixed on Wikidata. Seeded places near Golconda may show a Birla Mandir pin there.
3. Overpass/OSM extras were not replayed (unchanged from the 2026-11-18 check). Fateh Darwaza, Bala Hissar, Ibrahim Bagh and Naya Qila depend on OSM (`historic=city_gate` etc.) if the user wants them.
4. Wikipedia geosearch hit presence was not replayed for the new curated ids; their Wikipedia pages have coordinates (table), which is what geosearch uses.
