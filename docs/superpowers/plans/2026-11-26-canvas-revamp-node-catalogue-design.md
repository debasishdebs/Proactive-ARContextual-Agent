# Canvas revamp (1.9.x): node catalogue for embeddings, vision, places, map, location, Scenes and the rest (design, 2026-11-26)

Designer: Opus. Implementer: Sonnet, in the work packages of section 8. Reviewer: Codex sol at medium effort, or Opus at low effort when the Codex quota is out.
Branch `feat/v101-ask-eval`, `android-companion/`. This is a design only. It changes no source, needs no install and needs no phone.

It updates, and does not replace, the canvas docs it builds on:
- `2026-11-24-canvas-v2-1-live-e2e-and-v2-2-model-nodes-design.md` (Test run, model nodes);
- `2026-11-26-canvas-v2-3-detection-nodes-design.md`, `-v2-4-nl-tools-design.md`, `-v2-5-plugin-skill-nodes-design.md`;
- `handoffs/decisions-canvas-v2-1-v2-2-2026-11-26.md` (what is built).

Labels: VERIFIED means read in the code or docs on this branch at tip `1a0a624b`. UNVERIFIED means inferred or estimated.

---

## 1. Goals, and what changed since the canvas docs were written

**Goal.** Every pipeline the app runs today, or has designed, can be looked at, dry-run and recombined on the canvas with typed ports, and no new node can move a photo, a vector or a location to a place it may not go. The canvas stays a dry-run and experiment tool in 1.9.x. Live behaviour stays in the code that owns it today.

**Built or designed since the 24 Nov canvas doc (VERIFIED in the docs and code named):**

| New capability | Where | State |
|---|---|---|
| Image embeddings in two spaces: Nomic vision v1.5 and SigLIP 2 base p16-224 | `SigLipBundle`, `SiglipShadowCompare`, `PinReferenceVectors`, `CandidateScorer`, `PlaceShadow` | Shadow only, flags `placeShadowScore` + `placeShadowSiglip`, sideloaded files |
| Reference variants single / centroid / medoid, calibration | `MatchCalibration` (commit `1a0a624b`), `MatchThresholds` (VERSION 0, unset), `handoffs/calibration-1.4.4-result-2026-11-26.md` | Thresholds NOT SET. SigLIP 2 centroid best (F1 69.0%, top-1 30/40); no clean separation in any row |
| Pack Moments (pack place as a text-only match candidate) | `MomentCandidate`, `MomentCandidates`, `PackMomentStats` | Flag `packMoments`, shadow, text only |
| Offline VLM plan (Qwen3-VL-2B, SmolVLM2, Gemma 4 E2B via llama.cpp `mtmd`) | `2026-11-20-offline-vision-model-exploration.md` | Not started (1.10.0); bake-off inconclusive |
| Model resolver, Automatic / Offline only | `ModelResolver`, `BackendDecision`, `ModelPrivacyStore`; roles `CANVAS_TEXT/VISION/EMBED` declared, no factory | Built (1.4.3) |
| Map zones P1-P3: seed areas, cells (`PlacePack.cellStatuses`), density layer, offline notice | `2026-11-25-map-zones-p2-p3.md`, v18 WP7 | Flags `mapZonesV2`, `mapZonesP23`; device test owed |
| Moment location (context.db v18 `moment_location`), recorder over held fixes, GPS backfill import, validated-network wake | `MomentLocationRecorder`, `MomentLocationImport`, `NetworkWake`; v18 build spec | Built, not installed (1.6.0) |
| Developer test place and image import | `DevTestImport` (commit `87dc5a3a`) | Built (1.7.0) |
| Retention P2 and its read-only preview | `RetentionPreview`, flag `retentionP2` | Built (1.8.0) |
| Data saver counters and caps | `NetworkCostMonitor`, `DataSaverStore`, `DataSaverWorkGate` | Built |

**What this means for the canvas.** The v2.2 doc listed `embed.match` as one row "that rides 1.4.4". It is now a family: two spaces, three reference kinds, bands from a calibration, a place candidate step that needs a location, and a vision step that needs the resolver. None of it fits today's port types without leaking a vector or a coordinate into a run row.

---

## 2. The current node and connection model (VERIFIED), and the gaps

`services/ai/.../canvas/NodeTypes.kt` and `ModelNodes.kt`. A node has a **set of input port types and one output port type**. An edge is valid when `src.output in dst.inputs` (`PipelineValidator.kt:69`). An ASYNC graph has **exactly one input root** (`:75-83`). Branches are named outputs of the same port type. `mixedIn` (only `policy.capture_rate`) lets one node take two port types.

| Family (palette) | Node types today | Inputs -> output |
|---|---|---|
| Source | `input.photos`, `input.moments`, `input.scenes`, `input.utterance`, `trigger.summarizer_pass`, `trigger.moment/scene/signal/schedule`, `imu.head_motion`, `imu.settle`, `hub.context`, `src.watch_motion`, `signal.in` | - -> PHOTOS, MOMENTS, SCENES, TEXT, TRIGGER, MOTION_EVENT, CONTEXT, WATCH |
| Process | `ai.vision_extract`, `ai.critic`, `ai.prompt`, `proc.summary`, `proc.motion_model`, `embed.text` (descriptive in flows), `rag.window`, `rag.condense`, `rag.retrieve`, `rag.order`, `py.fn`; v2.2: `model.classify` | PHOTOS/JSON/... -> EXTRACTION, JSON, SUMMARY, NUMBER, VECTOR, TEXT, RAG |
| Aggregate | `agg.window` | SCENES/MOMENTS/WATCH -> NUMBER |
| Condition | `gate.local_critic`, `gate.compare`, `route.switch`, `gate.threshold`, `gate.novelty`, `gate.rate_cap`, `expr.guard`, `expr.value`, `gate.nudge`; v2.2: `model.route`, `rule.table/all/any/not/debounce/rate` | branches over the same port |
| Outcome | `memory.write` (preview), `out.deliver` (caller/phone/glasses/both), `out.set_frequency_preview`, `policy.capture_rate`, `capture.image`, `signal.out` | -> PREVIEW, PHOTOS, TRIGGER, SIGNAL |

