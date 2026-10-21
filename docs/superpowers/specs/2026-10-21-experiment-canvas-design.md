# Experiment canvas: design

**Status:** draft for the user's review. It was brainstormed on 2026-10-21 **without the user** (they were away and asked us to go ahead). Every place where the brainstorm would have asked them carries a `Ruling:` line, and all of them are listed again in §12, "Open for the user", so any one can be overturned.
**Inputs:** handoff `2026-10-21-handoff.md` §5 (user, 2026-10-21), §7, §9.3, §10.0 and §10.2; agent flows spec `2026-10-14-agent-flows-design.md`; wave 1 spec `2026-10-15-keyframe-capture-image-memory-design.md` §P5 (debug view).
**Plan for slice 1 (MVP):** `docs/superpowers/plans/2026-10-21-experiment-canvas-mvp.md`.

## 1. Goal
The user wants a place to tune the AI pipeline that turns glasses photos into memories, and to try new steps on top of it, starting with the planned **router → intent → memory** stack. The workflow is:

1. See the pipeline as a DAG.
2. Edit a step: a prompt, a parameter, or a new step wired to existing components.
3. Pick N real photos and run them through the pipeline in bulk.
4. Compare the outputs side by side, against each other and against what the live pipeline saved, and score them.
5. Freeze the version that wins, then publish it to the live phone runtime, with rollback.

The canvas lives **in the phone app**. The app hosts an embedded local web server. The app's own screens are read-only; editing happens on the local web page, opened in the app's WebView, in phone Chrome, or in a laptop browser on the same Wi-Fi after entering a pairing code. There are no accounts and no external server (user, 2026-10-21). The canvas **replaces the planned P5 "debug view"**.

## 2. What exists and what the canvas wraps
| Existing piece | Where | How the canvas uses it |
|---|---|---|
| `AgentFlow` / `AgentFlows` / `FlowLayouts` (read-only DAG + layered layout) | `:services:ai` `ai.flow` | A pipeline converts to an `AgentFlow`. `FlowLayouts.layout` places its nodes, both for the app's read-only view (the existing `FlowViewScreen`) and for the web SVG, so there is **no JS graph library**. |
| `RoleRunner` (active prompt + SYSTEM, structured output, `OutputCheck`, one corrective retry, fallback binding) | `:services:ai` `ai.run` | Role-backed nodes run through it unchanged, with a new **in-memory prompt override** on `RoleCall`. What you test is what ships. |
| `TestBed` (draft prompt on a chosen endpoint; `compatibility` gate) | `:services:ai` | Its `compatibility(key, draft)` check validates draft prompts before a run and before publish. |
| `ExtractSave.extract` (≤ 3 photos per VISION_EXTRACT call, provenance contract, single-photo fallback) | `:services:orchestration` | The extract node calls it as is (with the override). It **never** calls `saveExtraction`. |
| `ExtractFollowUp.locallyTrusted` / `modelCriticPasses`, `CriticWorker` | `:services:orchestration` | The trust-gate node and the critic node. |
| `FramePhotos.load`, `FrameInputs`, `frame_meta`, `frame_state`, `frame_source` | orchestration / memory | The photo picker, the images sent, per-photo metadata, and the **live result** for each photo (the memories `frame_source` links to it). |
| `PromptRepository` (versions, `save`, `restore`, `keepVersions`) | `:services:ai` | Publish writes through `save`. Rollback writes the stored previous text back through `save`. |
| `LanguageModel` + `CallBudget` (`CallClass`) | `:services:ai` / orchestration | Experiment calls run as `CallClass.USER`. |
| `LineDiff` | `:services:ai` `prompt` | Text diffs in the compare view. |
| `WipeDao`, `ForgetService` | `:services:memory` | Delete all and Forget also delete canvas outputs. |

There is no HTTP server in the app today: OkHttp's `mockwebserver` is a test-only dependency. There is no developer mode either.

