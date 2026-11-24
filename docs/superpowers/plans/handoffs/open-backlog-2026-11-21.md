# Open backlog — 2026-11-21

Branch feat/v101-ask-eval. Docs-only; built from overnight-queue-2026-11-20.md (G..X), needs-user-2026-11-19.md (I-K),
train-audit-2026-11-20.md, combined-flows-audit-2026-11-20.md, forget-coverage-map. Each item checked against HEAD
with grep; FIXED items are listed at the bottom, not in the groups. UNVERIFIED = could not confirm from code by grep.

Builds: cand = cand-4436d1a5, t12 = train12-046bc165, HEAD = HEAD-only (not in any staged build).

## Group A — before Charminar/Ramoji (22-23 Nov), if installing cand or t12
Nothing blocks either build: every review ended READY with no P1. Known open in the CONSERVATIVE `cand` (fixed in `t12`, not in `cand`): G-P2-1/2/3 and H-P2-1/2 (pin arming corners; rare, they fail in the safe direction: pins kept with a cleared link), the Diagnostics total-0-beside-a-deleted-snippet display race (S), a cited photo staying on an open preview until reopen and a forgotten memory's tags during reload (U): display only. Open in EVERY build: J-P2 (below, group C, small fix).
Recommendation: install `cand` for 22-23 Nov (smallest change from the installed build); the pin/display items above are low-risk there. If you prefer the stronger build, `t12` carries those fixes plus the data saver and Model privacy (unverified on a device).

## Group B — before Sikkim (1 Dec)
- NU-I data saver answers (limits, SIM, hotspot/VPN) and the Android-Data-Saver-vs-foreground-service ruling (NU-K-b): decide before roaming matters; install the train (t12 or newer) at least a day before 1 Dec so mobile data is exercised.
- Device checks of everything unverified (post-install-check, pin editor, data saver status line, log volume, battery with Detailed log).
- Forget rulings O1-O7 (needs-user J) before the Forget pipeline build (after Sikkim).

## Group C — before Sri Lanka (23 Dec)
- J-P2 (OPEN, all builds): explicitStore overwrites an uncommitted wipe arming while a wipe is pending: refuse with 'try again' or resume the wipe first. S, no ruling.
- CF-P3-4 (UNVERIFIED): Home suggestion feed can show a card from a forgotten source up to 10 s (merge `db.tableChanges("memory","frame")` into SuggestionFeed triggers). S.
- Hash the logged place ids (NU-K-f) and decide the Detailed-log default/retention.
- 'I'm abroad' switch must be turned on before travelling (roaming cannot be tested at home); SIM/eSIM behaviour (NU-K-e).
- SigLIP 2 eval on real photos + device load test (R-1) if you want the semantic ranking before Sri Lanka; offline VLM redo on a quiet machine or the phone (X-1).

## Group D — later
- All P3/test-gap/doc items in the raw rows below (J-P3, O2-nits, L-perf, W-1 flake watch, R-2 licence NOTICE, R-3 batchSize, V-1 SigLIP shipping steps, S/T nits, X-1).
- Forget pipeline wiring (design 2026-11-20-forget-pipeline-redesign.md) after Sikkim; Codex re-review of all Opus-only diffs after 21 Nov 21:36 (package: codex-rereview-package-2026-11-21.md).

## Raw rows (per queue section, appended while processing)

### G (pin cascade, tip 4436d1a5)
- G-P2-1/2/3: FIXED at fc3e41a4 (carry pending wipe pairs, range Forget armed inside the gate, epoch store restored on failed commit). Present in cand only (fc3e41a4 is after 4436d1a5); not in t12/HEAD. Rare, safe direction (pins kept with a cleared link); G-P2-2 has no production caller.

