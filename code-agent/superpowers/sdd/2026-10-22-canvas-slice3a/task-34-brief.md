### Task 34: Deletion graph (A13, S38, S58, S75, P7) [CORE, trimmed 2.5 h; rewrite LATER LA13]
**Stream A · 3.5 h · depends on: 9, 18, 19, 21, 24, 31.**

**Files:**
- `services/memory/.../db/CanvasSlice3ForgetDao.kt` (transactional tombstones, label deletes, invalidation, `labelRev` bump)
- `services/memory/.../forget/ForgetListener.kt` (an interface; `ForgetService` and retention `expirePhotos` call the registered listeners after their transaction)
- `services/orchestration/.../canvas/plan/CanvasForgetCoordinator.kt` (files and devices; registered in Task 33's wiring, or here through `CanvasGraph` if Task 33 has merged)

**Implement:**
- round 1 Task 23's behaviour;
- **a range rewrite writes a new trace (`traceId'`, `revision + 1`, `parentTraceId`) and tombstones the original**;
- retention reaches the same cascade;
- a label delete through `LabelRoutes` invalidates the dependent results.

**Tests:**
- round 1's, plus:
  - `retentionExpiryCascades`;
  - `rewriteGetsNewIdAndTombstonesOriginal`;
  - `lateChunkForOriginalRejected`;
  - `labelRouteDeleteInvalidatesResults`;
  - `memoryHasNoOrchestrationDependency` (a Gradle dependency check, or a compile-time import scan).
- **Mutation RED:** skip the fence on the replay commit → `forgetDuringReplayCommitsNothing` fails.

**Commit:** `feat(canvas): slice 3a deletion graph (memory tombstones, ForgetListener, retention, IMU rewrite to a new revision, label invalidation) with late-result fences`

