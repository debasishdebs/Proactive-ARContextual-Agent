# Canvas v2.1 (live end-to-end test) and v2.2 (model, classifier, router and rule nodes): design (2026-11-24)

Designer: Opus. Implementer: Sonnet, in the phases below. Reviewer: Codex sol at medium effort, or Opus at low effort when the Codex quota is out.
Branch `feat/v101-ask-eval`, `android-companion/`. This is a design only. It changes no source, needs no install and needs no phone.

ROADMAP rows:
- **v2.1** live end-to-end test on the canvas: run a pipeline over stored or live traces and see every node's output.
- **v2.2** model nodes on the resolver (online/offline), classifier and router presets, rule/condition node sets.
- **v2.3** (here only as needs) detection nodes: tier (a) prompt preset, tier (b) on-device detector. No identity recognition.

The user's words: "real time test on canvas E2E? Yesss!! We should have it by v2"; "object detection: person detection, scene detection, object detection, motion detection".

Labels: VERIFIED means read in the code on this branch. UNVERIFIED means inferred or estimated.

---

## 0. What already exists (VERIFIED), and the gap

The canvas already has four separate "run it and look" paths. None of them runs a whole pipeline across its lanes over one time window.

| Path | Code | Covers | Output kept |
|---|---|---|---|
| **Experiment run** over chosen photos, Moments, Scenes or a watch window | `services/orchestration/.../canvas/CanvasRunner.kt` (`estimate*`/`start*`, `MAX_PHOTOS=50`, `MAX_NOMINAL_CALLS=200`, `KEEP_RUNS=20`, one run at a time, `Busy`) | phone-lane executors only (`Executors.kt`): `ai.vision_extract`, `gate.local_critic`, `ai.critic`, `ai.prompt`, `proc.summary`, `agg.window`, `proc.motion_model`, `gate.compare`, `route.switch`, `out.set_frequency_preview`, `memory.write` (preview), `py.fn` (helper on the Mac, `PythonExecutor` + `helper/HelperJobService`) | `canvas_run` + **`canvas_run_item` per (run, frame, node)**: status, branch, outputJson, error, latency, tokens, model, promptVersion, batchId lineage, calls, imageDelivered |
| **Save** of a run | `CanvasRunSave.kt` (`POST runs/:id/save`) | the only commit: the real `ExtractSave` write path, on explicit request | memories |
| **Trace replay and sweep** (glasses capture plan) | `plan/ReplayService.kt`, `plan/SweepService.kt`, `trace/StoredReplayTraces.kt` -> `core/capture/.../replay/CounterfactualReplay.kt` | the glasses lane (IMU, gates, capture policy) over imported sensor traces (`TraceEvent`: Gyro, Accel, Ctx, Gate KEEP/SKIP with `keptAtMs`, Episode...). **Traces hold no images.** | `canvas_replay` (`paramsJson`, `resultJson`, `sourcesJson`, `fromMs/toMs`, `invalidated`, `labelRev`): aggregate figures only, no per-node values |
| **Live view** of the published interpreter | `PipelineInterpreter.kt`, `GET /api/live`, `assets/canvas/liveview.js` | the published pipeline on real frames | `canvas_live_trace` per (publish, frame, node) |
| Expression and signal try-its | `ai/canvas/plan/ExprTry.kt` (the same `ExprRunner` as the glasses), `signal/SignalTryReader.kt` (refuses seconds whose zone status is not CLEAR) | one expression over typed or recorded values | nothing |
| Day dry-runs | `POST /api/proactive/replay` (`ProactiveReplayRoute.kt`), `POST asks/:id/replay` | Suggestions loop / one Ask over stored data, read-only, no provider call | nothing |
| Offline trip replay harness | `docs/.../2026-11-24-offline-trip-replay-harness.md`, test code in `services/orchestration/src/test/.../work/frames/place/replay/` | Place Guide only, on the Mac, JVM tests driving the real `PlaceSampler` | TSV under `~/Desktop/rokid/backups/` |

**The gap for v2.1:**
- A run cannot take "a day" or "a trace" as its input. Today the user picks at most 50 photos by hand in `runNewView` (`views.js:187`).
- The glasses lane and the phone lane are tested separately. A replay answers "which frames would the plan keep". A run answers "what would the models say about these photos". Nothing joins the two.
- Glasses-lane nodes show no per-node output. `ReplayReport` holds figures only.
- Query and Nudge lane nodes (`rag.*`, `gate.nudge`, `out.deliver`) have no run executor. They are tested only through the asks replay and the proactive replay.
- No streaming input exists. `CanvasRunner` binds the frame list at estimate time (`confirmToken`).
- Runs are not reproducible. `LanguageModel`/`ModelRequest` carry no seed (VERIFIED: no `seed` in `services/ai/src/main`). There is no way to re-use recorded model outputs.

