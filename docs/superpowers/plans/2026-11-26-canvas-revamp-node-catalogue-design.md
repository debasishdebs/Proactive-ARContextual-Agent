# Canvas revamp (1.9.x): node catalogue and deploy model (design, 2026-11-26, revised 26 Nov for the deploy model)

Designer: Opus. Implementer: Sonnet, in the work packages of section 10. Reviewer: Codex sol at medium effort, or Opus at low effort when the Codex quota is out.
Branch `feat/v101-ask-eval`, `android-companion/`. This is a design only. It changes no source, needs no install and needs no phone.

It updates, and does not replace, the canvas docs it builds on:
- `2026-11-24-canvas-v2-1-live-e2e-and-v2-2-model-nodes-design.md` (Test run, model nodes);
- `2026-11-26-canvas-v2-3-detection-nodes-design.md`, `-v2-4-nl-tools-design.md`, `-v2-5-plugin-skill-nodes-design.md`;
- `handoffs/decisions-canvas-v2-1-v2-2-2026-11-26.md` (what is built);
- `handoffs/owner-decisions-2026-11-26.md` (the owner rulings this revision applies, Round 2 binding).

Labels: VERIFIED means read in the code or docs on this branch at tip `a9251ae7`. UNVERIFIED means inferred or estimated.

---

## 0. What this revision changes

The first version said the canvas is a dry-run tool, new nodes are test only and nothing is live in 1.9.x. The owner ruled otherwise (Round 2, binding):

- **The canvas is an authoring, calibration and deploy tool**, like Azure ML designer: design a pipeline, Test-run it, run experiments and calibration, then **Publish**, which deploys the pipeline, its settings and its thresholds to the phone as the live flow. Live events (mark, pin, moment, scene, signal, schedule) run the **deployed** pipeline. Nothing "invokes the canvas": the canvas page can be closed, the laptop gone. Section 3 is the deploy design; it builds on the v0.9.8 publish journal.
- Thresholds are editable from a Test run, and Save writes the live setting (Developer mode, confirmation, reset to the calibrated default).
- Renames: general `describe` and `compare` nodes with `modality: text | vision`; `vision_model` is the VLM node; `image_describe`, `image_compare` are the vision forms. No hidden fallback inside a node.
- Per-node and per-port **exportable** checkboxes with privacy-label defaults; coordinates locked off for remote, AI, logs and run results.
- `sensor.location` feeds a `context.hub` node that merges glasses, watch and phone sensors.
- `trigger.mark` (and `trigger.pin`) are normal trigger nodes.
- `seed.plan` is a preview usable in experiments.
- A stored-location test drops the MOMENT from the test (not the location row) with a counts-only drop reason.
- The Scene centroid becomes Scene metadata in a side table `scene_centroid`: a schema change that needs a ruling (section 8).
- New shadow work package: location first, cluster by embedding, centroid per Scene, dedup (WP-S).
- New section 9.3: experiment and report generation.
- The SigLIP 2 licence-text item is dropped (owner: not needed, Apache-2.0, no redistribution); the tokenizer checksum stays.

---

## 1. Goals, and what changed since the canvas docs were written

**Goal.** Every pipeline the app runs today, or has designed, can be looked at, Test-run, calibrated, recombined on the canvas with typed ports, and then published as the live flow, while no node and no publish can move a photo, a vector or a location to a place it may not go, and no publish can switch off a guard the app owns (section 3.8).

**Built or designed since the 24 Nov canvas doc (VERIFIED in the docs and code named):**

| New capability | Where | State |
|---|---|---|
| Image embeddings in two spaces: Nomic vision v1.5 and SigLIP 2 base p16-224 | `SigLipBundle`, `SiglipShadowCompare`, `PinReferenceVectors`, `CandidateScorer`, `PlaceShadow` | Shadow only, flags `placeShadowScore` + `placeShadowSiglip`, sideloaded files; tokenizer checksum, no licence screen |
| Reference variants single / centroid / medoid, calibration | `MatchCalibration` (commit `1a0a624b`), `MatchThresholds` (VERSION 0, unset), `handoffs/calibration-1.4.4-result-2026-11-26.md` | Thresholds NOT SET. SigLIP 2 centroid best (F1 69.0%, top-1 30/40); no clean separation in any row |
| Pack Moments (pack place as a text-only match candidate) | `MomentCandidate`, `MomentCandidates`, `PackMomentStats` | Flag `packMoments`, shadow, text only |
| Offline VLM plan (Qwen3-VL-2B, SmolVLM2, Gemma 4 E2B via llama.cpp `mtmd`) | `2026-11-20-offline-vision-model-exploration.md` | Not started (1.10.0); bake-off inconclusive |
| Model resolver, Automatic / Offline only | `ModelResolver`, `BackendDecision`, `ModelPrivacyStore`; roles `CANVAS_TEXT/VISION/EMBED` declared, no factory | Built (1.4.3) |
| Map zones P1-P3: seed areas, cells (`PlacePack.cellStatuses`), density layer, offline notice | `2026-11-25-map-zones-p2-p3.md`, v18 WP7 | Flags `mapZonesV2`, `mapZonesP23`; device test owed |
| Moment location (context.db v18 `moment_location`), recorder over held fixes, GPS backfill import, validated-network wake | `MomentLocationRecorder`, `MomentLocationImport`, `NetworkWake`; v18 build spec (FROZEN 25 Nov) | Built, not installed (1.6.0) |
| Developer test place and image import | `DevTestImport` (commit `87dc5a3a`) | Built (1.7.0) |
| Retention P2 and its read-only preview | `RetentionPreview`, flag `retentionP2` | Built (1.8.0) |
| Data saver counters and caps | `NetworkCostMonitor`, `DataSaverStore`, `DataSaverWorkGate` | Built |
| Dump API (Developer + pairing; coordinates and names included; vectors optional) | `handoffs/dump-api-2026-11-26.md`, `tools/pull_dump.py` | Built; questions 1-5 there pending |

**What this means for the canvas.** The v2.2 doc listed `embed.match` as one row "that rides 1.4.4". It is now a family: two spaces, three reference kinds, bands from a calibration, a place candidate step that needs a location, and a vision step that needs the resolver. None of it fits today's port types without leaking a vector or a coordinate into a run row, and none of it can be published today, because the flow shapes are closed (gap 7).

---

## 2. The current node and connection model (VERIFIED), and the gaps

`services/ai/.../canvas/NodeTypes.kt` and `ModelNodes.kt`. A node has a **set of input port types and one output port type**. An edge is valid when `src.output in dst.inputs` (`PipelineValidator.kt:69`). An ASYNC graph has **exactly one input root** (`:75-83`). Branches are named outputs of the same port type. `mixedIn` (only `policy.capture_rate`) lets one node take two port types. A node type carries `liveable: Boolean` (the v2.2 nodes are all `liveable = false`).

| Family (palette) | Node types today | Inputs -> output |
|---|---|---|
| Source | `input.photos`, `input.moments`, `input.scenes`, `input.utterance`, `trigger.summarizer_pass`, `trigger.moment/scene/signal/schedule`, `imu.head_motion`, `imu.settle`, `hub.context` ("Motion from context hub", RT_PHONE root), `src.watch_motion`, `signal.in` | - -> PHOTOS, MOMENTS, SCENES, TEXT, TRIGGER, MOTION_EVENT, CONTEXT, WATCH |
| Process | `ai.vision_extract`, `ai.critic`, `ai.prompt`, `proc.summary`, `proc.motion_model`, `embed.text` (descriptive in flows), `rag.window`, `rag.condense`, `rag.retrieve`, `rag.order`, `py.fn`; v2.2: `model.classify` | PHOTOS/JSON/... -> EXTRACTION, JSON, SUMMARY, NUMBER, VECTOR, TEXT, RAG |
| Aggregate | `agg.window` | SCENES/MOMENTS/WATCH -> NUMBER |
| Condition | `gate.local_critic`, `gate.compare`, `route.switch`, `gate.threshold`, `gate.novelty`, `gate.rate_cap`, `expr.guard`, `expr.value`, `gate.nudge`; v2.2: `model.route`, `rule.table/all/any/not/debounce/rate` | branches over the same port |
| Outcome | `memory.write` (preview), `out.deliver` (caller/phone/glasses/both), `out.set_frequency_preview`, `policy.capture_rate`, `capture.image`, `signal.out` | -> PREVIEW, PHOTOS, TRIGGER, SIGNAL |

Port types: `PHOTOS, EXTRACTION, JSON, PREVIEW, IMU, MOTION_EVENT, CONTEXT, TRIGGER, VECTOR, TEXT, SIGNAL, MOMENTS, SCENES, SUMMARY, NUMBER, WATCH, RAG`. Executor kinds: `BUILTIN, EXPR, TABLE, PROMPT, EMBED, TOOL, EVENT, SIGNAL, PYTHON, DRAFT`. Domains: `WATCH, RT_GLASSES, RT_PHONE, ASYNC, REPLAY_ONLY`. Presets (`DefaultPipelines`): `live-extract`, `intent-draft`, `default-capture`, `starter-capture-extract`, `starter-moment-summary`, `starter-watch-motion`, `default-query`, `dated-query`, `default-nudge`.

