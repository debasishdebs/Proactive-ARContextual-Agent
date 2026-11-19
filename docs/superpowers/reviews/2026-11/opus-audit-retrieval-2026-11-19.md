# Status audit of open Codex findings (r9 to r12, plus the 2026-11-19 batch)

Reviewer: Opus 5.5, standing in for Codex gpt-6.1-sol at low effort. Read-only, static review only (no Gradle, no devices).
Tree: `feat/v101-ask-eval` @ `ae6f1b01` (2026-11-19). No `codex-batch-r13+` file exists. Context: RQ1 (date windows, prompt rules, index fix) is on by default. RQ2 and T10 (the own-language probe) run only in the opt-in `dated-query` pipeline.

| # | Finding | Status | Evidence (current tree) |
|---|---|---|---|
| r9-1 | P1 "Show newer" duplicate-key crash after Forget | FIXED | `KeysetPager.kt:79-81` stops the prepended page before the retained window's first cursor (`takeWhile compare < 0`). `distinctById` (`:106`) dedupes every merge (`:61`, `:81`). Fix `3c8c6e8c`; no later commits. |
| r9-2 | P1 Sweep zone check lost while waiting for the model permit (Forget/privacy fence) | FIXED | `SuspectSweepWorker.kt:118` wraps the drain in `withContext(DispatchFence { zoneBlocked() })`. `KeyframeExtract.kt:65` wraps `FrameLiveness` in `ZoneFencedLiveness`, which checks the zone before and after `inner.gone` at dispatch (`PlaceSamplers.kt:49-53`). Fix `3c8c6e8c`; no later commits. |
| r10-1 | P1 Zone entry fail-open while prefs commit | FIXED | `ZoneController.protect` (`ZoneController.kt:124-131`): when `active`, `onState(true, judged)` runs BEFORE `saveProtect(true)`. Fix `e4fdf348`; later commits `c75c5f55` and `d9eb0f47` only add `zonesForCheck` and the `SuggestionGateEvents.closed()` hook. |
| r10-2 | P1 Stale startup read re-holds the Ask fence | FIXED | `AskZoneFence.kt`: every `protect` bumps `decision`, and `prepare` captures `gen = decision` and discards reads that began before the latest decision (doc at `:114-117`). Fix `e4fdf348`. |
| r11-1 | P1 Failed store of the clear flag locks Ask out indefinitely | FIXED | `ZoneController.kt:136-145`: with no zones to protect it releases at once, otherwise it releases after `CLEAR_STORE_RETRIES`=3 failed stores. `AskZoneFence.prepare` (`:121-127`) releases a held, judged, clear fence when there are no enabled zones. Fix `5a0a922c`. |
| r12-1 | P1 Probe scores (HYBRID/RRF vs BM25) discard the correct answer | FIXED | `RagCore.withProbe` (`RagCore.kt:469-482`, now in the companion object) scales probe `fused` and `score` by `k = firstTop/probeTop`, so the probe's best equals the primary's best before `OrderRules.relevant` (`:285`) applies `floor * top`. Raw `lexical`/`semantic` stay unscaled, so `WindowRelevance` is unaffected. Regression test: `RagCoreRelevanceTest.kt:125-136` (0.0328 vs 0.693). Fix `4c5ec4ce`; no later RagCore commits. Scope: opt-in `dated-query` only. |
| 1119-5a | P2 (privacy) ShadowEval prints unvalidated day keys | FIXED | `ShadowEval.kt:113-114`: a key that does not round-trip through `LocalDate.parse` is dropped and only counted (`invalidDayKeys`). Fix `a1c19578`; its message matches the finding. |
| 1119-5b | P2 (safety-eval correctness) daily snapshot hides cap violations | FIXED | `ShadowEval.kt:122-124`: `shown = max(records, snapshot would_show)` feeds the cap check. A larger snapshot sets `incompleteDays`, which blocks PASS (`:145`). Fix `a1c19578`. |

r9 to r12 contain no P2 findings.

## Residual notes (not findings)
- `withProbe` falls back to `k = 1` when either side's top score is 0. With no primary hits, the floor then applies to the probe's own scale. That is consistent, not a regression.
- If the primary mode is SEMANTIC (min-max floor), the scaled probe tail can widen the span. That only admits more candidates and never drops the primary top, so it is not a correctness risk.

## STILL OPEN (ranked)
None.

RETRIEVAL: ALL P1 CLOSED
