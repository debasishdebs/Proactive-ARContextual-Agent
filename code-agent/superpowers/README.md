# Superpowers (subagent-driven development) artefacts

- `sdd/<plan>/`: for each executed plan: `task-N-brief.md` (requirements handed to the implementer), `task-N-report.md` (what was built, real test counts, rulings), `task-N-review.md` / `-rereview.md` / `-fix*-report.md`, diagnosis files and `integration-ledger.md` (merge rounds, gates, flaky tests).
- Plans live in `docs/superpowers/plans/`, specs and design decisions in `docs/superpowers/specs/` (the plugin's convention).
- Slice 3a is the most complete example: `sdd/2026-10-22-canvas-slice3a/`.
- Slice 3b plans and the Moment/diagnostic specs of 2026-10-23 are listed in [`../handoffs/README.md`](../handoffs/README.md); the v0.9.2/v0.9.3 slice handoffs and integration ledger are in `sdd/2026-10-24-v093-moments-scenes/` (v0.9.3 branch).
