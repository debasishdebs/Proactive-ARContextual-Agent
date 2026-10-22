# Context rules graph: the context engine and capture policy as an editable, placeable DAG — lean implementation plan (Sonnet only, reuse-first)

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development. Lean plan: interfaces, behaviour bullets and named tests, not code. Every code task is TDD: write the named tests, see them fail (RED), implement, GREEN, commit. A task marked **mutation RED** must show each named test failing under the stated mutation before it is reverted, recorded in the commit body. **One Sonnet implementer per task, one worktree per agent, no `git stash`, no machine-wide `pkill`, clean up after merges.** No Claude review step: Codex does one read-only review per phase (never implements). Never print secrets (names and counts only).

**Spec:** `docs/superpowers/specs/2026-10-22-context-rules-dag-design-opus.md` (the design; this plan overrides it where the Codex rulings below differ). **Also read:** `.superpowers/codex/context-rules-review-codex.md` (the 9 findings), `.superpowers/codex/context-rules-codex.md` §§6, 10, 12, 15–16 (unavailable-input, memory/work limits, coordinated activation, replay uncertainty: carry over), `docs/superpowers/plans/2026-10-22-canvas-slice3a.md` (tasks named T#), slice 3 spec §16–§18.

**Goal:** the user edits the whole context engine (fact derivation, voting, arbitration, dwell, confidence, `out.ctx`) and the capture policy as one graph, places nodes on glasses or phone, joins them with explicit link edges, and can publish only a graph that the deterministic compiler passes. The phone shows the same graph as sentences and forms. The identity graph reproduces today's `ContextFusion` + `ContextPolicies`.

## 0. Reuse-first architecture (binding for every task)
**Rule:** a task that creates a class with the same job as one in the table below is rejected. A node type is a registry row plus a thin adapter.

| Need | Reuse (existing class) | How |
|---|---|---|
| Fact derivation, pair, hold, band, dwell, confidence | `ContextFusion` (`core:model`, `context/`) | **Extract-function refactor** into `FusionSteps` (same file set, behaviour-preserving, `ContextFusionTest` stays green); node adapters call `FusionSteps`. A byte-identical **frozen snapshot** `ContextFusionOracle` lives only in test sources, hash-locked, and is the oracle. Float/Double mixes are kept because the code is shared, not re-typed. |
| Policy rows | `ContextPolicies.DEFAULT`, `ContextCapturePolicy`, `PlanRow`, `PlanState` | `policy.row` adapter reads/writes `PlanRow`; rows resolve through `PlanResolver` (T4) |
| Bounds, size, validation | `PlanBounds` (gaps 5–300 s), `PLAN_MAX_BYTES`, `PlanValidator`, `PlanReceiver`, `reservedBoundMs` | glasses graph is a new block validated by extending `PlanValidator.bounds`/`rawRules`, never a second validator |
| Canonical JSON and hash | `CanonicalJson`, `PlanJson`, `PipelineHash` | graph doc and `phoneGraphHash` use them |
| Node registry, params, forms | `NodeTypes` v2 (T3: `NodeType`, `Domain`, `ExecutorKind.BUILTIN`, `PortType`), `ParamSchema`/`ParamSpec`/`ParamCheck`/`TableSpec` | every context node = one `NodeType` + `ParamSchema`; dwell matrix = a `TABLE` param; **phone view and canvas inspector are generated from the schema** |
| Compile pipeline and diagnostics shape | `PlanCompiler` (T7), `PipelineValidator` (messages with node anchors) | `GraphCompiler` is a pass registered inside `PlanCompiler`, same result/diagnostic style |
| Persistence, deletion | T8+9 fenced files, deletion epoch, backup rules | graph revisions are **fenced files**, not a new Room table (T8+9 owns the only migration) |
| Publish, gates, TRIAL | T26 state machine, T27+28 gates and actions, T11/T12 glasses apply | graph is one more component of the same state machine |
| Replay | T20/T21 `CaptureSimulator`, `BatteryLine`, trace schema (T6) | graph replay feeds ctx timelines into it |
| Canvas | slice 2/3a React Flow editor, T35 lanes and param forms | new node types render with the existing node component |
| Glasses veto | `ContextGate` 15 s local walking veto | untouched; always runs after `in.ctx` |

**Granularity decision (G2, default):** the identity graph uses about **30 nodes** (not 70): specialist nodes `fusion.facts.*`, `vote.logodds`, `band`, `arbiter`, `commit`, `confidence` each wrap one `FusionSteps` function and expose every threshold/TTL/prior/dwell as a param. Generic nodes (`cmp`, `and`, `or`, `not`, `clamp`, `choose`, `fresh`, `win`, `pair`, `for`, `hold`, `latch`, `hyst`, `prev`, `link.*`, `policy.*`) are used for the user-added rules and for the parts of the identity graph that are plain thresholds. Splitting a specialist node further is a later one-entry change; this is what keeps the interpreter near 3 000 lines of Kotlin instead of 7 000.

**One generic interpreter** (`GraphEngine`, `core:model` package `context.graph`), used unchanged by phone and glasses: topological order, cycles only through `prev`, one monotonic arrival clock per device with `ContextFusion`'s rules (pre-tick `refreshHeld` before each evidence; backwards jump > 60 s resets), preallocated state, no per-node allocation on the tick.
**One generic phone view** generated from `ParamSchema` + a per-`NodeType` `phrase` template. **No per-node bespoke UI anywhere.**

## 1. Codex rulings applied (each is a task acceptance line)
| # | Ruling | Where enforced |
|---|---|---|
| R1 | Intent/image evidence never throttles capture. A mandatory **non-image capture basis** (temporal, not just reason flags) | 1.9 + 2.2 taint rule; deltas file |
| R2 | `configHash`/`compileKey` cover phone graph hash and glasses plan; coordinated activation | 2.4, 2.6, 4.6 |
| R3 | Walking veto precedence protected; remaining arbitration order is graph data | 1.5 (`arbiter` order = table param), 2.1 floor |
| R4 | No raw-forward exemption: every link crossing passes an explicit `link.poll` / `link.agg` / `link.coalesce` | 4.2 (`E_LINK_RAW`, no cap) |
| R5 | Identity graph satisfies its own validator (gaps ≤ 300 s, FLIGHT 120/300, watch TTL **25 min** as in `ContextHub`, Float/Double as production) | 1.6 + test `IdentityValidatesTest` |
| R6 | Acquisition, delivery, evaluation rates are three declared fields; samples carry age, coverage, unavailable | 1.3 `Sample`, 4.1, 4.2 |
| R7 | Narrow `out.ctx` declassifier; quantised GPS/BT facts stay phone-local through replay/export | 2.1, 4.2, 6.1 |
| R8 | Hard limits: total state bytes and weighted work/s on glasses nodes (and phone) | 2.1 `EngineLimits`, 4.3 |
| R9 | Replay/SHADOW: counterfactuals needing absent observations are `UNCERTAIN`; SHADOW never controls sensing | 2.6, 6.2, 6.3 |
| R10 | Switch from `ContextFusion` only after oracle goldens + fuzz + the **explicit coverage list**; 48 h on device is supplementary | 1.7, 1.8, 1.10 |

**Documented intentional differences vs the frozen oracle (the deltas file `oracle-deltas.json`, test resource, consumed by the fuzz as the only allowed diffs):**
- **D1 (ruling 1):** the *published* `out.ctx` state (and so the capture row) uses the non-image basis: when the full decision is capture-reducing (REST, FLIGHT) but the **imageless twin** evaluation is not, `out.ctx` publishes the twin's state. The full `ContextDecision` (the oracle's fields) is still computed and equal at every tick; only the published/captured projection differs, and only under a predicate the fuzz checks.
- No other delta in the identity graph (arbitration order, TTLs, dwell all equal). A new delta needs a Codex-reviewed entry.