**What the offline harness teaches:**
- Drive the **real** code through its seams. Never re-implement a gate.
- A "Recorded" match stage makes replay deterministic and free.
- Photos leave the device only after a typed confirmation that counts them.
- Real data never enters git.

v2.1 copies these four rules onto the phone.

---

## 1. v2.1: live end-to-end test on the canvas

### 1.1 What it means here, concretely

A **Test run** takes three choices:
1. One saved pipeline version, or a draft (drafts are hashed exactly as runs are today).
2. One **source**:
   - **(a) Stored window.** A local day, or `[from, to)` on that day, or one imported trace (its `startedAtMs..endedAtMs`).
   - **(b) Live tap.** The frames committed from now on, for a bounded session.
3. A **mode**: dry-run (always), with an optional **Save** afterwards that goes only through `CanvasRunSave`.

It then runs every lane the pipeline has:

| Lane | Stored window | Live tap |
|---|---|---|
| Glasses (`imu.*`, `gate.*`, `policy.capture_rate`, `capture.image`, `expr.*`, `signal.*` with glasses domain) | `CounterfactualReplay` of the pipeline's compiled plan (`PlanCompiler`) over the traces that overlap the window, **plus per-node probes** (1.3) | Not run. The live glasses run the **published** plan. A test never publishes and never changes the capture plan. The inspector shows the live `canvas_live_trace` values of the published plan beside the draft's phone-lane output |
| Watch (`src.watch_motion`) | the existing watch run (`startWatch`) over the window | the hub's recent values, read in ticks |
| Phone photo lane (the executors listed in section 0) | `CanvasRunner` over the frames stored in the window, picked by the frame picker (1.2) | the same executors, per new frame, in a streaming run (1.5) |
| Moment and Scene inputs | Moments and Scenes whose span lies in the window (`startMoments` / `startScenes`) | Moments and Scenes as they close (`MomentBuildTrigger`) |
| Query / Nudge (`rag.*`, `gate.nudge`, `out.deliver`) | the dry-run engines that already exist: `ProactiveReplayRoute` for one day and `asks/:id/replay`. Their per-step candidates go into the inspector. **No delivery** | Not in v2.1. Nudge delivery stays the published pipeline's job. Added in v2.5 or v3.0 |

**The join (stored window).**
- A Gate `KEEP` event carries `keptAtMs` (`TraceRecords.kt:161`). A stored frame carries `capturedAtMs` (`frame_meta`).
- The E2E record lists for each trace KEEP its matched frame id, or `not_stored`. A match is within a tolerance (default 1.5 s, UNVERIFIED: tune it on a collect session). `not_stored` means the frame was dropped later by near-dup, retention, Forget or the zone fence.
- For a **counterfactual** plan, keeps that the recorded plan did not make have **no photo**. The report says so plainly: "the draft would also keep 14 frames that were never captured; their phone-lane output cannot be shown". The test never invents a photo.

### 1.2 Frame picker for a stored window (deterministic)

`WindowFramePicker` (new; pure; in `services/orchestration/.../canvas/window/`):
- **Input:** window, pipeline hash, `mode` (`dwell` | `even` | `all_dwell_first`), `n <= MAX_PHOTOS` (50), `seed`.
- **Candidates:** `frame_meta` rows in the window that are:
  - live (not tombstoned);
  - photo present;
  - not near-dup unless asked.
- **Pick:**
  - `even` takes time-stratified buckets with one frame per bucket, chosen by `hash(seed, bucket)`.
  - `dwell` takes the dwell frames first, then fills evenly.
- **Same input, same frames.** The picker is a pure function. The seed defaults to `hash(window, pipelineHash)`.
- **Output:** the frame ids, plus a "picked 50 of 1,312" note. These go to the existing `CanvasRunner.estimate/start`. The 50-photo and 200-call caps stay. A larger window is several runs, and the user starts each one.

### 1.3 Glasses-lane probes ("see every node's output" for the glasses lane)

`CounterfactualReplay.run` gets an optional `probe: (nodeId, tMs, value) -> Unit` sink. It is called by the **same** `ExprRunner` / gate code at each evaluated second.
- No second evaluator. The rule from `ExprTry.kt` holds.
- The probe is off by default, so the sweep pays nothing.
- **Storage** is bounded: per node at most 600 points (per-second values downsampled to the window), plus counts (KEEP/SKIP per reason). These go into the E2E record's `resultJson`.
- **Privacy:** a trace second the zone fence held is excluded before evaluation, as in `SignalTryReader`.
  - Open question (ruling R4): `SignalTryReader` today treats the zone status of recorded `moment_sensor` seconds as UNKNOWN and refuses them.
  - Collect-session traces are explicit Developer recordings. Whether their seconds may be shown needs the ruling.
  - Until it is given, probes show counts only and no per-second series.

