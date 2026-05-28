# MEMORY

Last Updated: 2026-05-28

## Purpose
Working memory for durable project knowledge that should survive across sessions.

## Stable Project Facts
- Project name: `proactive-context-agent`
- Scope: Rokid proactive context + memory system (separate from UPI and docs repos)
- Repo separation is mandatory:
  - UPI stays in its own repository.
  - Docs stay in their own repository.
  - This repository is for proactive context agent only.

## Locked Decisions
1. Glasses capture policy is event/activity + battery based (not continuous video).
2. Driving/Running capture can remain fast until the activity ends.
3. Glasses battery guardrails:
- `<=50%`: capture interval floor `>=5s`
- `<=30%`: capture interval floor `>=10s`
- `<=20%`: warn once, disable background capture, allow explicit/manual query capture only
4. Phone processing policy:
- Battery `>30%`: send/process every `15 min`
- Battery `<=30%`: send/process only when charging
5. Local model policy:
- Run local model only when charging or battery `>70%`
6. Model access should be uniform irrespective of backend (cloud API or local model).

## Important Assumptions To Validate
- Exact Rokid SDK hooks for low-frequency capture and transport lifecycle.
- End-to-end latency budget for query-time answer path.
- Storage retention limits for frame-derived memory.

## Implementation Milestone (Fast Mode)
- Android companion MVP baseline modules are now scaffolded with passing targeted unit tests.
