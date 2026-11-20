# Codex re-review package, 2026-11-21 (for after the Codex reset, 21 Nov 21:36)

Written 2026-11-20 by Claude (Opus agent, docs only: no Gradle, no adb, no code touched). Purpose: every code commit in `18c1b76a..07e27bcd` on `feat/v101-ask-eval` (126 commits, 239 files, +17228/-319; without `docs/`: 221 files, +14965/-307) was reviewed ONLY by Opus at low effort while Codex was out (19-21 Nov). This package re-reviews them with Codex `gpt-6.1-sol` at **medium** effort, read-only.

Sources: command form from `2026-11-16-v1.1-trip-build-handoff.md` section 1 (line 19) with the effort lowered to medium (line 95 + memory `feedback-codex-effort-budget`); invariants and follow-ups from `overnight-queue-2026-11-20.md` sections G-T, `train-audit-2026-11-20.md`, `needs-user-2026-11-19.md` sections I-K, the commit subjects and the `PinMomentLinks.kt` class doc (lines 25-58). Reports go to `docs/superpowers/reviews/2026-11/codex-<unit>-r1.md`.

Build mapping (the reviewer tags each finding with it): `apks-v110-cand-4436d1a5` (conservative trip candidate: units `map`, `log`, `pin` up to 4436d1a5) / `apks-v110-train7-ccfb0b62` (latest staged train: everything up to ccfb0b62) / HEAD-only (840ac900 and later: `ds3`, DiagProbe fix a61f5d59, 379fff33, SigLIP V, `diag`). Devices still run 18c1b76a. Test: `git merge-base --is-ancestor <sha> 4436d1a5`.

Not recorded anywhere in the repo (the reviewer must find these fresh): the street-map Opus review's "two non-blocking P2s"; the Opus round findings of trip logging, data saver slice 1 rounds 1-6 and Model privacy rounds 1-4 (only the fix commit subjects exist).

## 1. Review units, ordered by risk (privacy / data loss first)

Sizes: "net" = `git diff --stat <first>^..<last> -- <unit files> | tail -1` (interleaved commits from other units can add hunks in shared files); "sum" = per-commit `git show --shortstat` total. Both include tests.

| # | Unit (report name) | Risk | Commits | Net size | Sum |
|---|---|---|---|---|---|
| 1 | `pin` Pin to Moment + 5 arming rounds + 2 follow-ups | DATA LOSS (pins deleted) | 8 | 32 files +1937/-63 | +2328/-489 |
| 2 | `mark` mark undo de-link + run delete gate/prune | DATA LOSS / unconfirmed delete | 2 | 15 files +147/-15 | +158/-26 |
| 3 | `mp` Model privacy / resolver / Offline only net | PRIVACY (remote calls under Offline only) | 11 | 61 files +3131/-81 | +2670/-190 |
| 4 | `ds1` data saver slice 1 + 6 rounds | PRIVACY/COST (photos over roaming) + DATA LOSS (shed) | 9 | 51 files +2362/-49 | +2615/-302 |
| 5 | `map` street map (a2b85ca5 range) | PRIVACY (tile requests, cache survives wipe) | 7 | 24 files +1201/-24 | +1239/-62 |
| 6 | `log` trip logging + DiagProbe | PRIVACY (300 MB place trail) / ANR | 5 | 19 files +933/-96 | +662/-98 |
| 7 | `ds2` data saver slice 2 + counting rounds | PRIVACY (Send once) / COST | 9 | 39 files +1472/-80 | +1481/-106 |
| 8 | `shadow` shadow scaffold + pin reference vectors + ShadowCaches | PRIVACY (photo vectors after Forget) | 10 | 31 files +1575/-33 | +1726/-188 |
| 9 | `ds3` audit follow-ups: tiles/Place counting, downloads abroad | COST / download integrity | 3 | 14 files +254/-14 | +257/-17 |
| 10 | `geo` geocoder gate | PRIVACY | 2 | 4 files +67/-2 | +68/-3 |
| 11 | `siglip` SigLIP tokenizer/vision (+ Nomic output read change) | CORRUPT INDEX (production Nomic path) | 3 | 16 files +1209/-14 | +1223/-28 |
| 12 | `tools` install / post-install / report tools | DATA LOSS at install (backup acceptance) | 7 | 11 files +1359/-20 | +1066/-5 |
| 13 | `diag` Diagnostics suspect snapshot | PRIVACY (deleted snippet shown) | 1 | 3 files +32/-9 | +32/-9 |
| 14 | `zonedefer` zone publish deferral | PRIVACY (glasses zone state) | 1 | 3 files +30/-4 | +30/-4 |
| 15 | `forgetpolicy` Forget policy table + coverage map | LOW (not wired) / design truth | 3 | 8 files +783/-0 | +803/-20 |

Docs-only commits are not units. `android-companion/mr1.log` (a Gradle failure log) was added in 20fd16ba and deleted in 21f21049: net zero, no review needed.