### 1.4 Per-node inspector (editor UI)

The run and E2E data already hold per-node rows. The new part is mostly the view.
- **Where.** `floweditor.js` / `flowadapter.js` get an "inspect" overlay when the page is opened with `?run=<id>` or `?e2e=<id>`.
  - Every node shows a badge: OK n / ERROR n / skipped n, the branch counts and the median latency.
  - Clicking a node opens a side panel (new `inspectview.js`, `h()` only, no HTML sinks, same as `views.js`).
- **Phone node panel.**
  - One row per item: thumbnail (`photos.js`, same-origin), status, branch, the output JSON pretty-printed and truncated at 4 KB with an expand control, error, model label, tokens, latency, `imageDelivered`.
  - The incoming edge's value for the same item ("input"). This is the upstream node's row, so nothing new is stored.
- **Glasses node panel.** An inline SVG sparkline of the probe series and the KEEP/SKIP reason table. No chart library.
- **Query/Nudge panel.** The replay's step list: candidate, score, verdict and the gate that dropped it.
- **Diff.** "Compare with run…" reuses `GET compare` and `compareView`, extended to per-node rows.
- **Run start page.** `runNewView` gets a "Source" switch: *Pick photos* (today) | *Day / window* | *Trace* | *Live tap*.
  - The estimate card is the existing `Estimate.Ok`: photos, nominal/max calls, models, tokens.
  - It adds one plain line: "N photos will be sent to <endpoint label>", or "No photo leaves this phone".

### 1.5 Live tap (streaming dry-run)

`LiveTapSession` (new, `services/orchestration/.../canvas/live/`):
- **It is one `canvas_run` row with status `RUNNING`.** `canvas_run_item` rows are appended per frame. The PK `(runId, frameId, nodeId)` already allows this.
- **Its input** is the committed-frame signal that `PlaceSampler` and the interpreter already use (`KeyframeGraph.signal()`). Each new frame id is fed to the same executor loop.
  - This needs a refactor. The per-node loop body in `CanvasRunner` (lines ~742-900) becomes a `runItems(ids)` function that both the batch path and the tap call. Fences, leases and lineage stay where they are.
- **Bounds:** all are checked before each frame, and any one ends the session.
  - duration ≤ 15 min (default 5);
  - frames ≤ 50;
  - nominal model calls ≤ 200 (the run caps);
  - photos sent ≤ a per-session cap the user sets at start (default 0 = offline only);
  - Model privacy changes, a pause zone is entered, the hub stops, the canvas page closes (heartbeat 30 s), Delete all, or a Forget overlapping the session.
- **Lock.** The tap holds the runner lock, so no other run starts (`Busy`). The **live** pipeline keeps running. The tap is a side branch and never replaces live extraction.
- **Call class** is `CallClass.EXPERIMENT`, so it is never charged to the live budget. Data saver: see ruling R2.
- **Emulator test** uses a fake frame flow. A real test needs the glasses: frames must come from the real capture path.

### 1.6 Deterministic replay ("seeds")

Model calls are not deterministic, so v2.1 offers three levels and labels every run with one.

| Level | How | Exact? |
|---|---|---|
| **REPLAYED** | `RecordedOutputs` executor wrapper. For each model node, look up the source run's row for the same frame and node with the same **input key**: `sha256(node.type, canonical params, promptVersion, model label, upstream outputJson for this frame from the source run)`. On a hit, serve its `outputJson`/branch with `calls=0`. A miss is `not_recorded` (default) or a live call (user choice per run). Deterministic nodes (`agg.window`, `gate.compare`, `route.switch`, expr, rules) re-execute for real. | Yes, for hits |
| **SEEDED** | `ModelRequest` gets an optional `seed` and `temperature=0`. `OpenAiCompatProvider` and `OllamaProvider` forward them where the API takes them. llama.cpp (offline) sets the sampler seed. `py.fn` gets `seed` in its payload. | Best effort online (providers do not promise it); exact offline (UNVERIFIED for mtmd) |
| **LIVE** | today | No |

- A **determinism report** after a REPLAYED or SEEDED run reads "47/50 items identical to run X; 3 drifted at node `critic`", with a link to the diff.
- The input key needs **no new column**. It is recomputed from the source run's own rows, which are kept by the same `KEEP_RUNS` prune. If the source run is pruned or Forgotten, replay of it is refused.

