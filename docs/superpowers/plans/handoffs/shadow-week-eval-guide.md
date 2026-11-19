# Shadow week evaluation: how to read the report

Source: `ShadowEval.evaluate(ShadowEvalInput)` (`services/orchestration/.../proactive/ShadowEval.kt`), a pure, read-only function over the shadow store (records, counters, feedback, never-list size). `EvalReport.toText()` prints counts and fixed labels only; no suggestion text, place name, key or photo id. Bars come from the v3 spec section 4. Delivery switches stay OFF while you read this.

Existing Developer-only surface: `POST /api/proactive/replay` and the Developer shadow list already expose the counters; the report function is not yet wired to them (add a one-line `ShadowEval.evaluate(...)` call there when wanted).

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
