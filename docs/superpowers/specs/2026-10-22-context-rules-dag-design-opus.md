# Context rules as DAG nodes: the whole context engine and capture policy as an editable, placeable graph (Opus proposal)

Date: 2026-10-22. Status: independent design proposal (Opus planner), **r3**: revised for three binding user rulings received mid-draft (R-A: decompose all of `ContextFusion`/`ContextPolicies` into nodes with an identity graph; R-B: every signal declares source/target device and rates, link edges cross devices, aggregates sit before links, nodes are placeable on glasses or phone; R-C: an aggregate is mandatory before any link, compile is the gate with errors and warnings, an LLM pass is at most advisory, and only a compiled graph can be published). Docs only; no code, Gradle or device touched.

Read: wave B spec (§5, §6, §9, §9.1), slice 3 r3 (§2, §3, §5, §8, §16–§18), slice 3a plan round 3 (core table, LATER, pinned interfaces), the expression-nodes options and the decision brief. Code: `pca-ctx/int` (`ContextFusion.kt`, `ContextPolicies.kt`, `Evidence.kt`, phone `context/*`: `ContextHub` 10 s tick and 60 s resend, `CarSource` "My car" addresses, `SpeedSource`; glasses `ContextGate`) and `pca-s3/int` @`508de925` (`:core:capture` plan types, `PlanBounds.FUSION_DEFAULTS`, `reservedBoundMs`, `PLAN_MAX_BYTES = 8_192`).

**Code facts relied on:**
- `ContextFusion.evaluate` = fact derivation (walking, fast = two consecutive speeds ≥ 25 km/h ≤ 45 s apart, vehicle strong/BT facts held 180 s **only while already in a vehicle state**, `btContradicted` latched per link until disconnect, flight, still, log-odds `pDriver`), then a priority `when`, then a dwell matrix (WALKING 0 s, leave-WALKING ≥ 60 s, VEHICLE 30 s / switch 60 s, FLIGHT 120 s, REST 120 s, UNKNOWN 60 s), confidence and reasons. Evidence is timed by a monotonic "arrival" clock; `refreshHeld` runs **before** each evidence is applied; a backwards jump > 60 s resets.
- `ContextPolicies.DEFAULT`: VEHICLE_* = UNCHANGED (DRIVING only `nudges = false`), REST cap 2, FLIGHT cap 1. `imageAssist = false`: intent never starts an episode, REST or FLIGHT.
- Glasses `ContextGate`: TTL from receipt, `apply`, `phone_motion` kill switch, and an **unconditional** 15 s local walking veto.
- Reserved timer attempt: fixed Kotlin, 60 s (UNKNOWN/WALKING) or 300 s (REST/VEHICLE_*/FLIGHT).
- `CarLink` carries only a kind; addresses never leave `CarSource`. 3a-core refuses hub tunables (LA2).

## 1. The user's rules, and the reading of a DAG
| Kind | User example | Sink |
|---|---|---|
| Inference | [head movement > x] → [BT device "My Honda"] → [GPS speed y] → driving | `state.candidate` |
| Inference | [head movement > x] → [watch walk mode] → [GPS updating] → walking/running | walking source |
| Policy | [if driving] → [photos 2/min] → [unless head movement > x] | `policy.row` + `policy.unless` |

**Chain = AND, fan-in = OR, a sink ends a rule.** The editor reads the user's arrow notation literally, so no text language is needed (slice 3 §18).

## 2. The engine as a graph (ruling R-A)
Ruling G1: `ContextFusion` and `ContextPolicies` become **a default identity graph** of catalogue nodes, evaluated by one deterministic interpreter. Every threshold, TTL, hold, exit minimum, prior, band, dwell, confidence formula, reason and policy row is a node or a node param. The only fixed code is the **engine floor** (§4). `ContextFusion` is kept, frozen, as the **test oracle** and as the production engine until on-device parity is shown (§8).
- Why: the user ruled it; and it is feasible because every construct in `evaluate` maps to a finite node type below (checked line by line).
- Cost if wrong: about 6 agent-h more than wrapping, plus a parity gate before the switch.