### 1.7 Side-effect safety and privacy gates

Every Test run is a dry-run. The test below fails if any of these is broken.

| Effect | Rule |
|---|---|
| Memory write | Preview only (`MemoryWritePreviewExecutor`). Commit only through `POST runs/:id/save` (`CanvasRunSave`), on explicit request, per run. Live tap: Save after the session ends, never during it |
| Delivery (HUD, glasses card, phone notification, watch) | Never from a test. `out.deliver` and `gate.nudge` show "would deliver" |
| Capture plan | Never published by a test. Publish stays `PlanRoutes`/`CapturePlanPublisher` with its own evidence gates |
| `signal.out` | Not emitted to the hub |
| `py.fn` | Helper job under its existing confirmation (`HelperConfirm`, TTL). A tap refuses pipelines with `py.fn` in v2.1 (each item would need a confirmation) |
| **Photos leaving the phone** | (1) **Model privacy:** under `OFFLINE_ONLY`, an online vision node is refused at estimate (`offline_only`), not at call time. (2) `confirmToken` binds the photo count and the endpoint labels, as today. (3) The `ModelRouter` liveness guard still drops a call for a photo Forgotten mid-run (`PhotoForgottenException`). (4) Data saver photo checks (`DataSaverBlockedException`) apply. (5) Live tap: photos sent count against the per-session cap, which defaults to 0 |
| Zones | Frames are never captured inside a pause zone (fail closed). Trace seconds that the fence held are excluded (1.3). Live tap stops when a zone is entered |
| Logs | Count-only, fixed tokens (the `CanvasRunner.line` contract): never an id, a text or a coordinate |

### 1.8 Storage, retention and the Forget cascade (no Room change)

- **Phone-lane results** go in `canvas_run` / `canvas_run_item`, as today.
  - A Forget of a frame already deletes its rows through the frame index and the `mf:` lineage tokens (`CanvasDao.deleteCanvasOutputsForFrames`).
  - A mark undo removes its rows through `deleteRunRowsOfLabels`. Delete all wipes them.
  - The `KEEP_RUNS=20` prune applies. It is safe against a backwards clock (train16).
- **The E2E record** is one `canvas_replay` row with `replayClass = "E2E"`. Its fields are:
  - `paramsJson`: source, window, seed, picker mode, the phone `runId`, the replay ids;
  - `resultJson`: the join and the probes;
  - `sourcesJson`: the trace ids;
  - `fromMs/toMs`.
  - So the existing trace lifecycle applies unchanged. A time-range Forget tombstones the overlapping traces and invalidates their results (`CanvasSlice3Dao.ForgetOutcome.resultsInvalidated`), and an invalidated E2E record is shown as "invalidated by a Forget" with its probes removed.
  - The phone run it points to is cascaded on its own.
  - Open: check that `replayClass` is free text and that the replay list tolerates a new class (UNVERIFIED; read `ReplayRoutes` before P1).
- **Run source label** (day, trace, live) shown in the run list. It is not a privacy field.
  - Option A (preferred, no schema change): put it into the E2E record. A plain day run without a trace still gets a small E2E row, which holds no frames.
  - Option B: a nullable `sourceJson` column on `canvas_run`, folded into the next Room migration (ruling R5). The v18 slot is contested (section 3).
- **Retention v3** (`2026-11-24-retention-v3-keep-facts-design.md`) may thin photos in old windows. The picker reports "photo removed by retention" separately from a Forget.

### 1.9 API routes (phone, `phone/.../canvas/api/`)

| Route | Body / answer | Notes |
|---|---|---|
| `POST runs/estimate` (extended) | `+ source: {kind:"window", from, to, mode, n, seed} \| {kind:"trace", traceId, mode, n, seed}` | returns `Estimate.Ok` + `picked`, `of`, `photosLeave: {count, endpoint}` |
| `POST runs` (extended) | same source + `confirmToken` + `replay: {fromRunId, onMiss:"error"\|"live"}?` | starts the phone run |
| `POST e2e` (new, Developer only) | `{pipelineId, version?, source, seed, replayFrom?}` | starts the glasses replay with probes + the phone run, returns `{e2eId, runId, replayIds}` |
| `GET e2e/:id` | join, probes, links | |
| `POST e2e/:id/cancel`, `DELETE e2e/:id` | | |
| `POST runs/tap` (new) | `{pipelineId, version?, maxMin, maxFrames, maxPhotosSent, confirmToken}` | estimate first through `POST runs/estimate {source:{kind:"tap"}}`. 409 `busy` if another run is active |
| `POST runs/:id/stop` | ends a tap cleanly (not a cancel: its rows stay) | |
| `GET runs/:id/items?node=&after=` | paged per-node rows for the inspector | today `GET runs/:id` returns everything |