## 3. Rulings on the suggested defaults
- **Ruling R1: the first user is the developer only.** The canvas is reachable only when **Developer mode** is on: a new switch at the bottom of Settings → Diagnostics, off by default. When it is on, Settings → AI shows an "Experiment canvas" row. — *Why:* the canvas can publish prompts to the live runtime and spend model calls; end users don't need it yet. — *Cost if wrong:* small. Opening it to end users later means polish and copy work, not a redesign.
- **Ruling R2: freeze and publish are two separate steps.** *Freeze* makes an immutable, numbered version of a pipeline (v1, v2, …) with a content hash. *Publish* applies a frozen version to the live phone runtime, and *Roll back* undoes the latest publish. In the MVP, only **live-shaped** pipelines can be published: the same nodes and edges as the default "Live extraction" pipeline, with changes to prompts only. Publishing writes those prompts through `PromptRepository.save`, so the existing live code picks them up. A pipeline with a different shape, such as one with an intent step, can be built, frozen and run in the MVP, but it goes live only in slice 3, through a pipeline interpreter behind the `KeyframePipeline` extractor seam. Export and import as files is slice 5. — *Why:* it ships safe value now (prompt tuning is the main lever today) without replacing the production extraction path in slice 1. — *Cost if wrong:* if the user wants the intent stack live right away, slice 3 moves ahead of slice 2.
- **Ruling R3: bulk runs use the currently configured model.** Each role-backed node uses its role's binding (endpoint, model, fallback) exactly as the live pipeline does. Before a run, the page shows an estimate (photos, model calls, and which endpoint and model each AI node uses) and the user must confirm. Runs are sequential, one at a time, and run as `CallClass.USER`: the experiment doesn't take tokens from the live budget, and the live budget doesn't stall the experiment. Limits: 50 photos and 200 estimated calls per run. A per-node model override (A/B between models) is slice 2. — *Why:* this is the user's suggested default, and it's the honest test of what would ship. — *Cost if wrong:* no money figure is shown, because there is no pricing data in the app; and experiment calls show up in Diagnostics under user calls.
- **Ruling R4: runs and scores are stored on the phone,** in new additive Room tables (DB v3, §7) inside the existing SQLCipher database. Delete all wipes them. Forgetting a photo deletes every canvas output for that photo. They are never included in memory, settings or diagnostics exports. The newest 20 runs are kept, and older ones are deleted when a new run starts. — *Why:* run outputs are derived from photos, so they are as private as memories. — *Cost if wrong:* low. A laptop-side archive can come with export (slice 5).
- **Ruling R5: the server is NanoHTTPD 2.3.1** (`org.nanohttpd:nanohttpd`, BSD-3, about 100 KB, no transitive dependencies), in `:phone` only. — *Why:* Ktor CIO pulls several Ktor artifacts (about 2–3 MB), and nothing like it is in the app today. NanoHTTPD is old but small, synchronous and easy to test on the JVM. — *Cost if wrong:* swapping it later touches one class (`CanvasServer`), because routing and auth are written against our own `HttpRequest`/`HttpResponse` types.
- **Ruling R6: the web UI is a static bundle in `phone/src/main/assets/canvas/`:** plain ES2020 modules, no build toolchain, no vendored libraries. The server computes the DAG layout with `FlowLayouts`, and the page draws it as SVG. MVP editing covers prompt nodes (template and schema), node parameters, and a whole-pipeline **JSON editor** with validation, so new nodes and edges can be added as text. Drag-and-drop editing is slice 2. — *Why:* no Node toolchain to maintain in an Android repo, and the layout already exists and is tested. — *Cost if wrong:* if text editing of the graph is too clumsy, drag-and-drop moves into slice 1, at about 2 tasks more.
- **Ruling R7: LAN access is off by default.** The server binds to `127.0.0.1` only. Turning on "Use from a laptop" binds it to the Wi-Fi interface. **Every API call needs a bearer token, even on loopback**, because any other app on the phone can reach `127.0.0.1`. The app's WebView receives its token in the URL fragment. Any other browser pairs with a 6-digit code shown in the app. Requests are checked against a Host and Origin allow-list, against DNS rebinding. In the MVP, LAN traffic is plain HTTP: the toggle warns "Photos and outputs travel unencrypted on this Wi-Fi", and LAN turns itself off after 30 min idle. TLS with a pinned fingerprint is slice 2. — *Why:* it's the user's chosen access model, with the smallest safe surface. — *Cost if wrong:* on an untrusted Wi-Fi, someone listening on the network could see photos while LAN is on. That's why the toggle carries the warning and turns itself off.
- **Ruling R8: the server runs only while it's needed.** A small foreground service (`CanvasService`, type `dataSync`, with an ongoing notification and a "Stop" action) runs while the canvas screen is open, while LAN is on, or while a run is in progress. It stops after 30 min with no authenticated request and no run in progress. It is separate from the hub service. — *Why:* a backgrounded app can be frozen, which would cut a laptop session or a run halfway. — *Cost if wrong:* it adds one notification and one manifest permission, `FOREGROUND_SERVICE_DATA_SYNC`.
- **Ruling R9: the port is fixed at 8765.** If 8765 is busy, the server tries 8766–8770. The app always shows the URL it actually uses. — *Why:* a stable URL to bookmark on the laptop. — *Cost if wrong:* nothing.
- **Ruling R10: experiments never write anything live.** A run writes only canvas tables: no memories, no `frame_state` changes, no embeddings, no nudges, no reprocess jobs. The "Save memory" node is a **preview**: it shows what would be saved (fact, tier, review state) and saves nothing.
- **Ruling R11: what you test is what ships.** Role-backed nodes call `RoleRunner` with an in-memory prompt override, not a separate code path. The extract node calls `ExtractSave.extract`, so batching, the single-photo fallback and the out-of-date-schema rule are the live ones.
- **Ruling R12: intent comes first, as a draft.** A second built-in pipeline, **"Intent → extract (draft)"**, ships unpublished. A generic `ai.prompt` node classifies the photo into `sitting_idle | workout | computer_work | driving | walking | other`, with a confidence and a short reason, using the VISION_EXTRACT role's binding. A `route.switch` sends each photo along the edge for its label to an extract node with an intent-specific prompt. All routes start out with copies of the live VISION_EXTRACT prompt for the user to edit, and the label is passed to the prompt as context. — *Why:* it's the user's planned first stage (§10.0), and it is testable on real photos from day one. — *Cost if wrong:* it's only a template; the labels are editable as text.
- **Ruling R13: P5 debug coverage.** The run view shows, for each photo: the photo, its motion, dwell, blur, capture time and state, the live caption and facts, and every node's output. That covers the "stored output against what really happened" half of the P5 debug view. The Ask half (retrieved items, scores, salience boost) is slice 4.

