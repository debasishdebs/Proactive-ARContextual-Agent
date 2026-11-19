# Derived-signal try-it over real data (v1.2 row 2) — design

Roadmap: `docs/superpowers/plans/2026-11-16-roadmap-v1.1-v1.3.md` v1.2 row 2. Status: DESIGN (Opus, 2026-11-17). Implement with Sonnet after the user answers the open questions (section 10).
Depends on: v1.1 row 3 (Forget lifecycle; this design uses its read leases) landed first. No protocol, trace schema, Room schema or plan key change.

## 1. Current architecture (what exists, file:line)

Paths are relative to `android-companion/`.

- **A derived signal** is a user expression `d_<name>` over the seven per-second IMU features (+ `ctx`, `ctxAgeS`): `DerivedSignal(name, graph)` and `SignalConfig` (max 8)
  `services/ai/.../canvas/plan/DerivedSignals.kt:10-15`. `SignalConfigs.provider/current()/using()` `:18-27` is the config every compile reads. `DerivedSignals.validate` `:44-56`
  (guard check, falls back to value role when the only problem is "not bool"; no derived-in-derived). `DerivedSignals.expand` `:64-122` INLINES the definition into any guard/value
  graph that names it (`dv_<name>_<i>` steps), so the glasses only see ordinary ops; nodes using a derived signal stay SHADOW/TRIAL (`:32`).
- **Storage**: `SignalConfigStore` (prefs, plus a 16-entry history for frozen-version replay) `phone/.../ui/settings/capture/SignalConfigStore.kt:40-57`, `saveDerived` `:69-77`
  (size guard `MAX_DEFINITION_BYTES` `:115`, then `validate`, then `persist`). Delete all wipes it (`:99-104`).
- **Editing today**: the PHONE Settings screen only (Compose JSON text editor) `SignalSettings.kt:57-64,120-161`. The canvas web UI has NO derived-signal editor: no canvas JS calls
  `/api/signals`, and the canvas signal pickers list only the catalogue (`phone/src/main/assets/canvas/exprgraph.js:16-19`), so a canvas guard can name `d_x` only via the JSON tab.
- **API**: `SignalRoutes` `phone/.../canvas/api/SignalRoutes.kt:20-59`: `GET /api/signals`, `POST /api/signals/enabled`, `PUT|DELETE /api/signals/derived/<name>`
  (not Developer-gated). Registered as an `ApiExtra` in `phone/.../canvas/CanvasExtras.kt:78-87`.
- **The existing try-it (3c X8, synthetic values)**: `POST /api/expr/try` `ExprRoutes.kt:16-28` -> `ExprTry.run` `services/ai/.../canvas/plan/ExprTry.kt:28-107`: lowers ONE
  program with `ExprLowering.lowerAll`, runs it through the glasses' own `ExprRunner` (`core/capture/.../plan/ExprRunner.kt:143,146,232`) over user-given values (<=8 signals,
  <=300 seconds, null = missing second), probes only the LAST second, returns `{ok, steps, output, verdict|applied, seconds, windowStateBytes, windowTerms}`. Reads/writes nothing.
  Canvas side: `exprgraph.js:460-485` (`signalsOf`, `tryBody`, `tryLines`), panel `exprview.js:210-224`, wired in `floweditor.js:105`. It is in `READ_ONLY_POSTS` `CanvasApi.kt:58`
  (30 s deadline, `CanvasApi.kt:80-83`).
