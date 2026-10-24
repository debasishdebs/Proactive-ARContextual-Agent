---
name: slice3b-scope-rulings
description: "User rulings of 2026-10-23 on slice 3b scope, threat model, reviewers, logging and parallel work (Proactive Context Agent repo ~/Desktop/rokid/proactive-context-agent)"
metadata:
  node_type: memory
  type: project
  originSessionId: 5f738bb7-228a-40ad-8dd1-9f72f0041163
  modified: 2026-10-23T11:42:46.097Z
---

Rulings from the user on 2026-10-23 (binding until changed):

- **Threat model A for slice 3b:** user's own laptop, own CLI/key; generated code is not treated as deliberately hostile; containers get sane defaults only. Hostile-code defences (quota filesystem, process forensics, regex/ReDoS limits, VM watchdog, crash-safe lock matrices, 200-case oracle) are parked in the **3d hardening slice** (build core with safety in mind, sandbox later; keep the seams: one runner argv builder, profile table, resource config).
- **Codex is the diff reviewer** in place of the user (user has no time to read diffs): read-only `codex exec -m gpt-6.1-sol`, fixed checklist, PASS before merge; the user sees verdict + diffstat. Nothing installs on a device without the user's explicit OK and a phone backup.
- **NL-based pipeline/tool development moves to v2.** 3b = pairing/auth/grants + Python experiments (py.fn). Goal: ship a v1 app from v0.9.0 first.
- **Logging:** one logger, one gate predicate (never scattered ifs). All ~414 raw `Log.*` calls migrate onto the single logger; diagnostics/trace recording must start whenever Developer/diagnostic mode is on, regardless of pairing; pairing/transfer is gated separately (one `canTransfer()` predicate). Spec: docs/superpowers/specs/2026-10-23-always-on-diagnostic-recording.md. Debug exports are gated behind the diagnostic flag.
- **Parallel work:** one git worktree per work package from v0.9-beta under ~/Desktop/rokid/, merge into v0.9.1, v0.9.2 ... branches, remove the worktree folder after merge. Merge tasks that touch the same module into one work package.
- **3b CORE plan is Codex-READY (round c10, 2026-10-23):** docs/superpowers/plans/2026-10-23-canvas-slice3b-core/00-core-plan.md. Python experiments over photos/frames AND stored traces; py_exec needs grant + PER-RUN native confirmation; helper tokens memory-only; Podman: always stop -> remove -> recreate with one /stage mount, keep VM stopped when idle. 14 WPs, 60.25 h of WPs, 78.25 planned, cap 86.0, about 51.5 h wall-clock, 18 worktrees, branches v0.9.1-v0.9.3. Implementation not started; v1 blockers (always-on logging, replay fix, M1, Task 38, signed build) come first unless the user says otherwise.
- Plan estimates inflate through review rounds (3a went 71.5 -> 103.5 -> 66 CORE; 3b 32.5 -> 187 -> 69 CORE): cut to a CORE scope early; do not run unlimited Codex hole-hunting rounds.

**Why:** the user rejected a 187 agent-hour estimate for what they see as a small feature, and wants wall-clock speed with Sonnet-only agents and Codex reviews.
**How to apply:** scope every plan as CORE vs LATER up front; state the threat model to Codex reviewers; ask the user only for decisions that change scope.
