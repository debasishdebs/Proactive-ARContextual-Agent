# Experiment canvas, slice 3: the canvas as the end-to-end configurator

**Status:** draft r3, design only. Written on 2026-10-21 by the Opus planner (r2: 25 Codex findings folded). **Re-cut on 2026-10-22 into slices 3a / 3b / 3c under the user's rulings (next section).**
- Each choice the user would normally be asked about carries a `Ruling Sn:` line: what — why — cost if wrong.
- The S numbers belong to slice 3. Slice 2 rulings are cited as "slice 2 S6", and wave B sections as "wave B §n".
- Every ruling is listed again in §15, "Open for the user".

**Builds on:**
- slice 2 (`2026-10-21-canvas-slice2-design.md`, paused after Task 2 on `canvas/integration` in `/Users/debasishkanhar/Desktop/rokid/pca-canvas/int`). Its editor is React Flow, per `2026-10-21-canvas-editor-oss-evaluation.md`.
- the MVP (`2026-10-21-experiment-canvas-design.md`);
- wave B (`2026-10-21-wave-b-context-hub-design.md`);
- battery A3 (`2026-10-21-battery-wave-a3-glance-model.md`, code at `pca-bat/int` @6bb40b6);
- the handoff (`2026-10-21-handoff.md`).

**Amended 2026-10-21 (open source, aligned with slice 2 §4.1 and §4.6, Rulings S21 and S22):** §13 (S49) is reconciled with slice 2's vendoring rules and pins: flat `vendor-*` file names and `vendor-lock.txt` (the server's static path rule has no folders), **Chart.js 4.5.1** pinned by hash, **CodeMirror 5.65.21** for the `py.fn` source editor, `paramform.js` (pure) for the param forms, and a pure `patchview.js` instead of diff2html for patch diffs (diff2html emits HTML strings that need `innerHTML`, which the canvas bans, and these patches are model-written). §11 GraalJS and the T20 row of §14 are updated to match.

## Re-cut (2026-10-22)
The user ruled on these seven points on 2026-10-22, and they are binding. Each one becomes a ruling (S50–S56). Where this section and older text disagree, this section wins, and the older text has been amended in place.

| # | User ruling | Ruling | Where |
|---|---|---|---|
| 1 | The capture-half graph **wraps** the existing Kotlin (`GlanceDetector`, `MotionModel`, the `KeyframeGate` caps, `ContextGate`/`ContextFusion`). Nodes are thin wrappers or param presets. Code moves only where replay needs it, into the shared pure module. Nothing is rewritten | S50 | §5.1 |
| 2 | The `expr` nodes (a tiny language) are **deferred**. The user will brainstorm the language first. The section is kept and marked "3c, brainstorm first" | S51 | §4.3 |
| 3 | The laptop helper uses containers (Podman or Docker; installing one is allowed). There is an explicit **DEV mode** (canvas on the phone, helper on the laptop, Python nodes, promote to Kotlin) and an explicit **DEPLOY mode** (frozen plan, no helper, nothing extra running) | S52 | §4.4.1 |
| 4 | Replay and tuning are **lean first**: the recorded IMU and hub traces replay through the same shared code; grid or random sweeps; captures/min, recall of marked moments and a battery estimate from the measured slope; a Pareto chart (vendored Chart.js); holdout by day. Counterfactual ranges, the fitted drain model and heavy trace-transfer rigor go to a "later" list | S53 | §6, Later list below |
| 5 | **Mark-now** is a tap (glasses or phone) **and** a voice "mark that" or intent phrase. The voice part copies Live Fit's command code (not its permissions or connection). The user reads it as naming an intent label | S54 | §6.4 |
| 6 | **Natural-language tools** come right after 3a, as the first part of 3b. Once the node contract exists, they should take an evening to build and test | S55 | §4.5 |
| 7 | **Detectors and recognition rules come last, in 3c.** They start after the user has used the app for about a week, and that week's data is used for tuning. No work on them before then | S56 | §8b |

**What 3a keeps unchanged** (these invariants and Codex rulings apply to the 3a code paths):
- the non-removable invariants (§3: S3, S23, S31, S32);
- the publish gate (§7 gates 1–5, S11: SHADOW, a bounded TRIAL, or verified LIVE at ≤ 0.42 %/min);
- the group states and rollback modes (S25, S26);
- commands with generations and acks (S24);
- the frozen, resolved plan (S27) and the plan's **8 KB** cap (§5.2, user ruling §17.2);
- schema-1 hashes (S41), the deletion fences for every 3a table (S38), and the S44 verification rule.

### Slice 3a: capture-half graph, lean replay and tuning, mark-now, publish
Goal: the user edits the capture half of the §1 flow on the canvas (head movement → threshold → hub motion → rate by activity → capture), replays a recorded day against it, sweeps the params, reads the Pareto chart, and publishes in SHADOW or TRIAL. The ASYNC half stays slice 2's interpreter.

| # | Task (old T#) | Depends on | Stream | agent-h |
|---|---|---|---|---|
| A1 | `:core:capture`: a **move-only** extraction of the pure decision code that replay needs (`GlanceDetector`/`GlanceParams`, `MotionModel`/`HeuristicMotionModel`, the novelty decision, the `KeyframeGate` cap and dedupe decision), with A3's tests moved alongside; `ContextFusion` stays in `core:model` (S50) (T1) | A3 merged | A | 2 |
| A2 | NodeTypes v2 (categories, domains, kinds, `ParamSchema` with sweep hints; the `EXPR` kind is registered but `liveable = false` and hidden until 3c), schema-1 resolution (S41), rooted domains and the bindings validator (S34) (T2) | slice 2 T3 | B | 4 |
| A3 | `CapturePlan` with no `exprs` or `tables` yet, `PlanBounds`, `PlanValidator`, the invariants (S3, S23, S31, S32), resolution (S27), the 8 KB cap (T4; was 4 KB before §17.2) | A1, wave B (merged parts) | A | 3 |
| A4 | `PlanCompiler`: wrapper param blocks, threshold folds into `glance.*` (anything else is a problem, "needs an expression node (3c)"), the policy table, `phone.fusion`; the capture-half §1 golden (T5) | A2, A3 | B | 3 |
| A5 | Glasses: plan store, gen/ack/caps, apply **through the existing classes' params**, reserved timer (S31), SHADOW/TRIAL, heartbeat, auto-revert (with no `exprErr` rule yet) (T6) | A3 | C | 5 |
| A6 | Protocol `pc_capture_plan` / `_ack` / `pc_mark`, phone `Hub` send and reconcile (T7) | A3 | C | 3 |
| A7 | Phone RT apply: `FusionParams` from the plan and the `ctx` binding (T8, without the S36 lease) | A3, wave B (merged parts) | D | 2 |
| A8 | Traces, lean (S53): IMU recorder with candidate metadata, hub recorder, **manifest-lite** (chunk count, SHA-256 per chunk and per trace, clock offsets at start and end, the start snapshot, wipeGen, COMPLETE/PARTIAL), transfer while charging with a whole-trace retry, `adb pull` fallback, the "Collect for tuning" session (S47), **migration A** (T9) | A1, A6, Room v5 and v6 merged | D | 5 |
| A9 | Mark-now (S45, S54): glasses gesture, phone notification action, home button and tile, `pc_mark`, the **voice "mark that" and intent phrase** (ported Live Fit command layer), retroactive marks on the timeline | A6, A8 | D | 4 |
| A10 | `CaptureSimulator`, lean (S53): exact decision replay (parity), sweep replay with point-estimate keeps and the recorded context states, the fixed two-point battery line, the replay API (T10) | A1, A3, A8 | A | 5 |
| A11 | Sweeps (grid or seeded random), the objectives (captures/min, moment recall, estimated %/min and hours), holdout by day (S46), the Pareto API, the `battery-run.sh` import (S44) (T13) | A10 | A | 4 |
| **M1** | **Integration milestone:** the identity plan end to end (phone → glasses → ack → heartbeat) and replay parity on one recorded trace | A4–A7, A10 | — | 2 (plus the user wearing the glasses) |
| A12 | Publish groups (a `capture_plan` component plus slice 2's ASYNC component), the S25 state machine, the S26 actions, the S11 gates including TRIAL, the Settings and Diagnostics rows (T12) | M1, slice 2 T6 | B | 6 |
| A13 | Deletion graph and fences (S38) over the 3a tables, trace files and marks (T14, part) | A8, A9, A12 | D | 3 |
| A14 | Canvas UI on slice 2's React Flow: lanes, bindings, param forms, replay view, sweep and Pareto (Chart.js vendored per §13), mark timeline, publish and the rollback actions (T20, part) | A4, A10, A11, A12, slice 2 T11a–e | B | 6 |
| A15 | Device checklist for 3a (§11, S44): identity parity, SHADOW counts, TRIAL auto-end, over-ceiling auto-revert and each S26 action, the mark tap and the voice mark, the collection overhead A/B (S47), one verified interval against 0.42 %/min (T21, part) | all of 3a | — | 3 (plus about 1 h worn per verification) |

**3a total: about 60 agent-h.** The critical path is A1 → A3 → A6 → A8 → A10 → M1 → A12 → A14 → A15, about 35 agent-h. That is **about 40 h of wall-clock on 4 streams (A–D)**, with about 15 % added for the 2 concurrent Gradle slots. User wear time and waits for merges are not included.

### Slice 3b: helper, NL tools first, then Python and promotion
| # | Task (old T#) | Depends on | Stream | agent-h |
|---|---|---|---|---|
| B1 | Helper core: host launcher, the DEV/DEPLOY modes (S52), pairing (S39), the container runner (S22), leases, retention, `pca-helper doctor` (T15, part). **It may start during 3a** in its own stream, because it needs only A2 and slice 2's TLS | A2, slice 2 TLS | F | 6 |
| B2 | **NL tools (S48, S55):** the `tool.draft` node with stand-ins in experiment runs, the contract form, the build job (CLI in the generation container on `canvas-tool/<id>`), the path and licence allowlists, the injection scan, the example and schema check by the helper's oracle, the debug APK from the verification container, the diff shown by `patchview.js` (T17) | B1, A2, A14 | F | 6 |
| B3 | Interpreter shape v2 and the EffectBuffer (S35), with `const.table`, `embed.text` and `tool.call` executors (T11) | A2, slice 2 T5 | E | 5 |
| B4 | The intent lease (S36) and `event.hub_intent` → `signal.in intent` (T8, rest) | B3, **wave B Task 9 merged** (the `IntentSampler`) | E | 2 |
| B5 | `py.fn` (async and RT lockstep), the CodeMirror 5 editor vendored per §13 (T15, rest) | B1 | F | 5 |
| B6 | Promotion to Kotlin: the bundle, the CLI path, the export fallback and the oracle (S40). The path that translates to `expr` waits for 3c (T16) | B5 | F | 6 |
| B7 | UI for 3b: helper status and Disconnect, the DEV/DEPLOY indicator, the promotion review (T20, rest) | B2, B6 | B | 3 |
| B8 | Deletion fences for helper jobs and laptop copies (S38, S39); helper `pytest` suite; the 3b laptop and device checklist (T14 rest, T21 part) | B2–B7 | — | 3 |

**3b total: about 36 agent-h.** The critical path is B1 → B5 → B6 → B7 → B8, about 23 agent-h. That is **about 25 h of wall-clock on 3 streams (E, F, B)**, or **about 18 h** if B1 already ran during 3a. **NL tools (B2) are then the evening right after 3a: about 6 h of wall-clock**, which needs B1 already merged. Without B1 in place it is closer to 12 h.

### Slice 3c: after about a week of use (expression language, detectors, week-data tuning)
Starts only after 3a has been installed and used for about 7 days with "Collect for tuning" on (S56). Nothing here is built before then.

| # | Task (old T#) | Depends on | Stream | agent-h |
|---|---|---|---|---|
| C0 | **Brainstorm the expression language with the user**, then an Opus spec revision of §4.3 and a Codex review | the user's session | — | 2 (plus the user's time) |
| C1 | `PlanEvaluator`: the AST, windows, limits, error rules, determinism goldens (S33, S5) (T3) | C0, A1 | A | 5 |
| C2 | `expr` in the compiler and the plan (`exprs`, `tables`), glasses apply, the `expr`/`windows` caps, the `exprErr` auto-revert | C1, A4, A5 | A | 4 |
| C3 | `expr` editor and round trip in the UI; the promotion path that translates to `expr` (S6a b) | C2, B6 | B | 4 |
| C4 | Detection tier (a): seeded prompt, `DetectionSink`, `ExtractedBatch.detections`, **migration B** `frame_detection` (S37) (T18) | B3, A8 merged | E | 5 |
| C5 | `vision.detect`: licence and size probe, catalog entry, `OnnxDetector` (S19) (T19) | C4 | E | 6 |
| C6 | Tuning on the week's data: sweeps with days 1–5 for tuning and days 6–7 for validation (S46), a report, and new param presets | A11, 7 days of traces | A | 3 |
| C7 | Device checklist for 3c (T21, rest) | C2–C6 | — | 3 |
| (opt.) | Items from the Later list, only if the week's results call for them | — | — | about 9 |

**3c total: about 32 agent-h** (plus about 9 if Later items are taken). The critical path is C0 → C1 → C2 → C3 → C7, about 18 agent-h. That is **about 20 h of wall-clock on 3 streams**, starting no earlier than about 7 days after the 3a install. Recognition (S20) stays unbuilt; 3c records its rules only.

**Grand total: about 128 agent-h** (r2: 123). Removing the dropped rigor saves about 10. The additions are the voice mark (+2), the DEV/DEPLOY modes (+1), and a separate checklist and integration step per slice (+6). The optional Later items, about 9 more, are not counted.

### Dependencies on other branches
- **Room order** (slice 2 S26): v5 = wave B Task 8 merges on its own → v6 = slice 2 → **3a's migration A takes the next free version (v7 expected)** → 3c's migration B takes the next one (v8 expected). A8 can be built in a worktree before then, but it cannot merge until wave B Task 8 (v5) and slice 2's v6 are merged. Never edit an existing migration (S43).
- **Slice 2:** T3 (node registry) before A2; T5 (interpreter) before B3; T6 (publish) before A12; the React Flow tasks T11a–T11e before A14; TLS before B1.
- **Wave B:** the merged hub core, `ContextGate` and fusion (Tasks 1, 2, 6, 10, 11, 7a) are enough for A3 and A7. **Wave B Task 9 (the `IntentSampler`) is needed only by B4.** If it is late, B4 moves to the end of 3b, and nothing in 3a waits for it.
- **A3 battery code** merged into `int/2026-10-21` before A1.