- **Real per-second features on the phone**: only `moment_sensor` rows (`MomentSensorEntity`, `services/memory/.../db/MomentEntities.kt:76`), computed from IMPORTED IMU traces by
  `MomentSensors` `services/orchestration/.../canvas/moment/MomentSensors.kt:21-110` via `TraceSecondFeatures.compute` (`core/capture/.../trace/TraceSecondFeatures.kt:27-57`,
  the glasses' `MotionClassifier`). Read query `sensorBetween(fromSec, toSec)` `services/memory/.../db/DerivedStaleDao.kt:43`. Moments: `MomentDao.momentsBetween/partsOf`
  `MomentDao.kt:17-19` (FRAME parts link photos). Forget: `moment_sensor` is deleted with its Moment, its trace, every time-range Forget and Delete all (`DeletionGraph.kt:45`).
- **`POST ask/eval`** `CanvasApi.kt:158,496-506` -> `CanvasBackendImpl.kt:732-760`: the Developer-mode pattern (`DeveloperModeStore...enabled` else `SecurityException` -> 403
  `forbidden`), the wipe generation read before the work, `AskRedacted` -> 409. It is NOT read-only (it commits an ask row), so it has no deadline. The try-it is the opposite: read-only.
- **DerivePass** `services/orchestration/.../canvas/moment/DerivePass.kt:174-224` is NOT about derived signals: it writes Moment/Scene SUMMARY/CAPTION text through a model
  (`DeriveGate` `:70-76`, read lease `Kind.DERIVE` `:196-202`). Relevant only as the pattern: a lease over every input ref, `DataOpGate.shared.lease` (`DataOpGate.kt:132`),
  revocation by Forget (`Leases.kt:17`, `Lease.verify` `Leases.kt:84-100`). The try-it writes nothing, so it only VERIFIES the lease before showing.
- **Canvas run/save paths**: `runs/estimate` -> `confirmToken` -> `POST runs` -> `POST runs/:id/save` (`CanvasApi.kt:150-165`, `CanvasBackendImpl.kt:612-662,881`) create run rows and
  hold `CANVAS_RUN`/`CANVAS_SAVE` leases. The try-it uses NONE of them (no run row, no token, no save).
- **Privacy gates**: `ProvenanceGate` `services/orchestration/.../provenance/ProvenanceGate.kt:17-32` (text built from photos; `goneFrames` `:110`); `ZoneFence`
  `services/memory/.../place/ZoneFence.kt` (Place Guide side effects). Thermal: `PhoneThermal`/`ThermalStatusProvider` `services/orchestration/.../work/budget/PhonePowerPolicy.kt:3-7`
  (the canvas replay has `thermalSevere` unwired: `CanvasExtras.kt:39`).
- **Plan hash**: the built-in Default capture compiles to `f5782e0d8610` (`tools/M1-RUN.md:25`). Only compile+publish (`PlanRoutes`, `CapturePlanPublisher`) produce a plan hash.
- **JS budget**: `CanvasAssetsTest.jsUnderSizeBudget` `phone/src/test/.../canvas/CanvasAssetsTest.kt:118-121`, budget 4708; counted today (non-vendor `*.js`, `lines()`): **4705** (3 spare).

## 2. User-visible behaviour

1. Canvas nav gets **Signals** (`#/signals`): the catalogue (enabled flags, read-only here) and the derived list from `GET /api/signals`. New / Edit opens the existing boxed expression
   editor (`mountExprEditor`) on the definition graph, with a Name field, **Save** (`PUT /api/signals/derived/<name>`) and **Delete**.
2. Under the editor: the existing slider try-it (synthetic values, unchanged) AND a new **Try it on recent data** panel: a window choice (last 5 / 10 / 30 min of recorded sensor
   seconds, default 10) and **Run**. The phone evaluates the UNSAVED graph in the editor over the newest real `moment_sensor` seconds.
3. Output: a one-line summary (`min 0.12 · max 3.4 · mean 0.9` or `true 23 % of 600 s`), the state counts (NOT_READY while windows warm, MISSING seconds), a small SVG strip
   (<=300 points, NUM line or BOOL bars), and a list of up to 50 photo Moments in the window with the value at that second (thumbnails through the existing `frames/:id/thumb` route).
4. Nothing is saved: the definition, `SignalConfigStore` (state and history), any pipeline, plan, run, publish record and the plan hash are untouched. Closing the editor discards it.
5. No sensor data in range (no trace imported): "No recorded motion data yet. Import a trace (Developer mode) to try on real data." The slider try-it still works.

## 3. Data it may read (privacy)

- Reads ONLY: the posted graph; the current `SignalConfig.disabled` set (a disabled catalogue signal is a problem, as at Save); `moment_sensor` rows in the window (7 numbers, motion
  state name, `context` name, trace id); Moment ids and their FRAME part ids in the window. Never photo bytes, paths, memory text, marks, captions or any model output.
- No model call, no network, no glasses message: the route has no `LanguageModel`, router, hub or plan-publisher dependency (structural; tested). So "no photo leaves the phone beyond
  normal evaluation" holds trivially: normal evaluation of a derived signal sends no photo either (it is arithmetic on IMU features on the glasses or in replay).
- **Forget**: reads run inside one DB transaction under a read lease `Leases.Kind.TRY_IT` (new enum value) extended with `Ref(TRACE, id)` for every trace used, `Ref(MOMENT, id)` for every
  Moment listed and `Ref(PHOTO, id)` for every frame id listed. Before the response is built: `lease.verify(db, checkLive = true)` and the wipe generation is unchanged, else 409 `forgotten`
  and nothing is shown. A delete in progress (`LeaseBlocked`) -> 409 `busy`. Frame ids are additionally filtered through `ProvenanceGate.goneFrames` (tombstoned/expired-and-gone dropped).
- **ProvenanceGate**: no photo-derived TEXT is read or shown, so the text choke point has nothing to gate; only `goneFrames` is used for the ids. `ProvenanceChokePointTest` must stay green.
- **ZoneFence**: not consulted (nothing is sent or kept; numbers are not photo-derived). Whether seconds recorded inside a privacy zone are hidden is open question Q3.
- **Developer mode vs normal**: proposed available in both (the same per-second rows already back `GET /api/moments` `sensor` summaries, and the rows exist only after a Developer
  trace import). If the user rules Developer-only, the route answers 403 `developer_mode_off` like `ask/eval`. Logs: counts and outcome only (`PcLog.i("signaltry", ...)`), never the graph.

## 4. Limits

| Limit | Value | Where |
|---|---|---|
| Body | `ExprTry.MAX_BYTES` 64 KiB (definition also <= `MAX_DEFINITION_BYTES`) | route, 400 `too_large` |
| Window | `seconds` 60..1800 (hard cap `MAX_SECONDS = 1800` evaluated seconds) | 400 `bad_value` |
| Look-back | the window ends at the newest `moment_sensor` second within the last 7 days (or `endS` if given, also within 7 days) | empty result otherwise |
| Points returned | `MAX_POINTS = 300` (buckets of `ceil(seconds/300)` s: last state, NUM min/max) | `points`, `truncated` |
| Photo Moments | `MAX_MOMENTS = 50` newest, `MAX_FRAMES = 50` frame ids in total | `moments`, `truncated` |
| Rate | one try at a time (Mutex `tryLock`, else 409 `busy`); >= 2 s between tries per session (429 `too_soon`) | route |
| Thermal | refuse at `PhoneThermal.SEVERE` or worse (503 `busy`, "The phone is hot.") | route |
| Deadline | the 30 s read-only deadline (add `/api/signals/try` to `READ_ONLY_POSTS`) | `CanvasApi.kt:58` |
| Cost | 0 model calls, 0 network; CPU <= 1800 ticks x cost 64; <= 1800 rows + <= 1800 Moments read | budget note |

## 5. Route, auth, result shape

`POST /api/signals/try`, handled in `SignalRoutes.handle` before its 405 fallthrough (same `ApiExtra`). Auth, Host/Origin policy and body limit are the router's (bearer token,
like every `/api`). Body: `{name?: string, graph: <expr graph>, seconds?: int = 600, endS?: int}`. `name` (optional) only makes the count rule match Save; the graph is never stored.

