# Experiment canvas, slice 2: design

**Status:** draft. Written on 2026-10-21 by the Opus planner while the user was asleep. Every choice the user would normally have been asked about carries a `Ruling:` line (what — why — cost if wrong), and all of them are listed again in §12 "Open for the user".
**Builds on:** the canvas MVP (`2026-10-21-experiment-canvas-design.md`, built on branch `canvas/integration` in `/Users/<user>/Desktop/rokid/pca-canvas/int`, 12 tasks plus the final-review fixes), its ledger (`.superpowers/sdd/2026-10-21-experiment-canvas-mvp/progress.md`, `final-review.md`, `final-rereview.md` in that worktree), the wave 2 plan's "Canvas touch points" and the handoff §9.3 / §10.0 / §10.2.
**Plan:** `docs/superpowers/plans/2026-10-21-canvas-slice2.md`.

## 1. Goal (user direction, binding)
An MLflow/Dagster-like DAG canvas. The user defines new nodes (processes, prompts), connects them to existing components, bulk-tests N photos, freezes the winner, and the **router → intent → memory** stack goes live. The intent step classifies the activity in the image (`sitting_idle | workout | computer_work | driving | walking | other`) and routes each photo to an intent-specific prompt. Later the motion/context hub (wave B) feeds it, and the rest-vs-motion connectors are user-defined sources.

## 2. Scope and order
| # | Item | In slice 2? |
|---|---|---|
| 1 | Drag-and-drop DAG editor (plain JS, SVG, keyboard), plus a headless JS test harness | yes |
| 2 | Router → intent → memory as a publishable pipeline: an interpreter behind the `KeyframePipeline` extractor seam, per-intent prompt variants, A/B runs, the intent label recorded | yes |
| 3 | Freeze and publish for non-live-shaped pipelines, rollback, a versioned diff view | yes |
| 4 | The Ask/retrieval half of the P5 debug view, with replay using an edited prompt | yes |
| 5 | Compare-and-set prompt save (closes the parked rollback window and the half-done multi-key rollback) | yes, first |
| 6 | TLS on LAN with a self-signed certificate and a fingerprint check | yes |
| 7 | Scene-grouping nodes (`SceneRules` with `SceneTuning` overrides, cluster summary) | **gated**: only after wave 2 is merged into the canvas line |
| 8 | Cost estimate (tokens) and a Diagnostics line for EXPERIMENT calls | yes |
| 9 | Bounded-runner/watchdog leaks and other deferred minors | yes, folded into the tasks that touch the same files |

- Ruling S0: slice 2 takes in the MVP spec's slice 3 (the live interpreter) and the Ask half of slice 4 — the user's direction says the intent stack goes live — cost if wrong: a bigger slice. The interpreter only lands in shadow mode until the user flips it to live.
- Ruling S1: the order is CAS save → model and storage → interpreter → A/B, usage and Ask backend → API → TLS → editor → views; the scene nodes come last and are gated — the live seam is the riskiest change, so it lands early and gets reviewed alone; wave 2's `SceneRules` doesn't exist on this branch yet — cost if wrong: the scene nodes slip to slice 3 when wave 2 is late.
- Ruling S2: the per-node model override (A/B between models, `completeOn`) and the LLM-judge node are cut to slice 3 — A/B between prompts and shapes covers the user's stated need — cost if wrong: comparing models means changing the role binding between two runs by hand.

## 3. Headless JS tests (item 1)
The MVP JavaScript was never executed: only source scans ran.
- Ruling S3: the tests use **GraalJS** (`org.graalvm.polyglot:polyglot` + `js-community`, the newest 23.1.x, which runs on JDK 17 in interpreter mode), as a `testImplementation` in `:phone` only, in plain JUnit tests with no Robolectric and no WebView — it is the only JVM engine with full ES2020 and ES modules, and the bundle is modules. Rhino has no `import`/`export` and no `class`. QuickJS bindings need host native libraries. There is no Node on the machine — cost if wrong: about 40 MB of test-only jars in the Gradle cache. No APK change.
- **Structure:** logic lives in pure modules that never touch `document` or `window` (`graph.js` for the pipeline model operations, `keys.js` for keyboard navigation, `validate.js` for the client port check, `format.js`, `diffview.js`). The DOM modules (`dom.js`, `dag.js`, `editor.js`, `views.js`, `app.js`) are thin.
- **Harness** (`JsHarness`, Kotlin, test source set): it loads ES modules straight from `phone/src/main/assets/canvas/` and the JS test files from `phone/src/test/js/`. Each test file exports `tests = { name: fn }`, and the harness runs them and reports the failing names. It ships a **fake DOM** (`fake-dom.js`: `createElement`, `createElementNS`, `setAttribute`, `appendChild`, `textContent`, `addEventListener`, `dispatch`) so that `dom.js`, `dag.js` and `editor.js` can run too.
- **Parse check:** every `.js` asset is parsed as a module, so a syntax error anywhere, `views.js` and `app.js` included, fails the build.