All of these go through `CanvasRouter` (bearer, Host policy). New route templates are added in `CanvasApi.templateOf`.

### 1.10 Tests

JVM unit tests in `services/orchestration/src/test/java/com/rokid/context/canvas/` (fakes, virtual clock, no Robolectric where possible):
- `WindowFramePickerTest`: same seed gives the same ids; buckets cover the window; tombstoned, near-dup and photo-less frames are excluded; 50 cap.
- `E2eJoinTest`: KEEP to frame within tolerance; `not_stored`; a counterfactual extra keep is marked "never captured".
- `ReplayProbeTest`: probe values equal `ExprTry` values for the same series. Probe off gives the same `ReplayReport` bytes as before.
- `RecordedOutputsTest`: hit means 0 calls and identical rows; changed params or promptVersion means a miss; `onMiss=error`; a source run pruned or Forgotten is refused.
- `LiveTapSessionTest`: each bound ends the session (time, frames, calls, photo cap, privacy flip, zone, Forget, Delete all); rows commit through the fenced `insertRunItemsLeased`; the live pipeline is untouched.
- `TestRunPrivacyTest`: `OFFLINE_ONLY` refuses an online vision node at estimate; no `ModelRequest` with an image is built without a matching `confirmToken`; the data saver block surfaces as an item error, not a crash.
- `E2eForgetCascadeTest`: a frame Forget mid-run deletes its rows and stops the run; a time-range Forget invalidates the E2E record.

Others:
- Phone route tests next to the existing `phone/src/test/.../canvas` route tests: auth, templates, 403 Developer off, 409 busy.
- JS: the pure parts (inspector row model, sparkline scaling) as no-DOM modules in the `runlogic.js` style. Whether a JS test runner is wired is UNVERIFIED: check `phone/src/test` before P2.
- Fixtures: the synthetic trace fixtures under `services/orchestration/src/pulledfixtures/.../canvas/trace` plus a synthetic `frame_meta` set. No real data in git (harness rule R1).

### 1.11 Phases and effort (agent-days, UNVERIFIED)

| Phase | Content | Effort | Needs device? |
|---|---|---|---|
| **P0** | `WindowFramePicker`, `runs/estimate`/`runs` source extension, "Day / window" on `runNewView` | **S** (1-1.5 d) | No (emulator + JVM) |
| **P1** | probes in `CounterfactualReplay`, E2E record on `canvas_replay`, join, `POST e2e` | **M** (2.5-3 d) | No. Real collect traces help (one Sikkim collect session, ruling R4) |
| **P2** | inspector overlay + side panel + paged items route + per-node diff | **M** (2-3 d) | No (emulator WebView or desktop browser over LAN) |
| **P3** | REPLAYED/SEEDED levels, `seed` in `ModelRequest`/providers, determinism report | **M** (2-2.5 d) | No |
| **P4** | `LiveTapSession`, `runItems` refactor of `CanvasRunner`, tap routes and UI | **L** (3-4 d) | **Yes**: glasses frames, a zone entry, a privacy flip, screen-off |
| **Total** | | **~11-14 d** | |

Order: P0 → P2 → P1 → P3 → P4.
- P2 before P1 because the inspector pays off on today's runs.
- P4 last because it is the only phase that needs the glasses and the only one that touches the runner's core loop.

---

## 2. v2.2: model nodes on the resolver, presets, rules (and v2.3 needs)

### 2.1 Today (VERIFIED)

- **Canvas model calls do not go through the resolver.** Executors call `LanguageModel.complete/completeOn` through `RunContext` (role bindings, `ModelRouter`, fallbacks, data saver, liveness guard, `CallClass.EXPERIMENT`).
- **The resolver** (`services/ai/.../resolve/ModelResolver.kt`) knows only `ResolverRole.PLACE_CONFIRM` and `PLACE_EMBED` (the second has no factory yet).
  - `BackendDecision.decide` gives these rules: `OFFLINE_ONLY` gives the offline backend or unavailable, never online; `AUTO` gives online if the net is not known down, else offline, else unavailable.
  - `Resolved.Ready` carries `label`, `cacheHit`, `loadMs` and `mode`. `ModelPrivacyStore` fails closed. `OfflineOnlyGuard`/`OfflineOnlyNet`/`OfflineOnlyDns` refuse remote reach and VPN LAN.
- **The offline backend for vision does not exist (1.4.5 not started).** The JNI `llama_jni.cpp` has five functions (load, embed, generate, generate-with-grammar, release), text only. The CMake builds `llama`+`ggml` only, baseline armv8-a, no mtmd.

