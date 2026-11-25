# Canvas v2.5 (plugin skill nodes, watch-run route, scene-building flows): design (2026-11-26)

Designer: Opus. Implementer: Sonnet, in the work packages below. Reviewer: Codex sol at medium effort, or Opus at low effort when the Codex quota is out.
Branch `feat/v101-ask-eval`, `android-companion/`. This is a design only. It changes no source, needs no install and needs no phone.

ROADMAP row **v2.5** (old canvas label; it ships in the **1.9.x** canvas line, see ROADMAP "Versioning"): canvas nodes that call other plugins' skills; watch-run route; scene-building flows. The three parts are independent and ship separately: **A** watch-run route, **B** scene-building flows, **C** skill nodes.

Labels: VERIFIED means read in the code or docs on this branch. UNVERIFIED means inferred or estimated.

---

## 0. What already exists (VERIFIED), and the gap

**Watch run.** `CanvasRunner.estimateWatch` / `startWatch` (`CanvasRunner.kt:428,446`) are built: at most 50 values, a token bound to the values (`WatchMoved`), no model call in a watch chain. The source is wired in `CanvasBackendImpl.kt:183` with the comment "no route or page starts a watch run yet". Watch run rows are deleted on watch switch-off (`CanvasDao.deleteWatchRuns`, hardening B). Known race: a run executing at switch-off may write rows after the delete. Accepted residue GAP-2: no unified watch deletion. Ruling 25 Oct: the route is v2.

**Scenes.** `SceneCutter` is pure and deterministic. `SceneConfig` holds `hashDistanceFraction` 0.25, `minCosine` 0.75, `capMs` 5 min and `activityStates`. Its rules are HOUR_END, ACTIVITY_CHANGE, FRAME_CHANGE and CAP_5_MIN. `SceneBuilder.rebuildWindow` re-cuts whole clock hours in one transaction that re-reads the Moments, so it can never resurrect a forgotten Moment. Scene id = `S` + first Moment id. The cadence is stored (12/24/48/96 a day, default 24). `input.moments` and `input.scenes` nodes exist. Ruling 25 Oct: Scene building flows beyond the stored cadence are v2. **Gap:** `SceneConfig` has only code defaults. Nothing lets the user tune or preview it.

**Nexus skills.** PCA is a skills **provider** only: four action-only operations (`nexus-port/.../Skills.kt`: status, pause_capture, resume_capture, mark_moment), `SkillRouter` in the bridge (12 s host timeout, fixed codes through `SkillCodes.sanitize`). The Skills v1 contract (spike S0-S1 note, 17 Nov): 32 operations, a 64 KiB catalog, 16 KiB results, 15 s per call; `effect` read or action; a `data` vocabulary (`place_names, schedules, itinerary, media_metadata, playback_state, status, text`); output strings need `maxLength` or an `enum`; the wearer approves each operation for each caller; opaque `nexusRef` handles. A caller needs the hub capability "Use skills from other plugins". **PCA as a caller is not built.** The SDK caller surface (SDK section 3.6) is UNVERIFIED for our use. The Nexus line is 1.11.x at priority P2. Rulings of 17 Nov hold: no content skills, PCA stays the default HUD.

## 1. Goal

- **A.** Start a watch run from the canvas.
- **B.** Tune how Scenes are cut, preview the effect on real days, and publish the tuning.
- **C.** Let a pipeline read from other Nexus plugins (agenda, transit, media) as a node, with nothing personal leaving PCA.

## 2. Scope

**In:**
- A: `runs/watch` estimate and start routes, a "Watch window" source on the run page, and the switch-off race closed.
- B: a `scene.cut` node, a preview route, publish of a stored `SceneConfig`, and an optional bounded re-cut.
- C: a `skill.call` node for **read** operations only, a catalog browser in the palette, and one phone-side choke point `SkillCallGate`. It runs in experiment runs and v2.1 Test runs, and in a published pipeline only behind the Developer flag `skillsLive` (default OFF).

**Out:**
- Action skills (writes wait for v3.0 per-operation approval); in a Test run they show "would call".
- PCA content skills (17 Nov ruling).
- A model choosing which skill to call (agent loop, v3.0).
- Skill arguments carrying memory text, place names, coordinates, photos or transcripts.
- A watch deletion fence (GAP-2 stays accepted).
- Model-decided Scene boundaries, Scenes crossing a clock hour, and a change to the Scene id scheme.

## 3. Node and API shape

### 3.1 A: watch-run route

| Route | Body / answer |
|---|---|
| `POST runs/watch/estimate` | `{pipelineId, version?, from, to}` -> `Estimate.Ok` (`kind:"watch"`, `photoCount:0`) or `Refused` |
| `POST runs/watch` | same + `confirmToken` -> `{runId}`; 409 `busy`, 409 `watch_moved`, 404 `no_watch_source` |