## 4. Pipeline model (`:services:ai`, package `com.rokid.context.ai.canvas`)
A pipeline is JSON, stored as text, decoded by hand with `kotlinx.serialization.json` elements. `:services:ai` doesn't apply the serialization plugin, and the plan doesn't add it.

```json
{"schema":1,"id":"live-extract","name":"Live extraction",
 "nodes":[{"id":"photos","type":"input.photos","title":"Photo","params":{}},
          {"id":"extract","type":"ai.vision_extract","title":"Facts from frames","params":{"batch":3,"prompt":{"source":"live"}}}],
 "edges":[{"from":"photos","to":"extract"},{"from":"trust","to":"critic","when":"unsure"}]}
```

- `prompt` is either `{"source":"live"}`, which uses the active prompt at run time, or `{"source":"draft","template":"…","schema":"…"}`, which uses the given text. The schema may be null, meaning the default contract.
- **Limits:** node ids match `[a-z][a-z0-9_]{0,31}`; at most 40 nodes and 80 edges; templates and schemas at most 16,000 characters each; at most 64 KB for the whole JSON.

**Node types** (the `NodeTypes` registry, with typed ports):

| type | input port types | output | branches | model calls (n items) | params |
|---|---|---|---|---|---|
| `input.photos` | none (exactly 1 per pipeline) | PHOTOS | none | 0 | none |
| `ai.vision_extract` | PHOTOS, JSON | EXTRACTION | none | ceil(n / batch) | `batch` 1–3 (default 3), `prompt` |
| `gate.local_critic` | EXTRACTION | EXTRACTION | `trusted`, `unsure` | 0 | none |
| `ai.critic` | EXTRACTION | EXTRACTION | `pass`, `fail` | n (only items routed to it) | `prompt` |
| `ai.prompt` | PHOTOS, JSON, EXTRACTION | JSON | none | n | `role` (a `Role` name other than EMBED, default VISION_EXTRACT), `name`, `template`, `schema` (JSON Schema text, required), `images` (bool, default true) |
| `route.switch` | JSON | JSON (passed through) | each of `cases`, plus `other` | 0 | `field` (top-level key), `cases` (1–12 labels) |
| `memory.write` | EXTRACTION | PREVIEW | none | 0 | none (preview only, R10) |

