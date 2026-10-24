---
name: handoff-pointer-2026-10-24
description: Where the frozen handoff, version train, bug register and v0.9.3 status live for the Proactive Context Agent work; worktrees were cleaned on 2026-10-25
metadata:
  type: reference
---

Repo: `~/Desktop/rokid/proactive-context-agent` (main checkout, branch v0.9-beta, has 46 untracked/modified doc files: do not clean). All work is on branches `v0.9.1`, `v0.9.2`, `v0.9.3` (tip 6324b94d, v0.9.3 code complete). On 2026-10-25 the user had ALL worktrees removed (folders only; branches and history kept). Retained folders in ~/Desktop/rokid: android-sdk, awesome-rokid, live-fitness-tracker, proactive-context-agent, Rokid-APKs, rokid-docs, rokid-upi-payment-mvp, rokid-apps, backups plus `handover` (wear-window traces; the user said KEEP it on 2026-10-25). Rule: new work uses a fresh `git worktree add ../<name> -b <branch> v0.9.3`, then copy llama.cpp sources into services/ai/src/main/cpp/llama.cpp from the main checkout; delete the worktree folder after merge once verified (ancestor of the integration branch, clean).

Start a new chat by reading (via `git show v0.9.3:<path>` or a new worktree): `HANDOFF-2026-10-24.md`, `docs/superpowers/plans/handoffs/2026-10-24-v093-status.md`, `docs/superpowers/plans/2026-10-24-road-to-v1.md` (version train, bug register), `MORNING.md`. v0.9.3 APKs (phone + glasses debug, built from 6324b94d) are saved in `~/Desktop/rokid/backups/apks-v093-20261025/`; the phone has the 00:08 build installed, the glasses still the 12:45 build. Related: [[usage-budget-2026-10]], [[slice3b-scope-rulings]].