Port types: `PHOTOS, EXTRACTION, JSON, PREVIEW, IMU, MOTION_EVENT, CONTEXT, TRIGGER, VECTOR, TEXT, SIGNAL, MOMENTS, SCENES, SUMMARY, NUMBER, WATCH, RAG`. Executor kinds: `BUILTIN, EXPR, TABLE, PROMPT, EMBED, TOOL, EVENT, SIGNAL, PYTHON, DRAFT`. Domains: `WATCH, RT_GLASSES, RT_PHONE, ASYNC, REPLAY_ONLY`. Presets (`DefaultPipelines`): `live-extract`, `intent-draft`, `default-capture`, `starter-capture-extract`, `starter-moment-summary`, `starter-watch-motion`, `default-query`, `dated-query`, `default-nudge`.

**Gaps:**
1. **No privacy on ports.** A port says what a value is, not where it may go. VECTOR carries no space, so a Nomic and a SigLIP vector would connect.
2. **No second input.** `embed.match` needs a query and a reference set; `place.candidates` needs the item and a fix. One unnamed input set and one root cannot express "this item, against those references".
3. **No location, zone, candidate or match kinds.** And the v18 source rule (`MomentLocationSourceRulesTest`) forbids `moment_location` anywhere in `CanvasApi`, `services/ai`, prompt builders, `PayloadSources`, Ask and export. Any geo node must live on the phone side of that wall.
4. **No read nodes** for Scenes clusters, card history, seed areas, zones, retention or the data saver: only the three item roots.
5. **No resolver path** for vision or embeddings on the canvas (roles declared, no factory). Parked: `model.text`, `model.vision`, `embed.match`, `rule.when`.
6. **No card sink.** Place cards and glasses cards (left/centre/right) are not reachable; `out.deliver` covers Ask answers and nudges only.
7. **Flow shapes are closed.** `FlowPlan` allows exactly utterance -> [embed.text] -> rag.retrieve -> ai.prompt -> out.deliver, and trigger -> rag.retrieve -> ai.prompt -> gate.nudge -> out.deliver. Nothing new can sit in a live flow without a `FlowPlan` change.

---

## 3. New node catalogue

**Common rules for every new node:**
- **Flag:** Developer flag `canvasCatalogue`, default OFF, also OFF when prefs cannot be read; it needs Developer mode. OFF means "unknown type", as `canvasModelNodes` works (`NodeTypes.modelNodesEnabled`). Nodes that need a model file or a space also need that feature's own flag (named per row).
- **Runs on the phone** unless the row says otherwise. No new node runs on the glasses: the glasses run only the compiled capture plan. No new node runs on the watch. "Helper" means the laptop helper (`py.fn` style).
- **Liveable = false in 1.9.x.** They run in experiment runs and v2.1 Test runs. Live Place Guide stays `PlaceSampler`, live Ask stays `AnswerService` on `RagCore`, live nudges stay `NudgeEngine`. Section 9 asks about later live use.
- **Model nodes** take the v2.2 params `backend: auto | online | offline`, `maxLatencyMs`, `onUnavailable: error | skip | fallback_text`, intersected with Model privacy (`offline_only`, `offline_only_no_local_model` at estimate and again at call time).
- **Stored output** is `canvas_run_item.outputJson` only (no new table, no Room change). Port kinds marked LOCAL_ONLY or PHOTO_DERIVED are stored **reduced** (section 4.2).

Port kinds in **bold** are the new ones of section 4.

### 3.1 Embedding and matching

