# Handoff: 3c phase E2b, editor support, phone try-it, differential fuzz (v0.9.4), 2026-10-25

Branch `feat/expr-e2b` (worktree ~/Desktop/rokid/pca-v094d), from v0.9.4 tip 374cbf42. No PROTOCOL_VERSION, TRACE_SCHEMA or Room change; schema-1/2 plan hashes untouched (the golden hash tests are in the green runs); LIVE still refused.

## Commits
- 9f32a96a X8: phone-evaluated try-it
- d402e049 editor support for window terms and expr.value
- 851c23b3, 85fed545 X9: differential fuzz, golden vectors, replay parity fuzz, validator limits fuzz
- (this file)

## Built
1. **Editor (JS).** `win` is an op of the boxed editor (signal = the seven features only, agg max|mean|frac, seconds 1-300, threshold `t` only for frac; changing agg adds or drops `t`). `exprview` no longer indexes `OPS[n.op]` directly (`X.opOf`), so a `win` or an unknown op authored in the JSON view does not throw when selected. A palette-added `expr.value` starts with `clamp(2, 1..4)` (valid for the default target REST capPerMin; `defaultGraphFor(type)` in graph.js); row and field are the generated selects of E2a (the target picker). The editor shows "Tunes <field>, clamped to 1 to 4 captures a minute (rounded) / 5 to 300 seconds. An error or a warming window keeps the row value." Compile meters now add `windows N/4 · window state B bytes (counted in the 8192)`; the plan bytes shown are the plan JSON, the cap counts both.
2. **Try-it.** `POST /api/expr/try` (`ExprRoutes`, registered in `CanvasExtras`, same router auth/Host/Origin/body policy; listed in READ_ONLY_POSTS) -> `ExprTry` (services:ai) lowers the one program and runs it through `ExprRunner.probe`, a new non-hot-path method that evaluates through the SAME `run` as the glasses and returns every entry (NUM/BOOL/ENUM/ERROR/DIV0/NOT_READY/UNAVAILABLE; a result unavailable only because a window below it is warming says NOT_READY). `ExprChecked.stepOuts` maps step name to its entry. Body `{graph, role?, row?, field?, signals:{name: number | [numbers]}, ctx?, ctxAgeS?}`; response `{ok, steps:[{name,result}], output, verdict | applied, seconds, windowStateBytes, windowTerms}` or `{ok:false, problems}` for a program that does not compile. Bounds: body <= 64 KB, <= 8 signals, <= 300 values each, numbers within 1e6, lists of equal length, only the seven features, every signal the program reads must be given; each violation is a 400 with a code (`too_large`, `too_many_signals`, `too_many_values`, `ragged_signals`, `bad_value`, `unknown_signal`, `missing_signal`, `bad_request`) and a fixed message that echoes nothing. A constant is held for as many seconds as the longest window needs; a list is the per-second history (the last value is "now"). Panel in the editor: one slider per signal the program reads, Run, step values (`opts.tryIt` posts it, so the view source still has no `fetch`).
3. **X9.** `ExprReference` (test-only straight-line evaluator, written from the spec, uses none of StepOps/Sample/WindowRing) vs `ExprRunner.probe/keep/tune`: `ExprDifferentialFuzzTest` (3000 guard + 2500 value seeds, random typed programs over every op with windows, gaps, late and stale reads; each entry compared exactly; about 1 s each). `ExprGoldenVectorsTest` + `core/capture/src/test/resources/expr/golden_vectors.json` (35 hand-computed vectors). `ReplayRunnerParityFuzzTest` (replay guard decisions at every dwell and value ticks vs a hand-fed classifier + fresh runner on random head-turn traces). `PlanValidatorLimitsFuzzTest` (600 random plans: every plan over 32 nodes / cost 64 / 6 programs / 4 terms / 8192 bytes plus window state is refused, every plan within is accepted; expected verdict computed independently).

## Files
Main: `core/capture/.../plan/ExprRunner.kt` (probe), `services/ai/.../plan/ExprProgram.kt` (stepOuts), `ExprTry.kt` (new), `phone/.../canvas/api/ExprRoutes.kt` (new), `CanvasExtras.kt`, `CanvasApi.kt`, assets `exprgraph.js exprview.js floweditor.js graph.js`. Tests: phone `api/TryItMatchesRunnerTest`, `api/TryItBoundedInputTest`, `ExprEditorUiContractTest`, `CanvasAssetsTest`, js `exprgraph.test.js expr-ui.test.js fixtures.js`; core:capture `plan/ExprReference`, `ExprDifferentialFuzzTest`, `ExprGoldenVectorsTest`, `PlanValidatorLimitsFuzzTest`, `replay/ReplayRunnerParityFuzzTest`.

