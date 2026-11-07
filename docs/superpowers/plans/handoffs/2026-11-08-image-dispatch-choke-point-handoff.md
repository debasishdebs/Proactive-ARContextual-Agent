# Image-dispatch choke point (codex r10) — handoff, 2026-11-08

Branch `feat/v099-watch`. Codex r10 found that two invariants were each enforced by every caller on its own, and that some callers missed them:
"a photo is uploaded only while it is still live" and "a description is never produced unless its photo reached the model".
Both invariants are now enforced in one place. The per-caller guards are deleted.
Room stays at version 15. No protocol, TRACE_SCHEMA or plan-hash (f5782e0d8610) change.

## The choke point: `ModelRouter.call` (services/ai)

Every provider call of every model call runs through `ModelRouter.call`: RoleRunner, TestBed, canvas PromptExecutor, fallbacks, the frame chain
(`completeFromFrames`) and text retries. For a request about stored photos (image refs or `ModelRequest.photoIds`), the router does these steps in order:

1. **Guarded or refused.** Every `ModelImageInput` must have a `ref` (its frame id), and the request must have a `PhotoLiveness`.
   If either is missing, the router throws `ImagesUnguardedException` before anything is sent. It is not retried on the fallback.
2. **Capability.** A `requireImages` request fails closed with `ImagesUnsupportedException` (no output) when the endpoint cannot deliver images.
   That is the case when the endpoint lacks `Capability.VISION`, when its kind is in `EndpointRules.NO_IMAGES` (`LLAMACPP_LOCAL`), or when
   `ModelProvider.acceptsImages` is false (`LlamaCppNativeProvider`). That provider also throws by itself if a photo reaches it.
3. **Inside the call permit:** take `liveness.holdUploadSlot` (the DataOpGate dispatch side; a delete waits for it), run `liveness.gone(photoIds)`,
   send, then release once the network write returns. If any photo is gone, or Delete all ran since the load, the router throws
   `PhotoForgottenException(frameIds, wiped)` without sending. Nothing retries it: not the text retry, the fallback endpoint, the SUMMARIZE frame chain,
   RoleRunner's corrective retry, ExtractSave's batch-to-singles split or ClusterContract's solo retry. Every caller treats it as "gone, write nothing".
4. **Text retry** after a failed image call happens only when the request does not require images. The retry keeps `photoIds`, so it is re-checked too.
5. **Delivery proof.** `RoutedReply.imagesDelivered` is true only for a reply whose call carried the photos. `RoleResult.imagesDelivered` passes it on.
   `ExtractOutput` and `ClusterOutput` carry it as well. It is held in memory only and is never decoded from a model reply.

The one production `PhotoLiveness` is `work/frames/FrameLiveness.kt`, which replaces `FrameDispatchGuard` and the coroutine-context `DispatchGuard`.
A photo is live while its `frame` row exists, it is not tombstoned, and the wipe generation is unchanged. Callers only build one and attach it:

| Caller | Liveness |
|---|---|
| KeyframeExtract (pipeline, describe-on-demand, ask extraction) | `FrameLiveness.forApp(app, generation)` → `ExtractSave.extract` / `LiveExtract.batch` / shadow |
| KeyframeGraph.summarize (CLUSTER_SUMMARY) | `ClusterContract.call(..., liveness)` (required parameter) |
| IntentSamplers (INTENT_CLASSIFY) | per call |
| QueueDrainWorker (VISION_EXTRACT per photo, SUMMARIZE over frames) | per job; a forgotten photo means `StalePayloadException` |
| AnswerService ask and Ask-debug replay | `FrameLiveness.forGraph(memory, generation)`; image refs are set from the hit's frame id in RagCore/AnswerService |
| Canvas runs (CanvasBackendImpl), live/shadow interpreter (KeyframeGraph) | `RunContext.liveness`; PromptExecutor and VisionExtractExecutor attach it; a forgotten photo means `frame_forget`, so the run is STOPPED_FORGET |
| Prompt test bed | `TestBedInputs.Prepared.liveness` → `TestBedRequest.liveness` |

Image refs are set at the single byte source, `FrameInputs.fromFrames` (ref = frame id). `ExtractSave`, `ClusterContract`, `PromptExecutor` and
`IntentSamplers` also stamp the ref explicitly.

## Photo-requiring roles: one declaration

`PromptKey.imageUse` (`ImageUse.NONE | OPTIONAL | REQUIRED`) and `PromptKey.requiresImage`:

- REQUIRED: `VISION_EXTRACT`, `CLUSTER_SUMMARY`, `INTENT_CLASSIFY`. Canvas photo prompts are not PromptKeys: PromptExecutor sets
  `requireImages = images`, and describe-on-demand runs VISION_EXTRACT.
- OPTIONAL: `ANSWER` (photo recall) and `SUMMARIZE` (over frames).
- NONE: everything else. RoleRunner refuses images on a NONE key (`ImagesUnguardedException`). It also refuses a REQUIRED key with no images
  (`ImagesUnsupportedException`). It sets `requireImages = key.requiresImage` on every attempt, so no retry drops the photo.

A failure writes nothing. KeyframeExtract, KeyframeGraph and QueueDrainWorker turn a missing delivery proof into a retryable failure
(queued with backoff).

## Saves refuse descriptions without proof

- `ClusterSave.save` refuses without `out.imagesDelivered` and returns `ClusterSaveResult.Undelivered`. KeyframeGraph throws that as a retryable failure.
- `ExtractSave.saveExtraction` refuses without `output.imagesDelivered` when the output has captions or would be saved trusted (not NEEDS_REVIEW).
  It returns `ExtractSaveResult.Undelivered`.
