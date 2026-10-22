**Not ready to dispatch.** The plan incorporates the earlier review structurally, but several acceptance lines contradict their proposed implementation. P1–P3 is an infrastructure milestone, not yet a shippable phone-only rules product. Read-only review; no files changed or tests run.

Rulings audit: R3/R4 are addressed in 1.5/2.1 and 4.2; R5’s corrected defaults appear in 1.6. R1, R2, R6–R9 remain incomplete for the reasons below. R10 has the requested coverage list in 1.8, but needs capture/sensing comparisons and clearer integration contracts. Thus the nine findings are all *referenced*, not all resolved.

1. **Critical — R1’s capture projection still permits image-driven throttling** ([1.9 and D1](/Users/debasishkanhar/Desktop/rokid/proactive-context-agent/docs/superpowers/plans/2026-10-22-context-rules-graph.md:116)).
   Failure: DRIVING cap 1, PASSENGER cap 4; an image changes the subtype to DRIVING. Neither is classified as “capture-reducing” by D1, so capture drops. FLIGHT versus REST also escapes when both decisions are reducing.
   **Edit 1.9/2.2/4.4:** compare effective policies from both temporal evaluations, combining cap permissively, gaps permissively, and guards with OR. A single selected state cannot express every field-wise combination. Alternatively, initially use only the imageless decision for capture and explicitly expand the documented oracle delta.

2. **Critical — the compiler’s taint rule still rejects the identity graph** ([2.2](/Users/debasishkanhar/Desktop/rokid/proactive-context-agent/docs/superpowers/plans/2026-10-22-context-rules-graph.md:133)).
   Production images affect BT contradiction latches and REST exclusion, beyond `vote.logodds → band`. Freezing those computations while forbidding their paths makes `IdentityGraphCompilesCleanTest` contradictory.
   **Edit 2.2:** distinguish unrestricted full-decision computation from the independently masked capture computation; specify the permitted capture boundary and prove non-interference across latches, `prev`, holds, exceptions and policy selection. Add unequal-vehicle-row and FLIGHT/REST tests.

3. **Critical — coordinated activation is postponed beyond the phase that needs it** ([2.4–2.6](/Users/debasishkanhar/Desktop/rokid/proactive-context-agent/docs/superpowers/plans/2026-10-22-context-rules-graph.md:141)).
   Failure: P2 publishes new phone inference against an old glasses policy; local glasses TRIAL expiry restores its policy while the phone continues the candidate graph. P4’s eventual reconciliation does not protect P2.
   **Edit:** pull the two-component identity, context-message matching, mismatch→UNKNOWN behavior, expiry and crash recovery into P2. Extend T5/T11/T12 then; P4 should add only executable glasses nodes and aggregates. New config fields also require capability/schema handling immediately: existing strict decoders reject them.

4. **Critical — P2 TRIAL depends on evidence that arrives only in P6.**
   T27+28 CORE requires exact-hash replay over COMPLETE traces; T20 consumes recorded context, which cannot qualify changed inference. Bypassing that gate is unsafe; retaining it leaves P1–P3 SHADOW-only.
   **Edit 2.6:** explicitly remain SHADOW-only, or pull minimal sufficient evidence recording/replay and its gate forward. Preserve CORE’s blanket LIVE refusal; P6 replay alone does not implement LA7/LA9’s verified LIVE qualification.

5. **Important — reuse exists, but module ownership would create dependency cycles.**
   `ContextPolicies.DEFAULT`, `ContextCapturePolicy`, `PlanRow`, `PlanState`, `PlanResolver.rowOf/toContextPolicy/identity/resolve`, and `PlanBounds` all exist. However, `core:capture → core:model`, while `services:ai →` both.
   **Edit 1.1/1.5:** keep engine contracts in `core:model`; put `PlanRow` adapters in `core:capture` and registry adapters in `services:ai`, or move narrowly shared descriptors downward. A `core:model` engine cannot import the AI registry or `PlanRow` as currently implied.

6. **Important — `FusionSteps` is a substantial extraction, not existing reusable functions.**
   In [ContextFusion](/Users/debasishkanhar/Desktop/rokid/pca-ctx/int/android-companion/core/model/src/main/kotlin/com/rokid/context/core/model/context/ContextFusion.kt:47), `fastConfirmed`, `heldWithin`, `refreshHeld`, `arrival`, `dwell`, and `reset` exist privately; facts, arbitration, voting and confidence are interleaved in `evaluate()` and share mutable state.
   **Edit 1.2–1.5:** first pin explicit state/input/output contracts, then extract ingestion/clock, facts, commitment and scoring separately. Move clock extraction into 1.2; 1.3 currently edits an allegedly finished hotspot without declaring that dependency. Generic windows/latches are new implementations, not ≤60-line wrappers by default.

7. **Important — zero allocation conflicts with the proposed reuse.**
   Existing fusion uses collection filtering/sorting, reasons sets and new decisions; `GraphOutputs` also constructs collections. Extracting those functions cannot satisfy 1.3’s blanket `NoAllocationTest`.
   **Edit 1.3/2.1/4.4:** scope allocation requirements to the glasses evaluation hot path; separately budget evidence ingestion and public output construction. Account for both full and imageless engines, actual window storage and evidence-driven evaluations in resource limits.

