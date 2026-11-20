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
8. (already built, commits 99054e92/09ceb09b; only a worn check is pending) Activity pagination. Replaced by: coverage map of which Forget entry points already use ForgetPlanner/ForgetExecutor (read-only, feeds item 9).
9. Forget pipeline step 1 (`ForgetSource`, `ForgetPlan`, policy table; behaviour unchanged).

## D. Waiting on the user (record, do not block)
Install "go" for the candidate; pin times for the 3 home pins; Detailed-log and candidate-id privacy nod; nightly photo-describe zone fence ruling; Room v18 not needed (pins are prefs); S2 half-day 7-10 Dec; MapTiler key (optional).

## E. After 21 Nov 21:36
Re-run Codex on the diffs only Opus reviewed (hub-wp3 fixes, zone fixes, wipe backoff, coordinate rule, map picker, logging, pin-to-Moment).

## F. Decisions of the night (2026-11-20, user)
- Embeddings: KEEP the text embedder; ADD a CLIP-family image-text embedder as a second space (lazy, Forget-inventoried). Finalise embedding models (1.4.4, shadow mode first) BEFORE the offline VLM (1.4.5: Qwen3-VL-2B or SmolVLM2).
- Forget rework: ONE flow (source, selector, plan, one dialog, policy table), built after Sikkim.
- Pins are prefs JSON; Moment required for new pins; explicit Forget deletes the pin after a dialog naming it; expiry only de-links.
- Gradle jobs strictly sequential (lock); design jobs parallel; device-dependent work waits for the user or runs on the emulator; never stop on a blocked task, take the next one.
- Status at 01:45: pin fixes written (uncompiled, waiting for the lock), data saver slice 1 agent building, image embedder comparison running.

## G. Pin cascade review outcome (Opus, 2026-11-20 03:10, tip 4436d1a5): READY, no P1. Follow-ups (P2, low likelihood, all end in the SAFE direction: pins kept with a cleared link)
- P2-1: a fresh Delete all can overwrite an earlier wipe's UNCOMMITTED arming while that wipe is still pending (PinMomentLinks.kt ~197-199): carry `old.dead + old.pairs` into the new arming when the old arming is an uncommitted wipe and wipePending() is true. Needs: failed wipeCommitted disk write + crash + immediate second Delete all.
- P2-2: `forgetRangeOutcome` arms outside the DataOpGate (ForgetActions.kt ~227; the gate is only inside CanvasForgetCoordinator.forgetRange). No production caller today: gate it BEFORE any screen calls it.
- P2-3: DeletionEpochStore.commitWipe (~:37): when commit() returns false the in-memory pending marker stays true for the process; restore the previous value on failure.
Do these with the Forget pipeline redesign (or earlier if time allows).

## H. Pin follow-up round (fc3e41a4) review outcome (Opus, 03:40): READY, no P1. P2s logged, to do with the Forget redesign (both need a stack of failures and fail in the safe direction)
- P2-1: an abandoned wipe's uncommitted arming can be carried (`carried`, PinMomentLinks.kt ~103-106) into a later user Forget when `wipePending()` is true because of an EARLIER wipe W0 whose leftovers keep the flag set; the carried pairs are then deleted without the allowedPins filter. Fix: carry an uncommitted wipe arming's `pairs` only in the fresh-wipe branch (not in explicitStore), or bind the wipe arming to its epoch counter and promote it only when the pending value equals that counter.
- P2-2: the busy guard (`busy(op)`, :80 and :206) fires when an operation is in flight, not when it armed something: two overlapping explicit Forgets fail the second with PIN_PREP_FAILED (retry works); the code comment overstates it. Retention/sweeps do not count as in flight.

## I. Resolver round 5 review outcome (Opus, 06:30, tip 166d3fd4): READY, no P1. P2 follow-ups (none blocks an install; all rare)
- P2-a: quick double-tap can let an older choice reach disk last (ModelPrivacyStore.kt ~96-101: re-check `v != version` before EACH commit inside durable).
- P2-b: a write skipped because a newer tap replaced it returns true, so an older skipped write can clear `saveFailed` (ModelPrivacySettings.kt:87): skip onDone for skipped writes or pass the version.
- P2-c: FallbackParkCap is in memory; a background process killed between 15-minute queue parks never reaches the cap (old behaviour: parks forever). Persist the count with the job if it matters.
- P2-d: DeletionGraph.kt:104 text says `ai_backend_init` is one boolean; it also holds last_choice (a mode name).
- P2-e: tests: settle() after the cap and QueueDrainWorker's capped branch are untested; ModelPrivacyControlTest:125 should also assert !wasReset; fake DiskPrefs.apply with failWrites skips memory.
- Known open window by design: kill in the few ms between the tap and the first disk write reads the previous durable choice (closing it needs a synchronous side-file commit on main).

## J. P2 follow-up round (618a2778) review outcome (Opus, 07:25): READY, no P1
- New P2 (rare, safe direction but breaks a promise): Delete all commits its epoch, `wipeCommitted` fails (pinsCommitted=false, wipe stays pending); in the SAME process, before a restart, the user Forgets a Moment that has a pin: `explicitStore` overwrites the uncommitted wipe arming (carryWipe=false, PinMomentLinks.kt ~:84, resolveStore keeps it at :177), the Forget disarms, the next resume arms nothing and the pins Delete all promised to delete survive with cleared links. Also makes the class-doc row at :50 untrue in that case. Fix: in explicitStore, when store.arming() is an uncommitted wipe arming while wipePending() is true and pairs are non-empty, throw PinArmFailed ('try again') instead of overwriting, or run MemoryWipe.resumeIfPending first.
- P3: an older write that passed its last version check can briefly clear 'Saving' / set saveFailed (cosmetic); a throwing pushSettings inside `guarded` is also logged as 'deferred reason=no_link'; pre-existing: the glasses hello handler ignores pushSettings()'s return (glasses-side fail-closed covers it).
- Verified by trace: after a glasses reconnect the glasses get the CURRENT zone/pause state even if the phone never moves (the zone PauseRecord is stored without the link; Hub hello calls pushSettings which reads pauses.active() and zoneApproach()/zoneEnforce() live; publishPending retries on every onFix; hello cannot resume a pause created while disconnected). Geocoder gate: ledger count right, no other Geocoder caller, ConnectorsModuleRulesTest passes by reading the rule.
