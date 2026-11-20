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

## K. Shadow scaffold (1.4.4 slice A) review outcome (Opus, 07:40, tip 90357edf): READY, no P1; flag `placeShadowScore` default OFF, no behaviour change when off
- P2 (tiny window): SharedPhoto.clear() runs outside the Mutex (PlaceSamplers.kt:72-78): a decoded photo can stay held in memory after clear until the next get/clear (never written anywhere): put check-and-set in synchronized or let clear() take the lock.
- P2: SharedPhoto is shared across attempts; attempt 2's finally clear() can wipe state attempt 1's shadow still uses: safe only because MIN_CALL_GAP_MS (20 s) >> SHADOW_BUDGET_MS (2.5 s): pin the relation with an init require/comment.
- P2: stale KDoc at PlaceSampler.kt:168-169 ('one attempt runs at a time ... a field is enough').
- Missing tests: 'first loader cancelled, second loads itself'; second poke during settle ends RATE_LIMITED (test asserts only not BUSY).
- Slice B still to do: wire pins' reference-Moment photos into the image channel (imageRefs defaults empty), SigLIP 2 base as a second embedding space (6.5-9 agent-days), thresholds from a real eval on the user's pins; text embeds currently use the local llama.cpp/Nomic text path only when Nomic is bound.

## L. Slice B1 (pin reference vectors in the shadow) review outcome (Opus, 08:00, tip 879cce7a): READY, no P1; flag-off nothing runs; shadow only. P2s to fix before any non-shadow use (B2 / SigLIP):
- store-after-wipe race (PinReferenceVectors.kt:161-165): inside the lock `if (generation() != gen) return null` (a stale-gen vector can be stored and also clear newer entries; never served, cleared on the next revalidate).
- cache-hit gap (:122-124): a single-Moment Forget between momentFrame and the hit can let one log line carry a score from a just-forgotten photo (live() covers wipes, not single Forget): microseconds, log-only.
- Delete all clears the cache LAZILY (next attempt): add an eager clear from MemoryWipe/Forget through a small registry and list the in-memory vectors in the Forget inventory before any non-shadow use; flag ON then OFF leaves vectors in a long-lived hub process (unreachable, never scored).
- the embed job (:157) does not check live() (ticket/pipeline): after the attempt closed or the pipeline paused it can still read the stored photo: pass a pipeline-on gate.
- doc wording: 'or the file is gone' (production momentFrame uses requirePresent=false: a retention run deleting the file but keeping the row leaves the vector servable); 'with the flag off nothing is created' (PinReferenceVectors is allocated at sampler build, no I/O).
- mixed pin+pack attempts: shCh=image drops pack places from ranking (agreement skewed near a pin): log a mixed marker (dropped count) or analyse separately.
- per-pin parallel revalidate (pins x 16 reads per attempt): revalidate once per score. Missing tests: Forget DURING the embed, cached vector dropped on generation change before anything else is asked.

## M. B1 follow-ups review outcome (Opus, 08:25, tip 14a3ffa6): READY, no P1; no lock-order deadlock (clearAll takes two leaf monitors), no inventory test loosened. Small P2s (do with the next code round; not blocking):
- Delete all clears the caches TOO EARLY (MemoryWipe.kt:100 clearAll runs before the generation bump at :125): a shadow in that window can store a photo-derived vector that stays in memory until ForgetListeners.deleteAll() (:193, not in a finally). Never served, but add a second `ShadowCaches.clearAll()` right after `generation.incrementAndGet()` (or in the finally).
- ShadowCaches.epochValue++ is a @Volatile read-modify-write (only tests read it): use AtomicLong or delete.
- ShadowCachesTest.everyDeletionPathCallsTheRegistry counts the string 'ShadowCaches.clearAll()' (a comment satisfies it): brittle tripwire; the listener/registry tests carry the real coverage.

