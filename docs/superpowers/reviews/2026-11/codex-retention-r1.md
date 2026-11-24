VERDICT: NOT READY

1. [P1] android-companion/services/memory/src/main/java/com/rokid/context/memory/forget/ForgetService.kt:167 - Linked and skipped-photo expiry still drops Moments, shrinks/deletes Scenes, and invokes the privacy radius cascade; v2 does likewise - Separate photo storage expiry from dependent deletion in both implementations.

2. [P1] android-companion/services/memory/src/main/java/com/rokid/context/memory/forget/ForgetService.kt:197 - Memory expiry involving legacy frames still passes `inside` to `dropHolding` and calls `dropCanvasOutputs`, despite `redact=false`; planner/executor share this hole - Suppress frame-dependent and radius deletion for retention.

3. [P1] android-companion/services/orchestration/src/main/java/com/rokid/context/orchestration/forget/RetentionWorker.kt:191 - Legacy orphan selection permits CARRIED provenance; `forgetMoment(redactCites=false)` still cascades carried memories with default redaction, erasing dependent answers/nudges - Use a retention-specific path that disables every nested privacy cascade.

4. [P1] android-companion/services/memory/src/main/java/com/rokid/context/memory/forget/ForgetService.kt:191 - Expiry deletes provenance edges while keeping citing answers/nudges; subsequently Forgetting their photo cannot discover expired source IDs, so text and associated proposal/alarm content survive - Preserve durable lineage and consult it during later user Forgets, including missing source rows.

5. [P1] android-companion/services/orchestration/src/main/java/com/rokid/context/orchestration/proactive/ProactiveForget.kt:33 - Photo expiry emits `framesForgotten`; the listener treats expired sources as forgotten and withdraws stored suggestions and their surfaces - Distinguish expiry from explicit Forget and preserve expiry-safe suggestion provenance.

6. [P1] android-companion/services/orchestration/src/main/java/com/rokid/context/canvas/moment/MomentRepair.kt:39 - One missing second triggers rebuilding every existing Moment that day; `MomentBuilder` replaces retained text from surviving sources, losing text whose facts expired - Repair missing seconds without overwriting retained Moment content.

7. [P2] android-companion/services/orchestration/src/main/java/com/rokid/context/canvas/moment/MomentRepair.kt:37 - If Moment repair commits but Scene repair fails, retry sees no Moment gap and permanently skips the missing Scenes - Detect and repair Scene gaps independently, or persist resumable progress.

8. [P2] android-companion/services/orchestration/src/main/java/com/rokid/context/canvas/moment/MomentRepair.kt:39 - Repair processes whole days through transactions containing per-Moment reads/writes; 100k Moments therefore entail hundreds of thousands of operations without bounded chunks - Chunk Moment builds and rebuild Scenes by hour.

9. [P3] android-companion/phone/src/main/java/com/rokid/context/phone/ui/preview/CitedPreview.kt:81 - Nudge previews omit `cited.missing` and hide the section when all sources expire, so the new expiry wording never appears - Pass the missing count and retain the section when it is nonzero.

Static checks: repair authentication, Developer gate, shared rate limit and builder tombstone checks are present; no model/network calls found. Pause defaults OFF and precedes daily cleanup. Plan/result interpolations contain numbers only. No files edited; no Gradle, adb, tools scripts or runtime benchmarks run.