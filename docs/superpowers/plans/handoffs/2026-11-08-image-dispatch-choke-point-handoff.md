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

## Codex r12: PayloadSources and one description rule

### PayloadSources: every model call declares what stored content it carries

`com.rokid.context.model.PayloadSources(frameIds, memoryIds)` is ONE field on every request: `ModelRequest.sources`, `RoleCall.sources` and
`TestBedRequest.sources`. It replaces r11's `photoIds`/`derivedFromFrames`.

- **frameIds:** photos whose bytes, frame metadata, facts or captions are in the prompt.
- **memoryIds:** memories whose text is in the prompt. This includes photo cite ids (`frame:<id>`) and history dependencies.
- **`PayloadSources.NONE`:** declared at a site whose prompt holds no stored user content, for example explicit remember (the user's own words).

What `ModelRouter.call` does with them:

- Image refs are added to `frameIds`.
- After the permit and under the upload slot, `liveness.gone(frameIds)` checks that each frame row is present, not tombstoned, and that the wipe
  generation is unchanged.
- `liveness.goneMemories(memoryIds)` (new on `PhotoLiveness`) uses `ForgetFence.goneAmong`. A memory id counts as gone when its row is missing or
  `INVALIDATED`; a `frame:` cite counts as gone when its keyframe metadata is gone. A memory whose photo merely expired stays live, so no JPEG is
  required.
- Anything gone throws `PhotoForgottenException(frameIds, wiped, memoryIds)` without sending. Nothing retries it.
- Sources without a liveness are refused unsent (`ImagesUnguardedException`).

Sites covered (`PromptKeyImageUseTest.everyModelCallSiteDeclaresItsPayloadSources` fails for any `RoleCall(`/`ModelRequest(` above the providers that
omits `sources =`, or that declares sources without `liveness =`):

- **Ask** (`Retrieved.sources`: memories, photo cites, `fenceIds` history, stored photo lines).
- **Ask-debug replay**, which takes the new `liveness(gen)` constructor parameter.
- **Nudge** (`inputIds + fenceIds`).
- **Resummarizer** (its source memories).
- **DerivePass**: `DeriveText.complete(prompt, sources)`; `DeriveInputs.sources` reads the Moment's FRAME/MEMORY/CITE parts and each Scene member
  Moment's parts.
- **QueueDrainWorker**: the reprocess SUMMARIZE (source memories and frames), the critic review (reviewed memories and frames), explicit remember (NONE).
- **ExtractFollowUp critic.**
- **Canvas executors**: Prompt, Summary and Critic use `sourcesOf(item)`, which combines the item's photo, the photos its facts and captions cite,
  and `CanvasItem.sources`. That last field holds a Moment or Scene item's member frames and memories, set by CanvasRunner.
- **TestBed** (`TestBedInputs.Prepared.sources`).
- **Images**: ExtractSave, ClusterContract and IntentSamplers.
- **The legacy router `generate`** (NONE, and its images carry their refs).

### One qualifying-description rule (P1-1, P2-3)

`ForgetDao.DESCRIBED_BY_EXTRACTION` (and `STATE_DESCRIBED` for queries over `frame_state`) is the single "this photo is described" rule.
A memory describes a photo only when all of these hold:

- it is `OK`;
- it is not `derived`;
- it is not a canvas save (`rokid_cv_`);
- it cites that photo as delivered: `memory.frameId`, or a `frame_source` row with role FACT or CLUSTER_REP.

A CLUSTER_MEMBER link never describes a member photo, because the scene summary never saw it. The same rule is used by:

- `linkedPhotosDue` (retention);
- `NOT_DESCRIBED` (the skipped-photo exemption: a canvas citation no longer takes a photo out of the skipped rule);
- `describeDue` and `FrameStateDao.queueForDescribe` (night describe: a photo only excluded rows cite is now describable, so it is not stranded);
- `ForgetDao.describedRefs` (the `expirePhotos(requireLinked)` recheck in ForgetService, which replaces `remainingRefs`);
- `ForgetDao.strandedPhotos` (the new Diagnostics line `kf_stranded`, "N photos kept: only canvas/derived memories cite them").

Tests: ForgetServiceTest `aClusterMemberLinkNeverDescribesTheMember`, `aPhotoOnlyExcludedRowsCiteIsDescribableAndCounted` and
`retentionCountsOnlyOkExtractionDescriptions` (all on the real SQL). DiagnosticsTest `showsStrandedPhotos`.
ImageDispatchChokePointTest `aForgottenSourceMemoryDropsATextCallUnsent`. PhotoDerivedDispatchTest `frameLivenessOfSourceMemories`.

Not done: `ForgetDao.citedCount` (used only by tests) still counts any citation. Room stays at version 15.

## Codex r13 follow-up

1. **Canvas provenance travels with outputs.** `carriedSources(input, output)` (Executors.kt) is the union of `sourcesOf(input)` and what the new
   value cites. CanvasRunner and PipelineInterpreter each keep a per-item-id `carried` map, and every next node's `CanvasItem.sources` comes from it.
   So Vision(A+B) → Prompt (JSON) → Summary still declares B. Test: `CanvasProvenanceTest` holds Forget B at router admission; I confirmed it fails
   without the fix.
2. **Derive captures text, provenance and generation in one snapshot.** `DeriveInputs.snapshot` returns `Snapshot(lines, sources, generation)`
   from one transaction. The pre-call check compares the whole snapshot, and the call sends exactly those sources with a liveness bound to that
   generation (`DeriveText.complete(prompt, sources, generation)`).
   `PayloadSources.stored(...)` sets `required = true`. The router refuses a `required` request with empty provenance (`ImagesUnguardedException`)
   instead of bypassing the guard. Genuinely source-free calls keep using `PayloadSources.NONE`.
   `stored` is used by DerivePass (Moments with memory text), Nudge and Resummarizer.
   Tests: `DerivePassTest` (Forget and Delete all after the last check, via the `testBeforeSend` seam);
   `ImageDispatchChokePointTest.storedTextWithLostProvenanceIsRefused`.
3. **The grace timestamp uses only qualifying descriptions.** `linkedPhotosDue` takes the earliest createdAt over `DESCRIBES` memories, through
   memory.frameId or FACT/CLUSTER_REP rows only. Test (real SQL): `ForgetServiceTest.graceCountsFromTheFirstQualifyingDescription`. The case is
   deadline day 10, canvas citation day 1, qualifying description day 20: the photo is kept on days 20 and 22 and is due on day 23.
4. **Empty night describes count attempts by the shared rule.** `FrameStateDao.noteUncitedDescribed` uses `NOT ForgetDao.STATE_DESCRIBED`.
   Test (real SQL): `anEmptyNightDescribeCountsAnAttemptDespiteAnExcludedCitation`.
5. **DeleteAllFenceTest runs Delete all in its own coroutine.** The wipe starts while the upload is in flight, waits for the slot and is asserted
   to complete (generation moved, memories gone). Both the summary-job and the queued-Ask cases now use it.
   Residual: the Ask case cannot prove that the fence, rather than the completed wipe, removed the Ask row, because the save races the released wipe.

## Codex r14: three rules instead of point fixes

- **RULE A (outputs), at the execution boundary only.** `NodeProvenance` (Executors.kt) is created per node in CanvasRunner and PipelineInterpreter.
  It `consume`s every chunk before the run. Every output of that execution carries the union of the sources of ALL the chunk's inputs (the whole
  Vision batch, the whole window) plus what the value cites. An executor is `chunkLocal` (no state across chunks) only when it says so. Today that is
  Vision, LocalCritic, Critic, Prompt, Summary, Switch and MemoryWritePreview. Every other executor, and any new one, also carries everything the node
  consumed before (Window, Motion, Compare, SetFrequency, py.fn). The per-item `carriedSources` is gone; no executor sets provenance itself.