**Gaps:**
1. **No privacy on ports.** A port says what a value is, not where it may go. VECTOR carries no space, so a Nomic and a SigLIP vector would connect.
2. **No second input.** `embed.match` needs a query and a reference set; `place.candidates` needs the item and a fix. One unnamed input set and one root cannot express "this item, against those references".
3. **No location, zone, candidate or match kinds.** The v18 source rule (`MomentLocationSourceRulesTest`) forbids `moment_location` anywhere in `CanvasApi`, `services/ai`, prompt builders, `PayloadSources`, Ask and export. Any geo node must live on the phone side of that wall.
4. **No read nodes** for Scenes clusters, card history, seed areas, zones, retention or the data saver: only the three item roots.
5. **No resolver path** for vision or embeddings on the canvas (roles declared, no factory). Parked: `model.text`, `model.vision`, `embed.match`, `rule.when`.
6. **No card sink.** Place cards and glasses cards (left/centre/right) are not reachable; `out.deliver` covers Ask answers and nudges only.
7. **Flow shapes are closed.** `FlowPlan` allows exactly utterance -> [embed.text] -> rag.retrieve -> ai.prompt -> out.deliver, and trigger -> rag.retrieve -> ai.prompt -> gate.nudge -> out.deliver. Place Guide and marks have no published form at all. This is the gap the deploy model (section 3) closes.

---

## 3. The deploy model

### 3.1 What exists (VERIFIED), and is the base

| Piece | Where | What it does today |
|---|---|---|
| Publish journal | `canvas_publish` rows (`CanvasPublishEntity`): `pipelineId`, `version`, `hash`, `publishedAt`, `rolledBackAt`, `rollbackReason`, `snapshotJson`, `kind`, `component`, `mode`, `groupId`, `configHash` | Append-only; "active" = `rolledBackAt IS NULL` |
| Kinds | `CanvasPublisher.KIND_PROMPTS`, `KIND_INTERPRETER`, `KIND_FLOW` (v0.9.8, components `query`, `nudge`), `KIND_CAPTURE_PLAN` | One active `flow` row per component; a new publish marks the old one `superseded` in the same transaction |
| Frozen versions | `dao.version(pipelineId, version)`; a publish names a frozen version and its hash | The draft is never what runs |
| Gates | `PlanShape.tier3Nodes` (S6: `py.fn`, `tool.*` never publish), `InterpretableShape.problems`, prompt compatibility, "Run this version first" (`dao.testedNodeCount(hash, ...)`), `FlowPlan.compile`, delivery-target warning | Refusal = `409 publish_blocked` with fixed reasons |
| Modes | `LiveMode { SHADOW, LIVE }` for the interpreter; capture plan SHADOW / TRIAL (at most 3 per `configHash`) / LIVE | SHADOW: every 4th live call also runs the publish as `CallClass.EXPERIMENT`, traces only, Developer mode required (`LiveExtract.shadow`) |
| Fallback and auto-rollback | `LiveExtract.batch`: a failed interpreter run falls back to built-in extraction; `AUTO_ROLLBACK_REASON = "auto: 10 of 30 fell back"` | Memories are never lost to a publish |
| Built-in on failure | `DaoFlowPlans.plan`: an unreadable or no-longer-compiling version gives null, the built-in flow runs | Fail to built-in, never to "nothing" for Ask and Nudge |
| Rollback | `CanvasPublisher.rollback(expectedId)`: undoes the newest active row, refuses when a newer publish exists or an auto-rollback won the race; capture plan has `restore-previous`, `use-settings`, `reinstated` | For a `flow` row, undo = the mark, so the built-in runs (NOT the previous version) |
| Status | `LivePipelineInfo` (name, version, Live/Shadow, fallbacks/interpreted) in Diagnostics; `GET /api/plans/status` | Count-only |

Two honest gaps against the owner's model: flow rollback today returns to the built-in, not to the previous published version; and publish routes check pairing but not Developer mode for `flow` (UNVERIFIED beyond `CanvasApi.publish`, which has no Developer check).

### 3.2 Lifecycle

```
Draft --(Test run, experiments, calibration)--> Frozen version
  --Publish (Developer + confirm, validator)--> SHADOW: runs on live events beside the built-in; scores logged; cards unchanged
  --Promote (Developer + confirm, shadow evidence shown)--> LIVE: drives the component; built-in is the fallback
  --Roll back (one step)--> the previous published version of that component (or built-in when none)
  --Use built-in--> no active row for the component
```

A **component** is one live entry point: `query` (Ask), `nudge`, `place` (Place Guide card decision), `mark` (what happens after a mark), and the existing `capture_plan` and `interpreter` (extraction). One active row per component. A pipeline publishes into exactly one component; its root decides which (section 3.10).

### 3.3 What Publish does

One transaction under the publisher's `Mutex` (as `publishFlow`):
1. Load the frozen version; refuse when unreadable.
2. Run the **publish validator** (3.5) on the live path; refuse with fixed reasons naming node ids only.
3. Resolve the **settings snapshot**: every tunable param (thresholds, margins, rates, radii, model bindings) read from the version, with `calibrated` resolved to the current calibrated default and its `MatchThresholds.VERSION` recorded.
4. Write one `canvas_publish` row: `kind = "event"` (new; `flow` rows keep working), `component`, `mode = SHADOW` when the graph holds any `SHADOW_ONLY` node or the owner picks Shadow (a graph of YES nodes only may go straight to LIVE, as `flow` publishes do today), `snapshotJson = {pipelineId, version, hash, settings, prevPublishId}`. Mark the previous active row of the component `superseded`.
5. Answer `{publishId, component, mode, warnings}`. No restart, no glasses message (the capture plan keeps its own publisher and its own gates).

The phone side reads the active row per component through one reader (`DaoEventPlans`, the `DaoFlowPlans` pattern: compiled once per publish id, null on any problem means the built-in runs). Nothing reads the draft, and nothing calls the canvas server.

No Room change: `kind`, `component`, `mode` are free strings and `snapshotJson` is a JSON column today (VERIFIED).

### 3.4 Versioning and one-step rollback

- Every publish names a frozen `(pipelineId, version, hash)`; a version is immutable. The settings snapshot travels in the row, so a later threshold Save (3.9) is its own journal row, never an edit of an old one.
- **Roll back** (per component, Developer + confirm, `expectedId` as today): marks the active row `rollback` and inserts a new row that reinstates `prevPublishId` (same version, hash and settings snapshot, `reinstatedFrom` in its snapshot), in one transaction. The journal stays append-only. Only one step: rolling back a reinstated row offers "Use built-in", not an older version (re-publish an older version from the version list instead).
- **Use built-in**: marks every active row of the component; the built-in code runs.
- **Auto-rollback** for a LIVE component: the `LiveExtract` rule generalised: 10 fallbacks in the last 30 events (fallback = the deployed run failed, timed out at its deadline, or a node refused at call time), reason `auto: 10 of 30 fell back`. Auto-rollback goes to the built-in, not to the previous version (a failing environment, for example Offline only switched on, would likely fail the previous version too).
- A version whose node types are unknown after an app update (flag off, node removed) gives null in the reader and the built-in runs, counted in Diagnostics as `not_runnable`.

### 3.5 Gates: Developer mode, confirmation, validator