**Validation** (`PipelineValidator.validate(p): List<String>`; an empty list means valid):
- ids are unique and every edge end exists;
- there is exactly one `input.photos`, and it has no incoming edge;
- the graph is acyclic;
- every edge's source output type is accepted by the target, and all incoming edges of a node carry the same type;
- an edge out of a branching node has `when` set to one of that node's branches; edges out of other nodes have no `when`;
- every non-input node is reachable from `input.photos`;
- params are in range, and a draft `schema`, or `ai.prompt`'s `schema`, parses as a JSON object.

Problems are short fixed texts naming the node or edge id, never prompt text.

**Hash:** `PipelineHash.of(p)` is the SHA-256 of the canonical JSON (keys sorted, no whitespace), shown as 12 hex characters.

**Default pipelines** (`DefaultPipelines`, seeded at first open as built-in, read-only rows; the user duplicates one to edit it):
- `live-extract`: `photos → extract (batch 3) → trust`; `trust -trusted→ write`; `trust -unsure→ critic`; `critic -pass→ write`; `critic -fail→ write`. Both prompts are `live`. A test pins it to `CaptureFlow`'s photo path (photo → extract → confidence → critic → save) and to the code (`KeyframeHandle.extract`).
- `intent-draft`: `photos → intent (ai.prompt) → route (switch on "activity")`. Each case goes to its own `ai.vision_extract` (`extract_<label>`, draft prompt = the live seed text) and then to one shared `memory.write`. The `other` case goes to the live-prompt extract.

**`LiveShape.matches(p)`** is true when `p` has the same node types, ids and edges (`when` included) as `live-extract` and `batch` is 3; only `prompt` params may differ.

**`PipelineFlows.toAgentFlow(p)`** maps the node kinds as follows: `input.*` → INPUT; `memory.write` → OUTPUT; role-backed nodes → STEP with `Facet.Prompt(key)` and `Facet.Model(role)`; `ai.prompt` → STEP with `Facet.Custom("canvas.prompt")`; the rest → STEP with no facets. Edge labels come from `when`.

