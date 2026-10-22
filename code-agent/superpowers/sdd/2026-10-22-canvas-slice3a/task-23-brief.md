### Task 23: M1 integration milestone [CORE 2.5 h]
**23a, code · Stream C · 2 h · depends on: 16, 20, 21, 22.**

**Files:** `glasses/src/test/.../TraceParityTest.kt`; `phone/src/test/.../IdentityPlanEndToEndTest.kt`.

**Tests:**
- `sessionRecordingReplaysBitIdentical` (the real `SamplingSession` + recorder, then `CaptureSimulator.exact`, over Task 1's scripts);
- `identityPlanSessionEqualsSettingsGoldens`;
- `shadowCountsEqualCounterfactualOpens`;
- `IdentityPlanEndToEndTest`: compile → message → the **real `PlanReceiver`** → EFFECTIVE, with matching `configHash` and `phoneInstall`.
- **Mutation REDs:** change one byte of `config` after the compile → `IdentityPlanEndToEndTest` fails on the hash; drop a `Score` event → `sessionRecordingReplaysBitIdentical` fails.

**Commit:** `test(canvas): M1 trace parity on the real session and identity plan end to end through the real PlanReceiver`

**23b, device · controller with the user · about 1 h worn.**
- Back up both apps first. The controller installs.
- Publish the identity plan with the **debug SHADOW** route.
- A 20-min collection, worn; transfer while charging, or use `tools/trace-pull.sh`.
- Exact parity, and `shOpen` within ±5 % of the counterfactual.
- Read only `files/capture_diag.log`. **Never print AssistServer lines.**

Task 27 lands only after this passes.