| Id | Inputs -> output | Config | Online / offline | Flag | Wraps |
|---|---|---|---|---|---|
| `embed.image` | PHOTOS -> **EMBEDDING** | `space: nomic \| siglip2`; `reuse: stored_first \| always_embed` | Always local ONNX; Offline only changes nothing. `siglip2` without the sideloaded vision tower gives `space_missing` | + `placeShadowSiglip` for siglip2 | `frame_vector` (`FrameVectorDao.forFrames`, Nomic 768), `OnnxImageEmbedder`/`NomicBundle`, `SigLipBundle` (one image per run, int8 batch rule) |
| `embed.text` v2 | TEXT -> **EMBEDDING** | `space: nomic \| siglip2`; `role: query \| anchor` | Local: Nomic text via llama.cpp `embedQueryFull` (768, truncated to image dims, L2); SigLIP text tower (283 MB, optional) | same | `Embedders`, `PhotoRecall` text-to-image math, SigLIP text session (1.4.4 remaining work). The schema-2 `embed.text` (TEXT -> VECTOR, flows) stays unchanged; v2 exists only in schema-3 graphs (S41 resolve rule) |
| `embed.refset` | side inputs (PHOTOS, MOMENTS, **CANDIDATES**, SCENES) -> **REFSET** | `kind: single \| centroid \| medoid`; `from: pin_moment \| pack_text \| cluster \| labelled`; `leaveOneOut` (calibration only) | Local | `canvasCatalogue` | `PinReferenceVectors`, `MomentCandidate` (PIN, PACK), `SceneRules.centroid`/`pickReps` medoid, `MatchCalibration` centroid/medoid |
| `embed.match` | **EMBEDDING** (item) + **REFSET** (side `ref`) -> **MATCH**, branches `accept`, `borderline`, `reject` | `thresholds: calibrated \| custom` (custom only in a Test run); `margin` (default 0, owner question 4 of 1.4.4); `channel: image \| text` | Local | `canvasCatalogue` | `CandidateScorer`, `MatchThresholds.band()`. While `VERSION = 0` every item is `borderline` by construction |
| `embed.search` | TEXT -> PHOTOS (hits) | `topK <= 24`, `space` | Local; `NO_IMAGE_SPACE` gives caption hits only | `canvasCatalogue` | `PhotoRecall` (brute force, 50,000 vectors or 2 s cap, separate image and caption lists) |
| `calib.report` | **MATCH** (labelled items) -> **COUNTS** | per space and reference kind | Local | `canvasCatalogue` | `MatchCalibration` (p10/p50/p90, best-F1 t, `acceptAtPrecision98`, `rejectBelow`) |

**Bands.** `MatchThresholds` is the only source of live thresholds. The calibration of 25/26 Nov proposed, and did NOT apply (milli = cosine x 1000):

| Space, reference | accept (no model) | reject below | between |
|---|---|---|---|
| SigLIP 2, single | 917 | 814 | borderline |
| SigLIP 2, centroid | 959 | 879 | borderline |
| Nomic, single / centroid | 946 / 954 | 846 / 885 | borderline |

`thresholds: custom` lets a Test run try these numbers side by side. It never writes `MatchThresholds`: a threshold change stays a code constant plus a `VERSION` bump, made by a human. Medoid is offered because it is cheap and honest to show, but the calibration found it no better than single.

### 3.2 Vision (resolver)

| Id | Inputs -> output | Config | Online / offline | Flag | Wraps |
|---|---|---|---|---|---|
| `vision.describe` | PHOTOS -> JSON (`caption`, `facts[]`) | `preset: describe \| scene_summary \| list_objects`; at most 3 photos per call | Online: resolver `CANVAS_VISION` -> `ModelRouter` (confirmToken, data saver, liveness). Offline: the 1.10 VLM via `nativeGenerateImage`; until then `offline_only_no_local_model` | `canvasCatalogue` | Describe on demand (VISION_EXTRACT, `ExtractSave` only through `CanvasRunSave`), `CLUSTER_SUMMARY` prompt for `scene_summary`. v2.3 `IdentityGuard` on every output. Replaces the parked `model.vision` (question 5) |
| `vision.compare` | PHOTOS + **CANDIDATES** (side `cand`) -> **MATCH** (choice or NONE, confidence) | shortlist: online <= 16, offline <= 3; `confidenceFloor` online 0.50, offline 0.70 plus top-2 embedding agreement | Online: `PLACE_MATCH` contract (`PlaceMatchOutput`), names and kinds only. Offline (1.10): `nativeScoreImage` label logits, softmax over allowed labels | `canvasCatalogue` | `PromptKey.PLACE_MATCH`, `RoleContracts`, `ResolverRole.PLACE_CONFIRM` thresholds of the resolver doc 2.6 |

At an estimated 15-35 s per offline call (UNVERIFIED), offline vision is batch-only: the live-tap estimate refuses it above `maxLatencyMs`, and a stored-window estimate shows the time.

### 3.3 Places of interest

| Id | Inputs -> output | Config | Online / offline | Flag | Wraps |
|---|---|---|---|---|---|
| `place.candidates` | **GEOFIX** (side `fix`) per item -> **CANDIDATES** | `radiusM` (default 300, max 1,000), `maxPack` 8, `pinSlots` 3, `cut` 16 | **Cached cells only. It never fetches**, in either mode, so Offline only and the data saver are not reached; an unfetched cell gives `cell_not_ready` | `canvasCatalogue` | `PlacePack.candidates(..., networkUp = false)`, `Geo`, `UserPinStore`, `cutKeepingPins`, `MomentCandidates.of` (pack Moments when `packMoments` is on) |
| `place.match` (composite) | PHOTOS -> **CARD**, branches `card`, `nearby`, `none` | inner params of the nodes below; `rankSkip: off \| on` (on = accept skips the model, Test run only) | per inner node | `canvasCatalogue` | the 1.4.4 pipeline: `geo.moment` -> `place.candidates` -> `embed.image` + `embed.refset` -> `embed.match` -> borderline: `vision.compare` -> card. Reuses `PlaceShadow`/`CandidateScorer`/`PlaceCards`; the pin-first and ambiguity rules come from `PlaceSampler` through extracted pure functions, never a copy |
| `place.card` (sink) | **CARD** -> PREVIEW | `side: setting \| left \| centre \| right` | none | `canvasCatalogue` | `PlaceCards` text, `PlaceGuideSettings.cardSide`. Shows "would show on glasses (centre)"; never delivers |
| `place.history` | (root-less read, side input) -> JSON | window, limit 50 | none | + `placeCardHistory` | `PlaceCardHistory.recent` (context.db `place_card_log`): what live Place Guide actually showed, for an agreement report |