- **Developer mode** is required for Publish, Promote, Roll back, Use built-in and threshold Save (new check in the publish routes; 403 `developer_mode_off`). Pairing stays as today.
- **Confirmation**: the publish route answers first with a summary and a `publishToken` (the `confirmToken` pattern of runs): component, mode, the nodes that would run live, every setting that differs from the calibrated default, every online model node and its endpoint label, every exportable port that is ticked toward remote. The second call with the token publishes. A token binds the hash and the settings, so a change in between refuses.
- **Publish validator** (on the live path = every node reachable from the component root), refusing when any of these holds:
  1. a node is `liveCapable = NO`, or `SHADOW_ONLY` in a LIVE publish or Promote;
  2. a label rule of section 5 is broken, an exportable tick reaches a consumer its label forbids, or a coordinate field is ticked toward anything (it cannot be, see 5.3; checked again here);
  3. an online model node is on the path while Settings is Offline only (`offline_only`), or a model node has `backend = auto` or `onUnavailable = fallback_text` (no hidden fallback in live use);
  4. a geo consumer (`place.candidates`, `geo.near`) is not downstream of `zone.check` `clear` and `pause.state` `clear`;
  5. a node has stored output but no Forget family;
  6. a tier 3 node (`py.fn`, `tool.*`, `skill.call`, `tool.draft`) is anywhere in the graph (S6);
  7. the component has no successful Test run of this exact hash for every model node (today's "Run this version first");
  8. a node type declares a write effect on zones, seed areas or pins.
- The estimate of the confirmation summary also repeats the Offline-only check, and the router repeats it at call time. A publish is never the last check.

### 3.6 Per-node live-capable marking

`NodeType.liveable: Boolean` becomes `liveCapable: NO | SHADOW_ONLY | YES` (`liveable = true` maps to YES, false to NO, so existing graphs keep their meaning).

| Value | Meaning | Who changes it |
|---|---|---|
| NO | experiments and Test runs only (`py.fn`, `memory.write` preview, `calib.report`, `seed.plan`, `seed.area`, `place.history`, `retention.preview`, the v2.2 nodes until migrated to explicit model nodes) | code |
| SHADOW_ONLY | may be published, runs in SHADOW only: its scores are logged, its outputs never reach a card or a delivery | code |
| YES | may drive a LIVE component | code, after a device session and a review |

Every new match or vision node starts `SHADOW_ONLY`. A promotion to YES is a code change with its device evidence named in the commit, never a canvas action. The palette shows the value as a badge.

### 3.7 Staged rollout

1. **Shadow.** The deployed pipeline runs on each live event of its component after the built-in has acted (the `PlaceShadow` and `LiveExtract.shadow` pattern): its model calls are `CallClass.EXPERIMENT`, its scores and outcomes go to `canvas_live_trace` and the count-only log (ids by the 1.4.1 rule, bands, milli scores), and the card, the nudge or the answer the user gets is the built-in one, unchanged. Shadow pauses while Developer mode is off (as today).
2. **Shadow report.** Diagnostics and the canvas show, per component: events seen, events scored, agreement with the built-in (same top-1 or same deliver/skip), the band histogram, fallbacks. Counts only.
3. **Promote to LIVE** (Developer + confirm), offered once the shadow has scored at least N events (default 30, the auto-rollback window; owner question 2). LIVE runs the deployed pipeline first; on failure the built-in acts for that event (fallback counted); auto-rollback as 3.4.
4. Settings changes (3.9) to a LIVE component apply at once and are one journal row each, so one Roll back undoes the last one.

### 3.8 What a published pipeline may change, and what it can never change

| May change (through Publish or Save) | Can never change (code-owned; the validator and the runtime both enforce) |
|---|---|
| The node graph, from `liveCapable` nodes | The zone guard: `ZoneEditor.upsert` stays the only zone writer; no node writes zones, seed areas or pins |
| Thresholds, margins, rates, radii, debounce times, as settings (3.9) | Forget, multi-select Forget, Delete all: one flow, one confirmation; the Forget fence, photo gate and bounds inside `RagCore` |
| Model bindings: which `vision_model` / `text_model` node, offline or online, which catalog model | Offline only: an online node under Offline only is refused at validate, estimate, publish and call time; a deployed online node then fails closed for that event (counted, routed by the owner's own branch if he wired one) |
| Prompts, through the existing prompt publish path (`KIND_PROMPTS`, CAS, rollback) | Pause zones and the Pause switch fail closed: unreadable or "not judged yet" is blocked |
| Delivery target (caller / phone / glasses / both) and card side | The data-saver router: `ModelRouter.call` and `DataSaverWorkGate` enforce; `gate.datasaver` is advisory |
| Trigger params (`maxPerHour`, schedule, signal window) | `IdentityGuard`, the photo liveness guard (`PhotoForgottenException`), the confirmToken for photos leaving |
| Exportable ticks within what the labels allow (5.3) | The coordinate locks (5.3), the v18 source rule, export through `DataOpGate` |
| | Developer flags, Settings values outside the canvas (Offline only, Place Guide on/off, momentLocation), the capture plan (its own publisher) |

### 3.9 Thresholds as settings

- Calibrated defaults stay code constants in `MatchThresholds` (with `VERSION`), set by a human from a calibration (still unset, VERSION 0).
- The live value of each tunable lives in `LiveSettingsStore` (a prefs file, not Room), keyed `component/nodeId/param`, holding `{value, source: calibrated | saved, savedAt, thrV}`. Read failure gives the calibrated default; a node param `calibrated` always means the default.
- A Test run shows each threshold with its calibrated default and lets the owner enter a new value for that run. **Save** (Developer + confirm) writes the live setting of the active publish of that component and appends a journal row `kind = "setting"` with `{key, old, new}` in `snapshotJson`, so Roll back undoes it in one step.
- **Reset to calibrated default** per setting and per component (also a journal row).
- When `MatchThresholds.VERSION` moves (a new calibration in code), saved values stay and Diagnostics flags them `saved against thrV n`; nothing is silently replaced.
- A threshold Save on a SHADOW component changes only the shadow scoring.

### 3.10 Live events and components

| Live event | Seam (VERIFIED unless marked) | Root node | Component |
|---|---|---|---|
| Moment ends, Scene closes | `NudgeTrigger` (`TRIGGER_MOMENT`, `TRIGGER_SCENE`) | `trigger.moment`, `trigger.scene` | `nudge` (today), `place` |
| Signal, schedule | `NudgeTrigger`, `CronSpec` | `trigger.signal`, `trigger.schedule` | `nudge` |
| Mark recorded | `MarkGraph` `onRecorded` | `trigger.mark` (new) | `mark`, `nudge` |
| Pin saved or attached | `PlaceGraph.savePins`, `PlaceGuideSettings.attachLatest` (pin save); arrival near a pin is inside `PlaceSampler` (UNVERIFIED seam) | `trigger.pin` (new; owner question 3) | `place`, `nudge` |
| Place Guide sample (dwell frames) | `PlaceSampler`, `PlaceShadow` | `trigger.place_sample` (new, internal) | `place` |
| Question asked | `AnswerService` | `input.utterance` | `query` (today) |

The existing `FlowPlan` graphs keep compiling to the same runner, so `default-query` and `default-nudge` stay equal to the built-in (the `DaoFlowPlans` guarantee). The new `EventPlan` compiler replaces the closed shapes with "any graph of `liveCapable` nodes from one trigger root to one sink", run by one phone-side `EventPipelineRunner` per component (single-flight per event, deadline as `INTERPRETER_DEADLINE_MS`).

---

## 4. Node catalogue

**Common rules for every new node:**
- **Flag:** Developer flag `canvasCatalogue`, default OFF, also OFF when prefs cannot be read; it needs Developer mode. OFF means "unknown type" in the palette and in the reader (a published graph holding one then runs the built-in). Nodes that need a model file or a space also need that feature's own flag (named per row).
- **Runs on the phone** unless the row says otherwise. No new node runs on the glasses (they run only the compiled capture plan) or the watch.
- **Live-capable** per row (column `Live`): NO, SHADOW_ONLY or YES, as section 3.6.
- **No hidden fallback.** A model node has one backend, `offline` or `online`; there is no `auto` and no `fallback_text`. `onUnavailable: error | skip`. To have an offline and an online path the owner wires both model nodes and a branch (`privacy.mode` or `model.ready`). Settings (Automatic / Offline only) stays the hard gate.
- **Exportable** per node and per port, defaults from the label (section 5.3).
- **Stored output** in Test runs is `canvas_run_item.outputJson`; in SHADOW and LIVE it is `canvas_live_trace` (frame-keyed, cascaded by Forget). Port kinds LOCAL_ONLY or PHOTO_DERIVED are stored **reduced** (section 5).

Port kinds in **bold** are new (section 5).

### 4.1 Embedding and matching

| Id | Inputs -> output | Config | Online / offline | Live | Wraps |
|---|---|---|---|---|---|
| `embed.image` | PHOTOS -> **EMBEDDING** | `space: nomic \| siglip2`; `reuse: stored_first \| always_embed` | Always local ONNX. `siglip2` without the sideloaded tower gives `space_missing` (+ `placeShadowSiglip`) | SHADOW_ONLY | `frame_vector` (`FrameVectorDao.forFrames`, Nomic 768), `OnnxImageEmbedder`/`NomicBundle`, `SigLipBundle` (one image per run, int8 batch rule) |
| `embed.text` v2 | TEXT -> **EMBEDDING** | `space`; `role: query \| anchor` | Local: Nomic text via llama.cpp `embedQueryFull` (768, truncated to image dims, L2); SigLIP text tower (283 MB, optional) | SHADOW_ONLY | `Embedders`, `PhotoRecall`. The schema-2 `embed.text` (TEXT -> VECTOR, flows) stays unchanged |
| `embed.refset` | side inputs (PHOTOS, MOMENTS, **CANDIDATES**, SCENES) -> **REFSET** | `kind: single \| centroid \| medoid` (default **centroid**, all selectable: owner 11); `from: pin_moment \| pack_text \| scene \| labelled`; `leaveOneOut` (calibration only) | Local | SHADOW_ONLY | `PinReferenceVectors`, `MomentCandidate` (PIN, PACK), `SceneRules.centroid`/`pickReps` medoid, `MatchCalibration`; `from = scene` reads the Scene centroid (section 8) |
| `embed.match` | **EMBEDDING** (item) + **REFSET** (side `ref`) -> **MATCH**, branches `accept`, `borderline`, `reject` | `accept`, `reject` (settings, default `calibrated`); `margin` over the runner-up (setting, default 0 until measured: owner 3); `channel: image \| text` | Local | SHADOW_ONLY | `CandidateScorer`, `MatchThresholds.band()`. While `VERSION = 0` and no Save, every item is `borderline` by construction |
| `embed.search` | TEXT -> PHOTOS (hits) | `topK <= 24`, `space` | Local; `NO_IMAGE_SPACE` gives caption hits only | NO | `PhotoRecall` (brute force, 50,000 vectors or 2 s cap) |
| `calib.report` | **MATCH** (labelled items) -> **COUNTS** | per space and reference kind; `precisionTargets: [95, 98]` (owner 2) | Local | NO | `MatchCalibration` (p10/p50/p90, best-F1 t, accept at precision 95 and 98, `rejectBelow`) |

**Bands.** Proposed by the 25/26 Nov calibration, NOT applied (milli = cosine x 1000):

| Space, reference | accept (no model) | reject below | between |
|---|---|---|---|
| SigLIP 2, single | 917 | 814 | borderline |
| SigLIP 2, centroid | 959 | 879 | borderline |
| Nomic, single / centroid | 946 / 954 | 846 / 885 | borderline |

A Test run can try these side by side, and Save writes the chosen pair as the live setting (3.9). Nomic stays the default space (owner 1, 4); SigLIP 2 is chosen per node and needs its model file from the catalog (size shown, free space checked first).

### 4.2 Model nodes, describe and compare (renamed)

| Id | Inputs -> output | Config | Online / offline | Live | Wraps |
|---|---|---|---|---|---|
| `vision_model` | - -> **MODEL** (a binding, carries no data) | `backend: offline \| online` (one, no auto); `model` from the catalog (offline: the 1.10 VLM; online: an endpoint label) | Online refused under Offline only at validate, estimate, publish and call time | YES (binding only) | resolver role `CANVAS_VISION`; replaces the parked `model.vision` (owner 14) |
| `text_model` | - -> **MODEL** | same | same | YES | resolver role `CANVAS_TEXT`; replaces the parked `model.text` |
| `model.ready` | (side `model`) -> branches `ready`, `missing` | none | offline: model file present and verified; online: not Offline only, network up, data saver would allow (advisory) | YES | `ModelResolver`, `BackendDecision`, `DataSaverStore` (reads only) |
| `describe` | PHOTOS or TEXT/JSON + side `model` -> JSON (`caption`, `facts[]`) | `modality: text \| vision` (must match the input and the bound model); `preset: describe \| scene_summary \| list_objects`; at most 3 photos per call | per bound model; online goes through `ModelRouter` (confirmToken in Test runs, data saver, liveness) | SHADOW_ONLY | Describe on demand (VISION_EXTRACT), `CLUSTER_SUMMARY` for `scene_summary`; v2.3 `IdentityGuard` on every output |
| `compare` | PHOTOS or TEXT + **CANDIDATES** (side `cand`) + side `model` -> **MATCH** (choice or NONE, confidence) | `modality`; shortlist online <= 16, offline <= 3; `confidenceFloor` (setting) online 0.50, offline 0.70 plus top-2 embedding agreement | Online: `PLACE_MATCH` contract, names and kinds only, and names only while the Settings switch "place names in AI prompts" is on (owner 8), never under Offline only or inside a pause zone; coordinates never. Offline (1.10): `nativeScoreImage` label logits | SHADOW_ONLY | `PromptKey.PLACE_MATCH`, `PlaceMatchOutput`, `RoleContracts`, `ResolverRole.PLACE_CONFIRM` |

The palette offers `image_describe` and `image_compare`: they are `describe` and `compare` with `modality = vision`, stored as the general type plus the attribute, so one executor serves both modalities.

**Owner-wired fallback, the only kind there is:**
```
privacy.mode --auto--> model.ready(vision_model online) --ready--> image_compare (online binding)
             \--offline_only--------------------------------------> image_compare (offline binding) --missing--> none
```
If the owner wires no offline path, an event under Offline only ends at the refusal (counted, no card). At 15-35 s per offline vision call (UNVERIFIED), offline vision is batch-only: the live-tap estimate refuses it above `maxLatencyMs`, and a LIVE publish with an offline vision node on a card path gets a warning (not a refusal).

### 4.3 Places of interest

| Id | Inputs -> output | Config | Online / offline | Live | Wraps |
|---|---|---|---|---|---|
| `place.candidates` | **GEOFIX** (side `fix`) per item -> **CANDIDATES** | `radiusM` (setting, default 300, max 1,000), `maxPack` 8, `pinSlots` 3, `cut` 16 | **Cached cells only. It never fetches**; an unfetched cell gives `cell_not_ready` | YES | `PlacePack.candidates(..., networkUp = false)`, `Geo`, `UserPinStore`, `cutKeepingPins`, `MomentCandidates.of` |
| `place.match` (composite) | PHOTOS -> **CARD**, branches `card`, `nearby`, `none` | inner params; `rankSkip: off \| on` (on = accept skips the model) | per inner node | SHADOW_ONLY | `sensor.location`/`geo.moment` -> `zone.check` -> `place.candidates` -> `embed.image` + `embed.refset` -> `embed.match` -> borderline: `image_compare` -> card. Pin-first and ambiguity rules from `PlaceSampler` through extracted pure functions, never a copy (owner 13: the live coords -> candidates -> match flow is real) |
| `place.card` (sink) | **CARD** -> PREVIEW (Test run, SHADOW) or a delivered card (LIVE) | `side: setting \| left \| centre \| right` | none | YES | `PlaceCards`, `PlaceGuideSettings.cardSide`, the card history writer `PlaceCardHistory.add` (one guarded writer, unchanged) |
| `place.history` | (side read) -> JSON | window, limit 50 | none | NO | `PlaceCardHistory.recent` (context.db `place_card_log`): what live Place Guide actually showed |

### 4.4 Map: seed areas and zones (read only; no node edits a zone)

| Id | Inputs -> output | Behaviour | Live | Wraps |
|---|---|---|---|---|
| `zone.check` | **GEOFIX** or item time -> **ZONE_STATE**, branches `clear`, `blocked` | `CLEAR` only when the guard says so; `WARN`, `INSIDE`, "not judged yet", a missing row or unreadable zones are `blocked` (fail closed) | YES | `ZoneEvaluator.blocksPoint`, `ZoneController` state, `placeZoneBlocked`, `PauseDao.countCovering` |
| `pause.state` | item time -> **ZONE_STATE** | the Pause switch, auto-pause and recorded pause intervals; unreadable = `blocked` | YES | `PauseDao` |
| `seed.area` | (side read) -> JSON | seed areas and per-cell status `ready \| partial \| not_fetched`; above 4,000 cells it says so and lists nothing (+ `mapZonesP23`) | NO | `AreaEntity`, `PlacePack.cellStatuses(box)` |
| `seed.plan` (preview) | JSON -> PREVIEW, COUNTS | what would be downloaded and how big: "N cells, about X KB, from Overpass and Wikipedia/Wikidata"; under Offline only "refused: offline_only"; under a roaming data saver "would queue". Usable in experiments (its COUNTS join a report). **It never fetches**; real seeding stays on the Map seed screen | NO | `PlaceCellSource`, `OverpassHttpSource`/`OverpassMirrorSource`, `WikidataHttpSource`, partial-cell backoff |

The validator refuses any node type that declares a write effect on zones, seed areas or pins (3.5 rule 8). A guard test pins `ZoneEditor.upsert` to its one call site.

### 4.5 Location sensor and context hub

| Id | Inputs -> output | Behaviour | Live | Wraps |
|---|---|---|---|---|
| `sensor.location` | (root-less source on a live event; item time in a live tap) -> **GEOFIX** | the held fix nearest the event: zone stream, then Place Guide, then system last-known (the v18 order), within 60 s, accuracy <= 100 m. **Never** `PlaceFixProvider.fresh()` (its fallback is a one-shot request). Refused in a stored window (`live_only`) | YES | `ZoneController.lastFix()`, `PlaceSamplers.flight.good`, `LocationSource.lastKnown`, through `GeoFixReader` |
| `geo.moment` | MOMENTS or PHOTOS item -> **GEOFIX** | the `moment_location` row of the item's second; `source` ZONE_STREAM / PLACE_GUIDE / LAST_KNOWN / MEDIA_BACKFILL, `accuracyM`, `fixAgeMs`, `speedClass`, `method` (+ `momentLocation` ON) | YES | `MomentLocationDao.of` through `GeoFixReader` |
| `geo.near` | **GEOFIX** + target (pin, pack place, seed area) -> NUMBER bucket, branches `near`, `far` | buckets `<50, <100, <300, >=300 m` only | YES | `Geo` distance, edge gap |
| `context.hub` | named inputs `glasses` (MOTION_EVENT, IMU summary), `watch` (WATCH), `phone` (**PHONE_SENSORS**: activity, steps bucket, screen on, charging, worn estimate), `location` (**GEOFIX**); each optional -> outputs `context` (**HUB_CONTEXT**, highest input label, LOCAL_ONLY when `location` is wired) and `reduced` (**HUB_REDUCED**) | merges the latest value per input, with an age per input; a missing or stale input is `unknown`, never a guess. `reduced` holds only `{motion, activity, worn, speedClass, hasFix}`: no coordinate, no distance, no zone state (a pipeline inside a zone has already stopped) | YES | the phone hub's held values (`HubGraph`), `src.watch_motion` values, the existing `hub.context` motion root (which stays as is for capture plans) |

`GeoFixReader` (`orchestration/canvas/geo/GeoFixReader.kt`) is the one new file on the v18 location allow-list (applied by owner decision 10); it hands out `LocalOnly<GeoFix>` only. Only `reduced` may go toward remote, export or logs (owner decision 10).

What every geo consumer does in the four cases:

| Case | `geo.moment` / `sensor.location` | `place.candidates`, `geo.near`, `zone.check` |
|---|---|---|
| No row, no fix | `fix: none` | `skip` with `no_fix` (counted); never a guess, never a request |
| Stale (> 60 s) or inaccurate (> 100 m), or backfill beyond its class window | `fix: stale` with the bucket | `skip` with `stale_fix`. Test-run param `allowBackfill` (default ON) admits `MEDIA_BACKFILL` rows |
| Point inside a pause zone, or under a pause, judged against **today's** zones | live: `fix: zone_blocked`, value discarded in the executor. Test run: the MOMENT is dropped from the test (9.2) | never sees it |
| Zones unreadable | `zone_blocked` | never sees it |

### 4.6 Moments, Scenes and clusters

| Id | Inputs -> output | Behaviour | Live | Wraps |
|---|---|---|---|---|
| `moment.read` | (side read, or `input.moments`) -> MOMENTS | Moments of a window with parts (FRAME, MARK), sensor summary, a `hasLocation` boolean only | YES | `MomentDao`, `MomentResolver` (outside `contentHash`) |
| `scene.read` | (side or `input.scenes`) -> SCENES | Scenes of a window, `membershipRev`, derived summary state | YES | `SceneBuilder` rows, `DerivePorts`, `DerivedStaleDao` |
| `scene.cluster` | PHOTOS or SCENES -> SCENES (with `reps[]`) | clusters and representatives in `pickReps` order (medoid, most different, sharpest, <= 3) | SHADOW_ONLY | `SceneRules`, `SceneIndexer`, `ClusterContract` |
| `scene.centroid` | SCENES -> **REFSET** | the centroid per Scene and space: from `scene_centroid` when the row's `membershipRev` matches (section 8), else computed on the fly and cached in memory | SHADOW_ONLY | `SceneRules.centroid`, `frame_vector` |
| `scene.route` | PHOTOS -> JSON, branches `fast_path`, `near_dup`, `cluster` | read-only preview of `SceneRules.route` with a `SceneTuning` override; claims nothing | NO | `SceneRules`, `SceneTuning` |
| `place.dedup` | **CARD** -> **CARD**, branches `new`, `repeat` | the same place within `gapMin` (setting) of the last card is `repeat`; reads the card history, writes nothing | YES | `PlaceSampler` dedupe and call gap (extracted pure function), `PlaceCardHistory.recent` |
| `derive.preview` | MOMENTS or SCENES -> SUMMARY | the summary derive step as a dry run | NO | `DerivePass` |
| `scene.cut` | MOMENTS -> SCENES | as designed in v2.5 part B | NO | `SceneCutter` |

The "new Scenes list" screen was NOT found at the tip (UNVERIFIED; only `ui/preview/ScenePreview.kt`). The nodes read the same rows.

### 4.7 Retrieval (Ask), nudges, triggers, delivery

| Id | Status | Live | Notes |
|---|---|---|---|
| `rag.window`, `rag.condense`, `rag.retrieve`, `rag.order`, `ai.prompt` (ANSWER / NUDGE), `gate.nudge`, `out.deliver` | existing | YES (as today) | The Forget fence, photo gate and bounds stay inside `RagCore`. Safety questions and last-seen state stay inside the ANSWER prompt and `AnswerService` |
| `trigger.moment/scene/signal/schedule` | existing | YES | `maxPerHour` per pipeline |
| `trigger.mark` | new, Source | YES | fires on a recorded mark (`MarkGraph.onRecorded`), params `maxPerHour`, `kinds` (all, or mark with photo); output TRIGGER with the mark's Moment id. A normal, editable trigger (owner Round 2). Mark confirmations are user feedback and always show, outside any pipeline |
| `trigger.pin` | new, Source | YES | `on: saved \| arrived` (owner question 3); output TRIGGER with the pin id |
| `embed.search` | new (4.1) | NO | Ask keeps its own 2 image hits |
| `gate.datasaver` | new, Condition | YES | **advisory**: reads `NetworkCostMonitor` and `DataSaverStore` for a kind (`PHOTO`, `BULK_OTHER`, `SMALL`), branches `allow`, `queue`, `drop`. Writes no counter. The router enforces |
| `privacy.mode` | new, Source (side read) | YES | `auto` or `offline_only` as a branch. Unreadable = `offline_only` |
| `gate.worn` | new, Condition | YES | `worn`, `not_worn`, `unknown` from `context.hub reduced.worn`; for the not-worn fallback (deliver to phone) |

### 4.8 Retention, Forget, export (read only)

| Id | Behaviour | Live | Wraps |
|---|---|---|---|
| `retention.preview` | counts by kind for the next 24 h and 7 days, and with `retentionP2` ON how many card/mark/pin photos are kept. Unreadable = nothing | NO | `RetentionPreview`, `Retention.plan` |

No Forget node and no export node. Forget stays one flow with one confirmation dialog; export stays behind `DataOpGate`. Every node with stored output names its Forget family; the validator refuses a node type without one.

---

## 5. Typed ports, labels, exportable checkboxes, connection rules

### 5.1 New port kinds

| Kind | Carries (in the executor) | Privacy label | Stored in run rows and live traces as |
|---|---|---|---|
| EMBEDDING | `space`, `modality`, `dims`, the vector | PHOTO_DERIVED | `{space, dims, norm}`; never the vector |
| REFSET | per candidate: id, kind, vector count, vectors | PHOTO_DERIVED | ids and counts |
| CANDIDATES | per candidate: id (`wd:`, `osm:`, `user:<uuid>`), name, kind, distance bucket, origin PIN/PACK | PLACE_DERIVED | ids, kinds, buckets; names only in the phone-local inspector |
| MATCH | top-1 id, `band`, milli score, margin, `thrV`, threshold source (`calibrated \| saved`) | PLACE_DERIVED | as is |
| GEOFIX | `LocalOnly<T>` lat, lon; source, age bucket, accuracy bucket | LOCAL_ONLY | `{fix: held \| none \| stale \| zone_blocked, source, ageB, accB}`; **never a coordinate** |
| ZONE_STATE | `CLEAR` / `BLOCKED` with a fixed reason token | LOCAL_ONLY | the token |
| PHONE_SENSORS | activity, steps bucket, screen, charging, worn estimate | PERSONAL | as is |
| HUB_CONTEXT | the merged inputs, `LocalOnly` location when wired | highest input label | reduced form only |
| HUB_REDUCED | `{motion, activity, worn, speedClass, hasFix}` | PERSONAL | as is |
| MODEL | a binding: role, backend, catalog id, endpoint label | PUBLIC | as is |
| CARD | title, kind, source ids, confidence, side | PERSONAL | as is |
| COUNTS | integers and fixed labels | PUBLIC | as is |

Existing kinds: PHOTOS = PERSONAL (photo bytes stay behind the router's image guard), TEXT/JSON/EXTRACTION/SUMMARY/RAG = PERSONAL, NUMBER = inherits, VECTOR = PHOTO_DERIVED when from an image.

**Named side inputs** (`ref`, `cand`, `fix`, `target`, `model`, and the hub's four inputs) sit next to the item input. A side input is fed by a node that has no root role. The one-root rule stays for the item flow. This generalises `mixedIn` (WP0).

### 5.2 Connection rules

1. `src.output` must be in `dst.inputs`; for EMBEDDING and REFSET the `space` must match; for `describe`/`compare` the `modality` must match the input and the MODEL binding.
2. **Labels flow downstream.** Output label = highest input label, unless the node is a declared reducer (`place.candidates` drops coordinates: LOCAL_ONLY -> PLACE_DERIVED; `context.hub reduced` -> PERSONAL; `calib.report`, `retention.preview`, `seed.plan` -> PUBLIC COUNTS).
3. What may reach which consumer (the ceiling; the exportable ticks of 5.3 can only narrow it, except where 5.3 says a tick opens a default-off cell):

| Label | Remote model | `rag.*` / Ask, `memory.write` | `skill.call`, `tool.draft` | Export | Logs | Run results / live traces |
|---|---|---|---|---|---|---|
| PUBLIC | yes | yes | yes | yes | yes | yes |
| PERSONAL | after the confirmToken, not under Offline only | yes | no | via memories only | no (counts) | yes |
| PLACE_DERIVED | names and kinds into `compare` only, behind the Settings switch (owner 8); not under Offline only, not in a pause zone | **no** | no | no | ids, bands, counts; resolved names in the Detailed log (owner 9) | yes |
| PHOTO_DERIVED | **never** | never | never | never | never | `{space, dims}` only |
| LOCAL_ONLY | **never** | **never** | never | never | never | reduced form only |

4. **Three layers**: the validator refuses the edge; the estimate refuses the run (`local_only_to_remote`); at call time `LocalOnly<T>` has no serializer and no `toString` beyond a fixed token. A test scans the new executors for any path from a GEOFIX field to a request builder.
5. **Location stays local, per the v18 source rule.** No coordinate reaches a remote model, Ask, an export, a log, a backup, `CanvasApi` or the browser. The Dump API (Developer + pairing) is the one, separately gated, exception and is not a canvas path.

### 5.3 Exportable checkboxes

Every node shows one checkbox per destination and per output port: **remote model**, **export**, **log**, **run results** ("may leave this node toward ..."). Defaults come from the label table above. The graph JSON stores only ticks that differ from the default; the publish summary lists them.

| Field or label | Remote | Export | Log | Run results | Toggleable |
|---|---|---|---|---|---|
| Coordinates (any GEOFIX lat/lon, any distance below 50 m) | off | off | off | off | **locked** (owner rule; export also by the v18 source rule) |
| Photo bytes, raw vectors | off | off | off | off | locked (owner question 1: existing tests `LogContentScanTest` and the router image guard already forbid them) |
| LOCAL_ONLY reduced fields (fix state, source, buckets, zone token) | off | off | on | on | yes |
| PLACE_DERIVED names | off (on only with the Settings switch) | off | off (Detailed log allowed) | on | yes |
| PLACE_DERIVED ids, bands, milli scores | off | off | on | on | yes |
| PERSONAL | on (with confirmToken) | off | off | on | yes |
| PUBLIC, COUNTS | on | on | on | on | yes |

A tick never beats Offline only, a pause zone, the data saver or the identity guard; those are checked after it.

---

## 6. Privacy and threat model per family

Threats: (1) photos or photo-derived vectors leaving the phone, (2) a location leaving or being inferred from an output, (3) derived values outliving a Forget, (4) work inside a pause zone, (5) identity creep, (6) **a publish changing live behaviour unsafely** (a wrong threshold, a broken graph, a disabled guard, a silent online call).

| Family | Forget coverage | Offline only | Zones | Publish safety | Identity |
|---|---|---|---|---|---|
| Embedding / match | vectors cached in `ShadowCaches` (both spaces), emptied by every Forget and Delete all; run rows and live traces frame-keyed | no effect (local) | inputs are frames; none exists from a zone | SHADOW_ONLY; thresholds unset = all borderline; Save is journalled and undoable | no face crops, no person vectors; `embed.refset` refuses a `person` source |
| Model / describe / compare | frame-keyed rows | online nodes refused at validate, estimate, publish, call; no `auto` | as above | SHADOW_ONLY; publish summary names every online node | `IdentityGuard` on every output; presets forbid naming people |
| Places | candidates from local cells and pins; pin Forget unchanged; card history unchanged | no fetch at all | `zone.check` and `pause.state` must precede (validator rule 4) | `place.card` delivers only in LIVE; dedup on the path | n/a |
| Map / seed | read only | `seed.plan` says refused | fail closed; never writes | NO nodes | n/a |
| Location / hub | `GeoFixReader` only; reduced storage; a user Forget deletes `moment_location` rows (v18 WP2) | local read allowed; nothing sends | re-checked against today's zones | `reduced` is the only exportable output | n/a |
| Moments / Scenes | Moment and Scene Forget cascades; whether `moment:<id>` run rows are reached by a Moment Forget is UNVERIFIED (test in WP2); `scene_centroid` has its own family (section 8) | local | none exists from a zone | read only, except the centroid writer | n/a |
| Retention / gates | nothing stored | n/a | n/a | n/a | n/a |

Logs for all new nodes are count-only fixed tokens (node type, outcome, counts, latency buckets, band, publish id, component, mode). `LogContentScanTest` covers the new files.

**Not in scope:** identity recognition of any kind; NL tools beyond the v2.4 design (`tool.draft` takes TEXT/JSON only); an agent loop or a model choosing nodes (v3.0); any zone, pin or seed-area write; downloads of models or tiles from the canvas (the catalog downloader is its own screen); publishing the capture plan through the event path (it keeps `CapturePlanPublisher`).

---

## 7. Starter pipelines (presets)

All seeded read-only; copy to edit. `canvasCatalogue` ON.

**P1. Place card (PoI)**, component `place`, publishable (SHADOW first).
```
trigger.place_sample (dwell frames)
  -> sensor.location --fix--> zone.check --clear--> pause.state --clear--> place.candidates (300 m, pins + pack, cached cells)
  -> embed.image (nomic; siglip2 selectable) + embed.refset (centroid; pin_moment + pack_text) --ref-->
  -> embed.match
       accept     -> place.dedup --new--> place.card            [rankSkip=on only]
       borderline -> privacy.mode / model.ready -> image_compare (online or offline binding) -> rule.table (confidence >= floor) -> place.dedup -> place.card
       reject     -> none
side (Test run only): place.history -> agreement report
```

**P2. Scene caption.** `input.scenes -> scene.cluster -> image_describe (scene_summary, offline or online binding) -> memory.write (preview)`. Experiment only (memory writes stay `CanvasRunSave`).

**P3. Revisit by Scene centroid.** `input.moments (days at a pin) -> scene.cluster -> scene.centroid --ref--> embed.match` over a later visit's dwell frames, side by side with `embed.refset (single)`. Output: COUNTS of top-1 agreement and bands per reference kind.

**P4. Calibration.** Labelled set = Developer test places with imported images (`DevTestImport`), or pins with attached Moments plus `geo.near` frames within the pin radius. `-> embed.image (both spaces) -> embed.refset (single, centroid, medoid; leaveOneOut) -> embed.match -> calib.report`. The report (9.3) carries the proposed bands; Save from the report writes them as live settings (3.9).

**P5. Location-first matcher (WP-S)**, component `place`, SHADOW_ONLY.
```
trigger.place_sample -> sensor.location -> zone.check -> pause.state -> place.candidates (location first)
  -> scene.cluster (cluster the visit's frames by embedding) -> scene.centroid (one centroid per Scene) --ref-->
  -> embed.match (item = the visit's Scene centroid vs candidate refsets) -> place.dedup -> place.card (shadow: logged, not shown)
```
Run beside the built-in `PlaceSampler`; the shadow report compares top-1 with the live card. The Dump API lets the owner run the same comparison offline on the laptop in parallel.

**P6. Mark nudge.** `trigger.mark -> rag.retrieve -> ai.prompt (NUDGE) -> gate.nudge -> out.deliver`, component `nudge` or `mark`.

Existing presets stay: `default-capture`, `starter-capture-extract`, `starter-moment-summary`, `starter-watch-motion`, `default-query`, `dated-query`, `default-nudge`.

---

## 8. Scene centroid as Scene metadata (SCHEMA CHANGE, needs a ruling)

The owner wants the centroid stored as metadata of the Scene (a Scene is a collection of Moments). That is a new context.db table.

**Table (proposed DDL):**
```
CREATE TABLE IF NOT EXISTS `scene_centroid` (
  `sceneId` TEXT NOT NULL, `membershipRev` INTEGER NOT NULL, `space` TEXT NOT NULL,
  `dim` INTEGER NOT NULL, `vector` BLOB NOT NULL, `memberCount` INTEGER NOT NULL, `builtAt` INTEGER NOT NULL,
  PRIMARY KEY(`sceneId`, `membershipRev`, `space`))
```
- `membershipRev` is the existing `scene.membershipRev` (VERIFIED in `SceneEntities`, `SceneStoreTest`). A row is valid only while it equals the Scene's current rev.
- `vector`: float32 little-endian, L2-normalised, `dim` 768 (Nomic) or the SigLIP 2 dims; `memberCount` = member frames with a vector in that space.
- **Recompute on membership change:** when `SceneBuilder` bumps `membershipRev`, the same transaction deletes the Scene's rows; the writer (`SceneCentroidWriter`, single-flight on IO, guarded like `PlaceCardHistory.add`: `frameForgetRev` + `MemoryWipe.generation` unchanged, Scene not tombstoned) recomputes lazily on the next read or after the Scene closes.
- **Forget family `SCENE_CENTROID`** (leaf, `selfDeletes = false`): a user Forget of a member photo, of a range, of the Moment or Scene, multi-select and Delete all delete the rows of every affected Scene in the same transaction; retention or expiry of a member's `frame_vector` also deletes them (a centroid must not outlive its inputs). Never in export, never in logs, never in `CanvasApi` (PHOTO_DERIVED).

**Option A: v18 now (3 days before the Sunday 29 Nov install).** The v18 DDL was FROZEN on 25 Nov and is built and verified. Cost:
- migration: `MIGRATION_17_18` gains a third table; regenerate `18.json`; rewrite `MigrationV17to18Test.migrationBodyIsTwoTablesFourIndexes`, rerun `allV17RowCountsUnchanged`, `brokenMigrationRollsBackToV17`, `MigrationV17to18RealCopyRehearsalTest`, `EncryptedMigrationV17to18DeviceTest` (emulator);
- Forget: `Ref`, `ForgetPolicy`, `Families`, `DeletionGraph`, `DesignDivergence`, `WipeDao`; tests `FamilyInventoryTest`, `DeletionGraphInventoryTest`, `ForgetPolicyTest`, `ForgetPolicyConformanceTest`, `GoldenDeleteSetTest` (forgetV2 OFF and ON), `WipeDaoTest`, an export-exclusion test;
- tooling: `post-install-check.sh` expected tables, `INSTALL-TRIP-BUILD.md`; the G5 emulator rehearsal again; one review pass;
- about 7 agent-h (UNVERIFIED) plus the reruns, inside the install freeze;
- risk: v18 cannot be downgraded; a migration or Forget defect found on the trip phone has only the `v17-upgrade-recovery.md` path; and the frozen v18 work (card history, moment location) would be re-opened for a table with no writer yet.

**Option B: v19 after Sikkim (recommended).** Until then `scene.centroid` computes on the fly from `frame_vector` with an in-memory LRU cache keyed `(sceneId, membershipRev, space)` (about 200 entries, UNVERIFIED size), emptied by every Forget and Delete all (the `ShadowCaches` hook). No schema change, no Forget family, nothing to migrate; cost is recompute time per read (a Scene of up to a few hundred vectors: milliseconds, UNVERIFIED). v19 can also carry the Dump API's per-caption `model`/`modelVersion` (dump question 1). About 4 agent-h after the trip.

Owner question 4.

---

## 9. Test runs, experiments and reports

### 9.1 How a Test run dry-runs these nodes

- **Estimate first.** `runs/estimate` (built, `12677f98`) gains per node: backend, space present, `photosLeave {count, endpoint label}`, "would fetch 0 cells", geo source counts (`held / none / stale / zone_blocked`), dropped Moments by reason, the data-saver verdict, and every threshold with its calibrated default. Photos leaving default to **0**.
- **Stored window.** Geo comes only from `moment_location` rows (the fix held at capture), re-checked against today's zones. `sensor.location` is refused (`live_only`).
- **Live tap.** `sensor.location` reads the held fix; the tap stops on zone entry (`LiveTapBounds`). The tap driver itself is still parked.
- **Deterministic.** `embed.*`, `scene.*`, `place.candidates`, `place.dedup`, `zone.check`, `calib.report` are pure over stored data and re-execute in REPLAYED runs. `describe`/`compare` use `RecordedOutputsExecutor`.
- **No side effects.** `place.card`, `seed.plan`, `out.deliver` say "would"; `memory.write` is a preview; nothing touches the data saver counters (v2.1 ruling R2, still open). The only write a Test run can lead to is an explicit Save of a threshold (3.9) or a Publish, each with Developer mode and a confirmation.
- **Inspector.** EMBEDDING shows `space, dims`; GEOFIX the reduced form; MATCH band, milli score and threshold source; CANDIDATES names only in the phone-side panel.

### 9.2 Zone re-check drops the Moment

In a stored-location Test run, each Moment's location is re-checked against **today's** pause zones and pause intervals. A blocked Moment is **dropped from the test**: no node downstream sees it, and the `moment_location` row itself is untouched (only a user Forget deletes it). The run records the drop with counts only:
```
dropped: {pause_zone: n, paused: n, auto_pause: n, zones_unreadable: n, not_judged: n}
```
No Moment id, time or place is recorded for a dropped item, so the audit shows how many were dropped and why, and nothing about where.

### 9.3 Experiment and report generation

An experiment is a Test run (or an A/B of two versions, the existing `startAb`) with a labelled set or a shadow window. Its report is a JSON object stored with the run (`canvas_run`, existing) and rendered as tables in the canvas; it can be downloaded over the paired session as counts.

| Section | Content |
|---|---|
| Identity | run id, pipeline id, version, hash, component, source (labelled set, window, shadow window), dates |
| Counts | items in, scored, skipped by reason (`no_fix`, `stale_fix`, `cell_not_ready`, `space_missing`), dropped by reason (9.2), model calls, photos sent, latency buckets |
| Thresholds | every threshold used, its source (`calibrated` with `thrV`, `saved`, `run`), the calibrated default beside it |
| Quality | per space and reference kind: p10/p50/p90 milli of positives and negatives, precision, recall, F1, best-F1 threshold, accept at precision 95 and at 98 (owner 2), margin over the runner-up (distribution, owner 3), top-1 accuracy |
| Confusion by place | a matrix of true place vs predicted place (plus `none`); places as stable opaque labels `place#1..n` within the report and their kind |
| Shadow agreement | for a shadow window: same top-1 as the live card, deliver/skip agreement, band histogram, fallbacks |
| Proposed bands | the `MatchCalibration` proposal, with a Save action (3.9) |

Names appear in the confusion matrix only when the owner ticks "show place names" for that report (Developer mode; the tick is recorded in the report). **Coordinates never appear in a report**, opt-in or not: the owner's lock covers run results (5.3); the Dump API is the path for coordinates.

---

## 10. Work packages (agent hours, UNVERIFIED) and order inside 1.9.x

| WP | Content | Hours | Depends on | Gate |
|---|---|---|---|---|
| WP0 | port kinds and labels, label flow in `PipelineValidator`, named side inputs, graph schema 3, `LocalOnly<T>`, exportable checkboxes (per node and port, defaults, locks), `liveCapable` enum, `canvasCatalogue` flag, Forget-family check | 6 | v2.2 built | none |
| WP-D1 | deploy core: `kind = event`, `EventPlan` compiler (FlowPlan graphs compile to it), `DaoEventPlans`, `EventPipelineRunner` per component with built-in fallback, SHADOW/LIVE, Promote, one-step rollback that reinstates the previous version, Use built-in, auto-rollback 10 of 30, publish validator (3.5), Developer check and `publishToken`, Diagnostics lines | 8 | WP0 | none |
| WP-D2 | `LiveSettingsStore`, `calibrated` resolution, Save from a Test run, reset to calibrated, `kind = setting` journal rows, `thrV` flag in Diagnostics | 3 | WP-D1 | none |
| WP-D3 | `trigger.mark`, `trigger.pin`, `trigger.place_sample`; live event wiring (`MarkGraph`, pin save, `PlaceSampler` sample, existing `NudgeTrigger`) into the runner | 4 | WP-D1 | owner question 3 for `trigger.pin` |
| WP1 | `embed.image`, `embed.text` v2, `embed.refset`, `embed.match`, `calib.report`, EMBED executors | 5 | WP0 | bands real only after a calibration sets `MatchThresholds` or a Save |
| WP2 | `scene.read`, `scene.cluster`, `scene.centroid` (on-the-fly + LRU cache, Forget hook), `scene.route`, `moment.read`, `place.dedup`, `derive.preview`, Moment-Forget cascade test | 5 | WP0 | none |
| WP3 | `zone.check`, `pause.state`, `seed.area`, `seed.plan` (preview with COUNTS), Test-run Moment drop with counts-only reasons | 3 | WP0 | map zones device test (1.8.0) for real data |
| WP4 | `sensor.location`, `geo.moment`, `geo.near`, `GeoFixReader` + allow-list change in `MomentLocationSourceRulesTest`, `context.hub` with its four inputs and two outputs, reduced storage, the four-case table as tests | 5 | WP0, WP3 | v18 installed (1.6.0) for real rows |
| WP5 | `vision_model`, `text_model`, `model.ready`, `describe`/`compare` with `modality` (palette `image_describe`, `image_compare`), resolver factories, Offline-only refusal at validate/estimate/publish/call, place-names Settings switch, `IdentityGuard` hook | 5 | WP0 | v2.3 WP3 for the guard (or a copy of its denylist first) |
| WP6 | `place.candidates`, `place.match` composite (pure functions from `PlaceSampler`), `place.card` (preview, shadow, live sink), `place.history` | 6 | WP1, WP3, WP4, WP5 | none |
| WP7 | `gate.datasaver`, `privacy.mode`, `gate.worn`, `retention.preview`, `embed.search` | 3.5 | WP0 (`gate.worn` after WP4) | none |
| WP8 | presets P1-P6 with golden tests (synthetic vectors, fixtures; no real data in git) | 3.5 | WP1-WP7, WP-D3 | P4 real use needs 1.7.0 test place import |
| WP9 | Test run integration: estimate fields, recorded outputs for `describe`/`compare`, inspector forms, threshold edit field, experiment report (9.3) with the names opt-in | 4 | WP0-WP7, WP-D2, v2.1 screens | screens are parked in v2.1 |
| WP-S | shadow matcher: P5 published as SHADOW on the `place` component (location first, cluster by embedding, centroid per Scene, dedup); shadow report vs live `PlaceSampler`; cards unchanged | 4 | WP-D1, WP-D3, WP1, WP2, WP4, WP6 | device for real events; the Dump API offline test in parallel |
| WP10 | offline vision behind the resolver (`nativeScoreImage`, `nativeGenerateImage`) | 2 here | 1.10.0 offline VLM | device |
| WP11 | `skill.call` refusal of new labels | 1 | WP0 | Nexus 1.11 bridge |
| WP12 | device session: SigLIP memory, a stored window with locations, a zone re-check and drop counts, publish P1 in SHADOW, a threshold Save and its Roll back, an Offline-only switch with an online node deployed, Use built-in | 3 + user | WP-D1..D3, WP1-WP9 | phone |
| Review | one Codex pass (Opus low if out) + one fix round | 2.5 | all | |
| **Total** | | **~73.5 h** | | |
| WP13 (conditional) | `scene_centroid` table, writer, Forget family: v18 now (~7 h, inside the freeze) or v19 after Sikkim (~4 h) | not in total | WP2 | owner question 4 |

The deploy model adds about 26 h over the first version (WP-D1..D3, WP-S, the exportable checkboxes, the hub and the report). No licence-text work item (dropped by the owner).

Recommended order: WP0 -> WP-D1 -> (WP1, WP2, WP3, WP7, WP-D2 in parallel worktrees) -> WP4 -> WP5 -> WP-D3 -> WP6 -> WP8 -> WP9 -> WP-S -> review -> WP12. WP10 follows 1.10.0, WP11 follows the 1.11 bridge, WP13 follows the ruling. A first useful cut with no device (WP0, WP-D1, WP1, WP2, P4 of WP8) is about 25 h; the first live-facing cut (P1 in SHADOW) needs WP12.

---

## 11. Coverage: every existing pipeline or feature, and its canvas node or why not

| Pipeline or feature (ROADMAP row) | Canvas node or preset | Publishable as | Not exposed, why |
|---|---|---|---|
| Glasses capture plan: IMU, settle, thresholds, novelty, capture rate, rate cap, capture image (0.9.x) | existing glasses-lane nodes, `default-capture` | `capture_plan` (own publisher, SHADOW/TRIAL/LIVE) | |
| Traces, replay, sweep, sensor recording (0.9.x, 0.9.7) | Test run source `trace`, existing replay | n/a | |
| Keyframe routing: salience, semantic dedupe, clusters (wave 2) | `scene.route` (preview), `scene.cluster` | no | live routing claims frames (CAS) |
| Photo queue, claims, retry, FAILED_FINAL revive (1.6.0 WP9) | none | no | state machine with CAS and leases; a pipeline must never claim a frame |
| Extract, critic, local trust, save memory | `ai.vision_extract`, `ai.critic`, `gate.local_critic`, `memory.write` | `interpreter` (exists) | |
| Cluster summary, scene caption | `scene.cluster` + `image_describe`, P2 | no (memory writes stay `CanvasRunSave`) | |
| Describe on demand, photo search (wave 3) | `image_describe`, `embed.search` | no | |
| Moments, Scenes, summary derive (0.9.3) | `moment.read`, `scene.read`, `derive.preview`, `input.*`, `starter-moment-summary` | read nodes only | |
| Scene centroid (owner, 26 Nov) | `scene.centroid`, P3, P5 | `place` (SHADOW) | stored table waits for question 4 |
| Scene cut config (v2.5 B) | `scene.cut` | no | |
| Ask on RagCore (1.1, 1.3) | `rag.*`, `default-query`, `dated-query` | `query` (exists) | safety questions and last-seen state live inside the ANSWER prompt |
| Quick recall | counts in `retention.preview`; `rag.retrieve` recent flags | no | a retention tier, not a step |
| Nudges: triggers, gates, delivery (0.9.8) | `trigger.*`, `gate.nudge`, `out.deliver`, `default-nudge` | `nudge` (exists) | |
| Marks (0.9.6) | `trigger.mark`, `moment.read` (MARK parts), P6 | `mark`, `nudge` | confirmations are user feedback and always show |
| Pins and pin-to-Moment (1.4.2) | `trigger.pin`, `place.candidates`, `embed.refset from=pin_moment` | `place`, `nudge` | pin edits stay in Settings |
| Suggestions loop, proactive replay, shadow eval | none | no | already read-only Developer routes |
| Not-worn fallback | `gate.worn` | yes | |
| Voice and chat input | `input.utterance` | `query` | |
| Watch motion | `src.watch_motion`, `context.hub watch` | via hub | |
| Phone and glasses sensors, location | `context.hub`, `sensor.location`, `geo.*` | yes (reduced only outward) | |
| Hub auto-start and AutoStop | none | no | lifecycle under its own guards (`AutoStartGate`) |
| Place Guide live: pin-first, cluster card, dedupe, ambiguity, call gap (1.4.8) | `place.match`, `place.dedup`, P1, P5 | `place` (SHADOW first) | built-in `PlaceSampler` stays the fallback |
| Place cards on glasses, left/centre/right | `place.card` | `place` | |
| Card history (v18) | `place.history`, `place.dedup` | read only | |
| Pack places (1.1, 1.4.7) | `place.candidates` (cached), `seed.area`, `seed.plan` | candidates yes; seed no | fetches stay in Place Guide and the Map screen |
| Pack Moments (1.4.6) | `embed.refset from=pack_text` | yes | |
| Semantic ranking, SigLIP shadow, calibration (1.4.4) | `embed.*`, `calib.report`, P1, P3, P4, P5 | match nodes SHADOW_ONLY | |
| Model resolver, Offline only (1.4.3) | `vision_model`, `text_model`, `model.ready`, `privacy.mode` | yes | the setting itself |
| Offline VLM (1.10.0) | `vision_model backend=offline` | later | not built |
| Data saver (1.4.x) | `gate.datasaver` (advisory) | yes | enforcement stays in `ModelRouter.call`, `DataSaverWorkGate` |
| Pause zones, Pause switch, map zones P1-P3 (1.4.9) | `zone.check`, `pause.state` | yes (read) | zone edits (zone guard is the only writer) |
| Offline notice on the zones map | none | no | UI only |
| Moment location, recorder, GPS backfill import (1.6.0) | `geo.moment`, `sensor.location`, `geo.near` | yes | the import route is a Developer step |
| Developer test place, image import (1.7.0) | input to P4 and fixtures | no | creating a test place stays in Developer |
| Retention v3 and P2 (1.8.0) | `retention.preview` | no | expiry itself |
| Forget, multi-select Forget, Delete all (1.2) | none (nodes honour it) | no | one flow, one confirmation dialog |
| Export, Dump API | none | no | `DataOpGate`; the Dump API is its own Developer + pairing route |
| Detection (v2.3), NL tools (v2.4), skill nodes (v2.5) | their own docs | tier 3 never | |
| Nexus HUD delivery (1.11) | `out.deliver` target later | later | not built |
| Activity paging, date filter, trip logging, link diagnostics, install helper | none | no | UI or tooling |

---

## 12. Owner questions still open (recommended default in bold)

Answered and applied in this revision: the geo reader file (decision 10), centroid default (11), thresholds editable with Save (12), `place.match` and the deploy model (Round 2), vision node names (14, Round 2), place names in prompts (8), `seed.plan` (Round 2), the Moment drop on re-check (Round 2), `gate.datasaver` advisory (Round 2), `trigger.mark` in scope (Round 2), the licence text (Round 2).

1. **Photo bytes and raw vectors locked out of logs and export, like coordinates?** Plain English: even with a checkbox, a photo or an embedding vector can never be written to a log or an export from the canvas (the Dump API stays your way to pull vectors). **Yes.**
2. **Promote a new matcher from Shadow to Live only after 30 shadow-scored events?** Plain English: the "go live" button stays greyed until the new logic has quietly scored at least 30 real events beside the current one, so you have numbers before it changes what you see. **Yes, 30, no agreement floor** (you judge the report).
3. **What fires `trigger.pin`: saving a pin, or arriving near one?** Plain English: should a pin pipeline run when you create or attach a pin, or when you later walk up to that pinned place? **Both as a choice, default "arrived".**
4. **Scene centroid table: v18 now, or v19 after Sikkim?** Plain English: storing each Scene's average image vector needs a new database table; adding it now re-opens the frozen database upgrade three days before the Sunday install, while waiting means computing it on the fly (slightly slower, nothing stored) until after the trip. **v19 after Sikkim.**

Other open items live in their own docs: v2.1 ruling R2 (Test-run photo uploads against the data-saver caps) and Dump API questions 1-5 (`handoffs/dump-api-2026-11-26.md`).
