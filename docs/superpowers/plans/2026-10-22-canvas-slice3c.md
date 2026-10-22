# Canvas slice 3c: expression nodes (structured steps), then detectors (LATER), then connectors (last) — lean implementation plan (Sonnet only, reuse-first)

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development. Lean plan: interfaces, behaviour bullets and named tests, not code. Every code task is TDD: write the named tests, see them fail (RED), implement, GREEN, commit. A task marked **mutation RED** must show each named test failing under the stated mutation before it is reverted, recorded in the commit body. **One Sonnet implementer per task, one worktree per agent, no `git stash`, no machine-wide `pkill`, clean up after merges.** No Claude review step: Codex does one read-only review per phase (never implements). Never print secrets (names and counts only). At most **3 concurrent Gradle builds** (8 cores, 8 GB).

**Specs (read these, do not copy them):**
- `specs/2026-10-21-canvas-slice3-e2e-configurator-design.md` §17-§18 (user rulings), §8a/§8b (S18-S21, S37, S56), §3 invariants (S31, S32), §4.5 (tools).
- `specs/2026-10-22-expression-nodes-design-options.md` (the evaluator semantics, cost model, signal catalogue, limits) and `specs/2026-10-22-design-decision-brief.md` §1 (the picks).
- `plans/2026-10-22-canvas-slice3a.md` (T# = its tasks) and `plans/2026-10-22-context-rules-graph.md` (R# = its tasks, e.g. R1.3 = its task 1.3).

**Goal:** the user builds guards and values from a finite catalogue of form-edited steps in the canvas inspector. The canvas compiles them straight to the AST (no parser). One Kotlin evaluator runs them on the glasses, the simulator and the phone. They publish through 3a's SHADOW/TRIAL path. Detectors (week-of-use gated) and connectors follow, in that order, each in its own gated block.

## 0. Rulings (all defaults from the decision brief, plus §17-§18; each is a task acceptance line)
| # | Ruling | Where enforced |
|---|---|---|
| U1 | **§18:** first slice = finite step catalogue, canvas builds the AST, **no text parser, no JS evaluator**. The pcx text syntax (CEL subset) is a later optional "power mode" emitting the same AST; not planned here | X2, X5 |
| U2 | **§17.1 / brief Q1 = yes:** a guard may read `ctx` and drop glances. Drop-only; the 60 s reserved timer photo attempt (S31/S84) and the walking veto (S32) still run after it; WALKING/UNKNOWN rows stay locked. Inspector shows a warning when a guard reads `ctx` | X2, X3 |
| U3 | **§17.2:** `PLAN_MAX_BYTES` 4096 -> 8192 (`too_big` above it). The plan is never trimmed | X1 |
| Q8 | `headSpeed` = `gyroMax` of the last second (a catalogue alias, no new signal) | X1 |
| Q9 | `and/or/not` and `in (A,B)` aliases rewrite to canonical on blur: applies to **power mode only**, so deferred with it | none |
| Q10 | Windows wait for the **full horizon** (NOT_READY = `UNAVAILABLE` in the rules-graph vocabulary, never "satisfied"); missing seconds are invalid, not zero | C1b, X6 |
| Q11 | `x / 0` is an **ERROR by role**: guard keeps the candidate, value takes the row default; counted `exprDiv0`, **not** toward auto-revert | C1a, X3 |
| Q12 | 8 KB cap (U3 replaces the 4 KB default), windows 1-300 s, v1 programs are glasses-only (no phone or hub rules) | X1, X2 |
| Q13 | The JS "try it" evaluator is **out** (U1). Replaced by a phone-evaluated try-it (X8) | X8 |
| Q18 | Python-to-expression promotion is later; not in 3c | none |
| B7 | Brief Q2-Q7, Q14-Q17 are hidden-foreground and mark items (3a / hidden-foreground stream): **not in this plan** | none |
| F1 | Limits (brief §1.1): 32 nodes, 6 programs, 4 windows, 3 tables x 32 rows, <= 2 evaluations/s, <= 8 KB state. More than 10 non-div0 errors per minute auto-reverts via T12's auto-revert (new reason `auto:exprErr`) | X2, X3 |
| F2 | `planSchema: 2` plus caps `expr`, `sig1`. Schema-1 plan hashes unchanged. Old glasses reject `unsupported:expr` (S24) | X1 |
| F3 | No new sensor, timer, wakeup or camera open. A value may raise `capPerMin` within `PlanBounds` only | X3, X7 |

## 1. Reuse map (binding; a task that re-creates one of these is rejected)
| Need | Reuse | New in 3c |
|---|---|---|
| Node registry, params, forms | T3 `NodeTypes` v2, `ParamSchema`/`ParamSpec`/`ParamCheck`/`TableSpec`; T35a form renderer (`paramform.js`) | rows `expr.guard`, `expr.value`; one `ParamSpec.visibleWhen` (columns shown per `op`). **No per-node UI** |
| Step operations | **Shared catalogue = rules-graph R1.3 + R1.4** (see §2) | `arith` only (+ - * /), and the program runner |
| Plan carriage, bounds, hash | T4 `CapturePlan`/`PlanConfig`/`PlanBounds`/`PlanValidator`/`PlanResolver`/`CanonicalJson` | `PlanConfig.exprs` (prefix-form step list; same encoding family as rules-graph `GlassesGraph`) |
| Compile and diagnostics | T7 `PlanCompiler` pass pattern, `Diagnostic` | `ExprLowering` pass inside it |
| Glasses apply, admission, auto-revert | T2 `GlanceAdmission`, T11 staged apply, T12 SHADOW counts and auto-revert, `ContextGate` | guard hook before admission; value hook at row resolve |
| Replay | T20 `CaptureSimulator` | runs the same runner, no second evaluator |
| Publish, gates | T26, T27+28, T22 routes | none (a plan with `exprs` is just a plan) |
| Canvas | T35a lanes and forms, T35b replay panel | none |

## 2. The shared step catalogue: which plan builds it
The rules-graph plan already needs `cmp`, `in`, `and`, `or`, `not`, `choose`, `clamp`, `fresh` (R1.3) and `win(max|mean|frac|count)`, `hold`, `hyst`, `latch`, `for` (R1.4), all as thin adapters over a `GraphEngine` in `core:model` package `context.graph`, with `UNAVAILABLE` propagation (its R6).

**Ruling K0 (default): the rules-graph plan builds the catalogue** (R1.1, R1.3, R1.4). 3c adds to it, and builds nothing parallel. Pinned seam, to be requested of whoever builds R1.3/R1.4: each node's logic is a **pure, allocation-free step function** (`StepOps.<op>(inputs, params, state?) -> Sample`) that the node adapter calls. 3c's program runner calls the same functions, so graph nodes and expression rows cannot diverge.

| Piece | Shared with rules-graph | Built by |
|---|---|---|
| `cmp`, `in`, `and`, `or`, `not`, `choose`, `clamp`, `fresh`, `Sample`/`UNAVAILABLE` | yes | R1.3 |
| `win` max/mean/frac (quantised Int32 one-second buckets, monotonic deque, <= 4 terms, <= 300 s) | yes | R1.4 (extend if its ring is not Int32/exact) |
| `hold`, `hyst`, `latch`, `for`, `prev` | rules-graph only; expressions do **not** expose them in 3c | R1.4 |
| `arith` (+ - * /), **ERROR sample state** (div0) | new, additive: `Sample.error` flag; logic nodes treat ERROR like UNAVAILABLE unless a role says otherwise | C1a (3c) |
| Registry rows, `ParamSchema` | shared registry | R1.1 |

**Fallback if 3c starts before R1.3/R1.4 merge:** task **C1** (3.5 h, below) builds only `StepOps` for the ops above plus `Sample`, **under the rules-graph file and class names**, and rules-graph R1.3/R1.4 then shrink to adapters (about -2.5 h there). Whoever merges second adapts; the pinned names in rules-graph "Shared interfaces" win. The glasses runtime stays in `:core:capture` and depends on the `StepOps` file set, so if `context.graph` sits in `core:model`, `:core:capture` must depend on it (check at C1/X3; T2 already sets module deps).

## 3. Phases, estimates, stop-loss
Sonnet agent-hours, code plus tests, no waits. **Hard stop-loss = 1.3 x phase estimate, or the named trigger, whichever first: stop, write a handoff note, keep the previous phase's shipped state.**

| Phase | Ships | Agent-h | Range, confidence | Stop-loss (h / trigger) |
|---|---|---|---|---|
| E1 | scalar guards and values, SHADOW/TRIAL, inspector, replay (no windows) | **14.0** (13.0 if R1.3 merged first) | 11-19, 55 % | 18 / differential fuzz fails after 2 fix rounds on the same op, or any change to `GlanceAdmission` outside the guard hook |
| E2 | windows, try-it, max-cost device plan | **6.5** | 5-10, 55 % | 8.5 / window ring needs more than 8 KB state or exact replay parity cannot be reached |
| D (LATER) | detectors | **~17** | 12-28, 35 % | 22 / the gate in §6 is not met, or probe model fails S19 |
| K (LAST) | connectors | **~9** | 6-16, 30 % | 12 / OAuth or secret storage needs a new subsystem |
| | Expected total | **E 20.5 (committed) + D 17 + K 9 = ~46.5**, plus 4 Codex fix rounds (~6) = **~52**; controller and device ~5 h extra | | |

Per-phase gate: Codex read-only review once at the phase end (input: diff, this phase section, §0 rulings; fixes by the same Sonnet agent; no second review unless Codex marks a finding Critical). No Claude review.

## Phase E1: scalar expression nodes [14.0 h]
Streams: A phone/compiler (X1, C1a, X2, X5), B glasses (X3, X4). Entry: **3a-CORE merged through T35b and T38**, plus the week of use for the detector decision only (E1 itself needs no week).

**X0 Spec patch (docs only) [0.5 h]** — no dependencies, no Gradle.
- Edit: slice 3 spec §3/§4.3 (S5 guards may read `ctx`; S31/S32 re-check wording per §17.1; S33 div0 by role; 8 KB where it says 4 KB). Reuse: the brief §1.4 as source. New: none.
- Tests: none (docs). Acceptance: `grep` finds no remaining "motion-only" or "4,096" in the edited sections.

**X1 Signal catalogue `sigSchema 1`, 8 KB cap, caps, `planSchema 2` [1.5 h]** — depends T4, T5.
- New: `SignalCatalog.kt` (names, enum wire codes, defaults, staleness, units; `headSpeed` alias to `gyroMax`; reads the 7 `SecondFeatures` plus `ctx`, `ctxConf`, `ctxAgeS`, bout and keep features **only if A3 already computes them**, verified against `MotionModel.kt`). Edit: `PlanBounds`/`PlanValidator` (`PLAN_MAX_BYTES` 8192), `PlanJson` (`planSchema`), caps in `Hello`/wire. Served through the `/api/node-types` route that T3 already has.
- Tests (RED first): `SignalCatalogMatchesSecondFeaturesTest` (field order), `PlanTooBigAt8193Test`, `PlanAt8192Accepted`, `Schema1HashUnchangedTest`, `OldGlassesRejectUnsupportedExprTest`. **Mutation RED:** swap `gyroMax`/`gyroMean` in the catalogue -> the order test fails.

**C1 Fallback: shared `StepOps` seam [3.5 h]** — only if R1.3/R1.4 are not merged (see §2); else replaced by a 0.5 h adoption check (names match, seam exposed). Tests: the rules-graph names (`UnavailableDoesNotSatisfyTest` etc.), mutation RED as in R1.3.

**C1a `arith` and the ERROR sample state [1.5 h]** — depends R1.3 (or C1).
- New: `StepOps.arith` (add, sub, mul, div), `Sample.error`. Edit: logic ops to propagate ERROR. No allocation.
- Tests: `DivZeroIsErrorTest`, `ErrorPropagatesThroughLogicTest`, `ErrorNeverSatisfiesTest`, `ArithFiniteOnlyTest` (NaN/Inf inputs become ERROR), `NoAllocationTest` (reuse R1.3's counter). **Mutation RED:** div0 returns 0 -> `DivZeroIsErrorTest` fails.

**X2 `ExprProgram`, lowering and validator rules [2.5 h]** — depends X1, C1a, T7.
- New: `ExprProgram.kt` (role GUARD|VALUE, `steps: List<Step(id, op, a, b, const)>` where a/b refer to earlier ids or signals, so cycles are impossible by construction), `ExprLowering.kt` (the pass registered inside `PlanCompiler`; emits prefix-form `PlanConfig.exprs`), cost model (static unit costs from the options doc §4.4). Edit: `PlanValidator` rules.
- Rules (each a `Diagnostic` code): guard output bool; value clamped to **one policy field of one non-locked row**; WALKING/UNKNOWN rows refused; no role can open the camera; nodes <= 32, programs <= 6, evaluation cost bound, bytes <= 8192; guard reading `ctx` gives a WARN (U2); constants carried as params so the tuner can sweep them (T24 reads `ParamSchema`).
- Tests: `LoweringDeterministicTest` (same hash, key order irrelevant), `WalkingRowValueRefusedTest`, `ValueClampedToBoundsTest`, `CycleImpossibleTest` (forward reference refused), `CostBoundTest`, `GuardReadsCtxWarnsTest`, `ExprBytesCountedInCapTest`. **Mutation RED:** allow a value on WALKING -> refusal test fails; drop the clamp -> clamp test fails.

**X3 Glasses runner, guard and value hooks, `exprErr` [3.0 h]** — depends X2, T11, T12 (and T2).
- New: `ExprRunner` in `:core:capture` (post-order over the step list, preallocated state, evaluates every step with the shared error rule; runs only on events that already happen: glance candidate for guards, the 1 Hz row tick for values; <= 2 evaluations/s). Edit: `GlanceAdmission` (**one** guard hook before admission, drop-only), `PlanResolver` row read (value hook), T12 counters (`exprErr`, `exprDiv0`) and auto-revert rule `auto:exprErr` (> 10 errors per minute, not div0). T11 is the only task allowed to touch `KeyframeGate`; X3 does not.
- Behaviour: guard error keeps the candidate; value error uses the row default; the reserved timer photo attempt and `ContextGate` run unchanged after the guard; SHADOW counts would-drop and would-change without acting (S67).
- Tests: `GuardDropsOnlyTest`, `GuardErrorKeepsCandidateTest`, `ValueErrorUsesDefaultTest`, `ReservedTimerUnaffectedByGuardTest` (guard "always drop" still yields >= 1 eligible attempt per minute), `WalkingVetoAfterGuardTest`, `Div0NotCountedTowardRevertTest`, `ErrAutoRevertsTest`, `ShadowNeverActsTest` (sensor spy), `NoAllocationOnEventTest`, and the **sensing-unchanged golden** (T12) re-run with an identity-like `exprs`. **Mutation RED:** let the guard return "keep" as force-capture -> `GuardDropsOnlyTest` fails; count div0 toward revert -> `Div0NotCountedTowardRevertTest` fails.

**X4 Replay parity [1.5 h]** — depends X3, T20.
- Edit: `CaptureSimulator` to call `ExprRunner`; replay result shows would-drop counts per guard. No new evaluator.
- Tests: `ReplayExprExactTest` (an exact-hash replay equals the live runner decision for decision on a golden trace), `ExprChangesCountsUnderParamsTest` (T21 param replay with a changed constant). **Mutation RED:** off-by-one on the comparison (`>=` for `>`) -> exact test fails.

**X5 Registry rows and generated inspector [2.0 h]** — depends X2, T3, T35a.
- New: node rows `expr.guard`, `expr.value`, each with a `ParamSchema`: a `TABLE` param `steps` (columns: op, left, right, constant, list), one `ParamSpec.visibleWhen`. Edit: T35a renderer to honour `visibleWhen` (the only front-end change; generic). The compile view (T35a) already shows diagnostics; add meters (cost, bytes, nodes) from the compile result.
- Tests: `InspectorForEveryRegistryTypeTest` (reuse R5.3's if present, else add: iterates the registry, fails if a type needs a special component), `VisibleWhenHidesColumnsTest`, `StepsRefOnlyEarlierRowsTest`, `ParamSweepableTest`.
- No per-node UI. **Stop-loss trigger:** a bespoke editor.

**X7 Device gate (worn, controller) and Codex review [1.5 h controller + review]** — depends X3, X4, T38 conventions.
- Max-cost plan (6 programs) in SHADOW against the identity plan, 35 min worn. Pass: `exprUs` < 50 ms per minute, no GC during evaluation (logcat counts only), would-drop counts within 5 % of exact replay, old glasses reject `unsupported:expr`, worn drain within noise (<= +0.02 %/min). **LIVE stays refused** until 3a-LATER LA7/LA9 land (S44 <= 0.42 %/min); E1 is SHADOW/TRIAL only.
- Codex read-only review of E1: S31/S32 wording against X0, guard hook scope, `exprErr` rule, 8 KB rule.

## Phase E2: windows, try-it [6.5 h]
Entry: E1 shipped and the X7 gate passed.

**C1b Windows in the shared catalogue [1.5 h]** — depends R1.4 (or C1).
- Verify or extend `win` (max|mean|frac): exact integer one-second sums, Int32 buckets, monotonic deque max, **full-horizon warm-up -> UNAVAILABLE**, gaps invalid, reset at plan apply and at capture session restart, <= 4 terms, 1-300 s.
- Tests: `WindowWarmupNotReadyTest`, `WindowGapInvalidNotZeroTest`, `WindowExactSumTest` (1e5 samples, no drift), `WindowResetOnApplyTest`, `WindowStateBytesTest`. **Mutation RED:** a partial window answers at n = 1 -> warm-up test fails.

**X6 Window terms in programs, `choose`, replay [2.0 h]** — depends C1b, X2, X3, X4.
- Edit: `ExprLowering` (distinct `(signal, d, agg)` triples <= 4, state bytes counted), `ExprRunner` (window terms read at tick), the catalogue ops in X5's table (win rows, choose rows; **no new UI code**). Replay starts at plan apply with empty rings, exactly like the device (no ring snapshot in the trace; a trace that starts mid-plan is marked "warming" by T20, not exact).
- Tests: `FiveWindowsRefusedTest`, `WindowReadsOnlyOneHzSignalsTest`, `ReplayWindowParityTest`, `WarmupFallbackSafeTest` (guard keeps, value default while warming). **Mutation RED:** skip the ring reset on apply -> parity test fails.

**X8 Phone-evaluated try-it [1.0 h]** — depends X2. `POST /api/expr/try` runs the **Kotlin** evaluator on sliders' values (no JS evaluator, U1); the response carries step values and ERROR/NOT_READY labels. Tests: `TryItMatchesRunnerTest`, `TryItBoundedInputTest`. Skip if E1 stop-loss was hit.

**X9 Differential fuzz and goldens [1.5 h]** — depends X6. Seeded fuzz of random step lists: Kotlin runner vs a test-only reference (a straight-line evaluator in test sources); golden vectors under `phone/src/test/resources/expr/`. Optionally cel-java as `testImplementation` oracle for the CEL fragment only if the dependency is already available; otherwise skip (not a gate). **Mutation RED:** three seeded op mutations (swap `<`/`<=`, drop clamp, window off-by-one) each killed.

**X10 Device re-run, Codex review of E2 [0.5 h + controller 1 h]:** the X7 plan plus windows, 35 min worn; same pass rule.

## 4. Phase D (LATER): detectors and recognition nodes
**Do not schedule before the entry criteria below are met (S56).** The task list is a sketch; re-plan it with the real week's traces and marks at entry (0.5 h Opus planning, outside this budget). Recognition (tier c, identity) stays unbuilt (S20 needs its own spec and threat model); it is not in D.

**Entry criteria (all must hold; the controller checks them):**
1. Slice 3a-CORE merged and in use for **>= 7 days** with "Collect for tuning" on (>= 20 h of COMPLETE traces, >= 30 marks, by count, never content).
2. Phase E1 shipped (so detector outputs can feed expression guards), and the user has written down which people and object queries they actually want after the week.
3. The S19 probe model identified (Apache-2.0 or MIT, <= 20 MB, p95 <= 300 ms); A/B of tier (a) merged into the extract schema vs a separate prompt (S18) has a result.
4. `frame_detection` and its deletion cascade are in migration A (T8+9); if absent, D1 adds the **next free** migration (not v7), with the encrypted migration suite (3a-LATER LA1) run first.

| Task | Content (reuse first) | Depends | agent-h |
|---|---|---|---|
| D1 | `DetectionResult` (S37), `DetectionSink` adapter, `frame_detection` idempotent replace inside the EffectBuffer commit (S35), additive `ExtractedBatch.detections`; deletion with the frame (T34 graph) | entry, T8+9, T34 | 4 |
| D2 | Tier (a): `ai.prompt` "List objects and people" as a registry preset (prompt forbids names); merged-into-extract variant per the S18 result | D1, slice 2 prompts | 2.5 |
| D3 | Tier (b): `vision.detect` executor on the Nomic ONNX path, SHA-checked catalog model, params `classes`, `scoreMin`, `nmsIou`, `maxDet` as `ParamSchema`; `PhonePowerPolicy` gating; off until downloaded | D1, probe | 5 |
| D4 | Registry rows and generated inspector, no per-node UI; detector output port typed `DetectionResult`; detections readable by phone-side expression nodes only (glasses never see them) | D2, D3, X5 | 2 |
| D5 | Information-flow tests (S20 list) for the detection sinks: no detection field reaches prompts, traces, labels, logs or exports unless the node is the declared producer; **recognition cannot register** (negative test) | D1 | 1.5 |
| D6 | Device check, Codex review | D3 | 2 (controller) |
| | **Phase D** | | **~17** (12-28, conf 35 %) |

Tests (RED first, per task): `DetectionReplaceIdempotentTest`, `ModelChangeNewProducerKeyTest`, `SchemaForbidsNamesTest`, `RecognitionNotRegisteredTest`, `DetectionDeletedWithFrameTest`, `ModelShaMismatchRefusedTest`. **Mutation RED:** insert instead of replace -> idempotence test fails; accept a `name` field -> schema test fails.

## 5. Phase K (LAST): connectors
**Entry:** Phase D shipped or explicitly skipped by the user; slice 3b's `tool.draft` and tool executor registry exist (§4.5); the user has named the first connector. Not scheduled otherwise.
- A connector = a tool executor behind the existing `tool.call` wiring (confirm-before-act, live always as a proposal; §8a). No new node framework.

| Task | Content (reuse first) | Depends | agent-h |
|---|---|---|---|
| K1 | Connector contract on the existing tool-executor registry: auth state (token in the existing secret store, names and counts only in logs), scopes shown in the inspector, a connect/disconnect card generated from `ParamSchema`, deletion cascade (Forget, Delete all) | 3b tool registry, T34 | 4 |
| K2 | First connector (user's choice, one only), read-only first, with a recorded-fixture test suite | K1 | 3.5 |
| K3 | Threat and secret review (S22 helper isolation applies), device check, Codex review | K2 | 1.5 |
| | **Phase K** | | **~9** (6-16, conf 30 %) |

Tests: `TokenNeverInLogsTest`, `DisconnectRevokesTest`, `ForgetCascadesConnectorTest`, `ConfirmBeforeActTest`. **Mutation RED:** log the token field -> `TokenNeverInLogsTest` fails.

## 6. Dependencies on other plans (explicit)
| 3c task | Needs | Fallback |
|---|---|---|
| X1 | T4 (plan types), T5 (caps) | none |
| C1a, X5 | R1.3 and R1.1 (or C1), T3, T35a | C1 builds the seam |
| X2 | T7 (`PlanCompiler`), T4 | none |
| X3 | T2, T11, T12 | none (hard dependency) |
| X4 | T20, T21 | none |
| X6 | R1.4 (or C1) | C1 |
| X7 | T38 checklist conventions, T22 route | none |
| D1 | T8+9, T34 | next-free migration |
| K1 | 3b tool registry | none |

## 7. Dispatch order (first 6) and parallelism
Lowest risk and most unblocking first; 3a-CORE must be merged through T12 (glasses) and T35a for the later items.
1. **X0** (docs, no Gradle): unblocks wording for every other task.
2. **X1** (A): signal catalogue, 8 KB, caps. No dependency on the rules-graph plan.
3. **C1a** (A, after R1.3 merged; if not merged, **C1** first, then C1a): shared seam plus `arith`/ERROR.
4. **X2** (A, after X1 and C1a): program model, lowering, validator.
5. **X3** (B, after X2): glasses runner and hooks (one agent owns `GlanceAdmission` for the plan).
6. **X5** (A, after X2; parallel with X3): registry rows and generated inspector.
Then X4 (after X3), X7 (controller), Codex E1 review, then E2 (C1b first, then X6; X8 and X9 in parallel after X6).

**Parallel windows (<= 3 Gradle builds):** X0 free; X1 and C1a/C1 in parallel (disjoint files); X3 and X5 in parallel (glasses vs phone/canvas); X8 and X9 in parallel; D1 and D2 in parallel; K1 alone. Rules-graph P1 can run beside E1 in a third worktree as long as only one of the two plans edits `StepOps`/`Sample` at a time.

**Merge hotspots:** `PlanConfig`/`PlanJson`/`PlanValidator` (X1, then X2; rules-graph 2.4/4.3 strictly after, per its own order), `GlanceAdmission` (X3 only), `PlanResolver` (X3 only), T12 counters/auto-revert (X3 only), `Sample.kt`/`StepOps` (C1a, coordinated with R1.3/R1.4), T35a form renderer (X5 only), `README.md` none.

## 8. Totals and honesty
| Block | agent-h | Confidence |
|---|---|---|
| E1 | 14.0 | 55 % lands in 11-19 |
| E2 | 6.5 | 55 % lands in 5-10 |
| D (LATER, gated) | ~17 | 35 % lands in 12-28; re-plan at entry |
| K (LAST) | ~9 | 30 % lands in 6-16 |
| Codex fix rounds (4 x ~1.5) | ~6 | |
| **Total** | **~52** (E1+E2 committed: 20.5, plus ~3 for their two fix rounds) | |

Why E1 is 14 h and not the brief's 15 h first slice: no parser, no JS evaluator, no `/api/expr/check` (the compile route already reports diagnostics); offset by the replay task (X4) and the shared-catalogue work (C1a), which the brief did not have. If R1.3 merged first, C1a drops from 1.5 to 0.75 h; if not, C1 adds 3.5 h here and removes about 2.5 h from the rules-graph plan.

## 9. Open for the user (max 6; defaults in bold)
1. Which plan builds the shared catalogue? **Rules-graph P1 (R1.1, R1.3, R1.4) first; 3c C1 is only the fallback.**
2. Which policy fields may a value program drive? **`capPerMin` and the gap seconds of non-locked rows only (never WALKING/UNKNOWN); no `expr.signal` (derived signals) in E1.**
3. Authoring shape: **one `expr.guard`/`expr.value` node with a form table of steps** (each step refers to an earlier row), instead of drawing each step as its own canvas node. (Draw-each-step is possible later, because the shared catalogue nodes exist for the rules graph.)
4. Detector entry gate: **7 days, >= 20 h of COMPLETE traces, >= 30 marks, and a written list of wanted people/object queries.**
5. Tier (a) shape for detectors: **merged into the extract schema** (S18, 0 extra calls) unless the A/B says otherwise.
6. First connector: **none named, so only K1 is scheduled until you pick one** (read-only first).
