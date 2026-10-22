# Task 35 (35a) report: Canvas UI 1, capture-half editor

Status: DONE. Branch s3a/b from 9ff34e3e. No new vendored library, no new module (no registration changes).

Changes (all under phone/src/main/assets/canvas):
- paramform.js: kinds number, enum, text maxLen, table; sweep mark; coerceCell (blank gap = inherit, blank cap/bool = unchanged, explicit 0 kept); TABLE_HINT ("timer photo gap", "reserved timer photo attempts").
- graph.js: v2 defaults (number, enum, empty table); adding a schema-2 node bumps the draft to schema 2 (S41).
- validate.js: port labels, photos-binding exemption in portCheck, attributeProblems ("node x:" / "edge a->b:").
- flowadapter.js: domain lanes (LANES order, signal domain from params, single-domain graphs unchanged), warnings on nodes, photos binding allowed across lanes, bindingsOf.
- editorlogic.js: palette hides liveable=false (expr), tableChange, compile state (compileStart/applyCompile/compileFailed/compileView, stale on edit, warning placed on policy.capture_rate), flowFor merges fresh compile marks, lanesFor/bindingsFor.
- editorflow.js: compileDraft (POST /api/plans/compile, never saves; save never compiles).
- floweditor.js/style.css: TableControl (grouped headers, tri-state bool, blank placeholders), lanes legend + bindings, Compile panel, node warning badge and lane class, handle.compile().

Tests (node-run + GraalJS gate): paramform 21 (+11), graph 19 (+2), validate 8 (+3), flowadapter 24 (+6), editorlogic 56 (+13), editorflow 15 (+4), floweditor 3, views-smoke 23. New: 39. Gradle (--no-daemon, workers 2): JsEditorTest 10, JsViewsTest 5, JsDomTest 3, JsModulesParse 1, CanvasAssetsTest 15, VendorLockTest 5, all green.
Includes labelsSayTimerPhotoGap, blankMeansInherit, compilePostsTheDraftAndNeverSaves, saveDoesNotCompile.
Mutation checks: 11 mutants, all caught (blank-inherit, hint text, compile-saves, stale, lane gap, photos binding x2, schema bump, liveable, warning home, table range).

Rulings: compile problems are attributed by text prefix because the route returns strings; the WARN_GAP text (no node id) is placed on policy.capture_rate nodes. Edge problems mark only edges without a `when`. Replay/sweep/Pareto/publish UI not touched (36-37).
Concerns: React rendering (TableControl, Lanes, CompilePanel) is covered only by load/parse tests plus the manual checklist, as in slice 2; lane backgrounds are a legend plus node colour, not drawn bands. JS own-code lines still under the 3500 budget.
