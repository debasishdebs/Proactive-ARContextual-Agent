### Task 3: NodeTypes v2, ParamSchema, S41, rooted domains, bindings, backend v2, ASYNC projection (A2, S34, S41, S72, S78) [CORE, trimmed 3 h]
**Stream B · 4 h · depends on: base.** It is round 1 Task 2, plus the following.

**Files (extra):**
- modify `phone/.../canvas/api/CanvasBackendImpl.kt`: `nodeTypes()` serves the v2 fields from the registry instead of the hardcoded ASYNC list
- modify `CanvasBackend.kt`
- `services/ai/.../ai/canvas/Pipeline.kt`: `fun asyncProjection(): Pipeline`

**Implement (extra):**
- **`signal.in`/`signal.out`:** `params.domain` (enum of the allowed domains) is validated per instance, with the text `"node <id>: signal domain <d> not allowed here"`.
- **`asyncProjection()`** keeps only the ASYNC nodes and edges, with the schema-1 layout when the source was ASYNC-only. `PipelineHash` of the projection equals the slice 2 hash of the same ASYNC graph. `CanvasRunner` and `CanvasPublisher` call it.
- **Policy table labels:** the `policy.capture_rate` `table` columns are labelled **"timer photo gap (settled/quiet/walk/still cap)"** (S84 (3)). Each gap accepts 0 ("inherit") or 5–300 s, and `capPerMin` accepts blank ("unchanged") or 1–4.

**Tests (extra over round 1):**
- `signalDomainPerInstance`;
- `asyncProjectionHashEqualsSlice2Hash`;
- `runnerUsesProjection`;
- `nodeTypesRouteServesV2` (phone, real `CanvasBackendImpl`);
- `policyTableColumnsNamedTimerPhotoGap`.

**Commands:** `gw.sh :services:ai:testDebugUnitTest --tests 'com.rokid.context.ai.canvas.*'`, `gw.sh :services:orchestration:testDebugUnitTest --tests 'com.rokid.context.canvas.*'`, `gw.sh :phone:testDebugUnitTest --tests 'com.rokid.context.phone.canvas.*'`.

**Commit:** `feat(ai): canvas NodeTypes v2 with capture-half types, rooted domains and bindings, schema-1 resolution, v2 node-types route, ASYNC projection`

