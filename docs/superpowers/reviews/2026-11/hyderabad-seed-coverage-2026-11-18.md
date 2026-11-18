# Hyderabad seed coverage check, 2026-11-18

Branch feat/v101-ask-eval. Read-only HTTP GETs from the Mac with the app's own User-Agent, the app's own query shapes (cell box `gsbbox`, `gslimit=500`, extracts in batches of 20, `wbgetentities` in batches of 50, class allowlist `PlaceCellSource.MONUMENT_CLASSES`) and the preset's own cell enumeration (`Geohash5.cellsCovering`, 12 cells). Script: `~/Desktop/rokid/scratch/seed-check/check.py` (outside the repo). No personal data sent. Overpass was NOT replayed (see below).

## Result per key place (BEFORE the fix, allowlist of 2026-11-17)

| Place | Cell | In preset cells | Wikipedia article returned | Kept by allowlist |
|---|---|---|---|---|
| Charminar | tepfc | yes | yes | yes (monument) |
| Mecca Masjid ("Makkah Masjid, Hyderabad") | tepfc | yes | yes | yes (mosque) |
| Chowmahalla Palace | tepf9 | yes | yes | yes (palace) |
| Golconda ("Golconda", the fort) | tepfb | yes | yes | yes (archaeological site) |
| Qutb Shahi Tombs | tepdz | yes | yes | NO: P31 = Q381885 "tomb", not in the list |
| Salar Jung Museum | tepfc | yes | yes | NO: P31 = Q207694 "art museum" + Q17431399 "national museum" (no subclass walk) |
| Ramoji Film City | tepfn | NO (outside the box; 23 Nov) | yes | NO: P31 = Q375336 "film studio" + Q2416723 "theme park" |

Cells of the preset: tepdx tepdz tepf8 tepf9 tepfb tepfc tepfd tepff tepep tepg0 tepg1 tepg4 (Wikipedia hits per cell 4 to 87; places kept 0 to 15 after the fix list below is added the three dropped places join their cells).

## Which cell failed on the emulator

The rehearsal's 1 failed cell of 12 is not identifiable from its log (counts only). From this Mac, the first pass over the 12 cells got HTTP 429 (Too Many Requests) from Wikipedia on tepf9 (Chowmahalla) and tepfd right after a burst of requests; both answered normally 60 s later. That is the most likely cause: a rate limit, not a hole in the data. The fix is the seed's retry pass (one more try of each failed cell after the others, 5 s back-off).

## Overpass

All three mirrors (overpass-api.de, overpass.kumi.systems, overpass.private.coffee) timed out on `/api/status` from this Mac at 2026-11-18 (curl, 15 s), like the emulator run. Mirror reachability from the real phone's network is NOT verified; the fallback chain is unit-tested with fake HTTP only.

## What was fixed

1. Allowlist: added Q381885 tomb (kind tomb), Q207694 art museum and Q17431399 national museum (kind museum), Q2416723 theme park (kind attraction). Each id and English label read back from wikidata.org on 2026-11-18. `film studio` (Q375336) is not admitted. With these, Qutb Shahi Tombs, Salar Jung Museum and Ramoji Film City land in the pack.
2. Ramoji preset: "Seed Ramoji Film City (23 Nov)" in the Place Guide settings: box 17.2443, 78.6708 to 17.2643, 78.6908 (the listed coordinate 17.2543 N 78.6808 E plus or minus 0.01 degree), exactly one cell (tepfn), outside the Hyderabad box.
3. Overpass: `OverpassMirrorSource` tries the three mirrors in order, 30 s timeout each, back-off 2 s then 4 s between mirrors, then throws; `PlaceCellSource` turns that into a PARTIAL cell (stored in the existing `cell.status` = PARTIAL, no migration; Wikipedia/Wikidata places kept). After an all-mirrors failure the mirrors are not asked again for 3 minutes (so a 12-cell seed does not wait out 12 rounds of timeouts), then probed again. A seed treats a PARTIAL cell as not fresh, so seeding again retries it; normal glances retry it after 1 day (unchanged).
4. `seedArea`: a failed cell is retried once after the others (5 s pause). `SeedResult` has `ok`, `partial`, `failed`, `retried`. Screen text: "Cells: N ok, P seeded without OSM extras, F failed (R retried once). M places in the pack." plus "Seed again later to add the OSM extras." or "Try again when online."

Unchanged: Room v17, places.db v1, PROTOCOL_VERSION 1, no new permissions, places excluded from backup, only the 5 km cell key leaves the phone. Logs: literal text and counts (`overpass mirror failed idx=0 cls=...`, `seed retry cells=1 recovered=1`, `seed done cells=12 ok=.. partial=.. skipped=.. failed=..`), no coordinates or names.

## Still not covered or not verified

- Overpass/OSM extras from a network that can reach a mirror.
- Nothing else of the seven is missing once the Ramoji preset is also seeded (Ramoji Film City needs its own seed tap on the 23 Nov trip, or earlier on Wi-Fi).
- Golconda's kept row is the archaeological site "Golconda"; the fort's sub-places (Bala Hissar etc.) depend on Wikidata P361/P527 and are as before.