8. **Important — NodeTypes v2 and TABLE exist, but the claimed extension surface does not.**
   [NodeTypes](/Users/debasishkanhar/Desktop/rokid/pca-s3/int/android-companion/services/ai/src/main/java/com/rokid/context/ai/canvas/NodeTypes.kt:24) has the named enums, but an immutable registry, one output type, input type sets, and no named-port signatures or `phrase`. `safeSide` is also new.
   **Edit 1.1:** explicitly add descriptor serialization, registry versioning, named-port validation and phrase metadata. TABLE supports numeric/bool cells; numeric dwell matrices fit, while arbitration needs either unique numeric ranks or a new ordered-enum representation. Do not call `safeSide` the only extension.

9. **Important — T7/T35 reuse omits the graph integration work.**
   [PlanCompiler](/Users/debasishkanhar/Desktop/rokid/pca-s3/int/android-companion/services/ai/src/main/java/com/rokid/context/ai/canvas/plan/PlanCompiler.kt:46) is a fixed-chain compiler, with no pass-registration interface; its result contains string problems/warnings. Existing canvas models also lack `GEdge` named ports.
   **Edit 2.2/5.1–5.4:** add explicit graph-model/API conversion, compiler dispatch, diagnostic compatibility and persistence ownership tasks. Preserve fixed-chain behavior with regression fixtures. T35a supplies JavaScript forms, not reusable Compose widgets; budget 3.2 separately.

10. **Important — the first product phase omits essential authoring and source paths.**
    3.1 makes complex identity chains read-only; 3.3 offers edits/toggles without defining rule creation or rewiring. Canvas support waits until P5. `CarSource` emits aggregate link kinds, not selected-device facts; no task implements alias bindings, lazy GPS demand or its budget.
    **Edit:** add bounded rule creation/deletion/connection operations and shared revision APIs before declaring P3 shippable. Either add selected-device/demand tasks or explicitly limit the milestone to existing “My car” facts and current sensing. Move basic canvas editing alongside phone editing.

11. **Important — R6 regresses, and R9’s exact replay lacks sufficient inputs.**
    4.2 again requires evaluation ≤ delivery: evaluating every 10 s over GPS delivered every 30 s fails its own example. Separately, quantising 24.9 km/h to 25 can manufacture confirmed-speed evidence.
    **Edit 4.2:** bound delivery against transport capacity and evaluation against compute capacity; independently validate age/coverage/latency. **Edit 6.1–6.3:** record versioned predicate outcomes and sufficient temporal state; changed thresholds crossing quantisation uncertainty must be UNCERTAIN. A warning cannot establish exact replay.

12. **Important — hash and dependency contracts need correction before parallel work.**
    Putting changing `ctxGen` inside hashed config risks changing qualification identity on every activation, defeating T27’s replay→TRIAL hash stability and per-config trial limits.
    **Edit 2.4:** hash stable executable content, including phone graph hash; bind activation generation separately in the matching protocol. Add replay→publish→rollback hash tests.
    **Dependency edits:** 1.6 needs T4; 2.4 cannot run parallel with 2.1 because it depends on 2.2; the suggested early 4.1 start requires 1.1. Add LA2/LA8 explicitly to P2, T5/T11/T12 to its activation work, and T6/T19–21 schema/simulator extensions to replay. Coordinate T8+9 deletion ownership and T35 API changes; existing fenced files are primitives, not a graph repository.

13. **Minor — oracle and parity interfaces need precise task text.**
    A byte-identical source copy redeclares `FusionParams`, `ContextDecision` and `ContextFusion`; the inline Hub watch default is not an importable constant. `GraphOutputs` also lacks several fields promised for parity.
    **Edit 1.2/1.6/1.7/1.10:** hash the original resource, isolate the executable oracle with a documented transformation, expose effective Hub defaults through a production-owned contract, and return a typed full `ContextDecision`. Compare capture and sensing effects separately from the intentional D1 difference.

**Sizing and estimate:** the arithmetic is correct: 83 base, 92 with fixes; P1–P3 is 45.5 base, approximately 50 with its allocated fixes. However, **20 of 34 code tasks exceed two hours**, before the missing work. Split 1.3 into topology/clock/value semantics; 1.5 into fact/scoring/commit adapters; 2.2 into structure/provenance/resources; 2.6 and 4.6 into staging/recovery/expiry; 6.2 into exact replay/uncertainty. Each needs its own ≤2-hour acceptance boundary. Treat 92 as optimistic; **110–150 incremental agent-hours** is a safer provisional full-scope allowance, excluding unfinished slice 3a prerequisites and device wear time.

**Recommended budget-first phase:** replace P1–P3 with a narrow, complete vertical slice, provisionally **24–36 incremental hours after required 3a foundations**, dispatched as ≤2-hour tasks.

- Keep existing fusion ingestion, commitment and defaults; expose grouped drive/walk/rest rule nodes over approved existing facts, with bounded AND/NOT conditions and per-mode policy rows.
- Support creating, editing and disabling those rules in both editors over one revision; show protected walking behavior explicitly.
- Implement the imageless capture basis, stable executable identity and paired SHADOW/TRIAL rollback before activation; include only the evidence needed to qualify this restricted scope.
- Defer whole-engine decomposition, arbitrary placement, new link transport, arbitrary windows, selected-device aliases, widened GPS demand, advanced inspectors/lints and LLM advice.
- This delivers editable inference rules and frequency by mode, while explicitly deferring whole-engine editability. If even that exceeds budget, ship parameter/policy editing first and label it accurately as a smaller milestone.