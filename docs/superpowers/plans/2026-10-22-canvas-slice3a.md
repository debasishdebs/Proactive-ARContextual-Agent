# Experiment canvas, slice 3a: capture-half graph, lean replay and tuning, mark-now, publish — implementation plan (round 3: 3a-CORE and 3a-LATER)

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development (recommended) or superpowers:executing-plans. This is a **lean plan**: exact interfaces, values, behaviour bullets and named tests, not full code. Every code task is TDD: write the named tests, see them fail (RED), implement, see them pass (GREEN), commit. Where a task lists **mutation REDs**, the implementer shows each named test failing under that mutation before reverting it, and records this in the commit body. Opus writes specs and reviews. **Sonnet implements.** One Sonnet implementer per task, one agent per worktree.

**Goal:** the user edits the capture half of the slice 3 §1 flow on the canvas (head movement → threshold → hub motion → photo rate by activity → capture). The user replays a recorded day against the edited graph, sweeps its params, reads a Pareto chart (estimated hours against moment recall), and publishes the result as a frozen `CapturePlan` in SHADOW or a 60-min TRIAL. Unrestricted LIVE is allowed only after a measured run at ≤ 0.42 %/min for the same configuration. Moments are marked with a glasses tap, from the phone, or by voice ("mark that, commuting"). The ASYNC half stays slice 2's interpreter.

**Architecture:**
- **The capture-half nodes wrap the existing Kotlin** (S50): `GlanceDetector`/`GlanceParams`, `KeyframeGate` caps and dedupe, `ContextPolicies` rows, `FusionParams`.
  - A node compiles to a param block that these classes already accept.
  - One staged, immutable configuration is activated at a safe boundary.
- **The pure decision code moves** into `:core:capture` as a move-only commit, together with the pure plan receiver, so the glasses, the phone tests and the replay simulator run the same code.
- **The plan:**
  - `PlanCompiler` (`:services:ai`) produces a `CapturePlan`: an immutable **config**, identified by `configHash`, plus a **meta** block derived from replay (S60).
  - It is canonical JSON, ≤ 8,192 bytes, and travels on an additive channel with durable generations, acks and a recovery table.
- **The glasses** re-validate and apply the plan as SHADOW (counts only), TRIAL (LIVE for ≤ 60 min, enforced locally on a monotonic clock) or LIVE, and auto-revert on fixed rules.
- **The plan changes only timer-driven photo attempts** (S84). It never changes:
  - sensor sampling, glance detection, `MotionModel`/`MotionClassifier` cadence, `ContextFusion` updates or hub intake;
  - glance-triggered or manual captures.
- **The reserved timer photo attempt** is an invariant in every mode:
  - at least one camera open every **60 s** in UNKNOWN or WALKING;
  - at least one every **300 s** in rest-like states.
- **Replay** runs recorded glasses IMU traces (frozen schema, clean start boundary, pre-roll) and phone hub traces through `:core:capture` and `core:model`:
  - exact parity, which recomputes the scheduler;
  - counterfactual replay with point-estimate keeps;
  - the fixed two-point battery line;
  - grid or seeded-random sweeps, a holdout by day, and a Pareto API with source provenance.
- **The UI** extends slice 2's React Flow editor: lanes, bindings, param forms, replay and sweep views, a Chart.js Pareto chart (vendored, flat `vendor-chart.js`, hash-locked), a mark timeline, and publish and rollback.

**Spec (source of truth):** `docs/superpowers/specs/2026-10-21-canvas-slice3-e2e-configurator-design.md` r3:
- the Re-cut (S50–S56) and the Later list L1–L4;
- §3 (S3, S23, S31 as amended by S83, S32);
- §4.1–§4.2, §5, §6, §7, §8, §9–§11, §12–§13, §14, §15;
- **§16–§18 and the 2026-10-22 rulings S83–S85 below, which win over older text.**

**Also read:**
- `docs/superpowers/specs/2026-10-22-design-decision-brief.md` §2;
- `docs/superpowers/specs/2026-10-22-hidden-foreground-and-mark-moment-design-options.md` §4–§6;
- the Codex plan review `.superpowers/codex/slice3a-plan-review.md`.

## Round 3 (2026-10-22): slice 3a-CORE and slice 3a-LATER (binding; overrides the round 2 task table and schedule)
The user ruled that 103.5 agent-h is too long and that wall-clock matters, so slice 3a is cut in two:
- **3a-CORE** (about **66 agent-h**) is the minimum safe path to what the user asked for.
- **3a-LATER** (about **27.5 agent-h**) is everything else.

Task numbers stay stable. Each task heading below carries `[CORE n h]`, `[CORE, trimmed n h]` or `[LATER]`, and **the core scope column in the table below wins over that task's round 2 text.** CORE and LATER together come to about 93.5 h, against round 2's 103.5. The saving comes from consolidations: a time-based listen join, whole-trace deletes and a table view instead of a chart.

**Why deferral is safe:**
- In CORE, **unrestricted LIVE is refused outright** with `"LIVE needs a verified measurement (slice 3a-LATER)"`. A plan can only run as SHADOW, which by construction cannot change real capture (S67), or as a TRIAL that the glasses end themselves after at most 60 min, including after a reboot (S59).
- Everything deferred either gates unrestricted LIVE (verification, calibration, Pareto, recall and holdout) or adds convenience (transfer over the link, entry points, timeline drag).
- None of the deferred items weakens an invariant, deletion, backup or privacy.

**Rulings folded in this round:**
- **S83a:** WorkRest resting while UNKNOWN counts as "at rest" (the 300 s bound), as the round 2 default had it.
- **S83b:** in settings mode the reserved attempt may add an occasional open at edge cases (heat-SLOW, camera bind back-off). This is accepted.
- **S85a:** the final mapping is reading and writing → `COMPUTER_WORK`; travelling → `VEHICLE_PASSENGER`; **riding → `OTHER`** (a two-wheeler rider may be the driver, so never guess). The "can you <command>" and "could you <command>" exceptions are kept.

