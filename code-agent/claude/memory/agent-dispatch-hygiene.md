---
name: agent-dispatch-hygiene
description: Rules to put in every subagent dispatch in this repo (no git stash, no machine-wide pkill, gradle scoping, clean after merges)
metadata:
  type: feedback
---

Observed 2026-10-22 overnight with many parallel worktrees: (1) `git stash` leaked hunks across worktrees (refs/stash is shared) and polluted w3b history; (2) an unscoped `pkill gradle` killed other streams' builds; (3) incremental Gradle output went stale after merges and produced false red gates; (4) two agents editing the same worktree blocked each other's compile.

**Why:** the user runs 3-4 streams in parallel for speed ([[fast-parallel-execution]]); shared state (stash, Gradle daemons) is the failure surface.
**How to apply:** every dispatch prompt says: never `git stash`; never pkill gradle machine-wide (kill only your own worktree's processes); one Gradle per worktree via gw.sh; clean modules after a merge before trusting a red gate; one agent per worktree at a time (sequence fix rounds), commit explicit paths only; no adb installs by agents.