The routes are registered in `CanvasApi.templateOf`. `runNewView` gets a "Watch window" source with presets of 15, 30 and 60 min. **Race fix:** `ContextHub.onWatchOff` calls `runner.cancelWatchRuns()` and waits for it under the runner lock, and only then calls `deleteWatchRuns()`. This part is small and may land with v2.1 P0.

### 3.2 B: scene-building flows

- **`scene.cut`** (ASYNC, MOMENTS -> SCENES). Its params are the `SceneConfig` fields, bounded: hash fraction 0.05..0.5, cosine 0.5..0.95, cap 1..30 min, activity subset, plus `useEmbedding`. The executor calls `SceneCutter` with that block: it wraps the cutter and does not rewrite it (ruling S50).
- **`POST scenes/preview {config, from, to}`**: at most 7 days, whole hours. It returns the proposed vs stored Scenes: counts, moved boundaries and counts per rule. It writes nothing.
- **`POST scenes/config {config, confirmToken, recut?:{from,to}}`**: stores the config in `canvas_meta` (key `scene_config_v1`, versioned, as `KEY_PER_DAY`). Future rebuilds use it. An optional bounded re-cut runs through `rebuildWindow`. The confirm card names "N Scenes will be re-cut; their summaries are hidden until rebuilt". `POST scenes/config/reset` restores the code defaults.

### 3.3 C: skill nodes

**`skill.call`** (ASYNC, executor `TOOL`):

| Param | Rule |
|---|---|
| `plugin`, `op` | from the catalog of approved plugins; the op must be `effect: read` |
| `args` | each value is a typed constant, the item's timestamp, a window bound, or an upstream **enum or number** field. Text, PHOTOS, place and coordinate fields are refused by `SkillArgPolicy` |
| `timeoutMs` | at most 12 000 |

- **Output:** `{plugin, op, ok, code, data}`. `data` is validated against the op's output schema from the catalog, and strings are cut at the catalog `maxLength`. It is untrusted data: when it feeds a model node, it goes between delimiters as data, never as instructions.
- **Code:** port `SkillClient` in `nexus-port` (pure JVM): `catalog()` and `call(plugin, op, argsJson)`. The implementation adds one AIDL method on the bridge port service. The bridge does the Nexus call and returns the strict wire answer. The catalog is parsed with Nexus's shared `SkillCatalogParser` (as the spike advised), not a hand-written subset.
- **`SkillCallGate`** (phone, the only caller of `SkillClient`, like `NexusHudSink` for the HUD): it checks the flag, Model privacy, zone status, `SkillArgPolicy` and the size caps, and maps codes through `SkillCodes.sanitize`.
- **Routes:** `GET skills/catalog` returns operation names and schemas only. The estimate shows "N skill calls to <plugin label>; arguments: constants and times only" and binds the catalog hash into the `confirmToken`.

## 4. Data flow

```
A  page -> runs/watch/estimate -> token bound to the ring values -> runs/watch -> CanvasRunner.startWatch -> canvas_run_item (watch:<atMs>)
   watch switch-off -> cancel + wait -> deleteWatchRuns

B  scene.cut params -> scenes/preview -> SceneCutter over stored Moments (read only) -> diff
   publish -> canvas_meta scene_config_v1 -> SceneBuilder periodic rebuild (+ optional bounded recut, one transaction)
   -> scenes -> input.scenes pipelines, Scene summary derive (stale fields hidden until rebuilt)

C  run item -> skill.call -> SkillCallGate (flag, privacy, zone, args, caps)
   -> SkillClient -> bridge AIDL -> Nexus hub (per-op wearer consent) -> other plugin
   <- result (<= 16 KiB) -> schema check -> canvas_run_item.outputJson -> downstream nodes
```

## 5. Privacy and threat model

**C, skills:** threats are (1) personal data leaving through arguments, (2) another plugin's output steering a model (injection), (3) results outliving a Forget, (4) a call that should not happen in Offline only or in a zone.
- **What leaves PCA:** only the op id and constant, time, enum or number arguments, checked at validate, at estimate and at call time. PHOTOS ports cannot connect to `skill.call` (validator). Nexus's own per-op consent is required on top; PCA never asks the hub to skip it.
- **Forget coverage:** results live only in `canvas_run_item.outputJson`. A frame item's rows go with the frame Forget cascade. Window items go with the run prune, pipeline delete and Delete all. A new Forget policy row `skill_call_result` makes the policy test pass. The other plugin's own storage is its own, and the node panel says so.
- **Offline-only mode:** every skill call is refused (`offline_only`, fail closed). PCA cannot verify that another plugin stays local.
- **Pause zones fail closed:** inside a zone, or when the zone status is UNKNOWN, calls are refused (`zone_paused`), matching PCA's own skills (`pause_zone`).
- **No identity recognition:** no person data is ever an argument. Skill output (for example agenda attendee names) is text from another plugin. It is never matched to faces or to detection output. The v2.3 `IdentityGuard` runs on any model node that takes detection output.
- **No raw photos leaving:** by type, as above.

