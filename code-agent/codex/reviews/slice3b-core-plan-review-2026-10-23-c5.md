Read-only; no files changed. Findings 1–2 are blocking design gaps. Findings 3–4 are bounded implementation or wording corrections. No hostile-code-only findings.

1. **Important — Freeze a repo-backed trace provenance contract before WP3.**  
   **File:** [00-core-plan.md:43](docs/superpowers/plans/2026-10-23-canvas-slice3b-core/00-core-plan.md#L43), WP1, WP3 and WP8a.

   WP3 requires `itemId`, `contentHash` and `sealState` to be copied from a stored trace row. Those per-item fields do not exist. `CanvasTraceEntity` stores trace-level metadata and a manifest; recorded IMU events are decoded from chunk files. WP8a currently introduces the item model after WP3 has already committed its schema and provenance tests.

   **Exact edit:** Assign WP1 the shared provenance DTO consumed by `PyRequest`, the DAO and the runner. Define item identity against the actual storage—for example, trace revision plus chunk sequence/event ordinal—and specify which fields come from `canvas_trace`, the manifest and verified chunk bytes. WP3 must accept that captured provenance and transactionally check its trace revision/epoch; WP8a implements its production resolver. Replace “copied from the stored trace row” accordingly. Test with a real IMU chunk fixture rather than invented per-item Room rows. Keep memory DTOs independent of orchestration.

2. **Important — The retained result store lacks an owned read and lifecycle contract.**  
   **File:** [00-core-plan.md:49](docs/superpowers/plans/2026-10-23-canvas-slice3b-core/00-core-plan.md#L49), WP3, WP4, WP8b and WP9.

   `CanvasBackendImpl.run()` currently reads `CanvasDao.items()` from `canvas_run_item`. WP8b moves trace-derived Python outputs into different tables but permits only a phone composition line. No package explicitly connects the new store to result retrieval. Existing `deleteRun()` and `pruneRuns()` also know nothing about it. Furthermore, protecting only `py.fn` outputs leaves the storage policy for trace-input and intermediate values unspecified.

   **Exact edit:** Give WP3 the result read API, output-to-node/item identity and run deletion/pruning cascade. Give WP8b the corresponding `CanvasBackendImpl.run()` integration and explicitly permit those backend edits. Require every persisted trace-derived value to use the provenance-fenced store, or explicitly prohibit storing trace-input/intermediate payloads elsewhere. Extend WP9 tests to verify retrieval, run deletion/pruning, and absence of surviving payloads in both the new tables and legacy `canvas_run_item` after Forget. This needs settling before WP3 freezes v8.

3. **Minor — Complete WP8a’s explicit integration checklist.**  
   **File:** [00-core-plan.md:48](docs/superpowers/plans/2026-10-23-canvas-slice3b-core/00-core-plan.md#L48), also its test row.

   Three existing seams need naming:

   - `CanvasApi` invokes extras first; `TraceRoutes` consumes `/api/traces/*` and returns 404 for unrecognised shapes. An items route added only to `CanvasApi` will be shadowed.
   - Existing estimate/start requests require `frameIds`, and start consumes an estimate token bound to those IDs.
   - `NodeExecutor.run()` accepts `List<CanvasItem>`, whose photo is mandatory. Adding a neighbouring trace carrier does not itself provide executor dispatch.

   **Exact edit:** Add `TraceRoutes.kt` to WP8a’s files; define trace estimate/start behaviour and input binding; and assign WP8a an explicit trace executor interface or overload for WP8b to implement. State which nodes trace graphs support. Test through the assembled production route chain, including estimate/start and a fake trace executor. These fit the existing WP8a owner and dependency boundary.

4. **Minor — Remove the remaining generation-only fixture scope.**  
   **Files:** [00-core-plan.md:41](docs/superpowers/plans/2026-10-23-canvas-slice3b-core/00-core-plan.md#L41), WP1 and §4.1; [3b-LATER-backlog.md](docs/superpowers/plans/2026-10-23-canvas-slice3b-core/3b-LATER-backlog.md).

   WP1 still lists “id/paths” and a keyword denylist inherited from generated Kotlin file/tool contracts. Python helper job IDs need validation; generated class names and repository path templates do not.

   **Exact edit:** Enumerate the Python-only fixture files retained by WP1. Move generated-file templates, tool/class-name denylists and their tests to v2 V1/V3. Mark `not_confirmed` as reserved or remove it from CORE denials. The NL deferral itself is sound: no remaining blocker requires `tool_build`, stand-ins, generation/verification profiles, the CLI, proxy or S40 generation oracle.

The arithmetic checks pass: **14 WPs = 49.0 h**, split **34.0 + 3.0 + 12.0**; planned **64.25 h**, reserve **6.25 h**, cap **70.5 h**. CP1/CP2 are **6.5/37.0**, with triggers **7.8/44.4**; CP3’s **77.1** trigger correctly yields to the cap. Expected burn is **58.5**, leaving **5.75**, with escalation at **64.35**. The dependency path is **26.5 h**, scheduled WP9 completion **28.0 h**, total **40.75 h**, and worktree count **18**. Backlog subtotals also reconcile. The reserve is **9.7%**; re-estimate affected packages after findings 1–2 before treating the cap as dependable.

The two VM batches, Gradle exclusion, merge order and release-bookkeeping/fix-before-final-build sequence are coherent. Pairing/native grants, session termination, retention, additive v8, unchanged `PROTOCOL_VERSION`, S6, logger ownership and no-host-fallback boundaries remain intact.

**Safe to start:** PF and WP10 now. WP1 can start its wire protocol/logger work, but must settle finding 1 before merging its DTO contract. **WP2, WP7 and WP11–13 are unaffected by the blocking findings and safe once their declared prerequisites are met.** Hold the persistence/service integration and trace packages against the unresolved contracts.

VERDICT: NOT READY