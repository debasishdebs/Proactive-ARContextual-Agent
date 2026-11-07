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

## Not done here: retention and pre-fix captions (codex r10 item 4, waits for a user ruling)

`ForgetDao.linkedPhotosDue` (services/memory/.../ForgetDao.kt, the `@Query` at ~line 130) treats any citing memory (`memory.frameId` or `frame_source`)
as the description that lets retention delete the photo. Captions saved before this fix may have been guessed from metadata.
The hook would be that query's `EXISTS (…)` clause, which would also need a "delivery-proven" marker on the citing row. That is a Room migration
(version 15 → 16) and needs the ruling. An alternative without a migration is a one-off quarantine of pre-fix `caption`/`cluster_summary` rows,
for example re-marking them NEEDS_REVIEW and excluding NEEDS_REVIEW citers in that clause. Nothing in this change touches retention.

## Tests

- services/ai `ImageDispatchChokePointTest`: the order (permit, slot, re-check, send, release), a forgotten photo never sent or retried anywhere,
  wiped, unguarded refused, an optional text retry re-checked and without proof, a provider without images failing closed, a stored local+Vision row
  refused at run time, a local endpoint refused Vision at save and bind, the native provider refusing a photo, RoleRunner requiring images for every
  REQUIRED key (CLUSTER_SUMMARY included), refusing images on a NONE key and refusing a REQUIRED key without a photo.
- services/ai `PromptKeyImageUseTest`: scans every PromptKey and every image call site.
- orchestration `ExtractSaveTest.aResultWithoutDeliveryProofIsNeverSavedAsAMemory`, `theExtractionCarriesTheRoutersDeliveryProof`,
  `ClusterSaveTest.anOutputWithoutDeliveryProofIsNeverSaved`. The existing `PhotoDispatchSafetyTest` (end to end through KeyframeExtract) still passes.
