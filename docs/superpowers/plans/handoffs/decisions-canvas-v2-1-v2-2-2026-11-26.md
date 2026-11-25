# Canvas v2.1 (live end-to-end test) and v2.2 (model nodes): what was built, flags, device checks, questions (2026-11-26)

Branch `feat/v101-ask-eval`, `android-companion/`. Design: `docs/superpowers/plans/2026-11-24-canvas-v2-1-live-e2e-and-v2-2-model-nodes-design.md`. Ships as the 1.9.x line.

Binding rules kept: every switch is a Developer flag defaulting OFF (also OFF when prefs cannot be read); dry-run only (nothing sends, delivers, publishes or writes user data); Offline only means no remote model call;
no Room schema change (context.db v18 and places.db v3 untouched); zone guard untouched; no new stored value, cache or file, so Forget and Delete all needed no new code (the Forget inventory and ratchet tests ran green
in the full suites, unchanged).

**Honest status: building blocks, not finished screens.** The pure logic, the node types, the executors and the flags are built and unit tested. The Test run screens, the live tap driver and the glasses lane probes are
parked (list at the end). Nothing was run on a phone, an emulator or the glasses: every item under "device check pending" is unverified on a device.

## Flags (Settings > Diagnostics > Developer, both default OFF; they also need Developer mode)

| Flag (`DeveloperFlags`) | What it turns on |
|---|---|
| `canvasModelNodes` | The v2.2 node types `model.classify`, `model.route`, `rule.table` exist for the canvas (palette, validator, runner). OFF: they are "unknown type", a graph holding one is refused, and their executors cannot be reached. |
| `canvasTestRun` | Read by `TestRunPolicy` (the Test run admission). No screen or route uses it yet, so it changes nothing visible. |

## What was built, per work package (one commit each)

| Commit | WP | What |
|---|---|---|
| `68d54b8d` | v2.1 P0 | `WindowFramePicker` (pure, deterministic: modes `even`, `dwell`, `all_dwell_first`, 50 cap, seeded hash buckets, excludes tombstoned / photo-less / near-dup / retention-thinned frames and says how many), `TestRunSource` + `TestRunPolicy` (fail-closed admission: flag, Developer, busy, Offline only vs an online vision node, window length, count, tap bounds, zone held or unknown, `py.fn` refused for a tap). The flag `canvasTestRun`. |
| `fb3ce10a` | v2.1 P2 | `NodeInspector`: per-node badges (OK / error / skipped, branch counts, median latency) and paged per-node rows with the upstream value as "input", output cut at 4 KB on a character boundary. Pure over the existing `canvas_run_item` rows, so nothing new is stored. |
| `ba902f35` | v2.1 P1 | `E2eJoin`: KEEP to stored frame match (1.5 s tolerance, one frame per keep, `not_stored`), the counterfactual "never captured" count and sentence, and the E2E record (`replayClass = "E2E"`, 256 KB cap, at most 600 listed keeps). The record holds NO frame id (only offsets and status words), so a frame Forget leaves nothing behind in it and the trace tombstone is its only lifecycle. |
| `42807c4a` | v2.1 P3 | `RecordedOutputsExecutor` (REPLAYED level: input key `sha256(type, canonical params, promptVersion, model label, upstream output)`; hit = recorded output with `calls = 0`; miss = `not_recorded` or a live call; a pruned/Forgotten source = `replay_source_gone`, never a live call), `DeterminismReport` ("47/50 items identical; 3 drifted at node X"), and an optional `seed` on `ModelRequest` forwarded by the Ollama and OpenAI-compatible providers when set (every live caller leaves it null). |
| `e864a2c5` | v2.1 P4 | `LiveTapBounds` (pure): duration (max 15 min, default 5), frames (50), nominal calls (200), photos sent (default 0), privacy flip, zone entered, hub stopped, page heartbeat 30 s, Delete all, Forget overlap, user stop, unreadable state. First stop reason sticks. Under Offline only no photo may be sent. |
| `fd76dc34` | v2.2 types | `ModelNodeTypes`: `model.classify`, `model.route` (labels 2..12 plus `none`, threshold, `backend` auto/online/offline, `onUnavailable` error/skip, `maxLatencyMs`), `rule.table` (first matching row wins, ops `== != > >= < <= in contains exists`, dotted field path, no second expression language). `NodeTypes.modelNodesEnabled` switch (OFF, fail closed). `ClassifySpec` (schema, instruction that forbids naming people, normalisation: unknown label or low confidence = `none`; only `label` and `confidence` ever leave the node). `ModelNodeBackend` (node choice intersected with privacy: `offline_only`, `offline_only_no_local_model`, `backend_mismatch`, `not_bound`). `ResolverRole` gained `CANVAS_TEXT/VISION/EMBED` (declared, no factory). |
| `b22cdc20` | v2.2 executors | `ModelClassifyExecutor` (classify and route, one call per item through `ctx.model`, the router choke point: fallbacks, data saver, liveness, EXPERIMENT class), `RuleTableExecutor`, `ModelNodePreflight` (estimate refusal, layer one) and the call-time check (layer two), `RunContext.modelPrivacy` / `boundBackend` (defaults fail closed: OFFLINE_ONLY / unbound), phone wiring in `CanvasBackendImpl`. Two phone tests that pin the composed executor set were updated to include the three new executors (they cannot be reached with the flag OFF). |
| `64512bfc` | switches | The two Developer rows in Diagnostics (placed below the sensor-recording rows so `SensorRecSettingTest` still sees its rows). |

