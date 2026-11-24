# backfill_gps.py: GPS backfill dry run

Dry-run planner only. It proposes a position for existing Moments from the media-fix CSV and writes a report. It does NOT write to a phone and has NO import code. Applying a plan needs the planned location-per-Moment table (Room v18, a later build, see `docs/superpowers/plans/2026-11-24-place-fixes-build-spec.md` section 7). Ruling: fill only what can be filled, never force, blank otherwise.

Python 3 stdlib only. No network, no adb, no photo is opened.

## Use

```
python3 tools/replay/backfill_gps.py <context.db copy> --out <folder> [--window 2|5|10] [--csv <file>] [--keep-scratch]
python3 tools/replay/backfill_gps.py --fixtures      # synthetic self-test, exits 0
```

- `--out` must be OUTSIDE any git tree (refused otherwise) and is created 0700. The db is first copied to `<out>/scratch/` and opened read-only from the copy (the dump has no `-wal`); the scratch copy is deleted at the end unless `--keep-scratch`.
- The CSV defaults to `~/Desktop/rokid/backups/trip-gps/trip_media_gps.csv`. Fix times come from the file names as local IST (`20261121_173418.jpg`, `img-20261121-192145-...`, `vid-20261121-...`). Rows with no coordinates are skipped.
- Moments: table `moment` (type FRAME or MARK). Time = earliest `moment_part.partTimeMs` of its FRAME/MARK parts.

## Rule

- Nearest media fix within N minutes (default 5; 2/5/10 allowed): source `media_exact_window`.
- Interpolation (`interpolated`) only if a fix lies <= 5 min away on each side (and within N), the two fixes are <= 200 m apart and the implied speed is <= 2 m/s. If those conditions fail, the nearest fix within N is used.
- Otherwise blank. Nothing is invented; no pin or site position is ever used.
- `ageSeconds` is the distance in time to the nearest fix used. Age buckets: <=1min, 1-2min, 2-5min, 5-10min.

## Output

- `summary.md`: counts only (Moments total, with proposal, blank, by source, by age bucket, by day).
- `proposals.tsv`: `momentId, lat, lon, source, ageSeconds`. It holds coordinates: keep it on the Mac, never commit or share it.
