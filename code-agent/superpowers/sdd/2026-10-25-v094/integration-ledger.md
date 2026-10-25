# v0.9.4 integration ledger (2026-10-25, branch v0.9.4)

Based on v0.9.3 (6324b94d). Slice handoffs in this folder. APKs of the final tip (phone + glasses debug): `~/Desktop/rokid/backups/apks-v094-20261025/` (local only, SHA256SUMS + commit id).

| Order | Slice (branch) | Gate | Notes |
|---|---|---|---|
| 1 | Summary node, Aggregate (agg.window, gate.compare), starter-moment-summary (feat/summary-node) | Codex NOT READY (1 Important) -> fixed c314ae86 | summary shown as one extraction fact; Prompt accepts MOMENTS |
| 2 | mark-only Moments, MomentType, Room v13, Marks in Activity (feat/mark-moments) | Codex NOT READY (3 Important) -> fixed 90ad61ec | user decision 2026-10-25: a Moment can be born from a mark alone; no-photo reason stored UNKNOWN |
| 3 | 3c E2a: windows, window terms, replay parity, expr.value (feat/expr-e2a) | Codex NOT READY (2 Important) -> fixed ad068120 | strict full-horizon windows; sigSchema 2; SHADOW/TRIAL only |
| 4 | 3c E2b: editor for windows/values, phone try-it, differential fuzz + goldens (feat/expr-e2b) | full run green; no Codex | JS budget 4550 -> 4614 |
| 5 | composite phase C: glasses-lane nodes (feat/composite-c) | full run green; no Codex | JS budget 4628; compile goldens pin five built-ins |
| 6 | watch lane, proc.motion_model, out.set_frequency_preview, starter-watch-motion (feat/watch-lane) | final full run | preview only, no live acting |

Final run on the tip: all modules green except load-sensitive flaky tests (LocalModelsPageTest, PromptEditorTest, HistoricalBackfillTest, JsEditorTest, StepSheetTest, CanvasServerTest port tests) which pass alone. Cancelled earlier: the first v0.9.4 attempt (discarded at the user's request, then restarted).
Needs the user: browser visual pass, phone and glasses installs (backup first, no call), the worn 35-minute X7/X10 SHADOW gate, the 01:50 marks check.