**A, watch:** no photo and no location. Rows are deleted on switch-off and by Delete all and the prune. GAP-2 stays accepted. A watch run whose window overlaps an explicit pause or a zone pause is refused (`paused_window`) (owner question 5). Whether pause intervals are recorded per second is UNVERIFIED. If they are not, any window that cannot be shown clear is refused.

**B, scenes:** Scenes are built only from stored Moments, and no Moment exists from inside a zone. A re-cut re-reads in one transaction and cannot resurrect a forgotten Moment. Preview writes nothing. The config is not personal data. Cutting and the Nomic embeddings are local, so Offline only changes nothing. The existing Moment and Scene Forget cascade covers everything.

**Logs (all parts):** count-only fixed tokens: op id, code, counts and latency. Never an argument, a result or a time.

## 6. Failure modes

| Part | Failure | Behaviour |
|---|---|---|
| A | no watch source / values moved / > 50 values | 404 `no_watch_source` / 409 `watch_moved` / refused, narrow the window |
| A | switch-off mid-run | run cancelled, then its rows deleted (race closed) |
| B | window over 7 days, config out of bounds | refused at validate |
| B | Forget during a re-cut | the transaction rule: the Scene shrinks, nothing is resurrected |
| B | periodic rebuild races a publish | serialised on the builder (UNVERIFIED that a lock exists; WP B1 checks and adds one) |
| C | plugin or op gone, catalog changed since estimate | `skill_missing` / `catalog_moved` |
| C | op not approved by the wearer | `not_granted`, with a hint to the hub screen |
| C | timeout, hub down, busy | `deadline_exceeded`, `unavailable`, `busy`; at most one retry; read ops have no effect to undo |
| C | result too large or not in schema | `invalid_result`; nothing stored but the code |

## 7. Test plan

**JVM (no device):**
- A: route tests (auth, templates, 403 Developer off, 409s); a race test with a fake hub (switch-off during a run leaves no rows).
- B: `SceneCutter` with default params gives byte-identical plans to today (a regression guard); bounds; preview diff; publish then re-cut is idempotent; a Forget during a re-cut.
- C: the `SkillArgPolicy` table (text, place, coordinate and photo sources refused); `SkillCallGate` (flag, `offline_only`, zone UNKNOWN, caps, sanitised codes); catalog parsing via the shared parser; a fake `SkillClient`; result schema and length cuts; Forget, prune and Delete all remove results; logs count-only.

**Emulator:** A and B end to end with a fake watch ring and synthetic Moments. C against the Nexus hub built from source with a stub plugin that offers one read op (the 19 Nov smoke ran the hub on the emulator).

**Device:** one real watch run. One read-only preview over a real day of Moments on the phone. C needs the real hub, the "Use skills from other plugins" grant, the per-op consent screen (never reached so far) and one real plugin.

## 8. Work packages (agent hours, UNVERIFIED)

| WP | Content | Hours | Depends on | Device? |
|---|---|---|---|---|
| A1 | watch routes, run-page source, race fix, tests | 3 | none | one smoke |
| B1 | `scene.cut` node, config store, preview route, builder lock check | 3.5 | none | No |
| B2 | publish, bounded re-cut, diff view, reset | 3 | B1; v2.1 P2 helps | read-only preview |
| C0 | read SDK section 3.6; emulator spike: call one read op of a stub plugin | 2.5 | Nexus bridge build | emulator |
| C1 | `SkillClient` port, bridge AIDL method, catalog | 4 | C0 | No |
| C2 | `skill.call` node, `SkillCallGate`, `SkillArgPolicy`, estimate | 4 | C1 | No |
| C3 | palette catalog browser, inspector rows | 2 | C2 | No |
| C4 | Forget row, cascade tests, `skillsLive` flag | 1.5 | C2 | No |
| C5 | device session with the real hub and one plugin | 2 + user | C1-C4, Nexus 1.11 bridge installed | Yes |
| Review | one Codex pass per part | 1.5 | | |
| **Total** | | **~27 h** (A 3, B 6.5, C 16, review 1.5) | | |

Order: A1 -> B1 -> B2 -> C0 -> C1 -> C2 -> C3 -> C4 -> C5. A and B can run in parallel worktrees. C is gated by the Nexus line (1.11.x). If the bridge is not installed by then, A and B ship in 1.9.x and C follows.

## 9. Owner questions (yes/no)

1. Skill nodes call read operations only in v2.5, with actions left for v3.0's per-operation approval?
2. Skill arguments are limited to typed constants, times, enums and numbers (never memory text, place names, coordinates or photos)?
3. All skill calls are refused under Offline only, even for plugins that might work locally?
4. Read-only skill nodes may run in a published pipeline behind `skillsLive` (default OFF)?
5. A watch run refuses any window that overlaps an explicit pause or a pause zone?
6. A published Scene config applies to future rebuilds only, unless you also confirm a bounded re-cut of past days?
7. Parts A and B ship in 1.9.x first, and part C waits for the Nexus 1.11 bridge?