- **RULE B (requests).** `ExtractSave.extract(..., contextSources, contextProvenance)` declares the upstream text's sources on every attempt,
  including the single-photo fallback. Context text without provenance is refused (`require`). VisionExtractExecutor passes the union of its chunk's
  `sourcesOf`. Prompt, Summary and Critic already build their request sources from the same item (`sourcesOf(item)`), whose `sources` are the
  RULE A carried union.
- **RULE C (generation).** `PayloadSources.stored` is true for every value except `NONE`, including the primary constructor with empty id sets.
  `frames()` and `memories()` give `NONE` when empty. The router guards every stored request: liveness required, upload slot, `gone(frameIds)`
  (which also checks the wipe generation the liveness captured, even with no ids), then `goneMemories`. Only `NONE` skips the guard. A mark-only
  Derive (label text, no ids) is therefore dropped after a Delete all. This replaces r13's "required + empty = refused".
- **Tests (each confirmed failing before its fix):**
  - `CanvasProvenanceTest.aBatchMatesProvenanceTravelsWithEveryOutputOfTheBatch`: cross-photo caption, Vision(2) → Prompt → Summary.
  - `aVisionRequestDeclaresTheSourcesOfItsUpstreamJson`: Vision(2) → Prompt → Vision(1).
  - `ImageDispatchChokePointTest.storedTextWithNoIdsIsStillGuardedByTheGeneration`.
  - `DerivePassTest.aMarkOnlyMomentsLabelIsGuardedByTheCapturedGeneration`.
  - Property test: `everyBuiltInExecutorsOutputsCarryEveryInputsSources` runs every `Executors.all()` executor over a batch of 2+ inputs at the
    boundary.

