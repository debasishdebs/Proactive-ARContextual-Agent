**Not ready to dispatch.** The [3c plan](/Users/debasishkanhar/Desktop/rokid/proactive-context-agent/docs/superpowers/plans/2026-10-22-canvas-slice3c.md) follows the requested order, but its safety contracts and reuse assumptions need tightening. Read-only review; no files changed, tests or builds run.

1. **Critical — `ctx` can indirectly let image intent throttle capture (X1–X3).**
   Failure: image evidence switches PASSENGER to DRIVING; `ctx != DRIVING` then drops glances. Existing fusion permits image-driven subtype voting and temporal effects; the reserved attempt does not preserve glance frequency.
   **Edit:** retain permission to read `ctx`, but require a capture-safe projection for every capture-affecting context input. Prove non-interference across subtype changes, confidence, age and temporal state; reuse the corrected rules-graph contract, not its currently unresolved “reducing state” test. Until available, defer context-dependent programs.

2. **Critical — advertised inputs cannot all be replayed exactly (X1/X4/X6).**
   [TraceEvent.Ctx](/Users/debasishkanhar/Desktop/rokid/pca-s3/int/android-companion/core/capture/src/main/kotlin/com/rokid/context/capture/trace/TraceRecords.kt:89) records state only. A confidence/age expression can differ live and replay while the state never changes, invalidating CORE’s exact-hash TRIAL evidence.
   **Edit:** add a versioned signal/replay contract and sufficient receipt, expiry, confidence and evaluation-order evidence, or exclude those signals. Missing observations must block exact qualification. Specify window eligibility after trace boundaries; “warming” alone cannot satisfy the publish gate.

3. **Important — shared `StepOps` ownership is aspirational (§2/C1).**
   Rules-graph R1.3/R1.4 define `GraphEngine`, `Sample` and adapters, **not** the proposed `StepOps` interface. “Whoever merges second adapts” leaves both teams implementing incompatible availability, error and window semantics.
   **Edit both plans before dispatch:** one named owner builds a small shared package first; pin signatures, status representation, numeric types, clocks, state layout and conformance vectors. Keep pure operations in `core:model`, policy adapters in `core:capture`, registry code in `services:ai`. The capture→model dependency already exists. Remove the competing fallback implementation.

4. **Important — ERROR semantics conflict with the selected defaults (C1a/X3).**
   “Propagate ERROR” and “treat ERROR like UNAVAILABLE” do not specify CEL absorption: `false && ERROR`, `true || ERROR`, and an error in an unselected `choose` branch. A single error flag also loses div0 provenance.
   **Edit:** publish complete VALID/UNAVAILABLE/ERROR truth tables, including `not`, `fresh` and `choose`; preserve error cause. Define whether counters count executed errors or failed program outputs, once per evaluation; mixed div0/overflow must not exempt overflow from revert. Add permutation and absorption goldens in E1.

5. **Important — the program representation and validation contract are incomplete (X2).**
   `Step(id, op, a, b, const)` cannot clearly encode three computed operands for `choose`/`clamp`, enum sets or window predicates. Duplicate target writers also lack a resolution rule.
   **Edit:** pin typed operand variants, explicit output, arity, unique IDs, earlier-reference ordering, enum domains, target allowlist, duplicate-writer refusal, integer conversion and units. Apply the same closed validation on glasses before allocation/activation. Explicitly defer lookup tables and unused functions rather than implying support through F1.

6. **Important — rate and memory bounds are declarations, not an executable design (X3/C1b/X6).**
   A glance plus a value tick can exceed two evaluations/s; six separately scheduled programs multiply that ambiguity. Four 300-second Int32 rings plus Int32 deque indices already need 9,600 bytes, before stacks and metadata.
   **Edit:** specify one shared scheduling budget, candidate coalescing and cached-result expiry. State the numeric work limits, including window updates and deque worst-case work. Count all arrays, validity data and concurrent graph/expression state. Refuse over-budget combinations before allocation; test burst traffic and four maximum-length max windows.

7. **Important — invariant sequencing needs a concrete integration contract (X0/X3).**
   A cached VEHICLE value must not survive a transition to vetoed UNKNOWN; clamping to `PlanBounds` alone does not preserve heat, battery, resting or hard-ceiling composition. The universal 60-second promise also conflicts with 3a’s accepted S83 behavior.
   **Edit:** select the effective vetoed row first, apply its expression, then retain stricter settings/rest/heat/battery bounds. Test transitions, Capture now exemption, failed opens and reserved attempts through the real session. Use existing [reservedBoundMs](/Users/debasishkanhar/Desktop/rokid/pca-s3/int/android-companion/core/capture/src/main/kotlin/com/rokid/context/capture/plan/CapturePlan.kt:52): 60 seconds active, 300 seconds rest-like, including S83a. Preserve the current walking-veto contract.