## 5. Runner (`:services:orchestration`, package `com.rokid.context.canvas`)
- **Items.** One item per chosen photo, which carries the photo (`ExtractPhoto`) through every node. Nodes run in topological order. An item travels along edge `e` from A to B when A produced a value for it and `e.when` is null or equals the item's branch at A. An item that reaches a node from two edges is an item error (`merge_conflict`), and that node doesn't run for it.
- **Batching.** `ai.vision_extract` groups the items that reach it into chunks of `batch`, in the order they were picked, and calls `ExtractSave.extract(runner, photos, CallClass.USER, override, contextSources)`. Each item's output is the facts whose `sourceFrameIds` include its frame, plus its caption. The batch's latency and tokens are recorded on each item, with a shared `batchId`. Upstream JSON (for example the intent result) becomes the `{{context_sources}}` data block `upstream`.
- **Executors** (one per type; listed under `NodeExecutor`): `input.photos` loads with `FramePhotos.load`, and gone or unseen photos become item errors. `gate.local_critic` uses `ExtractFollowUp.locallyTrusted(CriticWorker(), "<runId>:<frameId>", facts)`. `ai.critic` runs RoleRunner CRITIC with the same variables as `modelCriticPasses`; an unbound CRITIC role gives branch `fail` with the note `unbound`, just as live does. `ai.prompt` composes SYSTEM + template with the standard variables (photo lines in `{{recent_context}}`, upstream in `{{context_sources}}`), calls `LanguageModel.complete(role, USER, …)` with `StructuredOutput.plan`, parses with `ModelJson`, and validates with `JsonSchemaValidator`. There is no corrective retry in the MVP; an invalid reply becomes item error `INVALID_OUTPUT`. `route.switch` reads `field`. `memory.write` builds the preview.
- **Estimate.** For each AI node, assume every item reaches it: `nominal` = the sum of per-node calls; `max` = nominal × 2 for the corrective retries, plus the single-photo fallback for extract nodes with `batch` > 1. A run is refused when there are more than 50 photos or nominal is over 200. The estimate returns a `confirmToken` (random, valid 5 min, bound to the pipeline hash and the frame ids), and starting a run requires it.
- **Run life cycle.** One run at a time; a second one is refused with `busy`. Statuses: `RUNNING → DONE | CANCELLED | FAILED`. A run still `RUNNING` at process start becomes `INTERRUPTED`. Cancel stops at the next model call. A run that sees thermal SEVERE (from `PhonePowerPolicy`) stops as `STOPPED_HOT`. Each item and node result is written when it finishes, so a long run can be watched by polling. The prompt version and model used are recorded on each item.
- **Run snapshot.** A run stores the full pipeline JSON it ran, plus its hash, so later edits never change what an old run means.

## 6. Freeze, publish, roll back (`CanvasPublisher`, orchestration)
- **Freeze** (`pipelineId`, note ≤ 80 characters) appends `canvas_pipeline_version(version = last + 1, json, hash)`. A version is never edited. Freezing a draft identical to the last version returns that version.
- **Publish** (`pipelineId`, `version`) is refused, listing all reasons, when any of these holds:
  1. `LiveShape.matches` is false;
  2. no prompt is `draft` (nothing to publish);
  3. `TestBed.compatibility(key, draft)` reports problems for any draft prompt;
  4. there is no `DONE` run of exactly this hash with at least one `OK` item at every draft-prompt node ("tested").

  Otherwise it snapshots, for each touched key, the active `(version, template, schema)`, then calls `PromptRepository.save(key, draft, note = "Canvas: <name> v<N>", testedOk = true)`, and inserts a `canvas_publish` row with the snapshot and the fingerprints of the published texts. That takes one transaction for the row; the prompt saves go through the repository.
- **Roll back** undoes the newest publish that hasn't been rolled back yet. For each key it checks that the active prompt's fingerprint still equals the one published. If anything else changed it since (for example the in-app prompt editor), the rollback is refused with "This prompt was changed after the publish. Use Settings → Prompts → History instead." Otherwise it saves the snapshot text back with note "Canvas rollback" and sets `rolledBackAt`. Publishes stack, and each rollback undoes the newest one. The app's canvas screen has a native **Roll back** button, so it works without a browser.

## 7. Storage (`:services:memory`, Room v2 → v3, additive only)
Six new tables. No existing table changes.

| table | columns (PK in bold) | indexes |
|---|---|---|
| `canvas_pipeline` | **id** TEXT, name TEXT, draftJson TEXT, draftRev INTEGER, builtIn INTEGER, createdAt INTEGER, updatedAt INTEGER | none |
| `canvas_pipeline_version` | **pipelineId** TEXT, **version** INTEGER, json TEXT, hash TEXT, note TEXT, frozenAt INTEGER | none |
| `canvas_run` | **runId** TEXT, pipelineId TEXT, version INTEGER NULL, pipelineHash TEXT, pipelineJson TEXT, status TEXT, frameCount INTEGER, estCalls INTEGER, calls INTEGER, failures INTEGER, startedAt INTEGER, finishedAt INTEGER NULL, modelsJson TEXT | pipelineId; startedAt |
| `canvas_run_item` | **runId** TEXT, **frameId** TEXT, **nodeId** TEXT, status TEXT (OK/ERROR/SKIPPED), branch TEXT NULL, outputJson TEXT NULL, error TEXT NULL, latencyMs INTEGER NULL, promptTokens INTEGER NULL, completionTokens INTEGER NULL, model TEXT NULL, promptVersion INTEGER NULL, batchId TEXT NULL | frameId |
| `canvas_score` | **runId** TEXT, **frameId** TEXT, **nodeId** TEXT, score INTEGER (−1/0/+1), note TEXT, updatedAt INTEGER | frameId |
| `canvas_publish` | **id** INTEGER autoincrement, pipelineId TEXT, version INTEGER, hash TEXT, publishedAt INTEGER, rolledBackAt INTEGER NULL, snapshotJson TEXT | none |