Test counts (this branch, after the last WP): orchestration 3077 (4 skipped), ai 1095 (8 skipped), phone 3225, all 0 failures. New tests: `WindowFramePickerTest` 10, `NodeInspectorTest` 4, `E2eJoinTest` 4,
`RecordedOutputsTest` 7, `SeedForwardTest` 1, `LiveTapBoundsTest` 7, `ModelNodesTest` 8, `ModelNodeExecutorsTest` 10. The final `verify_final.sh` result is in the report that came with this file.

## Parked, and why (nothing invented)

- **Test run screens and routes** (`runNewView` source switch, `POST e2e`, `runs/tap`, `runs/:id/stop`, `GET runs/:id/items`, `inspectview.js` overlay, per-node diff): Compose/WebView layout and route wiring were not built.
  The logic they need is built (picker, policy, inspector rows, E2E record). Device check pending: the browser page layout, the phone route auth and 409 `busy`. No JS test runner exists under `assets/canvas`, so no JS was written.
- **Glasses-lane probes**: the design puts a probe sink inside `CounterfactualReplay`/`ExprRunner`. Ruling R4 is open (zone status of Collect traces is UNKNOWN), and the safe default is counts only, which the E2E record
  already holds. Per-second series were NOT built and the simulator was NOT touched.
- **Live tap driver**: needs the `runItems` refactor of `CanvasRunner`'s fenced commit loop (the design's biggest risk) and real glasses frames, a zone entry, a privacy flip and screen-off. Only the bounds are built.
- **SEEDED plumbing**: the seed exists on `ModelRequest` and two providers, but executors do not set it yet (llama.cpp sampler seed and `py.fn` payload seed also not done). REPLAYED is exact; SEEDED is not wired.
- **Wiring `RecordedOutputsExecutor` into a run** (the `replay: {fromRunId, onMiss}` body) and a `RecordedSource` over the DAO: not built.
- **`model.text`, `model.vision`, `embed.match`, `rule.when`, `rule.all/any/not`, `rule.debounce/rate`**: not registered. `rule.when` needs a field-access term in `ExprProgram`, which the glasses share (too risky
  without a device check); the combinators need a fan-in meaning the item flow does not have; `embed.match` rides 1.4.4 and has no executor; `model.text/vision` would duplicate `ai.prompt`.
- **Resolver factories** for `CANVAS_*` roles: the executors use the router, so there is one choke point already; wrapping it as a resolver factory adds nothing until the offline VLM (1.4.5) exists.
- **Offline vision backend**: blocked on 1.4.5 as in the design (section 2.4). Offline text through llama.cpp works only if the bound summarise endpoint is the local model.
- **Estimate-time Offline only check** covers photo estimates only; Moment/Scene/watch estimates rely on the call-time check and the router.
- **v2.3 detection nodes**: out of scope here (needs only; separate design).

## How to test on a device (once an install is allowed; none was done)

1. Settings > Diagnostics > Developer: turn on Developer mode, then "Canvas model nodes".
2. Open the canvas page. The palette should now list Classify, Route by model and Rule table. Turn the switch off again: the nodes vanish and a saved graph holding one is refused ("unknown type").
3. Build photos -> Classify (labels `indoor, outdoor`) -> Rule table; run it over 3 photos. Check: one provider call per photo, branch `indoor`/`outdoor`/`none`, nothing about people in the output, run rows deleted when a photo is Forgotten.
4. Turn Offline only on with an online endpoint bound: the estimate must refuse with `offline_only`; with `onUnavailable = skip` the run must take the `none` branch and make no call.
5. (Later, when screens exist) the Test run window source, inspector overlay and live tap: not testable yet.

## Owner questions (yes/no, with the safe default I used)

1. R4: may per-second glasses-lane probes from Developer Collect traces be shown? Default used: NO, counts only.
2. R1/R2: may Test runs send photos online after the typed confirmation, and should a live tap default to 0 photos sent? Default used: tap = 0 (offline only); photo upload counts against the data saver as its own counter is NOT built yet.
3. R3: do taps share the `KEEP_RUNS = 20` prune with all runs? Default used: yes (nothing special built).
4. R5: keep the canvas out of the Room migration (Option A: run source label lives in the E2E record)? Default used: yes, no schema change.
5. R6: is a people count in public places acceptable, and may person boxes stay in run rows until Forget or prune? Default used: not built (v2.3).
6. R8: show tokens and bytes only, no currency? Default used: yes (nothing shows a price).
7. R9: one Developer Collect session at a Sikkim site as the first real E2E fixture (kept on the phone, never in git)? Default used: not requested.
8. R10: keep the canvas line (1.9.x) separate from the Nexus v2.0 row? Default used: yes.
9. New: is `none` below the confidence threshold the right behaviour for Classify, or should a below-threshold item go to its own `unsure` branch? Default used: `none`.
10. New: should `model.route` stay a near-twin of `model.classify` (it only adds the item to the output), or be merged into one node? Default used: kept both, per the design table.