### H (fc3e41a4)
- H-P2-1/2: FIXED at c867d709 (user Forget never carries a wipe's uncommitted pairs; busy guard checks armed state). Present in cand only.

### I (resolver round 5)
- I-P2-a/b: FIXED 166840d4. I-P2-d/e: FIXED 96f3e0d2. I-P2-c (park cap in memory): accepted with a documented rationale in 96f3e0d2 -> D, not a defect. cand-4436d1a5 predates the whole Model privacy/resolver line (166d3fd4 etc. is after it).
- I-known: kill in the ms between tap and first disk write reads the previous choice: by design, D.

### J (618a2778)
- J-P2 OPEN (all builds): explicitStore (PinMomentLinks.kt:84-88) still overwrites an uncommitted wipe arming while wipePending() is true (resolveStore keeps it at :182, then store.arm at :88 with carryWipe=false replaces it). Class privacy (Delete all promise broken: pins survive with cleared links). Scenario: Delete all, wipeCommitted disk write fails, same process the user Forgets a pinned Moment. Fix: in explicitStore throw PinArmFailed when store.arming() is kind=wipe, !committed, wipePending() and pairs non-empty (or run MemoryWipe.resumeIfPending first). Effort S. No ruling. Group C.
- J-P3: older write briefly clears 'Saving'/sets saveFailed (cosmetic); throwing pushSettings logged as 'deferred reason=no_link'; glasses hello ignores pushSettings() return. UNVERIFIED whether touched by 166840d4 (superseded writes report nothing: likely fixes the first). Group D.

### K (shadow scaffold)
- K P2s (SharedPhoto clear under lock, SHADOW_BUDGET relation, KDoc, tests): FIXED 14a3ffa6. Flag placeShadowScore default OFF; not in cand.
- K slice B (SigLIP as a second space, thresholds from a real eval): feature work, D.

### L (B1 shadow)
- store-after-wipe, cache-hit gap, eager clear (80b92425), live() gate on embed, mixed marker, doc wording, missing tests (PinReferenceVectorsTest:193-214): FIXED (queue O line 93 + grep).
- L-perf: per-pin parallel revalidate (pins x 16 reads per attempt) -> 'revalidate once per score'. UNVERIFIED (revalidate still called at PinReferenceVectors.kt:75 per get). Class cost (battery), flag OFF by default so no build affected. S. Group D.

### M (B1 follow-ups)
- M-1/2/3: FIXED in the O round (second ShadowCaches.clearAll after the bump, AtomicLong epoch, scan ignores comments). Shadow flag OFF; not in cand.

### N (data saver slice 2)
- N1-N6 + nits: FIXED (O round: 4cdce532, 2abb4a76). Data saver not in cand (cand predates it).

### O2 (counting fix)
- O2-P2 loopback counted as mobile: FIXED 7cc56874.
- O2-nits: test does not assert costlyNow was not called; VPN that reports Wi-Fi undercounts (document); IOException on a 2xx body counts nothing. UNVERIFIED (not grepped). test-gap/doc, P3, S. Group D.

### P (loopback + O5/O6)
- P-P2 pruneGated under the CanvasRunner lock, and the honest prune/409 copy: FIXED ccfb0b62.
- P-side: MARK_BODY says 'It has no photo' yet removeMark forgets a late photo. Per ccfb0b62 'mark-forget copy' likely FIXED; grep for the string returns nothing (listed at the end of this file if found). UNVERIFIED.

### Q (train audit; see also TA rows below)
- Q P2-3, P3-3, P3-4, P3-8 KDoc, P3-9, P3-11: FIXED (840ac900, a61f5d59, 379fff33, af67ff9f, 8c15461e). All after cand: in t12.
- Q rulings: see needs-user K rows (NU-K-a..f).

### R / V (SigLIP 2 milestones T, V, X, Y; unwired, HEAD has X/Y only after t12)
- R-1 DEV: re-hash model files + tokenizer.model; device load test on ORT-android 1.31 arm64 (ConvInteger/MatMulInteger, ~380 MB, release frees memory); real latency/PSS. device-only. Group D (post-trip, no build uses it).
- R-2 RULING: licence NOTICE (conversion repo has no licence field; upstream Apache-2.0). doc. Group D.
- R-3: batchSize decision (int8 per-tensor activations: batch changes vectors, cos 0.975-0.996): keep batchSize=1. S. Group D.
- V-1: remaining to ship (catalog + downloader, second EmbeddingSpec, lazy embedding, thresholds from eval, promote shadow): L, needs the user's go. Group D.

### S / T (diagnostics suspect flake)
- S: real product race, FIXED 777b8ffa; T-P2 (more suspects than shown) and nits FIXED 3f8c34f7. In t12 and HEAD, not in cand (cand can show total=0 beside a deleted snippet for one emission: UX, display only).

### U (combined-flows audit)
- U-P2-1, U-P2-2, U-P3 (Activity/Day flash, MemoryDetail framePath): FIXED 046bc165 (t12). Present in cand: a retention-expired cited photo stays on an open preview until reopen; forgotten memory's tags shown during reload. privacy (display only), low.
- CF-P3-3: Diagnostics KeyframeStats frame_meta-only changes leave counts stale (numbers only). P3, D, no fix needed.
- CF-P3-4 OPEN UNVERIFIED: SuggestionFeed.kt:75 triggers = store.changes + 10 s tick + switches + gate events; no memory/frame table watch: a Home card from a forgotten source may stay up to 10 s. privacy (display), P3. Fix: merge db.tableChanges("memory","frame") into triggers. S. Group C.

### W (flake watch 2)
- W-1: orchestration unit tests stalled once at the 10-min cap (suspects DataOpGateTest, EmbeddingWorkerTest). test-gap, P3. If it recurs, jstack at minute 5. S. Group D.

### X (offline VLM bake-off)
- X-1: inconclusive; redo on a quiet Mac or the phone, score the answer token not the model's confidence. needs NU-I go/no-go. M. Group D.


## J-P2 / CF-P3-4 outcome (295c7892, 2026-11-22 03:40)
- Fix in 295c7892. Full verification: 14 of 15 rc=0; `:phone:testDebugUnitTest` rc=1 after a 5h16m run under memory pressure (AppShellTest idle timeout, PhoneLogSinkTest, FramesToPyFnIntegrationTest). Re-run alone: AppShellTest, PhoneLogSinkTest, FramesToPyFnIntegrationTest pass (rc=0, 32 s). Treated as load flakes (flake watch W); a clean full re-run is still owed.
- Liveness (traced by me; Opus reviewer stalled): the refusal lasts while the epoch store says a wipe is pending, i.e. until the next start (`resumeIfPending` completes it) or another Delete all; it is not permanent. Opus review of this diff still owed (Codex after 21:36 on 21 Nov has passed: use the re-review package).

## Codex re-review results (24 Nov 2026; Codex back; reports in docs/superpowers/reviews/2026-11/)
- pin (codex-pin-r1.md): NOT READY, 5 P1 + P3 -> FIXED in bf733c05 (Sonnet); verification + Opus review in progress. Open: multi-select Forget (forgetSelectionOutcome) is not bound to confirmed pin ids (check names vs arming).
- mark (codex-mark-r1.md): NOT READY. P1-1 CanvasRunner.kt:200 prune can delete a just-started run when the phone clock goes backwards (CanvasDao.kt:227 orders by startedAt): protect new/active run ids in the prune selection. P1-2 MarkGraph.kt:28 CACHE_CLEANUP_FAILED is turned into DONE by `.committed`, hiding the cache-left notice while exported text survives: keep the outcome. P3 UploadInProgress shown as "A save is running". Not fixed.
- mp (codex-mp-r1.md): NOT READY. P1-1 PlaceHttp.kt:71 a cell fetch/seed started in Automatic keeps sending (Overpass sends the bounding box) after the user switches to Offline only: re-check the privacy mode per request. P1-2 OfflineOnlyNet.kt:48 a private-address model behind an Android VPN is treated as LAN and the DIRECT route is accepted: content can follow the VPN. P2 embedders read the key before the guard; FallbackParkCap restart loop; KeyframePipeline drops fallbackUnavailable. P3 DataSaverGraph main-thread init; .local DNS before refusal. Not fixed. Model privacy is a feature the user has not enabled.
- Not yet re-reviewed by Codex: map+log, ds1-3, shadow/siglip/diag, tools/zonedefer/forgetpolicy, and the older list in codex-rereview-package section 3.

## Retention v3 round 3 (24 Nov 2026): P1-6, pre-existing, not coded
- RV3-R2-P1-6 (Codex v3 r2; P2, pre-existing): `PipelineRecords.dropFactsFrom` (PipelineRecords.kt:172, called from QueueDrainWorker.kt:271 and :395) deletes a job's trailing facts and captions (`<prefix>n..`, `<prefix>c<n>..`) and their `frame_source` rows when a retry of the same job returns FEWER facts than an earlier attempt committed, with no Forget. Scenario: "retry returning fewer facts deletes trailing facts without a Forget": attempt 1 commits facts 0..4, attempt 2 (queue retry or Remember retry) extracts 3, facts 3 and 4 are deleted.
- Verified pre-existing: the supersede was introduced in 92e8a943 (15 Oct 2026, "Fix M5", idempotent re-extraction), the prefix cleanup in bc89b39b/d6cebb29 (13 Oct). 18c1b76a (the installed phone build, 19 Nov) is a descendant and contains the function and both call sites (`git grep dropFactsFrom 18c1b76a`). Retention v3 did not change it.
- Decision 24 Nov: extraction semantics are NOT changed now. Proposed fix: keep the trailing facts and mark them superseded (a state recall ignores; only an explicit Forget or the retention rules delete them) instead of deleting; test that a retry with fewer facts keeps every committed row. Severity P2, pre-existing, effort S-M.

## mp-r3 (24 Nov 2026): FallbackParkCap restart persistence, deliberately not built
- codex-mp-r2 P2: `FallbackParkCap` counts parks in memory only, so repeated process death before the sixth park permits indefinite parking of a reachable-but-always-failing fallback box.
- Why left: the keys are frame ids, queue job ids and cluster ids, so a persisted count is a per-id keyed map (a new prefs file: a DeletionGraph inventory entry, a Delete-all wipe test, a BackupRules exclusion, pruning by id), not a single scalar in an existing file. A keyless "park attempts" scalar cannot say which job to release, so it would not bound the harm per job. The failure is in the safe direction (work parks, nothing is sent, no attempt is used) and needs a box that is up yet always failing plus repeated process death within about an hour and a half.
- If wanted later: one prefs file `fallback_parks` (id hash -> count, capped at 2,000 entries, pruned with the queue), listed in DeletionGraph and excluded in the backup rules, with the three tests.

## False-DONE reporting gaps for photo-file cleanup after a photo Forget (pre-existing in 18c1b76a: Forget always reported DONE; the FrameSweep tombstone sweep still deletes the JPEG at the next start)
Source: codex-train16-recheck.md findings 2-5 (24 Nov 2026), static review of bb49cb5b; finding 1 (the `mark_cleanup_owed:` key surviving Delete all, no pruning) was fixed on branch mark-r4. Severity of all four: P2 (user-facing message only; the photo row, tombstone and outbox are committed and the file is removed by the FrameSweep tombstone sweep at the next start or retention pass). NOT coded.
- F2 (ForgetActions.kt:258, concurrent range Forgets): `rangeFilesLeft` is one global value. `DataOpGate.kt:11` permits concurrent deletes, so range Forget A can finish its file cleanup and overwrite the flag that range Forget B just set (B's JPEG still on disk); B then reports DONE. Proposed fix: carry the file-cleanup status in each range result (a field of the per-call result object), never in a shared global; the caller reads only its own result.
- F3 (ForgetService.kt:77, repeated photo Forget): the first photo Forget removes the row and tombstones it but cannot delete the JPEG (e.g. file locked). Repeating the same Forget finds no row, returns an empty file list and reports DONE while the JPEG remains. Proposed fix: when the frame is already gone, recover the outstanding paths for that frame id from its cleanup obligation (the tombstone / `cleanup_outbox` PHONE_FILE row) and run the same file check before returning DONE.
- F4 (ForgetService.kt:250 and v2 lifecycle/ForgetExecutor.kt:255, fact Forget): a fact Forget lists only the JPEGs of its cascade, not their sidecars; the sidecar survives while `applyFull` reports clean. Proposed fix: include sidecar paths in both cascade results (v1 ForgetService and v2 ForgetExecutor) and in the cleanup outbox entries, so `applyFull` checks them like JPEGs.
- F5 (MarkRecorder.kt:184, undo): process death after the photo Forget commits but before `putMeta(mark_cleanup_owed:...)` loses the obligation; the retry sees neither the label (already deleted) nor the owed key and reports DONE with files remaining. Proposed fix: persist the obligation atomically with the photo cascade (write the owed key inside the Forget transaction, or derive it from the tombstone + outbox row on retry) instead of in a separate later write.

## Design backlog (data saver r3, 2026-11-24): storage backpressure for parked photos

Round 2 added a free-space guard (no new frames below 1 GB while photos are parked). Codex r3 P1-4/P2-5 showed it was unsafe and it was removed (parked photos are still never shed or deleted; the "N photos waiting for Wi-Fi, X MB" line stays). Design to revisit, NOT code: backpressure needs (a) a retryable receipt/job for phone captures, so a refused capture is re-indexed when space or Wi-Fi returns (today legacy archive files are not covered by v2 receipt reconciliation), (b) pressure applied BEFORE receipt writes so the glasses stop acknowledging/writing instead of the inbox rejecting after JPEG storage, and (c) a visible pending status on the capture itself, not only in Data saver settings.
