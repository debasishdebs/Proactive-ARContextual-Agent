### Task 28: S26 actions, Settings and Diagnostics, full-graph API and publisher test (S26, S12, S72) [CORE, with 27; extras LATER LA10]
**Stream B · 2.5 h · depends on: 27.**

**Implement:**
- round 1 Task 19's actions and rows;
- the Settings row text "Capture plan: <name> v<N> (<mode>) · Use settings", and frozen rows labelled "Frozen in canvas plan vN";
- the Diagnostics plan card shows the group-state rows.

**Tests:**
- round 1's;
- **`fullThreeDomainGraphPublishesThroughRealApi`** (the real `CanvasBackendImpl` and `CanvasPublisher`: the ASYNC projection publishes as an interpreter, and the plan as `capture_plan`).

**Commit:** `feat(canvas): undo, restore-previous and use-settings across publish groups; Settings and Diagnostics rows; full-graph publish through the real API`