### Later list (dropped from the first version)
| L# | What is deferred | From Codex finding | Ruling it trims | Why deferring it is safe |
|---|---|---|---|---|
| L1 | Counterfactual keep **ranges** (keeps_min/max), the coverage bounds, and SHADOW's would-keep rules beyond counts | #9 (exact vs visual replay) | S29 | 3a reports a point estimate and one "unobserved opens %" warning. The estimate only ranks the Pareto chart and decides TRIAL entry. A TRIAL is bounded to 60 min and auto-reverts. **Unrestricted LIVE still needs a measured S44 run at ≤ 0.42 %/min**, so a wrong estimate can never make a plan permanent |
| L2 | **Fusion recomputed** in replay, and the "intent unobservable" shares | #10 (fusion in replay) | S30 | 3a replays the recorded committed context states, and fusion params are **not sweepable** in 3a. A plan whose fusion params differ from the trace's gets the fixed warning "fusion changes are not replayed" and can only go SHADOW or TRIAL |
| L3 | The **fitted drain model**: refits on verified intervals, the 3-slope upgrade, the condition-number rank check, leave-one-out residuals, the extrapolation flags | #8 (drain model rank) | S28 | 3a uses a fixed two-point line (A3: 0.49 %/min at 2.4 s/min of camera-open time; A2: 0.6 at 7 s/min), labelled "estimate". It gates only TRIAL entry; LIVE needs a measurement (S11, S44) |
| L4 | **Heavy trace transfer**: per-seq acks and resume, clock-mapping samples every 60 s with their uncertainty | #22 (trace manifest) | S42 | 3a keeps a simple per-chunk and per-trace SHA-256 plus a completeness check (all chunks are present and match, otherwise the trace is PARTIAL). A PARTIAL trace is never publish evidence. The only cost is re-sending a whole trace. wipeGen and tombstones stay, because they are privacy fences, not rigor |

Codex findings that are **not** deferred but move with their feature:
- #13 (cost ceiling, S33) and #15's `expr` part move with the expression language to 3c. No `expr` runs before then.
- #15 (interpreter shape) and #16 (intent ownership) move to 3b (B3, B4).
- #1, #19 and #20 (helper isolation, pairing, oracle) move to 3b.
- #17 (detection data) and #25 (biometric flow) move to 3c.
- Every other finding applies to 3a as written.

