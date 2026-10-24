Read-only; no files changed. **0 Critical, 2 Important, 1 Minor.**

1. **Important — The late-confirmation test commits at the execution deadline.**  
   **File:** [00-core-plan.md:67](/Users/<user>/Desktop/rokid/proactive-context-agent/docs/superpowers/plans/2026-10-23-canvas-slice3b-core/00-core-plan.md:67).  
   `confirmJustBeforeExpiryThenExecutionGetsFullRuntime` requires success at lease + `pyExecDeadlineMs` (130 seconds). Retained P8a expires jobs when `deadlineMs <= now`; the lease also needs renewal throughout this interval.

   **Exact edit:** Keep confirmation at `confirmTtlMs - 1`, explicitly send successful heartbeats every second, and commit the result at lease + `pyExecDeadlineMs - 1`. Assert success without waiter timeout; separately assert expiry at lease + `pyExecDeadlineMs`. Keep the 135-second execution waiter and cancellation cleanup unchanged.

2. **Important — The production integration test enters through the wrong route.**  
   **File:** [00-core-plan.md:72](/Users/<user>/Desktop/rokid/proactive-context-agent/docs/superpowers/plans/2026-10-23-canvas-slice3b-core/00-core-plan.md:72).  
   `/api/helper-jobs` does not create Python experiment runs: retained P12 explicitly rejects browser submission of `py_exec`. Experiments start through `POST /api/runs`, reaching `PythonExecutor` through the runner.

   **Exact edit:** Replace the test’s opening chain with browser-authenticated `POST /api/runs/estimate` → `POST /api/runs` using WP8a’s `traceItems` contract → real `CanvasRunner`/`PythonExecutor` → real service and adapter → native Compose card/tap → fake helper → result fetched through `GET /api/runs/{id}`. Preserve expiry/session-termination removal cases and the mutation that omits production adapter injection.

3. **Minor — WP1’s direction test references WP3’s future DAO signature.**  
   **File:** [00-core-plan.md:63](/Users/<user>/Desktop/rokid/proactive-context-agent/docs/superpowers/plans/2026-10-23-canvas-slice3b-core/00-core-plan.md:63).  
   **Exact edit:** Keep DTO placement/import checks in WP1; move “WP3’s DAO signature uses it” into WP3’s test group. WP1 must pass before that signature exists.

`ConfirmBinding` placement and phone-only HMAC ownership otherwise reconcile across WP1/WP3/WP5, §6 and §21. WP2/WP6 ownership, production injection and STARTED-scoped refresh match the inspected `CanvasHost`, `CanvasHomeRoute` and `CanvasHomeViewModel` seams. **WP6 remains a defensible, tight single session**, with card work in WP2, full integration coverage in WP9 and the explicit 6-hour stop/split rule.

All requested arithmetic reconciles: **WPs 60.25; planned 78.25; reserve 7.75; cap 86.0; fixes 8.75; CP1 7.5/9.0; CP2 45.0/54.0; CP3 trigger 93.9 subject to cap; burn 72.25; escalation strictly >79.475; dependency path 35.25 through WP5; scheduled 36.75; wall-clock 51.5; 18 worktrees.** Helper-path labels and backlog subtotals also reconcile.

**Safe now:** WP1 (with finding 3 corrected) and WP10, following PF/admission rules. **Safe after dependencies merge:** WP2, WP3, WP4, WP6, WP7, WP8a, WP8b, WP11–WP13. Correct findings 1 and 2 before starting WP5 and WP9 respectively.

VERDICT: NOT READY