### 3.4 Map: seed areas and zones (read only; the canvas never edits a zone)

| Id | Inputs -> output | Config | Behaviour | Flag | Wraps |
|---|---|---|---|---|---|
| `zone.check` | **GEOFIX** or item time -> **ZONE_STATE**, branches `clear`, `blocked` | none | `CLEAR` only when the guard says so; `WARN`, `INSIDE`, "not judged yet", a missing row or unreadable zones are `blocked` (fail closed) | `canvasCatalogue` | `ZoneEvaluator.blocksPoint`, `ZoneController` state, `placeZoneBlocked` predicate, `PauseDao.countCovering` |
| `pause.state` | item time -> **ZONE_STATE** | none | the Pause switch and recorded pause intervals; an unreadable interval is `blocked` | `canvasCatalogue` | `PauseDao` |
| `seed.area` | (side read) -> JSON | area or visible box | seed areas and per-cell status `ready \| partial \| not_fetched`; above 4,000 cells it says so and lists nothing | + `mapZonesP23` | `AreaEntity`, `PlacePack.cellStatuses(box)` |
| `seed.plan` (sink) | JSON -> PREVIEW | area | "would fetch N cells, about X KB, from Overpass and Wikipedia/Wikidata"; under Offline only "refused: offline_only"; under a roaming data saver "would queue". **It never fetches** | same | `PlaceCellSource`, `OverpassHttpSource`/`OverpassMirrorSource`, `WikidataHttpSource`, the partial-cell backoff (5 min to 1 h) |

The zone guard stays the only writer of zones (`ZoneEditor.upsert` has one call site, `MapZonesViewModel.savePause`; a guard test pins it). No catalogue node has a write effect on zones, seed areas or pins, and the validator refuses any node type that declares one. Real seeding stays in the Map zones screen.

### 3.5 Location (held fixes only)

| Id | Inputs -> output | Behaviour | Flag | Wraps |
|---|---|---|---|---|
| `geo.moment` | MOMENTS or PHOTOS item -> **GEOFIX** | the `moment_location` row of the item's second; `source` ZONE_STREAM / PLACE_GUIDE / LAST_KNOWN / MEDIA_BACKFILL, `accuracyM`, `fixAgeMs`, `speedClass`, `method` | + `momentLocation` setting ON | `MomentLocationDao.of` through one new reader file (section 5) |
| `geo.fix` | item time (live tap only) -> **GEOFIX** | the held fix nearest the capture: zone stream, then Place Guide, then system last-known (the v18 order), fix within 60 s of the capture, accuracy <= 100 m | `canvasCatalogue` | `ZoneController.lastFix()`, `PlaceSamplers.flight.good`, `LocationSource.lastKnown`. **Never** `PlaceFixProvider.fresh()` (its fallback is a one-shot request) |
| `geo.near` | **GEOFIX** + target (pin, pack place, seed area) -> NUMBER bucket, branches `near`, `far` | buckets `<50, <100, <300, >=300 m` only (the `distB` precedent of the `place attempt` log) | `canvasCatalogue` | `Geo` distance, edge gap |

What every geo consumer does in the four cases:

| Case | `geo.moment` / `geo.fix` | `place.candidates`, `geo.near`, `zone.check` |
|---|---|---|
| No row, no fix | `fix: none` | `skip` with `no_fix` (counted); never a guess, never a request |
| Stale (> 60 s) or inaccurate (> 100 m), or backfill with `fixAgeMs` beyond its class window | `fix: stale` with the bucket | `skip` with `stale_fix`. A Test-run param `allowBackfill` (default ON) admits `MEDIA_BACKFILL` rows; its accuracy is shown |
| Point inside a pause zone, or under a pause, judged against **today's** zones | dropped: `fix: zone_blocked`, value discarded in the executor | never sees it |
| Zones unreadable | `zone_blocked` | never sees it |

### 3.6 Moments, Scenes and clusters

| Id | Inputs -> output | Behaviour | Wraps |
|---|---|---|---|
| `moment.read` | (side read, or the `input.moments` root) -> MOMENTS | Moments of a window with parts (FRAME, MARK), sensor summary, and a `hasLocation` boolean only | `MomentDao`, `MomentResolver` (outside `contentHash`) |
| `scene.read` | (side or `input.scenes`) -> SCENES | Scenes of a window, derived summary state (fresh, stale, none) | `SceneBuilder` rows, `DerivePorts`, `DerivedStaleDao` |
| `scene.cluster` | PHOTOS or SCENES -> SCENES (with `reps[]`) | clusters (`frame_cluster`: state, member count, close reason) and representatives in the `pickReps` order: medoid, most different, sharpest (<= 3); cluster summary memory text when present | `SceneRules`, `SceneIndexer` rows, `ClusterContract` |
| `scene.route` | PHOTOS -> JSON, branches `fast_path`, `near_dup`, `cluster` | read-only preview of `SceneRules.route` (salience, dedupe 0.93/10 min, drift) with a `SceneTuning` override; writes nothing, claims nothing | `SceneRules`, `SceneTuning` |
| `derive.preview` | MOMENTS or SCENES -> SUMMARY | the Moment/Scene summary derive step as a dry run | `DerivePass` |
| `scene.cut` | MOMENTS -> SCENES | as designed in v2.5 part B (not repeated here) | `SceneCutter` |