### Unit 1 `pin` (highest priority)
- Commits: 8a5efce9 (pin to Moment, cascade, editor), dbfb4a75 (tests/wiring), ccebc4e8 (armed cascade, day picker, pinnedAt, Delete all pin names), 51891d2e (arm only target pairs, exact dead set, sweeps under pin lock, keyset picker), 84c8ca1e (armed pins keep link until cascade, truthful notices, arming bound to confirmed set), 4436d1a5 (stale wipe arming vs epoch store, revert on failed commit write, invariant table) = end of cand-4436d1a5; fc3e41a4 (carry pending wipe pairs, refuse racing arming, range Forget armed inside the gate, epoch store restored on failed commit), c867d709 (user Forget never carries a wipe's uncommitted pairs; busy guard checks armed state).
- Net diff: `git diff 8a5efce9^..c867d709 -- <files below>` (foreign hunks from 06e2440e, 84c1bcd9, 1d91f33a, 20fd16ba, 232d7f69 in ContextApp/SettingsDestinations/PlaceGuideSettings/AndroidPlaceGuideControl/DeletionGraph).
- Files: `PinMomentLinks.kt`, `PlacePinning.kt`, `ForgetActions.kt`, `MemoryWipe.kt`, `CanvasForgetCoordinator.kt`, `DeletionEpochStore.kt`, `MomentDao.kt`, `UserPlace.kt`, `ConfigExport.kt`, `ScopedForget.kt`, `ForgetSelection.kt`, `DeletionGraph.kt`, phone `MemoryForget.kt`, `ProductionForget.kt`, `ForgetDialog.kt`, `SelectionController.kt`, `MemoryDetailViewModel.kt`, `ListScreen.kt`, `PlaceGuideSettings.kt`, `MemorySettings.kt`, `AndroidPlaceGuideControl.kt`, `ContextApp.kt`; tests `PinMomentLinksTest`, `DeletionEpochStoreTest`, `PinMomentLookupTest`, `UserPlaceTest`, `ConfigExportTest`, `PinForgetConfirmTest`, `PinDialogRotationTest`, `PlaceGuideModelTest`, `MomentForgetFixture`.
- Invariants: (1) a pin is deleted only by an explicit user Forget or Delete all whose dialog named it; retention, suspect sweep, invalid-Moment purge and mark undo only clear the link ("Needs a Moment"); (2) an arming names only (pin, Moment) pairs whose Moment is in the operation's confirmed target set (Delete all: every pair alive at start); (3) one write stores `committed=true` with the exact dead set; only that stored set is ever deleted (by the op or by `settle` after a crash), never "every linked pin"; (4) sweeps and settle do nothing while an explicit op runs in this process (checked inside the pin lock); (5) a new arming merges an older unfinished committed one, never overwrites it; (6) every stored arming is resolved at the next start (table kind x committed x wipePending; class doc lines 39-55, check every row); (7) a Moment is gone only when no stored photo of its second is left or the second is tombstoned (not the projection row); (8) the range Forget arms inside the DataOpGate; (9) PinArmFailed is thrown BEFORE the Forget runs when arming cannot be stored; a cascade failure after commit never fails the Forget; (10) settings export strips `moment`, `at`, `armed`; (11) no Room change (prefs JSON); (12) pin log lines are literals plus a count.
- Follow-ups to confirm/overrule: G P2-1 (Delete all overwrites an earlier wipe's uncommitted arming; believed fixed fc3e41a4), G P2-2 (`forgetRangeOutcome` arms outside the gate; fc3e41a4), G P2-3 (`DeletionEpochStore.commitWipe` in-memory pending marker on failed commit; fc3e41a4), H P2-1 (abandoned wipe arming carried into a user Forget without the allowedPins filter; c867d709), H P2-2 (busy guard fires on in-flight, not armed; c867d709), **J P2 (believed STILL OPEN)**: Delete all commits its epoch, `wipeCommitted` fails, then in the same process a user Forget of a pinned Moment: `explicitStore` (PinMomentLinks.kt ~80-88) overwrites the uncommitted wipe arming, so pins Delete all promised to delete survive with cleared links; proposed fix: throw PinArmFailed or run `MemoryWipe.resumeIfPending` first. Audit P3-6 (rolled-back build rewrites pins without `moment`/`at`, old export leaks ids), P3-7 / ruling K(d) (every old pin shows "Needs a Moment"), P3-8 / ruling K(a) (`pinnedAt` survives Delete all; KDoc fixed in af67ff9f).

### Unit 2 `mark`
- Commits: fea96193 (mark undo only de-links pins, O6; canvas run delete and prune take the DataOpGate, O5), ccfb0b62 (run start no longer waits for the delete gate: prune after start, outside the runner lock; honest prune/409 and mark-forget copy). Range `git diff fea96193^..ccfb0b62` (contiguous for these files).
- Files: `MarkGraph.kt`, `MarkRecorder.kt`, `CanvasApi.kt`, `CanvasBackendImpl.kt`, `CanvasRunner.kt`, `ForgetActions.kt`, `PinMomentLinks.kt`, `ForgetPolicy.kt`, phone `MemoryForget.kt`; tests `DeleterGateInventoryTest`, `CanvasRunnerTest`, `ForgetCopyTest`, `ForgetPolicyTest`, `ForgetPolicyPinConformanceTest`, `PinMomentLinksTest`.
- Invariants: glasses, voice and phone mark undo run the photo Forget with an EMPTY pin target (nothing armed), a pin on that Moment is never deleted, now or later; `.deleteRun(` and prune are inside the DataOpGate and listed in `DeleterGateInventoryTest`; prune outside the runner lock cannot delete the run that is starting or race an export/save/upload of a kept run; no deadlock between the runner lock and the DataOpGate; a second start during prune gets a truthful answer.
- Follow-ups: P side note (MARK_BODY says "It has no photo" but removeMark forgets a late photo; ccfb0b62 claims the copy is fixed), P P2 UX (prune under the runner lock blocks start up to 30 s; ccfb0b62), P P2 cosmetic (prune log / 409 text names only export; ccfb0b62), ruling J O6 (implemented as recommended).

### Unit 3 `mp` (Model privacy / resolver / Offline only)
- Commits: 41603080 (Offline only fails closed at router, embeddings, endpoint test, data gate), 595f8a49 (ModelResolver, per-(role,backend) cache, per-backend confidence floor), 20fd16ba (Settings row, backup excluded, kept by Delete all), 21f21049 (Offline only is a standing hold, not a Blocked-today count), 232d7f69 (LAN literals and DNS check, fail-closed lost setting, real-reach work gates, async store, seed gate), 7e2488e3 (no system proxy, direct own-network route, pool eviction, photo reach, tiles gated), 43f2d70d (lost/failed mode write reads back as reset, tile re-check after the slot wait, LAN fallback asleep keeps parking), 166d3fd4 (synchronous begin, intent in a separate file, save failure shown, FallbackParkCap), 166840d4 (version re-check before each commit, superseded writes report nothing), 96f3e0d2 (park cap after-cap tests, sentinel text), 379fff33 (mode/wasReset never load on the caller's thread).
- Net diff `git diff 41603080^..379fff33 -- <files>` is heavily mixed with data saver hunks (ModelRouter, KeyframeGraph, DataSaver.kt): review per commit.
- Files: `ModelRouter.kt`, `Http.kt`, `OfflineOnlyNet.kt`, `OfflineOnlyDns.kt`, `OfflineOnlyGuard.kt`, `ModelPrivacyStore.kt`, `ModelResolver.kt`, `OnlineRouteFactory.kt`, `EmbeddingClient.kt`, `EndpointTester.kt`, `ModelErrors.kt`, `ModelErrorMapper.kt`, `AiGraph.kt`, `ModelBackend.kt`, `UserFacingError.kt`, `FallbackParkCap.kt`, `QueueDrainWorker.kt`, `KeyframePipeline.kt`, `KeyframeGraph.kt`, `DataSaverWorkGate.kt`, `PlaceSampler(s).kt`, `AnswerService.kt`, `AskActions.kt`, `AppAnswerer.kt`, `ModelPrivacySettings.kt`, `OnlineTileSource.kt` (Offline only hops), backup XMLs, `DeletionGraph.kt`.
- Invariants: under Offline only no request to a non-LAN host leaves the phone from any model role, embeddings, the endpoint test, place seed or tiles; the check runs before the key is read; no system proxy is used and the route is the phone's own network (not a VPN to a remote host); DNS and route guards throw only inside the synchronous `execute()` (FencedCall), never on an OkHttp dispatcher thread; a lost, unreadable or failed mode write reads back as "reset" (fail closed), never Automatic; a tap is in force at once and survives leaving the screen; the last tap wins on disk; parked frames under Offline only are a standing hold, not a Blocked-today count; FallbackParkCap stops a failing LAN fallback parking forever; `ai_backend*.xml` excluded from all 3 backup sections and kept by Delete all; resolver labels are fixed tokens; no main-thread disk read.
- Follow-ups: I P2-a (fixed 166840d4), I P2-b (fixed 166840d4), I P2-c (FallbackParkCap in memory; documented in 96f3e0d2, not persisted: accept?), I P2-d (fixed 96f3e0d2), I P2-e (tests; partly 96f3e0d2/166840d4), I known window (kill between tap and first disk write keeps the previous choice: accept?), J P3 (older write briefly clears Saving; throwing pushSettings logged as no_link), audit P3-2 / ruling K(c) (DNS query for a `.local` name before refusal), audit P3-4 (fixed 379fff33), audit table row "model file download unguarded under Offline only" (documented: confirm the doc).

### Unit 4 `ds1` (data saver slice 1)
- Commits: 0124b00e (pure cost classification + policy), 9d7dfc33 (store, monitor, graph, router gate), 06e2440e (pipeline park, photo admit, stale cells, worker gate, screen), bb3a6ffa (ask-photo gate seam, tests), 84c1bcd9 (no shedding while parked, nearby card, home-mobile default, VPN, onLost, queue defer, seed gate, counters), c7384bc3 (park-tag backlog, Android Data Saver state, watch retry, real endpoint reach, count once), 8951eb0e (park before shed across restarts and races, born-parked frames, 300 cap, off-main refresh), 1d91f33a (reach-aware debounced block mark, compare-and-clear), 3285d9a6 (debounced park/open, no main-thread blocking, fail-closed probe). Range `git diff 0124b00e^..3285d9a6` (clean, no foreign code commits).
- Files: `DataSaver.kt`, `DataSaverGraph.kt`, `DataSaverStore.kt`, `NetworkCostMonitor.kt`, `ModelRouter.kt`, `ModelErrors.kt`, `FrameDaos.kt`, `RokidFrameInbox.kt`, `KeyframePipeline.kt`, `KeyframeGraph.kt`, `DataSaverWorkGate.kt`, `OrchestrationScheduler.kt`, `QueueDrainWorker.kt`, `NightIndexWorker.kt`, `SuspectSweepWorker.kt`, `LocalModelStore.kt`, `AnswerService.kt`, `PlaceSampler(s).kt`, `PlacePack.kt`, `IntentSamplers.kt`, `AndroidDataSaverControl.kt`, `DataSaverSettings.kt`, `AndroidPlaceGuideControl.kt`, backup XMLs, `DeletionGraph.kt`.
- Invariants: a parked frame (PENDING, `lastError='data_saver'`) is never shed while parked, across restarts and races (park before shed); born-parked frames are tagged; the 300 cap never deletes a parked photo silently (what happens at the cap?); PHOTO and BULK refused on roaming/unknown/abroad by default, SMALL allowed on roaming; a 20 s blip debounce does not open the gate on a roaming network; compare-and-clear of the block mark; the probe fails closed; no main-thread blocking; workers honour `DataSaverWorkGate`; WorkManager uses `UPDATE` with `dataSaverNetwork()`; `data_saver.xml` excluded from all backups and kept by Delete all (counts only).
- Follow-ups: audit P1-1 (rollback uploads the parked backlog: procedural warning added in 2bda76d8, check the text), audit P2-2 / ruling K(b) (Android Data Saver RESTRICTED blocks photos even for an FGS), audit P3-5 (Wi-Fi-only WorkManager constraint persists after rollback), audit P3-10 / ruling K(e) (dual-SIM home looks like roaming).

### Unit 5 `map` (street map, a2b85ca5 range)
- Commits: 1e81fe1d (OnlineTileSource: HTTPS allowlist, caps, backoff, 50 MB LRU), 0e3fe036 (switch, first-use dialog, Centre on me, attribution, Delete all reset, backup exclusion), a1065528 (docs), 0b9816d3 (Delete all clears switch, consent, cache even after a crash mid-wipe), dd155a06 (switch off stops the source before the cache is deleted), 5cc1665d (dialog names the provider really used; switch without consent reads OFF), a2b85ca5 (cap is per visit). Range `git diff 7567369c..a2b85ca5` (clean). Read `OnlineTileSource.kt` at HEAD too (later hunks: 7e2488e3, 43f2d70d, 840ac900).
- Files: `OnlineTileSource.kt`, `OnlineMapGraph.kt`, `OnlineMap.kt`, `OfflineMap.kt`, `TileMath.kt`, `ZonesScreen.kt`, `SettingsDeps.kt`, `MemoryWipe.kt`, `DeletionGraph.kt`, `ContextApp.kt`, `phone/build.gradle.kts`, backup XMLs, `PRIVACY.md`, `release/README.md`; tests `OnlineTileSourceTest`, `OnlineMapModelTest`, `OnlineMapCardTest`, `NoSecretsInBuildConfigTest`, `InterruptedDeleteAllTest`, `DeletionInventoryFilesTest`, `BackupRulesTest`.
- Invariants: nothing is requested until the switch is on AND consent was given; one HTTPS host, redirects checked, size caps, at most 400 tiles per visit, backoff; the Offline only check runs before every hop and after the slot wait; switch off stops the source before the cache is deleted; Delete all clears switch, consent and `cache/online_tiles/` even after a crash mid-wipe; `online_map.xml` excluded from all 3 backup sections; the MapTiler key never lands in BuildConfig or the repo; a debug build without a key uses OSM; tile logs are literals.
- Follow-ups: the Opus map review's two P2s are not recorded: report what you find.

### Unit 6 `log` (trip logging)
- Commits: f096600b (60 x 5 MB segments, 300 MB, Detailed log switch default on), 03e88123 (place attempt decision record, pack lookup timing, DiagProbe lines), 16fc7a79 (Moment/Scene Forget gate reads Developer mode again), dba8a833 (PRIVACY s8 wording, rotation retry keeps oldest segment, snapshot flag, bounded work map, off-main registration), a61f5d59 (lifecycle callbacks synchronous, foreground from an activity set, receivers on a background handler, worker watch unfinished only).
- Files: `AsyncLineWriter.kt`, `PhoneLogSink.kt`, `DeveloperModeStore.kt`, `DiagProbe.kt`, `ContextApp.kt`, `Diagnostics.kt`, `SettingsDestinations.kt`, `PlaceSampler.kt`, `PlacePack.kt`, backup XMLs, `tools/link-health-report.sh`; tests `AsyncLineWriterTest`, `PhoneLogSinkTest`, `DiagProbeTest`, `LogContentScanTest`, `PlaceSamplerTest`.
- Invariants: no new log line carries content (message, URL, host, key, path, name, note, coordinate, `e.message`); the only ids are documented `ids=`/`chosen=`; Delete all removes every segment `pca.log*`; rotation never loses the newest data or exceeds 300 MB; the logs folder is backup excluded; the Moment/Scene Forget gate follows Developer mode, not the Detailed log; DiagProbe does no binder or disk work on main and its foreground count cannot go negative.
- Follow-ups: audit P2-4 / ruling K(f) (default-ON 300 MB place trail, ids not hashed, I lines mirrored to logcat), audit P2-1 (rollback leaves segments; procedural fix in 2bda76d8), audit P3-3 and P3-11 (fixed a61f5d59).

### Unit 7 `ds2` (data saver slice 2)
- Commits: d712af92 (pure daily cap, ledger, send-once marker, Wi-Fi-as-metered abroad), 82f9b2b9 (byte counter at the router, cap in the gate, midnight wake, decideOnce), 5fe42cc8 (Delete all keeps counters, photoHeldForPrompt, cap park/wake test), e969e2ed (screen + Send once dialog), eac23a5c (phone tests), 2abb4a76 (N1-N6 fixes + ShadowCaches M fixes), 4cdce532 (LAN endpoint on mobile, unusable 2xx, class read at the permit), c2609926 (log literals), 7cc56874 (loopback not counted). Range `git diff d712af92^..7cc56874` (clean for code).
- Files: `DataSaver.kt`, `ModelRouter.kt`, `DataSaverGraph.kt`, `DataSaverStore.kt`, `ModelErrors.kt`, `OllamaProvider.kt`, `OpenAiCompatProvider.kt`, `KeyframeGraph.kt`, `DiagProbe.kt`, `ContextApp.kt`, `PhotoSearch*.kt`, `DataSaverSettings.kt`, `AndroidDataSaverControl.kt`, `DeletionGraph.kt`, `MemoryWipe.kt`, `ShadowCaches.kt`, `PRIVACY.md` s15; tests `DailyCapsTest`, `DataSaverCapTest`, `ModelRouterDataSaverTest`, `PhotoSearchViewModelTest`, `DescribeOnDemandTest`, `MemoryWipeTest`, `ShadowCachesTest`.
- Invariants: default users are uncapped (DailyCaps.limitFor null on FREE/OFFLINE/HOME_MOBILE/RESTRICTED unless the user set a limit); Send once is structural (withContext + USER class + photos) and covers only the USER photo calls of one describe; photos count only on success, request bytes of a failure only on an HTTP error status, class read once at call start, only REMOTE endpoints counted, a throwing ledger can neither discard a reply nor double count; loopback is not counted and no remote host form (localhost.evil.com, 127.1, 0x7f.1, ::ffff:127.0.0.1, userinfo) is classed loopback; midnight wait restarts on TIME_SET/TIMEZONE_CHANGED; Delete all keeps the counters (user ruling).
- Follow-ups: N1-N6 and nits (all claimed fixed in O: confirm each), O2 nits (test never checks costlyNow is not called; VPN reporting Wi-Fi over cellular undercounts; IOException in a 2xx body counts nothing), P doc P2 (local forwarder on the phone is trusted as on-phone; documented).

### Unit 8 `shadow` (shadow scaffold, pin reference vectors, ShadowCaches)
- Commits: 24bbc2f1 (ImageTextEmbedder, CandidateScorer), 93a66dfa (shadow scoring, flag `placeShadowScore` default off), d6ff14d8, 90357edf (scaffold fixes), bffd2481 (MomentDao.photoFrameOf), 438c784f (in-memory pin reference vector cache), 879cce7a (wired into the shadow image channel), d0b442ce, 80b92425, 14a3ffa6 (B1 fixes); also the ShadowCaches hunks of 2abb4a76. Range `git diff 24bbc2f1^..14a3ffa6` (foreign hunks from c867d709, 96f3e0d2 in shared files).
- Files: `PlaceShadow.kt`, `PinReferenceVectors.kt`, `ShadowCaches.kt`, `PlaceSampler(s).kt`, `ImageSpaceTargetImageText.kt`, `ImageSpaces.kt`, `CandidateScorer.kt`, `ImageTextEmbedder.kt`, `Embedder.kt`, `EndpointEmbedder.kt`, `LocalModelPower.kt`, `MomentDao.kt`, `ForgetShadow.kt`, `MemoryWipe.kt`, `PinMomentLinks.kt`, `PlacePinning.kt`, `Families.kt`, `ForgetPolicy.kt`, `DeletionGraph.kt`, `ContextApp.kt`; tests `PlaceSamplerShadowTest`, `PinReferenceVectorsTest`, `ShadowCachesTest`, `SharedPhotoTest`, `FamilyInventoryTest`, `PlaceSurfaceInventoryTest`, `CandidateScorerTest`.
- Invariants: flag off = nothing runs and no behaviour change; images embedded only on device (Nomic ONNX), after the data-saver photo check; the shadow never delays the card or its timing; a photo load cannot hang an attempt; vectors are never persisted, revalidated on every use, a stale generation is never stored or served; ShadowCaches cleared on Forget, Delete all (twice, the second after the generation bump), pin link change and flag off, and listed in the Forget inventory; log fields content-free.
- Follow-ups: K (SharedPhoto.clear outside the lock; cross-attempt clear vs SHADOW_BUDGET; stale KDoc; 2 missing tests), L (store-after-wipe, cache-hit gap, lazy Delete all clear, embed job without live(), doc wording, mixed pin+pack marker, per-pin revalidate; missing tests incl. Forget DURING the embed), M (clearAll too early, epoch RMW, brittle string tripwire). Section O says all K/L/M items are closed: confirm each.

### Unit 9 `ds3` (audit follow-ups)
- Commits: 840ac900 (Place cell fetches and tiles in the ledger; tiles cache-only where photos would wait), af67ff9f (Allow mobile data respects I'm abroad; pinnedAt KDoc), 8c15461e (abroad guard replaces the work with a Wi-Fi-only one, no retry; switch and app start re-constrain queued and running downloads). Range `git diff 840ac900^..8c15461e` (foreign 379fff33, a61f5d59 only).
- Files: `PlaceHttp.kt`, `PlaceGraph.kt`, `OnlineTileSource.kt`, `OnlineMapGraph.kt`, `DataSaverSettings.kt`, `LocalModelStore.kt`, `LocalModelDownloadWorker.kt`, `AndroidDataSaverControl.kt`, `ContextApp.kt`, `UserPlace.kt`; tests `PlaceHttpCountingTest`, `OnlineTileSourceTest`, `LocalModelDownloadWorkerTest`.
- Invariants: every Place cell byte and tile byte on a counted network is in the ledger once; tiles are cache-only exactly where photos would wait; replacing a running download never corrupts or deletes a model file, never loops, and resumes on Wi-Fi.
- Follow-ups: audit P2-3, P3-8, P3-9 (claimed fixed); ruling K(g).

### Unit 10 `geo`
- Commits: d979e4bc (Geocoder gated by Offline only and the data saver), 941fcda4 (log literals).
- Files: `LocationSource.kt`, `SourcesGraph.kt`, `PRIVACY.md`; test `LocationSourceTest`.
- Invariants: no Geocoder call under Offline only or when SMALL is refused; no other Geocoder caller; ConnectorsModuleRulesTest still enforces the rule; logs literal. Follow-up: audit P3-1 (PRIVACY contradiction fixed 2bda76d8).

### Unit 11 `siglip`
- Commits: 487f09e8 (tokenizer, milestone T), 66e8af7d (vision tower, milestone V; OnnxImageEmbedder outputName), 3288c3f8 (**Nomic output read without whole-tensor copy**: production path; SigLIP one image at a time). Range `git diff 487f09e8^..3288c3f8` (clean for code).
- Files: `SigLipTokenizer.kt`, `TextTokenizer.kt`, `SigLipImageEmbedder.kt`, `SigLipPreprocess.kt`, `OnnxImageEmbedder.kt`, `ImageTextEmbedder.kt`, `services/ai/build.gradle.kts` (env-gated testRuntimeOnly jar only); goldens.
- Invariants: no production flow constructs SigLIP classes; Nomic output is bit-identical to before (index/stride/dtype, tensor and result closed, no leak, shape assumptions checked), so no wrong vector is ever written to the persisted index; `pooler_output` read by name; batchSize 1. Follow-ups: R human checks (re-hash, ORT-android ConvInteger, licence NOTICE, batch decision).

### Unit 12 `tools`
- Commits: 5a677357 (plaintext context.db accepted only when `db_encrypted=false` AND integrity_check passes on the backup copy), 7567369c (link-health-report), d6370794, 8c677558, 24847920 (place-attempt-report), 95389805 (post-install-check trip checks, 71 fixtures), 2bda76d8 (rollback warning in installer guidance + runbook 14).
- Files: `tools/install-trip-build.sh`, `tools/post-install-check.sh`, `tools/link-health-report.sh`, `tools/place-attempt-report.sh`, `tools/INSTALL-TRIP-BUILD.md`, `tools/PLACE-REPORT.md`, runbook section 14.
- Invariants: dry run by default, `--go` required; a backup that fails validation stops the install before anything is written; the report and check scripts are read-only on the device (no `rm`, `pm clear`, `am force-stop`, settings writes) and quote every path; the rollback guidance says: mobile data and roaming off, unbind remote endpoints (Offline only users), quarantine `files/logs/pca.log.[2-9]*`.
- Follow-ups: audit P1-1 and P2-1 procedural fixes (check the wording is reachable before the rollback command).

### Unit 13 `diag`
- Commit: 777b8ffa (one query snapshot `ForgetDao.observeSuspects(limit)`). Files: `Diagnostics.kt`, `ForgetDao.kt`, `DiagnosticsViewModelTest`.
- Invariants: total and rows come from one query with the same SUSPECT predicate; Room invalidation covers memory/frame/frame_source; a deleted memory's snippet is never shown. Follow-ups: T P2 (no test with more suspects than shown), T nits (watcher started after the delete; stale KDoc ForgetDao.kt:352).

### Unit 14 `zonedefer`
- Commit: 618a2778. Files: `Hub.kt`, `ZoneController.kt`, `ZoneR2Test`.
- Invariants: a refused publish is deferred, retried on every fix, logged once; after reconnect the glasses get the CURRENT zone/pause state. Follow-ups: J P3 (throwing pushSettings logged as `deferred reason=no_link`; hello handler ignores pushSettings' return).

### Unit 15 `forgetpolicy`
- Commits: e300ff9a (ForgetSource, policy table, PolicyPlan, not wired), ce0a679d (conformance vs real planner and PinMomentLinks), e5cf06f6 (20 cells verified, TRACE x purge fixed, side-delete rows, open rulings); plus the table rows edited in 80b92425, fea96193, ccfb0b62. Doc: `docs/superpowers/plans/2026-11-20-forget-coverage-map.md` (24 entry points, gap table).
- Invariants: no production decision reads the table yet (behaviour unchanged); every cell matches the code; the conformance tests compare against real code, not copies; the coverage map's 24 entry points and gaps are true at HEAD. Follow-ups: rulings J O1-O4, O7 (PLACE_CARD x user Forget is KEEP in the table but PlaceGraph.kt:31-35 withdraws the card), O5/O6 (now implemented: check the table rows).

## 2. Scripts (commands only; not run)

Block 0: run once per shell. Each later block writes its prompt file and calls `cx`, which writes `docs/superpowers/reviews/2026-11/codex-<unit>-r1.md`. Run each in its own terminal or append `&` (one call can take up to an hour). If auth expired: `codex logout && codex login`.

```bash
# Block 0: common setup
REPO=~/Desktop/rokid/proactive-context-agent
OUT=$REPO/docs/superpowers/reviews/2026-11
P=${TMPDIR:-/tmp}/codex-rereview; mkdir -p "$P"
git -C "$REPO" rev-parse --short HEAD   # 07e27bcd or later
cat > "$P/common.txt" <<'EOF'
Read-only re-review in this checkout (branch feat/v101-ask-eval, written against HEAD 07e27bcd; if HEAD moved, also run `git log --oneline 07e27bcd..HEAD -- <the unit's files>` and review those changes). Claude Opus reviewed these commits alone, at low effort, while Codex was unavailable on 19-21 Nov 2026. You are the independent reviewer. Do not edit any file. Do not run Gradle, adb or the tools scripts. Method: run `git show <sha>` for each listed commit, then read the HEAD version of the listed files and their callers. Context (read only the named parts): docs/superpowers/plans/handoffs/overnight-queue-2026-11-20.md, docs/superpowers/plans/handoffs/train-audit-2026-11-20.md, PRIVACY.md, docs/superpowers/plans/handoffs/codex-rereview-package-2026-11-21.md (your unit's section).
Severity: P1 = data loss; a deletion the user did not confirm; a privacy leak (content, photo, location, id or key leaving the phone, or surviving a Forget or Delete all that promised to remove it); a crash or ANR; fabricated memory; or a photo deletable on a non-photo description. P2 = wrong but rare or failing in the safe direction. P3 = cosmetic, docs or tests.
Builds: cand-4436d1a5 contains commits up to 4436d1a5; train7 contains commits up to ccfb0b62 (use `git merge-base --is-ancestor <sha> <build sha>`).
Output: line 1 `VERDICT: READY` (no P1) or `VERDICT: NOT READY`. Then numbered findings: `[P1|P2|P3] path:line - scenario step by step - one-line fix - affects: cand-4436d1a5 | train7-ccfb0b62 | HEAD-only`. Then a table of every listed follow-up: `id | CONFIRMED-OPEN | FIXED (sha, path:line) | OVERRULED (why)`. Then each listed invariant: `HOLDS | BROKEN (finding #) | NOT CHECKABLE STATICALLY`. Report a finding only when you can name the code path. Static review only.
EOF
cx() { # cx <unit> <prompt files...>
  local unit=$1; shift
  cat "$P/common.txt" "$@" > "$P/$unit.prompt"
  codex exec -m gpt-6.1-sol -c model_reasoning_effort=medium -s read-only --skip-git-repo-check --ephemeral \
    -C "$REPO" -o "$OUT/codex-$unit-r1.md" "$(cat "$P/$unit.prompt")" < /dev/null
}
```

```bash
# Unit 1: pin
cat > "$P/pin.txt" <<'EOF'
UNIT pin (Pin to Moment cascade; highest priority; deletion path). Commits: 8a5efce9 dbfb4a75 ccebc4e8 51891d2e 84c8ca1e 4436d1a5 fc3e41a4 c867d709. Net: git diff 8a5efce9^..c867d709 -- android-companion (other units add hunks in ContextApp, SettingsDestinations, PlaceGuideSettings, AndroidPlaceGuideControl, DeletionGraph).
Core files: work/frames/place/PinMomentLinks.kt (class doc lines 25-58 is the spec), PlacePinning.kt, orchestration/forget/ForgetActions.kt, orchestration/MemoryWipe.kt, canvas/plan/CanvasForgetCoordinator.kt, memory/db/DeletionEpochStore.kt, memory/db/MomentDao.kt, memory/place/UserPlace.kt, orchestration/ConfigExport.kt, memory/forget/ScopedForget.kt, forget/lifecycle/ForgetSelection.kt, phone ui/memory/MemoryForget.kt, ProductionForget.kt, ForgetDialog.kt, ui/list/paging/SelectionController.kt, MemoryDetailViewModel.kt, PlaceGuideSettings.kt.
Invariants: (1) a pin is deleted only by an explicit user Forget or Delete all whose dialog named it; retention, the suspect sweep, the invalid-Moment purge and mark undo only clear the link. (2) An arming names only pairs whose Moment is in the confirmed target set. (3) One write stores committed=true with the exact dead set; only that set is ever deleted. (4) Sweeps and settle do nothing while an explicit operation runs (checked inside the pin lock). (5) A new arming merges an older unfinished committed arming and never overwrites it. (6) Every stored arming is resolved at the next start: verify every row of the crash table in the class doc. (7) A Moment is gone only when no stored photo of its second is left or the second is tombstoned. (8) The range Forget arms inside the DataOpGate. (9) PinArmFailed is thrown before the Forget runs. A cascade failure after the commit never fails the Forget. (10) The export strips moment, at and armed. (11) There is no Room change. (12) Log lines are literals plus a count.
Follow-ups: G-P2-1, G-P2-2, G-P2-3 (claimed fixed fc3e41a4); H-P2-1, H-P2-2 (claimed fixed c867d709); J-P2 (believed OPEN: Delete all epoch committed, wipeCommitted write fails, then a same-process user Forget of a pinned Moment; explicitStore ~line 80-88 overwrites the uncommitted wipe arming; the pins Delete all promised to delete survive); audit P3-6, P3-7, P3-8.
Also state which findings affect cand-4436d1a5 (it lacks fc3e41a4 and c867d709).
EOF
cx pin "$P/pin.txt"
```

```bash
# Unit 2: mark
cat > "$P/mark.txt" <<'EOF'
UNIT mark (mark undo de-link O6; canvas run delete and prune under the DataOpGate O5; prune off the run-start path). Commits: fea96193 ccfb0b62. Net: git diff fea96193^..ccfb0b62.
Files: phone canvas/MarkGraph.kt, canvas/api/CanvasApi.kt, CanvasBackendImpl.kt, orchestration canvas/CanvasRunner.kt, canvas/marks/MarkRecorder.kt, orchestration/forget/ForgetActions.kt (forgetMomentKeepPinsOutcome), PinMomentLinks.kt, forget/lifecycle/ForgetPolicy.kt, phone ui/memory/MemoryForget.kt; tests DeleterGateInventoryTest, CanvasRunnerTest, ForgetCopyTest.
Invariants: glasses, voice and phone mark undo arm nothing and never delete a pin, now or later. `.deleteRun(` and prune are inside the DataOpGate and listed in DeleterGateInventoryTest. Prune outside the runner lock cannot delete the run being started or race an export, save or upload. There is no deadlock between the runner lock and the gate. Every user-facing 409 and dialog text is true.
Follow-ups: P side note (MARK_BODY says 'It has no photo' while removeMark forgets a late photo); P P2 prune under the runner lock; P P2 cosmetic texts (all claimed fixed in ccfb0b62).
EOF
cx mark "$P/mark.txt"
```

```bash
# Unit 3: mp
cat > "$P/mp.txt" <<'EOF'
UNIT mp (Model privacy: Offline only, resolver, proxy, DNS, interceptor, mode store). Commits: 41603080 595f8a49 20fd16ba 21f21049 232d7f69 7e2488e3 43f2d70d 166d3fd4 166840d4 96f3e0d2 379fff33. Review per commit: the range diff mixes in data saver hunks.
Files: services/ai ModelRouter.kt, Http.kt, resolve/OfflineOnlyNet.kt, OfflineOnlyDns.kt, OfflineOnlyGuard.kt, ModelPrivacyStore.kt, ModelResolver.kt, OnlineRouteFactory.kt, embed/EmbeddingClient.kt, EndpointTester.kt, ModelErrors.kt, ModelErrorMapper.kt, AiGraph.kt; core/model net/ModelBackend.kt; orchestration work/FallbackParkCap.kt, QueueDrainWorker.kt, DataSaverWorkGate.kt, frames/KeyframePipeline.kt, KeyframeGraph.kt, place/PlaceSampler(s).kt, AnswerService.kt, AskActions.kt; phone voice/AppAnswerer.kt, ui/settings/modelprivacy/ModelPrivacySettings.kt, zones/online/OnlineTileSource.kt; res/xml backup_rules.xml, data_extraction_rules.xml.
Invariants: (1) Under Offline only, nothing from any model role, embeddings, the endpoint test, the place seed or tiles reaches a non-LAN host, and the check runs before the key is read. (2) No system proxy is used; the route is the phone's own network. (3) The DNS and route guards throw only inside a synchronous execute() (FencedCall), never on an OkHttp dispatcher thread: list every client built with them. (4) A lost, unreadable or failed mode write reads back as reset, never Automatic. (5) A tap takes effect at once and survives leaving the screen, and the last tap wins on disk. (6) Offline-only parking is a standing hold, not a Blocked-today count. (7) FallbackParkCap ends a failing LAN fallback. (8) ai_backend*.xml is excluded from all 3 backup sections and kept by Delete all. (9) Resolver labels are fixed tokens. (10) Nothing reads the disk on the main thread.
Follow-ups: I-P2-a, I-P2-b (claimed fixed 166840d4), I-P2-c (in-memory cap: accept or persist), I-P2-d (96f3e0d2), I-P2-e (tests), I known window (a kill between the tap and the first disk write), J-P3 (cosmetic Saving; throwing pushSettings logged as no_link), audit P3-2 (DNS for a .local name before the refusal), audit P3-4 (379fff33), audit table row: model file download is unguarded under Offline only (documented?).
EOF
cx mp "$P/mp.txt"
```

```bash
# Unit 4: ds1
cat > "$P/ds1.txt" <<'EOF'
UNIT ds1 (roaming data saver slice 1 + 6 Opus rounds; the round findings are not recorded, review fresh). Commits: 0124b00e 9d7dfc33 06e2440e bb3a6ffa 84c1bcd9 c7384bc3 8951eb0e 1d91f33a 3285d9a6. Net: git diff 0124b00e^..3285d9a6.
Files: core/model net/DataSaver.kt; services/ai datasaver/DataSaverGraph.kt, DataSaverStore.kt, NetworkCostMonitor.kt, ModelRouter.kt, ModelErrors.kt; memory db/FrameDaos.kt (parkPendingForDataSaver, shed query); orchestration data/frames/RokidFrameInbox.kt, work/frames/KeyframePipeline.kt, KeyframeGraph.kt, work/DataSaverWorkGate.kt, OrchestrationScheduler.kt, QueueDrainWorker.kt, forget/NightIndexWorker.kt, SuspectSweepWorker.kt, data/models/LocalModelStore.kt, AnswerService.kt, place/PlaceSampler(s).kt, intent/IntentSamplers.kt; phone datasaver/AndroidDataSaverControl.kt, ui/settings/datasaver/DataSaverSettings.kt, place/AndroidPlaceGuideControl.kt; backup XMLs. Design: docs/superpowers/plans/2026-11-20-roaming-data-saver-design.md.
Invariants: (1) A parked frame (PENDING with lastError='data_saver') is never shed while parked, across restarts and races. (2) Born-parked frames are tagged. (3) Check what the 300 cap does to parked photos: is anything silently deleted? (4) PHOTO and BULK are refused on roaming, unknown and abroad by default; SMALL is allowed on roaming. (5) The blip debounce never opens the gate on a roaming network. (6) The block mark is compare-and-clear. (7) The probe fails closed. (8) Nothing blocks the main thread. (9) Every worker honours DataSaverWorkGate; WorkManager uses UPDATE with dataSaverNetwork(). (10) data_saver.xml is excluded from all backups and Delete all keeps only counts.
Follow-ups: audit P1-1 (a rollback uploads the parked backlog: check the 2bda76d8 warning), audit P2-2 (Android Data Saver RESTRICTED blocks photos even for a foreground service: is that true on API 35?), audit P3-5, audit P3-10.
EOF
cx ds1 "$P/ds1.txt"
```

```bash
# Unit 5: map
cat > "$P/map.txt" <<'EOF'
UNIT map (opt-in street map for the Pause zones editor). Commits: 1e81fe1d 0e3fe036 a1065528 0b9816d3 dd155a06 5cc1665d a2b85ca5. Net: git diff 7567369c..a2b85ca5. Also read OnlineTileSource.kt at HEAD (later hunks 7e2488e3, 43f2d70d, 840ac900).
Files: phone ui/settings/privacy/zones/online/OnlineTileSource.kt, OnlineMapGraph.kt, zones/tiles/OnlineMap.kt, OfflineMap.kt, TileMath.kt, zones/ZonesScreen.kt, settings/SettingsDeps.kt, ContextApp.kt, phone/build.gradle.kts (key from local.properties/env); orchestration MemoryWipe.kt; memory forget/DeletionGraph.kt; backup XMLs; PRIVACY.md; release/README.md.
Invariants: (1) Nothing is requested until the switch is on and consent was given; a saved switch without consent reads OFF. (2) There is one HTTPS host, redirects are checked, sizes are capped, there are at most 400 tiles per visit, and failures back off. (3) The Offline only check runs before every hop and after the slot wait. (4) Switching off stops the source before the cache is deleted. (5) Delete all clears the switch, the consent and cache/online_tiles/ even after a crash mid-wipe. (6) online_map.xml is excluded from all 3 backup sections. (7) The key never reaches BuildConfig or the repo. (8) Tile logs are literals.
Follow-ups: the Opus review's two non-blocking P2s were never recorded: report anything you find.
EOF
cx map "$P/map.txt"
```

```bash
# Unit 6: log
cat > "$P/log.txt" <<'EOF'
UNIT log (trip-sized log and DiagProbe). Commits: f096600b 03e88123 16fc7a79 dba8a833 a61f5d59.
Files: core/model log/AsyncLineWriter.kt; phone log/PhoneLogSink.kt, log/DiagProbe.kt, canvas/DeveloperModeStore.kt, ContextApp.kt, ui/settings/Diagnostics.kt, ui/shell/SettingsDestinations.kt; orchestration place/PlaceSampler.kt (place attempt line); memory place/PlacePack.kt; backup XMLs; tools/link-health-report.sh; tests LogContentScanTest, AsyncLineWriterTest, PhoneLogSinkTest, DiagProbeTest.
Invariants: (1) No new line carries content (e.message, URL, host, key, path, name, note or coordinate); the only ids are the documented ids= and chosen=. (2) Delete all removes every pca.log* segment. (3) Rotation keeps the newest data and stays under 300 MB. (4) The logs folder is backup excluded. (5) The Moment/Scene Forget gate follows Developer mode, not the Detailed log. (6) DiagProbe does no binder or disk work on the main thread, and its foreground count cannot go negative.
Follow-ups: audit P2-4 (default-ON 300 MB place trail; logcat mirroring of I lines: quantify), audit P2-1 (rollback leaves segments; procedural fix 2bda76d8), audit P3-3 and P3-11 (claimed fixed a61f5d59).
EOF
cx log "$P/log.txt"
```

```bash
# Unit 7: ds2
cat > "$P/ds2.txt" <<'EOF'
UNIT ds2 (data saver slice 2: daily caps, mobile-data ledger, Send once, counting rounds). Commits: d712af92 82f9b2b9 5fe42cc8 e969e2ed eac23a5c 2abb4a76 4cdce532 c2609926 7cc56874. Net: git diff d712af92^..7cc56874.
Files: core/model net/DataSaver.kt (DailyCaps, isLanHost, loopback); services/ai ModelRouter.kt (send), datasaver/DataSaverGraph.kt (sentAt, onClockChanged), DataSaverStore.kt, ModelErrors.kt, model/provider/OllamaProvider.kt, OpenAiCompatProvider.kt; orchestration KeyframeGraph.kt, MemoryWipe.kt, place/ShadowCaches.kt; phone ui/photos/PhotoSearch*.kt, datasaver/DataSaverSettings.kt, AndroidDataSaverControl.kt, log/DiagProbe.kt, ContextApp.kt; PRIVACY.md section 15.
Invariants: (1) Default users are uncapped. (2) Send once is structural and covers only the USER photo calls of one describe. (3) Photos count only on success. A failure counts request bytes only on an HTTP error status. The network class is read once at call start, and only REMOTE endpoints count. A throwing ledger can neither discard a reply nor double count. (4) Loopback is not counted, and no remote host form (localhost.evil.com, 127.0.0.1.evil.com, 127.1, 0x7f.1, ::ffff:127.0.0.1, userinfo) is treated as loopback. (5) The midnight wait restarts on a time or zone change. (6) Delete all keeps the counters.
Follow-ups: N1-N6 and nits (claimed fixed, section O), O2 nits (costlyNow call never checked by the test; a VPN reporting Wi-Fi over cellular undercounts; an IOException in a 2xx body counts nothing), P doc P2 (a local forwarder is trusted as on-phone).
EOF
cx ds2 "$P/ds2.txt"
```

```bash
# Unit 8: shadow
cat > "$P/shadow.txt" <<'EOF'
UNIT shadow (Place Guide shadow scaffold, pin reference vectors, ShadowCaches; flag placeShadowScore default OFF). Commits: 24bbc2f1 93a66dfa d6ff14d8 90357edf bffd2481 438c784f 879cce7a d0b442ce 80b92425 14a3ffa6, plus the ShadowCaches/MemoryWipe hunks of 2abb4a76. Net: git diff 24bbc2f1^..14a3ffa6.
Files: orchestration work/frames/place/PlaceShadow.kt, PinReferenceVectors.kt, ShadowCaches.kt, PlaceSampler.kt, PlaceSamplers.kt (SharedPhoto), ImageSpaceTargetImageText.kt, scene/ImageSpaces.kt, forget/ForgetShadow.kt, MemoryWipe.kt, PinMomentLinks.kt, PlacePinning.kt; memory retrieval/CandidateScorer.kt, ImageTextEmbedder.kt, Embedder.kt, db/MomentDao.kt (photoFrameOf), forget/lifecycle/Families.kt, ForgetPolicy.kt, DeletionGraph.kt; ai embed/EndpointEmbedder.kt, LocalModelPower.kt.
Invariants: (1) With the flag off nothing runs and behaviour is unchanged. (2) Images are embedded only on the device, after the data-saver photo check. (3) The shadow never delays the card or its timing, and a photo load cannot hang an attempt. (4) Vectors are never persisted, are revalidated on every use, and a stale generation is never stored or served. (5) ShadowCaches is cleared on Forget, on Delete all (twice, the second after the generation bump), on a pin link change and on flag off, and is listed in the Forget inventory. (6) Log fields are content-free.
Follow-ups: every item in overnight-queue sections K, L and M (section O claims all closed): give one row per item.
EOF
cx shadow "$P/shadow.txt"
```

```bash
# Unit 9: ds3
cat > "$P/ds3.txt" <<'EOF'
UNIT ds3 (audit follow-ups: Place cell and tile bytes counted, tiles cache-only on costly networks, model downloads respect I'm abroad). Commits: 840ac900 af67ff9f 8c15461e. Net: git diff 840ac900^..8c15461e.
Files: connectors place/PlaceHttp.kt; orchestration place/PlaceGraph.kt, data/models/LocalModelStore.kt, work/LocalModelDownloadWorker.kt; phone zones/online/OnlineTileSource.kt, OnlineMapGraph.kt, datasaver/DataSaverSettings.kt, AndroidDataSaverControl.kt, ContextApp.kt; memory place/UserPlace.kt.
Invariants: (1) Every Place cell byte and tile byte on a counted network is in the ledger exactly once. (2) Tiles are cache-only exactly where photos would wait. (3) Replacing a running download never corrupts or deletes a model file, never loops, and finishes on Wi-Fi.
Follow-ups: audit P2-3, P3-8, P3-9 (claimed fixed).
EOF
cx ds3 "$P/ds3.txt"
```

```bash
# Unit 10: geo
cat > "$P/geo.txt" <<'EOF'
UNIT geo (Location Geocoder gated by Offline only and the data saver). Commits: d979e4bc 941fcda4.
Files: connectors sources/LocationSource.kt, SourcesGraph.kt; PRIVACY.md; LocationSourceTest; ConnectorsModuleRulesTest.
Invariants: (1) There is no Geocoder call under Offline only or when SMALL is refused. (2) Nothing else calls the Geocoder. (3) The module rule test still enforces this. (4) Logs are literals.
Follow-up: audit P3-1 (PRIVACY contradiction, fixed 2bda76d8).
EOF
cx geo "$P/geo.txt"
```

```bash
# Unit 11: siglip
cat > "$P/siglip.txt" <<'EOF'
UNIT siglip (SigLIP 2 tokenizer and vision tower, not wired; plus a PRODUCTION change to the Nomic ONNX output read). Commits: 487f09e8 66e8af7d 3288c3f8. Net: git diff 487f09e8^..3288c3f8 -- android-companion/services.
Files: ai embed/siglip/SigLipTokenizer.kt, TextTokenizer.kt, SigLipImageEmbedder.kt, SigLipPreprocess.kt, ai local/OnnxImageEmbedder.kt, memory ImageTextEmbedder.kt, services/ai/build.gradle.kts.
Invariants: (1) No production flow constructs a SigLIP class. (2) The Nomic output is bit-identical to before 3288c3f8: check index, stride, dtype and shape assumptions, and that every tensor and result is closed. A wrong vector would be persisted in the photo index. (3) pooler_output is read by name. (4) batchSize is 1. (5) The gradle change is test-only.
Follow-ups: section R human checks (state which can be verified statically).
EOF
cx siglip "$P/siglip.txt"
```

```bash
# Unit 12: tools
cat > "$P/tools.txt" <<'EOF'
UNIT tools (installer and read-only report scripts). Commits: 5a677357 7567369c d6370794 8c677558 24847920 95389805 2bda76d8. Read the scripts; do not run them.
Files: tools/install-trip-build.sh, tools/post-install-check.sh, tools/link-health-report.sh, tools/place-attempt-report.sh, tools/INSTALL-TRIP-BUILD.md, tools/PLACE-REPORT.md, docs/superpowers/plans/handoffs/device-session-runbook-2026-11-19.md section 14.
Invariants: (1) The installer is a dry run by default and --go is required. (2) A backup that fails validation (a plaintext db without db_encrypted=false, or a failed integrity_check on the copy) stops before anything is installed. (3) The report and check scripts never write to the device (no rm, pm clear, am force-stop or settings put) and quote every path. (4) The rollback guidance says, before the rollback command: turn mobile data and roaming off; Offline only users unbind remote endpoints; quarantine files/logs/pca.log.[2-9]*.
Follow-ups: audit P1-1 and P2-1 procedural fixes.
EOF
cx tools "$P/tools.txt"
```

```bash
# Unit 13: diag
cat > "$P/diag.txt" <<'EOF'
UNIT diag (Diagnostics suspect total and snippets from one query snapshot). Commit: 777b8ffa.
Files: phone ui/settings/Diagnostics.kt, memory db/ForgetDao.kt (observeSuspects), DiagnosticsViewModelTest.
Invariants: (1) The total and the rows come from one query with the same SUSPECT predicate. (2) Room invalidation covers memory, frame and frame_source. (3) A deleted memory's snippet is never shown.
Follow-ups: T-P2 (no test with more suspects than shown), T nits (DiagnosticsViewModelTest.kt:149-151 starts the watcher after the delete; stale KDoc at ForgetDao.kt:352).
EOF
cx diag "$P/diag.txt"
```

```bash
# Unit 14: zonedefer
cat > "$P/zonedefer.txt" <<'EOF'
UNIT zonedefer (a refused zone publish is deferred, not failed). Commit: 618a2778.
Files: phone hub/Hub.kt, zone/ZoneController.kt, ZoneR2Test.
Invariants: (1) A refused publish is deferred, retried on every fix and logged once. (2) After a reconnect the glasses get the current zone and pause state.
Follow-ups: J-P3 (a throwing pushSettings is logged as 'deferred reason=no_link'; the hello handler ignores pushSettings' return).
EOF
cx zonedefer "$P/zonedefer.txt"
```

```bash
# Unit 15: forgetpolicy
cat > "$P/forgetpolicy.txt" <<'EOF'
UNIT forgetpolicy (Forget policy table step 1, not wired, and the coverage map). Commits: e300ff9a ce0a679d e5cf06f6, plus the table rows edited in 80b92425 fea96193 ccfb0b62.
Files: memory forget/lifecycle/ForgetPolicy.kt, ForgetSource.kt, PolicyPlan.kt, Families.kt; tests ForgetPolicyTest, PolicyPlanTest, ForgetPolicyConformanceTest, ForgetPolicyPinConformanceTest; doc docs/superpowers/plans/2026-11-20-forget-coverage-map.md.
Invariants: (1) No production decision reads the table yet, so behaviour is unchanged. (2) Every cell matches the code: sample at least 10 cells, including TRACE x purge and the side-delete rows. (3) The conformance tests compare against the real code, not copies. (4) The map's 24 entry points and its gap table are true at HEAD.
Follow-ups: rulings J O1-O4; O7 (PLACE_CARD x user Forget is KEEP in the table but PlaceGraph.kt:31-35 withdraws the card); O5/O6 (now implemented: are the rows updated?).
EOF
cx forgetpolicy "$P/forgetpolicy.txt"
```

## 3. Batching for a limited Codex budget

One `cx` call = one review. To batch, pass several prompt files: `cx batch1-pin-mark "$P/pin.txt" "$P/mark.txt"` (the report goes to `codex-batch1-pin-mark-r1.md`; each unit keeps its own findings and follow-up table). Write the unit prompt files first, using the heredocs above without their `cx` line.

If only 3 reviews are possible:
1. **pin + mark** (about 2.1k net lines): the only data-loss path, and it is in every staged build. J-P2 is probably still open.
2. **map + log** (about 2.1k): with batch 1, this makes the whole conservative trip candidate `apks-v110-cand-4436d1a5` Codex-reviewed. That candidate is the one recommended for Charminar and Ramoji. The default-ON 300 MB log affects every user.
3. **mp + geo** (about 3.2k): the Offline only privacy fence. If the user will not use Offline only on the trips, swap this for **ds1 + ds2** (about 3.8k), because the data saver is the protection needed for roaming in Sri Lanka (23 Dec) and parked frames must never be shed.

Full coverage in 6 reviews: the 3 above, then 4. ds1 + ds2 + ds3 (about 4.1k; split off ds1 if the run times out), 5. shadow + siglip + diag (about 2.8k), 6. tools + zonedefer + forgetpolicy (about 2.2k).

Also owed but outside `18c1b76a..HEAD` (overnight-queue section E and the trip handoff line 294). Each was reviewed only by Opus or Claude: bddc0262 (hub-wp3 P2 fixes, no Codex r2); bde734ce and 5fbe0462 (zone fail-closed S1/S2: privacy, so do this before the others in this list); 73ef1ce3, 435e8bf0 and 8d6920d1 (Nexus wipe backoff); `git diff 0b71d96b..9d69e05a`, b07969ad and ed27c5ae (coordinate rule, landmark ids, per-QID rule); 1707b367, b573738e and aedb2d5f (multilingual Ask, flag off); 22c2e0bb and a1c19578 (shadow-eval route); 322f6319 (signal try-it); 18c1b76a and ed0ab932. Suggested 7th review: S1/S2 + coordinate rule + wipe backoff.

## 4. Controller checklist for applying findings

1. Check that each report file exists and starts with `VERDICT:`. If a run died, re-run only that unit and keep `-r1`. If Codex quota runs out again, use Opus at low effort (never Sonnet, never skip) and name the report `opus-<unit>-rr1.md`.
2. Verify every finding against the code before acting (skill `superpowers:receiving-code-review`). If a finding is wrong, record it as OVERRULED with path:line evidence in the outcome note.
3. On any P1, follow the loop rule. Fire a Sonnet fix agent at once, in its own worktree (one WP per worktree). Run the full build and tests through the Gradle lock, one job at a time. Then run Codex `-r2` on the fix diff only (`git diff <fix base>..<fix tip>`, same prompt plus "verify the fixes for codex-<unit>-r1.md"). Repeat until no P1 remains.
4. For a P1 that affects a staged build (`affects:` cand-4436d1a5 or train7-ccfb0b62), add a HOLD line to that folder's README and to the runbook, checklist and quickref pointers. Tell the user before any install. Never install without their "go". Devices run 18c1b76a.
5. Batch the P2s into one fix round per unit family, before Sri Lanka. Then update the follow-up tables in `overnight-queue-2026-11-20.md` (sections G-T: mark each item CLOSED (sha), OPEN or OVERRULED).
6. Send any finding that needs a product decision to `needs-user-2026-11-19.md` as a new section L, linked to rulings K(a)-(g) and J where relevant. Do not decide it yourself.
7. Fix any PRIVACY.md or runbook statement a finding contradicts in the same round.
8. After the fixes: run full verification (15/15), stage a new `apks-v110-train<N>-<sha>`, and add SUPERSEDED to older folders. A conservative candidate is restaged only if a pin, map or log P1 was fixed.
9. Record the outcome: a dated section in the trip handoff (resume pointer at the top) and a line in the morning summary. Commit the docs locally. Never push the feature branch, and never touch Rokid-Coder.
10. If a review finds nothing in a unit, still record "Codex READY at <sha>", so that unit leaves the "Opus-only" list.
