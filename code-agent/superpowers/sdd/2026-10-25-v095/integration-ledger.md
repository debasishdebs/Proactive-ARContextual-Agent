# v0.9.5 integration ledger (2026-10-25, branch v0.9.5, based on v0.9.4 tip 5563c209)

| Order | Slice (branch) | Gate | Notes |
|---|---|---|---|
| 1 | hardening A: recorder thread guard, outbox rows for every deletion path, mark lineage (feat/harden-a) | Codex NOT READY (2 Important) -> fixed caf31a2d, 47836d32 | retention writes phone-file rows only; bounded drain, cap 2000 per glasses target |
| 2 | hardening B: A/B Photo filters, watch run rows deleted with the source (feat/harden-b) | Codex canvas re-check NOT READY (2) -> fixed | A/B later changed to per-arm settings (rulings) |
| 3 | hardening C: log ratchet migration, strict content scan (feat/harden-c) | Codex NOT READY (2) -> fixed 1f3aea23 | baseline: only the two log sinks |
| 4 | ItemRef/Forget inventory and design, analysis only (feat/itemref-design) | n/a | recommendation: residue oracle + gap fixes before v1, Phase B planner after v1 |
| 5 | forget gaps: cached export deletion, residue oracle (feat/forget-gaps) | Codex NOT READY (3) -> fixed 0021e421 | GAP-2, GAP-3 accepted residue (user), GAP-4/9/12/13 deferred |
| 6 | catch-up diagnostics: cadence job scheduling fix, marker, PcMoments logs, rebuild route (feat/catchup-diag) | Codex READY | root cause: the cadence job was scheduled only when pipeline processing was on |
| 7 | rulings: Moments palette, A/B per arm, DataOpGate, watchdog/forget-radius/Scene cadence settings (feat/rulings) | in progress at 17:25 | see 2026-10-25-rulings-handoff.md on that branch |

Flaky under load (pass alone): PromptEditorTest, LocalModelsPageTest, HistoricalBackfillTest, one orchestration test JVM hang (not reproduced). NomicVisionTest needs `git lfs pull` in a new worktree.
Not pushed: v0.9.5. Not installed: any v0.9.5 build.