### 2.2 New node types (`services/ai/.../canvas/NodeTypes.kt`, family PROCESS or CONDITION)

Every model node has these params:
- `backend: auto | online | offline` (default `auto`);
- `maxLatencyMs`;
- `onUnavailable: error | skip | fallback_text`.

The effective backend is `BackendDecision.decide(ModelPrivacy, net, …)` **intersected** with the node's choice:
- `online` under `OFFLINE_ONLY` is refused at validate/estimate with the fixed reason `offline_only`;
- `offline` with no local model gives `offline_only_no_local_model`.

| Type | In → out | Executor | Notes |
|---|---|---|---|
| `model.text` | TEXT/JSON → JSON | PROMPT | the generic prompt node on the resolver; replaces nothing (`ai.prompt` stays for saved pipelines) |
| `model.vision` | PHOTOS → JSON | PROMPT | photo in. The estimate counts photos leaving the phone per backend |
| `model.classify` (preset) | PHOTOS or TEXT → JSON `{label, confidence}` + branches = labels | PROMPT | labels 2..12, `none` always present. Online: JSON schema output. Offline: logit scoring of single-token labels (the 1.4.5 design, `nativeScoreLabels`) or GBNF. Threshold param; below it gives `none` |
| `model.route` (preset) | same → branch per label | PROMPT + SWITCH | a classify whose branches fan out, like `route.switch` (`branches` lambda) |
| `embed.match` | PHOTOS/TEXT → JSON `{best, score, scores[]}` | EMBED | cosine to reference texts or reference Moments. It rides 1.4.4 (SigLIP 2 text-image) and is the cheap, offline "classifier" |
| `rule.when` | JSON → JSON + branches `pass`/`fail` | EXPR (new item-JSON variant) | a predicate over fields of the item (`confidence >= 0.7 && label in [...]`). The **expr language** stays the one in `ExprProgram`, with a field-access term added. A second rule language is out |
| `rule.all` / `rule.any` / `rule.not` | branches → branch | BUILTIN | combine conditions |
| `rule.table` | JSON → branch | TABLE (the `ExecutorKind.TABLE` slot already exists) | first matching row wins; rows edited in `paramform.js` |
| `rule.debounce` / `rule.rate` | item stream → item stream | BUILTIN | time-based. Phone-lane twins of `gate.rate_cap` |

**Accounting.** No schema change. `canvas_run_item.model` gets the resolver's fixed label (`remote:AZURE_OPENAI`, `device:LLAMACPP_LOCAL`), never a user string.
- Per node, the inspector shows: calls, tokens, median/p95 latency, `loadMs` on a cold load, backend, photos sent, data-saver blocks.
- Money cost: there is no price table in the app (UNVERIFIED that none exists). Show tokens and bytes, not currency, until ruling R8.

**Resolver wiring.**
- Add `ResolverRole.CANVAS_TEXT`, `CANVAS_VISION` and `CANVAS_EMBED`.
- The online factory wraps the existing `ModelRouter` route (`OnlineRouteFactory.kt`). Online calls therefore keep fallbacks, the data saver and the liveness guard.
- The offline factories are the llama.cpp text model (exists) and, after 1.4.5, mtmd vision.
- **One choke point.** Executors never call a provider directly. The `TestRunPrivacyTest` from 1.10 is extended to every new node.

### 2.3 Detection nodes (v2.3, needs only)

**No identity recognition** in any tier. The rules:
- no face embeddings, face crops, names or "who is this";
- the prompt presets forbid naming people;
- the output validator drops any `name`-like field for the `person` class.

A separate spec and threat model would be needed to change this (ROADMAP v2.3).

| Detector | Tier (a): prompt preset on `model.vision` | Tier (b): on-device |
|---|---|---|
| **Person** | `{people: 0..20, groups: n}`, count only | MediaPipe Tasks Object Detector (EfficientDet-Lite0/2, COCO, `person` class), boxes + scores. Apache-2.0 (VERIFY licence of the model card before use). YOLO/Ultralytics is avoided (AGPL) |
| **Object** | `{objects:[{label, count}]}` over a fixed vocabulary, or open vocabulary | the same detector (80 COCO classes). Open vocabulary would be zero-shot with SigLIP 2 over crops (heavy; later) |
| **Scene** | `{scene: enum(indoor/outdoor/street/museum/restaurant/vehicle/nature/…), confidence}` | zero-shot SigLIP 2 image embedding vs scene label texts. It reuses the 1.4.4 embedding model, so it adds **no new model** |
| **Motion** | not a model task | **already built**: IMU head motion (`imu.head_motion`, `imu.settle`), frame-change hash gates (`gate.novelty`, `GateParams.hamming/bigChange`), watch motion (`src.watch_motion`). v2.3 adds presets only |

