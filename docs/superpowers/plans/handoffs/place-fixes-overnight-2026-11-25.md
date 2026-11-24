# Place fixes overnight build, track A (sampler), 2026-11-25

## RESUME HERE
- Branch `place-fixes-A` (from `feat/v101-ask-eval`), worktree `agent-aa17a5cecb017fcab`. Spec: `2026-11-24-place-fixes-build-spec.md` (sections 0-6, 8).
- Rulings: pin-first floor 15 m; card history 90 days / 500 rows; no F6c.
- Replay bundle `~/Desktop/rokid/backups/replay-bundle-2026-11-24` (present, perms set); outputs `~/Desktop/rokid/backups/replay-out` (outside git).
- Gradle only via `scratchpad/run_wt.sh <worktree> <log> <args>`.
- Last completed step: H1-H5 (7a87aceb) and B1 real replay.
- B1 acceptance.tsv: ~/Desktop/rokid/backups/replay-out/run-2026-11-24T05-52-06/acceptance.tsv (outside git).
- B1 vs B0: HEAD totals unchanged (OUTDOOR_10M 17 cards, wrong 0; F1 overlay wrong 1; StubNone 28 calls 0 cards; INDOOR_60M and STALE HEAD 0 cards; calibration 5/6 TRUSTED). New INDOOR_MEDIA_GOOD HEAD baseline: 12 cards, 20 calls, wrong 0.
- S1 done: F7 fields on the attempt line, constructor seams, PlaceFrame motion/sessionId, movedSince, six DeveloperFlags (ON). Place + replay tests green.
- S2 done (F3 dedupe v2, F3 overlay deleted; replay runs dedupe v2 always, Historical stages run legacy dedupe because they feed NONE for every unrecorded frame). Place + replay tests green.
  - S2 replay (counts; run dir run-2026-11-24T06-08-30): wrong 0 in every HEAD run; calibration TRUSTED 5/6; INDOOR_60M, STALE, ASSUMED_PIN_60M 0 cards; NO_FRAME 169.
  - OUTDOOR_10M: SJ_ENTRY 1, SJ_BRONZE 1, SJ_IVORY 1, SJ_EXTERIOR 1, GOLCONDA_LS 1. AS_RECORDED SJ_EXTERIOR 1.
  - ASSUMED_PIN_10M: RAMOJI 6 cards (71 calls), ANTERA 4 cards (51 calls), GOLCONDA_LS 1. StubNone x OUTDOOR_10M: 35 calls (limit 42), 0 cards.
  - NOT MET at S2: OUTDOOR_10M SJ_IVORY >= 2 (got 1; B1 also 1; once-per-place suppression keeps the second Ivory card out; the spec's earlier 2 came from the F4 overlay, S3 may recover it). Report to controller.
- S3 done: PlaceSampler.retryRecent(PIN_CREATED|CELL_RETRY, pinId), PlaceGraph.pinCreated, PlacePinning.onCreated, HubGraph collector; pack-miss frames leave `handled`. F4 overlay deleted; replay calls the real retryRecent at each pin's creation (not in the legacy Historical runs). Place + replay tests green, phone compiles.
  - S3 replay (counts; run dir run-2026-11-24T06-2x, newest): wrong 0 in every HEAD run; calibration TRUSTED 5/6; INDOOR_60M/STALE 0 cards; StubNone x OUTDOOR_10M 37 calls (limit 42), 0 cards.
  - ASSUMED_PIN_10M: ANTERA_FACADE 1 card (the retry), ANTERA 4, RAMOJI 6, GOLCONDA_LS 1. OUTDOOR_10M: SJ_BRONZE 1, SJ_IVORY 1.
  - NOT MET: OUTDOOR_10M SJ_BRONZE >= 2 and SJ_IVORY >= 2. The spec's S2 once-per-place-per-30-min suppression applies to every card kind, so a second card for the same place inside a short span cannot appear; the numbers 4 and 2 came from the overlay before S2. S2 and S3 criteria contradict each other here; kept S2 (no card storm). Controller decision needed.
- Next step: S4 (F2 fix reuse).

## Step log (counts only, no names / coordinates / ids)
| Step | Status | Commit | Notes |
|---|---|---|---|
| H0 bundle | done (bundle already built, perms 700/600) | - | |
| H1-H5 | done, replay tests green | 7a87aceb | |
| B1 baseline | done | next commit | see resume section |
| S1 | | | |
| S2 | | | |
| S3 | | | |
| S4 | | | |
| S5 | | | |
| S6 | | | |
| S7 | | | |
