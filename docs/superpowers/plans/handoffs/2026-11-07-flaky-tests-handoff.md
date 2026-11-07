# Flaky phone unit tests: root cause (2026-11-07)

Both tests are timing races, not shared-state leaks (no singleton, prefs, port or dispatcher leak was found for these two).

- LocalModelsPageTest.rollBackHiddenAfterGraceAndDialogCarriesTheCounts: only test whose source reads a real Room DB on page load
  (SpacesSwapRollback -> swap.impact()). The page reloads once per gateway state emission, so a second load is always in flight
  when the test ends. tearDown tried to dispose via a second compose.setContent (throws, swallowed by runCatching), so the composition
  stayed alive and TestGraph.close() closed the DB under the load: "connection pool has been closed". Fix: content gated by a state
  flag that tearDown flips and waits idle, cancelling the LaunchedEffects before the DB closes.
- PromptEditorTest.savingCreatesVersionTwo...: save() silently returns while gate (1) (initial schema check, real Default thread)
  has not landed; setUp only waited for loading/endpoint. Fix: setUp also waits for compatFor != null.

No production defect: the DB is never closed under a live page outside tests.
Not reproduced/changed: JsEditorTest, StepSheetTest, CanvasServerTest ports, HistoricalBackfillTest, WiringEndToEndTest (no evidence collected).
Verification: full :phone:testDebugUnitTest --rerun-tasks twice, both BUILD SUCCESSFUL. The races are load-dependent, so a pass is not proof.