## 4. DAG editor (item 1)
- **Palette:** one button per node type from `/api/node-types` (label, ports). You add a node by dragging a button onto the canvas, or by pressing Enter on it (it is added after the selected node). Ids are generated as `<short type>_<n>`, matching the id rule.
- **Connect:** drag from a node's out-port (the bottom handle) to a target node. While dragging, the client port check marks targets as allowed or refused. On a refused drop, the reason shows inline: "Facts from frames accepts Photo or JSON, not Preview". From a branching source, a small menu picks the `when` (the first unused branch is pre-selected).
- **Remove:** select a node or an edge (edges are focusable paths), then press Delete or use the inspector's Remove button. Removing a node removes its edges.
- **Layout:** Ruling S4: there are no hand-placed positions. After every edit the page calls `POST /api/validate` (debounced 250 ms), which returns the `FlowLayouts` layout and the structured problems. Nothing about position is stored, and the hash never changes because of layout — one source of truth for layout and validation, with no JS layout code — cost if wrong: if the user wants hand placement, a hash-excluded `ui` key can be added later.
- **Validation inline:** `PipelineValidator.problems(p)` returns `Problem(text, nodeId?, edge?)`. A node with a problem gets a red outline, a badge, and `aria-describedby` pointing at its messages. An edge with a problem is dashed red. The full list sits under the canvas, and each line focuses its node. The client checks port types only, for instant feedback. The server is the authority.
- **Keyboard:** Tab moves into the canvas. The arrow keys move between nodes by layer and column (`keys.js`). Enter selects. `c` starts connect mode from the selected node: the arrows pick a target, Enter connects, Esc cancels. Delete removes. `a` focuses the palette. Every action has a button equivalent. Focus is always visible.
- **One draft model:** the inspector, the JSON panel, Save and Freeze all read from the editor's single in-memory draft. This fixes MVP Task 10 minors 1–2. Freeze while there are unsaved changes saves first.
- **Pointer events** cover the mouse, touch and pen. On a 360 px phone the canvas scrolls inside its own container.

## 5. Interpreter and the intent stack (item 2)
**Interpretable shape** (`InterpretableShape.problems(p)`, in `:services:ai`):
- the pipeline is valid;
- before the extraction tier, it contains only `input.photos`, `ai.prompt` and `route.switch` nodes;
- every path from `input.photos` reaches exactly one `ai.vision_extract`;
- after an extract node there are only `gate.local_critic`, `ai.critic` (whose prompt must be Live) and `memory.write`;
- there are at most 3 `ai.prompt` nodes.

The default `intent-draft` pipeline passes.