## Dependents found
`ExprRunner` gained a method only; `ExprChecked` gained a defaulted field (all constructors use the default); `ExprLowering`/`ExprProgram` untouched otherwise; `graph.js defaultParams` now takes the type (palette only). Compiler is the truth; all modules compiled.

## JS budget
`CanvasAssetsTest.jsUnderSizeBudget` counts `readText().lines().size` over non-vendor `.js` files (wc -l plus one per file, 31 files). Before: 4540 (budget 4550). E2b adds 74 lines (exprgraph +48, exprview +33 net, floweditor +6 net, graph 0); now 4614; budget raised 4550 -> **4614** (+64, exactly the new total; well under the 120 allowed).

## Mutations (each RED, restored)
- Try-it: a second naive evaluator for the verdict -> `TryItMatchesRunnerTest` (window test) fails.
- Fuzz: `<=` as `<`, `>` as `>=`, clamp dropped, a gap at the window edge counted valid, window answering partial (n >= 1) -> `ExprDifferentialFuzzTest` fails each (golden vectors also fail on `<=`).
- Replay: feature read interval 1000 -> 1500 ms -> both `ReplayRunnerParityFuzzTest` tests fail.
- Validator: node limit +1, window state not counted in the cap, `in` costing 1 -> `PlanValidatorLimitsFuzzTest` fails (the first generator did not reach the state-bytes and `in`-cost cases; the generator was extended with heavy-window and `in` programs, then both died). Two early window mutations (`ready` at n >= seconds-1, n >= 1 keeping the lastInvalid clause) were equivalent mutants and survived; not counted.

## Decisions (please review)
1. Try-it evaluates ONE program per request (not the interplay of a plan's programs), SHADOW semantics only; it returns `verdict` keep/drop and the clamped `applied` row value.
2. Missing signals are a 400, not UNAVAILABLE (the runner's feed always writes all seven features).
3. Slider ranges are a JS table of guesses (`SLIDER`: gyro 0-10, aboveOnFrac 0-1, accVar 0-50, stepHz 0-4, rotRad 0-7).
4. Sliders give constants, so the UI always shows full windows; warming/gaps are reachable only through the API's lists.
5. Changing the target (row/field) does not rewrite the graph: the default `clamp(2,1..4)` is also valid (clamped) for gap fields; only the note changes.
6. Golden vectors live in core:capture test resources (where the runner tests are), not phone.
7. cel-java oracle skipped (not available).
8. Reference shares `SignalCatalog` names and the `ContextState` ordinal; the row clamp bounds are hard-coded in the test.

## NOT done
- Real browser: layout, dark theme and tap size of the try-it panel and the window controls, slider usability on a phone, React mounting of the new `target` prop (only GraalJS fake-DOM tests ran).
- Worn gates X7/X10, device install, Codex review of this diff, a try-it with a trace-backed history, windows warming shown in the UI.

## Merge steps
In pca-int1: `git merge --no-ff feat/expr-e2b` (touch points: `CanvasExtras.kt` one line, `CanvasApi.kt` one set entry, `CanvasAssetsTest.kt` budget number: if another branch also raised it, add the numbers). Then the five-module run: `./gradlew --no-daemon -Dorg.gradle.workers.max=2 --offline --continue :core:capture:cleanTest :core:capture:test :glasses:cleanTestDebugUnitTest :glasses:testDebugUnitTest :services:ai:cleanTestDebugUnitTest :services:ai:testDebugUnitTest :services:orchestration:cleanTestDebugUnitTest :services:orchestration:testDebugUnitTest :phone:cleanTestDebugUnitTest :phone:testDebugUnitTest`. Phone APK rebuild needed for the editor and route; no glasses change.

## Test results
(see the final section appended below)

Final clean run, one Gradle (`--continue`): :core:capture 466, :glasses 926, :services:ai 688, :services:orchestration 1694, :phone 1845. Only failure: `PromptEditorTest.savingCreatesVersionTwoThenOverwritesWhenVersioningIsOff` (known flaky), green when rerun alone. 0 other failures.
