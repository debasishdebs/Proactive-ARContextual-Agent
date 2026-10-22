---
name: opus-specs-sonnet-code
description: "Model split for agents — since 2026-10-22 Sonnet 5.5 for everything (planning, reviews, code); no Opus, never Haiku"
metadata:
  node_type: memory
  type: feedback
  originSessionId: a6a5b53b-c399-4266-98de-c72503e9f379
  modified: 2026-10-22T09:13:07.820Z
---

From 2026-10-22 the user said: do not use Opus anymore; use Sonnet 5.5 for reviews, planning AND code dev. Never Haiku. (Supersedes the earlier 2026-10-15 split of Opus for specs/plans/reviews, Sonnet for code.)

Codex spend is fine (user can add credits; Claude credits are frozen until month end), so prefer Codex (read-only) for reviews where practical; Codex still never implements.

**Why:** costs ballooned (~$400 in one day, ~$500 left before the monthly quota expired on 2026-10-22).

**How to apply:** pass `model: "sonnet"` on every Agent dispatch (planning, review, re-review, implementation, fixes, device runs). Keep reviews lean: one review per task, no re-review when approved with only minors. Codex (gpt-6.1-sol) still does the final pass per [[codex-second-opinion-rules]]. Related: [[fast-parallel-execution]].
