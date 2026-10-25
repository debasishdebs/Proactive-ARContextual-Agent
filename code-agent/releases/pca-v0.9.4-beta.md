## v0.9.4-beta

Debug-signed; no APKs are attached. Phone database is Room v13 (additive migrations, one-way: back up before every `adb install -r`, never uninstall).

**Moments**
- A Moment can be born from a mark alone (`MomentType` FRAME or MARK); a Marks section in the Activity tab; mark undo, retag and Forget rebuild and clean up runs and Scenes.

**Capture expressions (3c, SHADOW/TRIAL only, LIVE refused)**
- Window signals (max, mean, frac over 1-300 s, full-horizon warm-up), window terms in guards, `expr.value` programs tuning the capture cap and gap seconds of editable rows, replay parity, glasses-side program limits, a phone try-it (`POST /api/expr/try`) and differential fuzz.

**Canvas**
- Summary node, Aggregate nodes (`agg.window`, `gate.compare`), a WATCH lane with `src.watch_motion`, a tunable `proc.motion_model`, a preview-only `out.set_frequency_preview`, built-in starters (`starter-moment-summary`, `starter-watch-motion`), composite cards for the glasses-lane nodes (capture frequency by activity, head movement, settle, novelty, rate cap, capture image).

**Reviews**
- Codex found Important defects in the Summary, mark-only Moment and E2a work (all fixed); canvas-only slices had no extra review.

**Known limitations**
- Not yet verified in a real browser or on a worn day; windows are strict (a missed second makes a window unavailable); the watch-run route is not wired; the derive step is off; the no-photo reason of a mark-only Moment is UNKNOWN.
