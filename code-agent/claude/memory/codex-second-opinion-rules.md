---
name: codex-second-opinion-rules
description: "How Codex is used in this project: second reviewer of Opus plans, one final code pass after Opus reviews; never for implementation; model gpt-6.1-sol only"
metadata:
  node_type: memory
  type: feedback
  originSessionId: a6a5b53b-c399-4266-98de-c72503e9f379
  modified: 2026-10-21T13:01:22.539Z
---

User rules (2026-10-21), on top of [[opus-specs-sonnet-code]]:
- **Plans:** every plan/spec Opus writes gets a second-pair-of-eyes review by Codex before implementation (read-only).
- **Implementation:** Sonnet only. Never Codex.
- **Code reviews:** Opus reviews (per task and the whole-branch review) until all findings are resolved; THEN one last final pass by Codex. No Codex per-task reviews.
- **Codex model:** `gpt-6.1-sol` (GPT-6.1-Sol; fallback `gpt-6-sol`). Never astra or luna. Pass it explicitly: `node "/Users/debasishkanhar/.claude/plugins/cache/openai-codex/codex/1.0.6/scripts/codex-companion.mjs" task --model gpt-6.1-sol --effort high "<prompt>"` (read-only unless `--write`; `review`/`adversarial-review` take no --model flag and would use ~/.codex/config.toml's gpt-5.3-codex, so use `task` instead). Do not edit the user's ~/.codex/config.toml.

**Why:** the user wants independent verification of plans and one last independent code check without paying Codex on every task.
**How to apply:** after an Opus planner returns, run Codex plan review (background, read-only), triage findings (controller rules on each, send fixes to the planner or edit the plan), then start implementation. After the final Opus whole-branch review is clean, run ONE Codex pass over the branch diff; fix real findings through Sonnet; re-review those fixes with Opus.

**Resolved 2026-10-21 (user upgraded Codex to 0.163.0 via `brew upgrade --cask codex`, ChatGPT login):** the plugin companion's `task --model` path still fails with "model not supported with a ChatGPT account", but the PLAIN CLI works with both Sol models. Use the plain CLI, read-only, from the repo: `codex exec -m gpt-6.1-sol -c model_reasoning_effort="high" -s read-only --skip-git-repo-check --ephemeral -C <dir> -o <out.md> "<prompt>"` (run in background, read the -o file). Do NOT use `codex-companion.mjs task` or `/codex:*` commands for this. Plans reviewed by Codex so far: see handoff "Codex reviews" lines.
