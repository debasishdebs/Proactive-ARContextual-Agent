**1. Place parser hardening — no new P1/P2/P3 findings**

Reviewed git log and all three diffs. Coordinate checks reject non-finite and out-of-range values. `parseBody` catches only `StackOverflowError`; `OutOfMemoryError` propagates. Non-object roots throw `SerializationException`. Overpass failure preserves Wikipedia/Wikidata places and marks the cell partial; cancellation is rethrown and logs contain counts/classes only.

Person exclusion remains unchanged. Absolute “never” has an existing limitation: duplicate JSON keys use last-wins semantics, potentially hiding an earlier person classification.

**2. PMTiles / TileMath — no P1/P2/P3 findings**

`zoomFor(NaN, …)` returns `minZoom`; valid archive headers enforce ordered zoom bounds. Attribution catches `StackOverflowError` without catching `OutOfMemoryError`.

**3. Release gates — no P1/P2/P3 findings**

The extracted Boolean gates preserve debug/release results. Both skip-dedupe entry points pass `BuildConfig.DEBUG`, so release cannot honour that extra. Explicit user capture still forces capture, as before. `isProductionFlags` preserves the original flag expression and strict production Forget/cache-cleanup handling.

**4. Side-card layout — no P1/P2/P3 findings**

The CENTRE branch in `HudRoot` is byte-identical; default rendering arguments retain the previous values. Side cards compute to 168 dp width with 24 dp edge insets on a 240 dp display. Parent constraints and text line limits bound layout; empty text and odd source strings have no evident crash path. Rendering was not executed.

**5. ShadowEval — two P2 findings**

- **P2 — Unvalidated day keys reach report text.** [ShadowEval.kt:105](/Users/<user>/Desktop/rokid/proactive-context-agent/android-companion/services/orchestration/src/main/java/com/rokid/context/orchestration/proactive/ShadowEval.kt:105) merges arbitrary `dailyCounters` keys into `dayKeys`, then line 76 prints them verbatim. A key containing a place name or record key violates the counts-only guarantee. Parse keys as `LocalDate` and emit canonical dates; reject invalid keys.

- **P2 — Daily snapshots cannot expose cap violations.** [ShadowEval.kt:109](/Users/<user>/Desktop/rokid/proactive-context-agent/android-companion/services/orchestration/src/main/java/com/rokid/context/orchestration/proactive/ShadowEval.kt:109) assigns snapshot `would_show` to `passed`, while `wouldShow` always uses retained record count. A snapshot showing four cards with only one retained record reports one against the cap and can permit PASS. Use snapshot totals conservatively and mark incomplete safety history unchecked.

Missing predicates prevent PASS; predicates throwing `Exception` count as leaks. Record fields and unknown counter names otherwise stay out of report text.

Targeted tests could not start: the read-only sandbox blocked Gradle’s lock file. Verdict is based on static review; no P1 found.

READY