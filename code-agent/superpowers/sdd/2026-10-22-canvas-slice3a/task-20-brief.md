### Task 20: `CaptureSimulator` exact replay (A10 part, S65, S77) [CORE 3 h]
**Stream A · 3.5 h · depends on: 2, 4, 6.**

**Implement:**
- **Input:** `exact(events: Sequence<Ev>, m: Manifest)`, streamed chunk by chunk.
- **Classifier and admission:** `MotionClassifier` from the snapshot's glance params; dwell → `GlanceAdmission`.
- **Scheduler recomputed:** `CameraPolicy.decide` every 250 ms from the recorded `Profile`, `Backoff`, `Blocked`, `Ctx` and `Resting`, plus the reserved attempt (S83). The recomputed episode starts **must equal** the recorded `Episode(open)` events.
- **Frames:** `Score` events go to `KeyframeGate.offerScored`, the window and `Flush` behaviour reproduce selection, and the gate outcomes must equal the recorded `Gate` events.
- Decisions before `PreRollEnd` are discarded. A trace with `exact = false` → `IllegalArgumentException("approximate trace")`.

**Tests:**
- `exactReplayMatchesGoldenSessionTrace` (fixtures produced by Task 23a from the **real session**; until then a committed trace from Task 1's script driver);
- `recomputedOpensEqualRecorded`;
- `windowSelectionReproduced`;
- `preRollDiscarded`;
- `approximateTraceRefused`;
- `streamsWithoutMaterialising` (a 12 h synthetic stream stays under a fixed heap bound);
- `deterministic`.
- **Mutation RED:** replay with the recorded opens echoed back → `recomputedOpensEqualRecorded` must still catch a broken `CameraPolicy` input (shown by an injected `stillStep` mismatch).

**Command:** `gw.sh :core:capture:test`.

**Commit:** `feat(capture): exact capture replay that recomputes the scheduler and gate selection from the frozen trace schema (streamed)`

