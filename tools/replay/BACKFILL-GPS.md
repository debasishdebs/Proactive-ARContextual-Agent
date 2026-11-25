# backfill_gps.py: GPS backfill dry run

Planner. It proposes a position for existing Moments from the media-fix CSV and writes a report. It never talks to a phone. `--emit-import` also writes `moment-loc-import.tsv` (0600, coordinates, stays on the Mac), which `tools/import-moment-locations.sh` sends to a paired phone (`POST /api/moments/locations/import`, v18). Ruling: fill only what can be filled, never force, blank otherwise.

Python 3 stdlib only. No network, no adb, no photo is opened.

## Use

```
python3 tools/replay/backfill_gps.py <context.db copy> --out <folder> [--emit-import] [--csv <file>] [--keep-scratch]   # adaptive windows
python3 tools/replay/backfill_gps.py <context.db copy> --out <folder> --window 2|5|10|15|30|60                       # the fixed window (legacy, for comparison)
tools/import-moment-locations.sh <folder>/moment-loc-import.tsv      # after: tools/m1-run.sh pair <host:port> <code> <fp8>
python3 tools/replay/backfill_gps.py --fixtures      # synthetic self-test, exits 0
```

- `--out` must be OUTSIDE any git tree (refused otherwise) and is created 0700. The db is first copied to `<out>/scratch/` and opened read-only from the copy (the dump has no `-wal`); the scratch copy is deleted at the end unless `--keep-scratch`.
- The CSV defaults to `~/Desktop/rokid/backups/trip-gps/trip_media_gps.csv`. Fix times come from the file names as local IST (`20261121_173418.jpg`, `img-20261121-192145-...`, `vid-20261121-...`). Rows with no coordinates are skipped.
- Moments: table `moment` (type FRAME or MARK). Time = earliest `moment_part.partTimeMs` of its FRAME/MARK parts.

## Adaptive rule (default; v18 build spec section 4)

For a Moment at `t`, `prev`/`next` are the bracketing media fixes, `d` the distance between them and `v = d / span`. Class, checked top-down: UNKNOWN (a bracket is missing, or span > 120 min; window 10 min), STATIONARY (d <= 150 m and v <= 0.3 m/s; 60 min), WALKING (v <= 2.5 m/s; 15 min), VEHICLE (5 min). INTERPOLATED when both brackets are inside the window, else NEAREST when the nearer one is, else blank. Gaps are inclusive at the edge.
`accuracyM = min(5000, ...)`: STATIONARY `15 + d`, WALKING `15 + max(v, 0.5) * gapS`, VEHICLE `15 + v * gapS`, UNKNOWN `15 + 1.4 * gapS` (`gapS` = seconds from `t` to the fix used; for INTERPOLATED the nearer bracket).
Output: `summary.md` (counts per class: candidates / interpolated / nearest / blank, and per day) and `proposals.tsv` (`momentId, lat, lon, source=MEDIA_BACKFILL, gapSeconds, speedClass, method`). The import TSV header is `momentId, atMs, anchorKind, anchorId, lat, lon, accuracyM, fixAgeMs, speedClass, method`; the anchor is the earliest FRAME part, else the earliest MARK part.
The phone refuses a row for a missing Moment or anchor, a forgotten (tombstoned) Moment, a point in or near a pause zone, a paused second, and never overwrites an existing row (first writer wins).

## Rule (fixed window, `--window`)

- Nearest media fix within N minutes (default 5; 2/5/10 allowed): source `media_exact_window`.
- Interpolation (`interpolated`) only if a fix lies <= 5 min away on each side (and within N), the two fixes are <= 200 m apart and the implied speed is <= 2 m/s. If those conditions fail, the nearest fix within N is used.
- Otherwise blank. Nothing is invented; no pin or site position is ever used.
- `ageSeconds` is the distance in time to the nearest fix used. Age buckets: <=1min, 1-2min, 2-5min, 5-10min.

## Output

- `summary.md`: counts only (Moments total, with proposal, blank, by source, by age bucket, by day).
- `proposals.tsv`: `momentId, lat, lon, source, ageSeconds`. It holds coordinates: keep it on the Mac, never commit or share it.

See also (the other direction): the read-only Developer dump `tools/pull_dump.py` pulls Moments, Scenes, marks, pins and their locations from the same paired session (`GET /api/dump/...`, Developer mode and a paired session, USB `adb forward`). Design: docs/superpowers/plans/handoffs/dump-api-2026-11-26.md.