- Ruling S5: the live save tail is always the live one. After the interpreter produces the facts and captions, today's code runs unchanged: the claim CAS, `saveExtraction`, the local trust check, the model critic with the active CRITIC prompt, embeddings and nudges. Trust and critic nodes in the pipeline only shape experiment previews, and the publish dialog says so — the save path keeps its invariants (claims, Forget fence, wipe generation) — cost if wrong: an experiment preview with no trust node can look more permissive than what live saves.
- **Seam:** in `KeyframeHandle.extract`, the line `ExtractSave.extract(ai.runner, photos, callClass)` becomes `LiveExtract.batch(photos, callClass)`. When no interpreter publish is active, it calls exactly that same line. When one is active in Live mode, `PipelineInterpreter` runs the pipeline's pre-save nodes with the **same canvas executors** (`RunContext.callClass` = the live call class; "what you test is what ships"). It merges the per-item extractions into one `ExtractedBatch` (facts de-duplicated by text, type and source-frame set) and returns it. The rest of `extract` is untouched.
- **Fallback:** Ruling S6: when the interpreter fails for a batch for any reason other than a retryable network or budget `ModelCallException` (those propagate exactly as live does today), the whole batch is extracted by the live `ExtractSave.extract`, and one `_batch` ERROR trace row per photo is written. When 10 of the last 30 interpreted photos of this publish fell back, the publish is **auto rolled back** (`rollbackReason = "auto: 10 of 30 fell back"`) — memories are never lost to an experiment, and a broken prompt cannot stay live for long — cost if wrong: a failing batch costs one extra call.
- **Cost:** Ruling S7: the intent call (and any other `ai.prompt` node) runs once per photo at the **live call class**, charged to the live `CallBudget`. A batch of 3 photos becomes about 3 intent calls plus 1–3 extract calls instead of 1. The publish dialog shows "about N× the model calls of Live extraction", computed from the shape — it's the honest price of the stack — cost if wrong: the live backlog grows on slow budgets. Shadow mode (below) measures it first.
- **Modes:** Ruling S8: an interpreter publish has a mode. In **Shadow**, live extraction stays as it is, and on 1 in 4 live batches (after the live save) the interpreter runs as `CallClass.EXPERIMENT`, saving trace rows only. Shadow pauses while Developer mode is off. In **Live**, the interpreter replaces the extraction step. Live stays live when Developer mode is off, just as published prompts do, and Diagnostics always shows "Live pipeline: <name> v<N> (canvas)". Going from Shadow to Live is a new publish of the same version — the same staging the MVP spec planned for slice 3, minus canary — cost if wrong: there is no canary percentage. Live is all batches at once, protected by S6.
- **Intent label:** Ruling S9: the canvas records the label in its own photo-derived table `canvas_live_trace` (the per-node outputs of every interpreted photo). The live label is also handed to `FrameIntentSink.record(frameId, label, confidence, atMs)`. The default is a no-op. **Wave B owns the `frame_meta` intent column and its writer**, and binds the sink when it lands. The canvas only **reads** that column, after wave B is merged (one swap in `FrameIntentReader`, listed in the device checklist) — this follows the user's ownership split, and a canvas branch cannot compile against a column that doesn't exist yet — cost if wrong: until wave B, the label lives only in canvas tables (it is wiped and forgotten like other canvas data).
- **The label as context:** as in the MVP, the switch passes the intent JSON downstream, and the extract executors put it in `{{context_sources}}` as the `upstream` data block.
- **The deterministic signals and the fusion node** (car Bluetooth, GPS, Activity Recognition, watch, user-defined rest-vs-motion sources) stay in wave B and slice 3. `RouteInput` and the node registry stay open for an `input.signals` node.

## 6. Freeze, publish, roll back, diff (item 3)
- **One publish stack:** Ruling S10: interpreter publishes are rows in the existing `canvas_publish` table, with new columns `kind` (`prompts` | `interpreter`), `mode` (`SHADOW` | `LIVE` | null) and `rollbackReason`. "Roll back" always undoes the newest active row of either kind. The active interpreter is the newest non-rolled-back interpreter row — one Roll back button with stack semantics the user already knows — cost if wrong: you can't roll back an older interpreter publish without first undoing a newer prompt publish.
- **Interpreter publish gates:**
  1. `InterpretableShape` has no problems;
  2. `TestBed.compatibility(VISION_EXTRACT, draft)` is clean for every draft extract prompt;
  3. every `ai.prompt` schema parses;
  4. there is a DONE run of this exact hash with an OK item at every AI node.

  The reason texts are fixed. Publishing writes no prompts: the interpreter reads the frozen version's JSON, so the per-intent prompts live in the version.
- **Live-shaped** pipelines keep the MVP prompt publish (`kind = prompts`). Its saves now go through compare-and-set (§9).
- **Diff view:** `PipelineDiff.of(a, b)` lists the nodes added, removed or changed (with the params that changed), the edges added or removed, and a `LineDiff` per prompt template or schema. It compares any two versions, or a version against the draft. The UI route is `#/p/<id>/diff?a=<v>&b=<v|draft>`.

