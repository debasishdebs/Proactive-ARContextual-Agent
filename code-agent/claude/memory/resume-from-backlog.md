---
name: resume-from-backlog
description: "Where to resume after compaction: the committed status/backlog doc lists what's built, pending device checks, open bugs and decided features; wave 1 keyframe capture merged as v1.0.0-rc1"
metadata:
  node_type: memory
  type: project
  originSessionId: a6a5b53b-c399-4266-98de-c72503e9f379
  modified: 2026-10-22T15:46:39.724Z
---

Read `docs/superpowers/specs/2026-10-22-handoff-evening.md` FIRST (newest resume point, 2026-10-22 evening: state, rules, branches, devices, pending, repo-merge/push plan, plan reviews), then `docs/superpowers/specs/2026-10-21-handoff.md` for older history. Then, as needed, docs in `docs/superpowers/specs/` on branch feat/proactive-mvp-implementation:
- `2026-10-14-status-and-backlog.md`: current state, read first (sections "Wave 1 …" at the end).
- `2026-10-14-decision-log.md`: every ruling, probe and device result; ends with "Wave 1 — rulings and device facts".
- `2026-10-15-keyframe-capture-image-memory-design.md` (spec) + `plans/2026-10-15-keyframe-capture-wave1.md` (plan) + `2026-10-15-wave1-device-checklist.md`.
- `2026-10-14-session-log.md`.

State at 2026-10-15 15:00: wave 1 (F, C, P1, P2: keyframe capture, DB v2, Nomic foundation, pipeline, guarded reopen, real wear signal, 3-tap exit) is merged (`dc0bfce`, tag `v1.0.0-rc1`; `v1-beta` = previous build), app v1.0.0, full gate green. Installed on both devices. Nothing pushed.

**Why:** the user flies on 2026-10-15 ~18:00 and wants v1 usable; later chats continue from these docs.

**How to apply:** pending: user rotates AWS Bedrock keys, legacy Llama token, EAI_SHARED_TOKEN (earlier leak) and may re-pair glasses (device key partly leaked 2026-10-15, see [[glasses-logcat-leaks-device-secret]]); device checks still open (1 s glance promise, battery drain, LED, Nomic reference vectors, photo 256-vs-768 test); waves 2–4 (image index + lifecycle, image recall, motion hub with LiveFit at ../live-fitness-tracker); connectors brainstorm (Outlook, Slack, Gmail, Amazon, Flipkart); user notes feature is merged. Model split: [[opus-specs-sonnet-code]]. Related: [[fast-parallel-execution]], [[never-print-secrets]], [[submodule-no-reference]].