## 0. Changes in round 2
1. **Codex review** (`.superpowers/codex/slice3-spec-review.md`): all 25 findings are accepted. None is rejected. Each one is ruled below:

   | # | Finding | Where it is ruled |
   |---|---|---|
   | 1 | Helper isolation | S22 |
   | 2 | Capture now exemptions | S23 |
   | 3 | Ack identity | S24 |
   | 4 | Cross-domain activation | S25 |
   | 5 | Rollback actions | S26 |
   | 6 | Frozen vs settings | S27 |
   | 7 | 4 h gate | S11 (rewritten) |
   | 8 | Drain model rank | S28 |
   | 9 | Exact vs visual replay | S29 |
   | 10 | Fusion in replay | S30 |
   | 11 | Liveness | S31 |
   | 12 | Walking guarantee | S32 |
   | 13 | Cost ceiling | S33 |
   | 14 | Rooted domains | S34 |
   | 15 | Interpreter shape and effects | S35 |
   | 16 | Intent ownership | S36 (replaces S14's stand-down) |
   | 17 | Detection data | S37 |
   | 18 | Deletion graph | S38 |
   | 19 | Helper pairing | S39 |
   | 20 | Oracle | S40 |
   | 21 | Schema-1 hashes | S41 |
   | 22 | Trace manifest | S42 |
   | 23 | Dependencies and migrations | S43 (S15 rewritten) |
   | 24 | Verification durations | S44 |
   | 25 | Biometric flow | S20 (extended) |

2. **Mark-now moments**, the holdout split and the recording overhead budget: §6.4 (S45–S47).
3. **Tool from natural language**: §4.5 (S48).
4. **Open-source UI:**
   - the editor is slice 2 §4's React Flow (not redefined here);
   - Chart.js for the Pareto chart;
   - a pure `patchview.js` for patch diffs (amended: diff2html needs `innerHTML`);
   - CodeMirror 5 for the `py.fn` source (amended);
   - no forms library.

   See §13 (S49).
5. **Task list re-estimated in agent-hours**, with the missing dependencies, an integration milestone, and separate migrations: §14.
6. Earlier text that these items changed (S3, S9, S11, S12, S14, S15, §8, §10, §11) was rewritten in place.

## 1. Goal (user direction, binding)
The user designs the whole flow on the canvas, for example:

`[Head movement detected] → [above threshold?] → [motion from context hub?] → [capture frequency by activity] → [capture image] → [classify intent: sitting / driving / passenger / aeroplane / working / talking …] → [intent-specific summarise/memorise prompt]`

- **Both halves are editable as a DAG:** the front half (the capture policy) and the back half (the photo pipeline).
- The settings screens stay as the defaults and the fallback. The canvas is for experiments: the user experiments, then publishes, and the published params are **frozen** (S27).
- A node is a **generic step type**. It maps to deterministic Kotlin, a DSL, Python, events, objects, AI prompts, embeddings or tool calls.
- **Intent classification never pauses capture by itself** (user ruling: the user wants to glance at shops while riding). The exact guarantee is in S31.
- The user can define new prompts, tools and ops (§4.5, §8a).
- **Battery comes first:** at least 4 h of glasses battery, so ≤ 0.42 %/min. The A3 worn run measured 0.49 %/min (about 3.4 h).

**Tiers (user direction, binding):**

| Tier | What | Runs where | Publish |
|---|---|---|---|
| 1 | Built-in op nodes (§8) with params | the devices (fixed Kotlin) | instant: params only, no code |
| 2 | `expr` nodes in a small deterministic DSL (§4.3), **slice 3c, after the user's brainstorm (S51)** | interpreted on the device, bounded | instant: deterministic compile, no LLM |
| 3 | `py.fn` (Python) and `tool.draft` (a tool described in natural language), **slice 3b** | the laptop helper only, in DEV mode, for experiments and replay (§4.4, §4.5) | never at runtime. Promotion yields a reviewed patch, and going live needs an APK rebuild |

Slice 3a ships tier 1 only: the capture-half nodes are wrappers and param presets over the existing Kotlin (S50).

## 2. Domains, roots and signals
```
 RT_GLASSES graph (root imu.head_motion)                   ASYNC graph (root input.photos)
 imu.head_motion → imu.settle → gate.* → policy.capture_rate → capture.image ══PHOTOS══> input.photos → ai.prompt(intent) → route.switch → [vision.detect] → ai.vision_extract → memory.write
                                    ^ signal.in "ctx"                                            │
 RT_PHONE graph (root hub.context)  │                                                          event.hub_intent ──signal.out "intent"
 hub.context ──signal.out "ctx"─────┘ (pc_motion_ctx + glasses ContextGate)                      │
      ^ signal.in "intent" (fusion evidence) ─────────────────────────────────────────────────────┘
```
Ruling S34: a pipeline holds **up to three rooted domain graphs**:

| Domain | Root |
|---|---|
| RT_GLASSES | `imu.head_motion` |
| RT_PHONE | `hub.context` |
| ASYNC | `input.photos` |

Validation is per graph: unique root, acyclic, and every node reachable from its own root. This replaces the MVP rule "reachable from `input.photos`". The domains connect only through **signal bindings**:

| Binding | From → to | Port type | Carrier |
|---|---|---|---|
| `photos` | `capture.image` (RT_GLASSES) → `input.photos` (ASYNC) | PHOTOS | the existing durable frame intake |
| `ctx` | `signal.out ctx` (RT_PHONE) → `signal.in ctx` (RT_GLASSES, feeding `policy.capture_rate`) | CONTEXT | `pc_motion_ctx` + `ContextGate` |
| `intent` | `event.hub_intent` (ASYNC) → `signal.in intent` (RT_PHONE, into fusion) | SIGNAL(IntentEvidence) | `FrameIntentSink` → `ContextFusion` |

Each binding name is used once per side, the port types match, and an unbound `signal.in` takes its default: `ctx` = UNKNOWN, `intent` = no evidence. There are no other cross-domain edges. The §1 example is an end-to-end validation golden (§11).
- Why: real roots and typed bindings make the example graph expressible and checkable, and the feedback loop (intent → rate) is not a graph cycle.
- Cost if wrong: the editor shows three lanes rather than one free canvas.

Ruling S1: the RT graphs compile to **tier-1 params and tables over a fixed Kotlin skeleton, plus tier-2 `expr` programs**. The programs run at event rate only (S33). There is no bytecode, script or general-purpose interpreter on the glasses.
- Why: per-sample IMU work at 50 Hz must cost nothing extra, and A3's measured behaviour and tests are kept.
- Cost if wrong: a new per-sample algorithm is tier 3 and goes live only through promotion and a rebuild.

Ruling S2: the ASYNC graph runs in slice 2's `PipelineInterpreter`, with the shape revised in S35. It keeps slice 2's S5 (the live save tail), S6 (fallback and auto-rollback) and S7 (cost).
- Cost if wrong: none.

## 3. Non-removable invariants (outside any graph)
These are enforced in code that the plan cannot reach. The plan supplies values. `PlanBounds` clamps them, and then these invariants apply, in this order:

1. **User pause, wear gate, Delete all / Forget fences, keyframe claims (claim CAS), wipe generation, camera ownership and camera-busy back-off, LED never touched.** Unchanged code; none of them is a plan input.
2. **HeatGuard, `BatteryBudget` tiers and `PhonePowerPolicy`.** Applied after the plan; the stricter value wins.
3. **Walking** (S32).
4. **Hard caps:**
   - per-state `capPerMin` 1–4;
   - hard ceiling 2 × cap, and never more than 8 keeps per rolling minute;
   - check gaps 5–60 s under a plan (S31, S33);
   - `settleMs` ≥ 1,000;
   - under a plan `cameraWake` is always SETTLE. MOTION streaming stays a settings-only choice (S27).
5. **Liveness, "no pause by context or intent"** (S31).
6. **DRIVING suppresses nudge pop-ups** (wave B §9).

Ruling S3: these invariants live in Kotlin, and the validator refuses graphs that try to express them, for example a policy row with `pause`.
- Why: battery, privacy and safety must not depend on a draft being right.
- Cost if wrong: pause in flight is not possible from the canvas; it stays the user's tap. Wave B's "stop while flying?" stays open.

Ruling S23: **"Capture now" is exempt only from** novelty, guards, the policy check gaps, the per-minute cap and the hard ceiling (A3 §5). It is **never exempt from**:
- the user pause and the wear gate, which keep today's behaviour;
- Forget/wipe fences;
- camera ownership and camera-busy;
- the LED;
- HeatGuard STOP and `BatteryBudget` stop.

Precedence tests cover Capture now during a wipe, during heat STOP and while the camera is busy.
- Why: "exempt from everything" contradicted invariants 1 and 2.
- Cost if wrong: none.

Ruling S32 (the walking guarantee, wave B §6.1 and §9.1 kept):
- The phone's fusion priority (walkingVeto first) and the glasses' local veto are not editable.
- **The SPEED exception, stated explicitly:** fusion's walkingVeto requires "walking and not fast", and the glasses veto does not apply when `"SPEED"` is in `reasons`. Movement at ≥ 25 km/h (or the bounded plan value, ≥ 20 km/h) is treated as not on foot. Walking along a train or plane aisle can therefore get the vehicle row for a few minutes.
- **The effective walking policy:** the WALKING and UNKNOWN rows of `policy.capture_rate` are **locked to the default row**. That row is full rate, the A3 cap 4, gaps 0 and nudges on. A vetoed state (the glasses return UNKNOWN) therefore always gets full capture. A plan cannot make the fallback rows restrictive.
- Why: wave B's promise is that a false vehicle reading never costs capture while the user is walking. An editable UNKNOWN row would defeat it.
- Cost if wrong: an experiment cannot slow capture in the UNKNOWN state; it can only slow capture in states that are positively detected.

Ruling S31 (liveness, exactly what is guaranteed):
- **G1.** While capture is otherwise eligible, a **reserved timer opportunity** comes at least once per 60 s under any plan. "Eligible" means: not user-paused, worn, not heat- or battery-stopped, camera not busy, and the HUD capture session running.
- **What the reserved opportunity is:** a timer check that opens the camera. Guards, novelty and policy gaps cannot suppress it. The keyframe gate's dedupe and blur rules and the hard ceiling still apply. So "opportunity" means a camera open, not necessarily a kept photo.
- **One exception, built in and not editable:** A3's still-scene backoff may suppress an opportunity while the IMU is STILL and the last 2 opens were near-duplicates of the ring. Its built-in maximum is 60 s, so G1 still holds.
- **G2.** Guards may drop every glance. That is allowed. A guard may read the context state (§17.1) and drop on it; G1 and the S32 walking rows still hold because the reserved timer attempt and the walking veto run after the guard. G1 is what bounds the loss.
- **G3.** No policy row can set `pause`, and `capPerMin` ≥ 1.
- **Settings mode (no plan)** keeps wave B's defaults. There, REST and FLIGHT gaps of up to 300 s give at least 1 opportunity per 5 min. That is wave B's guarantee, not G1.
- Why: `capPerMin ≥ 1` alone was an allowance, not a promise. A timer path that guards can't reach gives a promise that can be tested.
- Cost if wrong: under a plan the camera opens at least once a minute even in a quiet flight. A3 measured timer opens at about 2.7/min, so this cost is already inside the 0.49 %/min.

## 4. Generic node contract
### 4.1 Node type descriptor (`NodeTypes` v2, `:services:ai` `ai.canvas`)
```kotlin
data class NodeTypeSpec(
    val type: String, val version: Int,
    val domain: Domain,          // RT_GLASSES | RT_PHONE | ASYNC | REPLAY_ONLY
    val category: Category,      // SENSORS | SIGNALS_HUB | THRESHOLDS_FILTERS | POLICY_FREQUENCY | ACTIONS_CAPTURE | AI_CLASSIFY | MEMORY | DATA | CODE
    val executor: ExecutorKind,  // BUILTIN | EXPR | TABLE | PROMPT | EMBED | TOOL | EVENT | SIGNAL | PYTHON | DRAFT
    val inPorts: List<Port>, val outPorts: List<Port>, val branches: Branches,
    val params: ParamSchema,     // number/int (min, max, step, sweep hint), enum, bool, string(maxLen), table(rows, cols)
    val cost: CostSpec,          // model calls per item, rtOps per event, cpuClass
    val liveable: Boolean,       // false for PYTHON, DRAFT, REPLAY_ONLY
)
```
- **Port types:** `PHOTOS`, `JSON`, `EXTRACTION`, `PREVIEW`, plus the new `IMU` (replay only), `MOTION_EVENT`, `CONTEXT`, `TRIGGER`, `VECTOR`, `TEXT` and `SIGNAL`.
- **Executor kinds:**

  | Kind | What it is |
  |---|---|
  | `BUILTIN` | tier 1 Kotlin |
  | `EXPR` | tier 2 (§4.3) |
  | `TABLE` | a keyed data object |
  | `PROMPT` | through `RoleRunner` |
  | `EMBED` | `EmbeddingService` |
  | `TOOL` | the existing action registry; a preview in experiments, and live only as an `ActionProposal` through confirm |
  | `EVENT` | emits or consumes a named signal |
  | `SIGNAL` | the binding ends (S34) |
  | `PYTHON` | §4.4 |
  | `DRAFT` | §4.5 |

- **`/api/node-types`** serves the registry. The palette groups nodes by category, and the editor colours the domains and refuses cross-domain edges. The server is the authority.
- **Every op is a node and every param is tunable**, including head movement, the thresholds, the hub motion and the cap.

Ruling S4: one registry, with a fixed set of executor kinds. A user-made node is an **instance** of a kind: a prompt, an `expr`, a table, a `tool.call` wiring, a `py.fn` or a `tool.draft`. A new kind is a Kotlin change.
- Why: no unreviewed code reaches the devices (§9).
- Cost if wrong: none.

### 4.2 Params, hashing, versioning
- Params are validated against `ParamSchema`, with the client as a hint and the server as the authority. RT params also carry `PlanBounds`, which are narrower where an invariant requires it.
- **Schema 2 hash** = SHA-256 of the canonical JSON, including each node's `type@version` and its params.

Ruling S41 (legacy schema 1):
- **Resolution:** a schema-1 node resolves to `version = 1` of its type. Every MVP and slice 2 type keeps a v1 descriptor forever.
- **Stored hashes are never recomputed:** the hashes of frozen versions, runs and publishes stay in their rows. An active publish keeps running from its stored JSON.
- **Editing an old version:** opening a schema-1 version for edit creates a schema-2 **draft**. Freezing that draft creates a new successor version with a new hash. Exact-hash gates therefore refer to the version that was actually tested.
- **Tests:** upgrade fixtures with frozen schema-1 versions, DONE runs and an active publish.
- Why: normalising the old records would silently break slice 2's "tested hash" gates.
- Cost if wrong: an old pipeline needs a new run after its first schema-2 freeze.

### 4.3 Tier 2: the `expr` DSL (slice 3c, brainstorm first)
Ruling S51 (2026-10-22, user): the expression language is **deferred to 3c**. The user will brainstorm it first (task C0), and this section is the starting draft for that session, not a build spec.
- **Until then:**
  - A2 registers the `EXPR` kind as not liveable and hidden from the palette;
  - the compiler reports anything that is not a simple threshold as "needs an expression node (3c)";
  - `CapturePlan` has no `exprs` or `tables`;
  - the glasses advertise neither `expr` nor `windows` in `caps`.
- **S5 and S33 apply when it lands.** Nothing in 3a or 3b depends on them.
- Why: the user wants to design the language, and 3a's wrappers cover the §1 flow without it.
- Cost if wrong: "skip when at a computer" style rules wait for 3c. Until then they are made with a policy row or a param preset.

- **Form:** a JSON AST. The canvas has a text syntax that the server parses into it, and nothing is ever eval'd.
- **Operations:**
  - `+ − × ÷` (dividing by 0 gives 0), `min`, `max`, `clamp`, `abs`;
  - comparisons, `and`, `or`, `not`, `if(c, a, b)`;
  - `lookup(table, key, default)` over a `const.table` of at most 32 rows;
  - windows: `count | mean | max | frac(feature, s ≤ 300)`.
- **Not allowed:** loops, variables, recursion, calls, IO, clock reads, strings.
- **Limits:** at most 32 AST nodes per program, 6 programs per plan, 4 window terms per plan and 3 tables per plan.
- **Features:**
  - motion: `boutMs`, `boutRad`, `rotSinceKeepRad`, `msSinceKeep`, `walkedSinceKeep`, `keeps60s`, `stillFrac5m`, plus the `SecondFeatures` fields;
  - context, for guards and values (§17.1; the inspector warns when a guard reads it): `ctxState`, `ctxConf`, `ctxSinceMs`.
- **Roles:**
  - a **guard** returns a bool and may only drop a glance candidate. It may read motion and context (§17.1). It never touches the reserved timer opportunity (S31) and never lifts the walking veto or the WALKING/UNKNOWN full-rate rows (S32); both run after the guard.
  - a **value** computes one policy field, clamped to `PlanBounds` and the invariants.

Ruling S33 (the cost ceiling, made concrete):
- **Windows:** each window term is backed by an incremental aggregate over a fixed ring that the skeleton allocates when the plan is applied. That ring has 300 per-second slots, or 64 event slots for event-time terms. A query costs O(1): a running sum, count or max with a monotonic deque. Updates happen once per second.
- **Lookups:** a linear scan of at most 32 rows.
- **Memory:** a plan's evaluator state is at most 8 KB, fixed when the plan is applied.
- **Rate:** evaluation runs on glance and timer events only, coalesced to **at most 2 evaluations per second**. Extra events in the same 500 ms fold into the next tick.
- **Scheduler floor:** check gaps are 5–60 s.
- **Worst case:** 2 × (6 × 32 + 4 window reads) ≈ 400 operations per second.
- **Results are finite and typed:**
  - Values are computed as `Double`.
  - A non-finite intermediate is an evaluator error. A guard error **keeps** the candidate, so a failure costs at most the cap, never liveness. A value error uses the row default.
  - Division by zero is an error by role (3c ruling Q11): the guard keeps the candidate, the value takes the row default. It is counted as `exprDiv0` and does not count toward auto-revert.
  - Other errors are counted as `exprErr`. More than 10 in a minute auto-reverts the plan (§7).
- **One evaluator everywhere:** `PlanEvaluator` in `:core:capture` is used by the validator, the simulator, the phone and the glasses. A max-cost and overflow test runs on the glasses.
- Why: the earlier ceiling ignored windows, lookups, gaps of zero and NaN.
- Cost if wrong: under a burst of glances, a decision can be delayed by up to 500 ms.

Ruling S5: tier 2 is non-Turing, bounded and evaluated at event rate. Guards are drop-only (they may read motion and, per §17.1, the context state), and values are clamped.
- Why: instant publish with a provable cost.
- Cost if wrong: "skip when at a computer" must be written as a value program or put in the REST row.

### 4.4 Tier 3: Python and the laptop helper (slice 3b)
**The helper.** Python cannot run on the phone or the glasses, and a browser cannot run local programs. So both Python execution and promotion go through one **laptop helper**, `tools/canvas-helper/helper.py` (Python 3).
- It connects **out** to the phone's canvas server and listens on no port. The page keeps CSP `connect-src 'self'`.

Ruling S39 (pairing, capabilities, revocation):
- **Pairing:** the user types the certificate fingerprint shown in the app (8 groups of 4 hex) into the helper before pairing. The helper refuses a mismatch, and only then sends the 6-digit code. This is an out-of-band check, not trust on first use.
- **Capabilities:** at pairing, the user grants the token explicit capabilities in the app: `py_exec` and/or `promote`. Both are off by default.
- **Who can submit jobs:** a browser session can submit helper jobs only while the app's switch "Allow laptop helper jobs" is on. That switch is off by default and turns off with LAN. Every `promote` or `tool_build` job also needs a confirm tap in the app.
- **Leases:** a job is leased for 60 s and renewed by heartbeat. Only the leasing helper can post its result.
- **Revocation:** Disconnect, Sign out all, the switch going off or the lease expiring all do the same things:
  - the server voids the job;
  - the helper kills the job's container (§S22);
  - the helper deletes the job's inputs.
- **Retention on the laptop:**
  - inputs: deleted at job end;
  - outputs and patches: kept under `~/.pca-helper/` for 7 days, or until "Delete laptop copies" (a command sent to the helper on its next poll, which the helper acknowledges).
- Why: the previous pinning trusted whatever certificate came first, and any browser could make the laptop execute code.
- Cost if wrong: two more taps when pairing.

Ruling S22 (the isolation boundary):
- **Containers:** every `py.fn` job, every CLI generation and every verification build runs in a **rootless container**, Podman or Docker. With no container runtime, the helper refuses execution jobs ("needs Podman or Docker").
- **Python jobs:**
  - `--network none`;
  - no credentials;
  - no home directory;
  - mounts: the job's inputs read-only and an output directory;
  - limits of 2 CPUs and 2 GB, plus a timeout of 5 s per item and 120 s per job;
  - on timeout or cancel, the whole container is killed, which kills the process tree.
- **Generation jobs (codex / claude):**
  - mounts: a **read-only snapshot** of the repo at a fixed commit (`git archive`, not the live checkout) plus a writable scratch copy;
  - network: egress only to the CLI's own API host, through a helper allowlist proxy;
  - credentials: only the CLI's own API credential, mounted read-only. It is the one credential the CLI needs. There are no git/ssh/cloud/adb keys and no `.env`.
- **Verification builds:**
  - a separate container with `--network none`;
  - Gradle `--offline` with a read-only copy of the dependency cache;
  - the patch applied to a fresh snapshot.
- **The helper's own credentials stay outside every container:** its phone token and its pinned fingerprint.
- Why: a scratch directory and a timeout ran untrusted code as the laptop user.
- Cost if wrong: a container runtime has to be installed on the laptop, and the first build needs a warm Gradle cache.

#### 4.4.1 DEV mode and DEPLOY mode
Ruling S52 (2026-10-22, user: containers are fine; installing Podman or Docker is allowed):

| | **DEV mode** (experimenting) | **DEPLOY mode** (daily use) |
|---|---|---|
| What runs on the phone | the app, plus slice 2's LAN HTTPS canvas server as a foreground service with the notification "Canvas dev session" | the app only. No LAN listener |
| What runs on the glasses | the app, running the desired plan in SHADOW, TRIAL or LIVE | the app, running the persisted frozen plan (S24) in Kotlin |
| Laptop | the browser on the canvas, plus `pca-helper` and its job containers | **nothing** |
| Node tiers allowed | 1, 2 (from 3c) and 3 (`py.fn`, `tool.draft`), in experiments and replay | the published tiers 1 and 2 only. S6 already refuses to publish a graph with a tier-3 node, so DEPLOY never needs the helper |
| Promote to Kotlin | yes: a patch on `canvas-promote/<id>` or `canvas-tool/<id>` on the laptop | no. Promoted code arrives only through review → merge → gate → normal install |
| On-device recorder and marks | yes | yes: "Collect for tuning" and mark-now are part of the app, not the helper. This is how 3c's week of data is collected |

- **Starting DEV:**
  1. On the phone: Developer mode → Canvas → **"Start dev session"**. This starts the canvas server and offers the "Allow laptop helper jobs" switch (S39: off by default).
  2. On the laptop: `tools/canvas-helper/pca-helper dev`, which pairs once (S39), then polls.
  3. The session ends on "End dev session", after 4 h with no canvas request, or when the LAN goes away. Ending it stops the server, revokes the helper's jobs (S39) and turns the switch off.
- **Starting DEPLOY:** there is nothing to start. Ending the dev session *is* DEPLOY. The canvas home and Settings → Capture show "Canvas: deploy · plan <name> v<N> (<mode>) · no helper".
- **Packaging:**
  - **APKs:** one phone APK and one glasses APK, the same in both modes. There is no build flavour. The canvas web assets are bundled in the phone APK (slice 2). DEV features sit behind Developer mode.
  - **Helper:** never in an APK. It lives in the repo at `tools/canvas-helper/`:
    - **`pca-helper`:** a single Python 3 **standard-library** script that runs on the laptop host. It needs no pip install.
    - **`Containerfile.pyjob`:** `python:3.12-slim`, pinned by digest, plus pinned numpy wheels. It runs `py.fn` jobs.
    - **`Containerfile.gen`:** the codex or claude CLI at a pinned version. It runs generation jobs (S22).
    - **`Containerfile.verify`:** Temurin JDK 17, the Android command-line tools and a read-only copy of the Gradle cache. It runs offline builds.
  - **Setup:** `pca-helper setup` builds the three images locally with `podman build` (or `docker build`) and tags them with the helper version. `pca-helper doctor` checks the runtime, the images, the CLI credential and `helperProtocol`, which pairing also compares (version 1).
- **Why the helper is a host process and not a container:** the helper has to start the job containers. Putting it in a container would mean mounting the container runtime's socket into it, which hands that container the whole laptop. The host script is small and stdlib-only. Everything untrusted (Python, CLI output, builds) runs inside the S22 containers.
- Why: the user wants a clear line between experimenting and living with the result, with nothing extra running in daily use.
- Cost if wrong: one more Developer-mode switch, and a one-time image build of about 10 min on the laptop.

**`py.fn` nodes:**
- **The phone never executes the source.** It is stored in the pipeline JSON (at most 16,000 characters), and the phone forwards it in a job.
- **Output:** it must match the out-port schema.
- **In RT replay:** the node runs per event in lockstep, about 20 ms per round trip. A sweep that contains a `py.fn` is limited to 10 configs.
- **No helper connected:** the items fail with `no_helper`.

**Promote to Kotlin.** This is not a runtime publish.
1. **The bundle:** the phone exports a promotion bundle: the source, the port and param schemas, and the **golden I/O**, which is the Python outputs over the chosen replay traces. It holds at most 5,000 cases, numbers and enum names only, and its SHA-256 is recorded.
2. **The helper picks a path:**
   - **a.** `codex` or `claude` is on PATH: the CLI generates a Kotlin `BUILTIN` node in the generation container (S22).
   - **b.** (3c, it waits for the expression language, S51) No CLI: a deterministic translator handles the DSL-able subset (one pure function: arithmetic, comparisons, `if`/ternaries, `min`, `max`, `abs`, dict lookups over constants; no loops, imports or IO) and produces a **tier-2 `expr`**, publishable with no APK.
   - **c.** Otherwise: a clear alert ("no codex/claude CLI and not DSL-expressible: <first construct>"), plus an exported `promotion-<id>/` with `SPEC.md`, the golden I/O and the source.
3. **Verification:** the helper verifies with its own oracle (S40).
4. **Review:** the diff, the test report and the agreement figures come back to the canvas read-only, rendered as text by `patchview.js` (§13).
   - The patch stays on the laptop as `promotion-<id>.patch` on an unmerged branch `canvas-promote/<id>`.
   - Nothing is committed to the user's branch, built into an installed APK, or installed by the helper.
   - A promoted node reaches the devices only through review → merge → gate → install. After that it is tier 1, and the canvas offers "Replace `py.fn` with <node>".

Ruling S40 (the oracle):
- **The oracle belongs to the helper:** the comparator and its test adapter live in the helper's own source tree, outside the patch. They load the golden I/O, verify its hash, and call the generated node through a fixed `NodeUnderTest` adapter.
- **A patch is rejected** when it touches the harness, the adapter, the golden files, build-verification scripts, `gw.sh`, or any Gradle file outside the new node's module entry.
- **Held-out cases:** the generator sees 80 % of the golden cases. The other 20 % are held back, together with auto-generated boundary cases at the param bounds and the feature extremes.
- **Acceptance:** 100 % agreement on branches and enums, and |Δ| ≤ 1e-6 on numbers, over **all** cases.
- **The report:** "agreement on N sampled cases (M held out)", never "equivalent". Generated tests are extra tests, not the oracle. Human review stays mandatory.
- Why: a generator must not be allowed to write its own pass condition.
- Cost if wrong: a function that uses `exp` or `sqrt` needs its tolerance raised by hand in the bundle.

Ruling S6: a graph with any `PYTHON`, `DRAFT` or `REPLAY_ONLY` node can be frozen and replayed but **never published**. The fixed reason is "Promote, build or port <node> before publishing".
- Why: there is no Python on the devices, and generated code is reviewed before it runs there.
- Cost if wrong: tier-3 ideas go live only after a translation to tier 2, or after a reviewed rebuild.

Ruling S6a: promotion prefers the CLI, falls back to the DSL translation, and then to an export. The output is always a diff for review.
- Cost if wrong: see S40.

### 4.5 Tool from natural language (`tool.draft`) (slice 3b, first)
Ruling S55 (2026-10-22, user): NL tools are the **first** feature of 3b, built right after 3a.
- **What they need:** the 3a node contract (A2) and the helper core (B1). That is why B1 may run during 3a.
- **Size:** with those in place, B2 is about 6 agent-h, one evening of build and test. Every S48 guard below stays.
- **What is in the first version:**
  - the stand-ins, which work with no helper and no build;
  - the generation container;
  - the path and licence allowlists and the injection scan;
  - the example and schema check;
  - the debug APK and the diff.
- Cost if wrong: if B1 slips, NL tools wait for it, about 6 agent-h more.

1. **Describe.** On the canvas, the user describes a tool in plain language, for example "when a receipt is in view, add the total to my expenses sheet". The canvas saves it as a **`tool.draft` node** (ASYNC, executor `DRAFT`). The user fills in its contract:
   - the in and out ports;
   - the output JSON Schema;
   - the side effect: none, a proposal, or a new external call;
   - **stand-in outputs**, which are example input → output pairs. Optionally, the stand-in can be a prompt-based `ai.prompt`.

   Experiment runs use the stand-in, so the graph can be tested before the tool exists.
2. **Freeze.** Freezing the graph freezes the draft's NL text, contract and examples together.
3. **Build** (an app confirm and the `promote` capability, S39). The helper gives the CLI the NL spec, the node contract, the examples and the read-only repo snapshot, in the generation container (S22). The CLI works on a **separate git branch `canvas-tool/<id>`** in the scratch copy, cut from the snapshot commit, never `main` and never the user's working branch. It produces:
   - a Kotlin node or tool executor, with its tests;
   - the **equivalence check against the draft**: the helper's oracle (S40) replays the frozen examples and checks schema conformance on 200 generated inputs;
   - a **debug APK** built in the verification container.
4. **Review.** The helper returns the diff (shown in the canvas by `patchview.js`), the test results, the equivalence report, the licence report and the APK's path and SHA-256 on the laptop. The user reviews them on the laptop.
5. **Approve.** Approval is the user merging the branch through the normal review → gate path. An install needs the user's explicit OK and the normal backed-up install procedure. **Nothing is ever auto-installed or auto-merged.**

Guards on the build:
- **Licence check:** new Gradle dependencies and any model files are checked against an allowlist (Apache-2.0, MIT, BSD-2/3, ISC, MPL-2.0 file-level). GPL, AGPL, research-only, non-commercial and unknown licences are rejected, as MobileCLIP and the YOLO family were.
- **Prompt-injection guard:**
  - the NL text is data, passed in a fixed template between delimiters, with an instruction that it is a requirement, not a command;
  - the CLI runs without the user's credentials or network beyond its API (S22);
  - the patch must stay inside a **path allowlist**: a new package under `services/tools/canvas/<id>/`, its tests, and one registry line;
  - patches that touch the manifest, permissions, network or endpoint code, build scripts, other modules, keys or `.env` are rejected;
  - a scan rejects URLs, IP literals and credential-looking strings that the NL spec did not contain.
- **No CLI:**
  - if the draft can be expressed as a `tool.call` wiring to an existing executor or as an `expr`, the canvas offers that deterministic path, checked against the same examples;
  - otherwise a clear alert ("No codex/claude CLI found; this tool needs code"), plus an export of `tool-<id>/` (the NL spec, the contract, the examples, `SPEC.md`).

Ruling S48: NL tools are **drafts with stand-ins** until a reviewed build lands. A build runs only in the isolation boundary on its own branch, ships as a diff plus test results plus a debug APK for human review, and is installed only by the user.
- Why: the user wants to describe tools in words, and generated device code is the highest-risk path, so every gate is a human one.
- Cost if wrong: each new tool costs one review and one install cycle.

## 5. The compile step
### 5.1 `:core:capture` (a new pure Kotlin/JVM module)
Ruling S50 (2026-10-22, user: **wrap, do not rewrite**):
- **The capture-half nodes are thin wrappers and param presets over the existing Kotlin:**

  | Node | Wraps |
  |---|---|
  | `imu.head_motion`, `imu.settle` | `GlanceParams` for A3's `GlanceDetector` |
  | `gate.novelty` | A3's novelty decision in `SamplingSession.onDwell` |
  | `gate.rate_cap` and `capture.image` | the params of `KeyframeGate.offer(…, capPerMin)` and its ring dedupe |
  | `policy.capture_rate` | `ContextCapturePolicy` rows |
  | `hub.context` | `FusionParams` for wave B's `ContextFusion` |
  | `signal.in ctx` | wave B's glasses `ContextGate` |

  A node's compile output is a param block that these classes already accept. Their algorithms are unchanged.
- **Code moves only where replay needs it.** The simulator must run the same decision code as the glasses, so the **pure** decision pieces move here as **move-only** commits, with A3's tests moving alongside:
  - `GlanceDetector` and `GlanceParams`;
  - `MotionModel` and `HeuristicMotionModel`, the pure core of `MotionClassifier`;
  - the novelty and cap decision of `SamplingSession.onDwell`;
  - `KeyframeGate`'s cap and dedupe decision.

  `SamplingSession`, camera code and the Android-side `MotionClassifier` wiring stay where they are and call into the moved code. `ContextFusion`, `ContextPolicies` and `ContextGate` stay in `core:model` (already pure), and the simulator calls them there.
- **What the module adds:** `CapturePlan`, `PlanBounds`, `PlanValidator` and `CaptureSimulator` in 3a. `PlanEvaluator` follows in 3c (S51).
- **A move commit that needs more than renames and visibility changes is stopped** and brought to review.
- Why: A3's behaviour and green tests are the measured baseline (0.49 %/min), and a rewrite would void that measurement.
- Cost if wrong: a later per-sample algorithm still needs its own Kotlin node (tier 3 → promote).

Ruling S7: one pure module is shared by the glasses, the simulator and the compiler.
- Why: what you replay is what ships.
- Cost if wrong: one refactor of merged A3 code, done move-only.

### 5.2 `CapturePlan` (data, schema 1)
```json
{"planSchema":1,"hash":"…12hex","name":"ride-glances v3","settingsRev":41,
 "glance":{"onsetRadS":0.6,"releaseRadS":0.3,"bridgeMs":250,"minBoutMs":300,"minBoutRad":0.26,"settleMs":1500,
           "noveltyWindowMs":20000,"noveltyMinRad":0.30,"bigTurnRad":0.8},
 "exprs":[{"role":"guard","ast":{…}},{"role":"value","field":"REST.capPerMin","ast":{…}}],
 "tables":{…},
 "policy":{"REST":{"capPerMin":2,"settledCheckMs":30000,"quietCheckMs":60000,"walkCheckMs":30000,"stillCapMs":60000,"nudges":true},
           "VEHICLE_PASSENGER":{…},"VEHICLE_DRIVING":{…},"FLIGHT":{…}},
 "gate":{"bigChange":22,"hamming":10,"ringCompare":"all"},
 "phone":{"fusion":{…all FusionParams…},"intentCadence":{"vehicleMs":60000,"otherMs":300000}}}
```
- **Size:** `planJson` is at most **8,192 bytes** (UTF-8, canonical JSON). The compiler fails over that and never trims. The glasses reject a larger plan as `too_big`.
- **`exprs` and `tables`** are absent in 3a and 3b. They arrive with the expression language in 3c (S51), together with the `expr` and `windows` caps.

Ruling S27 (frozen means resolved):
- **Resolution at publish:** a published plan is **fully resolved**. Every field the skeleton reads is filled in, from the graph or else from the settings and defaults current at that moment, and the result is hashed. `settingsRev` records which settings were used.
- **Settings edits:** a later edit in the settings screens does not change a live plan. It applies after "Use settings" (S26).
- **"No plan" is a sentinel, not an empty plan:** `USE_SETTINGS` delegates everything to the settings, exactly as today, MOTION wake included.
- **The identity plan** is the plan resolved from the current settings, and it is only defined for SETTLE wake. A user whose settings are MOTION gets SETTLE under any plan, and the publish dialog says so (the S3 exception).
- **Rows:** the WALKING and UNKNOWN rows are never in a plan (S32).
- Why: a "frozen" plan that silently followed mutable settings would invalidate its own replay.
- Cost if wrong: after changing a setting, the user must republish to fold it into a live plan.

**`PlanCompiler.compile(pipeline): CompileResult(plan?, problems, staticCost)`** (`:services:ai`) walks the RT graphs:
- `BUILTIN` nodes write their param blocks;
- simple threshold bounds fold into `glance.*`, and anything else becomes a guard `expr`;
- the policy table becomes `policy`;
- `hub.context` becomes `phone.fusion`.

In 3a it does not depend on the `expr` AST: a threshold that cannot fold into `glance.*` is the problem "needs an expression node (3c)". From 3c on it depends on `PlanEvaluator` (C1). Unplaceable nodes get fixed problem texts. A plan over the hard caps fails to compile. It is never clamped silently.

Ruling S8: the policy table is keyed by `ContextState` only. The intent acts through fusion.
- Why: one place, with tested hysteresis, merges deterministic and image evidence.
- Cost if wrong: "computer work" and "sitting idle" share the REST row. A later key can be added.

### 5.3 Delivery to the glasses (PROTOCOL_VERSION stays 1, additive)
Ruling S24 (commands with generations):
- **`pc_capture_plan` (phone → glasses):**
  - **Fields:** `CapturePlanMessage(v, gen: Long, publishId: Long?, mode: SHADOW|LIVE|TRIAL|SETTINGS, hash: String?, planJson: String?, trialEndsAtMs: Long?, atMs, sessionStartMs)`.
  - **Generation:** `gen` is a durable counter on the phone that grows with every desired-state change: publish, mode change, rollback, settings.
  - **Settings:** `mode = SETTINGS` with `planJson = null` means use settings.
  - **Sending:** the message is sent on every change and in `onReady()`. Re-sending the current gen is idempotent.
- **Glasses rules:**
  - Messages with `atMs < sessionStartMs` are ignored, as for other channels.
  - A message with `gen < appliedGen` is rejected as `stale`.
  - A message with `gen == appliedGen` is re-acked without being applied again.
  - `appliedGen`, the plan and the mode are persisted, so they survive force-stops and restarts. After a restart the glasses run the persisted plan (TRIAL only until `trialEndsAtMs`) and re-ack it on the next `onReady()`.
- **`pc_capture_plan_ack` (glasses → phone):**
  - **Fields:** `(v, gen, publishId, mode, effectiveHash, state: EFFECTIVE|REJECTED|REVERTED, reason?, planSchemaMax, caps: List<String>)`.
  - **Reasons:** `too_big`, `schema`, `bounds`, `expr_limit`, `version`, `stale`, `unsupported:<cap>`, `auto:<rule>`.
  - **Capabilities:** `caps` lists what the glasses support, for example `expr`, `windows` and `trial`. A plan that needs a missing capability is rejected as `unsupported`.
  - **Old glasses** send no ack, and the phone shows "Glasses app too old for canvas plans".
- **Heartbeat** (stays `v=2`): appends `plan=<hash8|none> gen=… planMode=… ruleSkip1m=… exprErr=… planRevert=<reason|none>`.
- Why: a hash alone cannot tell SHADOW from LIVE, or a duplicate from a stale rollback.
- Cost if wrong: none.

Ruling S9: the plan has its own channel, not a `HudSettings` field.
- Why: size, and the need for an ack.
- Cost if wrong: two constants.

## 6. Replay, traces, tuning
Ruling S53 (2026-10-22, user: **lean first version**). In 3a:
- **Replay:** the recorded IMU and hub traces replay through the same shared code (`:core:capture` and `core:model`, S7, S50).
- **Sweeps:** grid or seeded random (§6.3).
- **Objectives:** captures/min, the recall of marked moments, and a battery estimate from the measured slope.
- **Pareto chart:** vendored Chart.js (§13).
- **Holdout:** by day (S46).

The parts of S28, S29, S30 and S42 marked **"Later (Ln)"** below are not built in 3a. They are on the Later list in the Re-cut section, which names the Codex finding each came from and why deferring it is safe. In short, they only sharpen estimates. Estimates gate only entry to a bounded TRIAL, and unrestricted LIVE always needs a measured S44 run (S11).
- Cost if wrong: Pareto points carry more hidden error, and the user may pick a plan whose TRIAL then auto-reverts or measures worse. The cost is one trial.

### 6.1 Traces
| Trace | Where | Contents |
|---|---|---|
| IMU | glasses `files/imu_trace/*.bin`, sealed | gyro and accel at native rate; the classifier state; glance, keep and open events; **candidate metadata for every open** (each analysed frame's dHash/pHash/blur, the sharpest selection, the gate outcome and its reason) |
| Hub | phone `canvas_trace_event` | timestamped `ContextFusion` **evidence** (enum names, `kmh` rounded to 5, confidence) and committed decisions; no location, address or device name |
| Photos | existing `frame_meta` | keyframe ids, hashes, motion, dwell, intent |
| Labels | `canvas_label` | MOMENT and INTENT labels (§6.4) |

Ruling S42 (the trace manifest). **3a builds manifest-lite:**
- **What it keeps:**
  - traceId and the boot id;
  - the chunk count, the per-chunk size limits, and SHA-256 per chunk and for the whole trace;
  - the start snapshot (needed for exact parity);
  - the wipeGen and tombstones;
  - COMPLETE/PARTIAL.
- **Clock offset:** glasses ↔ phone, sampled at the trace start and at its end, and interpolated linearly in between.
- **Transfer:** while charging. A failed or mismatched transfer retries the **whole** trace, and a trace whose chunks are not all present and matching is PARTIAL.
- **Later (L4):** per-seq acks and resume, and clock-mapping samples every 60 s with their uncertainty. The full text follows for that later version.

- **Each trace has a versioned manifest** (`traceSchema 1`) holding:
  - **identity:** traceId and the glasses boot id;
  - **clock mapping:** samples of glasses `elapsedRealtime` ↔ glasses wall ↔ phone wall, taken from a link ping every 60 s, with the offset and its uncertainty;
  - **chunk inventory:** chunk sequence numbers and count, per-chunk compressed size (≤ 64 KB) and decompressed size (≤ 512 KB), and SHA-256 per chunk and for the whole trace;
  - **a start snapshot:** the `HudSettings` hash, the classifier and detector state, the keyframe ring hashes and the plan gen;
  - **the wipe generation;**
  - **status:** COMPLETE or PARTIAL.
- **`pc_trace_chunk` transfer:**
  - each chunk carries (traceId, seq, wipeGen, sha);
  - the phone acks every seq, and a transfer resumes from the last acked seq;
  - chunks for a tombstoned trace or an older wipeGen are rejected and deleted on both sides.
- **Use:** a PARTIAL trace can be viewed and replayed, but it is never publish evidence. Replay orders events by the mapped phone time, and IMU samples keep their own monotonic order.
- Why: the trace sources are joined across 2 clocks and a lossy link.
- Cost if wrong: about 300 bytes per manifest.

Ruling S10: raw IMU is recorded only in an explicit "Collect for tuning" session (§6.4), and shipped only while charging and linked. `adb pull` is the developer fallback.
- Cost if wrong: a trip's traces arrive only at the next charge.

### 6.2 `CaptureSimulator`
Ruling S29 (two classes of replay). **In 3a:**
- **Exact decision replay** is built in full, as below. It is the parity test.
- **Counterfactual replay** is built lean:
  - The IMU-driven decisions are exact: glances, novelty, opens and caps.
  - The keeps of opens that were never observed are a **point estimate**: the outcome of the nearest observed frame within 2 s, otherwise counted as a keep.
  - One figure, "unobserved opens: x %", is shown as a warning.
  - SHADOW reports would-open and would-skip counts only.
- **Later (L1):** keeps_min and keeps_max, the coverage bounds and the error bars.

- **Exact decision replay** runs on the observed path: the same config as recorded, from the start snapshot. It feeds the IMU and the recorded candidate metadata through the exact glance → novelty → `expr` → policy → gate → cap code. It must reproduce **bit-identical** open, keep and skip sequences. This is the parity test (§11).
- **Counterfactual replay** (any other config):
  - **IMU-driven decisions are exact given the trace:** glances, guard skips, novelty, opens, caps.
  - **Visual outcomes** of opens that were never observed (keep or skip by dedupe or blur) are **unknown**. They are reported as bounds: keeps_min (all unknown skip) and keeps_max (all unknown keep), plus a point estimate from the nearest observed frame within 2 s, and the coverage share.
- **SHADOW mode** reports would-open and would-skip counts, which are IMU-exact, and never would-keep counts.
- Why: frames that were never captured cannot be replayed.
- Cost if wrong: keeps-based metrics come with ranges, not single numbers.

Ruling S30 (fusion in replay). **In 3a:**
- Replay uses the **recorded committed context states** from the hub trace.
- Fusion params are **not sweepable**.
- A plan whose fusion params differ from the trace's shows the fixed warning "fusion changes are not replayed", and can only run in SHADOW or TRIAL until a verified interval exists.
- **Later (L2):** fusion is recomputed in replay, with the intent-unobservable accounting below.

- **Recomputed, not replayed:** counterfactual replay runs the recorded timestamped evidence through `ContextFusion` with the plan's `FusionParams`, rather than reusing the recorded committed states.
- **Intent evidence** is available only from frames the counterfactual also keeps (by the point estimate) and only at the sampler's cadence. Where the counterfactual would have classified a frame that does not exist, the state is marked `intent unobservable`, and the share of time affected is reported.
- **Sweeps:** `intentCadence` is not sweepable. Fusion params are sweepable, and they are flagged when more than 20 % of the time is intent-unobservable.
- Why: replaying the recorded states would ignore the very params being tuned.
- Cost if wrong: intent-heavy fusion settings are tuned with warnings.

**Outputs** (per minute, per state, with ranges where S29 applies):
- opens and camera-open seconds;
- keeps;
- vetoes, `ruleSkip`, `capped`, `novelSkip`;
- reserved timer opportunities (S31).

**The baseline** (the identity plan) is always replayed on the same traces.

Ruling S28 (the drain model, identifiable by construction). **In 3a:**
- **A fixed two-point line from the measured slope:** %/min ≈ 0.433 + 0.0239 · camOpenS/min. It passes through the A3 worn run (0.49 %/min at 2.4 s/min of camera-open time) and the A2 point (0.6 at 7 s/min).
- **Labelling:** every figure shows "estimate", plus "hours from full" = 100 / estimate.
- **No refits.** Verified intervals are imported and shown next to the estimate, but nothing is fitted.
- **Later (L3):** the refit on verified LIVE intervals, the 3-slope upgrade with its condition-number rank check, the leave-one-out residuals and the extrapolation flags (the text below).

- **Start simple:** %/min = floor + k · camOpenS/min. It has 2 parameters, fitted on the A3 anchor (0.49 at 2.4 s/min) and the A2 point (0.6 at 7 s/min), and **refitted on every verified LIVE interval**.
- **Upgrade rule:** the 3-slope model (opens, camera-open seconds, keeps) is enabled only after **at least 6 verified LIVE intervals** whose design matrix has a condition number ≤ 30. The rank check is shown.
- **Uncertainty:** the leave-one-out RMS residual, shown as "± x %/min (n intervals)".
- **Extrapolation:** an input outside 1.2 × the observed range is flagged "extrapolated".
- **The fit's inputs:** calibration rows store the plan hash, `settingsRev`, the mode and the phase boundaries. Only LIVE (or settings-mode) intervals that pass S44 are used.
- Why: 3 slopes cannot be fitted from 2 points.
- Cost if wrong: early estimates ignore effects that are separate from camera time, such as BT traffic per keep.

**The ASYNC half** replays as slice 2 runs do: kept frames only, under slice 2's limits.

### 6.3 Tuning (sweeps) and the battery gate
- **Swept params:** any tier-1 glasses param (and, from 3c, any `expr` constant) can be swept. Fusion params are not swept in 3a (S30). Sweeps run by grid or by seeded random sampling. At most 4 params and 200 configs per sweep. RT-only sweeps make no model calls: they run on the phone CPU in `CanvasService` (about 1 min for 200 configs × 4 h), and stop on thermal SEVERE.
- **ASYNC variants:** at most 4, as slice 2 A/B runs.
- **Objectives**, each per config:

  | Objective | Notes |
  |---|---|
  | captures/min | point estimate in 3a; ranges later (L1) |
  | **moment recall**: share of MOMENT labels with a keep within ±5 s | point estimate in 3a; keeps_min to keeps_max later (L1) |
  | estimated %/min and hours from full | the 3a two-point line (S28); fitted later (L3) |
  | intent hit rate | over labelled photos the config keeps; shown once INTENT labels exist (voice marks, S54) |
  | model calls/min | ASYNC variants only |
  | unobserved opens % | a warning in 3a; coverage and unobservable shares later (L1, L2) |

- **The Pareto view** plots estimated hours (x) against moment recall (y): the front, the baseline marker, and the validation-split values as the headline (S46). Error bars come later (L1). Clicking a point shows its params, and "Use these params" writes them into the draft.

Ruling S11 (the battery gate, rewritten to keep the 4 h target binding):
- **Unverified plans** may run only in **SHADOW**, or as a **bounded TRIAL**. A TRIAL is LIVE for at most 60 min; then it auto-reverts to the previous effective state. At most 3 trials per plan version. A trial needs a replay estimate ≤ baseline × 1.10.
- **Unrestricted LIVE** requires a **measured** verified LIVE interval (S44) of this plan hash at **≤ 0.42 %/min**.
- **A consequence, stated:** today's baseline measures 0.49, so only plans that are measured as cheaper than the target can go unrestricted LIVE.
- Why: the binding 4 h target must be met on the device, not inside a model.
- Cost if wrong: experiments that cost more than 0.42 %/min live only as trials.

Ruling S44 (one verification rule):
- `tools/battery-run.sh start`, then **≥ 10 min of SHADOW warm-up** (excluded from the fit).
- Then **≥ 45 min of LIVE or TRIAL**, worn and unplugged, with the persistent-logging state recorded.
- Then `pull` and `summary`, and the import into `canvas_calibration` with the phase boundaries.
- The device checklist (§11) uses the same rule.
- Cost if wrong: a verification takes about an hour.

Ruling S16: sweeps cover RT params over replay, objectives go on a Pareto view, and a battery figure counts only after S44.
- Cost if wrong: prompt tuning stays slice 2's A/B.

#### Honest limits of the tuner (shown in the sweep view's header)
1. **It needs moments the user marked** (§6.4). Without them, recall shows "no labels", and the trade always favours fewer photos.
2. **It needs diverse traces.** Under 2 days of validation data, or fewer than 2 states covered, the view warns "likely overfit". A config tuned on sitting says nothing about a bus ride.
3. **Battery is an estimate until it is verified on the device** (S28, S44).
4. **Keeps for views that were never observed are estimates, not facts** (S29). In 3a they are a point estimate with the unobserved share shown, and ranges come later (L1). Fusion is not recomputed in 3a (S30).

### 6.4 Moments, collection and holdout
Ruling S45 (mark-now):
- **The live tap ("I wanted that").** A quick mark records a MOMENT label for the window [t − 10 s, t]. The tap can come from:
  - a glasses gesture: the user binds it in Settings → Capture → "Mark moment gesture". It is stored as an additive `HudSettings.markGesture: String? = null`, a gesture name as a **string**, because adding a `GestureAction` enum value would break old decoders of the gesture map. It defaults to unbound, and binding a gesture that is already used is refused;
  - the phone: an ongoing-notification action "Mark moment", a home-screen button and a Quick Settings tile.
- **Carriage:** the glasses send `pc_mark (v, seq, atElapsedMs, sessionStartMs)`, an additive channel, and the phone maps the time through the clock mapping (S42).
- **Feedback:** the HUD shows a 600 ms "✓ marked" in both modes. A mark never forces a capture; the user has "Capture now" for that.
- **Retroactive marking** on the canvas trace timeline: the user drags a window and adds an optional note. Imported automatically: "Capture now" presses, starred photos, and (optionally) the frames behind memories the user kept.
- **Use:** MOMENT labels feed recall (§6.3). They are deleted with their source (S38).
- Why: without a cheap tap, recall has no data.
- Cost if wrong: one gesture slot.

Ruling S54 (2026-10-22, user: a **voice** mark too, which names an intent label):
- **What the user says:** after the normal push-to-talk tap, "mark that", "mark moment" or "mark this", optionally with an intent phrase: "mark that, I'm on a bus", "mark that, driving", or just "I'm on a plane".
- **What it records:**
  - a MOMENT label for [t − 10 s, t];
  - an INTENT label with the canvas label the phrase names: passenger, driving, aeroplane, working, sitting, talking (the S13 vocabulary).

  The user sees it as **"marked · <label>"** on the HUD for 600 ms and on the phone.
- **The time anchor t** is the push-to-talk start (the glasses' `ListenStart` time, mapped through the trace clock offset). It is not the time of the transcript, so the 1–3 s of speaking and recognition does not shift the window.
- **Routing:**
  - A local deterministic parser runs on the final transcript **before** the ask path (`VoicePipeline.think` → `answerer.answer`).
  - A match records the labels and **never calls a model**.
  - No match goes on to today's ask path unchanged.
  - A clause that names no known intent records the MOMENT only.
  - Only the label names and times are stored, never the transcript (§9: labels are times and enum names).
- **Toggle:** a "Mark moment" voice group in Settings → Voice. It is on by default and turning it off restores the plain ask path.
- **Audio path (already in this app, not re-ported):**
  - The glasses record on **their own mic**: `AudioRecord` with `MediaRecorder.AudioSource.MIC`, 16 kHz mono PCM16. Live Fit found that the Rokid CXR-L audio stream arrives silent (Live Fit spec §2.1).
  - They send 100 ms chunks of 3,200 B, Base64 on `pc_audio`, between `ListenStart` on `pc_cmd` and `pc_listen_end`. This is `glasses/…/voice/PushToTalk.kt`, already adapted from Live Fit.
  - The phone recognises with the **on-device** `SpeechRecognizer` only (API 33, `createOnDeviceSpeechRecognizer`, audio fed through `EXTRA_AUDIO_SOURCE`), never the network recognizer. This is `services/voice/…/android/AndroidOnDeviceStt.kt`.
  - **There is no hotword.** "Hi Rokid" belongs to the Rokid assistant, which holds the mic (uid 1000, seen in the battery runs), and always-on listening would cost battery.
- **Port from Live Fit** (`/Users/debasishkanhar/Desktop/rokid/live-fitness-tracker`, read-only):
  - **Licence and ownership:** the same user owns both repos. All of Live Fit's commits are by Debasish Kanhar, and it is Apache-2.0. Copying is therefore the owner reusing his own code. Each copied file keeps a header line, "Adapted from Live Fit `<path>`@`<commit>`".
  - **Copied** from `services/voice/src/main/kotlin/com/debasish/livefit/services/voice/`, into PCA `services/voice` (`com.rokid.context.voice`):

    | Live Fit file | What PCA takes |
    |---|---|
    | `CommandParser.kt` | the rule-ordered word-boundary matcher pattern. Its rules are rewritten as `MarkCommandParser`: the mark verbs, plus an intent lexicon of phrases → canvas labels (bus/car/taxi passenger → passenger; driving; plane/flight → aeroplane; laptop/desk/computer → working; sitting; talking/meeting) |
    | `CompositeCommands.kt` | `EnglishClauses.split` and `parseUtterance`, as they are, so "mark that and I'm driving" becomes two clauses |
    | `VoiceCommandGroup.kt` | the `VoiceCommandGroup` and `VoiceCommandGate` shape, for the "Mark moment" toggle |
    | `LanguageRegistry.kt` | the `LanguagePack` shape: a locale, a parser and a clause splitter, English packs only |
    | Tests: `CommandParserTest.kt`, `CompositeCommandTest.kt`, `VoiceCommandGroupTest.kt`, `LanguageRegistryTest.kt` | templates for the new tests |

  - **Not copied:**
    - the permissions: PCA already declares `RECORD_AUDIO` in its phone and glasses manifests, with its own onboarding;
    - the connection and protocol (`lf_listen`, `lf_audio`, `lf_listen_end`, `GlassesVoiceBridge.kt`): PCA has its own channels;
    - `LiveVoiceService.kt`: PCA's `VoicePipeline` replaces it;
    - `PushToTalk.kt`, `EnergyVad.kt`, `AndroidOnDeviceStt.kt`, `SpeechPacks.kt` and `PhoneMic.kt`: PCA already has adapted versions;
    - `YesNoParser.kt`: a mark needs no confirmation.
- Why: a spoken label is the cheapest way to get INTENT labels for the intent hit rate and for 3c's week of data, and the audio path already exists.
- Cost if wrong: a misheard intent writes a wrong INTENT label. It can be removed on the timeline, and a MOMENT is still recorded.

Ruling S46 (the holdout split):
- A collection is split **chronologically by day**. The default is tune on days 1–5 and validate on days 6–7 of a 7-day collection. More generally, the last ≈ 30 % of days, at least 1 day, are held out.
- Sweeps score on the tuning days. The Pareto headline and "Use these params" show the **validation** values.
- With fewer than 2 validation days, the view says "no holdout — overfit risk". Labels and traces of the validation days are never shown to the sweep while it is choosing.
- Why: tuning and judging on the same minutes overfits.
- Cost if wrong: a week of collection before the result can be trusted.

Ruling S47 (the cost of recording, budgeted):
- **"Collect for tuning"** is a Developer-mode session. It records while the glasses are worn, for at most 12 h a day and at most 7 days, with a status line in the HUD and the app.
- **Glasses side:** IMU recording buffers 64 KB and flushes about every 53 s. Its budget is **≤ 0.02 %/min** (≤ 5 % of the 0.42 target). It is verified by one S44-style A/B (collection on vs off) before the first collection. If over budget, recording drops to 25 Hz.
- **Storage:** the glasses buffer is capped at 150 MB, and recording pauses when it is full (under the user's 250 MB storage warning). The phone trace store is capped at 500 MB, and the oldest trace is evicted first. Transfer happens only while charging.
- Why: the recorder must not be the reason for a missed 4 h.
- Cost if wrong: fewer samples per second in the traces.

## 7. Freeze, publish, roll back
- **Freeze:** as in the MVP: an immutable version, a hash (S41), the full graph including the `tool.draft` text.
- **Publish:**
  - tiers 1 and 2 publish through the gates below; tier 3 never publishes (S6).
  - A full-graph publish is a **group**: a `groupId` with up to 3 component rows, `capture_plan` (glasses and phone RT) and slice 2's `interpreter` or `prompts` (ASYNC).
- **`capture_plan` gates** (fixed texts):
  1. compile clean;
  2. no tier-3 node;
  3. a DONE exact-hash replay over ≥ 30 min of COMPLETE traces covering ≥ 2 states;
  4. S11 (SHADOW, TRIAL or verified LIVE);
  5. glasses linked and capable (S24).

Ruling S25 (states across the domains):
- **Each component** (glasses RT, phone RT, ASYNC) moves through DESIRED → PREPARED (validated locally) → EFFECTIVE | REJECTED, with a terminal REVERTED per gen. These states are persisted.
- **Activation order:**
  1. the phone validates everything (PREPARED);
  2. the glasses are sent the gen;
  3. **only after `EFFECTIVE` from the glasses** do the phone RT and the ASYNC interpreter activate.
- **Failure before activation:** a reject, or no ack within 10 min, makes the group FAILED. There is nothing to compensate, because the phone halves never activated.
- **A glasses auto-revert** is reported as `REVERTED(gen)`. The phone marks the group reverted, deactivates its phone RT and ASYNC components, and moves the desired state to the previous effective group. It never re-sends a reverted gen.
- **Reconnect:** the phone reconciles the glasses' (appliedGen, effectiveHash, state) against the desired state. It re-sends when the glasses are behind, and adopts a terminal revert.
- Why: the Room transaction alone left mixed configurations.
- Cost if wrong: activation waits for the glasses, a few seconds.

Ruling S26 (three distinct actions):
- **Undo latest publish:** slice 2 S10's stack, made group-aware. The newest active group of any kind is undone as one unit, in the order ASYNC → phone RT → glasses.
- **Restore previous capture plan:** for `capture_plan` only. The newest earlier non-reverted plan becomes desired again, in its own recorded mode, with TRIAL re-bounded.
- **Use settings:** disables the capture overrides. Desired becomes `SETTINGS` on the glasses and the phone RT. ASYNC is untouched.

Where they appear:
- the canvas home shows all three;
- Settings → Capture shows "Capture plan: <name> v<N> (<mode>) · Use settings";
- Diagnostics shows the effective state per component.
- Cost if wrong: none.

**Auto-revert on the glasses** (it acts locally and then reports):
- the plan is rejected;
- over 15 min, opens/min > 1.5 × the replay p95 for the same states;
- the hard ceiling is hit 3 times in 10 min;
- `exprErr` > 10 in a minute;
- a TRIAL reaches its end.

Ruling S12: settings stay the always-valid fallback. Under a live plan, the settings rows the plan resolved show "Frozen in canvas plan vN"; edits there apply after "Use settings" (S27).
- Cost if wrong: one extra tap to fold a setting change into the plan.

## 8. Node catalogue (the user's flow, mapped to code)
| Step | Node | Domain / executor | Maps to | Params (bounds) |
|---|---|---|---|---|
| Head movement detected | `imu.head_motion` (root) | RT_GLASSES / BUILTIN | `GlanceDetector` bout (A3 §3) | `onsetRadS` 0.4–1.0, `releaseRadS` 0.2–onset, `bridgeMs` 100–500, `minBoutMs` 100–800, `minBoutRad` 0.1–0.8 |
| (settle) | `imu.settle` | RT_GLASSES / BUILTIN | SETTLING → glance | `settleMs` 1,000–3,000 |
| Above threshold? | `gate.threshold` or `expr` (guard) | RT_GLASSES / BUILTIN, EXPR | folds into `glance.*`, or a guard | a feature, a comparison, a value |
| (novelty) | `gate.novelty` | RT_GLASSES / BUILTIN | A3 §4 | `noveltyWindowMs` 5–60 s, `noveltyMinRad` 0.1–0.8 |
| Motion from context hub? | `hub.context` (root) → `signal.out ctx` | RT_PHONE / BUILTIN, SIGNAL | `ContextFusion`, `pc_motion_ctx` (wave B §6) | `FusionParams` subset, each within ±50 % of its default; the speed threshold 20–40 km/h; walkingVeto fixed (S32) |
| (receive) | `signal.in ctx` | RT_GLASSES / SIGNAL | glasses `ContextGate` (wave B §9.1) | none |
| Capture frequency by activity | `policy.capture_rate` | RT_GLASSES / TABLE (+ `expr` values) | `ContextCapturePolicy` + `capNow()` | rows REST, VEHICLE_PASSENGER, VEHICLE_DRIVING, FLIGHT: `capPerMin` 1–4, check gaps 5–60 s, `nudges` (DRIVING locked off); WALKING and UNKNOWN locked (S32); no `pause` |
| (rate cap) | `gate.rate_cap` | RT_GLASSES / BUILTIN | `KeyframeGate` cap (A3 §5) | `bigChange` 16–32, `bigTurnRad` 0.5–1.5 |
| Capture image | `capture.image` → binding `photos` | RT_GLASSES / BUILTIN | `SamplingSession` + `KeyframeGate` dedupe | `hamming` 6–16, `ringCompare` last \| all |
| Classify intent | `ai.prompt` seeded from `INTENT_CLASSIFY` | ASYNC / PROMPT | `RoleRunner` (slice 2) | `labels` 2–12, `cadence` hub \| every_photo |
| (feed the hub) | `event.hub_intent` → binding `intent` | ASYNC / EVENT | `FrameIntentSink` → `frame_meta` + fusion | `map` label → `IntentLabel` |
| Route by intent | `route.switch` | ASYNC / BUILTIN | slice 2 | `field`, `cases` |
| Objects / people | `ai.prompt` (seeded) or `vision.detect` | ASYNC / PROMPT, BUILTIN | §8b | §8b |
| Summarise / memorise per intent | `ai.vision_extract` → `memory.write` | ASYNC / PROMPT | `ExtractSave.extract` + the live save tail | `prompt`, `batch` |
| Others | `embed.text`, `tool.call`, `const.table`, `expr`, `py.fn`, `tool.draft` | ASYNC | `EmbeddingService`; actions (preview or proposal); —; —; helper; §4.5 | — |

**Palette categories:**

| Category | Nodes |
|---|---|
| Sensors | `imu.*` |
| Signals/Hub | `hub.context`, `signal.*`, `event.hub_intent` |
| Thresholds/Filters | `gate.*`, `expr` (guard) |
| Policy/Frequency | `policy.capture_rate`, `expr` (value) |
| Actions/Capture | `capture.image`, `tool.call`, `tool.draft` |
| AI/Classify | `ai.*`, `route.switch`, `vision.detect` |
| Memory | `memory.write`, `embed.text` |
| Data | `const.table` |
| Code | `py.fn` |

Every param is tunable and sweepable.

Ruling S13: the user's intent labels are free canvas labels. `event.hub_intent` maps them to wave B's closed `IntentLabel`:

| Canvas label | `IntentLabel` |
|---|---|
| sitting | SITTING_IDLE |
| driving | DRIVING |
| passenger | VEHICLE_PASSENGER |
| aeroplane | FLIGHT_CABIN |
| working | COMPUTER_WORK |

Unmapped labels, such as talking, route prompts but are never fed to fusion.
- Cost if wrong: "talking" doesn't change the rate; an enum value can be added later.

Ruling S36 (one intent scheduler, a lease for ownership; replaces S14's stand-down):
- **Wave B's `IntentSampler` stays the only admission scheduler**, with all of its rules: the `image_intent` opt-in switch, one call in flight, the cadence, frame-time evidence and the 10 min freshness.
- **The lease:** when a **LIVE or TRIAL** interpreter group is healthy and contains `ai.prompt(intent)` → `event.hub_intent`, the sampler **leases** classification to it.
  - The sampler still picks the frame and marks it `intentWanted`.
  - The interpreter classifies that frame inside the frame's batch, with no separate call.
  - If no result arrives within 2 min (a fallback, a failure or a rollback), the lease is revoked and the sampler calls itself.
- **What feeds fusion:** only sampler-picked frames, whatever the cadence.
- **`cadence = every_photo`** classifies every photo for routing only. The publish dialog shows the S7-style call multiplier.
- **SHADOW is read-only:** it never writes `frame_meta` or feeds fusion, and the sampler keeps ownership.
- Why: one scheduler preserves wave B's limits, and fusion is never starved.
- Cost if wrong: other photos route with the newest label, at most 5 min old.

Ruling S35 (the interpreter's live shape, revised from slice 2 §5):
- **Before extraction:** `input.photos`, `ai.prompt` (≤ 4), `route.switch`, `const.table`, ASYNC `expr`, `vision.detect`, `embed.text` and the terminal sink `event.hub_intent`.
- **After extraction:** `gate.local_critic`, `ai.critic`, `memory.write` and `tool.call`.
- **Every path from `input.photos` reaches exactly one `ai.vision_extract`.**
- **Effects are buffered:** persistent effects (the `frame_meta` intent write and its fusion feed, `frame_detection` rows, `ActionProposal`s, stored embeddings) go into a per-batch **EffectBuffer**. It is committed only after the batch's live save succeeds, in the save transaction or right after it, under the same claim and wipe-generation check.
- **Idempotency key:** `(publishId, frameId, nodeId)`. Retries, fallbacks and restarts replace rather than duplicate.
- **On S6 fallback** the buffer is discarded.
- Why: slice 2's grammar rejected the new nodes, and effects written before a fallback would repeat.
- Cost if wrong: intent evidence reaches fusion a few seconds later, after the save.

## 8a. Defined at runtime vs needs a build
| Definable on the canvas, no APK | Needs a Kotlin node type and a build |
|---|---|
| prompts and their schemas | new on-device model executors: a detector, a face embedder, audio |
| `tool.call` wiring to existing executors (reminder, shopping, calendar, note…), with an argument map (output field path → argument, or a constant); live always as a proposal plus confirm | a new tool executor (Slack, Outlook…), via `tool.draft` → build (§4.5) |
| `expr`, `const.table`, `route.switch` cases | new per-sample RT algorithms (tier 3 → promote) |
| param presets ("bus", "desk") | new port types and executor kinds |

Ruling S17: "new prompt, tool or op" means a new instance or wiring. Everything in the right-hand column is a reviewed build, and the canvas's fixed reason is "needs an app update: new executor".
- Cost if wrong: none.

## 8b. Person and object detection after the intent step (slice 3c)
Ruling S56 (2026-10-22, user): detectors (tiers a and b) and the recognition rules come **last, in 3c**. They start only after the user has used the 3a build for about a week with "Collect for tuning" on, and that week's traces and marks are the tuning data (C6).
- **No detector work happens before then.** This includes not promoting tier (a) as a published draft. An ad-hoc tier (a) prompt on slice 2's canvas stays possible as a private experiment, but it is not scheduled.
- Recognition (tier c) stays unbuilt. S20 is a list of requirements for its own later spec.
- Why: the user wants real usage to shape what is detected.
- Cost if wrong: "who and what" stays inside the extract prompt for a week longer.

| Tier | Node | How | Cost | Default |
|---|---|---|---|---|
| a | `ai.prompt` "List objects and people" | today's `ai.prompt`. Its output is the normalised `DetectionResult` (S37). The prompt forbids names and identifying traits | none; possible as a private draft, scheduled in 3c (S56) | draft |
| b | `vision.detect` | ONNX Runtime 1.31 on the phone (the Nomic path). The model comes from the catalog with a SHA check. Params: `classes`, `scoreMin` 0.2–0.9, `nmsIou` 0.3–0.7, `maxDet` 1–50. About 100–300 ms of CPU per photo under `PhonePowerPolicy` | one executor and a build | off until downloaded |
| c | `vision.recognize` (identity) | biometric | **not in slice 3** (S20) | not registered |

Ruling S18: tier (a) publishes cheapest **merged into the per-intent extract schema**, which costs 0 extra calls. Both shapes should be A/B tested first.
- Cost if wrong: a longer extract prompt.

Ruling S19: the tier (b) model must have a permissive licence (Apache-2.0 or MIT, for example EfficientDet-Lite or SSD-MobileNet). No AGPL YOLO. A probe picks the model (≤ 20 MB, p95 ≤ 300 ms).
- Cost if wrong: weaker detection; the model can be swapped through the catalog.

Ruling S37 (detection data, normalised):
- **`DetectionResult`:** `{objects:[{label, count, score?, box?}], people:{count, activities:[label]}}`. There are no identity fields; the schema forbids names.
- **Both producers go through one `DetectionSink` adapter:**
  - the separate node writes directly;
  - the merged extract schema's `detections` field is carried by an additive `ExtractedBatch.detections: Map<frameId, DetectionResult>?` (null by default).
- **Rows in `frame_detection`:** (frameId, producerKey = `nodeType@version + modelId + promptHash`, kind OBJECT | PEOPLE | ACTIVITY, label, count, score NULL, box NULL, at).
- **Instance identity:** per (frameId, producerKey), all rows are replaced in one transaction, inside the EffectBuffer commit (S35). Retries and re-runs are therefore idempotent, and a model change gives a new producerKey alongside the old one.
- Why: counts and activities were being lost, and replacement was undefined.
- Cost if wrong: one adapter.

Ruling S21: detections are stored in **`frame_detection`**, not as memory facts. They become facts only through the extract prompt and the normal save tail. They are deleted with their frame and never exported, and only a live publish writes them.
- Cost if wrong: one join for recall.

Ruling S20 (recognition is deferred; requirements recorded for its spec):
- local-only, opt-in, off by default;
- enrolment of named people the user chooses, with a consent confirm;
- faces that are not enrolled are compared in memory and discarded;
- no export or backup;
- Forget person and Delete all cascade;
- its own threat model, and its own next-free migration.
- **Information-flow boundary** (codex #25), a requirement for that spec: identity-derived data (embeddings, match ids, **and any recognised name**) must not reach any of these sinks unless that spec explicitly allows it:
  - `context_sources`;
  - captions or facts;
  - prompts sent to any cloud model;
  - traces and labels;
  - tool arguments;
  - helper jobs;
  - logs;
  - exports.

  The data carries deletion provenance to every derived row. Tests must prove that identity results cannot reach the prohibited sinks, and recognition cannot register until those tests exist.
- Why: biometric data is the highest-risk data the app could hold, and a name converted to plain text could bypass the rules.
- Cost if wrong: "who" stays "a person".

## 9. Security
- **The canvas server:** slice 2's HTTPS-only LAN, the pinned WebView, bearer tokens, the Host/Origin rules, CSP and the escalating lockouts all apply.
  - New routes: `/api/plans/*`, `/api/replays/*`, `/api/sweeps/*`, `/api/traces/*`, `/api/labels/*`, `/api/helper/*`.
  - Helper tokens are limited to `/api/helper/*`, their own jobs and their granted capabilities (S39).
  - Trace routes return aggregates, never raw IMU. The only exception is the numbers-only golden I/O inside a user-started bundle.
- **The helper:** S22 (isolation) and S39 (pairing, capabilities, leases, retention). The canvas shows "Helper connected (laptop) · caps" with a Disconnect button.
- **Nothing generated runs on the devices unreviewed** (S6, S40, S48). Tier-2 translations are data and pass the same validators.
- **No code execution on the devices:**
  - plans are data, validated on the phone and again on the glasses;
  - `expr` runs in a switch-based evaluator with no reflection and no `eval`;
  - Python and NL text are only forwarded;
  - the canvas JS keeps `script-src 'self'`, and the vendored libraries are local (§13).
- **Tools:** previews in experiments; live only as `ActionProposal` plus confirm.
- **Privacy:**
  - hub traces carry enum names and rounded speed only;
  - IMU traces are sealed;
  - labels are times and enum names;
  - all of it is wiped by Delete all, never exported (except the numbers-only bundles the user starts) and logged as counts;
  - helper jobs can carry photos, the same exposure as the paired browser, and the pairing screen warns about it.

Ruling S38 (the deletion graph):
- **Provenance:** every derived artifact records its sources, as frameIds and/or a (traceId, time range).

| Deleted | Also deleted |
|---|---|
| a frame (Forget, retention, Delete all) | `canvas_run_item`, `canvas_score`, `frame_detection`, its INTENT labels, the MOMENT labels imported from it (starred photo, Capture now); replays and sweeps that used it are marked INVALIDATED with their results deleted; pending helper jobs that carry it are voided, and the helper deletes their inputs |
| a time range | the trace events and labels in the range; **sealed IMU chunks are rewritten without the range, or deleted when fully covered**; the replays, sweeps and promotion bundles derived from it are invalidated |
| a trace | its tombstone blocks late chunks (S42) |

- **What is kept:** `canvas_calibration` rows are counts only and stay, unless Delete all.
- **Fences everywhere:** the wipe generation and tombstones are checked at **every result commit**:
  - run items, detections and the EffectBuffer;
  - helper results;
  - replay and sweep results;
  - trace chunk acceptance.
- Why: late results must not recreate deleted data.
- Cost if wrong: one provenance column per table.

## 10. Storage
Ruling S15 / S43 (migrations):
- **Migration A (3a task A8):** all slice 3 tables that exist at that time, at the **next free version** when A8 merges. Wave B Task 8 is v5 and lands on its own, and slice 2 expects v6, so expect v7. A8 cannot merge before both.
- **Later additions get their own next-free migration:** `frame_detection` (3c task C4) expects v8, and recognition gets one later. `canvas_helper_job` is in migration A, because it is a plain table that 3b only starts writing.
- **Never edit an existing migration.**
- Cost if wrong: a rename and a schema JSON.

**Migration A:**
- `canvas_publish` gets `groupId`, `component`, `planJson`, `planHash`, `gen`, `state`, `trialEndsAt`;
- `canvas_trace` (with the manifest JSON, status, wipeGen, tombstone);
- `canvas_trace_event`;
- `canvas_replay` (with `sweepId`, `paramsJson`, `class` EXACT | COUNTERFACTUAL, `invalidated`; nullable range columns reserved for L1);
- `canvas_sweep` (with its split days);
- `canvas_label` (MOMENT | INTENT, source, provenance);
- `canvas_calibration` (plan hash, settingsRev, mode, phase boundaries, measured and estimated values);
- `canvas_helper_job` (lease, capability, state).

**Migration B:** `frame_detection` (S37).

## 11. Testing
Each item belongs to the slice that builds its code:
- **3c:** the `PlanEvaluator`, `expr` and detection items;
- **3b:** the helper, `patchview.js` and CodeMirror items;
- **3a:** everything else, plus:
  - the move-only check (S50): A3's moved tests pass unchanged;
  - the voice-mark parser goldens (S54): mark verbs, the intent lexicon, composite clauses, no-match falls through to ask, the toggle;
  - the manifest-lite completeness and checksum cases (S42).

The range, coverage and fusion-recompute replay tests wait for L1 and L2.

- **Pure JVM** (`:core:capture`, `:services:ai`):
  - the `PlanValidator` refusals: pause, cap 0, MOTION, DRIVING nudges, editing WALKING or UNKNOWN, context features in a guard, `expr` limits, gaps outside 5–60 s;
  - the `PlanEvaluator` truth tables, windows against hand-computed rings, overflow and NaN → error rules, max cost;
  - determinism goldens shared between the JVM and Android.
- **Goldens:**
  - the §1 example graph validates per domain with its bindings, and compiles to an exact `CapturePlan`;
  - the identity plan resolves to the settings values (S27);
  - schema-1 upgrade fixtures (S41).
- **Parity (S29):** an exact decision replay of the golden traces reproduces the recorded decision sequences bit for bit. The glasses' plan path with the identity plan equals the no-plan path.
- **Liveness (S31):** with an all-false guard, the reserved opportunity still fires within 60 s whenever eligible. It does not fire while paused, not worn or heat-stopped.
- **Precedence (S23):** Capture now during a wipe, heat STOP and camera-busy.
- **Walking (S32):** a vetoed vehicle reading gives the full-rate row, and SPEED disables the veto.
- **Protocol (S24, S25):**
  - old glasses ignore the channel;
  - stale, duplicate and unsupported gens;
  - a restart with a persisted plan and a trial end;
  - group activation and compensation, and reconcile on reconnect.
- **Replay:**
  - 3a: point-estimate keeps and the unobserved share, the two-point battery line, the "fusion changes are not replayed" warning, holdout separation (S46);
  - later: fusion recomputed with changed params (S30, L2), bounds and coverage (S29, L1).
- **Deletion graph (S38):** Forget a frame or a time range while a helper job, a replay or a trace transfer is in flight.
- **Detection (S37):** both producers through the sink, idempotent replacement, cascades, no writes from experiments.
- **GraalJS:** the pure modules (slice 2 §4's `flowadapter.js`, `graph.js`, `validate.js`, `keys.js`) gain:
  - domain lanes, binding validation and cross-domain refusal reasons;
  - the `expr` text ↔ AST round trip;
  - `pareto.js` (the front, the ranges, the split, the Chart.js datasets and options);
  - `paramform.js` extensions (enum, step, `maxLen`, `table`, sweep marks);
  - `patchview.js` (unified-diff line classification);
  - the mark-timeline model.

  The React Flow, Chart.js and CodeMirror views are covered by the manual checklist, as in slice 2.
- **Helper (`pytest`):**
  - the container flags (`--network none`, the mounts, the limits, kill on cancel);
  - capability and lease enforcement;
  - the oracle rejecting patches that touch the harness;
  - the held-out cases;
  - the licence and path allowlists and the injection scan;
  - the DSL translator;
  - the CLI detection matrix.
- **The device checklist** follows the S44 rule:
  - identity plan parity;
  - SHADOW would-open counts within ±5 % of the exact replay of the same trace;
  - TRIAL → auto-end;
  - forced over-ceiling → auto-revert → each of the three S26 actions;
  - the mark gesture, the notification mark and the voice mark with an intent phrase (S54);
  - the collection overhead A/B (S47);
  - a verified LIVE interval reported against 0.42 %/min.

## 12. What slice 3 does not do
- No device execution beyond tiers 1 and 2.
- No on-device Python.
- No auto-merge or auto-install.
- No grid sweeps that make model calls.
- No recognition.
- No hotword or always-on listening: voice marks follow a push-to-talk tap (S54).
- Nothing on the laptop or LAN in DEPLOY mode (S52).
- Slice 3a has no expression nodes, no Python, no NL tools and no detectors (S51, S55, S56).
- No end-user canvas.
- No money figure.
- `TreeMotionModel` as `imu.model` comes later.

## 13. Open-source UI pieces
Ruling S49: no custom UI where a mature open-source library exists.
- **The editor** is exactly **slice 2 §4 as amended for React Flow** (`@xyflow/react` 12.12.0 UMD, React 18.3.1 and `htm`, vendored, per `2026-10-21-canvas-editor-oss-evaluation.md`). Slice 3 adds only node kinds, lanes and colours, and the bindings, through `flowadapter.js`. It does not redefine the editor.
- **Vendoring** follows slice 2 §4.1 exactly: pinned versions, fetched once with `curl` from jsdelivr, flat `vendor-*` names (the server's static path rule `^/[a-z0-9_-]+\.(html|js|css|svg)$` has no folders), SHA-256 and byte counts in `vendor-lock.txt` enforced by `VendorLockTest`, licence texts in `vendor-licenses.txt`, and `vendor-*` excluded from the GraalJS module parse and from the own-code scans. The libraries (checked 2026-10-21; none contains `eval(`, `new Function` or injected `<style>`):

| Need | Pick | Licence | Size (measured) | Why / rejected alternatives |
|---|---|---|---|---|
| Pareto and tuner charts | **Chart.js 4.5.1** `dist/chart.umd.min.js` → `vendor-chart.js`, SHA-256 `48444a82d4edcb5bec0f1965faacdde18d9c17db3063d042abada2f705c9f54a` | MIT | 208,522 B raw, 70,522 gzip | scatter/bubble, tooltips, click handling, offline UMD, no build. uPlot is time-series-first; Vega-Lite is about 800 KB and needs `vega-interpreter` under our CSP. The front, ranges and datasets come from the pure `pareto.js`; the sortable table is the accessible equivalent of the canvas chart |
| Patch and promotion diffs | **none: a pure `patchview.js`** (classify unified-diff lines by `+`, `-`, `@@`, context; render through `h()`) | — | — | diff2html 3.4.56 (MIT) returns HTML strings, so showing them needs `innerHTML`, which the canvas bans (`CanvasAssetsTest`); the patches are model-written, so text-only rendering is the safer path. `parse-diff` 0.12.0 (MIT) is CommonJS only. Prompt and JSON diffs keep the server `LineDiff` + `diffview.js` |
| Inspector param forms | **none**: slice 2's pure `paramform.js`, extended to the `ParamSchema` subset (enum, step, `maxLen`, `table(rows, cols)`, sweep marks), rendered by `floweditor.js` | — | — | `@rjsf/core` 6.11.0 needs a bundler (about 8 extra UMD globals) and ajv, which compiles with `new Function` (blocked by CSP); JSON Forms has the same ajv problem; `@json-editor/json-editor` 2.17.3 is 537 KB and injects `<style>` |
| `py.fn` source editor (≤ 16,000 characters) | **CodeMirror 5.65.21** `lib/codemirror.js`, `lib/codemirror.css`, `mode/python/python.js` → `vendor-cm5.js`, `vendor-cm5.css`, `vendor-cm5-python.js`; SHA-256 `e98aac5ffa07bae58acd4ff07c4293059f8921c0ae0eba506929d8c6f41c9288`, `eb494ea972d2661ef86f7f6ac656dd6786d721e49c9c1b46e1eb967e4b6f9bf3`, `19a59ca387addb04e95002c9adbe2b8c231427ce49369ac537107e3088a6947c` | MIT | 425,759 B raw, about 113 KB gzip | line numbers, Python highlighting, indentation, keyboard-accessible; UMD with external CSS. CodeMirror 6 is ESM-only across about 7 packages (needs a bundler); Ace injects styles and uses workers |
| `expr` editor | a plain textarea + the server parse + inline problems | — | — | the programs are at most 32 AST nodes; an editor library adds nothing |

- **Size and security:** slice 3 adds about 634 KB raw (about 184 KB gzip) on top of slice 2's 351 KB. Every library is local under `script-src 'self'`, and none makes a network call.
- Why: the user's direction, and both picks are MIT, offline and buildless.
- Cost if wrong: a swap touches one view module each.

## 14. Build plan (re-cut 2026-10-22, in agent-hours)
The task lists for each slice, with their dependencies and streams, are in the **Re-cut (2026-10-22)** section at the top. This table maps the r2 tasks onto them, so the two stay consistent.

**Preconditions:**
- 3a:
  - A3 merged into `int/2026-10-21`;
  - the merged wave B parts (hub core, `ContextGate`, fusion);
  - slice 2 Tasks 3, 6 and 11a–11e (React Flow);
  - for merging A8: wave B Task 8 (v5) and slice 2's v6 merged first.
- 3b: slice 2 Task 5 and TLS. B4 needs wave B Task 9.
- 3c: about 7 days of use of the 3a build with "Collect for tuning" on, and the user's brainstorm of the expression language.

Estimates are Sonnet implementer agent-hours, including one Opus review and one fix round. Wall-clock assumes parallel worktree streams and the machine's 2 concurrent Gradle slots; helper builds in containers count against the same slots.

| r2 task | What | Re-cut | agent-h (r2 → r3) | Notes |
|---|---|---|---|---|
| T1 | `:core:capture` extraction | **3a** A1 | 3 → 2 | move-only of the pure pieces replay needs; everything else is wrapped (S50) |
| T2 | NodeTypes v2, S41, S34 | **3a** A2 | 5 → 4 | `EXPR` kind registered, hidden until 3c |
| T3 | `PlanEvaluator` (`expr`) | **3c** C1 | 5 → 5 | after the brainstorm (C0, 2 h) |
| T4 | `CapturePlan`, bounds, validator, invariants | **3a** A3 | 5 → 3 | no `exprs`/`tables`; 8 KB cap (§17.2) |
| T5 | `PlanCompiler` + golden | **3a** A4 | 4 → 3 | threshold folds only; the rest is "needs an expression node (3c)" |
| — | `expr` in compiler, plan and glasses | **3c** C2 | (in T5, T6) → 4 | split out of T5/T6 |
| T6 | Glasses plan apply | **3a** A5 | 7 → 5 | applies through the existing classes' params; no `exprErr` rule yet |
| T7 | Protocol | **3a** A6 | 3 → 3 | |
| T8 | Phone RT apply + lease | **3a** A7 (2) + **3b** B4 (2) | 4 → 4 | lease waits for wave B Task 9 |
| T9 | Traces + migration A | **3a** A8 | 9 → 5 | manifest-lite; marks moved to A9 |
| — | Mark-now incl. voice | **3a** A9 | (in T9) → 4 | + Live Fit command-layer port (S54) |
| T10 | `CaptureSimulator` | **3a** A10 | 8 → 5 | exact parity + point estimates + two-point line; L1–L3 later |
| T11 | Interpreter v2 + EffectBuffer | **3b** B3 | 6 → 5 | |
| M1 | Integration milestone | **3a** M1 | 2 → 2 | |
| T12 | Publish groups, S25, S26, S11 | **3a** A12 | 7 → 6 | ASYNC component is slice 2's publish in 3a |
| T13 | Sweeps, holdout, Pareto API, S44 import | **3a** A11 | 5 → 4 | fusion params not swept (S30) |
| T14 | Deletion graph + fences | **3a** A13 (3) + **3b** B8 (part) | 4 → 3 + part | |
| T15 | Helper part 1 + `py.fn` | **3b** B1 (6) + B5 (5) | 8 → 11 | + DEV/DEPLOY modes, image builds, `doctor` (S52) |
| T16 | Promotion | **3b** B6 | 8 → 6 | `expr` translator path moves to 3c (C3) |
| T17 | `tool.draft` NL build | **3b** B2 | 7 → 6 | first in 3b, one evening once B1 is in (S55) |
| T18 | Detection tier (a) + migration B | **3c** C4 | 5 → 5 | after the week of use (S56) |
| T19 | `vision.detect` | **3c** C5 | 6 → 6 | |
| T20 | Canvas UI | **3a** A14 (6) + **3b** B7 (3) + **3c** C3 (4, with the translator) | 9 → 13 | CodeMirror goes with B5 |
| T21 | Device checklist | A15 (3) + B8 (part) + C7 (3) | 3 → 6+ | one per slice |
| — | Week-data tuning | **3c** C6 | — → 3 | |
| — | Later list L1–L4 | optional, 3c or after | — → about 9 | see the Re-cut section |

**Totals:**

| Slice | agent-h | Critical path (agent-h) | Wall-clock | Streams |
|---|---|---|---|---|
| **3a** | about 60 | about 35 (A1 → A3 → A6 → A8 → A10 → M1 → A12 → A14 → A15) | **about 40 h** | 4 (A–D) |
| **3b** | about 36 | about 23 (B1 → B5 → B6 → B7 → B8) | **about 25 h**, or about 18 h if B1 runs during 3a | 3 (E, F, B) |
| **3c** | about 32 (+ about 9 Later) | about 18 (C0 → C1 → C2 → C3 → C7) | **about 20 h**, starting ≥ 7 days after the 3a install | 3 |
| All | about 128 | | | |

**Notes:**
- **Earliest value:** right after 3a, the user can replay a recorded day, sweep and publish a SHADOW or TRIAL capture plan, and mark moments by tap or voice. NL tools (B2) follow as the next evening when B1 ran during 3a.
- **Not included:** the wall-clock figures leave out user wear time (M1, and about 1 h per S44 verification), the week of use before 3c, and merge waits on Room versions.

## 15. Open for the user
Each ruling can be overturned. Its cost is next to it above.
1. S1 / S2 / S34: the RT graphs compile to tier-1 params (plus tier-2 `expr` from 3c); three rooted domain graphs linked by `photos`, `ctx` and `intent` bindings.
2. S3 / S23: the invariants live outside any graph; Capture now is exempt only from novelty, guards, gaps and caps.
3. **S31: under any plan, a reserved timer opportunity (a camera open) comes at least once a minute while eligible, and plan check gaps are 5–60 s.** Wave B's 300 s gaps then apply only in settings mode.
4. **S32: the WALKING and UNKNOWN rows are locked to full rate. SPEED (≥ 25 km/h) still overrides the walking veto.**
5. S4 / S17: user-made nodes are instances or wirings; new executors need a build.
6. S5 / S33 / **S51: the expression language is deferred to 3c, and you brainstorm it first (C0).** The r2 draft limits are the starting point for that session: (32 nodes, 6 programs, 4 windows, 3 tables, ≤ 2 evaluations/s, a guard error keeps the candidate).
7. **S22 / S52: the helper needs Podman or Docker (3b).** DEV mode = canvas server on the phone + the host `pca-helper` script and its job containers on the laptop; DEPLOY mode = the frozen plan on the devices, no LAN server, nothing on the laptop; one APK pair for both. CLI generation gets network only to its API host and only its own API credential. Builds run offline.
8. S39: out-of-band fingerprint pairing; explicit `py_exec` / `promote` capabilities; an app switch plus a per-job confirm; 7-day laptop retention.
9. S6 / S6a / S40: tier 3 is never published; the oracle belongs to the helper, holds 20 % of cases back, and reports "agreement on N cases".
10. **S48 / S55: NL tools come first in 3b (one evening once the helper core B1 exists; B1 may run during 3a). They are drafts with stand-ins, built on a `canvas-tool/<id>` branch, and handed over as a diff, test results and a debug APK. You merge and install; nothing is automatic.**
11. S7 / **S50: the capture-half nodes wrap the existing Kotlin as param presets; only the pure decision code that replay needs moves into `:core:capture`, move-only; no rewrite.**
12. S8 / S13 / S36: the policy is keyed by `ContextState`; free labels map to `IntentLabel`; one intent scheduler, with a lease to a healthy live interpreter (3b, B4, after wave B Task 9).
13. S9 / S24 / S25 / S26: a plan channel with gens and acks; activation waits for the glasses; three separate rollback actions.
14. **S11: unverified plans run only in SHADOW or a 60-min TRIAL. Unrestricted LIVE needs a measured ≤ 0.42 %/min. Today's 0.49 baseline means only plans measured cheaper qualify.**
15. S12 / S27: a published plan is fully resolved and frozen; settings edits apply after "Use settings"; MOTION wake is settings-only.
16. S28 / S44: **3a uses a fixed two-point line (0.49 %/min at 2.4 s/min, 0.6 at 7 s/min), no refits; the fitted model is Later (L3).** verification is ≥ 10 min SHADOW plus ≥ 45 min LIVE, worn.
17. **S29 / S30 / S53: 3a reports point-estimate keeps with an "unobserved opens %" warning and replays the recorded context states (fusion params not swept). Ranges (L1) and fusion recompute (L2) are Later. Deferral is safe because estimates gate only TRIAL entry; LIVE needs a measured run.**
18. **S45 / S54: you choose a glasses gesture for "Mark moment"** (unbound by default; also a phone notification action and tile); marks label the previous 10 s. **Voice: after a push-to-talk tap, "mark that" with an optional intent phrase ("I'm on a bus") records a MOMENT and an INTENT label without calling a model; it uses Live Fit's command layer (copied, same owner, Apache-2.0) on the existing glasses-mic + on-device recognizer path. Confirm the intent lexicon and that the voice group is on by default.**
19. S46: holdout by day (tune days 1–5, validate days 6–7).
20. **S47: "Collect for tuning" sessions of up to 12 h a day for 7 days, a ≤ 0.02 %/min budget, 150 MB on the glasses and 500 MB on the phone.**
21. S10 / S42: traces only on request, shipped while charging, with **manifest-lite** in 3a (checksums + completeness, whole-trace retry); per-seq resume and 60 s clock samples are Later (L4).
22. S15 / S43: migration A (3a, A8) at the next free version after wave B Task 8 (v5) and slice 2 (v6), so v7 expected; `frame_detection` in its own migration B (3c, v8 expected).
23. **S56: detectors and the recognition rules are last (3c), after about a week of use whose data tunes them; no work before then.** S18 / S19 / S21 / S37: detection (a) merged into the extract schema if published; (b) an ONNX detector with a permissive licence; `frame_detection` with normalised counts and activities.
24. S20: recognition deferred, with the information-flow boundary as a requirement for its spec.
25. S38: the provenance deletion graph, including rewriting IMU chunks on a time-range Forget.
26. S16: RT-only sweeps, ≤ 200 configs and 4 params.
27. S49 (amended with slice 2 S21/S22): Chart.js 4.5.1 and CodeMirror 5.65.21 vendored with flat `vendor-*` names and hash pins; patch diffs through a pure `patchview.js` (no diff2html); no forms library (`paramform.js`); the editor is slice 2's React Flow.
28. Still open from earlier specs: stop capture in flight (wave B §12.3); the wave B fusion rulings; slice 2 S0–S20; the Codex reviews of the plans.
29. **Re-cut estimates (2026-10-22): 3a about 60 agent-h (about 40 h wall-clock on 4 streams); 3b about 36 agent-h (about 25 h, or 18 h if B1 runs during 3a); 3c about 32 agent-h (+ about 9 for Later items; about 20 h, starting at least 7 days after the 3a install).**
30. The Later list L1–L4: take any of them in 3c only if the week's TRIAL results show the estimates misleading.

## 16. User rulings (2026-10-22, later)
1. **Mark moment gesture:** the glasses D-pad tap while OUR app is in the foreground, plus the voice mark. Voice mark is ON by default (user: easier and natural). The intent words list still needs the user's confirmation (proposed set stays in §15).
2. **Foreground-but-hidden glasses app (new 3a task):** the user wants our app to stay the foreground app on the glasses (instead of the Hi Rokid home screen) with the screen hidden after inactivity, woken by a D-pad press or a voice action. Build on the existing minimised/icon UI mode (`START_MINIMISED`, `ui_mode=icon`, phone shows "Minimised on glasses · capturing"); first read how it handles D-pad key events and wake today, check the A3 battery effects (screen-off must not add wakeups), and keep the Rokid wear/home behaviour unchanged. Estimate with the task table when scheduled.

## 17. User rulings (2026-10-22, expression nodes)
1. A guard MAY read the context state (ctx) and drop glances based on it (not motion-only). This replaces the "guards read motion features only" rule in §3/§4.3; the non-removable invariants (≥1 eligible capture opportunity per minute via the timer path, walking veto, hard caps) still bound what a guard can do — re-check S31/S32 wording when 3c is written.
2. The CapturePlan size cap is raised from 4 KB to 8 KB (planJson ≤ 8,192 bytes; glasses reject larger as too_big). Update §4.1/S-rulings that say 4 KB when 3a/3c are scheduled.
3. Expression evaluator: see specs/2026-10-22-design-decision-brief.md (pending); user asked what the 'JavaScript evaluator' is — answered: a second copy of the evaluator in the canvas web page so the inspector can check/try expressions instantly; alternative is one Kotlin evaluator only with the canvas calling the phone to check.

## 18. User ruling (2026-10-22): structured steps first, text language later
The user does not want to write Kotlin; expressions are NOT Kotlin (Kotlin only hosts the single authoritative evaluator). First version of expression nodes = a FINITE CATALOGUE of structured, form-edited steps built in the canvas inspector (paramform.js): compare (signal vs number: > < >= <= ==), set membership (ctx/enum in a chosen list), logic (and/or/not over steps), basic arithmetic + clamp, window aggregates (max/mean/fraction over the last N s, waiting for full warm-up), choose (if step then A else B), over a fixed signal list (gyroMax, still fraction, ctx, ...). The canvas BUILDS THE AST DIRECTLY: no parser in the first slice, one Kotlin evaluator, same cost caps and 8 KB plan cap, numbers sweepable by the tuner. The pcx text syntax (CEL subset) becomes an optional later 'power mode' emitting the same AST. Re-estimate with the 3c task table (the 15 h first slice shrinks: no parser). The JS 'try it' evaluator stays out of the first slice.