Result (200, additive; field names match `ExprTry` where they overlap):
```json
{ "ok": true, "role": "guard|value", "source": "moment_sensor",
  "window": { "fromS": 1793890000, "toS": 1793890599, "seconds": 600, "covered": 571, "missing": 29 },
  "summary": { "num": { "min": 0.1, "max": 3.4, "mean": 0.9 } | null, "trueFrac": 0.23 | null,
               "states": { "NUM": 571, "BOOL": 0, "NOT_READY": 0, "ERROR": 0, "DIV0": 0, "UNAVAILABLE": 0, "MISSING": 29 } },
  "points": [ { "s": 1793890001, "state": "NUM", "value": 0.4, "min": 0.2, "max": 0.6 } ],
  "moments": [ { "id": "6ae1c2f0", "s": 1793890300, "frameIds": ["…v2"], "result": { "state": "BOOL", "value": true } } ],
  "truncated": false, "windowStateBytes": 120, "windowTerms": 1 }
```
`{ok:false, problems:[...]}` for a definition that does not validate (the same texts as Save). `{ok:true, window:{seconds:0,...}, reason:"no_sensor_data", points:[], moments:[]}` when empty.

Evaluation: new pure `ExprSeries.run(graph, role, rows, cfg, windowMinValidPct)` in `services/ai/.../canvas/plan/ExprSeries.kt`, sharing `ExprTry`'s lowering and `ExprRunner` (no
second evaluator): one tick per second oldest first; a second without a row is NaN for every feature (= missing second, the warming/tolerance rules of `ExprTry`); `ctx` = row
`context` (REST when null, flagged), `ctxAgeS` = seconds since the last `context` change seen in the window, counted from the window start until the first change (the code and `ExprSeriesTest` do this; the spec was wrong); probe every second.