- **Behaviour change, canvas Save:** a stored canvas run keeps no delivery proof (that would need a Room column), so `CanvasRunSave` no longer proposes
  or saves captions. Facts still save as NEEDS_REVIEW, as before. Tests in CanvasRunSaveTest and ResidueOracleTest were updated to match.

## How to add an image role safely

1. Add the `PromptKey` with the right `ImageUse`. A key on a VISION role must be `REQUIRED`: `PromptKeyImageUseTest` enforces this and fails
   for any `RoleCall(PromptKey.X, … images = …)` (or test-bed photo input) on a key declared NONE.
2. Load the bytes through `FrameInputs` / `FramePhotos` (ref set), or stamp `ref = frameId`.
3. Pass `liveness = FrameLiveness.forApp(app, generation)`, with the generation read before the load, on the `RoleCall` (or `ModelRequest`).
4. Catch `PhotoForgottenException` and treat its `frameIds` as gone. Never retry, never fall back to text.
5. If the output is persisted as a description, copy `RoleResult.imagesDelivered` into it and make the save refuse without it.
6. A new provider that cannot send images must override `ModelProvider.acceptsImages = false`. Add its kind to `EndpointRules.NO_IMAGES`, so the editor
   refuses Vision on it (save error `NO_IMAGES_MESSAGE`) and the binding check refuses VISION roles on it.

## Codex r11 follow-up

- **Photo-derived text is guarded like a photo.** `RoleCall.derivedFromFrames` feeds `ModelRequest.photoIds`. The router already re-checks every
  `photoIds` entry with the liveness, inside the permit and under the upload slot, even when no image is attached. These callers now declare it:
  CRITIC in `ExtractFollowUp.modelCriticPasses`/`trusted` (from KeyframeExtract, DescribeOnDemand and QueueDrainWorker), QueueDrainWorker's
  critic review, the canvas CriticExecutor, the PromptExecutor frame line (also for text-only prompts), and the Ask and Ask-debug photo lines.
  `PromptKeyImageUseTest.everyCallWithPhotoDerivedTextNamesItsPhotos` fails for any RoleCall whose enclosing function builds frame lines without
  `derivedFromFrames`/`images` and a `liveness`.
- **The live seam ends on a forgotten photo.** The interpreter reports a dropped call as `frame_forgotten`. `LiveExtract.batch` then re-reads which
  photos are gone and throws `PhotoForgottenException`. It no longer runs a fallback extraction.
- **Retention counts only real descriptions.** `ForgetDao.DESCRIBES`/`DESCRIBED_BY_EXTRACTION`, used by `linkedPhotosDue`, needs no migration.
  A citing memory counts only if all three hold: it is `reviewState = 'OK'`, it is not `derived`, and it is not a canvas save. Canvas saves are told
  apart by the origin prefix `rokid_cv_`, which `CanvasRunSave` stamps on every row it writes. The photo paths (`rokid_kf_`, `rokid_desc_`,
  `rokid_cl_`, `rokid_mem_`) write OK facts and captions under their own prefixes. An allowlist of those prefixes would be stricter, but fixtures
  across the retention tests use arbitrary ids, so the canvas origin is excluded instead. Canvas facts stay searchable memories.
  Test: `ForgetServiceTest.retentionCountsOnlyOkExtractionDescriptions` runs the real SQL on four photos. The canvas-saved one, the one with a
  NEEDS_REVIEW fact and the one with a derived re-summary are not due; the one with an OK extraction caption is due.
  Side effect: a photo whose only citers are excluded is now neither deleted nor picked by the night describe job, because `describeDue` still means
  "no memory cites it". It is kept past its window, which fails safe. Aligning `describeDue` with the new rule changes night-job behaviour, so it is
  left for a ruling.

## Still open: pre-fix captions (codex r10 item 4, needs a ruling)

The existing columns cannot tell a caption written before this fix by the old text-only retry (guessed from metadata) from a real one. Both have the
same id prefix, `reviewState = OK`, confidence 1.0 and `frame_source` rows, and no column records image delivery. Only a schema change can separate
them: a delivery-proof column, which means a Room migration from version 15 to 16. The other option is a policy decision to quarantine or re-describe
every pre-fix caption, for example by `createdAt` before this commit. The hook for either is `DESCRIBES` in `ForgetDao`. No migration was made.

## Tests

- services/ai `ImageDispatchChokePointTest`: the order (permit, slot, re-check, send, release), a forgotten photo never sent or retried anywhere,
  wiped, unguarded refused, an optional text retry re-checked and without proof, a provider without images failing closed, a stored local+Vision row
  refused at run time, a local endpoint refused Vision at save and bind, the native provider refusing a photo, RoleRunner requiring images for every
  REQUIRED key (CLUSTER_SUMMARY included), refusing images on a NONE key and refusing a REQUIRED key without a photo.
- services/ai `PromptKeyImageUseTest`: scans every PromptKey and every image call site.
- r11: `PhotoDerivedDispatchTest` (CRITIC over a forgotten photo never sent; live photo re-checked; no liveness refused),
  `LiveExtractTest.aForgottenPhotoEndsTheLiveBatchWithoutAFallback`, `ForgetServiceTest.retentionCountsOnlyOkExtractionDescriptions`.
- orchestration `ExtractSaveTest.aResultWithoutDeliveryProofIsNeverSavedAsAMemory`, `theExtractionCarriesTheRoutersDeliveryProof`,
  `ClusterSaveTest.anOutputWithoutDeliveryProofIsNeverSaved`. The existing `PhotoDispatchSafetyTest` (end to end through KeyframeExtract) still passes.