- `MIGRATION_2_3` only creates these tables, with Room's generated SQL from `3.json`.
- `WipeDao.wipeMemory` also clears `canvas_run_item`, `canvas_score` and `canvas_run`. Pipelines, versions and publishes stay: they hold prompts, not photo-derived data.
- `ForgetService`: wherever a frame gets a tombstone (`forgetMoment`, and `cascade` with `lockFrames`), `canvas_run_item` and `canvas_score` rows for those frames are deleted in the same transaction.
- `error` holds fixed short texts (a failure kind or a `UserFacingError` title), never provider text.

## 8. Server and access (`:phone`, package `com.rokid.context.phone.canvas`)
- **`CanvasServer`** (a NanoHTTPD subclass) is a thin adapter. Routing, auth and JSON live in plain-JVM classes (`CanvasRouter`, `CanvasAuth`, `HostPolicy`) that take our `HttpRequest` and return an `HttpResponse`, so they're unit-testable with OkHttp against a real port.
- **Binding:** `127.0.0.1:8765` (fallback 8766–8770); with LAN on, `0.0.0.0` on the same port.
- **Host allow-list:** `127.0.0.1:<port>`, `localhost:<port>`, and, with LAN on, `<current Wi-Fi IPv4>:<port>`. Anything else gets 421 `forbidden_host`. A request other than GET must carry an `Origin` that equals `http://<Host>`, or no `Origin` at all (the WebView); anything else gets 403.
- **Auth:**
  - **WebView:** the app makes a token and loads `http://127.0.0.1:<port>/#t=<token>`. The page moves the token to `sessionStorage` and clears the fragment with `history.replaceState`. A fragment is never sent to the server.
  - **Other browsers:** `POST /api/pair {"code":"123456"}` returns `{"token":…}`. The 6-digit code comes from `SecureRandom`, is shown on the app's canvas screen, is valid for 5 min and for a single use. After 5 wrong tries in total the code is voided, a new one is needed, and pairing is locked for 30 s. "Open in phone browser" opens `…/#pair=<code>`, which fills the code in.
  - **Tokens** are 32 random bytes, base64url. They expire after 30 min idle and 12 h at most. At most 3 sessions; a 4th evicts the oldest. "Sign out all browsers" is in the app. Turning Developer mode off stops the server and drops all sessions.
  - Every `/api/*` route except `/api/pair` needs `Authorization: Bearer <token>`; without it the answer is 401. Static assets need no token: they contain no data.
- **Headers on every response:**
  - `Content-Security-Policy: default-src 'self'; img-src 'self' blob:; style-src 'self'; script-src 'self'; connect-src 'self'; frame-ancestors 'none'`;
  - `X-Content-Type-Options: nosniff`;
  - `Referrer-Policy: no-referrer`;
  - `Cache-Control: no-store` (API and images);
  - no CORS headers.
- **Body limit:** 256 KB, else 413.
- **Logging:** the log is count-only: method, route template, status and duration. Never ids in paths, bodies, prompt text or tokens.
- **Errors:** JSON `{"error":"<code>","message":"<short text>","problems":[…]}`. The codes are `unauthorized`, `forbidden_host`, `bad_request`, `invalid_pipeline`, `not_found`, `conflict`, `busy`, `too_large`, `publish_blocked`, `rollback_blocked`, `refused` (over the limits).