## Codex r15: marks, the explicit-write payload, persisted provenance, audit

1. **Marks are dispatch sources.** `PayloadSources.markIds` carries `canvas_label` ids. `PhotoLiveness.goneMarks` re-checks them under the upload
   slot; `FrameLiveness` reports a mark gone when its label row is missing or the wipe generation moved.
   DerivePass declares every MARK part, and Moment and Scene canvas items declare their marks. A range Forget, which deletes the label without
   moving the generation, therefore drops "sleeping".
   Test: `DerivePassTest.aRangeForgetOfAMarkOnlyMomentDropsItsLabelAtAdmission` (failed without the fix).
2. **The explicit-write payload is stored content.** QueueDrainWorker declares `PayloadSources.stored(...)` and a liveness bound to the job's
   captured generation, so a Delete all at admission drops the call.
   Test: `ExplicitWriteFenceTest` (failed without the fix).
3. **Persisted provenance (no schema change).**
   - **Runtime rows:** CanvasRunner and PipelineInterpreter write every output's RULE A union into the row lineage as `mf:<frameId>` tokens (the
     existing S25 column), so a Forget of any carried photo deletes the row.
   - **Canvas saves:** `CanvasRunSave` reads those tokens. A fact derived from a gone photo is not saved, and every saved memory is linked to all
     carried photos.
   - **The link itself:** `ExtractSave.saveExtraction(carriedFrames = sentFrameIds by default)` adds `frame_source` rows with the new role value
     `CARRIED` (data, not schema). The same applies to ClusterSave (every representative of the call) and the queued reprocess summary (its frames).
   - **How Forget treats CARRIED:** a Forget of the photo removes the memory (`memoryIdsOfFrame` reads every role). A Forget of the memory never
     locks or deletes a CARRIED photo, because `sourceFramesOf` and `remainingRefs` skip that role. CARRIED never counts as a description.
   - **Save-time rollback:** a carried photo already forgotten at save time rolls the whole save back. ExtractSave returns StaleClaim, so the
     frames are retried; ClusterSave returns Stale.
   - **Dependencies:** Ask and Nudge rows now store every input (cited or not) as dependencies, so their Forget redaction covers them.
   - Test: `CanvasProvenanceTest.aSavedCanvasMemoryGoesWithAnyPhotoItWasDerivedFrom` (failed without the fix).
   - **Pre-existing bug fixed:** Save of a runner-made run always answered NO_MEMORY_NODE. The run stores the canonical pipeline, whose types are
     versioned (`memory.write@1`); CanvasRunSave now compares bare types.
4. **Property test through the real paths.**
   - `everyBuiltInExecutorThroughTheRealRunnerCarriesEveryInputOfItsExecution` runs every `Executors.all()` executor through CanvasRunner.
     It uses photo runs, and Scene runs for the number-port executors, with 3 inputs. It requires every output row to be OK with a value, and its
     lineage to contain every input photo of its execution (cumulative for non-chunkLocal executors).
   - `theLiveInterpreterCarriesEveryInputOfAVisionBatch` covers PipelineInterpreter: Prompt → Switch → Vision(batch 2).
   - Neither test swallows exceptions or applies provenance by hand.
