# tools/place-attempt-report.sh

Read-only analysis of a trip's place matching, from the content-free `place attempt ...` lines of PlaceSampler and the `pack lookup ...` lines of PlacePack. Prints counts, fixed tokens, ids and numbers only; never a log line, name, coordinate or path. bash 3.2 / macOS awk. Never writes to the phone.

```
tools/place-attempt-report.sh --phone <serial> [--date MM-DD | --since "MM-DD HH:MM" [--until "MM-DD HH:MM"]] [--pin-map pins.tsv]
tools/place-attempt-report.sh --file pca.log.3 pca.log.2 pca.log.1 pca.log --date 11-23     # OLDEST FIRST
tools/place-attempt-report.sh --fixtures
```

`--phone` lists `files/logs` (debuggable builds, `run-as`) and reads every segment oldest first (pca.log.59 ... pca.log.1, pca.log). With `--file`, give the saved segments in that same order; the longest-gap figure depends on it.

`--pin-map` takes a text file, one `id<TAB>label` per line (`wd:Q123`, `osm:...`, `user:<uuid>`). Labels are printed next to ids in section 3 on the laptop only. Keep the file off the phone and out of the repo.

## Sections
1. Attempts per clock hour by outcome (plus pack lookups by cell state and online).
2. Reason histogram for every non-MATCHED attempt (`outcome reason`).
3. Per candidate id: times offered, chosen, MATCHED with the card shown.
4. Calls: made / skipped_offline / skipped_not_admitted / none; callMs and totalMs p50, p95, max (nearest rank).
5. Confidence (`confPct`) histogram of chosen candidates.
6. DUPLICATE / RATE_LIMITED / SEEN / BUSY / NOT_ADMITTED counts, callGapS stats, longest gap without an attempt.
7. Cards shown / dropped / none, why dropped, reveal result.

## Reading a trip
- A day with many `NO_FIX`/`STALE_FIX` and few attempts: location, not matching, is the limit (section 1, 2).
- `skipped_offline` high with `NEARBY` cards: the day ran without data; those are "nearby, not checked", not identifications.
- An id offered often but never chosen: the pack holds a place the model never picks; check its pin label with `--pin-map`.
- `LOW_CONFIDENCE` clustered at 40-59 in section 5: the confidence threshold decides what shows.

## Limits of the log format
- The logger writes `cand=` (the task text said `cands=`); both are accepted but the count is not reported, only `ids`.
- `ids` is cut at 12 ids (`+N` tail): ids beyond the 12th are not counted as offered; the report says how often.
- No ContextHub card lines exist in the source today, so cards come only from `card=` / `cardWhy=` / `reveal=` on the attempt line.
- Offered ids are the candidate list after the cut, not the whole pack; ids carry no names, so without a pin map only `user:` pins and wd/osm ids can be told apart.
- The log has no year, so a gap across New Year is not measured; the gap is between consecutive attempt lines in file order, and a gap with no attempt at all (no frames) cannot be told from a stopped sampler.
- Place text, distances beyond buckets, coordinates and frame ids are not logged, so per-place accuracy needs the user's own memory of where they stood.