## 2. Phases, estimates, stop-loss
Each phase ships independently and may be the last. Estimates are Sonnet agent-hours (code + tests, no wall-clock waits). **Hard stop-loss = 1.3 × phase estimate, or the named trigger, whichever first: stop, write a handoff note, keep the previous phase's shipped state.**

| Phase | Ships | Agent-h | Stop-loss (h / trigger) | Needs from slice 3a |
|---|---|---|---|---|
| P1 | interpreter + identity graph + oracle goldens (phone, flagged off, SHADOW parity counter) | 23.0 | 30 / differential fuzz still failing after 2 fix rounds on the same field: stop, keep `ContextFusion` | T3 (NodeTypes v2, ParamSchema) |
| P2 | floor + `GraphCompiler` + `compileKey` + store + SHADOW/TRIAL publish of phone-only graphs | 14.5 | 19 / any invariant (R1, R3, R7) needs a design change | T4, T7, T8+9, T26, T27+28 |
| P3 | phone "Rules" view | 8.0 | 10.5 / form generator needs per-node code | T3, T35a (param forms) |
| P4 | `DeviceProfiles`, link nodes, glasses subgraph in `CapturePlan`, link runtime, coordinated activation | 19.0 (+2.5 controller) | 25 / `PLAN_MAX_BYTES` or glasses CPU/battery gate fails | T4, T5, T7, T11, T12, T26 |
| P5 | canvas lanes, link edges, inspectors | 9.5 | 12.5 / needs a new editor framework | T35a/b, T3 |
| P6 | evidence recorder, graph replay, uncertain counterfactuals; optional LLM advisory | 9.0 (LLM 2.0 optional) | 12 / replay cannot reproduce active-graph ctx exactly | T6, T19, T20, T21 |
| | **Base total** | **83.0** | | |
| | + fix rounds after Codex reviews (6 × ~1.5) | 9.0 | | |
| | **Expected total** | **≈ 92 agent-h** (≈ 90 without the LLM pass) plus **≈ 4.5 controller/device h** (4.7 + 1.10 parity run + P6 check) | | |

**Honest comparison:** Codex puts the unreused design at 90–130 h. Reuse-first removes the re-created validator, store, form generators, canvas inspectors and fusion re-implementation (about 20–25 h), but the dominant cost is oracle parity, two-device recovery and replay uncertainty, which reuse does not shrink. **Estimate 92 h, 70–115 h range, confidence about 55 %** that the real total lands in 75–105 h. Phone-only value (P1–P3, 45.5 h base) is the first decision point.

