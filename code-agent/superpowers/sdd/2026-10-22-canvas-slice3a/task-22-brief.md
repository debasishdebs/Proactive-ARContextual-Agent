### Task 22: Composition wiring 1 and the M1 SHADOW-only debug publish (S71) [CORE 1.5 h]
**Stream B · 2 h · depends on: 7, 11, 12, 13, 18, 19, 21.**

**Files:** `phone/.../hub/HubGraph.kt`, `phone/.../canvas/CanvasGraph.kt`, `CanvasBackendImpl.kt`, `CanvasApi.kt` (register `TraceRoutes` and `ReplayRoutes`).

**Implement:**
- Wire `PhoneRtApplier`, `TraceIntake`, `HubTraceRecorder`, `CollectSession` and `ReplayService`, and the `capturePlan`/`traceControl` flows (from a temporary `DebugPlanSource`).
- **`POST /api/plans/debug-shadow`** works only in Developer mode, is **SHADOW only**, and is labelled "debug SHADOW (no publish gates)". It never sends LIVE or TRIAL.
- **Plan reconciliation lives in the hub,** not in the canvas server.

**Tests:**
- `debugShadowRefusesLiveAndTrial`;
- `debugShadowNeedsDeveloperMode`;
- `routesRegistered`;
- `reconcileSurvivesCanvasServerStop`.

**Command:** `gw.sh :phone:testDebugUnitTest`.

**Commit:** `feat(phone): wire capture-plan, trace, collect and replay services; Developer-mode SHADOW-only debug publish for M1`

