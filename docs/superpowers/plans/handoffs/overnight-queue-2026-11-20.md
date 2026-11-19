# Overnight queue, 2026-11-20 (user asleep until about 10-11 am; trip 22 Nov)

Rules: no installs without the user's "go"; nothing here changes the staged candidate; Gradle one job at a time through the lock; Opus low-effort reviewer replaces Codex until 21 Nov 21:36; Rokid-Coder untouched; never push the feature branch.

## A. Candidate path (priority 1)
1. Pin agent fixes (4 P2 + day picker + pinnedAt) -> merge -> full verification on the final tip -> Opus re-review of the arming/cascade -> stage `apks-v110-cand-<sha>` (README, SHA256SUMS, mark `a2b85ca5` SUPERSEDED, handoff/runbook/checklist/quickref pointers) -> push the roadmap update to `v1.0` and `main`.

## B. Research and design docs (no Gradle, running in parallel)
2. Offline vision model exploration -> `plans/2026-11-20-offline-vision-model-exploration.md`.
3. Resolver + semantic ranking design -> `plans/2026-11-20-place-guide-resolver-and-semantic-ranking.md`.
4. Roaming data saver design -> `plans/2026-11-20-roaming-data-saver-design.md`.
5. Forget pipeline redesign -> `plans/2026-11-20-forget-pipeline-redesign.md` (written).

## C. Build slices on separate branches from the final candidate tip (NOT in the candidate; each: implement, tests, full verification, Opus review; merge to `feat/v101-ask-eval` only after the candidate is staged)
6. Data saver slice 1 (Wi-Fi only default for photo uploads, roaming detection, per-day cap, status card): needed before Sikkim 1 Dec.
7. 1.4.3 model resolver (online only, "offline only" setting present, per-backend cache, logging).
8. Activity pagination (v1.3 row): list + day/hour breakdown.
9. Forget pipeline step 1 (`ForgetSource`, `ForgetPlan`, policy table; behaviour unchanged).

## D. Waiting on the user (record, do not block)
Install "go" for the candidate; pin times for the 3 home pins; Detailed-log and candidate-id privacy nod; nightly photo-describe zone fence ruling; Room v18 not needed (pins are prefs); S2 half-day 7-10 Dec; MapTiler key (optional).

## E. After 21 Nov 21:36
Re-run Codex on the diffs only Opus reviewed (hub-wp3 fixes, zone fixes, wipe backoff, coordinate rule, map picker, logging, pin-to-Moment).