## 7. A/B runs, usage and estimate (items 2 and 8)
- **A/B:** Ruling S11: an A/B run is two ordinary runs (A, then B) over the same photos, linked by `canvas_run.abGroup`, behind one estimate (the sum of both, still at most 200 nominal calls and 50 photos) and one confirm. B starts when A ends DONE. Cancel cancels the group. When B ends, the compare view opens — it reuses the runner, the storage and the compare view unchanged — cost if wrong: B runs a few minutes after A, so a provider-side change in between can skew it.
- **Intent summary:** for every `route.switch`, the run and compare views show the count per branch. For an A/B pair they also show the agreement rate (the share of photos whose branch is the same in A and B), and the score sums per node per run.
- **Usage:** `ExtractedBatch` gains additive `promptTokens`, `completionTokens` and `model` fields (from the extract call's `RoutedReply`; null by default), so extract items finally record tokens. Each run shows "calls X (estimated Y) · tokens in/out · median latency".
- **Estimate:** Ruling S12: next to calls, the estimate shows tokens: the per-node-type mean of prompt and completion tokens over that type's last 200 OK items. There is no money figure — the app has no pricing data — cost if wrong: the user converts tokens to cost by hand.
- **Diagnostics:** the calls line names `EXPERIMENT` ("EXPERIMENT N" per hour, from `BudgetStats.callsLastHour`) and stops folding it into OTHER. When an interpreter publish is active, the line "Live pipeline: …" with its mode and fallback count.

## 8. Ask debug and replay (item 4)
- **Views:** `#/asks` lists the newest 50 asks (time, question, a short answer, rating). `#/ask/<id>` shows:
  - the question, the stored answer and the cited sources;
  - **"Retrieved as of then"**: the hits with lexical, semantic, fused and final score, the mode used, and whether each was cited, plus the memory text and frame thumbnail;
  - the **composed prompt** (SYSTEM + ANSWER with the variables filled in), read-only;
  - an ANSWER template editor prefilled from the active prompt, with Check, then **Replay** (one model call, confirmed), which shows the new answer, its sources, its latency and its tokens.
- Ruling S13: retrieval for the view is **re-run** with `nowMs = ask.ts`, dropping memories created after the ask. The original retrieved set and its scores were never stored, and the view says "re-retrieved; may differ if settings or embeddings changed". Recording live retrieval traces would be a live-path change for a debug view. A replay is not persisted, runs as `CallClass.EXPERIMENT`, never saves an ask or a proposal, and is refused for redacted asks — cost if wrong: the scores shown can differ from what the ask really saw, after a settings change or a re-index.
- Ruling S14: this is a view, not `input.asks` / `ai.answer` nodes. The runner is built around photo items, and the user asked for "pick an ask … replay" — cost if wrong: no bulk replay of N asks until slice 3.

## 9. Compare-and-set prompt save (item 5)
- `PromptDao.casSave(id, expectTemplate, expectSchema, template, schema, note, testedOk, now, keepVersions): Int?` runs in one transaction. It reads the active version, compares its text **exactly** (template and schema), and then appends or overwrites as `save` does. It returns null on a mismatch.
- `PromptRepository.saveAllIfUnchanged(edits: List<CasEdit>): CasResult` runs every key in **one** transaction, so if any key mismatches, nothing is written.
- `CanvasPublisher.publish` (prompts) and `rollback` use it. This closes the parked window (Task 7 b) and the half-done multi-key rollback.
- Ruling S15: the comparison is on exact text inside the transaction, not on version number — with versioning off, the version number doesn't change when the text does — cost if wrong: nothing.

## 10. Secure LAN (item 6)
- Ruling S16: when LAN is on, the server speaks **HTTPS only**, with a self-signed EC P-256 certificate generated in the AndroidKeyStore (alias `pc_canvas_tls`, valid for 825 days, rotatable). Loopback with LAN off stays HTTP, because loopback cannot be sniffed. Plain-HTTP LAN is removed: if the keystore TLS fails on the phone, the LAN switch is disabled with "Secure LAN isn't available on this phone". The app shows the certificate's SHA-256 fingerprint (8 groups of 4 hex). The laptop browser warns once, and the user compares the fingerprint in the browser's certificate details. The app's WebView **pins** it: `onReceivedSslError` proceeds only when the presented certificate's SHA-256 equals ours — TLS defeats passive sniffing even when the fingerprint is never checked, and active interception when it is — cost if wrong: a browser warning on the first visit from each laptop. If AndroidKeyStore keys can't serve TLS on this phone, LAN is unavailable until a software-key fallback is added.
- The Origin rule becomes `<scheme>://<Host>`. The WebView URL is `https://127.0.0.1:<port>/` while LAN is on.
- **Pairing hardening** (final-review M4): Ruling S17: after a lockout, no new code is minted automatically: the user taps "New code". Lockouts escalate (30 s, 2 min, 10 min) — this removes the endless 5-guesses-per-30-s window — cost if wrong: one extra tap after a typo streak.

## 11. Storage (Room, next free version, additive)
- Ruling S18: the migration takes the **next free version at merge time**. Canvas MVP is v3, wave 2 is v4, wave B is v5, so expect v6. While slice 2 is built on a branch without wave 2 or wave B, use that branch's next number and renumber at merge. Never edit an existing migration — cost if wrong: renaming one migration and regenerating one schema JSON.
- Changes:
  - `canvas_publish` gets `kind TEXT NOT NULL DEFAULT 'prompts'`, `mode TEXT NULL` and `rollbackReason TEXT NULL`;
  - `canvas_run` gets `abGroup TEXT NULL`;
  - a new table `canvas_live_trace` (**publishId** INTEGER, **frameId** TEXT, **nodeId** TEXT, mode TEXT, status TEXT, branch TEXT NULL, outputJson TEXT NULL, error TEXT NULL, at INTEGER), with indexes on `frameId` and `at`.
- Ruling S19: trace retention is 7 days or 5,000 rows, whichever is smaller, pruned at most once an hour from the interpreter path — it's debug data — cost if wrong: less history for the Live view.
- Delete all and Forget also clear `canvas_live_trace`, in the same transactions as the other canvas tables. It is never exported.

## 12. Scene nodes (item 7, gated)
- Dispatch these only when `SceneRules`, `SceneTuning` and `ClusterContract` from wave 2 exist on the canvas branch. Otherwise skip the task and record it in the ledger.
- `scene.group` takes PHOTOS and outputs JSON. It makes 0 calls. Its params are the `SceneTuning` overrides (only the listed fields, range-checked). It needs `frame_vector` rows. Per photo it outputs `{route, cluster, salience:{…}, nearDupOf?}`, and the cluster ids are computed over the run's photos in time order.
- `ai.cluster_summary` takes JSON from `scene.group` and outputs EXTRACTION. It uses the CLUSTER_SUMMARY prompt (Live or Draft), makes one call per ≤ 3 clusters and ≤ 6 photos, and gives each member item its cluster's summary.
- Both are preview-only. Ruling S20: they are not interpretable in slice 2, so publish is blocked with a fixed reason — wave 2's cluster save path has its own claims — cost if wrong: scene tuning goes live only through wave 2's own settings.

## 13. Open for the user
Each ruling can be overturned. Its cost is next to it above.
1. S0 / S1: the interpreter and the Ask half move into slice 2; the order; the scene nodes gated on wave 2.
2. S2: no per-model A/B or LLM judge in slice 2.
3. S3: GraalJS as a test-only dependency for the JS tests.
4. S4: auto-layout only; no hand-placed node positions.
5. S5: the live save tail (trust, critic, save) always applies to interpreted pipelines.
6. S6: per-batch fallback to Live extraction, and auto-rollback at 10 of 30.
7. S7: intent calls are charged to the live budget at the live call class.
8. S8: Shadow (1 in 4 batches, EXPERIMENT class) then Live, with no canary; Live stays on when Developer mode is off.
9. S9: the intent label goes to canvas tables plus a sink that wave B implements; the `frame_meta` intent column is wave B's.
10. S10: one Roll back stack for prompt and interpreter publishes.
11. S11 / S12: A/B is two sequential runs; the estimate shows tokens, not money.
12. S13 / S14: Ask debug re-retrieves as of the ask's time; replay is single, not persisted; it is a view, not nodes.
13. S16 / S17: HTTPS-only LAN with a pinned WebView and a manual fingerprint check in browsers; no plaintext LAN; escalating pairing lockouts.
14. S18 / S19: Room at the next free version; 7-day trace retention.
15. S20: scene nodes are preview-only.
16. Still open from the handoff §10.0, not ruled here: how the deterministic signals and the image intent are fused, and whether capture stops while flying.