The "new Scenes list" screen named in the request was NOT found on this branch at the tip (UNVERIFIED; only `ui/preview/ScenePreview.kt`). The nodes read the same rows, so they do not depend on that screen.

### 3.7 Retrieval (Ask), recall, nudges, delivery

| Id | Status | Notes |
|---|---|---|
| `rag.window`, `rag.condense`, `rag.retrieve`, `rag.order`, `ai.prompt` (ANSWER / NUDGE), `gate.nudge`, `out.deliver` | existing, unchanged | The Forget fence, photo gate and bounds stay inside `RagCore` and cannot be edited out. Safety questions and the last-seen state stay inside the ANSWER prompt and `AnswerService` |
| `embed.search` | new (3.1) | photo search for experiments; Ask keeps its own 2 image hits |
| `vision.describe` | new (3.2) | describe on demand as a dry run |
| `gate.datasaver` | new, Condition | **advisory** pre-check: reads `NetworkCostMonitor` and `DataSaverStore` counters for a kind (`PHOTO`, `BULK_OTHER`, `SMALL`), branches `allow`, `queue`, `drop`. Writes no counter. `ModelRouter.call` stays the enforcement point |
| `privacy.mode` | new, Source (side read) | `auto` or `offline_only` as a branch, so a pipeline can show both paths. Unreadable = `offline_only` |
| `gate.worn` | new, Condition | branches `worn`, `not_worn`, `unknown` from the hub's worn estimate; for the not-worn fallback (deliver to phone). Exact seam in `phone/.../hub/` UNVERIFIED (`AutoStopController`, `BackgroundPolicy` mention it) |
| `trigger.mark` | new, Source, **not in 1.9.x by default** (question 10) | a Nudge root on a mark |

### 3.8 Retention, Forget, export (read only)

| Id | Behaviour | Wraps |
|---|---|---|
| `retention.preview` | counts by kind for the next 24 h and 7 days (photos, skipped photos, image vectors, details leaving Quick recall), and with `retentionP2` ON how many card/mark/pin photos are kept. Unreadable = nothing | `RetentionPreview`, `Retention.plan` |

No Forget node and no export node. Forget stays one flow with one confirmation dialog (`ForgetSource`, `PolicyPlan`). Export stays behind `DataOpGate`. The canvas only **honours** them: every node with stored output names its Forget family, and the validator refuses a node type without one (the same idea as the `ForgetPolicy` table test).

---

## 4. Typed ports and connection rules

### 4.1 New port kinds

| Kind | Carries (in the executor) | Privacy label | Stored in run rows as |
|---|---|---|---|
| EMBEDDING | `space` (`nomic` / `siglip2`), `modality`, `dims`, the vector | PHOTO_DERIVED | `{space, dims, norm}` only; never the vector |
| REFSET | per candidate: id, kind (single/centroid/medoid), vector count, vectors | PHOTO_DERIVED | ids and counts |
| CANDIDATES | per candidate: id (`wd:`, `osm:`, `user:<uuid>`), name, kind, distance bucket, origin PIN/PACK | PLACE_DERIVED | ids, kinds, buckets; names only in the phone-local inspector view |
| MATCH | top-1 id, `band` (`accept` / `borderline` / `reject`), milli score, margin, `thrV` | PLACE_DERIVED | as is (scores are milli integers, like `place shadow2`) |
| GEOFIX | opaque handle: lat, lon held in a `LocalOnly<T>` value; plus source, age bucket, accuracy bucket | LOCAL_ONLY | `{fix: held \| none \| stale \| zone_blocked, source, ageB, accB}`; **never a coordinate** |
| ZONE_STATE | `CLEAR` / `BLOCKED` with a fixed reason token | LOCAL_ONLY | the token |
| CARD | title, kind, source ids, confidence, side | PERSONAL | as is (the card text is what the glasses would show) |
| COUNTS | integers and fixed labels | PUBLIC | as is |

