VERDICT: NOT READY

Static review of candidate `bb49cb5b`; checkout HEAD is now `5d56782a` (documentation only; candidate source unchanged). Paths below are under `android-companion/`.

1. [P1] services/memory/src/main/java/com/rokid/context/memory/db/WipeDao.kt:54 - An interrupted undo leaves `mark_cleanup_owed:<target>` containing a frame ID; Delete all excludes this prefix, and abandoned obligations have no pruning - Wipe the prefix, register its lifecycle, and prune settled obligations.
2. [P1] services/orchestration/src/main/java/com/rokid/context/orchestration/forget/ForgetActions.kt:258 - Concurrent range Forgets overwrite global `rangeFilesLeft`; a successful range can make another return DONE while its JPEG remains (`DataOpGate.kt:11` explicitly permits concurrent deletes) - Carry file-cleanup status in each range result.
3. [P1] services/memory/src/main/java/com/rokid/context/memory/forget/ForgetService.kt:77 - First photo Forget removes the row but cannot delete its JPEG; repeating that same Forget returns an empty file list and DONE while the JPEG remains - Recover outstanding paths for this frame from its cleanup obligation before returning DONE.
4. [P1] services/memory/src/main/java/com/rokid/context/memory/forget/ForgetService.kt:250 - Fact Forget lists only JPEGs; its sidecar survives while `applyFull` reports clean; v2 repeats this at `lifecycle/ForgetExecutor.kt:255` - Include sidecars in both cascade results and cleanup outbox entries.
5. [P1] services/orchestration/src/main/java/com/rokid/context/canvas/marks/MarkRecorder.kt:184 - Process death after photo Forget commits but before `putMeta` loses the obligation; retry sees neither label nor owed key and reports DONE with files remaining - Persist the obligation atomically with the photo cascade.
6. [P2] services/orchestration/src/main/java/com/rokid/context/work/FallbackParkCap.kt:15 - Repeated process death resets every park counter, permitting indefinite parking; documentation deliberately defers this - Persist bounded counters with wipe and pruning coverage.
7. [P2] phone/src/main/java/com/rokid/context/phone/ui/settings/mapzones/MapZonesScreen.kt:330 - Selected seed areas still lack edit/rename, cell progress and Show cells - Implement the deferred capabilities before declaring map P1 complete.

| A: mark-r2 finding | Status | Trace |
|---|---|---|
| #1 voice loses cleanup notice | CLOSED | `MarkRecorder.kt:238` → `MarkUtteranceCommands.kt:50` → `Utterance.kt:127` supplies pending wording for undo replies. |
| #2 failed JPEG/sidecar deletion reports DONE | STILL OPEN | `ForgetActions.kt:224,384` fixes failed **listed** paths; findings 2–4 retain false-DONE cases. |
| #3 second-gate refusal loses cleanup notice | CLOSED | `MarkRecorder.kt:184,188–191` → `cleanupStillOwed():118`; crash durability remains finding 5. |
| #4 voice upload called a save | CLOSED | `MarkUtteranceCommands.kt:46–48` catches upload before its parent exceptions. |
| Carried R1 #1 prune deletes new run | CLOSED | `CanvasRunner.kt:201–203` protects active/registered IDs; `CanvasDao.kt:230` orders by rowid. |
| Carried R1 #2 phone drops cache notice | CLOSED | `ProductionForget.kt:42` → `MemoryDetailViewModel.kt:160–166`. |
| Carried R1 #3 phone mislabels upload | CLOSED | `MemoryDetailViewModel.kt:144–167` preserves upload-specific copy. |

| B: mp-r2 finding | Status | Trace |
|---|---|---|
| #1 request privacy dispatch gap | CLOSED | `PlaceHttp.kt:90–94` rechecks inside IO before open; corrected queued-dispatch test at `PlaceHttpOfflineOnlyTest.kt:43`. |
| #2 VPN route | CLOSED | `OfflineOnlyNet.kt:64–73` rejects non-loopback with VPN. |
| #3 embedding key read | CLOSED | `Embedders.kt:36→39,62→63` checks before key access. |
| #4 cluster flag/cap | CLOSED | `KeyframePipeline.kt:595–604` propagates fallback status and cluster IDs. |
| #5 restart cap / I-P2-c | STILL OPEN | `FallbackParkCap.kt:15–23`; explicitly deferred in `open-backlog-2026-11-21.md:107–110`. |
| #6 first refresh on main | CLOSED | `DataSaverGraph.kt:203–207,219` schedules monitor refresh on IO. |
| #7 / audit P3-2 `.local` lookup | CLOSED | `OfflineOnlyDns.kt:17–21` refuses before delegate lookup. |
| AUTO eager VPN probe | CLOSED | `OfflineOnlyNet.kt:62–64` returns before probing. |
| I-P2-a / I-P2-b write ordering | CLOSED | `ModelPrivacyStore.kt:102–113` serializes/version-checks durable writes. |
| I-P2-d inventory | CLOSED | `DeletionGraph.kt:114` includes `last_choice`. |
| I-P2-e test omissions | CLOSED | Earlier coverage retained; `ClusterCallTest.kt:132` adds cluster propagation coverage. |
| I known durability window | STILL OPEN | `ModelPrivacyStore.kt:90–91` uses asynchronous `apply()` before durable commit. |
| J-P3 Saving state | STILL OPEN | Store final version check `:111` precedes unversioned UI callback at `ModelPrivacySettings.kt:87`. |
| J-P3 no_link wording | STILL OPEN | `Hub.kt:308–309` converts throwing push into deferred; `ZoneController.kt:366` labels it no_link. |
| audit P3-4 flow getters | CLOSED | `ModelPrivacyStore.kt:59–65` schedules loading asynchronously. |
| Model download guard allegation | CLOSED | Previously overruled separate download path remains documented in `PRIVACY.md:210`. |

| C: map-p1-r1 finding | Status | Trace |
|---|---|---|
| #1 circle protection bounds | CLOSED | `MapGeometry.kt:55–63` bounds rendered Mercator radius conservatively before outward rounding. |
| #2 pan/pinch arbitration | CLOSED | `MapCanvas.kt:206–253` arbitrates slop, multitouch and timeout through `TouchArbiter`. |
| #3 save/navigation race | CLOSED | `MapZonesModel.kt:86,160–165` freezes edits and matches submitted draft; screen `:162,175` blocks Back. |
| #4 unreadable-zone guard | CLOSED | `MapZonesScreen.kt:186,265–267` passes live protected state; model `:149` rechecks admission. |
| #5 accessibility | STILL OPEN | `MapCanvas.kt:168–179` adds move/resize/select actions; editable coordinates remain explicitly unbuilt (`map-p1-2026-11-24.md:37`). |
| #6 seed capabilities | STILL OPEN | `MapZonesScreen.kt:325–332`; documented deferral does not implement them. |

Cleanup scope: `applyFull():224` checks only this result’s distinct paths; an unrelated undeletable JPEG does **not** poison every later Forget. `mark_cleanup_owed` is absent from MemoryExport’s memories/asks JSON (`MemoryExport.kt:118–145`), but survives Delete all and abandoned retries without a bound.
No default AUTO refusal regression found. `mapZonesV2` remains OFF, including prefs errors (`ForgetShadow.kt:63`); pause enforcement still uses the existing editor/controller path.
No files edited; no Gradle, adb, tools scripts, builds or tests run.