### 2.1 Node catalogue (finite, form-edited; the canvas and the phone build the AST directly)
| Group | Node types (params) | Identity-graph use |
|---|---|---|
| Source | `src.<signal>` (see §3; device, sensor, sourceHz, targetDevice, targetHz) | one per evidence kind |
| Freshness | `fresh(ttlMs)`, `latest`, `age`, `since(kind)` (connect time) | hint 90/120 s, AR 30 min, steps 90 s, watch 30 min |
| Compare/logic | `cmp(op, k)`, `in(set)`, `and`, `or`, `not`, `choose`, `clamp` | steps ≥ 60, speeds ≥ 150/350, stillFrac ≥ 0.85 |
| Aggregate | `win(max/mean/frac/count, N)`, `pair(k, maxGapMs, ttlMs)` (two consecutive ≥ k), `newest(n, maxAgeMs)`, `any(…)` over a window | `fast`, flight speeds in 10 min, the 3 newest intents |
| Time | `for(T)` debounce, `hold(T, onlyWhile?)`, `latch(set, reset)`, `hyst(on, off)`, `prev` (one-tick delay: the only way to close a loop) | vehicle hold 180 s only while `prev(committed)` is vehicle; `btContradicted` latch; airplane ≥ 120 s |
| Suspicion | `suspect(name, reason)`: a named bool exposed as a reason and to the UI | walking, fast, vehicleDet, vehicleImage, flight, still |
| Vote | `vote.logodds(priors by key, votes, clampLo/Hi)` → p; `band(hi, lo, keepPrev, tieAt)` | pDriver (AA 0.85, My car 0.75, car BT 0.5, none 0.15; band 0.4–0.6) |
| Priority | `arbiter` (ordered inputs, one per target state, sub-state chooser) | walking > flight > vehicle > rest > unknown |
| Hysteresis | `commit` (dwell matrix from × to, leave minimums, same-episode rule) | the dwell table |
| Output | `confidence` (per-state formula), `out.ctx` (validForMs, resend period, apply), `in.ctx` on the glasses (TTL, kill switch) | 180 s TTL, 60 s resend |
| Policy | `policy.row(state)` (cap, timer-photo gaps, settle, nudges), `policy.unless(cond, holdS)` | the `ContextPolicies` table |
| Link | `link.send(period, onChange, minGap, coalesce)`, `link.recv` (§3) | the hint: 60 s + on WALKING change ≥ 5 s apart (the **fast path**) |

