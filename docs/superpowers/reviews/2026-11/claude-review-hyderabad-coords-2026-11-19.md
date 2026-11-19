# Review: coordsDisagree rule (0b71d96b..9d69e05a) and Place Guide Hyderabad curation (b07969ad)

Reviewer: Claude (independent, read-only). This is NOT a Codex review.
Files: android-companion/services/connectors/src/main/kotlin/com/rokid/context/connectors/place/PlaceCellSource.kt, .../test/.../PlaceFetchTest.kt, PlaceGuideSettings.kt.

## P1
None.

## P2
None.

## P3
1. PlaceCellSource.kt ~L205 (companion haversineM/coordsDisagree): maths correct. Degrees to radians via Math.toRadians, a = sin^2(dp/2)+cos p1 cos p2 sin^2(dl/2), 2*r*asin(sqrt(a)) with a clamped to [0,1] (equivalent to the atan2 form), r = 6,371,008.8 m (mean Earth radius), result in metres, COORD_DISAGREE_M = 1000.0 metres. Boundary is strict `>`: exactly 1000.0 m is kept; a 1 m difference is immaterial, and the test brackets 999 m / 1001 m. NaN: d is NaN, `!isFinite()` returns true (disagree, fail closed); coerceIn on NaN returns NaN, so this holds. An infinite input gives NaN or Inf, also disagree. OK.
2. PlaceCellSource.kt L49-51 (fetchCell loop): the check is a pure extra `continue` placed before kept[] is written, so it can only remove candidates; nothing previously rejected can be admitted. It sits after the p31Malformed and PERSON_CLASSES gates and after the kind check, so people stay excluded and the admission order is unchanged. Compared only when ent.lat/lon AND a Wikipedia point (geosearch hit, else page) are both non-null, so single-source entities are unaffected. The `extras` loop (VERIFIED_COORDINATES, L66-72) has no Wikipedia point, is untouched, and still uses the table coordinate only (the test covers this, with Wikidata at 17.5/78.5). Caveat: if a VERIFIED_COORDINATES QID ever does appear in geosearch, it goes through the page path and the new check applies. That is correct fail-closed behaviour. The tested known data fit: Ramoji 91 m, Golconda 326 m and Hussain Sagar 290 m are under 1 km; Birla 7,886 m is rejected.
3. Logging: the log line adds only the integer `coordsDisagree=N`; no names or coordinates (the test asserts the title is absent). OK.
4. The curated large-area entities (Hussain Sagar lake, Nehru Zoological Park, Paigah Tombs, Lumbini Park) could in principle have a Wikipedia centroid more than 1 km from the Wikidata point and then be silently dropped (counted only). I could not check them offline; only Hussain Sagar (290 m) is a known measurement. Worth a device or log check of `coordsDisagree` after seeding Hyderabad.
5. b07969ad curated ids: Q7685364, Q7124701, Q4984164, Q3275994, Q1650886, Q3493970, Q6466530 all have label, P31 class and P625 in the code comment (L201-207); the date 2026-11-19 is in the shared version-4 header, not repeated per line (acceptable). None is a person, settlement or district (caravanserai/Baradari, cemetery, colossal statue, reservoir, park, zoo, marketplace). Their kinds are all valid PlaceKinds. I could not re-read Wikidata offline, so I relied on the commit's claims.
6. PlaceFetchTest.kt: the pinned set (verified.keys == CURATED_LANDMARK_QIDS.keys) includes all 7 new ids. CURATED_LANDMARKS_VERSION 3 to 4 and VERIFIED_COORDINATES_VERSION 2 to 3 are consistent with the code constants and the tests (assertEquals(4,...), assertEquals(3,...)), and the verified-table key set is pinned with the Taramati value. The doc comment above VERIFIED_COORDINATES still says "version 2" in its first line (L164), but the version-3 note is added inside, so it is cosmetic.
7. Preset box: HYDERABAD = GeoBox(17.34, 78.38, 17.41, 78.52). The Taramati point (17.37608, 78.378117) is about 0.0019 degree (about 200 m) WEST of the box edge, so it is not strictly inside the box. Buddha Statue (17.4156) and Hussain Sagar (17.4222) are also north of 17.41. Seeding covers whole 5 km geohash cells that overlap the box, and the cells containing those points (lon 78.3496-78.3936, lat 17.3606-17.4045, and lat 17.4045-17.4485 for the lake) do overlap the box, so all of them are seeded. No functional issue. A tighter box is not needed. The extras filter uses box.contains on the CELL box, so Taramati is found when its cell is fetched.

READY
