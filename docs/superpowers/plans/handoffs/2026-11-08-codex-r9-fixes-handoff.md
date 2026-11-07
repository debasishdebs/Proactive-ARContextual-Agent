# Codex r9 fixes: handoff (2026-11-08)

Branch `feat/v099-watch` (worktree `pca-v098`). Frozen values unchanged: PROTOCOL_VERSION 1, TRACE_SCHEMA 1, plan hash f5782e0d8610, Room v15 (no schema change, no migration).
All six findings were confirmed by reading the code. Both P1s were also reproduced by `PhotoDispatchSafetyTest` against the pre-fix code: the Forget test uploaded the photo, and the 400 test returned `Saved(memories=2, frames=1)`. Both tests pass after the fix. None of the findings was wrong. One finding was wider than reported (see P1-2).

## P1-1: a forgotten photo could still be uploaded
Root cause: `KeyframeExtract` read the photo bytes, then `ModelRouter.call` waited for a call permit. Nothing checked again after the wait, so a Forget that completed during the wait did not stop the upload. The save fence only stopped the write.
Fix:
- `DispatchGuard` (`services/ai/.../model/LanguageModel.kt`) is a coroutine-context element. `ModelRouter.gated` runs every provider call that carries images through `guard.send` inside the permit, right before the bytes leave.
- `FrameDispatchGuard` (`work/frames/FrameDispatchGuard.kt`) takes the new `DataOpGate.dispatch` side. It then checks that every frame in the batch is still live (`PipelineRecords.framesByIds`, which is tombstone-aware) and that the wipe generation is unchanged. If a check fails, it throws before the call: nothing is uploaded and nothing is written. A dropped call returns `ExtractOutcome.Stale` (the forgotten frames count as unreadable and the live ones are released with no attempt used), or `Wiped`.
- `DataOpGate.dispatch` keeps an upload slot for the whole provider call. A delete (Forget, Delete all, retention, outbox) waits for running uploads, up to 30 s, then fails with `UploadInProgress`. That is a subclass of `WriteInProgress`, so the UI shows the existing "busy, try again" text for a save. An upload waits for a running or waiting delete, up to 30 s, then fails with `DispatchBlocked` and sends nothing. Exports and writes are not affected.
- The guard is installed in `KeyframeExtract` (pipeline, describe on demand and night drain), in the live shadow run (`LiveExtract.shadowAsync`), in cluster summaries (`KeyframeGraph.summarize`) and in the intent sampler (`IntentSamplers.classify`).
- Deviation: the bytes are still read before the permit. The send is now atomic with the liveness check, which is the "equivalent atomic guard" the brief allowed. Fully lazy loading would mean changing `ExtractPhoto` in every canvas executor and in `KeyframeGraph`. Not covered: the ask photo path (`AnswerService`) and the legacy `QueueDrainWorker` reprocess. Both already have their own write fences.

## P1-2: an HTTP 400 could produce a fabricated description
Root cause: `RoleRunner` set `requireImages` only for INTENT_CLASSIFY. When a VISION_EXTRACT photo call failed, `ModelRouter` retried it without the photo, and also passed it to a text-only SUMMARIZE endpoint or fallback. The model then wrote captions from the frame metadata alone. Those were saved as confidence-1.0 OK memories, and a saved memory means "described" to retention.
This was wider than reported: the text retry ran after ANY vision failure, not only a 400 (see the audit below).
Fix:
- `RoleRunner.PHOTO_INPUT_KEYS` = {INTENT_CLASSIFY, VISION_EXTRACT}: every attempt requires the photo.
- The canvas Prompt executor sets `requireImages` when `images=true`.
- The router logs `vision_refused_no_text_retry` and never retries as text.
- When the SUMMARIZE endpoint or the fallback has no vision, the original failure is rethrown, so a 400 stays a 400. The frame takes its normal attempts with backoff and parking (FAILED, then FAILED_FINAL; a describe-before-delete photo keeps its cooldown and parking). Without this it would have been "provider NOT_SET" and been released forever with no attempts counted.
- Nothing is written, so retention never treats the photo as described.

## P2-3: the night job and the catch-up could overlap
`NightRunLock` is one process-wide try-lock Mutex. The whole of `NightIndexWorker.doWork` runs under it: the overdue check, the drain, the index backfill and the `last_run_at` write (now `commit()`). A second run returns success and does nothing. I chose a Mutex over a persisted lease because WorkManager runs both jobs in the one app process, and a Mutex cannot outlive a killed process, so it needs no timeout.

## P2-4: scoring rejected `.v2` frame ids
`PUT runs/:id/scores` now checks `frameId` with `FRAME_ID` (`frameIdField`). `nodeId` keeps the strict rule. This was the only frame-id field in a request body that used `idField`.

## P2-5: "This answer used keyword matches only" could be false
The notice is now `"Search used keyword matching: <reason>"` (`AskActions.SEARCH_FALLBACK_PREFIX`). It describes only the search. The strings in the 2026-11-08 quality-fixes handoff were updated to match.

## P2-6: NO_INDEX said no model was configured
- `SemanticOff.NO_MODEL` (no EMBED binding) keeps the setup instructions.
- `NO_INDEX` (a binding exists but there is no index yet: the first build is pending or its first probe failed) now says the index is not built yet, when it builds, and to test the Embeddings endpoint if it never appears.
- `SemanticRetriever.embedBound` is a property, set by `AiGraph` from the bindings.
- The reason token from the debug route and quality-eval is now `NO_MODEL` when no model is bound.

## Audit: memories from the old text-only retry (read-only, nothing changed)
- No stored row records whether a photo was delivered. `memory` has no provenance flag, `frame_source` only links a memory to its frame, KeyframeExtract writes no `summary_trace` row, and no table records individual model calls.
- So these memories can only be found by matching timestamps against the log.
- In `scratchpad/nov7/phone_all.log` (11-01 to 11-05) there are 65 lines reading `vision failed, retrying as text`. 29 of them are followed by `PcPipeline extracted=N`, about 44 frames in all.
  - 21 of those batches followed an HTTP 400 (REJECTED).
  - 8 followed transient network errors (SocketException, InterruptedIOException, IOException, SSL, a 500).
  - Most were class EXTRACT, i.e. live captures.
- Likely affected rows: memories with id `rokid_kf_*` or `rokid_desc_*`, `type='caption'` and fact rows, whose `createdAt` falls within about 0 to 30 s after one of those log lines. Their frames are `EXTRACTED`.

Proposed review path (not implemented):
1. A read-only Diagnostics query. It takes the log timestamps and lists the candidate memory ids, frame ids and created times.
2. A one-tap "Mark for review" behind a confirmation dialog. It moves those memories from OK to NEEDS_REVIEW with the existing `moveReviewState`.
3. Retention should also stop counting a NEEDS_REVIEW caption as "described", or the photos stay deletable. That is a code change that needs its own ruling.
4. Optionally, re-queue those frames for a real vision describe (state CAPTION_QUEUED).

## Tests added or changed
- `PhotoDispatchSafetyTest` (new).
- `DataOpGateTest`: 2 new tests for the upload slot.
- `ModelRouterTest.aRefusedPhotoCallNeverFallsToATextOnlySummarize`.
- `NightIndexWorkerTest.nightAndCatchUpNeverRunConcurrently`.
- `CanvasApiTest.scoringAcceptsDotV2FrameIdsAndRejectsUnsafeOnes`.
- `MemoryRetrieverTest.noModelAndNoIndexAreToldApart`.
- `AnswerServiceTest` and `RetrievalSettingsTest` updated for NO_MODEL and the new notice.