What tier (b) needs:
1. A new dependency: `com.google.mediapipe:tasks-vision` or bare LiteRT. APK grows by several MB (UNVERIFIED; measure). Model file about 4-7 MB, pinned by SHA-256, downloaded once with consent, or bundled.
2. A `DetectExecutor` on the phone CPU, chunked and run off the main thread, with a latency budget per photo (UNVERIFIED: tens of ms on a mid-range phone).
3. Output storage: boxes are photo-derived. They live in `canvas_run_item.outputJson`, so the frame Forget cascade covers them. In live use they would go to the interpreter's `canvas_live_trace`, which is cascaded the same way.
4. A glasses-side detector is out of scope: compute and battery on the glasses are unknown.

Tier (a) works online today. Offline it waits for the VLM (2.4). Tier (b) is offline from day one.

### 2.4 Offline VLM path: status and blockers

The sources for this section:
- `2026-11-20-offline-vision-model-exploration.md`;
- `2026-11-20-offline-vlm-bakeoff.md`;
- ROADMAP 1.4.5.

**Status: not started on the phone. The bake-off is INCONCLUSIVE.**
- Qwen3-VL-2B Q4_K_M + mmproj Q8_0 (1.55 GB) was 3/4 correct on N=4.
  - On the Mac it took 17.5 s per call with the weights resident, and 550-930 s once they were paged out (the machine was thrashing).
- SmolVLM2-2.2B and Gemma 4 E2B gave no data.
- Phone estimates (UNVERIFIED): 15-35 s per call on today's baseline armv8-a build, 5-10 s with an `armv8.2-a+dotprod`(+`i8mm`) variant.

What blocks it, in order:
1. **The bake-off must be re-run on a quiet Mac.** Disk is at 97% today, which caused the paging.
2. **Build:** `LLAMA_BUILD_MTMD ON`, link `mtmd`, plus a second JNI library `llama_jni_v82` (dotprod), picked by `getauxval(AT_HWCAP)`.
3. **New JNI functions:**
   - `nativeLoadVision`;
   - `nativeScoreLabels` (logits at label tokens);
   - `nativeGenerateImage`.
   The existing five functions stay untouched.
4. **A device benchmark on the user's phone.** This needs the phone, so it cannot run on the emulator: x86 emulator timings mean nothing.
5. **Download consent:** about 1.6 GB, pinned SHA-256, Wi-Fi only. Storage is tight on the phone too (UNVERIFIED).
6. **ROADMAP order:** 1.4.5 starts only after the embedding models (1.4.4 SigLIP 2). That is about 8.5 agent-days plus a device session.

**Use on the canvas.**
- At 15-35 s per call, the offline VLM is usable for stored-window Test runs. Fifty photos take 12-30 min, so the estimate must show the time.
- It is not usable for a live tap.
- The live-tap estimate refuses `model.vision backend=offline` above `maxLatencyMs`.

---

## 3. Dependencies and ordering

```
                 install freeze 26 Nov ── train16 install ── Sikkim 1-6 Dec ── (window 7-19 Dec) ── freeze ~20 Dec ── Sri Lanka 23-31 Dec ── Jan
Room v18 slot:   card history (place_card_log) ┐
                 Moment location (optional)    ├─ CONTESTED: all three docs say "v18". One combined MIGRATION_17_18 or v18 + v19 (ruling R5)
                 retention v3 (if schema)      ┘
map zones P1-P3 ─────────── zone fence = privacy gate for picker/tap (read only; no dependency on P2/P3)
1.4.4 SigLIP 2 ──► embed.match, scene tier (b) ──► 1.4.5 offline VLM ──► offline model.vision / classify / tier (a) offline
v2.1 P0 ─► P2 ─► P1 ─► P3 ─► P4 (glasses)
                          └─► v2.2 A (resolver roles, model.text/vision/classify/route online, rule.*) ─► v2.2 B (offline backends, after 1.4.5)
                                                                                                       └─► v2.3 tier (b) detector
```

**Nothing of v2.1/v2.2 should land before Sikkim.** The install freeze is 26 Nov, and train16 is staged but not installed.

During Sikkim, the user could make 1-2 Developer **Collect** sessions at one site. Each is a trace plus frames: the first real E2E fixture. This needs rulings R4 and R9 and costs the user nothing else.

**Between trips (7-19 Dec)**, in parallel worktrees (Sonnet), with one review pass at the end:
- v2.1 P0, P2, P1 and P3. All of these run on the emulator and JVM. None needs a Room change.
- v2.2 slice A, online only, on the emulator with the user's online endpoint.
  - Builds offline text `model.text` on the existing llama.cpp text JNI.
  - Its test path is a JVM fake plus the emulator. The arm64 speed check needs the phone.
