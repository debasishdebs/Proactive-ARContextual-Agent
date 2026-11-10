**NOT READY.** Verified `305ed3d6` on `feat/v099-watch`.

1. **P1 — Mark-only Derive still uploads forgotten labels.** [DerivePass.kt:125](/Users/<user>/Desktop/rokid/pca-v098/android-companion/services/orchestration/src/main/java/com/rokid/context/canvas/moment/DerivePass.kt:125) omits MARK identities from dispatch provenance.

   **Repro:** create a photo-less Moment with intent “sleeping”; pause Derive at router admission after its final snapshot check. Complete range Forget covering that Moment, then release. Forget removes the label without changing the wipe generation. Empty frame/memory sets pass liveness, and “sleeping” is uploaded. The later write fence cannot undo disclosure.

2. **P1 — Queued explicit-write text incorrectly declares `NONE`.** [QueueDrainWorker.kt:351](/Users/<user>/Desktop/rokid/pca-v098/android-companion/services/orchestration/src/main/java/com/rokid/context/work/QueueDrainWorker.kt:351) sends persisted `raw_input` without upload protection or the job’s captured generation.

   **Repro:** enqueue an explicit write containing private text; hold EXPLICIT_REMEMBER at router admission; complete Delete all; release admission. The request sends the deleted queue payload. WorkManager cancellation is asynchronous and supplies no dispatch fence. This violates the requested restriction on `PayloadSources.NONE`.

3. **P1 — Saved Canvas memories lose the carried source union.** [CanvasRunSave.kt:190](/Users/<user>/Desktop/rokid/pca-v098/android-companion/services/orchestration/src/main/java/com/rokid/context/canvas/CanvasRunSave.kt:190) reconstructs provenance solely from fact citations; Summary emits only the current item’s frame ID.

   **Repro:** with A/B outside each other’s Forget radius, run `Vision(batch=2) → Prompt → Summary → LocalCritic → memory.write`, preserving a B-specific detail in A’s summary. Save, then Forget B. The saved A summary has only A’s citation and survives; Ask can subsequently upload B-derived text. Runtime union propagation does not reach persistence. **This gap predates the reviewed range.**

Both execution boundaries correctly apply the runtime union; all seven `chunkLocal` executors are stateless across chunks. Extraction forwards context provenance through single-photo fallback. Retention fixes remain intact; no permanent production deadlock was found. Frozen versions remain **15 / 1 / 1**.

The tests miss these scenarios. The all-executors property test swallows exceptions, accepts error/null results, and manually applies provenance, so it cannot establish every executor/interpreter path. **Gradle and mutation tests were not run.**