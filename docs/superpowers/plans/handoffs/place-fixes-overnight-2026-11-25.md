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
- Next step: S2 (F3 dedupe v2).

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
