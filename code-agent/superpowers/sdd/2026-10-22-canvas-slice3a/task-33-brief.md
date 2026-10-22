### Task 33: Composition wiring 2 (S71) [CORE 1 h]
**Stream B · 1.5 h · depends on: 28, 31, 32.**

Wire `MarkRecorder`, the interceptor, the entry points, `CapturePlanPublisher` and the actions in `HubGraph`, `CanvasGraph` and `CanvasApi` (`LabelRoutes`, `PlanRoutes` publish and actions, `SweepRoutes`). Remove `DebugPlanSource` from the production path; the debug route stays in Developer mode.

**Tests:** `allRoutesRegistered`, `markFlowWiredEndToEnd` (a fake glasses mark → a row → an ack), `publishFlowWiredEndToEnd`.

**Commit:** `feat(phone): wire marks, voice interceptor, publish groups and rollback actions`