- The Room v18 owner (card history / Moment location / retention v3) goes in a separate worktree. Canvas stays out of the migration (Option A in 1.8).

**Needs the glasses (bundle into one device session before the ~20 Dec freeze):**
- v2.1 P4 live tap: real frames, a zone entry, a privacy flip, screen-off.
- One E2E run over a Sikkim collect trace on the phone.
- If P4 slips, it moves to January. P0-P3 still ship and are useful for Sri Lanka review on the phone.

**January (after Sri Lanka):**
- 1.4.4 SigLIP 2 eval;
- 1.4.5 offline VLM (bake-off re-run first);
- v2.2 slice B;
- v2.3 tier (b);
- the release signing key.

**Version numbering.** ROADMAP's v2.0 is the Nexus plugin "stable" row (P2, may move to v3). The canvas v2.1+ rows do not depend on it. Ruling R10: keep canvas on its own line, or renumber.

---

## 4. Risks, privacy review points, rulings

**Risks:**
- **Runner refactor (P4).** Splitting `CanvasRunner`'s loop touches the fenced commit, the leases and the lineage code that took many review rounds. Mitigation:
  - a pure move first, with the full existing test suite green;
  - then the tap is added as a new caller.
- **Join tolerance.** Clock skew between the glasses and the phone exists (`TraceClockBackfill`, `TracePhoneClock`). Mitigation:
  - report the matched/unmatched counts;
  - never hide a miss;
  - tune the tolerance on a real collect session.
- **Counterfactual honesty.** A draft that keeps more cannot be shown on photos that do not exist. The UI must say this in plain words, never guess.
- **Probe volume.** Per-second series over a long trace are bounded (600 points per node), but `resultJson` size needs a cap (UNVERIFIED; propose 256 KB) and a test.
- **Provider determinism.** SEEDED online is best-effort. Only REPLAYED is exact.
- **Offline speed.** The VLM may never meet 8 s. Then offline vision on the canvas stays batch-only, and tier (b) plus SigLIP 2 carry the offline story.
- **Disk.** The Mac is at 97%. The VLM bake-off and Gradle builds need space first.

**Privacy review points (for the Codex/Opus review of each phase):**
1. No path builds an image request without a `confirmToken` that names the count and the endpoint label.
2. `OFFLINE_ONLY` refusals happen at estimate and again at call time (two layers).
3. Live tap ends on a privacy flip, a zone, Delete all or a Forget.
4. Every new stored value is photo-derived and keyed so the frame Forget cascade reaches it, or is trace-derived and covered by the trace tombstone.
5. Logs are count-only.
6. Detection never outputs identity, and the validator enforces it.
7. No real data in git fixtures.

**Rulings needed from the user:**
- **R1.** Test runs may send photos online after the per-run confirmation (the same as today's runs)? Should live tap default to 0 photos sent, that is offline only (proposed: yes)?
- **R2.** Should Test-run and tap photo uploads count against the data saver daily caps? Proposed: yes, as their own counter. `EXPERIMENT` stays off the live call budget.
- **R3.** Retention of test results: keep the `KEEP_RUNS=20` prune for all runs, or give taps their own lower cap (proposed: taps count as runs)?
- **R4.** May per-second glasses-lane probes from Developer Collect traces be shown, given that the zone status of those seconds is UNKNOWN today? Proposed: show them only for seconds after the session started outside every zone; else counts only.
- **R5.** Room: Option A (no canvas schema change) is the proposal. Separately, the contested v18 slot (card history vs Moment location vs retention v3) needs one owner and one migration.
- **R6.** Detection classes: is a people **count** acceptable in public places (Sri Lanka)? Are person boxes OK to keep in run rows until Forget or prune?
- **R7.** Tier (b) dependency: MediaPipe Tasks (APK + several MB) and the model licence check.
- **R8.** Cost display: tokens and bytes only, or add a user-entered price per endpoint?
- **R9.** During trips, is a Developer Collect session (trace plus frames) acceptable at one Sikkim site, to build the first real E2E fixture (kept on the phone, never in git)?
- **R10.** Canvas version line vs the Nexus v2.0 row: keep them separate, or renumber?

**Unknowns, stated:**
- whether `canvas_replay.replayClass` and the replay list accept a new class unchanged;
- whether a JS test runner exists for `assets/canvas`;
- `ModelRequest` seed support per provider API version;
- real MediaPipe latency and APK size;
- all offline VLM phone timings;
- the right KEEP/frame join tolerance.