### Core task table
| # | Task | Core scope (what is in; the rest goes to LATER) | Stream | Depends on | agent-h |
|---|---|---|---|---|---|
| 1 | Freeze the pre-change goldens | as written | C | base | 1.5 |
| 2 | `:core:capture` move-only, module deps | as written | C | 1 | 2.5 |
| 3 | NodeTypes v2, domains, bindings, v2 route | capture-half types, S41, S34, signal instance domains. **The ASYNC projection and the full-graph test are LATER** (a core pipeline publishes only its `capture_plan`; its ASYNC half keeps slice 2's own publish path) | B | base | 3 |
| 4 | `CapturePlan`, validator, resolver, `PlanReceiver` | as written. `meta.expect` stays as an optional field that is unused in core | A | 2 | 3.5 |
| 5 | Wire | `pc_capture_plan`/`_ack`, `pc_mark`/`_ack`, `pc_trace_ctl` (collect, epoch, tombstones), `planAware`, ack correlation, a minimal old-peer fixture. **`TRACE_CHUNK`/`TRACE_ACK` and `CommandEnvelope.listenId` are LATER** | C | base | 1.5 |
| 6 | Trace schema freeze | as written | A | 2 | 2.5 |
| 7 | `PlanCompiler` | as written, except that `hub.context` has **no tunables** in core: setting one gives `"hub fusion params arrive in slice 3a-LATER"`. Its route is registered by Task 22 | B | 3, 4 | 2 |
| 8 + 9 | Room v7 schema and DAOs, deletion epoch, Delete all, backup | **one migration with every table** (no second migration later), the fenced DAOs, the persisted epoch, fenced files, the tmp sweep, both backup tests. Not in core: `labelRev`, the provenance used only by recall (the `sourcesJson` trace ids for invalidation **stay**) | B | base | 4.5 |
| 11 | Glasses staged apply, local TRIAL | as written. **Task 11 is the only task that edits `KeyframeGate`:** it adds `GateParams`, `ScoreListener` and `offerScored` together (a merge hotspot removed) | C | 4, 5 | 3 |
| 12 | Reserved timer photo attempt, SHADOW, auto-revert, heartbeat, sensing golden | as written, minus: the `auto:opens` rule and the S61 heartbeat fields (`planHash12`, `persistLog`, `chargeUah`) are LATER. The `auto:ceiling` and `auto:trial_end`/`auto:reboot` rules stay | C | 11 | 3 |
| 14 | Live Fit command port and S85 table | as written, with the S85a mapping | A | base | 2.5 |
| 15 | Voice-mark docs and README, doc-drift test | as written | A | 14 | 1 |
| 16 | Glasses IMU recorder | as written (epoch wipe included) | C | 6, 11, 12 | 3 |
| 19 | Collect for tuning and pulled-trace import | `CollectSession` and `TraceImport` (fenced, bounded by `TraceBounds`), with the controller-run `tools/trace-pull.sh`. **The hub trace recorder is LATER**, because replay uses the IMU trace's `Ctx` events | A | 6, 8+9 | 1.5 |
| 20 | Exact replay | as written | A | 2, 4, 6, 11 | 3 |
| 21 | Param replay for sweeps and the battery line | IMU-exact glances, admission, opens and caps under other params; point-estimate keeps with an "unobserved opens %" warning; `BatteryLine` (hours = 100 / %/min / 60); one job at a time, cancelled on stop, ABORTED after a restart; `ReplayRoutes` | A | 8+9, 20 | 2 |
| 22 | Composition wiring 1 and the debug SHADOW route | as written; also registers Task 7's compile route and `TraceRoutes` | B | 7, 11, 12, 19, 21 | 1.5 |
| 23 | M1 | as written (23a 1.5 h code, 23b 1 h device) | C + controller | 16, 20, 21, 22 | 2.5 |
| 24 | Simple param sweep | grid or seeded random, ≤ 4 params, ≤ 200 configs, sensing keys refused, timer photo gaps sweepable; objectives **captures/min, estimated %/min and hours**, unobserved %; a sortable result table; `sourcesJson` trace ids for invalidation; no model dependency (reflection test) | A | 21 | 2 |
| 26 | Publish state machine, lean | the `capture_plan` component only (no ASYNC group, no phone-RT activation in core); DESIRED → SENT → EFFECTIVE \| REJECTED \| UNKNOWN_REMOTE (10 min) → reconcile by the next ack (the glasses re-ack on `onReady`); REVERTED terminal; the full matching tuple; durable gens | B | 5, 7, 8+9 | 2 |
| 27 + 28 | Gates and actions, lean | gate 1 (compile clean), gate 2 (no tier 3), gate 3 (an exact-hash replay over ≥ 30 min of COMPLETE traces covering 2 states), gate 5 (linked and capable); **SHADOW**; **TRIAL** (an estimate ≤ baseline × 1.10, ≤ 3 per `configHash`, `trialMaxMs` = 3,600,000); **LIVE refused**. Actions: **Use settings** and **Undo latest capture plan**. A Settings row and a Diagnostics plan card | B | 21, 24, 26, M1 | 2 |
| 29 | Voice seam, lean | `SttResult.genuineFinal` from the platform into `think`, and the interceptor seam before `Thinking`. **No `listenId` plumbing**: marks and listens join by time (below) | C | base | 1 |
| 30 | Glasses tap mark | as written, except that `listenId` is optional (null in core) | C | 5 | 2.5 |
| 31 | Phone `MarkRecorder` and voice interceptor | as written, except the join: a glasses `listen = true` mark binds the first glasses `ListenStart` within ±3 s, in either order and idempotently (no duplicate MOMENT). The real-pipeline spy tests stay | C | 8+9, 14, 29 | 3 |
| 33 | Composition wiring 2 | marks, interceptor, publish and actions; registers `LabelRoutes`, `PlanRoutes` and `SweepRoutes` | B | 26, 27+28, 31 | 1 |
| 34 | Deletion graph, lean | memory tombstones, `ForgetListener`, retention, label deletes, replay and sweep invalidation by trace ids, glasses tombstones. **A range Forget deletes every overlapping trace whole on the phone too** (the rewrite is LATER) | A | 8+9, 19, 21, 24, 31 | 2.5 |
| 35 | Canvas UI, lean (35a + 35b) | 35a (2 h): lanes, bindings, param forms labelled "timer photo gap", the compile view. 35b (2 h): a replay result panel, a sweep **table** (no chart), "Use these params", publish SHADOW/TRIAL with gate texts and the trial countdown, Use settings and Undo, a marks list with delete. Full gate | B | 3, 7 (35a); 24, 27+28, 33 (35b) | 4 |
| 38 | Device checklist, core | items 1–3, 5–10 and 12 of Task 38; the overhead A/B is a coarse paired battery-% run (2 × 60 min). The encrypted migration rehearsal runs on a DB copy with the **existing** device suite | controller | all | 2 |
| | **3a-CORE total** | | | | **about 66** |

**Stream loads:** A 20.5 h (4, 6, 14, 15, 19, 20, 21, 24, 34), B 20 h (3, 8+9, 7, 22, 26, 27+28, 33, 35), C 22.5 h (1, 2, 5, 11, 12, 16, 23a, 29, 30, 31), and the controller 3 h (23b, 38).

### Core schedule (hours from start) and critical path
| Hours | Stream A | Stream B | Stream C |
|---|---|---|---|
| 0–4 | T14 (0–2.5), T15 (2.5–3.5) | **T3** (0–3), T8+9 (3–7.5) | **T1** (0–1.5), **T2** (1.5–4) |
| 4–8 | **T4** (4–7.5) | ″ | T5 (4–5.5), T29 (5.5–6.5) |
| 7.5–12 | T6 (7.5–10), T20 (10.5–13.5) | T7 (7.5–9.5), T35a (9.5–11.5) | **T11** (7.5–10.5) |
| 12–17 | T21 (13.5–15.5), T19 (15.5–17) | T26 (11.5–13.5), T27+28 code (13.5–15.5, lands after M1) | **T12** (10.5–13.5), **T16** (13.5–16.5) |
| 17–22 | T24 (17–19) | T22 (17–18.5) | T30 (16.5–19) |
| 19–23 | — | — | **T23a** (19–20.5), **T23b device** (20.5–21.5), **T31** (21.5–24.5) |
| 24–28 | T34 (24.5–27) | **T33** (24.5–25.5), **T35b** (25.5–27.5) | — |
| 27.5–29.5 | | **T38 checklist** (controller and user) | |

- **Core critical path:** T1 → T2 → T4 → T11 → T12 → T16 → T23a → T23b → T31 → T33 → T35b → T38 = 1.5 + 2.5 + 3.5 + 3 + 3 + 3 + 1.5 + 1 + 3 + 1 + 2 + 2 = **27 agent-h**.
- **Wall-clock:** about **29.5 h** on 3 streams, about **34 h** with 2 shared Gradle slots, not counting wear time or merge waits.

**Merge hotspots left in core:**

| File or area | Tasks |
|---|---|
| `HubGraph`, `CanvasGraph`, `CanvasApi` registration | 22 and 33 only (Stream B) |
| `KeyframeGate` | 11 only |
| `ContextDatabase`, `DatabaseFactory`, schema | 8+9 only |
| Glasses runtime and session | Stream C only (11 → 12 → 16 → 30) |
| `VoicePipeline` | 29 → 31 (Stream C) |
| `MainActivity` | 30 only |
| `README.md`, `docs/voice-marks.md` | 15 only |

**Controller dispatch order for the first 6 tasks** (lowest risk and most unblocking first):
1. **T1** (C, goldens): test-only, zero risk; unblocks the move.
2. **T3** (B, NodeTypes v2): no dependencies, a phone/ai-only data registry; unblocks the compiler and the UI.
3. **T14** (A, Live Fit port): pure, isolated to `:services:voice`; unblocks the docs and the voice marks.
4. **T2** (C, after T1 merges): a move-only refactor guarded by the T1 goldens; unblocks T4, T6 and T20.
5. **T8+9** (B, after T3): allocates Room v7 early, and puts the deletion epoch and backup safety in before any recorder exists.
6. **T4** (A, after T2 merges): plan types and the receiver; unblocks the compiler, the glasses apply and replay.

T5 and T15 follow as soon as their stream is free.

### 3a-LATER (deferred; about 27.5 agent-h)
| L# | Item (task) | agent-h | Why deferral is safe |
|---|---|---|---|
| LA1 | Encrypted migration device-suite extension (10) | 1.5 | Core rehearses the v6 → v7 open on a DB **copy** with the existing suite before installing (Task 38 item 1) |
| LA2 | Phone fusion params from the plan (13) | 1.5 | `hub.context` has no tunables in core, so fusion is unchanged |
| LA3 | Trace transfer over the link (17 + 18, `TRACE_CHUNK`/`ACK`) | 5 | Core uses the controller-run pull and the fenced import; glasses traces are still epoch-wiped and tombstoned |
| LA4 | Hub trace recorder (19 part) | 1 | Replay reads the IMU trace's applied `Ctx` events |
| LA5 | Replay job extras: fusion-change warning UI, thermal policy tuning (21 part) | 0.5 | One job at a time with cancel and ABORTED already covers the resource risk |
| LA6 | Moment recall, intent hit rate, holdout by day, Pareto front, `labelRev` provenance (24 part) | 2 | These only rank configs. Publishing beyond SHADOW/TRIAL is impossible in core |
| LA7 | Verification artifact, S61 heartbeat fields, fail-closed calibration import (25, 12 part) | 3.5 | They gate **only unrestricted LIVE**, which core refuses. **v0.9.6 slice C (mark photo): the battery/pause exception of a mark is NOT this evidence gate and is not LIVE capture:** it is one explicit, user-requested photo per mark, bounded to the hold (2-4 s), never consulted by a plan, SHADOW, TRIAL or LIVE run; before enabling LIVE re-read `handoffs/2026-10-26-v096c-handoff.md` section 1 (where the exception lives) and count one mark photo's camera time in the verified drain |
| LA8 | Recovery extras: compensation workflow, the crash-boundary matrix, multi-component ASYNC groups (26 part) | 1.5 | Core publishes one glasses component and activates nothing on the phone, so a lost ack leaves at most a glasses SHADOW or a self-ending TRIAL, reconciled at the next `onReady` |
| LA9 | The unrestricted LIVE gate, `meta.expect` and the `auto:opens` rule (27 part, 12 part) | 1 | LIVE is refused; TRIAL is time-bounded on the glasses. **v0.9.6 slice C:** the `auto:opens` rule and the LIVE gate must treat the mark photo as a user-initiated open outside the plan (it opens the camera even while capture is paused or the battery policy stops capture; bounded to one photo per mark and `markPhotoHoldMs`, heat STOP still wins); it must never count as, or be suppressed by, a plan's open budget |
| LA10 | Restore-previous action, ASYNC projection, full-graph publish test (28, 3 part) | 2 | Core actions are Use settings and Undo; ASYNC publishes stay on slice 2's path |
| LA11 | `listenId` plumbing (29 part, 5 part) | 2 | The core ±3 s time join is idempotent and tested in both orders |
| LA12 | Phone mark entry points: notification, tile, home (32) | 1.5 | Tap and voice marks are what was asked for |
| LA13 | IMU chunk rewrite on a range Forget (34 part) | 1 | Core deletes the whole overlapping trace, which is stricter |
| LA14 | Chart.js Pareto chart (36; vendor-lock as specified) | 1 | The core sweep table holds the same numbers |
| LA15 | Mark timeline drag and retro marks (37 part) | 1.5 | Voice "I missed that" gives retro moments in core |
| LA16 | Checklist extras: the `chargeUah` overhead A/B (2 × 90 min), a verified LIVE interval (38 part) | 1 | They need LA7 and gate only LIVE |
| | **3a-LATER total** | **about 27.5** | |

## Changes in round 2
1. **Codex plan review:** all 26 findings are folded in as Rulings S57–S82. None is rejected. #19's automatic Capture-now and starred-photo MOMENT imports are explicitly **deferred** with a reason (S75). #26 records the hidden-foreground deferral as an accepted scope change (S82).
2. **User ruling, timer photo gaps (S83):**
   - plan gaps range 5–300 s **per state** (REST, VEHICLE and FLIGHT up to 300 s);
   - the WALKING and UNKNOWN rows stay locked;
   - the identity plan reproduces today's gaps **exactly**: no clamping, and the `clamped` text is removed;
   - the reserved attempt bound is 60 s in UNKNOWN and WALKING and 300 s in rest-like states.
3. **User clarification (S84):**
   - a plan never lowers or delays sensor sampling, glance detection, hub evidence intake or fusion; it changes only how often a **timer-driven photo attempt** happens;
   - glance and manual captures are never delayed by a gap;
   - the terms are renamed: "check gap" → **"timer photo gap"** and "reserved timer opportunity" → **"reserved timer photo attempt"**;
   - a golden test shows the sampling profile and the glance trigger latency are unchanged.
4. **User ruling, voice phrases (S85):** the starting command and intent set is held as data. It has anchored rules and a question-word veto, and never uses "forget". A data-driven test and generated user docs (`docs/voice-marks.md` and a README section) come with a doc-drift test (new Task 15).
5. **New tasks:**
   - 1 (freeze the pre-change goldens);
   - 9 (persisted deletion epoch), 10 (encrypted migration suite);
   - 15 (voice-mark docs);
   - 17 (glasses transfer, split out), 22 (composition wiring 1);
   - 25 (verification artifact), 27–28 (the publish split);
   - 29 (listen metadata contract), 33 (composition wiring 2);
   - 36–37 (the UI split).

   The tasks are renumbered 1–38.
6. **Estimate:** about **103.5 agent-h** (round 1: 71.5). The dependency critical path is **33.5 agent-h**. Wall-clock is about **42 h** on 3 streams, or about **48 h** with Gradle contention.

## Rulings
**Round 1 plan rulings (kept, some amended):**
- Ruling P1: the plan cap is **8,192 bytes** (§17.2), with UTF-8 byte-boundary tests on the production path (S82's "keep" note).
- Ruling P2: marks are stored in **`canvas_label`** (brief pick 4), which is the "moment_label" storage.
- Ruling P3: **tap = mark + listen.** An Ask after a tap keeps the row as `ASK_TAP`, excluded from recall by default, and `HudSettings.markGesture` is dropped.
- Ruling P4: `MotionClassifier`, `CameraPolicy` and `CaptureProfile` move too. Moved files keep their packages.
- Ruling P5: the glasses `ContextGate` stays on the glasses. Replay reads the recorded applied state.
- Ruling P6 is superseded by S82.
- Ruling P7: after a time-range Forget, unsent glasses traces that overlap the range are deleted whole.

**Codex review rulings** (all accepted; what, then the cost if wrong):
- **S57 (#1): a persisted, pairing-scoped deletion epoch.**
  - `DeletionEpoch(installId: String, counter: Long, wipedAtMs: Long)` lives in phone prefs `deletion_epoch`, excluded from backup. It is **committed before** any deletion starts.
  - It is carried by `TraceControl`, `TraceChunk` and every durable trace or outbox item.
  - `MemoryWipe.generation` stays for in-flight jobs only.
  - The glasses store the last epoch they saw. A different `installId` (reinstall or re-pairing) → delete every local trace and mark, then adopt the new epoch.
  - **Marks are fenced by occurrence time:** a mark whose mapped time < `wipedAtMs` is dropped.
  - Cost if wrong: one prefs file and one field per message.
- **S58 (#2): deletion ownership.**
  - Transactional tombstones and invalidation live in `:services:memory` (`CanvasSlice3ForgetDao`, called by `ForgetService` and by retention).
  - `:services:orchestration` coordinates files and devices through a `ForgetListener` interface that memory defines and orchestration registers.
  - **A rewrite creates a new trace id** and tombstones the original.
  - **Files are published only through a fence:** write to tmp, check the fence inside the DB transaction, then rename; orphan tmp files are swept at start-up.
  - Mark outboxes and recorder buffers are cleared on a new epoch.
  - **Basic wipe handling lands with each recorder (Tasks 16–19), before M1 installs a collecting build.**
- **S59 (#3): the glasses enforce TRIAL themselves.**
  - `CapturePlanMessage` gains `trialMaxMs: Long? = null`. TRIAL requires `0 < trialMaxMs ≤ 3_600_000` (else `bounds`).
  - The glasses start the trial on their own `elapsedRealtime` at activation, and persist (`trialStartElapsed`, `bootId`, `trialMaxMs`).
  - A reboot (a new `bootId`) or a clock change ends the trial safely, by reverting.
  - A duplicate or re-sent message **never extends** a trial. `trialEndsAtMs` is display only.
- **S60 (#4): configuration identity.**
  - `CapturePlan = {planSchema, configHash, config{glance, policy, gate, ctxBound, phone}, meta{name, settingsRev, expect?}}`.
  - `configHash` = 12 hex of SHA-256 over canonical `config` only.
  - Every piece of evidence (replays, sweeps, trial counts, calibration rows, acks) is keyed by `configHash`, so filling `meta.expect` never changes the identity.
  - A test follows compile → replay → TRIAL → calibration → LIVE and asserts one `configHash` throughout.
- **S61 (#5): the S44 verification artifact** (new Task 25).
  - The heartbeat gains `planHash12`, `planMode`, `worn`, `charging`, `persistLog` and `chargeUah`.
  - `tools/battery-run.sh verify` writes a versioned `verification-v1.json` from the per-heartbeat samples.
  - `CalibrationImport` **fails closed** on missing fields, mixed plans or settings, gaps over 2 heartbeats, plugged or not-worn spans, or phase changes inside a phase.
  - It never trusts a supplied `verified` flag.
- **S62 (#6): a persisted transition and recovery table for publish groups** (Task 26).
  - A timeout means **UNKNOWN_REMOTE**: reconcile by the next ack, or compensate with a higher gen.
  - REVERTED is terminal per gen.
  - Activation needs a match on `gen`, `publishId`, `mode`, `configHash` and `phoneInstall`.
  - Crash tests cover every persist, send and activate boundary.
- **S63 (#7): the listen metadata path** is pinned end to end (new Task 29):
  - `GlassesEvent.Cmd(command, listenId)`;
  - `HubVoice.onListenStart(listenId)`;
  - `VoicePipeline.glassesListenStart(listenId)`;
  - `SttResult`'s genuine-final status is passed from recognition;
  - `PipelineAdapters` and `VoiceGraph`.

  The interceptor runs **before** `Thinking` is published. Only **unmatched clauses** go on to Ask. Mark and `ListenStart` reordering joins idempotently by `listenId`. Real-pipeline tests spy on `AnswerService`, the logs, persistence and HUD state.
- **S64 (#8): migration safety.**
  - If another branch takes v7 first, rebase onto the latest schema, keep every existing migration, **write a new latest → next migration**, regenerate the combined schema JSON and rerun every upgrade path. Never just rename the file.
  - The encrypted device suite (`androidTest`) is extended through both production builders with a seeded canonical v6 fixture, and its helpers are generalised beyond "current = v6" (Task 10).
  - A controller-owned encrypted migration rehearsal runs before the production upgrade (Task 38).
- **S65 (#9): the trace schema is frozen and reviewed before the recorder and the simulator diverge** (Task 6).
  - Traces start only at a **clean boundary**: the detector QUIET, no camera episode, an empty gate window.
  - They begin with a **10 s pre-roll**, so the classifier windows refill; decisions inside it are discarded.
  - Events carry every scheduling input: `Profile`, `GateParams`, `Score` for every scored frame (blur failures included, through a gate score listener), `Flush`, `Episode`, `Backoff`, `Plan` and `Close`.
  - Exact replay **recomputes** opens through `CameraPolicy` and compares them with the recorded `Open` events.
- **S66 (#10): 25 Hz traces** are flagged `exact = false`. They are never parity or publish evidence. The live classifier is never downsampled.
- **S67 (#11): SHADOW never changes real capture.**
  - The reserved attempt is a cross-mode invariant (S83) with **fixed bounds that are not plan params**, so SHADOW capture equals settings capture by construction.
  - SHADOW only counts the plan's hypothetical opens.
  - Tests use restrictive FLIGHT and REST settings.
- **S68 (#12): effective profiles.**
  - Plan rows mirror `ContextCapturePolicy`: `capPerMin: Int?` (null = the A3 cap unchanged and **no** hard flag), timer photo gaps (0 = inherit), and `forceSettle`.
  - The composition is the same as today's: the settings tier profile → `withContext(row)` (minima, via `maxOf`) → `forRest()` → heat and battery.
  - A plan gap below the settings profile has no effect. The compiler warns: `"timer photo gap below the settings profile has no effect"`.
  - Identity cases cover every state, WorkRest, the budget tiers and a settings edit after publish.
- **S69 (#13): staged, atomic apply.**
  - One immutable `ActiveConfig` is activated at a safe boundary: detector QUIET and no open episode, or after 10 s by aborting the current bout.
  - Every `MotionClassifier` param read goes through one `@Volatile` config.
  - EFFECTIVE is acked only after activation.
- **S70 (#14): liveness is tested on the production path.**
  - The cause is a distinct `resv`, with `lastPhotoAttemptAtMs` defined as the last camera episode start that delivered a first frame.
  - Tests cover rolling windows through the real session and camera owner, under: continuous head motion, still backoff, WorkRest, heat-SLOW, bind backoff, failed opens and eligibility transitions.
  - Both walking vetoes stay outside plan control; only the SPEED exception remains.
- **S71 (#15): build wiring, dependencies and owners.**
  - Task 2 adds `:core:capture` to `:services:ai` and `:services:orchestration`.
  - M1 depends on counterfactual replay (Task 21).
  - M1's SHADOW-only debug publish is owned by Task 22.
  - Production composition (`HubGraph`, `CanvasGraph`, `CanvasBackend`/`CanvasBackendImpl`, `CanvasApi` registration) is owned by Stream B in Tasks 22 and 33; leaf tasks expose interfaces only. See "Shared-file merge map".
- **S72 (#16): routes and the ASYNC projection.**
  - `CanvasBackendImpl.nodeTypes()` serves the v2 descriptors (Task 3).
  - `Pipeline.asyncProjection()` is immutable. Its hash is the slice 2 hash of the projected ASYNC-only pipeline, so tested identity is kept. The runner and publisher take the projection.
  - A full three-domain graph is tested through the real API and publisher (Task 28).
- **S73 (#17): the transfer contract.**
  - Every send needs **local glasses charging** and stops at once on unplug or disconnect.
  - The glasses start a whole transfer again when 10 min pass after the last chunk with no `TraceAck`.
  - The phone marks a trace PARTIAL and acks false when no chunk arrives for 2 min after the manifest, or the inventory is incomplete. There is no per-seq resume.
  - **Bounds:** base64 ≤ 90,000 chars, gunzip ≤ 512 KB (a streamed limit), ≤ 200,000 events per chunk, ≤ 4,096 chunks; `traceId` must match `^[a-z0-9-]{8,64}$`.
- **S74 (#18): mixed-version behaviour.**
  - Frozen old-peer fixtures run in both directions.
  - The glasses send their caps in an unsolicited `CapturePlanAck` on every `onReady()`, at gen 0 with no plan.
  - `HudSettings.planAware: Boolean = false`, which a new phone sets true. Glasses holding a persisted plan that see `planAware = false` revert to settings with `auto:old_phone`.
  - Transport freshness (`sessionStartMs`) is kept separate from occurrence time. Queued marks are never dropped for freshness.
  - Acks carry `phoneInstall` and `sessionStartMs` and are matched to an outstanding gen; anything else is a stale ack and is ignored.
- **S75 (#19): provenance.**
  - Replays and sweeps store `sourcesJson`: trace ids, revisions, intervals and `labelRev`.
  - `labelRev` is a durable counter bumped on every label insert, delete or correction. Results and gate-3 evidence whose `labelRev` or sources changed are invalidated.
  - Frame Forget **and** retention (`expirePhotos`) call the hook.
  - **The automatic Capture-now and starred-photo MOMENT imports are deferred to 3c.** Why: no producer exists, and tap, voice and retro marks cover recall. Cost if wrong: fewer labels in week 1.
- **S76 (#20): independent goldens and mutation REDs.**
  - Task 1 freezes decision logs from **unmodified `s2/int`** code before any move.
  - M1 uses the real `PlanReceiver` (moved to `:core:capture`), not a JSON stub.
  - Tasks list mutation REDs (suppress the reserved attempt, remove the walking veto, shift a threshold boundary, bypass a fence, mutate the config hash, forward a mark clause to Ask).
  - `SweepService` has no model dependency by construction (a reflection test of its constructor).
- **S77 (#21): resource gates.**
  - The overhead A/B uses the heartbeat's `chargeUah` (`BATTERY_PROPERTY_CHARGE_COUNTER`) over paired 2 × 90 min runs against A3's 0.49 %/min. SHADOW gets its own budget.
  - Replay consumes chunk streams (`Sequence<Ev>`), and only one replay or sweep job runs at a time.
  - Jobs persist RUNNING → DONE, CANCELLED or ABORTED, and are cancelled on thermal SEVERE, service shutdown, Developer mode off or process death (marked ABORTED at start-up).
  - Deployed-plan reconciliation lives in the hub, never in the canvas server.
- **S78 (#22): compiler semantics.**
  - Thresholds: `gyroPeak` allows only `>` (onset is strict), and `boutMs`/`boutRad` allow only `>=`.
  - The supported topology is the fixed chain `imu.head_motion → gate.threshold* → imu.settle → gate.novelty → policy.capture_rate → gate.rate_cap → capture.image` (optional nodes may be left out), with `signal.in ctx` feeding `policy.capture_rate`.
  - Any other order or branch → `"unsupported wrapper order: <a> before <b>"`.
  - A `signal.*` node's domain is its `params.domain`, validated per instance.
  - Exact-boundary tests cover every feature.
- **S79 (#23): splits.** Task 7 becomes 8, 9 and 10; Task 12 becomes 16, 17 and 18–19; Task 14 becomes 20 then 21; Task 18 becomes 26, 27 and 28; Task 20 becomes 29, 30 and 31; Task 25 becomes 36 and 37. Re-estimated.
- **S80 (#24): hours = 100 / (%/min) / 60.** A3 ≈ 3.40 h and A2 ≈ 2.78 h, pinned in tests.
- **S81 (#25): backup.** Extend the existing `BackupRulesTest` (its prefs-ruling classification **and** both the cloud and the device-transfer XML sections) and the existing `GlassesBackupRulesTest` (keep its blanket exclusions). Tmp, import and rewrite paths are covered. No duplicate class.
- **S82 (#26): accepted scope change.**
  - The §16.2 hidden-foreground app is **deferred**, gated on spike A0 (pointer at the end).
  - **Mark entry points in this release:**
    - a glasses tap while the HUD is visible;
    - the phone notification action, Quick Settings tile and home button;
    - voice after a phone push-to-talk;
    - voice after a glasses push-to-talk while the HUD is visible.

    In the default icon mode, glasses taps do not mark.
  - The user confirms this deferral in "Open for the user" #1.
  - Also kept from Codex's closing note: the 8 KB cap and §17.1's "guards may read `ctx`" (there are no guards in 3a, and no motion-only assertions are written).

**2026-10-22 user rulings:**
- **S83 (timer photo gaps and liveness; replaces the 5–60 s bound and the §3 text "check gaps 5–60 s under a plan"):**
  - **Rows:** a plan's REST, VEHICLE_PASSENGER, VEHICLE_DRIVING and FLIGHT rows may set timer photo gaps of 5,000–300,000 ms each, or 0 = inherit. The WALKING and UNKNOWN rows are never in a plan (S32) and keep today's settings profile.
  - **The identity plan = today's `ContextPolicies.DEFAULT`, bit for bit**, including REST 30/60/30/120 s, FLIGHT 120/300/120/300 s and the vehicle rows unchanged (cap null, gaps 0).
  - **The reserved timer photo attempt** (all modes: settings, SHADOW's real capture, TRIAL, LIVE):
    - while eligible, at least one timer photo attempt (a camera open) every `reservedBoundMs` since the last one;
    - `reservedBoundMs` = **300,000** when the applied state is REST, VEHICLE_* or FLIGHT, **or** A3's WorkRest is resting while the state is not WALKING (glasses motion below the thresholds = "at rest"); otherwise **60,000** (UNKNOWN, WALKING);
    - no plan, guard, novelty rule or gap can suppress it; only dedupe, blur and the hard ceiling apply;
    - the bounds are fixed in Kotlin and are not plan params.
  - **Why:** the user wants one photo attempt per 2–3 min at rest for battery, without ever being unbounded.
  - **Cost if wrong:** in nominal states today's schedules already meet the bounds (T0–T3 still-cap 60 s; WorkRest 120 s; FLIGHT 300 s), so the reserved attempt fires only on anomalies (heat-SLOW, bind back-off edges). The Task 1 goldens prove this for the nominal scripts.
- **S84 (a plan changes photo frequency only; user clarification):**
  - **(1)** A plan never lowers or delays IMU sampling rates, `GlanceDetector`, `MotionModel`/`MotionClassifier` cadence, `ContextFusion` evaluation cadence, phone hub ticks or evidence intake. None of these is a plan field, and the validator refuses any key that names them (`"<k>: sensing and detection are not plan parameters"`).
  - **(2)** A glance trigger, "Capture now" and a mark are **never delayed** by a timer photo gap. (A mark never captures, S45.)
  - **(3)** The names are "timer photo gap" (plan JSON `timerPhotoGapsMs`, UI and texts) and "reserved timer photo attempt" (heartbeat key `resv`, UI and texts). Code-internal names of the existing classes (`settledCheckMs`, …) are unchanged, because the move is move-only.
  - **(4)** The test `timerPhotoGapLeavesSensingUnchanged` (Task 12) checks the identity plan against a plan with every gap at 300 s: the same sensor registration profile, the same `MotionClassifier` input count, and the same glance-trigger-to-open latency distribution.
- **S85 (voice phrases):** the starting set below is data (`MarkPhrases.kt`).
  - English only; Hindi comes later.
  - The rules are anchored at the start of a clause, after an optional filler ("please", "ok", "okay", "hey").
  - **Question-word veto:** a clause whose first word is what, which, who, whom, whose, where, when, why, how, is, are, am, was, were, do, does, did, can, could, should, would, will, shall or may is never a command. The only exceptions are "can you <command>" and "could you <command>".
  - **Never "forget"** (it collides with the Forget feature).
  - Intent words count **only after a mark command** in the same utterance. An unknown, negated ("not …") or conflicting intent saves a plain MOMENT.
  - The final mapping to `IntentLabel` enum names is made at implementation (Task 14). Docs are generated from the same table (Task 15).

### S85 data: mark phrases (the parser and the docs read this table)
| Kind | Phrases | Effect |
|---|---|---|
| MARK | mark that · mark this · mark it · remember this · remember that · save this · save that · note this · note that · bookmark this · bookmark that · capture this · this is important · that was important | MOMENT for [t − 10 s, t], where t = the push-to-talk start |
| RETRO | I missed that | MOMENT for the previous ~10 s, [t − 10 s, t] (the same anchor), source `VOICE` |
| UNDO | undo mark · cancel mark · remove mark | deletes this listen's mark, or the newest voice/tap mark within 2 min |
| (never) | forget … | not a command; passes through to Ask unchanged |

| Intent word (after a command; optional "I'm / I am / we're / just / currently") | Canvas label | `IntentLabel` (proposed; **finalised at implementation**) |
|---|---|---|
| sitting · resting · relaxing · sleeping | the word itself | SITTING_IDLE |
| working · studying · reading · writing · computer work | the word | COMPUTER_WORK (S85a) |
| meeting · talking · conversation | the word | OTHER (S13: unmapped, routing only) |
| eating · cooking · shopping · watching tv · gaming · cleaning | the word | OTHER |
| driving | driving | DRIVING |
| passenger · commuting · travelling | the word | VEHICLE_PASSENGER (S85a) |
| riding | riding | OTHER (S85a: a two-wheeler rider may be the driver; never guess) |
| flying | flying | FLIGHT_CABIN |
| walking | walking | WALKING |
| exercising · workout · running · cycling | the word | WORKOUT |
| other | other | OTHER |

## Estimates and schedule (round 2 full scope; superseded by the Round 3 core table above)
Agent-hours are Sonnet implementing TDD through the throttled Gradle, plus one Opus review and one fix round.

| # | Task | Stream | Depends on | agent-h |
|---|---|---|---|---|
| 1 | Freeze pre-change decision goldens from `s2/int` (S76, S83) | C | base | 1.5 |
| 2 | `:core:capture` move-only, `GlanceAdmission` extract, module deps (A1, S71) | A | 1 | 2.5 |
| 3 | NodeTypes v2, ParamSchema, S41, rooted domains, bindings, backend v2, ASYNC projection (A2, S72) | B | base | 4 |
| 4 | `CapturePlan` config/meta, bounds (S83), validator (S84), resolver (identity exact), pure `PlanReceiver` (A3, S59, S60, S68) | A | 2 | 4 |
| 5 | Wire: plan, mark, trace channels, `listenId`, `planAware`, ack correlation, old-peer fixtures (A6, S74) | C | base | 3 |
| 6 | Trace schema freeze: snapshot, events, clean boundary, pre-roll, bounds, codec, manifest-lite (A8, S65, S73) | A | 2 | 2.5 |
| 7 | `PlanCompiler`: topology, strict operators, policy rows, fusion subset, §1 golden (A4, S78) | B | 3, 4 | 3.5 |
| 8 | Room v7 schema and DAOs (revisions, provenance, job states), fenced writes (A8 part) | B | base | 3 |
| 9 | Persisted deletion epoch, Delete all for tables and files, tmp sweep, backup rules (S57, S58, S81) | B | 8 | 2.5 |
| 10 | Encrypted migration device suite and upgrade paths (S64; written by the agent, run by the controller) | A | 8 | 1.5 |
| 11 | Glasses: staged atomic apply via `PlanReceiver`, local TRIAL enforcement, provider params (A5 part, S59, S69) | C | 4, 5 | 3.5 |
| 12 | Glasses: reserved timer photo attempt (S83), SHADOW counts, auto-revert, heartbeat incl. S61 fields, sensing-unchanged golden (A5 rest) | C | 11 | 4 |
| 13 | Phone RT apply: `FusionParams`, the `ctx` binding (A7) | A | 4, 5 | 1.5 |
| 14 | Live Fit command layer port with the S85 data table and question veto (A9 / brief M2) | C | base | 3 |
| 15 | Voice-mark docs: `docs/voice-marks.md` and a README section generated from `MarkPhrases`, plus a doc-drift test (S85) | C | 14 | 1 |
| 16 | Glasses IMU recorder: clean boundary, pre-roll, score listener, Close/Plan events, epoch wipe (A8 part) | C | 6, 12 | 3 |
| 17 | Glasses transfer: local charging, stop on unplug, whole-transfer timeout, tombstones (S73) | C | 16 | 2 |
| 18 | Phone trace intake: bounds, missing-chunk timeout, fences, revision ids (S73) | B | 5, 6, 9 | 3 |
| 19 | Hub trace recorder, Collect for tuning session, pulled-trace import (A8 part) | A | 6, 8, 9 | 2.5 |
| 20 | `CaptureSimulator` exact replay (scheduler recomputed, streamed input) (A10 part) | A | 2, 4, 6 | 3.5 |
| 21 | Counterfactual replay, battery line (S80), replay API, job lifecycle (S77) (A10 rest) | A | 8, 20 | 3 |
| 22 | Composition wiring 1: plan, trace, collect, replay; the M1 SHADOW-only debug publish (S71) | B | 7, 11, 12, 13, 18, 19, 21 | 2 |
| 23 | **M1**: a) parity and identity end-to-end with the real `PlanReceiver` (code, 2 h); b) device run (controller, 1 h) | C + controller | 16, 20, 21, 22 | 3 |
| 24 | Sweeps, objectives, holdout, Pareto, provenance and `labelRev` (A11, S75) | A | 21 | 3.5 |
| 25 | Verification artifact: `battery-run.sh verify`, fail-closed `CalibrationImport` (S61) | A | 12 | 3 |
| 26 | Publish state machine and recovery table (S25, S62) | B | 5, 7, 8, 13 (lands after M1) | 3.5 |
| 27 | Evidence gates: `configHash`, TRIAL count and estimate, verified calibration (S11, S60) | B | 21, 24, 25, 26, M1 | 2.5 |
| 28 | S26 actions, Settings and Diagnostics rows, full-graph API and publisher test (S72) | B | 27 | 2.5 |
| 29 | Listen metadata contract: inbound `Cmd.listenId`, `HubVoice`, `VoicePipeline` genuine final, adapters (S63) | C | 5 | 2.5 |
| 30 | Glasses tap mark: outbox (occurrence vs transport, epoch), cue, undo (brief M1) | C | 5, 29 | 2.5 |
| 31 | Phone `MarkRecorder` and voice interceptor integration with spies (brief M3, S63) | C | 8, 9, 14, 29 | 3.5 |
| 32 | Phone mark entry points (A9 rest) | C | 31 | 1.5 |
| 33 | Composition wiring 2: marks, voice, publish groups, actions | B | 28, 31, 32 | 1.5 |
| 34 | Deletion graph: memory tombstones, `ForgetListener`, retention hook, label invalidation, IMU rewrite (A13, S58, S75) | A | 9, 18, 19, 21, 24, 31 | 3.5 |
| 35 | Canvas UI 1: lanes, bindings, param forms ("timer photo gap") (A14 part) | B | 3, 7 | 2.5 |
| 36 | Canvas UI 2a: replay, sweep and Pareto views, vendored Chart.js (A14) | B | 24, 35 | 2.5 |
| 37 | Canvas UI 2b: mark timeline, publish and rollback views, full gate (A14) | B | 28, 33, 36 | 2.5 |
| 38 | Device checklist incl. the encrypted migration rehearsal (A15; controller with the user) | controller | all | 3 |
| | **Total** | | | **about 103.5** |

The streams carry: A 31 h, B 35.5 h, C 33 h, and the controller 4 h.

**Three-stream schedule** (hours from start):

| Hours | Stream A (core, replay, data) | Stream B (phone canvas, Room, UI, composition owner) | Stream C (glasses, link, voice) |
|---|---|---|---|
| 0–4 | T2 (1.5–4) | T3 (0–4) | **T1** (0–1.5), T5 (1.5–4.5) |
| 4–8 | **T4** (4–8) | T8 (4–7), T9 (7–9.5) | T29 (4.5–7), T14 (7–10) |
| 8–12 | T6 (8–10.5), T13 (10.5–12) | T7 (9.5–13) | **T11** (10–13.5) |
| 12–16 | T20 (12–15.5), T10 (15.5–17) | T35 (13–15.5), T26 (15.5–19) | **T12** (13.5–17.5) |
| 16–20 | T19 (17–19.5), T21 (19.5–22.5) | T18 (19–22) | **T16** (17.5–20.5), T15 (20.5–21.5) |
| 20–25 | T25 (22.5–25.5) | T22 (22.5–24.5) | **T23a** (24.5–26.5), **T23b** device (26.5–27.5) |
| 25–30 | T24 (25.5–29) | **T27** (29–31.5) | T17 (27.5–29.5), T30 (29.5–32) |
| 30–35 | T34 (35.5–39) | T28 (31.5–34), T36 (34–36.5) | T31 (32–35.5), T32 (35.5–37) |
| 35–42 | — | T33 (37–38.5), **T37** (38.5–41) | — |
| 41–44 | | **T38 device checklist** (41–44, controller and user) | |

- **Dependency critical path:** T1 → T2 → T4 → T11 → T12 → T16 → T23a → T23b → T27 → T28 → T33 (via T31) → T37 → T38 ≈ **33.5 agent-h**.
- **Wall-clock:** resource-constrained, about **42–44 h** on 3 streams; add about 15 % for 3 streams sharing 2 Gradle slots, so **about 48 h**. User wear time and merge waits are not included.

**Dependency graph:**
```
T1 ─> T2 ─┬─> T4 ─┬─> T7 (+T3) ──────┬─> T26 (+T5,T8,T13) ─┐
          │       ├─> T11 (+T5) ─> T12 ─> T16 ─> T17       │
          │       ├─> T13 (+T5)      │     │                │
          │       └─> T20 (+T6) ─> T21 (+T8) ─> T24 ─┐      │
          └─> T6 ─┬─> T16, T18 (+T5,T9), T19 (+T8,T9)│      │
                  └─> T20                             │      │
T12 ─> T25 ───────────────────────────────────────────┤      │
T7,T11,T12,T13,T18,T19,T21 ─> T22 ─> T23a (+T16,T20,T21) ─> T23b (M1) ─> T27 (+T21,T24,T25,T26) ─> T28 ─> T33 ─> T37 ─> T38
T3 ─> T35 (+T7) ─> T36 (+T24) ─> T37
T5 ─> T29 ─┬─> T30
           └─> T31 (+T8,T9,T14) ─> T32 ─> T33 ; T31 ─> T34 (+T9,T18,T19,T21,T24)
T14 ─> T15 ; T8 ─> T9, T10
```

**Shared-file merge map (S71):**

| File or area | Tasks | Rule |
|---|---|---|
| `phone/.../hub/HubGraph.kt`, `phone/.../canvas/CanvasGraph.kt` | 22, 33 only | Leaf tasks add classes and interfaces. **Only Stream B wires them** |
| `CanvasBackend.kt`, `CanvasBackendImpl.kt`, `CanvasApi.kt` | 3, 7 (compile route), 22, 33 | Leaf route files (`PlanRoutes.kt`, `ReplayRoutes.kt`, `SweepRoutes.kt`, `TraceRoutes.kt`, `LabelRoutes.kt`) are written by their tasks. Registration happens only in 3, 7, 22 and 33 |
| `core/capture/.../KeyframeGate.kt` | 11 (`GateParams` provider), 16 (score listener), 20 (`offerScored`) | The interfaces are pinned below. These tasks are serialized: 11 → 16 on C, 20 rebases after 11 merges |
| `ContextDatabase.kt`, `DatabaseFactory.kt`, `schemas/` | 8 only (9 and 34 add DAO methods only) | v7 is allocated in Task 8 before anything that depends on it rebases |
| glasses `GlassesRuntime`, `SamplingSession`, `CaptureService`, `HudController` | 11, 12, 16, 17, 30 | All in Stream C, in that order; callbacks are registered once in `GlassesRuntime` |
| `MainActivity.kt` | 30 (`onAsk` and cancel only) | The hidden-foreground stream starts after 30 merges |
| `services/voice/.../VoicePipeline.kt` | 29, 31 | Serialized on Stream C |
| `README.md`, `docs/voice-marks.md` | 15 only | Generated blocks between markers |

## Global Constraints
- **Where to work:**
  - **Controller:** `git -C /Users/<user>/Desktop/rokid/pca-s2/int worktree add -b s3a/int /Users/<user>/Desktop/rokid/pca-s3/int s2/int`. The base `s2/int` has wave B, wave 3 and slice 2, with Room v6.
  - **Stream worktrees**, created from `s3a/int`:

    | Stream | Path | Branch |
    |---|---|---|
    | A | `/Users/<user>/Desktop/rokid/pca-s3/a` | `s3a/core` |
    | B | `/Users/<user>/Desktop/rokid/pca-s3/b` | `s3a/phone` |
    | C | `/Users/<user>/Desktop/rokid/pca-s3/c` | `s3a/glasses` |

  - **Submodule:** `git submodule update --init -- android-companion/services/ai/src/main/cpp/llama.cpp`, **never `--reference`**.
  - **Check the base:** `MIGRATION_5_6`, `6.json`, `floweditor.js`, `glasses/.../capture/session/ContextGate.kt`, `GlassesBackupRulesTest.kt`. If any is missing, **stop and report**.
- **Process rules (hard):**
  - **NEVER `git stash`.**
  - **NEVER `pkill`/`killall` Gradle or Java machine-wide**, and never `gradlew --stop`.
  - One Gradle run per worktree; at most 2 machine-wide; wait if both are busy.
  - One agent per worktree.
  - Explicit-path commits (`git add <paths>`).
  - No push.
  - **No `adb`** in any code task (no install, pull, push or logcat). Device steps go only to Task 23b and Task 38.
  - Scripts that touch devices (`tools/trace-pull.sh`, `tools/battery-run.sh verify`) are written by agents and **run only by the controller**.
- **Modules and package roots** (as in round 1):
  - `:core:capture` is new (Kotlin JVM, JDK 17). Moved files keep `com.rokid.context.glasses.capture.*`; new code goes in `com.rokid.context.capture.{plan,trace,replay,sweep}`.
  - `:services:ai` adds `ai/canvas/plan/`.
  - `:services:orchestration` adds `canvas/{plan,trace,marks}/`.
  - `:services:voice` adds `voice/commands/`.
  - `:services:memory` adds `db/CanvasSlice3*.kt`.
- **Gradle:** `/Users/<user>/Desktop/rokid/proactive-context-agent/.superpowers/sdd/gw.sh <args>` from `<worktree>/android-companion`.
  - Run the named `--tests` first, then the whole module.
  - Module test tasks: `:core:capture:test`, `:core:model:test`, `:glasses:testDebugUnitTest`, `:phone:testDebugUnitTest`, `:services:{ai,memory,orchestration,voice,glasses-link}:testDebugUnitTest`.
- **Wire:**
  - `PROTOCOL_VERSION` stays **1**.
  - New channels and additive defaulted fields only; **no new `Command` subtype**.
  - Unknown enum properties fall back to their default; no enums inside wire lists.
  - **Transport freshness** (`sessionStartMs`) is separate from **occurrence time** (`atMs` or `atElapsedMs`). Queued marks are never dropped for freshness.
  - Old-peer behaviour is tested with frozen fixtures (S74).
- **Room:**
  - Migration A is `MIGRATION_6_7`, additive, in both builders, with `7.json` committed and `Migration6To7Test`.
  - `DatabaseFactoryUpgradeTest` covers v1 → v7.
  - The encrypted device suite is extended (Task 10).
  - A version collision at merge follows S64. Never edit or rename an existing migration.
  - No destructive fallback.
  - `frame_detection` is 3c.
- **Invariants (§3 as amended by S83 and S84; non-removable, enforced outside any plan; each one has a mutation-RED test):**
  - user pause, wear gate, Forget/Delete-all fences, keyframe claims, the deletion epoch and wipe generation, camera ownership and camera-busy back-off;
  - **the LED is never touched**;
  - HeatGuard, `BatteryBudget` and `PhonePowerPolicy` apply after the plan, and the stricter value wins;
  - **walking (S32):** the WALKING and UNKNOWN rows are not in plans; both walking vetoes are unconditional except SPEED;
  - **intent or vehicle classification never pauses capture.** The default vehicle rows are unchanged (cap null, gaps 0). Only an explicitly published plan row can slow photo attempts in a positively detected state, and it is bounded by the reserved attempt;
  - **the reserved timer photo attempt** (S83): ≤ 60 s in UNKNOWN and WALKING, ≤ 300 s in rest-like states, in every mode, unsuppressible;
  - **hard caps:** `capPerMin` 1–4 or null; hard ceiling = min(2 × cap, 8); timer photo gaps 5–300 s or 0; `settleMs` ≥ 1,000; SETTLE wake under a plan;
  - **the plan changes photo frequency only (S84):** sensing, detection, fusion and hub intake are never plan parameters, and glance or manual captures are never delayed;
  - no `pause` row; DRIVING nudges off.
- **Battery:**
  - The baseline is A3 at **0.49 %/min** (A2: 0.6 %/min at 7 s/min). The LIVE target is ≤ 0.42 %/min.
  - **No new wakeups on the glasses:** no new alarms, wake locks, delayed-loop handlers, `setTurnScreenOn` or sensor registrations, and no raised sensor rate.
  - Work rides the existing 250 ms session tick and the existing sensor and link callbacks.
  - Transfers need local glasses charging.
  - The recording budget is ≤ 0.02 %/min, verified in Task 38 with `chargeUah` (S77).
- **Privacy:**
  - **Audio and transcripts are never stored, uploaded or logged.** Marks keep times, sources and enum or canvas label names.
  - Hub traces: enum names, `kmh` rounded to 5, confidence; never a location, address, device name or BT address.
  - IMU traces: numbers only, sealed, never exported, wiped by Delete all.
  - No photo or memory reaches a model on any 3a path; sweeps make **no model calls**.
  - Trace routes return aggregates only.
  - Logs are counts, hash prefixes and enum names.
  - Never print secrets; never read AssistServer lines.
- **Data safety:**
  - every derived commit re-checks, inside its transaction, the captured `MemoryWipe.generation`, the **persisted deletion epoch** (S57), the tombstones and the source revisions;
  - filesystem publication is fenced (S58);
  - Forget, retention and Delete all reach every new table and file;
  - new phone prefs and folders are in `backup_rules.xml` and `BackupRulesTest` (classification plus both XML sections); new glasses prefs and folders are in `GlassesBackupRulesTest` (S81); none is in `ConfigExport.PREFS_FILES`.
- **Open-source and platform code first:**
  - `MessageDigest` and `GZIPInputStream`/`GZIPOutputStream`;
  - kotlinx `JsonElement` by hand in `:services:*` (`:core:capture` may apply the serialization plugin);
  - `kotlin.random.Random(seed)`, `TileService`, `NotificationCompat`, `BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER`;
  - Chart.js 4.5.1 vendored;
  - Live Fit's own Apache-2.0 command code (S54).
  - No new Gradle dependency except `:core:capture`'s (via `:core:model`; test `kotlin("test-junit")` and `junit:junit` at the glasses' version).
- **Web UI:** as in slice 2: flat names, no HTML-string APIs or `eval`, pure modules with GraalJS tests, and only `floweditor.js` and the view modules touch React or Chart.js.
- **Backward compatibility:**
  - New params default to today's behaviour.
  - **The no-plan (settings) path equals the Task 1 goldens bit for bit**, which are recorded from unmodified `s2/int`. The only allowed difference is a `resv` open where `s2/int` would have gone past the S83 bound. Those cases are listed in the golden file as `expectedResv`.
- **Commits:** one per task unless stated, with the given message, ending with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- FAST MODE: fix only Critical and Important findings.
- No unescaped `}` in a `Regex`, and no accidental non-ASCII in `.kt`.

## Shared interfaces (pinned)
```kotlin
// :core:capture  com.rokid.context.capture.plan  (Task 4)
const val PLAN_SCHEMA = 1; const val PLAN_MAX_BYTES = 8_192
const val RESERVED_ACTIVE_MS = 60_000L; const val RESERVED_REST_MS = 300_000L     // S83, not plan params
enum class PlanState { REST, VEHICLE_PASSENGER, VEHICLE_DRIVING, FLIGHT }        // editable rows only (S32)
data class TimerPhotoGaps(val settledMs: Long, val quietMs: Long, val walkMs: Long, val stillCapMs: Long)   // each 0 (inherit) or 5_000..300_000
data class PlanRow(val capPerMin: Int?, val timerPhotoGapsMs: TimerPhotoGaps, val forceSettle: Boolean, val nudges: Boolean)
data class GlanceBlock(val onsetRadS: Float, val releaseRadS: Float, val bridgeMs: Long, val minBoutMs: Long, val minBoutRad: Float,
                       val settleMs: Long, val noveltyWindowMs: Long, val noveltyMinRad: Float, val bigTurnRad: Float)
data class GateBlock(val bigChange: Int, val hamming: Int, val ringCompare: String /* last|all */)
data class PlanConfig(val glance: GlanceBlock, val policy: Map<PlanState, PlanRow>, val gate: GateBlock, val ctxBound: Boolean,
                      val phoneFusion: Map<String, Double>)
data class PlanMeta(val name: String, val settingsRev: Long, val expectOpensP95PerMin: Map<String, Double>? = null)
data class CapturePlan(val planSchema: Int = PLAN_SCHEMA, val configHash: String, val config: PlanConfig, val meta: PlanMeta)
object PlanJson { fun encode(p: CapturePlan): String; fun decodeStrict(s: String): Result<CapturePlan>; fun configHash(c: PlanConfig): String }
fun reservedBoundMs(state: ContextState, resting: Boolean): Long =
    if (state in setOf(REST, VEHICLE_PASSENGER, VEHICLE_DRIVING, FLIGHT) || (resting && state != WALKING)) RESERVED_REST_MS else RESERVED_ACTIVE_MS

// Pure receiver (Task 4), used by the glasses (Task 11) and by phone tests (Task 23a)
interface PlanPersistence { fun load(): ReceiverState?; fun commit(s: ReceiverState) }
data class ReceiverState(val appliedGen: Long, val phoneInstall: String, val mode: PlanMode, val planJson: String?, val configHash: String?,
                         val trialStartElapsed: Long?, val trialMaxMs: Long?, val bootId: String, val previous: ReceiverState?, val terminal: Map<Long, String>)
class PlanReceiver(persist: PlanPersistence, elapsed: () -> Long, bootId: () -> String) {
    fun onMessage(m: CapturePlanMessage): CapturePlanAck          // stale, duplicate, validate, persist, staged (not yet EFFECTIVE)
    fun activated(gen: Long): CapturePlanAck                       // EFFECTIVE after the safe boundary
    fun tick(state: ContextState, now: Long): CapturePlanAck?       // TRIAL end and reboot, REVERTED
    fun revert(reason: String): CapturePlanAck }

// :core:model Protocol.kt (Task 5); additions over round 1 marked +
CapturePlanMessage(v, gen, publishId?, mode = SETTINGS, configHash?, planJson?, trialEndsAtMs?, +trialMaxMs: Long? = null, +phoneInstall: String = "", atMs, sessionStartMs = 0)
CapturePlanAck(v, gen, publishId?, mode, effectiveHash?, state = REJECTED, reason?, planSchemaMax = 1, caps = [], +phoneInstall = "", atMs = 0, +sessionStartMs = 0)
MarkMessage(v, epoch, seq, atMs, atElapsedMs, bootCount = -1, sessionStartMs = 0, src = "tap", listen = false, listenId?, undo?, +deletionCounter: Long = -1)
MarkAck(v, epoch, seqs)
TraceControl(v, collect, collectUntilMs, +installId: String = "", +deletionCounter: Long = 0, tombstones, forgetFromMs?, forgetToMs?, send, atMs, sessionStartMs)
TraceChunk(v, traceId, kind, seq, count, +installId = "", +deletionCounter = 0, sha256, b64, atMs, sessionStartMs)
TraceAck(v, traceId, ok, reason?, atMs = 0, +sessionStartMs = 0)
CommandEnvelope(..., +listenId: String? = null); HudSettings(..., +planAware: Boolean = false)

// KeyframeGate additions (pinned for Tasks 11, 16, 20)
data class GateParams(val hamming: Int = HAMMING, val bigChange: Int = BIG_CHANGE, val ringCompareAll: Boolean = true)
// ctor + params: () -> GateParams = { GateParams() };  fun interface ScoreListener { fun onScored(seq: Long, d: Long, p: Long, blur: Float, passedBlur: Boolean) }
// fun setScoreListener(l: ScoreListener?);  fun offerScored(seq, d, p, blur, motion, dwellArmed, force, cap, hard, nowMs): GateResult  (same finish path)
```
- **Caps in 3a:** `["plan1", "shadow", "trial"]`. Never `expr` or `windows`.
- **Ack reasons:** `too_big`, `schema`, `bounds`, `version`, `stale`, `unsupported:<cap>`, `auto:ceiling`, `auto:opens`, `auto:trial_end`, `auto:reboot`, `auto:old_phone`, `auto:rejected`.

## Review focus
1. **Move-only** (Task 2): 100 % renames. The goldens from Task 1 pass after the move.
2. **The settings path equals the goldens** (Task 11). The plan changes photo frequency only (S84).
3. **Liveness and walking** (Task 12), with mutation REDs.
4. **Identity and evidence** (Tasks 4, 26, 27): one `configHash` from replay to LIVE; a TRIAL cannot be extended; nothing becomes unrestricted LIVE without a verified ≤ 0.42 row for that `configHash`.
5. **Recovery** (Task 26): ack loss and reordering, crash boundaries.
6. **Deletion** (Tasks 9, 34): the epoch, fenced files, rewrites with new ids, outbox clearing, retention.
7. **Privacy** (Tasks 31, 18): no transcript reaches storage, logs or Ask beyond the unmatched clauses; no location in hub traces.
8. **Battery:** no new glasses wakeups; transfer only while charging.

---

### Task 1: Freeze pre-change decision goldens from `s2/int` (S76, S83) [CORE 1.5 h]
**Stream C · 1.5 h · depends on: base.** It runs **before** Task 2 and touches no main code.

**Files:**
- create `glasses/src/test/java/com/rokid/context/glasses/capture/golden/DecisionGoldenRecorder.kt` (test-only)
- create `glasses/src/test/resources/golden/decisions-*.txt`, one per script
- create `GoldenScripts.kt` with seeded scripts:
  - `unknown_walk_20min`, `rest_still_30min`, `flight_settings_20min`, `workrest_rest_30min`;
  - `budget_t2_20min`, `heat_slow_10min`, `bind_backoff_10min`, `capture_now_mix_10min`.

**Implement:**
- Drive the real `SamplingSession` with `SamplingTestKit` (a fake camera, a fake clock, scripted IMU and frames) under **settings mode** (wave B's `ContextPolicies.DEFAULT` per scripted context state).
- Write one line per decision: `t kind cause detail`, covering glances, novelty and cap skips, opens with their cause, keeps and skips with their reason, and timer gaps.
- Also record, per script, the `MotionClassifier` input count and each glance-trigger-to-open latency (for S84's test).
- `expectedResv`: from the recorded opens, compute where the gap since the last open would exceed `reservedBoundMs` (S83) and list those times. Task 12 must add a `resv` open exactly there and nowhere else.

**Tests:** `DecisionGoldenTest.goldensReproduce`, which replays each script and compares with its file. After Task 2 this test must still pass unchanged.

**Command:** `gw.sh :glasses:testDebugUnitTest --tests '*DecisionGoldenTest'`.

**Commit:** `test(glasses): freeze pre-slice-3a capture decision goldens (settings mode, 8 scripts, expected reserved-attempt points)`

### Task 2: `:core:capture` move-only, the `GlanceAdmission` extract, module deps (A1, S50, P4, S71) [CORE 2.5 h]
**Stream A · 2.5 h · depends on: 1.** It is the same as round 1 Task 1, plus:
- `services/ai/build.gradle.kts` and `services/orchestration/build.gradle.kts` get `api(project(":core:capture"))`.
- `DecisionGoldenTest` (Task 1) is green after each of the two commits.

**Files, Implement:** round 1 Task 1, unchanged:
- `git mv` of `GlanceParams`, `GlanceDetector`, `MotionClassifier`, `MotionModel.kt`, `ImageHashes`, `KeyframeGate` (with `PrefsGateStore` split out), `CameraPolicy` and `CaptureProfile` (split out of `BatteryBudget.kt`), with packages unchanged;
- the moved tests;
- `public` visibility only where needed, each with a comment;
- Commit 2 extracts `GlanceAdmission.decide`.

**Tests:**
- the moved tests are unchanged;
- `GlanceAdmissionTest` (round 1 names and the 64-case truth table);
- `DecisionGoldenTest` green.
- **Mutation RED:** flip `turned < noveltyMinRad` to `<=` → `matchesOnDwellTruthTable` fails.

**Commands:** `gw.sh :core:capture:test`, `gw.sh :glasses:testDebugUnitTest`, `gw.sh :services:ai:testDebugUnitTest --tests 'com.rokid.context.ai.canvas.*'`.

**Commits:**
1. `refactor(capture): move the pure glance, motion, keyframe-gate and camera-policy code into :core:capture (move-only, packages unchanged)`
2. `refactor(capture): extract GlanceAdmission from SamplingSession.onDwell (behaviour-identical, truth-table tested)`

### Task 3: NodeTypes v2, ParamSchema, S41, rooted domains, bindings, backend v2, ASYNC projection (A2, S34, S41, S72, S78) [CORE, trimmed 3 h]
**Stream B · 4 h · depends on: base.** It is round 1 Task 2, plus the following.

**Files (extra):**
- modify `phone/.../canvas/api/CanvasBackendImpl.kt`: `nodeTypes()` serves the v2 fields from the registry instead of the hardcoded ASYNC list
- modify `CanvasBackend.kt`
- `services/ai/.../ai/canvas/Pipeline.kt`: `fun asyncProjection(): Pipeline`

**Implement (extra):**
- **`signal.in`/`signal.out`:** `params.domain` (enum of the allowed domains) is validated per instance, with the text `"node <id>: signal domain <d> not allowed here"`.
- **`asyncProjection()`** keeps only the ASYNC nodes and edges, with the schema-1 layout when the source was ASYNC-only. `PipelineHash` of the projection equals the slice 2 hash of the same ASYNC graph. `CanvasRunner` and `CanvasPublisher` call it.
- **Policy table labels:** the `policy.capture_rate` `table` columns are labelled **"timer photo gap (settled/quiet/walk/still cap)"** (S84 (3)). Each gap accepts 0 ("inherit") or 5–300 s, and `capPerMin` accepts blank ("unchanged") or 1–4.

**Tests (extra over round 1):**
- `signalDomainPerInstance`;
- `asyncProjectionHashEqualsSlice2Hash`;
- `runnerUsesProjection`;
- `nodeTypesRouteServesV2` (phone, real `CanvasBackendImpl`);
- `policyTableColumnsNamedTimerPhotoGap`.

**Commands:** `gw.sh :services:ai:testDebugUnitTest --tests 'com.rokid.context.ai.canvas.*'`, `gw.sh :services:orchestration:testDebugUnitTest --tests 'com.rokid.context.canvas.*'`, `gw.sh :phone:testDebugUnitTest --tests 'com.rokid.context.phone.canvas.*'`.

**Commit:** `feat(ai): canvas NodeTypes v2 with capture-half types, rooted domains and bindings, schema-1 resolution, v2 node-types route, ASYNC projection`

### Task 4: `CapturePlan` config/meta, bounds, validator, resolver, pure `PlanReceiver` (A3, S59, S60, S68, S83, S84, P1) [CORE 3.5 h]
**Stream A · 4 h · depends on: 2.**

**Files:** `core/capture/src/main/kotlin/com/rokid/context/capture/plan/{CapturePlan,PlanJson,CanonicalJson,PlanBounds,PlanValidator,PlanResolver,PlanReceiver}.kt` and their tests; `services/ai/src/test/.../CanonicalJsonParityTest.kt`.

**Implement:**
- **The pinned types.** `configHash` = 12 hex of SHA-256 over `CanonicalJson.encode(config)`. `PlanJson.decodeStrict` rejects unknown keys and **non-finite numbers**.
- **The UTF-8 byte length** of `encode()` must be ≤ 8,192.
- **`PlanBounds`:**
  - the §8 glance and gate bounds (as in round 1);
  - row `capPerMin` null or 1–4;
  - **each timer photo gap 0 or 5,000–300,000** (S83);
  - `phoneFusion` keys on the allowlist, each within ±50 % of the default; `fastKmh` 20–40.
- **`PlanValidator.problems(raw: JsonObject, p: CapturePlan?, bytes: Int)`**, with exact texts:
  - `"plan is <n> bytes (max 8192)"`;
  - `"<field> out of bounds <lo>–<hi>"`;
  - `"policy row <S>: capPerMin must be 1–4 or unchanged"`;
  - `"policy row <S>: timer photo gaps must be 0 (inherit) or 5–300 s"`;
  - `"VEHICLE_DRIVING nudges are always off"`;
  - `"policy row <S> is locked"` (WALKING, UNKNOWN);
  - `"pause is not allowed"`;
  - `"MOTION wake is settings-only"`;
  - `"fusion.<k> is not tunable"`;
  - **`"<k>: sensing and detection are not plan parameters"`** for any key under any block named `sampleRate*`, `sensor*`, `imu*Hz`, `tick*`, `fusionCadence*`, `evaluate*`, `detector*` or `classifier*` (S84);
  - `"plan schema <n> is not supported"`;
  - `"<field> is not a finite number"`.
- **`PlanResolver` (S27, S68, S83):**
  - `resolve(partial, base)` fills from `base`;
  - `identity(base)`: the rows equal `ContextPolicies.DEFAULT` exactly (REST cap 2 with gaps 30/60/30/120 s and `forceSettle`; FLIGHT cap 1 with 120/300/120/300 s; PASSENGER and DRIVING cap null with gaps 0, DRIVING `nudges = false`), and the glance and gate values equal today's defaults. **No clamping.**
  - `toContextPolicy(row)` maps back to `ContextCapturePolicy` exactly.
- **`PlanReceiver`** implements the pinned API:
  - `gen < appliedGen` → `stale`; `gen == appliedGen` → re-ack without re-applying;
  - a terminal (REVERTED) gen is never re-applied;
  - mode-specific required fields: TRIAL needs `trialMaxMs` in 1..3,600,000, else `bounds`; LIVE, TRIAL and SHADOW need `planJson` and `configHash`;
  - validates, then persists with `commit`, staged;
  - `activated(gen)` acks EFFECTIVE;
  - `tick`: TRIAL elapsed ≥ `trialMaxMs` → revert `auto:trial_end`; `bootId` changed during a TRIAL → revert `auto:reboot`;
  - a duplicate never resets `trialStartElapsed`.

**Tests:**
- `PlanValidatorTest`: round 1's refusal list with the new texts, plus:
  - `acceptsRestGap300s`, `refusesGap301s`, `refusesGap4s`, `acceptsGapZeroInherit`, `acceptsNullCap`;
  - `refusesSensingKey`, `refusesNaN`;
  - `utf8BoundaryMultibyteName` (8,192 accepted, 8,193 refused when a multi-byte character crosses the boundary).
- `PlanJsonTest`: `configHashIgnoresMeta`, `configHashStableAcrossKeyOrder`, `strictDecodeRejectsUnknownKey`, `roundTripExact`.
- `PlanResolverTest`:
  - **`identityEqualsDefaultPoliciesExactly`** (every row field, including REST 120 s and FLIGHT 300 s);
  - `identityVehicleRowsUnchangedNoHardFlag`;
  - `toContextPolicyRoundTrip`.
- `PlanReceiverTest`:
  - `staleRejected`, `duplicateReackedNotReapplied`, `terminalGenNeverReapplied`;
  - `trialNeedsMaxMs`, `trialMaxOver60MinRejected`;
  - `trialEndsOnElapsedNotWallClock`, `wallClockRollbackDoesNotExtend`, `duplicateDoesNotExtendTrial`;
  - `rebootDuringTrialReverts`;
  - `stagedNotEffectiveUntilActivated`.
- `CanonicalJsonParityTest`.
- **Mutation REDs:** remove the WALKING-row refusal → `refusesWalkingRow` fails; let a duplicate reset the trial start → `duplicateDoesNotExtendTrial` fails.

**Commands:** `gw.sh :core:capture:test`, `gw.sh :services:ai:testDebugUnitTest --tests '*CanonicalJsonParityTest'`.

**Commit:** `feat(capture): CapturePlan config/meta with configHash, 8 KB UTF-8 cap, per-state timer photo gaps 5–300 s, sensing-key refusal, exact identity plan, pure PlanReceiver with local TRIAL enforcement`

### Task 5: Wire, `listenId`, `planAware`, ack correlation, old-peer fixtures (A6, S24, S74) [CORE, trimmed 1.5 h]
**Stream C · 3 h · depends on: base.**

**Files:** as round 1 Task 4, plus:
- the pinned `+` fields;
- `HudSettings.planAware`;
- frozen fixture JSON under `core/model/src/test/resources/compat/` (old `CommandEnvelope`, old `HudSettings`, an old glasses peer that subscribes to none of the new channels);
- a glasses `onReady` hook that sends an unsolicited caps ack (the plumbing only; the content comes in Task 11).

**Implement:** round 1, plus:
- **Ack correlation** on the phone side: `Hub` passes acks through only when `phoneInstall` matches this install and `sessionStartMs` matches the current session. Others are counted `ackStale`.
- **`MarkMessage` is accepted regardless of `atMs < sessionStartMs`** (occurrence time). Only `v` is gated.

**Tests:**
- round 1's tests;
- `oldGlassesNeverAcksPhoneShowsTooOld` (with the fixture peer);
- `oldPhoneHudSettingsDecodesPlanAwareFalse`;
- `capsAckOnReadyWithoutPlan`;
- `ackFromOtherInstallIgnored`, `ackFromOldSessionIgnored`;
- `queuedMarkOlderThanSessionAccepted`;
- `oldDecoderIgnoresListenId` (frozen copy).

**Commands:** as round 1 Task 4.

**Commit:** `feat(link): additive capture-plan, mark and trace channels, listenId, planAware and ack correlation with frozen old-peer fixtures (protocol v1)`

### Task 6: Trace schema freeze (A8 pure part, S65, S66, S73; reviewed by Opus before Tasks 16 and 20 start) [CORE 2.5 h]
**Stream A · 2.5 h · depends on: 2.**

**Files:** `core/capture/src/main/kotlin/com/rokid/context/capture/trace/{TraceRecords,ChunkCodec,Manifest,ClockMap,TraceBounds}.kt` and their tests.

**Implement:**
- **Events** (all `tMs` = glasses `elapsedRealtime`):
  - `Gyro`, `Accel`;
  - `Ctx(state)`, `Resting(on)`;
  - `Profile(wake, settled, quiet, walk, stillCap, analysisMs, tier, lowPower, heatSlow)`;
  - `GateParams(hamming, bigChange, ringAll)`;
  - `Plan(gen, configHash, mode)`;
  - `Episode(open|close, cause: timer|dwell|onset|now|resv|stream, firstFrameAtMs?)`;
  - `Score(seq, d, p, blur, passedBlur, motion, dwellArmed, force)`;
  - `Gate(seq, outcome, reason)`;
  - `Flush(reason)`;
  - `Backoff(stillStep)`;
  - `Blocked(reason?)`;
  - `PreRollEnd`;
  - `Close(reason)`.
- **`StartSnapshot`:** settings hash, ring, `lastKeyframeAt`, `keptAt`, plan gen and hash, the glance params, the applied ctx, resting, tier, `sampleEvery`, `exact`.
- **Clean boundary:** the recorder may start only when the detector is QUIET, no episode is open and the gate window is empty. `PreRollEnd` comes 10 s after the start, and the simulator discards decisions before it.
- **Manifest-lite:** as round 1, plus `installId`, `deletionCounter`, `revision`, `exact`.
- **`TraceBounds`** (S73): the b64, gunzip, events-per-chunk, chunk-count and `traceId` regex limits, enforced on decode (a streamed gunzip limit).

**Tests:**
- `roundTripEveryEventType`, `floatBitsExact`;
- `gunzipBombRejected`, `tooManyEventsRejected`, `badTraceIdRejected`, `tooManyChunksRejected`;
- the manifest COMPLETE/PARTIAL cases (round 1);
- `clockMapStartMidEnd`;
- `downsampledFlaggedNotExact`.

**Command:** `gw.sh :core:capture:test`.

**Commit:** `feat(capture): frozen IMU trace schema (scheduling inputs, scores, flushes, clean start with 10 s pre-roll), bounded codec, manifest-lite with epoch and revision`

### Task 7: `PlanCompiler` (A4, S8, S27, S68, S78) [CORE, trimmed 2 h]
**Stream B · 3.5 h · depends on: 3, 4.**

**Files:** as round 1 Task 6, with `/api/plans/compile` in `PlanRoutes.kt`, registered in `CanvasApi.kt` (owned here).

**Implement:** round 1, changed as follows:
- the **S78 topology** (a fixed chain, so a branch or reorder → `"unsupported wrapper order: <a> before <b>"`);
- **operators:** `gyroPeak` allows only `>` (`">= is not supported for gyroPeak: onset is strict >"`), and `boutMs` and `boutRad` allow only `>=`;
- **policy rows:** blank cap → null, blank gap → 0;
- **the S68 warning:** a gap below the settings profile has no effect, and the compiler adds the warning `"timer photo gap below the settings profile has no effect"` (a warning, not a problem);
- **output:** `CompileResult(plan, planJson, problems, warnings, staticCost)`. There is **no `clamped` field**.

**Tests:**
- round 1's, plus:
  - `goldenCompilesExactly` (the resource updated to the config/meta layout);
  - `identityGraphEqualsIdentityPlanBitForBit`;
  - `gyroPeakGteRefused`, `boutMsGtRefused`;
  - `thresholdBoundaryExact` (onset 0.6 > vs a 0.6 sample in the replay fixture);
  - `reorderedPolicyBeforeNoveltyRefused`, `branchRefused`, `disconnectedBindingRefused`;
  - `gapBelowProfileWarns`, `blankCapIsNull`.
- **Mutation RED:** fold `>=` into onset → `gyroPeakGteRefused` fails.

**Commands:** as round 1 Task 6.

**Commit:** `feat(ai): PlanCompiler over the fixed wrapper chain (strict operators, per-state timer photo gaps, fusion subset) with the exact identity and §1 goldens`

### Task 8: Room v7 schema and DAOs (A8 part, S75, S77) [CORE, with 9: 4.5 h]
**Stream B · 3 h · depends on: base.**

**Files:** round 1 Task 7's schema part:
- `CanvasSlice3Entities.kt`, the DAOs, `MIGRATION_6_7`, `7.json`;
- `Migration6To7Test`, `DatabaseFactoryUpgradeTest`, `CanvasSlice3DaoTest`.

**Changes over round 1:**
- `canvas_publish` gains `configHash` instead of `planHash`.
- `canvas_trace` gains `revision`, `parentTraceId`, `installId`, `deletionCounter`, `exact`.
- `canvas_replay` and `canvas_sweep` gain `sourcesJson`, `labelRev`, and a `jobState` of `RUNNING|DONE|CANCELLED|ABORTED`.
- New `canvas_meta(key PK, value)` holds the `labelRev` counter.
- The `canvas_calibration` columns are `configHash`, `artifactJson`, `verified`, and so on.
- New `canvas_group_state(groupId PK, gen, publishId, configHash, component, state, updatedAt)`, the S62 recovery table.
- `canvas_label`: there is no text column (as round 1).

**Fenced DAO writes:** round 1's set, plus:
- `bumpLabelRev()`, inside every label write;
- `invalidateBySources(traceIds, fromMs, toMs, labelRevChanged)`;
- `markRunningAborted()`, called at start-up.

**Tests:**
- round 1's, plus:
  - `labelWriteBumpsLabelRev`;
  - `replayCommitBlockedWhenLabelRevMoved`;
  - `startupAbortsRunningJobs`;
  - `groupStateRoundTrip`.
- **Mutation RED:** remove the tombstone check from `commitReplayFenced` → `replayCommitBlockedByTombstone` fails.

**Commands:** `gw.sh :services:memory:testDebugUnitTest`.

**Commit:** `feat(db): Room v7 migration A (capture-plan publish columns, traces with revisions, events, replays and sweeps with provenance and job states, canvas_label, calibration, group state, helper jobs), fenced DAOs`

### Task 9: Persisted deletion epoch, Delete all, tmp sweep, backup rules (S57, S58, S81) [CORE, with 8]
**Stream B · 2.5 h · depends on: 8.**

**Files:**
- create `services/memory/.../db/DeletionEpochStore.kt` (phone prefs `deletion_epoch`: `installId` (a random UUID on first use), `counter`, `wipedAtMs`)
- modify `WipeDao`/`ForgetService` Delete all: **commit `counter + 1` and `wipedAtMs` first**, then delete the new tables and the folders `files/canvas_traces`, `files/canvas_import`, `files/canvas_tmp`
- create `services/orchestration/.../canvas/trace/FencedFiles.kt`: `publish(tmp, final, fence: suspend () -> Boolean)` renames only when the fence holds inside the transaction, and `sweepTmp()` runs at start-up
- extend `phone/src/main/res/xml/backup_rules.xml` and the data-extraction rules (cloud and device-transfer) with `deletion_epoch`, `canvas_plan_state`, `canvas_collect`, `mark_settings`, `canvas_traces`, `canvas_import`, `canvas_tmp`; extend `BackupRulesTest` (its classification and both XML sections)
- extend `glasses/src/main/res/xml/backup_rules.xml` and the existing `GlassesBackupRulesTest` with `capture_plan`, `mark_queue`, `imu_trace` and `imu_trace_tmp`, keeping its blanket exclusions

**Tests:**
- `epochCommittedBeforeDelete` (a crash injected after the commit → the next start sees the new counter);
- `installIdStable`;
- `deleteAllRemovesFolders`;
- `fencedPublishSkipsAfterWipe`, `tmpSweptAtStartup`;
- the backup tests.

**Commands:** `gw.sh :services:memory:testDebugUnitTest`, `gw.sh :services:orchestration:testDebugUnitTest --tests 'com.rokid.context.canvas.trace.*'`, `gw.sh :phone:testDebugUnitTest --tests 'com.rokid.context.BackupRulesTest'`, `gw.sh :glasses:testDebugUnitTest --tests '*GlassesBackupRulesTest'`.

**Commit:** `feat(data): persisted pairing-scoped deletion epoch committed before Delete all, fenced file publication, tmp sweep, slice 3a backup exclusions`

### Task 10: Encrypted migration device suite and upgrade paths (S64) [LATER LA1]
**Stream A · 1.5 h · depends on: 8.** The agent writes it; **only the controller runs it** (Task 38).

**Files:**
- extend the encrypted `androidTest` suite in `services/memory/src/androidTest/...` (find the class that `DatabaseFactoryUpgradeTest` delegates to): it seeds a canonical v6 SQLCipher DB through **both** production builders, migrates to v7, and checks row counts, the columns and `PRAGMA integrity_check`
- generalise the fixture helpers from "current = v6" to "current = `ContextDatabase.VERSION`"

**Tests:** `encryptedV6ToV7BothBuilders`, `encryptedV1ToV7Chain`. A JVM compile check: `gw.sh :services:memory:compileDebugAndroidTestKotlin`.

**Commit:** `test(db): encrypted v6->v7 migration coverage through both production builders (device suite, controller-run)`

### Task 11: Glasses staged atomic apply, local TRIAL, provider params (A5 part, S59, S67, S68, S69) [CORE 3 h]
**Stream C · 3.5 h · depends on: 4, 5.**

**Files:**
- create `glasses/.../capture/plan/PrefsPlanPersistence.kt` (prefs `capture_plan`, `commit()`), `PlanController.kt`, `ActiveConfig.kt`
- modify:
  - `MotionClassifier`: `@Volatile var config` replaces the constructor `params` reads everywhere; `setConfig` takes effect at the safe boundary;
  - `KeyframeGate`: the `GateParams` provider;
  - `SamplingSession`: `glanceNow`;
  - `CaptureService`: the profile composition per S68, and `ContextPolicies.of(applied, plan.rows ?: DEFAULT)`;
  - `GlassesRuntime`: wire `PlanController` and send the caps ack on ready;
  - `HudController`: `planAware`.
- tests: `PlanControllerTest.kt`, `PlanLiveApplyTest.kt`, `StagedActivationTest.kt`; extend `MotionClassifierTest`, `KeyframeGateTest`

**Implement:**
- **`PlanController`** wraps `PlanReceiver`. On a staged plan it builds one `ActiveConfig` and activates it when the detector is QUIET **and** no episode is open; after 10 s it aborts the bout instead. Then it calls `receiver.activated(gen)` and sends EFFECTIVE.
- **`planAware = false`** with a persisted plan → revert `auto:old_phone`.
- **Apply:** LIVE and TRIAL only. The camera wake is SETTLE. The rows go through `withContext`, then `forRest()`, then heat and battery (S68). Row `capPerMin` null → the context cap is null (no hard flag).
- **SHADOW and SETTINGS** leave the real capture inputs untouched.

**Tests:**
- `settingsPathEqualsGoldens` (Task 1 files; diffs only at `expectedResv`, which Task 12 adds);
- `identityPlanLiveEqualsGoldens` (every script, including REST 120 s, FLIGHT 300 s, WorkRest, T2 and heat-SLOW);
- `liveCapOneLimitsKeeps`, `onsetFromPlanChangesBoutStart`;
- `vetoedVehicleGetsFullRateRow`, `speedLiftsVeto`, `ctxUnboundAppliesUnknown`;
- `heatStopBeatsPlan`, `userPauseBeatsPlan`;
- `settingsEditAfterPublishDoesNotChangeLivePlan`;
- `stagedActivationWaitsForQuiet`, `activationAbortsBoutAfter10s`, `effectiveOnlyAfterActivation`, `noMixedOldDetectorNewCaps`;
- `oldPhoneRevertsPersistedPlan`;
- `shadowLeavesCaptureInputsUntouched`.
- **Mutation REDs:** apply the gate params before activation → `noMixedOldDetectorNewCaps` fails; drop the WALKING default row → `vetoedVehicleGetsFullRateRow` fails.

**Commands:** `gw.sh :core:capture:test`, `gw.sh :glasses:testDebugUnitTest --tests '*PlanControllerTest' --tests '*PlanLiveApplyTest' --tests '*StagedActivationTest' --tests '*DecisionGoldenTest'`, then `gw.sh :glasses:testDebugUnitTest`.

**Commit:** `feat(glasses): capture plan via PlanReceiver with staged atomic activation, local TRIAL limit, S68 profile composition (settings path equals the frozen goldens)`

### Task 12: Reserved timer photo attempt, SHADOW counts, auto-revert, heartbeat, sensing-unchanged golden (A5 rest, S61, S67, S70, S83, S84) [CORE, trimmed 3 h]
**Stream C · 4 h · depends on: 11.**

**Files:**
- create `ShadowRunner.kt`, `AutoRevert.kt`
- modify:
  - `MotionClassifier`: `addTap(SampleTap)`, as round 1;
  - `SamplingSession`:
    - `lastPhotoAttemptAtMs` = the start of the last episode that delivered a first frame;
    - a distinct cause `resv`: when eligible and `now − lastPhotoAttemptAtMs ≥ reservedBoundMs(applied, resting)`, start a brief episode with cause `resv`;
    - it bypasses `timerHoldUntil`, the policy gaps and novelty; dedupe, blur and the hard ceiling still apply;
    - it is active in **every mode** and uses no new timer (the existing tick);
  - `Heartbeat`: ` plan=<hash8|none> planHash12=<…|none> gen=… planMode=… ruleSkip1m=0 exprErr=0 planRevert=… resv=… shOpen=… shSkip=… worn=0|1 charging=0|1 persistLog=0|1 chargeUah=<n|na>`.
- tests: `ReservedAttemptTest.kt`, `ShadowRunnerTest.kt`, `AutoRevertTest.kt`, `CaptureNowPrecedenceTest.kt`, `SensingUnchangedTest.kt`; extend `HeartbeatTest`, `DecisionGoldenTest`

**Implement:**
- **SHADOW:** as round 1 (a second detector through the tap; would-open and would-skip counts only).
- **Auto-revert:** round 1's rules. The p95 comes from `meta.expectOpensP95PerMin`.
- **Glance and manual triggers:** `pendingDwellAt` and `pendingNowAt` keep starting episodes regardless of timer photo gaps (today's code). A test locks it in.

**Tests:**
- `ReservedAttemptTest`, through the real session and camera owner fakes:
  - `within60sInUnknownUnderRestrictivePlan`, `within60sWhileWalking`;
  - `within300sInRestWithGap300s`, `within300sInFlight`, `within300sWhileWorkRestResting`;
  - `continuousHeadMotionStillBounded`, `stillBackoffBounded`, `heatSlowBounded`, `bindBackoffNotEligibleThenFires`, `failedOpenDoesNotResetDeadline`;
  - `notWhilePausedNotWornHeatStoppedCameraBusy`;
  - `ignoresCapHoldButNotHardCeiling`;
  - **`goldensPlusExactlyExpectedResv`** (Task 1's `expectedResv` points, and no others).
- **`SensingUnchangedTest.timerPhotoGapLeavesSensingUnchanged`** (S84 (4)): the identity plan against the same plan with every REST, VEHICLE and FLIGHT gap at 300 s, over `rest_still_30min` and `unknown_walk_20min`:
  - identical sensor registration calls and rates;
  - an identical `MotionClassifier` input count;
  - identical glance-trigger-to-open latencies;
  - only the timer-cause opens differ.
- `glanceNeverWaitsForTimerGap`, `captureNowNeverWaitsForTimerGap`.
- `ShadowRunnerTest`: `countsWouldOpen`, `neverOpensCamera`, **`shadowCaptureEqualsSettingsUnderRestrictiveFlightAndRest`**.
- `AutoRevertTest` (round 1's names).
- `CaptureNowPrecedenceTest` (round 1's names).
- `HeartbeatTest`: the key order and defaults.
- **Mutation REDs:** suppress `resv` → `within300sInRestWithGap300s` fails; gate the dwell on the timer gap → `glanceNeverWaitsForTimerGap` fails; let the plan change `SENSOR_DELAY` → `timerPhotoGapLeavesSensingUnchanged` fails.

**Commands:** `gw.sh :core:capture:test`, `gw.sh :glasses:testDebugUnitTest --tests '*ReservedAttemptTest' --tests '*SensingUnchangedTest' --tests '*ShadowRunnerTest' --tests '*AutoRevertTest' --tests '*CaptureNowPrecedenceTest' --tests '*HeartbeatTest' --tests '*DecisionGoldenTest'`, then the whole module.

**Commit:** `feat(glasses): reserved timer photo attempt (60 s active, 300 s at rest, every mode), SHADOW counts, auto-revert, verification heartbeat fields; plan never touches sensing`

### Task 13: Phone RT apply (A7) [LATER LA2]
**Stream A · 1.5 h · depends on: 4, 5.** It is round 1 Task 10, and the hub tick and evidence intake are unchanged (S84). It produces the class only; Task 22 wires it.

**Extra test:** `setParamsKeepsEvaluationCadence`.

**Commit:** `feat(context): FusionParams from the capture plan (setParams keeps state and cadence; walking veto fixed)`

### Task 14: Live Fit command layer port with the S85 data table (A9 voice, brief M2, S54, S85) [CORE 2.5 h]
**Stream C · 3 h · depends on: base.**

**Files:**
- round 1 Task 11's, plus `services/voice/.../commands/MarkPhrases.kt`: the **single source** for the S85 tables, holding `MARK`, `RETRO`, `UNDO`, `INTENTS: List<IntentEntry(word, canvasLabel, intentLabel: String /* final IntentLabel name, filled here */)>` and `QUESTION_WORDS`
- tests: `MarkCommandParserTest.kt` (data-driven), `EnglishClausesTest.kt`, `VoiceCommandGroupTest.kt`, `LanguagePackTest.kt`

**Implement:**
- `sealed interface MarkCommand { Mark(intent: String?), Retro, Undo }`.
- Normalise as round 1. A rule matches only at the start of a clause, after an optional filler.
- Apply the question-word veto, keeping the two "can you" / "could you" exceptions.
- **"forget" never matches.**
- An intent is read only after a command, through the optional fillers; negation, conflict or an unknown word → `Mark(null)`.
- **Finalise the `IntentLabel` column** (`SITTING_IDLE`, `WORKOUT`, `COMPUTER_WORK`, `DRIVING`, `VEHICLE_PASSENGER`, `FLIGHT_CABIN`, `WALKING`, `OTHER`) with the S85a mapping (reading and writing → COMPUTER_WORK, travelling → VEHICLE_PASSENGER, riding → OTHER). It also appears in Task 15's generated doc.

**Tests:**
- **`everyListedPhraseParses`:** for each MARK, RETRO and UNDO phrase, alone, with "please", and with a trailing intent.
- **`everyIntentWordAfterCommandParses`:** "mark that, <word>", "mark that, I'm <word>", for every word.
- **`questionFormsNeverParse`**, a data list built from every command phrase: "did you <cmd>", "what did I <verb>", "is this important", "was that important", "should I save this", "how do I bookmark this", "can I note this", "where did I save that", "why did I miss that".
- `canYouMarkThatParses`.
- `forgetNeverMatches` ("forget that", "forget this", "forget the last mark").
- `intentWithoutCommandPassesThrough` ("I'm driving").
- `negatedIntentPlainMark`, `twoIntentsPlainMark`, `unknownWordPlainMark`.
- `undoPhrases`.
- `everyIntentHasFinalIntentLabel` (each name exists in `IntentLabel`) and `ridingMapsToOther`.
- The round 1 negatives ("Mark called me", "what did Mark say", …).
- `EnglishClausesTest`, as Live Fit has it.

**Commands:** `gw.sh :services:voice:testDebugUnitTest --tests 'com.rokid.context.voice.commands.*'`, then the whole module.

**Commit:** `feat(voice): mark-moment command layer adapted from Live Fit with the S85 phrase table, anchored rules, question-word veto and final IntentLabel mapping`

### Task 15: Voice-mark user docs generated from `MarkPhrases`, plus a doc-drift test (S85) [CORE 1 h]
**Stream C · 1 h · depends on: 14.**

**Files:**
- create `services/voice/.../commands/MarkPhrasesDoc.kt`: `fun render(): String`, the markdown tables from `MarkPhrases`
- create `docs/voice-marks.md` with the generated block between `<!-- voice-marks:begin -->` and `<!-- voice-marks:end -->`, plus hand-written text covering:
  - **how to mark:** tap the glasses (HUD visible), say a command after push-to-talk on the glasses or the phone, or use the phone notification, tile or home button;
  - **commands**, **intent words** and **undo**;
  - **"I missed that"**;
  - **not supported:** Hindi and other languages (later), the word "forget", any hotword or always-on listening, questions phrased as commands;
  - **how a word maps to an intent label** (the generated table);
  - **privacy:** audio is never stored or uploaded, recognition runs on the phone's on-device recogniser only, and only the time, source and label are kept.
- modify `README.md`: a short "Voice marks" section (5–8 lines, a link to `docs/voice-marks.md`, the generated compact command list between `<!-- voice-marks-readme:begin/end -->`)
- test: `services/voice/src/test/.../commands/VoiceMarksDocTest.kt` (it finds the repo root by walking up, like `MotionModelTrainedTreeTest`)

**Tests:**
- `docBlockEqualsRender`;
- `readmeBlockEqualsRenderCompact`;
- `everyPhraseInTableAppearsInDoc`;
- `noPhraseInDocMissingFromTable`;
- `docMentionsPrivacyAndUnsupported` (fixed sentences present).

**Command:** `gw.sh :services:voice:testDebugUnitTest --tests '*VoiceMarksDocTest'`.

**Commit:** `docs(voice): voice marks guide and README section generated from the phrase table, with a doc-drift test`

### Task 16: Glasses IMU recorder (A8 part, S65, S66, S57) [CORE 3 h]
**Stream C · 3 h · depends on: 6, 12.**

**Files:**
- round 1 Task 12's recorder parts;
- the `KeyframeGate.setScoreListener` implementation (pinned);
- `SamplingSession` emits the Task 6 events, including `Episode`, `Flush`, `Backoff`, `Plan` and `Close`.

**Implement:**
- **Window and buffering:** round 1 (records only while collecting and eligible; pauses on privacy pause; 64 KB flush on sample arrival; 150 MB cap).
- **Boundaries:** a trace starts only at a clean boundary, with a 10 s pre-roll. `exact = (sampleEvery == 1)`.
- **Epoch:** `TraceControl` with a new `deletionCounter` or `installId` → drop the buffer, delete the local traces and `imu_trace_tmp`, and adopt the epoch.
- **Writes:** chunk files are written to `imu_trace_tmp`, then renamed.

**Tests:**
- round 1's;
- `startsOnlyAtCleanBoundary`, `preRollMarked`;
- `scoreEmittedForBlurFailure`;
- `closeAndPlanEventsEmitted`;
- `newEpochDropsBufferAndTraces`, `newInstallIdResets`;
- `exactFalseWhenDownsampled`.

**Commands:** `gw.sh :core:capture:test`, `gw.sh :glasses:testDebugUnitTest --tests 'com.rokid.context.glasses.capture.trace.*'`.

**Commit:** `feat(glasses): IMU trace recorder on the frozen schema (clean start, pre-roll, every score), epoch-scoped wipe`

### Task 17: Glasses trace transfer (S73) [LATER LA3]
**Stream C · 2 h · depends on: 16.**

**Implement:**
- **A send needs local glasses `charging` and the link to be up.** Unplugging or disconnecting stops at once and keeps the trace.
- The manifest goes first, then the chunks in order, one in flight; the next chunk follows the link's send callback.
- **Timeout:** no `TraceAck` within 10 min of the last chunk → resend the whole trace, at most 3 times. `ok = false` → resend the whole trace.
- Tombstones and Forget ranges are handled as P7.

**Tests:** `noSendOnBatteryPower`, `unplugMidTransferStops`, `lostFinalAckResendsWhole`, `badAckResendsWhole`, `threeFailuresKeepTrace`, `tombstoneDeletes`, `forgetRangeDeletesWhole`.

**Command:** `gw.sh :glasses:testDebugUnitTest --tests 'com.rokid.context.glasses.capture.trace.*'`.

**Commit:** `feat(glasses): trace transfer only while the glasses charge, stop on unplug, whole-trace retry on timeout or bad ack`

### Task 18: Phone trace intake (S73, S57, S58) [LATER LA3]
**Stream B · 3 h · depends on: 5, 6, 9.**

**Implement:**
- round 1 Task 13's intake;
- `TraceBounds` checks on every chunk;
- reject a chunk from an older `deletionCounter` or another `installId`, or for a tombstoned id;
- **no chunk for 2 min after the manifest** → PARTIAL and `TraceAck(false, "missing")`;
- stored through `FencedFiles.publish`;
- the 500 MB phone cap;
- route `GET /api/traces`, aggregates only (registered in Task 22).

**Tests:** round 1's, plus `oldEpochChunkRejected`, `otherInstallRejected`, `missingChunkTimeoutAcksFalse`, `malformedB64Rejected`, `wipeDuringTransferPublishesNoFile`.

**Commands:** `gw.sh :services:orchestration:testDebugUnitTest --tests 'com.rokid.context.canvas.trace.*'`.

**Commit:** `feat(canvas): phone trace intake with bounds, epoch and tombstone fences, missing-chunk timeout and fenced file publication`

### Task 19: Hub trace recorder, Collect for tuning, pulled-trace import (A8 part) [CORE, trimmed 1.5 h; hub recorder LATER LA4]
**Stream A · 2.5 h · depends on: 6, 8, 9.**

**Implement:** round 1 Task 13's recorder, session and import parts. Changed:
- **`CollectSession` publishes `TraceControl`** with the persisted `installId` and `deletionCounter`, and `send = true` (the glasses check their own charging).
- **The hub tap is read-only and changes no hub tick** (S84).
- `tools/trace-pull.sh` is controller-run.

**Tests:** round 1's, plus `controlCarriesPersistedEpoch` and `hubTapDoesNotChangeTickCadence`.

**Commit:** `feat(canvas): hub trace recorder (enum names, speed to 5 km/h), Collect for tuning with the persisted epoch, pulled-trace import`

### Task 20: `CaptureSimulator` exact replay (A10 part, S65, S77) [CORE 3 h]
**Stream A · 3.5 h · depends on: 2, 4, 6.**

**Implement:**
- **Input:** `exact(events: Sequence<Ev>, m: Manifest)`, streamed chunk by chunk.
- **Classifier and admission:** `MotionClassifier` from the snapshot's glance params; dwell → `GlanceAdmission`.
- **Scheduler recomputed:** `CameraPolicy.decide` every 250 ms from the recorded `Profile`, `Backoff`, `Blocked`, `Ctx` and `Resting`, plus the reserved attempt (S83). The recomputed episode starts **must equal** the recorded `Episode(open)` events.
- **Frames:** `Score` events go to `KeyframeGate.offerScored`, the window and `Flush` behaviour reproduce selection, and the gate outcomes must equal the recorded `Gate` events.
- Decisions before `PreRollEnd` are discarded. A trace with `exact = false` → `IllegalArgumentException("approximate trace")`.

**Tests:**
- `exactReplayMatchesGoldenSessionTrace` (fixtures produced by Task 23a from the **real session**; until then a committed trace from Task 1's script driver);
- `recomputedOpensEqualRecorded`;
- `windowSelectionReproduced`;
- `preRollDiscarded`;
- `approximateTraceRefused`;
- `streamsWithoutMaterialising` (a 12 h synthetic stream stays under a fixed heap bound);
- `deterministic`.
- **Mutation RED:** replay with the recorded opens echoed back → `recomputedOpensEqualRecorded` must still catch a broken `CameraPolicy` input (shown by an injected `stillStep` mismatch).

**Command:** `gw.sh :core:capture:test`.

**Commit:** `feat(capture): exact capture replay that recomputes the scheduler and gate selection from the frozen trace schema (streamed)`

### Task 21: Counterfactual replay, battery line, replay API, job lifecycle (A10 rest, S77, S80) [CORE, trimmed 2 h]
**Stream A · 3 h · depends on: 8, 20.**

**Implement:**
- round 1 Task 15's counterfactual replay and warnings;
- **`BatteryLine`:** `pctPerMin = 0.433 + 0.0239 · camOpenSPerMin`; **hours = 100 / pctPerMin / 60**;
- **`ReplayService`:**
  - one job at a time (a mutex), with `jobState` persisted;
  - cancelled on thermal SEVERE, service shutdown or Developer mode off;
  - `ABORTED` after process death (`markRunningAborted`);
  - `sourcesJson` and `labelRev` recorded;
  - committed through the fenced DAO;
- `ReplayRoutes.kt` is written here and registered in Task 22.

**Tests:**
- round 1's, plus:
  - `a3Hours3_40` (2.4 s/min → 0.49 %/min → 3.40 ± 0.01 h), `a2Hours2_78`;
  - `oneJobAtATime`;
  - `cancelledOnDeveloperModeOff`, `abortedAfterRestart`;
  - `sourcesRecorded`.

**Commands:** `gw.sh :core:capture:test`, `gw.sh :services:orchestration:testDebugUnitTest --tests 'com.rokid.context.canvas.plan.*'`.

**Commit:** `feat(canvas): counterfactual replay (point-estimate keeps, unobserved share, fusion warning), battery line in hours, single-job replay service with persisted job states`

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

### Task 23: M1 integration milestone [CORE 2.5 h]
**23a, code · Stream C · 2 h · depends on: 16, 20, 21, 22.**

**Files:** `glasses/src/test/.../TraceParityTest.kt`; `phone/src/test/.../IdentityPlanEndToEndTest.kt`.

**Tests:**
- `sessionRecordingReplaysBitIdentical` (the real `SamplingSession` + recorder, then `CaptureSimulator.exact`, over Task 1's scripts);
- `identityPlanSessionEqualsSettingsGoldens`;
- `shadowCountsEqualCounterfactualOpens`;
- `IdentityPlanEndToEndTest`: compile → message → the **real `PlanReceiver`** → EFFECTIVE, with matching `configHash` and `phoneInstall`.
- **Mutation REDs:** change one byte of `config` after the compile → `IdentityPlanEndToEndTest` fails on the hash; drop a `Score` event → `sessionRecordingReplaysBitIdentical` fails.

**Commit:** `test(canvas): M1 trace parity on the real session and identity plan end to end through the real PlanReceiver`

**23b, device · controller with the user · about 1 h worn.**
- Back up both apps first. The controller installs.
- Publish the identity plan with the **debug SHADOW** route.
- A 20-min collection, worn; transfer while charging, or use `tools/trace-pull.sh`.
- Exact parity, and `shOpen` within ±5 % of the counterfactual.
- Read only `files/capture_diag.log`. **Never print AssistServer lines.**

Task 27 lands only after this passes.

### Task 24: Sweeps, objectives, holdout, Pareto, provenance (A11, S16, S46, S75) [CORE, trimmed 2 h; rest LATER LA6]
**Stream A · 3.5 h · depends on: 21.**

**Implement:** round 1 Task 17, without the calibration import (now Task 25), plus:
- sweepable paths include `policy.<S>.timerPhotoGapsMs.*` (0 or 5–300 s);
- sensing keys are refused (S84);
- `sourcesJson` and `labelRev` are stored;
- **`SweepService`'s constructor has no model dependency.**

**Tests:**
- round 1's (excluding the calibration ones), plus:
  - `sweepGapUpTo300s`, `sweepSensingKeyRefused`;
  - `labelDeleteInvalidatesSweep`;
  - `constructorHasNoModelDependency` (reflection), with the mutation RED "add a `RoleRunner` param" making it fail.

**Commit:** `feat(canvas): RT sweeps (per-state timer photo gaps up to 300 s), objectives, holdout by day, Pareto front with source provenance`

### Task 25: Verification artifact and fail-closed calibration import (S44, S61, S77) [LATER LA7]
**Stream A · 3 h · depends on: 12.**

**Files:**
- modify `tools/battery-run.sh`: a new `verify` subcommand that writes `verification-v1.json` = `{schema: 1, samples: [{t, planHash12, planMode, worn, charging, persistLog, battPct, chargeUah}], phases: [{mode, from, to}]}` from the pulled heartbeat lines. **Controller-run.** Add shell tests to `tools/test-battery-run.sh`.
- create `services/orchestration/.../canvas/plan/CalibrationImport.kt` and the `POST /api/calibration/import` handler in `SweepRoutes.kt`

**Implement:**
- **`verified = true` only when all of these hold:**
  - one `planHash12` and mode per phase;
  - ≥ 10 min of SHADOW warm-up, excluded from the result;
  - ≥ 45 min of LIVE or TRIAL with `worn = 1` and `charging = 0` throughout;
  - no heartbeat gap > 2 intervals;
  - persistent logging recorded.
- The result is `measuredPctPerMin` from `chargeUah` when present (else battery %), keyed by the **`configHash`** whose 12-hex prefix matches.
- **A caller-supplied `verified` is ignored.**

**Tests:**
- `verifiedHappyPath`;
- `failsOnMixedPlans`, `failsOnPluggedSpan`, `failsOnNotWorn`;
- `failsOnGap`, `failsOnShortLive`, `failsOnMissingField`, `failsOnPhaseChangeInsidePhase`;
- `suppliedVerifiedIgnored`;
- shell: `verify_writes_schema1`, `verify_marks_plugged`.

**Commands:** `gw.sh :services:orchestration:testDebugUnitTest --tests '*CalibrationImportTest'`, `bash tools/test-battery-run.sh`.

**Commit:** `feat(tools,canvas): versioned battery verification artifact and fail-closed calibration import keyed by configHash`

### Task 26: Publish state machine and recovery table (S25, S62) [CORE, trimmed 2 h; extras LATER LA8]
**Stream B · 3.5 h · depends on: 5, 7, 8, 13. It lands after M1.**

**Implement:**
- round 1 Task 18, with `canvas_group_state` as the persisted transition table:

  | From | Event | To |
  |---|---|---|
  | DESIRED | validated | PREPARED |
  | PREPARED | sent | SENT |
  | SENT | matching EFFECTIVE | EFFECTIVE (then activate the phone halves) |
  | SENT | REJECTED | FAILED |
  | SENT | 10 min | UNKNOWN_REMOTE |
  | UNKNOWN_REMOTE | a later matching EFFECTIVE | EFFECTIVE |
  | UNKNOWN_REMOTE | the user or the next publish | compensate: a new gen with the previous desired state |
  | any | REVERTED(gen) | REVERTED, **terminal** |

- **Matching** needs `gen`, `publishId`, `mode`, `configHash` and `phoneInstall`.
- An EFFECTIVE for a superseded or terminal gen → ignored and counted.

**Tests:**
- round 1's, plus:
  - `lostAckBecomesUnknownRemote`, `lateEffectiveAfterTimeoutActivates`;
  - `staleEffectiveAfterRevertIgnored`;
  - `supersededPublishIgnoresOldAck`;
  - `crashAfterPersistBeforeSend`, `crashAfterSendBeforeAck`, `crashAfterAckBeforeActivate` (each recovers on restart);
  - `compensationUsesHigherGen`.

**Commit:** `feat(canvas): capture-plan publish groups with a persisted transition and recovery table (unknown-remote, compensation, terminal revert)`

### Task 27: Evidence gates (S11, S60, S75) [CORE, with 28: 2 h; LIVE gate LATER LA9]
**Stream B · 2.5 h · depends on: 21, 24, 25, 26, M1.**

**Implement:**
- round 1 Task 19's gates 1–5, with all evidence keyed by **`configHash`**;
- gate 3 needs replay sources whose `labelRev` and revisions are still current;
- the plan's `meta.expectOpensP95PerMin` is filled from the gate-3 replay **without changing `configHash`**;
- TRIAL sends `trialMaxMs = 3_600_000`;
- the TRIAL count is per `configHash`.

**Tests:**
- round 1's gate tests, plus:
  - **`configHashStableFromReplayToLive`** (compile → replay → TRIAL → calibration import → LIVE);
  - `staleSourcesFailGate3`;
  - `metaFillDoesNotChangeHash`;
  - `liveRefusedAt043`, `liveAllowedAt042`.
- **Mutation RED:** hash `meta` together with `config` → `configHashStableFromReplayToLive` fails.

**Commit:** `feat(canvas): publish evidence gates keyed by configHash (SHADOW, 60-min TRIAL, verified LIVE ≤ 0.42 %/min)`

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

### Task 29: Listen metadata contract (S63) [CORE, trimmed 1 h; listenId LATER LA11]
**Stream C · 2.5 h · depends on: 5.**

**Files:**
- `GlassesEvent.Cmd(command, listenId: String? = null)`
- `GlassesInbound` (pass `env.listenId`)
- `phone/.../hub/HubVoice.kt` (`onListenStart(listenId: String?)`)
- `phone/.../voice/PipelineAdapters.kt`
- `services/voice/.../VoicePipeline.kt`:
  - `glassesListenStart(listenId: String? = null)` stores it per generation;
  - `SttResult` gains a `genuineFinal: Boolean` from the platform (a timeout partial = false), carried into `think`;
  - an `interceptor` hook runs **before** `Thinking` is published;
- the voice graph wiring

**Tests:**
- `listenIdReachesPipeline`;
- `listenIdScopedToGeneration`;
- `timeoutPartialNotGenuine`;
- `interceptorRunsBeforeThinking`;
- `existingPipelineTestsUnchanged`.

**Commands:** `gw.sh :services:glasses-link:testDebugUnitTest`, `gw.sh :services:voice:testDebugUnitTest`, `gw.sh :phone:testDebugUnitTest --tests '*Voice*' --tests '*Hub*'`.

**Commit:** `feat(voice): carry listenId and genuine-final status from the glasses envelope to the voice pipeline; interceptor seam before Thinking`

### Task 30: Glasses tap mark (brief M1, P3, S57, S74) [CORE 2.5 h]
**Stream C · 2.5 h · depends on: 5, 29.**

**Implement:**
- round 1 Task 20, plus:
  - each queued mark stores the glasses' known `deletionCounter`;
  - a new epoch or `installId` clears the queue;
  - queued marks are re-sent on `onReady` whatever their age (≤ 24 h).

**Tests:** round 1's, plus `newEpochClearsQueue` and `oldQueuedMarkResentAfterReconnect`.

**Commit:** `feat(glasses): tap marks a moment (persisted epoch-scoped outbox, pc_mark with listenId and both clocks, cue, undo)`

### Task 31: Phone `MarkRecorder` and voice interceptor integration (brief M3, S54, S57, S63, S85) [CORE 3 h; ±3 s time join]
**Stream C · 3.5 h · depends on: 8, 9, 14, 29.**

**Implement:**
- round 1 Task 21, plus:
  - a mark whose mapped time < `wipedAtMs` → dropped (S57);
  - the mark and the listen join by `listenId` in either order, without a duplicate MOMENT;
  - UNDO and RETRO per the S85 table;
  - **only unmatched clauses are forwarded to Ask**;
  - every label write bumps `labelRev`.

**Tests:**
- round 1's, plus:
  - `preWipeMarkDropped`;
  - `markBeforeListenJoins`, `listenBeforeMarkJoins`, `noDuplicateMoment`;
  - `undoCommandDeletesBoundMark`, `retroMarkWindow`;
  - **`realPipelineMarkMakesNoAnswerCall`** (a spy `AnswerService` gets zero calls; the HUD state is "marked · <label>");
  - **`onlyUnmatchedClauseReachesAsk`** (the spy receives exactly "what's this building");
  - **`transcriptNeverPersistedOrLogged`** (a capture of every DAO write, the log sink and the HUD text contains none of the input words beyond the canvas label).
- **Mutation RED:** forward the whole utterance to Ask → `onlyUnmatchedClauseReachesAsk` fails.

**Commit:** `feat(voice,canvas): mark recorder on canvas_label (epoch fence, listenId join, undo and retro) and the voice interceptor (no model call, only unmatched clauses go to Ask)`

### Task 32: Phone mark entry points (A9 rest) [LATER LA12]
**Stream C · 1.5 h · depends on: 31.** It is round 1 Task 22, unchanged (the notification action, the tile, the home button, and the voice toggle on by default and "Count asks as moments" off by default). It exposes classes; Task 33 wires them.

**Commit:** `feat(phone): Mark moment from the notification, a Quick Settings tile and the home screen; voice mark and count-asks toggles`

### Task 33: Composition wiring 2 (S71) [CORE 1 h]
**Stream B · 1.5 h · depends on: 28, 31, 32.**

Wire `MarkRecorder`, the interceptor, the entry points, `CapturePlanPublisher` and the actions in `HubGraph`, `CanvasGraph` and `CanvasApi` (`LabelRoutes`, `PlanRoutes` publish and actions, `SweepRoutes`). Remove `DebugPlanSource` from the production path; the debug route stays in Developer mode.

**Tests:** `allRoutesRegistered`, `markFlowWiredEndToEnd` (a fake glasses mark → a row → an ack), `publishFlowWiredEndToEnd`.

**Commit:** `feat(phone): wire marks, voice interceptor, publish groups and rollback actions`

### Task 34: Deletion graph (A13, S38, S58, S75, P7) [CORE, trimmed 2.5 h; rewrite LATER LA13]
**Stream A · 3.5 h · depends on: 9, 18, 19, 21, 24, 31.**

**Files:**
- `services/memory/.../db/CanvasSlice3ForgetDao.kt` (transactional tombstones, label deletes, invalidation, `labelRev` bump)
- `services/memory/.../forget/ForgetListener.kt` (an interface; `ForgetService` and retention `expirePhotos` call the registered listeners after their transaction)
- `services/orchestration/.../canvas/plan/CanvasForgetCoordinator.kt` (files and devices; registered in Task 33's wiring, or here through `CanvasGraph` if Task 33 has merged)

**Implement:**
- round 1 Task 23's behaviour;
- **a range rewrite writes a new trace (`traceId'`, `revision + 1`, `parentTraceId`) and tombstones the original**;
- retention reaches the same cascade;
- a label delete through `LabelRoutes` invalidates the dependent results.

**Tests:**
- round 1's, plus:
  - `retentionExpiryCascades`;
  - `rewriteGetsNewIdAndTombstonesOriginal`;
  - `lateChunkForOriginalRejected`;
  - `labelRouteDeleteInvalidatesResults`;
  - `memoryHasNoOrchestrationDependency` (a Gradle dependency check, or a compile-time import scan).
- **Mutation RED:** skip the fence on the replay commit → `forgetDuringReplayCommitsNothing` fails.

**Commit:** `feat(canvas): slice 3a deletion graph (memory tombstones, ForgetListener, retention, IMU rewrite to a new revision, label invalidation) with late-result fences`

### Task 35: Canvas UI 1 (A14 part) [CORE 35a 2 h]
**Stream B · 2.5 h · depends on: 3, 7.**

**Implement:** round 1 Task 24, with every label reading **"timer photo gap"** and **"reserved timer photo attempt"** (S84). The compile view shows warnings; there is no clamped text. The `paramform` inputs allow blank (inherit or unchanged).

**Tests:** round 1's, plus `labelsSayTimerPhotoGap` and `blankMeansInherit`.

**Commit:** `feat(canvas-ui): domain lanes, signal bindings and ParamSchema forms (timer photo gaps) on the React Flow editor; compile view`

### Task 36: Canvas UI 2a: replay, sweep and Pareto with vendored Chart.js [CORE 35b part (table, no chart); chart LATER LA14]
**Stream B · 2.5 h · depends on: 24, 35.**

**Implement:** round 1 Task 25's vendor step (Chart.js 4.5.1, SHA-256 `48444a82d4edcb5bec0f1965faacdde18d9c17db3063d042abada2f705c9f54a`, 208,522 B, `vendor-lock.txt`, licences, load order; stop on a mismatch) plus `pareto.js`, `paretoview.js` and `replayview.js`. Hours are shown with the S80 formula.

**Tests:** `pareto.test.js` (round 1's names); `VendorLockTest.vendorChartHashMatches`; `hoursFormula`.

**Commit:** `feat(canvas-ui): replay and sweep views with a Pareto chart on vendored Chart.js 4.5.1 (hash-locked)`

### Task 37: Canvas UI 2b: mark timeline, publish and rollback, full gate [CORE 35b part; timeline LATER LA15]
**Stream B · 2.5 h · depends on: 28, 33, 36.**

**Implement:** `marktimeline.js`, `timelineview.js`, `planpublish.js` (the gate texts, the TRIAL countdown, the three actions), and the `views.js`/`app.js` wiring.

**Tests:** round 1's `marktimeline` and `planpublish` tests, plus `retroMarkBumpsInvalidationNotice`.

**Final gate:** `tools/gate.sh /Users/<user>/Desktop/rokid/pca-s3/gate-3a.log` exits 0. A known-flaky `PushToTalkTest` alone may be re-run once.

**Commit:** `feat(canvas-ui): mark timeline with retro marks, publish gates and rollback actions`

### Task 38: Device checklist (A15; controller with the user) [CORE, trimmed 2 h; extras LATER LA16]
The round 1 Task 26 rules apply: back up first, the controller installs, read `capture_diag.log` only, never AssistServer lines, force-stops as counts.

1. **Encrypted migration rehearsal (S64):** on a **copy** of the production DB on the device, run the encrypted device suite (Task 10) and a v6 → v7 open. Check `integrity_check`, the table counts and that an ask works. Only then install the release build.
2. Install and migration counts. The heartbeat defaults, plus `resv` is visible.
3. **Settings parity:** a 30-min worn settings-mode run. Compare `resv` opens with expectations (it fires only on anomalies) and `opens1m` with A3.
4. **The S47 overhead A/B (S77):** paired 2 × 90 min worn runs, collection on and off, measured with `chargeUah` against 0.49 %/min. The budget is ≤ 0.02 %/min; otherwise use `sampleEvery = 2`, with traces marked not exact. SHADOW's overhead is measured separately.
5. **Identity parity in SHADOW** (as M1, on a 60-min trace).
6. **TRIAL:** a 60-min auto-end, including a **reboot mid-trial**, which must revert (`auto:reboot`). Changing the phone clock must not extend it.
7. **Over-ceiling:** a forced revert, then each S26 action once.
8. **Reserved attempt:** under a REST plan with 300 s gaps while sitting still, a `resv` open at least every 300 s; under UNKNOWN, at least every 60 s. Glance captures remain immediate (S84): turn the head and watch a capture within the settle time.
9. **Walking:** a 10-min walk under a vehicle-restrictive plan. The applied state is WALKING or UNKNOWN, with A3 rates.
10. **Marks:**
    - a HUD tap;
    - "mark that, commuting" → "marked · commuting" with no model call;
    - "remember this", "I missed that", "undo mark";
    - "what did I mark?" → a normal answer;
    - "forget that" → a normal Ask (not a mark, not a Forget);
    - the notification, tile and home marks;
    - the voice toggle off.
11. **Transfer:** while the glasses charge only. Unplug mid-transfer → it stops, then resumes the whole trace later.
12. **Forget and Delete all:** a range Forget rewrites the trace into a new revision; Delete all clears both devices. A tap made offline before the wipe does not reappear.
13. **One verified interval (S44, S61):** `battery-run.sh verify` → import → reported against 0.42 %/min.
14. Write the results into the handoff, then start the 3c week of collection.

## Hidden-foreground stream (deferred, pointer; S82)
- **The design** is brief §2.4 and the options doc (A0, F1–F4, about 13 h of its 31 h; M1–M3 are Tasks 30–31 here).
- **Gate:** spike A0 (D1–D5). If D2 or D5 fails, front mode stays off.
- **When it passes,** write its own plan:
  - branch it from `s3a/int` after Task 30 merges;
  - it owns `MainActivity.dispatchKeyEvent` and the lifecycle;
  - in `FRONT_BLANK` it calls Task 30's `MarkQueue` and `MarkCue`;
  - D11 (≤ 0.51 %/min worn, `scrOn` ≤ icon mode, no wake locks) gates making it the default.

## Open for the user
1. **S82:** confirm that deferring the §16.2 hidden-foreground app is acceptable for this release. Until it lands, glasses taps mark only while the HUD is visible. The phone, voice and HUD-tap marks all work.
2. **Resolved (S83a):** WorkRest resting while UNKNOWN counts as "at rest" (300 s).
3. **Resolved (S83b):** the occasional settings-mode `resv` open at edge cases is accepted.
4. **Resolved (S85a):** reading and writing → COMPUTER_WORK; travelling → VEHICLE_PASSENGER; riding → OTHER.
5. **Resolved (S85a):** the "can you" and "could you" exceptions are kept.
6. **S75:** the automatic Capture-now and starred-photo MOMENT imports are deferred to 3c.
7. **P2:** the store is `canvas_label`; say if you want the literal name `moment_label`.
8. **P3 and Q6:** `ASK_TAP` rows are excluded from recall by default.
9. **Estimate (round 3):** 3a-CORE is about **66 agent-h** (critical path 27 agent-h; about 29.5 h wall-clock on 3 streams, about 34 h with Gradle contention). 3a-LATER is about **27.5 agent-h** and gates only unrestricted LIVE or adds convenience. Run 3a-LATER now, or after the first week of use?
10. Still open from earlier specs: §15 items 1–30, and "stop capture in flight" (wave B §12.3).