Existing kinds get labels too: PHOTOS = PERSONAL (photo bytes stay behind the router's image guard), TEXT/JSON/EXTRACTION/SUMMARY/RAG = PERSONAL, NUMBER = inherits, VECTOR = PHOTO_DERIVED when from an image.

**Named side inputs.** A node may declare named side inputs (`ref`, `cand`, `fix`, `target`) next to its item input. A side input is fed by a read node that has no root role. The one-root rule stays for the item flow. This generalises `mixedIn` and is WP0.

### 4.2 Connection rules

1. `src.output` must be in `dst.inputs`, as today, and for EMBEDDING and REFSET the `space` must match (a Nomic query never meets a SigLIP reference; refused at validate).
2. **Labels flow downstream.** A node's output label is the highest of its input labels, unless the node is a declared reducer (`place.candidates` turns LOCAL_ONLY into PLACE_DERIVED by dropping the coordinates; `calib.report` and `retention.preview` give PUBLIC COUNTS).
3. What may reach which consumer:

| Label | Remote model (online backend, PROMPT executor) | `rag.*` / Ask, `memory.write` | `skill.call` (v2.5), `tool.draft` (v2.4) | Export | Logs | Phone inspector |
|---|---|---|---|---|---|---|
| PUBLIC | yes | yes | yes (numbers, enums) | n/a | counts only | yes |
| PERSONAL | after the confirmToken, not under Offline only (today's rule) | yes | no | via memories only | never | yes |
| PLACE_DERIVED | names and kinds only, and only into `vision.compare` (today's `PLACE_MATCH`); not under Offline only | **no** | no | no | ids by the 1.4.1 rule, bands, counts | yes |
| PHOTO_DERIVED | **never** | never | never | never | never | `{space, dims}` only |
| LOCAL_ONLY | **never** | **never** | never | never | never | the reduced form only |

4. **Three layers**, as the v2.2 privacy rule: the validator refuses the edge; the estimate refuses the run (`local_only_to_remote`); and at call time `LocalOnly<T>` has no serializer and no `toString` beyond a fixed token, so no `ModelRequest`, `RagQuery`, log line or JSON writer can be built from it. A test scans the new executors for any path from a GEOFIX field to a request builder.
5. **Location stays local only, per the v18 source rule.** No coordinate reaches a remote model, Ask, an export, a log, a backup, `CanvasApi` or the browser. The canvas page is served to a laptop browser over LAN, so "phone inspector" means the reduced forms above.

---

## 5. Privacy and threat model per family

Threats: (1) photos or photo-derived vectors leaving the phone, (2) a location leaving or being inferred from an output, (3) derived values outliving a Forget, (4) work inside a pause zone, (5) identity creep, (6) a dry run changing live state.

| Family | Forget coverage | Offline only | Zones | Shadow first | Identity |
|---|---|---|---|---|---|
| Embedding / match | Vectors are never stored by the canvas; cached in `ShadowCaches` (both spaces), emptied by every Forget and Delete all. Run rows are frame-keyed: `deleteCanvasOutputsForFrames` | no effect (local) | inputs are frames, none exists from a zone | thresholds unset = all borderline; `rankSkip` Test run only | no face crops, no person vectors; `embed.refset` refuses a `person` detection source |
| Vision | run rows frame-keyed | online refused at estimate and call time; offline needs the 1.10 model | as above | dry run; save only through `CanvasRunSave` | `IdentityGuard` on every output; presets forbid naming people |
| Places | candidates from local cells and pins only; pin Forget unchanged | no fetch at all | `zone.check` before candidates | cards are previews | n/a |
| Map / seed | read only, nothing stored | `seed.plan` says refused | fail closed; never writes | n/a | n/a |
| Location | reads `moment_location` through one allow-listed file `orchestration/canvas/geo/GeoFixReader.kt` (question 1); stores only the reduced form; a user Forget already deletes the rows | local read is allowed; nothing sends | re-checked against today's zones, dropped when blocked | n/a | n/a |
| Moments / Scenes | Moment and Scene Forget cascades; whether run rows keyed `moment:<id>` are reached by a Moment Forget is UNVERIFIED and gets a test in WP6 | local | none exists from a zone | read only | n/a |
| Retention / gates | nothing stored | n/a | n/a | n/a | n/a |

Logs for all new nodes are count-only fixed tokens (node type, outcome, counts, latency buckets, band). `LogContentScanTest` covers the new files.

**Not in scope:** identity recognition of any kind (faces, names, re-identification, tracking a person); NL tools beyond the v2.4 design (`tool.draft` takes TEXT/JSON only, so it cannot take EMBEDDING, GEOFIX or PHOTOS); an agent loop or a model choosing nodes (v3.0); live use of the new nodes; any zone, pin or seed-area write; downloads of models or tiles from the canvas.

---

## 6. Starter pipelines (presets)

All are read-only seeded presets, Test run only, `canvasCatalogue` ON.

**P1. Place card (PoI).**
```
input.photos (dwell frames of a window)
  -> geo.moment --fix--> zone.check --clear--> place.candidates (300 m, pins + pack, cached cells)
  -> embed.image (siglip2, else nomic) + embed.refset (centroid; pin_moment + pack_text) --ref-->
  -> embed.match
       accept     -> place.card (preview)               [only with rankSkip=on]
       borderline -> vision.compare (resolver, shortlist) -> rule.table (confidence >= floor) -> place.card
       reject     -> none
side: place.history (window) -> agreement report: same top-1 as the live card? (counts)
```

**P2. Scene caption.** `input.scenes -> scene.cluster (reps: medoid, most different, sharpest) -> vision.describe (scene_summary) -> memory.write (preview)`. Offline it needs the 1.10 VLM. Online it shows "N photos to <endpoint label>".

**P3. Revisit by cluster centre** (owner idea, 26 Nov). `input.moments (days at a pin) -> scene.cluster -> embed.refset (centroid of cluster medoids) --ref--> embed.match` over the dwell frames of a later visit, side by side with `embed.refset (single)`. Output: COUNTS of top-1 agreement and bands per reference kind. No vector is stored (question 12).

**P4. Calibration.** Labelled set on the phone = Developer test places with imported images (`DevTestImport`), or pins with attached Moments plus `geo.near` frames within the pin radius. `-> embed.image (both spaces) -> embed.refset (single, centroid, medoid; leaveOneOut) -> embed.match -> calib.report`. The report is the `MatchCalibration` table, counts and milli only. It never writes `MatchThresholds`. The host harness `MatchCalibrationRealDataTest` stays the reference.

Existing presets stay: `default-capture`, `starter-capture-extract`, `starter-moment-summary`, `starter-watch-motion`, `default-query`, `dated-query`, `default-nudge`.

---

## 7. How a v2.1 Test run dry-runs these nodes without sending photos

- **Estimate first.** `runs/estimate` (built, `12677f98`) gains per node: backend, space present, `photosLeave {count, endpoint label}`, "would fetch 0 cells", geo source counts (`held / none / stale / zone_blocked`) and the data-saver verdict. The default for photos leaving is **0**: with it, `vision.*` resolves offline or answers `would_call` with no request built.
- **Stored window.** Geo comes only from `moment_location` rows (the fix held at capture), re-checked against today's zones. `geo.fix` is refused in a stored window (`live_only`).
- **Live tap.** `geo.fix` reads the held fix of the moment; the tap stops on zone entry (built in `LiveTapBounds`). The tap driver itself is still parked.
- **Deterministic.** `embed.*`, `place.candidates`, `zone.check`, `scene.*`, `calib.report` are pure over stored data and re-execute in REPLAYED runs. `vision.*` uses `RecordedOutputsExecutor` (input key over type, params, prompt version, model label, upstream output): a hit makes no call.
- **No side effects.** `place.card`, `seed.plan`, `out.deliver` say "would"; `memory.write` is a preview; nothing touches the data saver counters (photo uploads in Test runs as their own counter is v2.1 ruling R2, still open).
- **Inspector.** EMBEDDING shows `space, dims`; GEOFIX shows the reduced form; MATCH shows band and milli score; CANDIDATES show names only in the phone-side panel.

---

## 8. Work packages (agent hours, UNVERIFIED) and order inside 1.9.x

| WP | Content | Hours | Depends on | Gate |
|---|---|---|---|---|
| WP0 | port kinds, labels, label flow in `PipelineValidator`, named side inputs, graph schema 3, `LocalOnly<T>`, `canvasCatalogue` flag, Forget-family check per node type | 5 | v2.2 built | none |
| WP1 | `embed.image`, `embed.text` v2, `embed.refset`, `embed.match`, `calib.report`, EMBED executors | 5 | WP0 | bands real only after **1.4.4 calibration** sets `MatchThresholds` |
| WP2 | `scene.read`, `scene.cluster`, `scene.route`, `moment.read`, `derive.preview`, Moment-Forget cascade test | 4 | WP0 | none |
| WP3 | `zone.check`, `pause.state`, `seed.area`, `seed.plan` | 3 | WP0 | map zones P1 device test (1.8.0) for real data |
| WP4 | `geo.moment`, `geo.fix`, `geo.near`, `GeoFixReader` + allow-list change in `MomentLocationSourceRulesTest`, reduced storage, the four-case table as tests | 4 | WP0, WP3 | v18 installed (1.6.0) for real rows; question 1 |
| WP5 | resolver factories `CANVAS_VISION` (online wraps the router), `vision.describe`, `vision.compare` online, `IdentityGuard` hook | 4 | WP0 | v2.3 WP3 for the guard (or a copy of its denylist first) |
| WP6 | `place.candidates`, `place.match` composite (pure functions extracted from `PlaceSampler`), `place.card`, `place.history` | 6 | WP1, WP3, WP4, WP5 | none |
| WP7 | `gate.datasaver`, `privacy.mode`, `gate.worn`, `retention.preview`, `embed.search` | 3.5 | WP0 | none |
| WP8 | presets P1-P4 with golden tests (synthetic vectors, fixtures; no real data in git) | 3 | WP1-WP6 | P4 real use needs **1.7.0** test place import |
| WP9 | Test run integration: estimate fields, recorded outputs for `vision.*`, inspector forms | 3 | WP0-WP7, v2.1 screens | screens are parked in v2.1 |
| WP10 | offline vision behind the resolver (`nativeScoreImage`, `nativeGenerateImage`) | 2 here | **1.10.0** offline VLM | device |
| WP11 | `skill.call` refusal of new labels (no PLACE_DERIVED, LOCAL_ONLY, PHOTO_DERIVED args) | 1 | WP0 | **Nexus 1.11** bridge for any real call |
| WP12 | device session: SigLIP memory, a stored window over real Moments with locations, a zone re-check | 2 + user | WP1-WP9 | phone |
| Review | one Codex pass (Opus low if out) + one fix round | 2 | all | |
| **Total** | | **~47.5 h** | | |

Recommended order: WP0 -> (WP1, WP2, WP3, WP7 in parallel worktrees) -> WP4 -> WP5 -> WP6 -> WP8 -> WP9 -> review -> WP12. WP10 follows 1.10.0, WP11 follows the 1.11 bridge. A first useful cut (embed + scenes + calibration preset: WP0, WP1, WP2, part of WP8) is about 15 h and needs no device.

---

## 9. Coverage: every existing pipeline or feature, and its canvas node or why not

| Pipeline or feature (ROADMAP row) | Canvas node or preset | Not exposed, why |
|---|---|---|
| Glasses capture plan: IMU, settle, thresholds, novelty, capture rate, rate cap, capture image (0.9.x) | existing glasses-lane nodes, `default-capture` | |
| Traces, replay, sweep, sensor recording (0.9.x, 0.9.7) | Test run source `trace`, existing replay | |
| Keyframe routing: salience, semantic dedupe, clusters (wave 2) | `scene.route` (preview), `scene.cluster` | live routing claims frames (CAS) |
| Photo queue, claims, retry, FAILED_FINAL revive on validated network (1.6.0 WP9) | none | state machine with CAS and leases; a canvas must never claim a frame |
| Extract, critic, local trust, save memory | `ai.vision_extract`, `ai.critic`, `gate.local_critic`, `memory.write` | |
| Cluster summary, scene caption | `scene.cluster` + `vision.describe`, preset P2 | |
| Describe on demand, photo search (wave 3) | `vision.describe`, `embed.search` | |
| Moments, Scenes, summary derive (0.9.3) | `moment.read`, `scene.read`, `derive.preview`, `input.*`, `starter-moment-summary` | |
| Scene cut config (v2.5 B) | `scene.cut` | |
| Ask on RagCore: time window, condense, ordering, photos, multilingual flag (1.1, 1.3) | existing `rag.*`, `default-query`, `dated-query` | safety questions and last-seen state live inside the ANSWER prompt and `AnswerService` |
| Quick recall | counts in `retention.preview`; `rag.retrieve` recent flags | a retention tier, not a step |
| Nudges: triggers, gates, delivery caller/phone/glasses/both (0.9.8) | existing `trigger.*`, `gate.nudge`, `out.deliver`, `default-nudge` | |
| Suggestions loop, proactive replay, shadow eval (`ShadowEval`, Developer route) | none | already read-only Developer routes; a node adds nothing |
| Not-worn fallback | `gate.worn` | |
| Marks and mark confirmations (0.9.6) | `moment.read` (MARK parts); `trigger.mark` later | confirmations are user feedback and always show |
| Voice and chat input | `input.utterance` | |
| Watch motion | `src.watch_motion`, watch run (v2.5 A) | |
| Hub auto-start and AutoStop | none | lifecycle under its own guards (`AutoStartGate`) |
| Place Guide live: pin-first, cluster card, dedupe, ambiguity, call gap (1.4.8) | `place.match` (dry run), preset P1 | live stays `PlaceSampler` |
| Pins and pin-to-Moment (1.4.2) | `place.candidates`, `embed.refset from=pin_moment` | pin edits stay in Settings |
| Place cards on glasses, left/centre/right, show again | `place.card` (preview) | delivery stays live code |
| Card history (v18) | `place.history` | |
| Pack places: Overpass, Wikipedia, Wikidata, cells, backoff (1.1, 1.4.7) | `place.candidates` (cached), `seed.area`, `seed.plan` | fetches stay in Place Guide and the Map screen |
| Pack Moments (1.4.6) | `embed.refset from=pack_text` | |
| Semantic ranking, SigLIP shadow, calibration (1.4.4) | `embed.*`, `calib.report`, presets P1, P3, P4 | |
| Model resolver, Offline only (1.4.3) | `backend` param on every model node, `privacy.mode` | the setting itself |
| Offline VLM (1.10.0) | `vision.*` offline backend | not built |
| Data saver (1.4.x) | `gate.datasaver` (advisory) | enforcement stays in `ModelRouter.call` and `DataSaverWorkGate` |
| Pause zones, Pause switch, map zones P1-P3 (1.4.9) | `zone.check`, `pause.state` | zone edits (zone guard is the only writer) |
| Offline notice on the zones map | none | UI only |
| Moment location, recorder, GPS backfill import (1.6.0) | `geo.moment`, `geo.fix`, `geo.near` | the import route is a Developer step |
| Developer test place, image import (1.7.0) | input to preset P4 and `place.match` fixtures | creating a test place stays in Developer |
| Retention v3 and P2 (1.8.0) | `retention.preview` | expiry itself |
| Forget, multi-select Forget, Delete all (1.2) | none (nodes honour it) | one flow, one confirmation dialog |
| Export | none | `DataOpGate`; no canvas value is exportable |
| Detection (v2.3), NL tools (v2.4), skill nodes (v2.5) | their own docs | |
| Nexus HUD delivery (1.11) | `out.deliver` target later | not built |
| Activity paging, date filter, trip logging, link diagnostics, install helper | none | UI or tooling |

---

## 10. Owner questions (yes/no, recommended default in bold)

1. May one new file, `orchestration/canvas/geo/GeoFixReader.kt`, join the v18 location allow-list, with outputs reduced to `held/none/stale/zone_blocked` plus buckets and never a coordinate in a run row or the canvas API? **Yes.**
2. Presets P1 and P3 default to the **centroid** reference (best in the calibration), while `single` stays selectable for parity with live? **Yes.**
3. The canvas reads `MatchThresholds` only; custom thresholds are allowed in Test runs and never written back? **Yes.**
4. `place.match` stays a dry run in 1.9.x, and live Place Guide stays `PlaceSampler`? **Yes.**
5. `vision.describe` replaces the parked `model.vision` (no separate node)? **Yes.**
6. Under Automatic, may `vision.compare` send the photo plus candidate names and kinds after the confirmToken, exactly as `PLACE_MATCH` does today? **Yes** (Offline only refuses).
7. `seed.plan` is a dry run only, and real seeding stays in the Map zones screen? **Yes.**
8. Stored-window geo re-checks old Moment locations against today's zones and drops those inside (fail closed)? **Yes.**
9. `gate.datasaver` is advisory only, and the router stays the enforcement point? **Yes.**
10. Add `trigger.mark` as a Nudge root in 1.9.x? **No** (later, with a `FlowPlan` change).
11. All new nodes sit behind one Developer flag `canvasCatalogue` (default OFF) and are not liveable in 1.9.x? **Yes.**
12. Revisit-by-cluster-centre computes centroids on the fly, with no stored vector, until a v19 table is agreed? **Yes.**