## 6. Canvas JS budget impact (counted)

| File | Change | Lines |
|---|---|---|
| `signalsview.js` (new) | list, name field, mount editor, Save/Delete, real-data panel host | ~70 |
| `exprgraph.js` | `realBody`, `realSummary`, `stripPoints` (pure, testable in `tools/canvas-browser-check`) | ~14 |
| `exprview.js` | `opts.tryReal` panel: window select, Run, summary, SVG strip, Moment list | ~16 |
| `app.js` | import, route, nav link | 3 |
| total | | **~103** -> budget 4708 -> **4808** (set to exactly the new total, per the test's convention) |

## 7. Failure modes

| Case | Answer |
|---|---|
| bad JSON / not an object / over 64 KiB | 400 `bad_request` / `too_large` (fixed texts, nothing echoed) |
| definition invalid, disabled signal, derived-in-derived | 200 `{ok:false, problems}` |
| no sensor rows in 7 days | 200 `reason:"no_sensor_data"` |
| Forget / Delete all during the read or before the reply | 409 `forgotten` ("Some of this data was forgotten while it ran. Nothing is shown.") |
| a delete is running (lease blocked) | 409 `busy` |
| a try already running / too soon | 409 `busy` / 429 `too_soon` |
| thermal SEVERE+, deadline hit | 503 `busy` |
| Developer mode off (only if Q2 rules Developer-only) | 403 `developer_mode_off` |
| evaluator throws (OOM, StackOverflow) | 500 `internal`, process unaffected (route's `catch Throwable`) |

## 8. Tests

- `ExprSeriesTest` (services/ai): agrees with `ExprTry` on the last second for the same series (constant, ramp, gaps); NOT_READY while a window warms; a missing second counts as
  MISSING and respects tolerance; guard -> BOOL/trueFrac, value -> NUM min/max/mean; bucketing to 300 points; `ctxAgeS` from context changes; 1800-second cap.
- `SignalTryRoutesTest` (phone, in-memory Room): rows in/out of window; Moments with FRAME parts listed and capped at 50/50; tombstoned frame dropped; Forget of the trace between
  read and reply -> 409 and no body; Delete all -> 409; 429 on a second try within 2 s; thermal SEVERE -> 503; bad bodies -> typed 400s; route is in `READ_ONLY_POSTS`.
- `SignalTryWritesNothingTest`: after a try, `SignalConfigStore` state and history prefs, canvas run/publish tables, `moment_sensor` row count are byte-identical; Default capture
  still compiles to `f5782e0d8610`; no `LanguageModel`/hub fake was called (the route cannot reach them).
- `CanvasAssetsTest`: budget raised to the exact new total; `signalsview.js` posts `/api/signals/try` and never `PUT`s from the try path. `tools/canvas-browser-check`: Signals page
  drive (edit, try, see summary, close, the list is unchanged).
- `ProvenanceChokePointTest`, `DeletionGraph` coverage test and the lease-kind exhaustiveness tests updated for `Kind.TRY_IT`.

## 9. Tasks (Sonnet-sized; one worktree each, Codex reviews the set once)

- **T1 `ExprSeries` (pure)** — `services/ai/.../canvas/plan/ExprSeries.kt`, `ExprSeriesTest.kt`; extract the shared lowering/runner setup from `ExprTry` into a private helper both
  call. Accept: `ExprSeriesTest` green; `ExprTry`'s existing tests (`TryItMatchesRunnerTest`, `TryItToleranceTest`, `TryItBoundedInputTest`) unchanged and green.
- **T2 lease kind + DAO read** — `Leases.Kind.TRY_IT`; `DerivedStaleDao.latestSensorSecond(fromSec)` (`SELECT MAX(phoneSecond) ...`, read-only, no Room version bump). Accept:
  Room schema JSON unchanged; lease tests green.
- **T3 backend reader** — `services/orchestration/.../canvas/signal/SignalTryReader.kt`: lease, one-transaction snapshot (rows, Moments, FRAME parts, trace ids), `goneFrames`,
  `verify` before returning. Accept: Forget/Delete-all race tests -> `Forgotten`; caps enforced.
- **T4 route** — `SignalRoutes.kt` `POST /api/signals/try` (body parse, limits, Mutex, 2 s rate, thermal provider injected, result JSON), `CanvasApi.kt:58` `READ_ONLY_POSTS`,
  wiring in `CanvasExtras.kt` (`ThermalStatusProvider` from `PhonePowerPolicy`). Accept: `SignalTryRoutesTest`, `SignalTryWritesNothingTest` green.
- **T5 canvas JS** — `signalsview.js`, `exprgraph.js`, `exprview.js`, `app.js`, `style.css` (strip only); budget in `CanvasAssetsTest.kt:120` set to the exact new total
  with the usual comment. Accept: `CanvasAssetsTest` green, `tools/canvas-browser-check/run.sh` drive passes, total JS <= 4808.
- **T6 docs + verification** — HANDOFF entry, roadmap row 2 -> DONE after review; logcat check on the device that a try logs one `signaltry` line with counts only. Accept: device try
  over an imported trace shows a summary; plan `configHash=f5782e0d8610` after the try.

## 10. Open questions (user rulings)

- **Q1 Canvas editor scope**: the canvas has no derived-signal editor today. Add the Signals page (this design, ~103 JS lines), or only add the real-data try to the PHONE Settings
  editor (`SignalSettings.kt`, 0 JS lines, Compose instead)? Also: should the canvas signal pickers list `d_<name>` (today JSON-only)? Proposed: Signals page yes, pickers later.
- **Q2 Developer mode**: available in normal mode (proposed) or Developer-only like `ask/eval` and traces?
- **Q3 Privacy zones**: hide seconds recorded while the zone fence was held (needs zone history, which `moment_sensor` does not keep), or show them (numbers only; proposed)?
- **Q4 Data source**: `moment_sensor` exists only for imported traces. Is "recent real data" = newest imported trace coverage acceptable for v1.2, or do you want live glasses
  per-second features kept on the phone (a protocol/storage change, its own design, v1.3+)?
- **Q5 Window caps**: 5/10/30 min choices and the 1800-second, 50-Moment, 50-frame caps OK? Thumbnails in the Moment list (existing route) or ids/times only?
- **Q6 Value vs guard**: when a definition is a number, also show what a value row would get after clamping (`applied`, as `ExprTry` does) for a chosen field, or the raw number only (proposed)?
