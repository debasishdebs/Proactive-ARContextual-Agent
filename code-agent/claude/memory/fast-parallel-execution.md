---
name: fast-parallel-execution
description: "User wants fast, parallel delivery — lean plans, independent streams in separate worktrees, cross-phase parallelism; avoid heavy per-task ceremony"
metadata:
  node_type: memory
  type: feedback
  originSessionId: a6a5b53b-c399-4266-98de-c72503e9f379
  modified: 2026-10-13T15:24:36.715Z
---

Run independent work in parallel. That covers tasks inside a phase and whole phases at once, each in its own git worktree. Write lean plans: exact interfaces, behaviour bullets and named tests, not ~10k lines of full code. Inside a task, fix only Critical/Important findings; put minors in the ledger for the phase-end review.

**Why:** on 2026-10-13 the user said the strict sequential SDD run (full-code plans, review plus fix rounds per task, minors regraded) was far too slow: "we built the LiveFit app in single day … seems off in implementation style." They also asked "why not do parallel work?"

**How to apply:**
- Split each phase into streams that touch disjoint files, and dispatch them at the same time.
- Draft the next phases' plans while the current phase is still running.
- Allow one Gradle run per worktree.
- Use Opus/Sonnet only, never Haiku. The user calls Haiku slow and low quality.
- Keep the security and privacy rules from [[never-print-secrets]].
