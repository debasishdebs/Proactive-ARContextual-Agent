**NOT READY — 3 P1 findings.** Reviewed `287d996d` on `feat/v099-watch`, including `815c1552..287d996d`.

1. **P1 — Outputs still lack the union of their inputs’ sources.** [CanvasRunner.kt:769](/Users/<user>/Desktop/rokid/pca-v098/android-companion/services/orchestration/src/main/java/com/rokid/context/canvas/CanvasRunner.kt:769), [PipelineInterpreter.kt:110](/Users/<user>/Desktop/rokid/pca-v098/android-companion/services/orchestration/src/main/java/com/rokid/context/canvas/PipelineInterpreter.kt:110).

   Both paths call `carriedSources(item, output)` using only the corresponding item. Batch mates and other consumed inputs are omitted.

   **Repro:** run `Vision(batch=2) → Prompt → Summary` over A/B outside each other’s Forget radius. Use a Vision draft requesting comparisons between photos: A’s caption contains a B-specific detail but carries only `frameId=A`. Hold Summary(A) at router admission, Forget B, then release. The carried sources contain only A, so B-derived text is sent. Window aggregation and stateful motion likewise retain only each output’s own input sources.

2. **P1 — Vision discards carried provenance when constructing its downstream request.** [Executors.kt:115](/Users/<user>/Desktop/rokid/pca-v098/android-companion/services/orchestration/src/main/java/com/rokid/context/canvas/Executors.kt:115), [ExtractSave.kt:126](/Users/<user>/Desktop/rokid/pca-v098/android-companion/services/orchestration/src/main/java/com/rokid/context/orchestration/ExtractSave.kt:126).

   Vision includes upstream JSON in `contextSources`, but `ExtractSave` declares only the attached photos as request sources.

   **Repro:** in a Canvas experiment, run `Vision(batch=2) → Prompt → Vision(batch=1)`. Have A’s initial fact cite A+B; Prompt’s JSON retains B-derived text and correctly carries A+B. Hold the final Vision(A) at router admission, Forget B, then release. Its request checks only A and uploads the stale JSON. Upstream sources must reach every extraction attempt, including single-photo fallback.

3. **P1 — Mark-only Derive calls bypass protection despite carrying stored text.** [DerivePass.kt:124](/Users/<user>/Desktop/rokid/pca-v098/android-companion/services/orchestration/src/main/java/com/rokid/context/canvas/moment/DerivePass.kt:124), [ModelRouter.kt:196](/Users/<user>/Desktop/rokid/pca-v098/android-companion/services/ai/src/main/java/com/rokid/context/ai/ModelRouter.kt:196).

   A Moment containing only an intent mark supplies stored label text, such as “sleeping,” but produces empty sources with `required=false`. The router then skips the upload slot and liveness check, including the captured wipe generation.

   **Repro:** create a photo-less intent mark, enable Derive, and hold its request at router admission after the final snapshot check. Complete Delete all, then release. The deleted activity text is sent. The snapshot now binds text, sources and generation correctly, but this path never checks that generation.

The actual-SQL SQLite checks passed for qualifying-description grace, five-attempt parking, and cluster-member exclusion. The r12 expiry recheck, forgotten-photo LIVE termination and follow-up protection remain intact.

The async wipe tests now await wipe completion; the teardown wait addresses test-job lifetime. No new production deadlock found by static tracing.

Room v15, `PROTOCOL_VERSION=1`, and `TRACE_SCHEMA=1` remain unchanged. No Gradle run.