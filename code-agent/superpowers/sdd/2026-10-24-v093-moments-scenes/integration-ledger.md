# v0.9.2 / v0.9.3 integration ledger (2026-10-24 .. 2026-10-25)

Branches (shared repo, worktrees removed on 2026-10-25, branches kept): `v0.9.1` (installed baseline 6adc222f + 3 bug fixes, patch-equivalent in later branches), `v0.9.2`, `v0.9.3` (tip 6324b94d). Slice handoffs (what was built, mutations, decisions, not done) are the files in this folder; the big handoff, addendum, road-to-v1 plan and MORNING.md live on those branches under the repo root and `docs/superpowers/plans/`.

| Order | Slice (branch) | Gate | Notes |
|---|---|---|---|
| 1 | merged multi-pipeline view + composite spacing fix (feat/merged-view) | full run green; installed 19:12 | no browser check yet |
| 2 | Scenes: Room v11, SceneBuilder, input.scenes (feat/scenes) | Codex NOT READY (3 Important) -> fixed f0437a3f; full run green; installed 20:58 | `codex/reviews/scenes-v11-review-2026-10-24-1.md` |
| 3 | cleanup outbox drainer, phone files (feat/outbox) | full run green | glasses/helper transports are stubs (rows kept) |
| 4 | moment_sensor, Room v12 (feat/sensor) | Codex READY; full run green | clock tolerance 500 ms: check on real traces |
| 5 | Scenes run page, input.scenes consumers, sensor backfill on import (feat/scenes-run) | full run green | JS budget 4500 -> 4550 |
| 6 | derive step, summary/caption, OFF by default (feat/derive) | Codex NOT READY (3 Important) -> fixed 88340035; full run green; installed 00:08 | cloud binding sends text off phone: decide before enabling |
| 7 | Photo settings, palette by families, starter pipeline (feat/canvas-a) | full run green; no Codex (user decision) | starter phone half differs from the drawn chain (needs Summary node) |
| 8 | composite nodes phase B (feat/composite-b) | full run green; no Codex (user decision) | 01:04 APK not installed |

Flaky under load (pass alone): PromptEditorTest, LocalModelsPageTest, JsEditorTest, StepSheetTest, CanvasServerTest port tests, HistoricalBackfillTest, one orchestration test JVM hang (rerun). New worktrees need llama.cpp sources copied from the main checkout; `git add -A` breaks on that path (stage by name).
Cancelled: v0.9.4 Summary/Aggregate slice (agent stopped, work discarded by the user's decision).
APKs of 6324b94d (phone + glasses debug): `~/Desktop/rokid/backups/apks-v093-20261025/` (local only).