5. **Audit of `PayloadSources.NONE` in production:**
   - The legacy `ModelRouter.generate`/`generateFromFrames`: test-only API, no production caller; images still carry refs and liveness.
   - Defaults of `RoleCall.sources`, `TestBedRequest.sources`, `TestBedInputs.Prepared.sources`, `CanvasItem.sources` and
     `ExtractSave.contextProvenance`: every production RoleCall/ModelRequest site declares `sources =` (scan test). Context text without
     provenance is refused.
   - TestBed inputs for SYSTEM, SUMMARIZE and EXPLICIT_REMEMBER: text the user types in the editor, not stored.
   - `frames()`/`memories()` over empty id lists: watch items (hub values in memory), a typed CRITIC test input.
   - The NodeProvenance/valueSources accumulators: start values, not declarations.
   - **Converted:** the QueueDrainWorker explicit write (item 2).

## Codex r16: CARRIED fixed and kept simple

- **Per saved memory.** `saveExtraction(carriedOf = { frameIds, index -> ... })` decides what each memory carries. By default a memory carries
  the photos of the model calls that saw its photos. `ExtractOutput.callGroups` and `ClusterOutput.callGroups` hold those photos, in memory only.
  `CanvasRunSave` passes the carried set of the output item that produced the memory, from the row lineage. The run's union is no longer used.
- **Invalidation, never photo deletion.** `ForgetDao.memoryIdsOfFrame` lists citing memories only. A Forget of photo B also handles
  `carriedMemoryIdsOfFrame(B)`: those memories go through `cascade(provenanceOnly = true)`. That removes their text, vectors, edges and
  `frame_source` rows, invalidates descendants and redacts asks and nudges. It never locks, tombstones, deletes or Moment-drops the photos they cite.
- **Queued reprocess.** The summary and all its provenance are written in one transaction. The transaction re-checks the wipe generation, the
  source memories and tombstones over the union: reloaded frames plus every photo the source memories cite or carry. The summary links all of
  them as CARRIED.
- **Helpers.** `PayloadSources.frames()` and `memories()` never collapse to `NONE`; only an explicit `NONE` is source-free. RoleRunner adds image
  refs only when there are some. TestBed assigns a liveness to any input whose sources are not `NONE`, so a NUDGE test with no ids is guarded.
- **`frame_source` query audit.** CARRIED is included only where a query cascades or invalidates. It is excluded from content, citations,
  description, retention, describe queue, clusters, statistics and UI. Per query:
  - **Included (cascade or invalidation):**
    - `WipeDao` delete-all.
    - `FrameSourceDao.deleteForMemory`, `deleteForMemories`, `deleteForFrames`.
    - `ForgetDao.deleteFrameSourcesFor`, `deleteFrameSourcesOfFrames`.
    - `ForgetDao.carriedMemoryIdsOfFrame`: CARRIED only.
    - `FrameSourceDao.forMemory`: provenance reads (the reprocess union, tests).
  - **Excluded:**
    - Forget citation set: `ForgetDao.memoryIdsOfFrame`, `MomentDao.memoryIdsOfFrames`, `ForgetDao.sourceFramesOf`, `remainingRefs`.
    - Photo file and tombstone cleanup: `orphanTombstonedFramePaths`, `pruneTombstones`, legacy uncited-frame retention.
    - Counts: `citedCount`, `CITED_NOT_DESCRIBED` / `strandedPhotos`, `EmbeddingDao.deletedPhotoCount` ("findable by text").
    - Night job and frame state: `FrameStateDao.describedCount`, `resetForReprocess`, `FrameAdoptionDao.awaiting` and `extracted`.
    - Moment content: `CanvasDao.momentMemories`, `momentVisibleTexts`.
    - UI and API: `CanvasDao.liveMemories` (frame API), `recentFrames` and `recentFramesBetween` (picker live count), `FrameSourceDao.memoriesForFrame`.
    - Export and purge: `MemoryOrphan.SQL`.
  - **Already role-filtered:** `describedAmong`, `DESCRIBES` / `DESCRIBED_BY_EXTRACTION` / `STATE_DESCRIBED` and the grace subquery
    (FACT/CLUSTER_REP), `NOT_A_REP` (CLUSTER_REP).
  - **No role filter needed:** `PhotoSearchDeps` (table-change signal only).
