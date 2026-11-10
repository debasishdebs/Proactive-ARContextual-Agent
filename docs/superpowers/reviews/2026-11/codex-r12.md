**NOT READY — 2 P1 findings, 1 P2.** Verified HEAD is `815c1552` on `feat/v099-watch`.

1. **P1 — Cluster membership still authorizes deletion without describing that photo.** [ClusterSave.kt:71](/Users/debasishkanhar/Desktop/rokid/pca-v098/android-companion/services/orchestration/src/main/java/com/rokid/context/work/frames/scene/ClusterSave.kt:71) writes an `OK`, non-derived summary, then cites **all members**, including photos never delivered to the model, at line 77. [ForgetDao.kt:244](/Users/debasishkanhar/Desktop/rokid/pca-v098/android-companion/services/memory/src/main/java/com/rokid/context/memory/db/ForgetDao.kt:244) accepts those `CLUSTER_MEMBER` links as descriptions.

   Reproduced with the actual SQL: an unseen, low-salience cluster member plus an excluded Canvas-summary `FACT` citation is returned by `linkedPhotosDue`. The Canvas citation removes it from skipped-photo eligibility; the representative-only cluster summary supplies the deletion authorization. Neither describes that individual photo. Also, [ForgetService.kt:122](/Users/debasishkanhar/Desktop/rokid/pca-v098/android-companion/services/memory/src/main/java/com/rokid/context/memory/forget/ForgetService.kt:122) rechecks only `remainingRefs`, rather than the qualifying-description predicate.

2. **P1 — Photo-derived text still escapes the dispatch guard.** Forget/Delete all can complete while these requests await a permit; [ModelRouter.kt:192](/Users/debasishkanhar/Desktop/rokid/pca-v098/android-companion/services/ai/src/main/java/com/rokid/context/ai/ModelRouter.kt:192) then sends their stale text. Later output/commit fences cannot undo disclosure. Remaining sites:

   | Call site | Missing coverage |
   |---|---|
   | [SummaryExecutor, Executors.kt:264](/Users/debasishkanhar/Desktop/rokid/pca-v098/android-companion/services/orchestration/src/main/java/com/rokid/context/canvas/Executors.kt:264) | Upstream facts/captions/JSON; no photo IDs or liveness. |
   | [PromptExecutor, Executors.kt:200](/Users/debasishkanhar/Desktop/rokid/pca-v098/android-companion/services/orchestration/src/main/java/com/rokid/context/canvas/Executors.kt:200), [CriticExecutor:149](/Users/debasishkanhar/Desktop/rokid/pca-v098/android-companion/services/orchestration/src/main/java/com/rokid/context/canvas/Executors.kt:149) | Guards only `item.photo.frameId`, omitting other fact-source photos; Moment/Scene items have an empty placeholder photo. |
   | [AnswerService.kt:249](/Users/debasishkanhar/Desktop/rokid/pca-v098/android-companion/services/orchestration/src/main/java/com/rokid/context/orchestration/AnswerService.kt:249), [AskDebug.kt:155](/Users/debasishkanhar/Desktop/rokid/pca-v098/android-companion/services/orchestration/src/main/java/com/rokid/context/canvas/AskDebug.kt:155) | Guards recalled stored photos, not photo-derived retrieved memories or history dependencies. |
   | [NudgeEngine.kt:102](/Users/debasishkanhar/Desktop/rokid/pca-v098/android-companion/services/orchestration/src/main/java/com/rokid/context/orchestration/nudge/NudgeEngine.kt:102) | Recent photo memories, photo lines and nudge history; no dispatch liveness. |
   | [ResummarizeWorker.kt:141](/Users/debasishkanhar/Desktop/rokid/pca-v098/android-companion/services/orchestration/src/main/java/com/rokid/context/orchestration/forget/ResummarizeWorker.kt:141) | Surviving source-memory text; no dispatch guard. |
   | [RouterDeriveText, DerivePass.kt:37](/Users/debasishkanhar/Desktop/rokid/pca-v098/android-companion/services/orchestration/src/main/java/com/rokid/context/canvas/moment/DerivePass.kt:37) | Moment/Scene visible memories and summaries; pre-call checks occur before router admission. |
   | [QueueDrainWorker.kt:301](/Users/debasishkanhar/Desktop/rokid/pca-v098/android-companion/services/orchestration/src/main/java/com/rokid/context/work/QueueDrainWorker.kt:301), [CRITIC:330](/Users/debasishkanhar/Desktop/rokid/pca-v098/android-companion/services/orchestration/src/main/java/com/rokid/context/work/QueueDrainWorker.kt:330) | Only attached/reloaded frames are guarded; secondary `frame_source` provenance and expired-photo memories remain uncovered. |
   | [TestBed.kt:89](/Users/debasishkanhar/Desktop/rokid/pca-v098/android-companion/services/ai/src/main/java/com/rokid/context/ai/run/TestBed.kt:89) | Text-only CRITIC frame metadata, NUDGE memories and ANSWER memory/photo lines receive no corresponding photo-ID guard. |

   **Yes, Ask needs a guard for photo-derived memories.** It must check source-memory liveness and wipe generation inside dispatch protection. Requiring an existing JPEG for every memory would incorrectly reject legitimate memories whose photos expired. This residual is **P1 privacy disclosure**, despite depending on timing.