**API** (all JSON; ids in paths):

| route | does |
|---|---|
| `POST /api/pair`, `POST /api/logout`, `GET /api/session` | pairing, ending the session, session info (LAN on or off, expiry) |
| `GET /api/node-types` | the registry: ports, branches and params of each type, for the inspector |
| `GET /api/pipelines` | id, name, builtIn, draftRev, versions, the live-published version |
| `POST /api/pipelines {"from":"<id>[@<version>]","name":…}` | duplicates a pipeline or a version into a new editable draft |
| `GET /api/pipelines/{id}` | draft JSON, draftRev, problems, `layout` (cells per node id, from `FlowLayouts`), versions, liveShape (bool) |
| `PUT /api/pipelines/{id} {"json":…,"draftRev":n}` | saves the draft; 409 when draftRev is stale; built-in pipelines are read-only (403); invalid JSON is saved anyway, returned with its problems, and blocked from runs and freeze |
| `DELETE /api/pipelines/{id}` | deletes a pipeline that isn't built in and has no active publish |
| `POST /api/pipelines/{id}/freeze {"note"}` | freezes the draft → `{version, hash}` |
| `POST /api/pipelines/{id}/versions/{n}/publish`, `GET /api/publish`, `POST /api/publish/rollback` | §6 |
| `POST /api/check-prompt {"nodeType","template","schema"}` | the `TestBed.compatibility` problems for a role-backed node; a schema parse check for `ai.prompt` |
| `GET /api/frames?before=<ms>&limit=<≤ 120>&dwell=<bool>` | recent frames: id, ts, motion, dwell, blur, state, hasLiveMemories |
| `GET /api/frames/{id}` | metadata plus the live caption and facts (memories linked by `frame_source`) |
| `GET /api/frames/{id}/thumb`, `/image` | decrypted JPEG: long side 160 px for thumb, as stored (≤ 640 px) for image; 404 `not_found` when the photo was deleted |
| `POST /api/runs/estimate {"pipelineId","version"?,"frameIds"}` | `{nominal,max,photos,models:[{nodeId,endpoint,model}],confirmToken}` or `refused` |
| `POST /api/runs {"pipelineId","version"?,"frameIds","confirmToken"}` | starts the run → `{runId}` |
| `GET /api/runs`, `GET /api/runs/{id}`, `POST /api/runs/{id}/cancel`, `DELETE /api/runs/{id}` | list, detail (items, scores, progress), cancel, delete |
| `PUT /api/runs/{id}/scores {"frameId","nodeId","score","note"}` | upserts a score |
| `GET /api/compare?a=<runId>&b=<runId>` | photos in both runs; for each node id present in both, the outputs side by side with a `LineDiff` of their pretty text |

## 9. Web UI (`phone/src/main/assets/canvas/`)
Files: `index.html`, `app.js` (hash router + API client), `dag.js` (SVG from layout cells), `views.js`, `style.css`. No inline scripts or styles (CSP). Photos are loaded with `fetch` plus a bearer token and shown through `blob:` URLs. Light and dark follow `prefers-color-scheme`. The layout works from a 360 px phone up to a laptop.

| view | shows | actions |
|---|---|---|
| `#/` pipelines | name, built-in tag, versions, LIVE badge, last run | duplicate, delete, open |
| `#/p/<id>` pipeline | the DAG (SVG, with branch labels on edges); inspector for the selected node: type, params, and for prompt nodes Live/Draft, template and schema text areas, "Check" (`/api/check-prompt`); the "Edit JSON" panel with problems; the versions list | save draft, freeze, publish (shows the blocking reasons), new run |
| `#/run/new?p=<id>&v=<n>` | photo picker: a grid of thumbnails with a dwell / motion / day filter and "select newest N", counter ≤ 50 | estimate → confirm dialog (photos, calls nominal/max, endpoint and model per node) → start |
| `#/run/<runId>` | progress; per photo: thumbnail (tap for 640 px), metadata, the **live result**, then one card per node (facts list, caption, route label, JSON, preview), latency, tokens, error | 👍 / 👎 / note per node card; cancel; compare with… |
| `#/compare?a=&b=` | per photo, two columns (run A, run B), line diff per node | none |