Interpreter semantics (needed for exact identity): synchronous dataflow per device; nodes in topological order; cycles only through `prev`; one engine clock per device that is monotonic with the same "arrival" rule and the same > 60 s backwards-jump reset; on each evidence a **pre-tick** refreshes `hold` nodes at the arrival time before the source changes (today's `refreshHeld`), then the source updates; a full evaluation runs on evidence and every `targetHz` tick (phone 10 s). Floats: binary64 as today, so the identity graph can match bit for bit.

### 2.2 Identity graph and golden tests
- The identity graph (≈ 70 nodes on the phone, ≈ 8 on the glasses) is shipped as data (`identity-v1.json`, hashed) and is what "Use built-in rules" restores.
- **Goldens (merge gate):** every `ContextFusionTest` scenario, the wave B §6.3 override table, and a seeded differential fuzz (10⁴ random evidence traces × 2 h, including clock jumps and out-of-order stamps) through the frozen `ContextFusion` and the interpreter: **every `ContextDecision` field equal at every tick** (state, confidence, sinceMs, reasons, pDriver, candidate, vehicleSuspected). The glasses side: `ContextGate` goldens and the hint's `stillFrac5m` bytes. Policy: the plan resolved from the identity graph equals `PlanResolver`'s identity plan hash.
- Ruling G2: specialist nodes (`pair`, `newest`, `since`, `vote.logodds`, `band`, `commit`) exist because decomposing them further would risk drift for no user benefit; each is one catalogue entry with params, so nothing stays hard-coded. Cost if wrong: one more node type later.

## 3. Devices, rates, link edges, placement (ruling R-B)
### 3.1 Every signal node declares four things
`source = {device, sensor}`, `sourceHz`, `target = device that evaluates`, `targetHz`. The editor draws one lane per device (GLASSES, PHONE, WATCH as a source only).

| Signal | Source (device · sensor) | sourceHz | Default target · targetHz | May be placed on | Privacy class |
|---|---|---|---|---|---|
| `imu.gyroMax/gyroMean/stepHz/accVar/rotRad`, `imu.motion` | glasses · IMU → 1 Hz `SecondFeatures` | 1 | glasses · 1 | glasses; phone **only via an aggregate** | open |
| `glance.*`, `keep.*` | glasses · A3 detector | event | glasses · event | glasses | open |
| `phone.activity` | phone · Activity Transition API | event (30–90 s lag) | phone · 0.1 | phone; glasses via link | open |
| `phone.steps` | phone · step detector (30 s batch) | 1/60 | phone · 0.1 | phone; glasses via link | open |
| `watch.state` | watch · Health Services via LiveFit | changes, ≤ 1 | phone · 0.1 | phone; glasses via link | open |
| `phone.airplane` | phone · broadcast | event | phone · 0.1 | phone; glasses via link | open |
| `car.link` (kind), `bt.device(alias)` (bool) | phone · Bluetooth | event | phone · 0.1 | **phone only** | PHONE_ONLY |
| `gps.speedKmh`, `gps.fixAgeS` | phone · fused location, **on demand** | 1/30 | phone · 0.1 | **phone only** | PHONE_ONLY |
| `intent.label/conf` | phone · `IntentSampler` | ≤ 1/60 | phone | phone | INTENT (taint, §4) |
| `ctx`, `ctxConf`, `ctxAgeS` | phone · `out.ctx` | ≤ 1/10 | glasses · event | glasses | open (coarse) |

Ruling P1: **GPS and BT never cross a link in any form** (not speeds, not device booleans, not addresses); they influence the glasses only through the coarse `ctx` state. Cost if wrong: a glasses-placed rule cannot read speed; that rule is placed on the phone instead (the validator says so).

### 3.2 Link edges
A link edge is drawn when a node's target device differs from its input's device. Edges and their properties:

| Link | Carrier | Budget (per release table) | Coalescing | Latency budget |
|---|---|---|---|---|
| GLASSES → PHONE | `pc_motion_hint` (exists), extended with `aggs: List<Int>` (≤ 8 quantised slots named by the plan) | ≤ 512 B per message; **free period 60 s** (already in the A3 baseline); extra messages only at period ≥ 10 s; on-change ≥ 5 s apart | slots ride the next due message | 2 s typical, 10 s budget |
| PHONE → GLASSES | `pc_motion_ctx` (exists) and `pc_capture_plan` (3a) | ctx ≤ 1 per 10 s + 60 s keep-alive; plan once per publish, ≤ 8,192 B | state changes only | 2 s / 10 s |
| WATCH → PHONE | LiveFit `ContentResolver.call` | ≤ 1/s, ≤ 2,048 B, changes only | watch holds 30 s | ≤ 35 s |

Ruling P2: **rates come from a per-release `DeviceProfiles` table (the authority); the live app probes and reports, but a probe can only warn, never relax.** The table (in `core:model`, versioned with the APKs) holds sensor rates, link budgets and the cost coefficients; validation, compile and replay use it, so they are deterministic. The glasses ack and heartbeat report measured link stats (`linkMsgs/min`, median RTT, drops); the phone records them. The editor shows on each edge: "requested 1/30 s · budget ≥ 1/10 s ✓ · measured RTT 0.8 s · ≈ +0.0x %/min"; a requested rate above the budget is a red compile problem; measured worse than budget is an amber warning. Cost if wrong: a better link on some day is not exploited until the next table release.

### 3.3 Aggregate before the link (a compile rule); placement by moving a node
- **Cross-device rule (binding):** every path that crosses a link edge must pass a **rate-reducing step on the source side** before `link.send`: an aggregate (`win`, `pair`, `newest`, `any`), a poll (`latest` sampled by `link.send(period)`), or a coalescer (`onChange` with `minGap`). A raw source → link edge is the compile **error** `E_LINK_RAW`, unless the source's declared rate is **≤ 1 sample per minute and ≤ 64 B per sample** (the raw-forward cap).
- Ruling P3: the cap is 1/min and 64 B — it equals the free 60 s hint period, so a raw forward under it adds no message; anything faster must say how it is reduced. Sources already reduced upstream declare it (`watch.state` is held 30 s on the watch and sent on change only, so it enters as a coalesced source). Cost if wrong: a slow raw signal (for example airplane mode, if ever placed on the glasses) needs an explicit `onChange` node.
- Example: a 1 Hz glasses signal becomes `win(max, gyroMax, 60 s)` and `win(frac, gyroMax ≥ 0.6, 60 s)`, then `link.send(period 60 s)`; the editor offers "insert aggregate" as the one-click fix. Values are quantised to milli-units (expr X4), so phone replay is exact. The identity hint's walking fast path is `onChange(minGap 5 s)`, a coalescer.
- **Moving a node** changes its `target`. "head movement > x" placed on the glasses = a 1 Hz `cmp` beside the policy (sub-second reaction, no link). Placed on the phone = `cmp` on the received 60 s peak (≤ ~70 s lag, usable for inference). The validator checks a placement: (1) the sensor exists on the source device; (2) a link path exists and every crossing is preceded by an aggregate whose output rate fits the budget; (3) end-to-end latency ≤ the sink's budget (`policy.unless` 2 s ⇒ glasses only; `state.candidate` 120 s); (4) privacy class (PHONE_ONLY never crosses); (5) the cost estimate (§5); (6) invariants (§4), including that the walking-veto floor still runs locally on the glasses whatever is moved.

### 3.4 What compiles where, and the 8 KB plan
- **Partition by target device.** GLASSES-target nodes (policy rows, `policy.unless`, guards, glasses aggregates, `link.send` slots, `in.ctx` params) compile into the `CapturePlan` as a new `graph` block: a flat node array in prefix form (`["win","max",["$","imu.gyroMax"],60000]`), ≈ 20–30 B per node. PHONE-target nodes compile into the phone's `ContextGraph` (stored and run on the phone only, ≤ 32 KB). The plan carries only `meta.phoneGraphHash` (12 hex).
- **Budget:** glasses graph ≤ 96 nodes, ≤ 8 agg slots, ≤ 4 windows; the identity glasses part is ≈ 8 nodes (≈ 250 B). A full user glasses graph is ≈ 2.5 KB; with today's blocks the plan stays well under 8,192 B. The compiler reports `planBytes` and `graphBytes` and fails over the cap (never trims). Glasses advertise `graph1`; older glasses reject `unsupported:graph1`.

### 3.5 Compile is the gate (errors block, warnings explain)
`GraphCompiler.compile(graph) → CompileResult(ok, diagnostics[], plan?, phoneGraph?, compileKey)`. Each diagnostic has a code, severity, a plain-language message, and an anchor (node id, edge id or "graph"); the canvas and the phone view highlight the anchor and offer a fix when one exists.

| Code | Sev | Check | Example message |
|---|---|---|---|
| `E_PLACEMENT` | error | sensor absent on the source device, or a node type not runnable on its target | "Speed exists only on the phone; this node is on the glasses" |
| `E_LINK_RAW` | error | §3.3 rule | "Head speed (1/s) goes straight to the phone link. Add an aggregate (e.g. peak over 60 s)" |
| `E_LINK_BUDGET` | error | rate or bytes per message over the table budget, or > 8 agg slots | "This link would send every 5 s; the budget is every 10 s" |
| `E_RATE` | error | requested targetHz above the achievable rate on that path (source rate after aggregation, link period) | "Asks for a value every 10 s, but it arrives once a minute" |
| `E_LATENCY` | error | path latency over the sink's budget | "'Unless head moves' must react within 2 s; via the phone it takes up to 70 s" |
| `E_PLAN_SIZE` | error | glasses plan > 8,192 B or graph limits (96 nodes, 4 windows) | "Glasses part is 9.1 KB; the limit is 8 KB" |
| `E_INVARIANT` | error | I1–I8 structural checks, including intent taint and unclamped values | "Photo intent may only choose driver vs passenger; here it enables Rest" |
| `E_PRIVACY` | error | PHONE_ONLY value crosses a link or enters a plan or trace | "GPS speed cannot be sent to the glasses" |
| `E_TYPE`, `E_CYCLE` | error | port types; a loop not through `prev` | — |
| `E_TIE` | error | two enabled rules in one arbiter block with no order (only possible from a merge of two edits) | "Two rules for Driving have the same priority" |
| `W_BATTERY` | warning | estimate (link `k`, node CPU, behaviour via the 3a battery line) over the current baseline × 1.10, or phone GPS > 60 min/day in scenarios | "≈ +0.03 %/min (≈ 12 min less battery); estimate" |
| `W_DEAD` | warning | a node not reaching any sink, or a rule never true in any built-in scenario or the last replay | "This rule never fired in 7 days of traces" |
| `W_SHADOWED` | warning | a rule always pre-empted by an earlier one in its block, or redundant with a built-in | "Built-in 'watch walking' already gives Walking" |
| `W_FLAP` | warning | a rule's path to `commit` has no `for`/`hold`/`hyst` and the target's dwell is at its minimum, or > 6 transitions/h in a scenario run | "This rule may switch on and off often; add 'for 30 s'" |
| `W_GPS_DEMAND` | warning | a `gps.*` term with no lazy-demand path | "Speed is only measured while a vehicle is suspected" |
| `W_WALK_GUARD` | warning | a guard can drop glances in WALKING/UNKNOWN (expr X5) | "May skip glances while walking; the 60 s timer still runs" |

Ruling C1: **what "compiled" binds to.** `compileKey = SHA-256(graph canonical hash, NodeTypes registry version, sigSchema, DeviceProfiles table version, identity graph version, compiler version, glasses caps set)`. A graph is publishable only while its stored `CompileResult` is ok **for the current `compileKey`**; any edit, app update (table or registry change) or a glasses caps change invalidates it, and the editor recompiles automatically (deterministic, < 1 s). Cost if wrong: none; recompiling is cheap.

Ruling C2: **save always, publish only compiled.** Drafts autosave as revisions whatever their diagnostics (no lost work). SHADOW, TRIAL and LIVE, on the phone and on the glasses, are offered only for a revision whose current-key result is ok; warnings are shown in the publish dialog and acknowledged once per `compileKey`. Cost if wrong: none.

Ruling C3: **the deterministic compiler alone decides publishability and proves invariants; an optional LLM review may only add advisory notes.** Agreed with the coordinator's view, evaluated:
- **LLM as a gate (rejected):** nondeterministic (same graph, different verdict), unreplayable, cannot prove anything, and it would make publishing depend on a network model.
- **No LLM (acceptable default):** the deterministic lints above (`W_FLAP` via scenario runs, `W_SHADOWED`, `W_DEAD`) already catch the mechanical problems.
- **LLM advisory (recommended as opt-in, off by default):** worth it for what code cannot judge: "the name says *driving* but nothing here reads a car signal", "this looks like two rules that contradict your intent", plain-language explanations. Rules: input is the structural graph and the node schema only, with user labels replaced by placeholders (`device 1`, `rule 3`) and no GPS, BT, addresses, photos, traces or transcripts; output is a list of `{anchor, note}` shown under "Suggestions (AI)", never mixed with errors; it can never clear an error or block a publish; results are cached by `compileKey`; it runs through the configured AI endpoint as a charged call (CallClass OTHER) or the laptop helper in DEV mode; a malformed reply is dropped silently (counted).
- Cost if wrong: about 2.5 agent-h for something the user may not use; it is isolated and removable.

## 4. Invariants: the engine floor and the validator proof
The floor is Kotlin that runs **around** the interpreter; the graph cannot address it. The validator adds structural proofs; a property fuzz (random valid graphs × random traces) asserts all of them at every tick.

| # | Invariant | Floor (fixed code) | Validator (structural) |
|---|---|---|---|
| I1 | Walking veto | phone: `floorWalking` from the built-in walking sources with **one-sided params** (the floor uses the safer of graph and default: TTL max, steps threshold min, `fastKmh` max(graph, 25)) forces WALKING ahead of the arbiter; glasses: `ContextGate`'s 15 s local veto runs after `in.ctx` on the glasses always | user walking sources only OR into walking; no node may target "not walking"; a graph without the veto-floor node still gets it |
| I2 | Reserved timer attempt 60/300 s | `reservedBoundMs` + timer path, before guards | no node can reach the timer |
| I3 | Hard caps (cap 1–4, ceiling 2× and ≤ 8/min, gaps 5–60 s, settle ≥ 1 s, SETTLE wake, no pause) | `PlanBounds` clamp in the skeleton | literals refused out of bounds; every value path ends in a `clamp` to the field bounds |
| I4 | Intent never throttles capture | when `INTENT_SUBSTATE` is in the reasons, the glasses use the **field-wise least restrictive** vehicle row and drop a glance only if guards drop it under both sub-states | **taint analysis**: any value downstream of `src.intent` is INTENT-tainted; tainted values may feed only `vote.logodds` → `band` → the vehicle sub-state, never an arbiter enable, `commit`, `policy.row` or a guard |
| I5 | WALKING and UNKNOWN rows full rate | rows not in any plan (S32) | `policy.unless` relaxes only **towards** the UNKNOWN row |
| I6 | Heat, battery, phone power, pause, wear, wipe | applied after the plan, stricter wins | none needed |
| I7 | Privacy | `SpeedSource`/`CarSource` emit only kmh / kind / alias booleans | PHONE_ONLY taint may not cross a link, enter a plan or a trace in raw form |
| I8 | DRIVING suppresses nudges | locked in the row | `nudges` not editable on DRIVING |

Consequence: every editable row is at most the UNKNOWN row and relax only returns to it, so **no graph can raise glasses capture above today's UNKNOWN behaviour**; the glasses-side battery risk of a graph is only its link messages and node CPU (§5).

## 5. Evaluation and link cost
- **Phone:** identity graph ≈ 70 nodes, evaluated on evidence and every 10 s (≈ 1 evaluation/s worst case with a pre-tick): < 0.2 ms/s on the phone; user graph ≤ 24 extra rules × 24 nodes, same order. Post-order bytecode, preallocated stacks, no allocation (expr X6).
- **Glasses:** nodes run only on the existing 1 Hz `SecondFeatures` row and the policy tick (≤ 2/s); no per-sample (50 Hz) node exists (targetHz ≤ 1 Hz on the glasses in v1). 96 nodes × 3 evaluations/s ≈ 300 ops/s ≈ 10 µs/s: below measurement noise against 0.49–0.51 %/min. Heartbeat `graphUs`.
- **Link:** aggregates riding the existing 60 s hint cost nothing new (that message is in the A3 baseline). Each **extra** glasses→phone message/min costs `k` %/min, `k` unknown today: task F7 measures it (forced 1 message/10 s vs baseline, 2 × 30 min worn). Until measured, the table sets `k = 0.005 %/min per extra message/min` (estimate, labelled), periods < 60 s are SHADOW/TRIAL only, and LIVE still needs the S44 measured run (≤ 0.42 %/min).
- **Phone GPS** is the real cost of user rules: `gps.*` stays on demand. A rule with a `gps.*` term widens demand only while all its **non-GPS** conditions hold (lazy sensor); a 60 min/day user-rule GPS budget, `locOnS` in the report.

## 6. Policy on the glasses
`policy.row` = the existing `PlanRow` (3a-core). "[if driving] → 2/min" sets `VEHICLE_DRIVING.capPerMin = 2` (the inspector warns that it lowers capture, against today's default). `policy.unless(cond, holdS 5–60)`: while `cond` held within `holdS`, the row's capture fields fall back to UNKNOWN. The identity graph places `cond = cmp(imu.gyroMax ≥ x)` on the glasses (one `lastAboveAtMs`, O(1), no ring, no timer).

## 7. Editors
- **Canvas:** device lanes (GLASSES, PHONE, WATCH); signal nodes with source/target/rate badges; link edges as dashed BT connectors with budget badges; drag a node to another lane to re-place it (the validator re-runs; infeasible = red with the reason); an **arbiter** inspector with ordered target blocks (walking locked first), a **commit** inspector as a dwell matrix; floor items shown as locked ghost nodes.
- **Phone view, generated from the same node schema** (`NodeTypes` v2 `ParamSchema`): Settings → Context → "Rules". Three tabs: **Rules** (each chain into a `state.candidate` or `policy.*` rendered as a sentence: "When head peak > 0.8 rad/s (glasses, 60 s) and on *My Honda* and speed ≥ 15 km/h for 30 s → Driving"), **Timing** (the commit matrix and holds as number fields), **Built-ins** (identity rules with toggles and their params). Forms are generated per node type from `ParamSchema` (min, max, step, one-sided bounds shown as "safer only"); a chain of up to 6 nodes with AND/NOT is editable on the phone, anything else (OR fan-in, windows, `vote`) is shown read-only "edit on the canvas". Both editors write one `ContextGraph` document with a revision (compare-and-set).

## 8. Simulator and rollout
- **Replay** needs LA4 (hub evidence recorder, scrubbed per I7: kmh quantised, alias booleans, no coordinates) and L2 (fusion recompute): evidence → phone graph → `ctx` timeline → `CaptureSimulator` with the IMU trace and the glasses graph. Report vs active graph: minutes per state, transitions, captures/min, vetoes, GPS-on minutes, link messages/min and %/min estimate, "node never fired", "redundant with built-in". Before traces exist: **synthetic scenarios** on the phone (drive in My Honda, taxi, walk with watch, flight).
- **Engine switch:** the interpreter with the identity graph runs in **on-device SHADOW** beside `ContextFusion` for ≥ 48 h of use; the switch happens only at 100 % tick agreement (counts on the Diagnostics card). `ContextFusion` then stays as the fallback for one release.
- **User graphs:** DRAFT → SHADOW (twin engine on the phone; counts only; glasses part per 3a SHADOW) → TRIAL ≤ 60 min (auto-revert at the end, or on > 3 glasses vetoes in 10 min, or a reducing state while the glasses hint says WALKING) → LIVE: phone-only changes after ≥ 4 h SHADOW with zero trips and a viewed report; glasses parts per S11/S44. "Use built-in rules" restores the identity graph in one tap.

## 9. Privacy
BT devices: phone-only `bt_devices {aliasId (random), label (user-typed), address}` in `context_sources` (extends "My car"), excluded from export, cleared by Delete all; graphs, traces, plans and logs hold alias ids and labels only. GPS: only quantised kmh and fix age leave `SpeedSource`; never coordinates; geofences out of v1. Glasses receive only `ctx`, reason names (`RULE:<nodeId>` included), the glasses graph and `phoneGraphHash`. Graph export replaces labels by "device 1/2" unless opted in.

## 10. Staging, tasks, estimates (Sonnet codes, Opus specs and reviews)
**Minimal = "3a-core + 1": the phone engine as a graph, editable on the phone, no glasses change (≈ 30 agent-h, ≈ 15 h wall-clock on 2 streams).**
| # | Task | agent-h |
|---|---|---|
| M1 | Node catalogue entries, `ParamSchema` with one-sided bounds, `ContextGraph` schema and canonical JSON | 2.5 |
| M2 | Interpreter in `core:model` (`context.graph`): topo order, `prev`, pre-tick, arrival clock, all stateful nodes | 6 |
| M3 | Identity graph as data + differential goldens and fuzz vs frozen `ContextFusion` (Opus review) | 5 |
| M4 | Engine floor (I1 phone, I7); `GraphCompiler` with the §3.5 phone-side diagnostics (types, cycles via `prev` only, intent and privacy taint, limits, ties, dead/shadowed, flap lint via scenarios), anchors and messages; property fuzz | 5.5 |
| M5 | `ContextHub`: engine behind a flag, on-device SHADOW parity counter | 2 |
| M6 | Graph store with autosaved revisions, `compileKey` binding and the publish lock (C1, C2), SHADOW twin, TRIAL monitor, "Use built-in rules" | 4 |
| M7 | Phone "Rules" view generated from `ParamSchema`, scenario runner | 3 |
| M8 | BT aliases + `Evidence.BtDevice`, lazy GPS demand + budget | 2 |

**Full (≈ 30.5 agent-h more, after 3a-core M1):** F1 `DeviceProfiles` table, link-edge model, the cross-device diagnostics (`E_PLACEMENT`, `E_LINK_RAW`, `E_LINK_BUDGET`, `E_RATE`, `E_LATENCY`, `E_PLAN_SIZE`, `W_BATTERY`) with one-click "insert aggregate", and the cost estimator (5); F2 glasses graph runtime in the plan (`graph` block, `graph1` cap, 1 Hz and policy ticks, `in.ctx` with the floor, `policy.unless`) (5); F3 link: `aggs` slots on the hint, periods and on-change, phone `link.recv`, probe stats in ack/heartbeat (3); F4 policy rows as nodes + I4 runtime (2.5); F5 canvas lanes, link edges, badges, re-placement, arbiter and commit inspectors (5); F6 LA4 scrubbed evidence recorder + L2 recompute replay + report (4.5); F7 device checks: 48 h identity parity, link-cost A/B for `k`, a max glasses graph SHADOW hour (≤ +0.02 %/min) (3); F8 optional LLM advisory pass (C3: placeholder-scrubbed structural input, `{anchor, note}` output, cache by `compileKey`, off by default) (2.5).
**Total ≈ 60.5 agent-h** (58 without F8). Windows on the phone are in M2; glasses windows and ctx guards reuse the 3c evaluator, so F2 and the 3c expr slice share code (no double build).

## 11. Risks
1. **Identity drift** (arrival clock, pre-tick hold, same-episode dwell). Mitigation: differential fuzz and 48 h on-device parity before the switch; `ContextFusion` stays as the fallback.
2. **Over-throttling user graphs.** Bounded by I1–I5 and the TRIAL monitor; visible in SHADOW first.
3. **Unknown link cost `k`.** Mitigation: periods < 60 s limited to SHADOW/TRIAL until F7 measures it.
4. **Phone GPS drain** from broad demand. Mitigation: lazy demand, daily budget, `locOnS` per rule.
5. **Two editors** on one document. Mitigation: revisioned compare-and-set; the phone edits only the shapes it renders.
6. **Stale compile results** after an app or glasses update. Mitigation: `compileKey` includes the table, registry and caps versions; publish re-checks the key server-side.
7. **Scope:** 60 h is larger than the 15 h "rules over facts" option. Mitigation: Minimal alone delivers the user's three examples except the sub-second `unless` (F2).

## 12. Open questions for the user (defaults in bold)
1. Block order WALKING > FLIGHT > VEHICLE > REST fixed in the arbiter, order editable inside a block? **Yes, fixed.**
2. May a user-authored row lower capture in DRIVING/PASSENGER (today's default: vehicles never throttle)? **Yes, user-authored only; the identity default is unchanged.**
3. Link rates from a per-release table (the live probe only warns), and link periods below 60 s only in SHADOW/TRIAL until the link cost is measured? **Yes to both.**
4. GPS and BT never cross the link, even as speed numbers or booleans? **Yes.**
5. Raw-forward cap for a signal crossing devices without an aggregate: ≤ 1 sample/min and ≤ 64 B? **Yes.**
6. LLM review: advisory only, off by default, structural graph with placeholders, never a gate? **Yes.**
7. Switch production to the interpreter only after 48 h of 100 % on-device parity (one WALKING state in v1, running as a reason)? **Yes.**
8. Build Minimal (phone-side graph, compile gate and phone view, ≈ 30 agent-h) right after 3a-core, Full after M1? **Yes.**