## N. Data saver slice 2 review outcome (Opus, 08:55, tip eac23a5c): READY, no P1; default users uncapped by construction (DailyCaps.limitFor null on FREE/OFFLINE/HOME_MOBILE/RESTRICTED unless the user's own limit is on); Send once cannot leak (structural withContext + USER class + photos). P2s (fix round dispatched, before Sri Lanka):
1. Failed calls use up the photo cap (ModelRouter.kt:258-259 counts size+photos on any non-cancellation failure even if nothing left the phone): count photos only on success / bytes after connect.
2. A success can be double-counted if dataGate.sent throws inside the try (move out of the try).
3. DataSaverGraph.sent classifies the network when the call ENDS (call that starts on mobile and ends on Wi-Fi uncounted; LAN endpoint over metered/UNKNOWN Wi-Fi counted as mobile).
4. Midnight watch is one fixed delay with no TIME_SET/TIMEZONE_CHANGED listener (30-min pipeline recheck covers it); flying west extends the cap day, east resets early; no DailyCaps zone-change test.
5. Send-once dialog race (two quick taps drop the first describe); photoHeldForPrompt uses plain decide not the debounced heldBack (wording 'Send over mobile data?' during the Wi-Fi reconnect blip).
6. No end-to-end SendOnce test (ViewModel -> DescribeOnDemand -> extractNow -> router); 'once' covers every USER photo call inside one describe (document).
Nits: BULK_OTHER counter always 0 vs PRIVACY s15 'per kind'; text bytes count toward the byte cap (say so in PRIVACY).

