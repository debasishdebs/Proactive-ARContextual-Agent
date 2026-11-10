**NOT READY — 3 P1 findings.** Reviewed `5470aa5a`, the r18 diff, and `815c1552..5470aa5a`.

1. **P1 — Vision records context that the request never included, causing unrelated-memory deletion.** [ExtractSave.kt:147](/Users/<user>/Desktop/rokid/pca-v098/android-companion/services/orchestration/src/main/java/com/rokid/context/orchestration/ExtractSave.kt:147), [Executors.kt:149](/Users/<user>/Desktop/rokid/pca-v098/android-companion/services/orchestration/src/main/java/com/rokid/context/canvas/Executors.kt:149).

   **Repro:** over unrelated A/B outside each other’s Forget radius, run `Vision(batch=2) → Prompt → Vision(batch=1, live/default) → memory.write`. The Prompt output carries A+B provenance. The default final Vision template omits `{{context_sources}}`, so its A request contains only A’s image and metadata. Nevertheless, nonblank supplied context makes its recorded group—and saved A memory—carry B. Forget B deletes that independent A memory.

   Provenance must reflect the rendered request, rather than supplied but unused variables.

   **Introduced after `815c1552`:** CARRIED persistence in `17140f2c`; remains in r18.

2. **P1 — Retried queued summaries retain provenance from overwritten results.** [QueueDrainWorker.kt:324](/Users/<user>/Desktop/rokid/pca-v098/android-companion/services/orchestration/src/main/java/com/rokid/context/work/QueueDrainWorker.kt:324).

   The deterministic summary ID is upserted, but CARRIED rows are only inserted; previous rows are never cleared.

   **Repro:** reprocess MA/MB and photos A/B into a MEDIUM summary. Cancel the worker after commit but before completion bookkeeping; cancellation postpones the job. Before retry, retention expires SHORT source MA and photo A, leaving the summary. Retry within the job TTL: it now summarizes only MB/B under the same summary ID. Old `CARRIED A` survives alongside B. Forget A deletes the replacement B-only memory.

   Replace the summary’s provenance atomically with its replacement content.

   **Introduced after `815c1552`:** `17140f2c`; the r16 transaction and r18 helper preserve this omission.

3. **P1 — Shared call lineage still lets a side branch erase independent completed results.** [CanvasRunner.kt:673](/Users/<user>/Desktop/rokid/pca-v098/android-companion/services/orchestration/src/main/java/com/rokid/context/canvas/CanvasRunner.kt:673), [CanvasLineage.kt:13](/Users/<user>/Desktop/rokid/pca-v098/android-companion/services/orchestration/src/main/java/com/rokid/context/canvas/CanvasLineage.kt:13).

   **Repro:** order nodes `[photos, sideVision(batch=2), soloVision(batch=1), memory.write]`, connecting `photos→sideVision` and `photos→soloVision→memory.write`. Use unrelated A/B outside each other’s Forget radius. Although edge provenance is now separate, the per-frame `lineage` map retains the side branch’s shared call token. Solo A’s stored rows inherit that token. Forget B before saving: batch-token cleanup deletes solo A’s independent extraction and preview; subsequent Run-and-Save loses A’s result.

   Call lineage must follow the producing edges as well.

   **Predates `815c1552`:** the same per-frame accumulation and batch-token deletion exist at that baseline.

The plain singleton-fallback case and direct save-branch CARRIED mixing are fixed. The resummarizer now inherits ordinary and carried photo sources inside its fenced transaction; those links survive source-memory expiry.

The audited r12–r16 retention, dispatch and version protections remain present. No additional under-linking or permanent deadlock was found by static tracing. Room **15**, protocol **1**, and trace schema **1** are unchanged.

Verification: static tracing and in-memory SQLite checks using repository queries and schema v15. **No Gradle run or workspace modifications.**