## 10. App side (`:phone`)
- **Developer mode:** a `DeveloperModeStore` (SharedPreferences `pc_dev`, key `developer_mode`, default false) with a switch at the bottom of Diagnostics.
- **Settings → AI → "Experiment canvas"** (only with Developer mode on) opens the **Canvas home** screen (Compose, read-only):
  - server status line ("Off" / "On · 127.0.0.1:8765" / "On · LAN 192.168.1.20:8765 · turns off after 30 min idle");
  - buttons: **Open editor** (in-app WebView), **Open in phone browser**, a **Use from a laptop** switch (the warning, the URL, the pairing code with its countdown);
  - pipelines (name, versions, LIVE badge) → the read-only DAG (the existing `FlowViewScreen` with a registry returning `PipelineFlows.toAgentFlow`, node taps do nothing);
  - the last 5 runs (status, photos, calls, when);
  - **Roll back live** when a publish is active, and **Sign out all browsers**.
- **`CanvasService`**: a foreground service of type `dataSync`, carrying the server and the run scope. It is started by the canvas screen, by LAN on and by a run, and stopped by the idle rule (R8), by its notification's Stop action, or by Developer mode turning off.
- **WebView screen:** JavaScript on; no file access; no content access; `setAllowFileAccess(false)`; no `addJavascriptInterface`; navigation is limited to the server origin, and anything else opens in an external browser.

## 11. Slices
1. **MVP (this plan):** the prompt override seam; the pipeline model, validation and defaults; Room v3; executors and runner; freeze, publish and rollback; the server with auth; the API; the web UI (view, prompt and JSON editing, photo picker, runs, compare, scores); developer mode, service, home screen, WebView, read-only DAG.
2. **Editing comfort:** drag-and-drop graph editing (add, remove, connect nodes); a per-node model override for A/B tests (`completeOn`); TLS on LAN with the fingerprint shown in the app; an `input.asks` node and an `ai.answer` + retrieval node (text inputs); an LLM-judge node and per-run score summaries; run retention by age.
3. **Live interpreter:** any frozen pipeline can be published. A `PipelineExtractor` implements the `KeyframePipeline` extractor lambda (claim CAS and `saveExtraction` unchanged), with **shadow mode** (the draft runs beside live on 1 photo in K, saving to canvas tables only), then canary, then full. Auto-rollback when the failure rate of the published version is more than 2× live's over 30 photos.
4. **P5 debug and context signals:** retrieval and embedding nodes, with item scores and sources for asks; image recall. Deterministic signal input nodes (car Bluetooth / Android Auto, GPS speed, activity recognition, watch motion) and a **fusion node** that merges them with the image intent, which is the §10.0 open question. Battery: the intent output feeding the capture-rate controller.
5. **Sharing:** export and import pipelines as files; an end-user mode if wanted.

## 12. Open for the user
Each line can be overturned. The cost of each is in §3.
1. R1: developer-only, behind a Developer mode switch in Diagnostics.
2. R2: freeze = an immutable version; publish = prompts only, for live-shaped pipelines, in the MVP. Intent-shaped pipelines go live in slice 3.
3. R3: bulk runs use the configured role models, as user calls, at most 50 photos and 200 calls, with a calls-only estimate (no money figure).
4. R4: runs are on the phone in DB v3, wiped by Delete all and Forget, 20 runs kept, never exported.
5. R5: NanoHTTPD rather than Ktor.
6. R6: plain JS with no build and no graph library; graph edits as JSON text in the MVP; drag-and-drop in slice 2.
7. R7: plain HTTP on LAN in the MVP (with a warning and auto-off); TLS in slice 2.
8. R8: a separate foreground service with a notification while the canvas is in use.
9. R12: the intent labels and the routing template.
10. R13: the Ask and retrieval debug moves to slice 4.
11. Open from §10.0, not ruled here: how deterministic signals and the image intent are fused (slice 4).
