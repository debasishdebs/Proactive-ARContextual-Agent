# Task 36 report: Canvas UI 2a, replay and sweep (core: tables, no chart)

Status: DONE (core). No Chart.js, no vendoring, no VendorLockTest change (chart is LA14; Pareto front LA6 not built).
New: pareto.js (pure: hours S80 formula, replaySummary, request builders, sort), replayview.js (#/replay), paretoview.js (#/sweep, sortable table; server sorts via ?sort=&dir=). app.js routes + nav links; style.css.
Tests: pareto.test.js 6 (hoursFormula, window counts, request bodies, sort), replay-views.test.js 6. Gradle (--no-daemon, workers 2): JsViewsTest 7, JsEditorTest 10, JsDomTest 3, JsModulesParseTest 1, CanvasAssetsTest 15, VendorLockTest 5, all green. JS own lines 3071 (< 3500).
Mutation checks: 6 mutants (inexact counted as compared, fusion warning off x2, sort dir flip, hours formula, sort query dropped), all caught; one botched mutant was a no-op and was not counted.
Rulings: "windows compared" = basePassed + baseFailed; inexact (excluded) = inexactWindows with reasons. Hours: server estimatedHoursFromFull, else 100/(%/min)/60. Fusion warning shown as role=alert at job and per-report level (replay: shadowOrTrialOnly; sweep: any warning mentioning fusion). Thumbnails: replay and sweep results contain no frames, so none shown (photos.js not used).
Concerns: sweep param path is free text (placeholder gate.hamming; no schema endpoint lists sweepable plan paths); routes /api/replays and /api/sweeps must be registered (Task 22/33) for the views to work live; React not involved (plain DOM), so no manual checklist beyond browser look.
