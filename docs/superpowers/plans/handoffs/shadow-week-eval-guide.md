# Shadow week evaluation: how to read the report

Source: `ShadowEval.evaluate(ShadowEvalInput)` (`services/orchestration/.../proactive/ShadowEval.kt`), a pure, read-only function over the shadow store (records, counters, feedback, never-list size). `EvalReport.toText()` prints counts and fixed labels only; no suggestion text, place name, key or photo id. Bars come from the v3 spec section 4. Delivery switches stay OFF while you read this.

## Fetch the report (Developer only)

`GET /api/proactive/shadow-eval` returns the report as `text/plain`. It is read-only, needs Developer mode ON (403 `developer_mode_off` otherwise, same as the replay route; the replay flag is not needed), has no UI, and works with every delivery switch OFF.

From the laptop, after the shadow week:

1. Unlock the phone, Settings > Diagnostics > Developer mode ON, Settings > Experiment canvas > "Use from a laptop"; read host:port, code and fingerprint.
2. From the repo root: `tools/m1-run.sh pair <host:port> <code> <fp8>` (the code lasts 5 min).
3. `tools/m1-run.sh get /api/proactive/shadow-eval` prints the report.

Built from the real stores: would-show records, the cumulative counters, feedback, never-list size, and the stored pause history (quiet = a non-zone pause covering the record time, zone = a `zone:` pause). The store keeps only CUMULATIVE counters, so `runs` and `refused_by_gate` are the overall histogram since install or the last Delete all (the report says so in a `note:` line); day lines show would-show counts only. Driving and unworn keep no history, so they print `UNCHECKED` and the verdict is NOT ENOUGH DATA at best until a build records them. Day keys that are not ISO dates are dropped and counted (`invalid day keys: N`); a daily snapshot with more would-show than retained records is used for the cap check and marks `incomplete_days`.

## Lines

- `verdict=PASS | FAIL | NOT ENOUGH DATA`, then one `reason:` line per cause.
- `observed_days` (bar: 7), `runs`, `would_show` (cards that passed all gates and were only recorded).
- `max_would_show_in_a_day` against `cap` (bar: never above the cap).
- `refused_by_gate`: how often each gate stopped a run (cumulative counters; per-day only when daily counter snapshots are supplied). `budget_exhausted` = cap reached + global cap + family max + shown gap.
- `per_category`: would-show count per family (today only `f2`).
- `safety_leaks`: would-show records that fall in quiet hours, a zone, driving or unworn time. Must be 0. `UNCHECKED` means the caller had no history for that check, so the report cannot pass.
- `feedback`: dismissed, snoozed, never-list size, thumbs up/down; `rates` are those over the would-show total, `useful` = up / (up + down), bar 60% with at least 5 thumbs.
- `harmful_or_false_claim`: not measurable by code. Bar is 0; you check the would-show list by hand.
- `day <date>`: the same numbers per local day.

## What to do on each verdict

- **PASS**: all measurable bars held. Still do the hand check for harmful or false cards in the Developer list, then you may decide to switch delivery on, one surface at a time (phone first), master switch last. Nothing switches automatically.
- **FAIL**: do not switch delivery on. Read the reason. A safety leak (quiet, zone, driving, unworn) is a bug: stop, keep everything OFF, and report it with the day line. Over the cap or a low useful rate: leave in shadow, fix or tune (cap, ranker, generator), then run a fresh 7-day week.
- **NOT ENOUGH DATA**: keep shadowing. Typical reasons: fewer than 7 observed days (wear the glasses longer), fewer than 5 thumbs (rate the cards in the Developer list), or `UNCHECKED` invariants (the caller must supply quiet-hours, zone, driving and worn history). Re-run later; never read this as a pass.

Not covered here (separate eval tool): recall of seeded F1 events and p95 run time.