3. **P2 — Excluded citations can strand photos past retention.** [ForgetDao.kt:154](/Users/debasishkanhar/Desktop/rokid/pca-v098/android-companion/services/memory/src/main/java/com/rokid/context/memory/db/ForgetDao.kt:154) and [FrameDaos.kt:295](/Users/debasishkanhar/Desktop/rokid/pca-v098/android-companion/services/memory/src/main/java/com/rokid/context/memory/db/FrameDaos.kt:295) still require **zero citations** before describing. SQL reproduction confirms Canvas-only/derived-only citations can block both description and expiry.

   Ordinary SHORT Canvas rows eventually expire, so this is not universally permanent. Excluded facts typed `caption`/`cluster_summary` can survive under the default FOREVER settings, however; there is no hard storage cap. Users see aggregate usage through [StorageUsage.kt:22](/Users/debasishkanhar/Desktop/rokid/pca-v098/android-companion/phone/src/main/java/com/rokid/context/phone/ui/status/StorageUsage.kt:22) and the generic storage banner, but no explanation/count of these stranded photos. P2: retention/storage edge case; no demonstrated crash.

The production writer inventory is:

| Writer/path | Can create the unsafe qualifying photo citation? |
|---|---|
| `ExtractSave`: keyframe, queued extraction, DescribeOnDemand/night description | **No:** OK facts/captions require image-delivery proof. |
| `ClusterSave`: facts/captions | **No:** proof required and citations restricted to delivered representatives. |
| `ClusterSave`: scene summary/member links | **Yes:** finding 1. |
| `CanvasRunSave` | Text-only Prompt→Summary saves are `derived=true`; all saved IDs are excluded by `rokid_cv_`. Vision facts lack persisted proof at replay-save time, but originate from guarded extraction. |
| `LiveExtract` / `PipelineInterpreter` | No separate memory writer; outputs pass through `ExtractSave` and its proof check. |
| Explicit remember (`QueueDrainWorker:356`) | **No photo citation:** `frameId=null`; model-provided frame sources are not persisted. |
| Legacy import / `PipelineRecords.appendMemory` | **No photo citation:** `LegacyImporter.toMemory` sets `frameId=null`. |
| Voice/Ask “remember”, notes | `NOTE_ADD` writes the separate notes table; voice marks write labels, followed by normal extraction. |
| Nudge | Writes nudge/proposal rows, not memory/fact rows; accepted notes use the separate notes table. |
| Priority reprocess / `Resummarizer` | Summaries are `derived=true`, with memory-source edges. |
| `MemoryWriteExecutor` | Absent. Current `MemoryWritePreviewExecutor` only previews; `CanvasRunSave` performs persistence. |
| DAO/repository inserts | `MemoryDao.insert/insertIgnore`, `MemoryRepository.add/addIgnore`, and `PipelineRecords.appendFact` are generic, proof-unchecked primitives; all production callers are covered above. Review/text edits preserve existing citations. No additional fact-table writer found. |

The original follow-up CRITIC now supplies IDs/liveness. The direct forgotten-photo LIVE path now terminates through `FRAME_FORGOTTEN` without fallback; extraction/router retries do not retry `PhotoForgottenException`.

Deletion-path review covered retention, skipped-photo expiry, Forget/range cascades, Delete all, sweep/outbox cleanup, ingest duplicate/wipe cleanup, synthetic-frame purge and temporary-file cleanup. No separate phone-photo cache eviction bypass was found. The glasses outbound buffer retains its independent capacity/72-hour shedding policy.

Verification: static call/writer tracing plus in-memory execution of the actual retention SQL. Android tests were not run in the read-only workspace.