Per-phase gates: Codex read-only review once at phase end (input: diff, this plan's phase section, the rulings table); fixes by the same Sonnet agent; no second review unless Codex marks a finding Critical.

## Shared interfaces (pinned; tasks must not drift)
- `ContextGraph(schema, revision, nodes: List<GNode>, edges: List<GEdge>)`; `GNode(id, type, params: JsonObject, device: GLASSES|PHONE|WATCH, label)`; `GEdge(from, fromPort, to, toPort)`. Canonical JSON via `CanonicalJson`; `graphHash12`.
- `SignalDecl(name, sourceDevice, sensor, acquireHz, deliverHz, evalHz, privacy: OPEN|PHONE_ONLY|INTENT, maxBytes)`; the three rates are separate and `E_RATE` compares only like with like.
- `Sample(value: Double|Boolean|Enum, atMs, ageMs, coverage: 0..1, available: Boolean, gen: Long)`. A node reading an `available=false` or too-old sample yields `UNAVAILABLE`, which every logic node treats as not-satisfied, never as stale-true.
- `GraphEngine.step(evidence | tick, nowMs): GraphOutputs(ctx: CtxOut, captureCtx: CtxOut, policy: Map<PlanState, PlanRow>, reasons, debug)`.
- `Diagnostic(code, severity ERROR|WARN, message, anchor: node|edge|graph, fix?)`; `CompileResult(ok, diagnostics, glassesPlanPart?, phoneGraph?, compileKey)`.
- `compileKey = SHA-256(graphHash, NodeTypes registry version, SignalDecl/DeviceProfiles version, identity version, compiler version, glasses caps)`. `CapturePlan.config` gains `phoneGraphHash` and `ctxGen` **inside `config`** (so `configHash` changes when the phone graph changes; `meta` stays excluded).
- Glasses block: `PlanConfig.graph: GlassesGraph?` (flat prefix-form nodes, ≤ 96 nodes, ≤ 8 link slots, ≤ 4 windows, `stateBytes ≤ 2 048`, `workPerSec ≤ 400` weighted units). Glasses cap `graph1`; old glasses reject `unsupported:graph1`.

---
## Phase P1: interpreter, identity graph, oracle goldens (phone only, nothing user-visible) [23.0 h]
Worktree stream A. Nothing in P1 changes production behaviour: the interpreter runs only as an off-by-default SHADOW twin.

**1.1 Graph schema and node registry rows [2.0 h]** — depends T3.
- New: `context/graph/ContextGraph.kt`, `GraphJson.kt`, `ContextNodeTypes.kt` (registers rows into the existing `NodeTypes` registry; `ExecutorKind.BUILTIN`, `Domain.RT_PHONE`/`RT_GLASSES`, ports typed by `PortType.SIGNAL`/`CONTEXT`), `SignalDecls.kt`. Reused: `NodeTypes`, `ParamSchema` (one-sided bounds = new `ParamSpec.safeSide` flag, the only schema extension), `CanonicalJson`.
- Tests (RED first): `GraphJsonRoundTripTest` (canonical, stable hash, reordered keys equal), `ContextNodeTypesTest` (every type has a schema, a `phrase` template, a domain; `ParamCheck.problems` rejects out-of-bounds), `RegistryVersionTest`. **Mutation RED:** drop a param bound → `ParamCheck` test fails.

**1.2 Extract `FusionSteps` from `ContextFusion` [1.5 h]** — no dependencies.
- Edit: `ContextFusion.kt` (pure extract-function; public API unchanged). New: `FusionSteps.kt`; test-only `ContextFusionOracle.kt` = byte copy of the pre-change file, with `OracleHashTest` (SHA-256 of the file text) so it cannot be edited.
- Tests: the existing `ContextFusionTest` unchanged and green before and after; `FusionStepsParityTest` runs 1 000 seeded traces through `ContextFusion` and the oracle: all `ContextDecision` fields equal. **Mutation RED:** change one constant in `FusionSteps` → parity fails.

**1.3 Interpreter core and generic stateless nodes [3.0 h]** — depends 1.1.
- New: `GraphEngine.kt`, `Topo.kt`, `ArrivalClock.kt` (the `arrival()`/`lastNow`/60 s-jump logic is **moved** from `ContextFusion` into `FusionSteps` and shared), `Sample.kt`, generic nodes `cmp`, `in`, `and`, `or`, `not`, `choose`, `clamp`, `fresh`, `prev`.
- Behaviour: topo order, cycle only via `prev` (else load error), pre-tick before each evidence, `UNAVAILABLE` propagation (R6), restart = engine rebuilt cold (state empty, warm-up flag exposed).
- Tests: `TopoOrderTest`, `PrevOnlyLoopTest`, `UnavailableDoesNotSatisfyTest`, `ClockJumpResetTest`, `PreTickBeforeEvidenceTest`, `NoAllocationTest` (allocation counter over 10⁵ ticks). **Mutation RED:** swap pre-tick/apply order → `PreTickBeforeEvidenceTest` fails; treat UNAVAILABLE as true → fails.

**1.4 Stateful generic nodes as thin adapters [2.5 h]** — depends 1.2, 1.3.
- New adapters (each ≤ 60 lines): `win(max|mean|frac|count)`, `pair` (calls `FusionSteps.fastConfirmed`), `newest(n,maxAge)`, `for(T)`, `hold(T, onlyWhile)` (calls `FusionSteps.heldWithin`/`refreshHeld`), `latch`, `hyst`. Window storage preallocated rings; bytes counted for `EngineLimits`.
- Tests: `PairNodeMatchesFastConfirmedTest` (45 s boundary ±1 ms), `HoldOnlyWhileTest` (180 s hold only while previous committed is vehicle), `LatchResetOnDisconnectTest`, `WindowStateBytesTest`. **Mutation RED:** off-by-one on the ≤ 45 s gap → boundary test fails.

**1.5 Specialist adapters [3.0 h]** — depends 1.2, 1.3.
- New adapters over `FusionSteps`: `fusion.facts.walking|vehicle|flight|still`, `vote.logodds` (priors by key, clamp), `band`, `arbiter` (ordered target table param; **walking first is fixed by the floor, the rest is data**, R3), `commit` (dwell matrix as a `TABLE` param, same-episode rule), `confidence`, `out.ctx` (validForMs, resend period), `policy.row`, `policy.unless`.
- Tests: one `<Node>MatchesStepTest` each (adapter output equals the `FusionSteps` function on 200 seeded inputs); `ArbiterOrderIsDataTest` (reordered table changes outcome in a synthetic case, walking still first). **Mutation RED:** hard-code the order → `ArbiterOrderIsDataTest` fails.

**1.6 Identity graph as data [2.5 h]** — depends 1.4, 1.5.
- New: `identity-v1.json` (resource, hash-locked), `IdentityGraphBuilder.kt` (code that emits the same JSON, test compares), `IdentityGraph.kt` loader. Params copy production exactly: FLIGHT gaps 120/300 s, REST cap 2, **watch TTL = the value `ContextHub` passes (25 min)**, hint 90/120 s, AR 30 min, steps 90 s.
- Tests: `IdentityJsonLockedTest`, `IdentityValidatesTest` (graph passes `PlanValidator` + this plan's compiler once P2 exists; until then `PlanBounds` checks), `IdentityPolicyHashTest` (policy rows resolve to `PlanResolver`'s identity plan hash), `IdentityParamsMatchHubTest` (reads `ContextHub` constants by reflection-free import).

**1.7 Oracle goldens [1.5 h]** — depends 1.6.
- Every `ContextFusionTest` scenario + the wave B §6.3 override table, replayed through `ContextFusionOracle` and `GraphEngine(identity)`; **every `ContextDecision` field equal at every tick**.
- Tests: `OracleGoldenScenariosTest`, `OverrideTableGoldenTest`. **Mutation RED:** perturb one identity param (BT hold 180 s → 181 s) → at least one golden fails.

**1.8 Differential fuzz and the explicit coverage list [3.0 h]** — depends 1.7, 1.9 (for D1).
- Seeded fuzz: 10⁴ traces × 2 h with out-of-order stamps and clock jumps; compare every field every tick except where `oracle-deltas.json` predicates apply (D1 only, and the fuzz asserts each delta occurrence satisfies its predicate).
- Named coverage tests (each must exist and be deterministic, R10): `ExpiryBoundaryTest` (every TTL and hold at −1/0/+1 ms), `ContradictionLatchTest` (BT contradicted by images then disconnect), `ReorderedArrivalTest`, `SourceRevocationTest` (My car removed mid-hold), `ClockJumpTest` (±60 s edge), `ReconnectTest` (glasses/watch gap, then resume), `RestartTest` (engine rebuilt cold mid-vehicle: warm-up documented, then equal after warm-up), `TrialRollbackTest` (graph swapped back mid-trace). Coverage is checked by `CoverageListTest` (fails if a named test is missing or `@Ignore`d).
- **Mutation RED:** three seeded mutations (drop pre-tick, drop latch reset, swap two dwell cells) must each be killed by at least one named test; recorded.

**1.9 Mandatory non-image capture basis (R1, D1) [2.0 h]** — depends 1.5.
- New: `CaptureBasis.kt`: a second `GraphEngine` instance (the **imageless twin**: `src.intent` and image-derived facts replaced by `UNAVAILABLE`, same graph, same code) evaluated alongside; `captureCtx` = full state unless the full state is capture-reducing and the twin's is not, then the twin's state (conservative toward more capture, evaluated on the temporal state, so a latch set by images does not persist into the capture projection). Phone `out.ctx` publishes `captureCtx`.
- Tests: `ImageLatchCannotReachRestTest` (Codex's failure case: BT sustains vehicle, two indoor images contradict BT, fresh stillness; full decision says REST, `captureCtx` stays vehicle), `TwinSharesCodeTest` (same class, different mask), `DeltaPredicateTest`. **Mutation RED:** publish full state → `ImageLatchCannotReachRestTest` fails.

**1.10 Hub integration, flag and SHADOW parity counter [2.0 h]** — depends 1.7, 1.9.
- Edit: `ContextHub` (phone; one flag `ctxEngine = FUSION|SHADOW_GRAPH|GRAPH`, default FUSION), `ContextHubStats` (parity counters per field). Reuse the existing stats/diagnostics card.
- Tests: `HubShadowDoesNotChangeOutputTest` (same ctx emitted with the twin on), `ParityCounterCountsDivergenceTest`, `FlagDefaultOffTest`. **Switch criterion (R10):** flag may move to GRAPH only when 1.7 + 1.8 + coverage list are green; the 48 h on-device counter is reported as supplementary and not a gate.

**P1 review point (Codex, read-only):** 1.2 extraction fidelity, 1.8 coverage list completeness, D1 predicate soundness, identity params vs `ContextHub`.

---
## Phase P2: floor, compiler gate, `compileKey`, store, SHADOW/TRIAL for phone-only graphs [14.5 h]
Stream B (needs T4, T7, T8+9, T26, T27+28 merged; if T26 is late, 2.6 waits).

**2.1 Engine floor and limits [2.5 h]** — depends 1.5.
- New: `EngineFloor.kt` (wraps the interpreter): `floorWalking` forces WALKING ahead of the arbiter using **one-sided params** (TTL max, steps threshold min, `fastKmh` max(graph, 25)); privacy: only the `out.ctx` declassifier may emit state, confidence, reason names (no kmh, no device booleans); `EngineLimits(stateBytes, weightedWorkPerSec)` per device (R8: phone 64 KB / 4 000, glasses 2 KB / 400). Reuse: `reservedBoundMs`, `PlanBounds`.
- Tests: `WalkingFloorOverridesGraphTest`, `FloorWithoutVetoNodeTest`, `DeclassifierEmitsOnlyAllowedFieldsTest` (a node that routes speed into a reason string is refused), `LimitsRejectLongWindowTest` (a 24 h `win` over 1 Hz exceeds state bytes). **Mutation RED:** let graph lower `fastKmh` below 25 → floor test fails.

**2.2 `GraphCompiler` structural pass and taint [3.0 h]** — depends 1.1, 2.1, T7.
- New: `GraphCompiler.kt` registered inside `PlanCompiler`; reuse `PipelineValidator`'s message style. Errors: `E_TYPE`, `E_CYCLE`, `E_TIE`, `E_INVARIANT`, `E_PRIVACY`, `E_LIMITS`, `E_PLAN_SIZE`. **Taint (R1):** INTENT-tainted values may reach only `vote.logodds → band` (vehicle sub-state) and the **full** decision; they may not reach `captureCtx`, `policy.*`, or any guard (the twin enforces it at runtime, the compiler proves it statically). PHONE_ONLY taint may reach only the `out.ctx` declassifier. `nudges` not editable on DRIVING; `policy.unless` relaxes only toward UNKNOWN; all values end in `clamp` to `PlanBounds`.
- Tests: `IdentityGraphCompilesCleanTest`, one test per code with the Codex failure examples, `IntentToPolicyRefusedTest`, `SpeedToReasonRefusedTest`, `UnboundedValueRefusedTest`, `DiagnosticAnchorsTest`. **Mutation RED:** disable taint propagation through `choose` → `IntentToPolicyRefusedTest` fails.

**2.3 Lints and scenario runner [2.0 h]** — depends 2.2, 1.6.
- New: `GraphLints.kt` (`W_DEAD`, `W_SHADOWED`, `W_FLAP`, `W_GPS_DEMAND`, `W_WALK_GUARD`) and `ScenarioRunner.kt` (built-in traces: drive in My car, taxi, walk with watch, flight, indoor images; reuse the P1 fuzz trace generator). Lints are deterministic and seeded.
- Tests: `DeadRuleLintTest`, `FlapLintTest` (transition count > 6/h in the drive scenario), `LintsAreDeterministicTest`.

**2.4 `configHash`/`compileKey` cover both graphs [1.5 h]** — depends T4, 2.2.
- Edit: `PlanConfig` (+`phoneGraphHash`, `ctxGen` in `config`), `PlanJson` canonical encode, `PlanValidator` (hash recompute), `PlanResolver`. New: `CompileKey.kt` (§Shared interfaces).
- Tests: `PhoneGraphChangesConfigHashTest` (the Codex failure: changing only the phone graph changes `configHash`), `MetaStillExcludedTest`, `CompileKeyChangesOnRegistryBumpTest`, `OldPlanWithoutGraphHashIdenticalTest` (golden: an existing T4 plan keeps its hash). **Mutation RED:** hash only the glasses config → first test fails.

**2.5 Graph store and the publish lock [2.5 h]** — depends T8+9, 2.2.
- New: `GraphStore.kt` over the T9 fenced file store: autosaved revisions (any diagnostics), compare-and-set on `revision`, `current CompileResult` cache keyed by `compileKey`, publish allowed only when stored result is ok for the **current** key (recomputed at publish, server side). "Use built-in rules" restores identity. Deletion epoch and Delete all wipe it; excluded from backup and export unless labels are replaced.
- Tests: `AutosaveKeepsInvalidDraftTest`, `CasConflictTest`, `StaleKeyBlocksPublishTest`, `DeleteAllWipesGraphsTest`, `BackupExcludesGraphsTest`.

**2.6 Phone-only activation: SHADOW twin, TRIAL, restore [3.0 h]** — depends 2.4, 2.5, T26, T27+28, 1.10.
- Edit: T26 state machine gains a `phone_graph` component (generation counter, staged → effective, restart recovery from the store, **warm-up**: no TRIAL decision counted until the twin has `warmupMs` of fresh samples, coordinated rollback = one atomic generation swap back). SHADOW = twin evaluates, counters only, **never requests real sensing** (GPS demand and image requests from the twin are logged, not issued: test spy on `SpeedSource`/`IntentSampler`). TRIAL ≤ 60 min with auto-revert (trips: > 3 glasses vetoes in 10 min, or reducing state while the hint says WALKING). **LIVE for a graph that changes REST/FLIGHT inference is refused** until a replay (P6) shows camera-open delta; otherwise refused with the T27 text pattern.
- Tests: `ShadowIssuesNoSensingRequestsTest`, `TrialAutoRevertTest`, `RestartMidTrialRecoversTest`, `WarmupNotCountedTest`, `RollbackGenerationTest`, `LiveRefusedForRestFlightChangeTest`. **Mutation RED:** let SHADOW call `SpeedSource` → first test fails.

**P2 review point (Codex):** taint proof completeness (indirect paths, R1), floor one-sidedness, recovery table, `configHash` golden.

---
## Phase P3: phone "Rules" view, generated [8.0 h]
Stream B. No per-node UI: everything comes from `NodeType.phrase` + `ParamSchema`.

**3.1 Chain projection [1.5 h]** — depends 1.1, 2.2.
- New: `RuleChainProjector.kt` (graph → list of sink-rooted chains; chain = AND, fan-in = OR, per slice 3 §18), `PhraseRenderer.kt` (fills `phrase` templates, e.g. "head peak > {x} rad/s (glasses, {win} s)"), `Editability.kt` (chain of ≤ 6 nodes with AND/NOT = editable; anything else read-only "edit on the canvas").
- Tests: `ProjectsIdentityToSentencesGolden`, `EditabilityBoundaryTest`, `PhraseHasNoUnfilledSlotsTest` (every registry type renders).

**3.2 Schema form renderer [2.5 h]** — depends T35a, 1.1.
- Reuse the existing ParamSchema form widgets if the phone already has them; otherwise one `SchemaForm` Compose function (int/number/enum/bool/table by `ParamKind`, min/max/step, "safer only" badge from `safeSide`). Writes `GraphEdit` ops through `GraphStore.cas`.
- Tests: `FormPerParamKindTest`, `SafeSideBoundShownTest`, `EditWritesRevisionTest`.

**3.3 Tabs: Rules, Timing, Built-ins [2.5 h]** — depends 3.1, 3.2, 2.3.
- Rules (sentences + edit), Timing (the `commit`/`arbiter` TABLE params), Built-ins (identity nodes with toggles; toggling off a floor-protected node is refused with the floor message). Diagnostics list with anchors and the scenario runner button; save always, Publish/SHADOW/TRIAL buttons only enabled for a current-key ok result (calls 2.5/2.6).
- Tests: `PublishDisabledWhenKeyStaleTest`, `FloorNodeToggleRefusedTest`, `DiagnosticTapScrollsToAnchorTest`.

**3.4 Settings entry and wiring [1.5 h]** — depends 3.3, T22/T33 composition.
- Settings → Context → Rules row, composition registration (one line in the existing graph composition files owned by T22/T33; no other edits there), strings. Test: `RulesRowOpensViewTest`, smoke on a fixture graph.

**P3 review point (Codex):** that no node type has its own screen; read-only fallback is honest.
**Stop-loss trigger:** if any node type needs special UI, stop and move it read-only.

---
## Phase P4: devices, link edges, glasses subgraph [19.0 h agent + 2.5 h controller]
Streams C (glasses) and A (compiler). Needs T4, T5, T7, T11, T12, T26.

**4.1 `DeviceProfiles` and `SignalDecls` data [2.0 h]** — depends 1.1.
- New: `DeviceProfiles.kt` (per-release table in `core:model`: sensor rates, link budgets incl. `pc_motion_hint` ≤ 512 B, `pc_motion_ctx` ≤ 1 per 10 s, plan ≤ 8 192 B, cost coefficients, `kLink = 0.005 %/min per extra message/min` labelled estimate), `SignalDecls` complete per the design §3.1 table with the three rates (R6) and privacy class. The live probe may only warn.
- Tests: `TableVersionedTest`, `GpsBtDeclaredPhoneOnlyTest`, `RatesAreThreeFieldsTest`, `ProbeCannotRelaxBudgetTest`.

**4.2 Link nodes and cross-device diagnostics [3.5 h]** — depends 4.1, 2.2.
- New nodes (thin; registry rows + adapters over 1.4 windows): `link.poll(period)` (samples latest), `link.agg(win|pair|newest|any, N)`, `link.coalesce(minGap, onChange)`, `link.recv`. **No raw-forward exemption (R4):** a signal edge crossing devices without one of these before the link is `E_LINK_RAW`, whatever its rate or size. The identity glasses hint becomes `link.coalesce(minGap 5 s)` + `link.poll(60 s)`. New diagnostics: `E_PLACEMENT`, `E_LINK_RAW`, `E_LINK_BUDGET`, `E_RATE` (compares evaluation ≤ delivery of the path; acquisition only sets freshness: R6), `E_LATENCY`, `E_PRIVACY` crossing (declassifier is the only allowed taint exit: R7), `W_BATTERY` (uses `BatteryLine` coefficients + `kLink`), "insert aggregate" fix.
- Tests: `RawSlowSignalStillRefusedTest` (airplane mode placed on glasses, no poll → error), `PollSatisfiesRuleTest`, `EvalFasterThanAcquireIsFineTest` (GPS 1/30 s acquired, evaluated every 10 s with age gating), `SpeedBooleanCannotCrossTest`, `LatencyBudgetUnlessTest`. **Mutation RED:** reintroduce a "≤ 1/min" exemption → `RawSlowSignalStillRefusedTest` fails.

**4.3 Glasses subgraph in `CapturePlan` [3.5 h]** — depends T4, T7, 4.2.
- Edit: `PlanConfig.graph` (§Shared interfaces), `PlanJson` (prefix-form encode/decode), `PlanValidator.bounds` (node ≤ 96, windows ≤ 4, slots ≤ 8, **stateBytes and weighted work limits** recomputed from the plan, not trusted), `PlanCompiler` partition by target device, `planBytes`/`graphBytes` report; over 8 192 B is an error, never trimmed. Reuse `PlanBounds` clamps for every literal.
- Tests: `GraphBlockRoundTripTest`, `OverStateBytesRefusedByReceiverTest` (receiver re-validates independently), `PlanSizeNeverTrimsTest`, `OldGlassesRejectGraph1Test`, `IdentityGlassesPartIsSmallTest` (golden byte length).

**4.4 Glasses runtime [3.5 h]** — depends 4.3, T11, T12.
- Edit: glasses apply path from T11 (staged immutable config now includes the graph), a `GlassesGraphRunner` that is the **same `GraphEngine`** restricted to the glasses node set, ticking on the existing 1 Hz `SecondFeatures` row and the policy tick (no 50 Hz node), `in.ctx` (TTL from receipt, kill switch `phone_motion`) then `ContextGate`'s veto unchanged. `policy.unless` implemented as `lastAboveAtMs` O(1). Heartbeat adds `graphUs`, `graphGen`.
- Tests: `GlassesVetoAfterInCtxTest`, `NoSensingChangeGoldenStillPasses` (T12 golden), `UnlessRelaxesTowardUnknownOnlyTest`, `RunnerWorkBudgetTest`. **Mutation RED:** run the veto before `in.ctx` → first test fails.

**4.5 Link runtime [3.0 h]** — depends 4.2, 4.4, T5.
- Edit: hint message gains `aggs: List<Int>` (≤ 8 quantised slots named by the plan, ≤ 512 B); phone `link.recv` produces `Sample` with age/coverage/gen; missing/late slot = unavailable (R6); `ctx` message carries `ctxGen`; the link stats (`linkMsgs/min`, RTT, drops) go into ack/heartbeat.
- Tests: `SlotQuantisationRoundTripTest` (replay-exact), `LateSlotBecomesUnavailableTest`, `HintFreePeriodNoExtraMessageTest`, `OldPeerIgnoresAggsTest`.

**4.6 Coordinated activation [3.5 h]** — depends 2.6, 4.4, 4.5, T26.
- Edit T26: group of two components (`phone_graph`, `capture_plan`) with one `activationId`; the glasses accept the plan only if the `ctxGen`/`phoneGraphHash` in `config` match what the phone will emit; phone stages, glasses stage + ack, phone commits; the glasses local TRIAL expiry restores the previous **pair** (phone learns on next ack and rolls back its generation); restart recovery table (phone down, glasses down, ack lost, trial expiry while phone offline); warm-up window before reducing policy applies.
- Tests: `MismatchedPairRefusedTest`, `DelayedInstallNoMixedPairTest`, `GlassesTrialExpiryRollsBackPhoneTest`, `RestartMatrixTest` (each row), `WarmupBlocksReducingPolicyTest`.

**4.7 Device checks (controller, 2.5 h)**: max glasses graph SHADOW hour (≤ +0.02 %/min vs 0.49–0.51), link A/B for `k` (2 × 30 min), plan size on the real device. Until done, link periods < 60 s are SHADOW/TRIAL only and LIVE stays refused.

**P4 review point (Codex):** R2, R4, R6, R8 enforcement in the receiver, recovery matrix.

---
## Phase P5: canvas lanes and inspectors [9.5 h]
Stream B. Reuse the slice 2/3a React Flow editor and T35a param forms; the only new front-end code is data-driven.

**5.1 Lanes, placement, badges [3.0 h]** — depends T35a, 4.1. Device lanes (GLASSES, PHONE, WATCH source-only) from `GNode.device`; drag between lanes calls the compile API and shows the diagnostic when infeasible; source/target/rate badges from `SignalDecl`.
Tests (front-end unit + API): `LaneFromDeviceFieldTest`, `DragInfeasibleShowsReasonTest`, `BadgesFromDeclsTest`.

**5.2 Link edges and the one-click fix [2.5 h]** — depends 5.1, 4.2. Link edges drawn when devices differ; budget and measured badges; clicking `E_LINK_RAW` offers "insert aggregate" which only calls the compiler's `fix` payload (no client logic).
Tests: `LinkEdgeDrawnTest`, `InsertAggregateFixTest`.

**5.3 Generic inspector and table inspectors [2.5 h]** — depends T35a, 1.5. The existing param form renders any `NodeType`; `arbiter` order and `commit` dwell matrix use the existing `TABLE` kind. Test: `InspectorForEveryRegistryTypeTest` (iterates the registry; failure if a type needs a special component).

**5.4 Floor ghosts and publish hooks [1.5 h]** — depends 5.3, T35b. Locked ghost nodes for floor items; publish/SHADOW/TRIAL buttons reuse T35b, gated by the current-key result.
Tests: `FloorGhostNotEditableTest`, `PublishNeedsCurrentKeyTest`.

**P5 review point (Codex):** no node-specific components. **Stop-loss trigger:** a new front-end dependency.

---
## Phase P6: replay, simulation, optional LLM advisory [9.0 h]
Stream A. Needs T6, T19, T20, T21.

**6.1 Scrubbed evidence recorder (LA4) [1.5 h]** — depends T19, 4.1. Hub recorder writes evidence with the declassifier rules: kmh quantised, alias booleans only, **no coordinates**, quantised GPS/BT stay phone-local in traces and exports (R7). Records enough temporal state for warm start. Tests: `RecorderScrubTest`, `ExportExcludesPhoneOnlyTest`.

**6.2 Graph replay with uncertain counterfactuals [3.5 h]** — depends 6.1, T20/T21, 1.10. Evidence → `GraphEngine` (candidate) → ctx timeline → `CaptureSimulator` + IMU trace. Replaying the **active** graph must reproduce the recorded ctx exactly (`ActiveGraphReplayExactTest`). For a candidate that needs observations absent from the trace (GPS fix, an image, a BT event not recorded), affected intervals are marked `UNCERTAIN`; the report shows minutes certain/uncertain and never invents confidence (R9). Tests: `AbsentGpsMarksUncertainTest`, `QuantisedSpeedNoteTest`, `CertainIntervalsOnlyScoreTest`.

**6.3 Report and SHADOW guarantee [2.0 h]** — depends 6.2. Report vs active graph: minutes per state, transitions, captures/min, vetoes, GPS-on minutes, link msgs/min and %/min estimate, dead/redundant rules, uncertain share; feeds LIVE gate for REST/FLIGHT-changing graphs (T27+28 gate 3 reused). Tests: `ReportDeterministicTest`, `ShadowNeverControlsSensingIntegrationTest` (spies on real sensors across a full SHADOW session).

**6.4 LLM advisory pass (optional, off by default) [2.0 h]** — depends 2.2. Input: structural graph with placeholder labels only; output `{anchor, note}` list in a "Suggestions (AI)" section; cannot clear an error or block a publish; cache by `compileKey`; malformed reply dropped and counted. Reuses the configured AI endpoint (CallClass OTHER). Tests: `AdvisoryNeverChangesCompileOkTest`, `NoPrivateFieldsInPromptTest` (prompt scan for labels/addresses/GPS), `MalformedReplyDroppedTest`.

**P6 review point (Codex):** R7, R9; SHADOW sensing spy coverage.

---
## Dispatch order (first 6) and parallelism
Lowest risk and most unblocking first; one agent per worktree.
1. **1.2** `FusionSteps` extract + frozen oracle (no deps; unblocks 1.3–1.10).
2. **1.1** graph schema + registry rows (after T3 merged; if T3 is not merged, start 1.2 and **4.1** instead).
3. **1.3** interpreter core (after 1.1).
4. **1.4** and **1.5** in parallel in two worktrees (after 1.3 and 1.2); file sets are disjoint (`nodes/stateful/*` vs `nodes/specialist/*`).
5. **1.6** identity graph (after 1.4 + 1.5).
6. **1.9** non-image basis (after 1.5; parallel with 1.6).
Then 1.7 → 1.8 → 1.10 → Codex P1 review, then P2 (2.1 and 2.4 parallel).
Merge hotspots: `ContextFusion.kt` (1.2 only, then read-only), `PlanConfig`/`PlanJson`/`PlanValidator` (2.4 then 4.3, strictly in that order), T26 state machine (2.6 then 4.6), `ContextHub` (1.10 only).

## Open questions for the user (defaults in bold)
1. Granularity: identity graph as ~30 nodes with specialist nodes (fact derivation, vote, dwell each one node with every constant as a param), instead of ~70 primitives? **Yes (saves about 15 h; finer split is a later one-entry change per node).**
2. After the Codex ruling, may the published capture state differ from the full decision (D1: images never cause REST/FLIGHT throttling, even though today's fusion lets them)? **Yes; only that delta.**
3. LIVE for a phone graph that changes REST/FLIGHT inference: refuse until a P6 replay shows no extra camera-open time, and a measured worn hour? **Yes.**
4. Stop at the end of P3 (phone-only, ~46 h base) for a decision before the device/link work (P4, 19 h), since the glasses subgraph is the riskiest part? **Yes, review after P3.**
5. Run the optional LLM advisory pass (2 h, off by default) or drop it? **Drop until P6 is otherwise done.**
6. Link periods below 60 s allowed only in SHADOW/TRIAL until the link cost `k` is measured on the device (4.7)? **Yes.**