- **Tests (each confirmed failing under its own mutation):**
  - `ForgetServiceTest.forgettingACarriedPhotoNeverDeletesThePhotoTheMemoryCites`.
  - `ForgetServiceTest.contentReadersNeverShowACarriedMemoryAsThePhotosContent`.
  - `CanvasProvenanceTest.anUnrelatedPhotosForgetLeavesTheOtherSavedMemoryAndItsPhoto`.
  - `QueueDrainReprocessTest.aReprocessSummaryInheritsItsSourcesCarriedPhotos`.
  - `QueueDrainReprocessTest.aCarriedPhotoForgottenDuringTheCallDropsTheWholeSummary`.
  - `ImageDispatchChokePointTest.theIdHelpersNeverCollapseToNone` (no separate failing-first run; it asserts the new helper semantics directly).

## Codex r17: provenance follows the actual requests and edges

The rule: a memory's CARRIED set is exactly the frames whose image or derived text was inside the model request(s) that produced its text. It is
traced along the graph edges that feed memory.write, never by frame id, batch or run membership.

- **Per attempt.** `ExtractSave.call` records one `callGroups` entry per ATTEMPT: that attempt's own images, plus the sources of the context text
  only when context text was in its prompt. A batch that failed into singleton retries leaves only the singletons' groups. The Vision executor's
  context provenance is taken from the items whose upstream JSON is actually in the prompt (none without JSON). Each Vision output reports
  `NodeResult.exactSources`: the attempts that carried its photo. `NodeProvenance.of(value, exact)` uses that instead of the chunk union; every other
  executor keeps the conservative union.
- **Per edge.** `ItemFlow.Out` carries the value's sources and `ItemFlow.gather` returns, per input, the sources of the one edge that delivered it.
  CanvasRunner and PipelineInterpreter build each node's items from that, not from a per-frame map shared across branches. Each row's lineage
  gets its OWN provenance as `mf:` tokens, no longer accumulated per frame. `CanvasRunSave` rebuilds the outputs with each row's own sources and
  gathers along the save node's incoming edges, so a side branch never feeds the save.
- **One inheritance rule.** `ExtractSave.inheritedFrames(db, memoryIds)` returns every photo the memories cite or carry. The queued reprocess and
  the Forget resummarizer both use it inside their writing transaction, link the result as CARRIED and drop the write if any photo is
  forgotten. The resummarizer also removes a dropped summary's own `frame_source` rows.
- **Tests (each confirmed failing under its own mutation):**
  - `LiveExtractTest.singletonRetriesRecordOnlyTheirOwnPhoto`.
  - `CanvasProvenanceTest.aSideBranchNeverAddsItsSourcesToTheSave`.
  - `ResummarizeWorkerTest.aRebuiltSummaryInheritsItsSourcesPhotosAndGoesWithThem` (the Codex repro).

## Codex r18: rendered requests, replaced provenance, per-edge call lineage

1. **Context counts only if it was rendered.** `RoleResult.renderedVars` lists the placeholders the system preamble and template rendered.
   `ExtractSave` records a context's sources (`callGroups`, `ExtractedBatch.contextIncluded`) only when `{{context_sources}}` was substituted, and
   the Vision executor's exact sources follow. The dispatch-side declaration stays conservative.
   Commit 32a2f194; test `CanvasProvenanceTest.unusedContextIsNeverRecordedAsProvenance`.
2. **A retried summary replaces its provenance.** The queued reprocess summary's `frame_source` rows are deleted and re-inserted with its content
   in the same fenced transaction.
   Commit 8789e97b; test `QueueDrainReprocessTest.aRetriedSummaryReplacesItsProvenance`.
3. **Call lineage follows producing edges.** `ItemFlow.Out.callLineage` carries the S25 call tokens along the edge that produced the value. Each
   row's lineage is the frame's own member and mark tokens, plus the delivering edge's calls, plus its own call. A side branch's batch call no
   longer reaches another branch's rows.
   Commit 4f1b4b13; test `CanvasProvenanceTest.aSideBranchsSharedCallNeverReachesTheSoloBranchsRows`.

Each test failed before its fix.

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