8. **Important — compiler/forms reuse omits substantial work (X1/X2/X5).**
   [PlanCompiler](/Users/debasishkanhar/Desktop/rokid/pca-s3/int/android-companion/services/ai/src/main/java/com/rokid/context/ai/canvas/plan/PlanCompiler.kt:46) is a fixed-chain compiler with string diagnostics, not registered passes. TABLE cells currently support bool/integer behavior; `visibleWhen` alone cannot provide ordered steps, enum sets and dynamic earlier-row references.
   **Edit:** add bounded generic table-schema/serialization/validation support, row operations, conditional cells, reference choices, compiler topology integration and nested constant sweep paths. Add one signal snapshot owner: `MotionClassifier.features()` resets accumulators, so a second consumer must not call it independently.

9. **Important — versioning and compatibility tests promise behavior old binaries cannot provide (X1).**
   The cap is **already 8,192 bytes**. Existing old receivers reject schema 2 as `version`; they cannot learn `unsupported:expr`.
   **Edit:** treat size tests as regressions; preserve schema-1 encoding/hash through omitted expression fields, support both schemas explicitly, and gate sending by capabilities. Require old peers to refuse safely with their existing reason; test precise new-peer reasons separately. Include signal/operation semantic versions in executable identity.

10. **Important — prerequisite accounting must use 3a’s CORE overrides (§6).**
    T13 is unnecessary for glasses-only programs; T17/T18 are unnecessary when controller pull plus fenced import supplies COMPLETE traces. T25/LA7 and LA9 remain prerequisites for unrestricted LIVE, not SHADOW/TRIAL.
    **Edit:** state those exclusions explicitly; retain CORE’s replay/estimate gates and blanket LIVE refusal. Add X8 route wiring/auth/bounds and trace integration dependencies. X7’s battery gate must use CORE’s coarse paired measurement or explicitly pull forward LA7 telemetry; a 35-minute run alone cannot substantiate the stated small drain difference.

11. **Important — detector gates contain hidden scope and circular prerequisites (§4).**
    Model probe and prompt A/B are required before entry, but have no scheduled task. D1 needs **3b’s EffectBuffer**, not just 3a. D4 requires phone expression execution despite Q12’s glasses-only scope.
    **Edit:** gate on the week first, then schedule bounded probe/A/B/replanning tasks. Require 3b explicitly or defer persistent detector writes. Remove phone expression consumers from D. Enforce S21: experiments never persist detections; no export/backup; deletion cascades. D5 should distinguish allowed normalized detection use from prohibited identity flow. Keep recognition unregistered.

12. **Important — Sonnet sizing and estimates undercount integration.**
    Split C1 3.5 h, X3 3 h, D1 4 h, D3 5 h, K1 4 h and K2 3.5 h into independently verifiable ≤2.5-hour tasks. X2/X5 also need splitting after the missing contracts are added.
    **Accounting:** listed E1 code totals 12.5 h, plus 0.5 h adoption or 3.5 h fallback: **13/16 h before X7’s 1.5 h**. E2’s 6.5 h adds correctly, but omits the identified integration work. Overall arithmetic is approximately **52.5 h**, with mixed controller accounting and unfinished prerequisites excluded.
    **Planning allowance:** provisionally E1 **22–32 h**, E2 **10–16 h**; full E+D+K roughly **65–95 h**, excluding upstream foundations and wear time. These are review estimates, not commitments.

13. **Minor — dispatch instructions contradict dependencies and gates.**
    D1/D2 cannot run in parallel as written; E1’s fuzz stop-loss references X9 in E2; §6 is not the detector gate; K1 is both entry-gated and “scheduled” without a connector choice.
    **Edit:** correct ordering, move scalar goldens/fuzz into E1, remove the mandatory Opus replanning allowance, and enforce the three-build semaphore across both plans.

**Recommended minimal first phase:** budget **16–24 incremental Sonnet hours after required 3a foundations**, then reassess.

- Pin the scalar shared seam without waiting for whole rules-graph P1; ship compare/membership/logic/arithmetic/clamp/choose with explicit status semantics.
- Provide generated step authoring, motion-only guards and narrowly targeted values; defer context-dependent capture effects until the safe-context/replay contract exists.
- Ship SHADOW first with receiver validation, immutable signal snapshots, scalar fuzz and exact replay; enable TRIAL only after existing CORE evidence gates pass.
- Defer windows, try-it, tables, detector persistence and all connector work. Keep the seven-day detector gate; collect whether the requested queries are actually supported before committing D/K budgets.