## O. Fix round for sections M and N (Sonnet, 2026-11-20)
- N1-N3 done in ModelRouter.send: photos count on success only, request bytes of a failure only for an HTTP error status (the server received it), class read once at call start (`DataGate.sentAt`), only REMOTE endpoints counted, a throwing ledger can no longer discard a reply or count twice.
- N4 done: `DataSaverGraph.onClockChanged()` (fed by DiagProbe's TIME_CHANGED/TIMEZONE_CHANGED receiver) restarts the midnight wait; west/east/DST tests added.
- N5 done: synchronous in-flight flag in PhotoSearchViewModel; prompt uses `heldBackForPrompt()` (debounced, not Offline only).
- N6 done: end-to-end SendOnce test through DescribeOnDemand/extractNow/router; 'once' covers every USER photo call of one describe (documented in code and PRIVACY s15).
- Nits done: BULK_OTHER wording fixed in PRIVACY s15 (bulk jobs are counted as text); text bytes count toward the byte cap (PRIVACY).
- M done: second `ShadowCaches.clearAll()` after the generation bump (behaviour test in MemoryWipeTest), epoch is an AtomicLong, source scan ignores comments and demands 2 calls in MemoryWipe.
- K and L leftovers: already closed in code (SharedPhoto lock, SHADOW_BUDGET require, store-after-wipe, live() gate, stillValid, mixed marker); not re-touched.

## O2. Counting-fix round (c2609926) review outcome (Opus, 09:55): READY, no P1
- P2 (safe direction, rare): loopback (`localhost`, 127.x, ::1) is classed LAN by DataPolicy.isLanHost (core/model DataSaver.kt:243,249), so with `counted = reachOf(e) != DEVICE` an Ollama/OpenAI-compatible endpoint on the phone itself (Termux at 127.0.0.1) counts as mobile data when the phone is on mobile and can trip the daily byte limit. Fix: treat loopback as not counted in send() (or a DEVICE-like reach for loopback) and fix the PRIVACY wording ('everything but a model on the phone is counted').
- Nits: the test 'anOnDeviceModelIsNeverCountedNorEvenAskedForTheNetworkClass' never checks costlyNow was not called (fake has no counter); a VPN that falsely reports Wi-Fi while riding cellular is undercounted (old behaviour, document it); an IOException while reading a 2xx body counts nothing (slight undercount).

## P. Loopback + O5/O6 review outcome (Opus, 10:50, tip fea96193): READY, no P1
- Loopback: no remote host can be classified loopback (localhost.evil.com, 127.0.0.1.evil.com, 127.1, 0x7f.1, ::ffff:127.0.0.1, userinfo tricks all counted as remote). Doc P2: a local forwarder on the phone (Termux ssh -L / socat to a remote Ollama) is trusted as on-phone and uncounted: PRIVACY now says so (done).
- O6 correct: glasses/voice/phone mark undo only de-links pins (empty pin target, nothing armed; the post-commit sweep clears the link; a pin whose Moment is gone is never deleted later). Side note to check later: MARK_BODY says 'It has no photo' yet removeMark forgets a photo that arrived late.
- O5: no deadlock. P2 (UX): pruneGated runs while holding the CanvasRunner lock (CanvasRunner.kt:193-196, called ~369/440/487/535/590): a run start can block up to 30 s (not only during an export: also any Save or photo upload), during which a second start says 'A run is already active.' and new uploads are held; after the wait the run starts with the prune skipped. Fix: prune outside the runner lock or try-lock with a short wait. P2 (cosmetic): the prune log says 'export in progress' and the 409 says 'An export or save is running' though UploadInProgress/WriteInProgress hit the same catch.

## Q. Whole-train audit outcome (Opus, 11:30; doc: handoffs/train-audit-2026-11-20.md). Done now: rollback warning in tools/install-trip-build.sh guidance + runbook section 14 (P1-1, P2-1), PRIVACY Geocoder contradiction removed (P3-1).
Code follow-ups, no ruling needed (fix agent dispatched): P2-3 count Place cell fetches and map tiles in the byte ledger and make street-map tiles cache-only on costly networks (design table says so); P3-3 DiagProbe lifecycle race + main-thread receivers + onTimeChange building the graph on main; P3-4 ModelPrivacyStore load off main; P3-8 UserPlace.pinnedAt KDoc wrong ('kept in the export': it is stripped); P3-9 model downloads on a local foreign SIM while 'I'm abroad' is ON (consult the abroad switch); P3-11 DiagProbe.watchWork collects all WorkInfos (CPU).
Rulings needed from the user (needs-user section K): (a) should Delete all clear pinnedAt on kept pins? (b) P2-2: should Android Data Saver RESTRICTED block photos when PCA runs a foreground service? (c) P3-2: Offline only still sends a DNS query for a .local name before refusing (hostname only): accept or classify first; (d) P3-7: every existing pin shows 'Needs a Moment' after the upgrade: accepted? (e) P3-10: dual-SIM/eSIM can make home look like roaming; (f) the hash-the-ids decision (PRIVACY open) before Sri Lanka and a Detailed-log retention decision (default ON, 300 MB, ~2 weeks).

## R. SigLIP 2 milestones built on the branch (not wired; flag-free; no production flow uses them)
- T (tokenizer, 10de9055, merged): pure Kotlin SentencePiece BPE; 80/80 exact id parity with HF transformers 5.23.0 GemmaTokenizer; tokenizer.model sha256 61a7b147390c64585d6c3543dd6fc636906c9af3865a5548f27f31aee1d4c8e2 (pinned commit 75de2d55...).
- V (vision tower, 69b38c23): SigLipImageEmbedder + SigLipPreprocess (PIL bilinear fixed-point port, bit-exact vs HF), OrtImageSession outputName (default unchanged for Nomic; test proves identical), parity vs python onnxruntime cosine >= 0.9999999999999986, model sha256 5f2b401c1a4fc095702a5d45348e17ad46c4f87064085365b43c6e8eaa5c0070 (94,553,333 bytes, commit ba1f3b08...). FINDING: the int8 model quantises activations per tensor, so an image in a batch of 8 differs from the same image alone (cosine 0.975-0.996): keep batchSize = 1 or re-baseline thresholds. Output 0 (last_hidden_state) vs the reference is cosine 0.0099: pooler_output by name is REQUIRED.
- Human must verify: re-hash the two files, device load test on ORT-android 1.31 arm64 (ConvInteger/MatMulInteger), real latency/PSS, licence NOTICE (onnx-community conversion repo has no licence field; upstream Apache-2.0), batchSize decision.

## S. Flake watch: DiagnosticsViewModelTest.aDeletedSuspectLeavesTheOpenScreenAtOnce failed ONCE in the full phone suite at 66e8af7d (`no snippet of the deleted memory is left`, DiagnosticsViewModelTest.kt:148) and passed 3/3 alone; the code under it (ai module) was not touched by that commit. The test waits for `suspects.total == 0` then asserts `items.isEmpty()` as two separate facts: either a real transient inconsistency between the total and the items flows under load (then a product race), or a test race. Investigate (needs a look at suspectDescriptionsOf / keyframeRows combine); other flakes seen and fixed: PushToTalkTest, EmbeddersTest.
