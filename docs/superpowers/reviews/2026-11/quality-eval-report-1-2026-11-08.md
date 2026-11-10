# Proactive Context quality evaluation

Generated 2026-11-08 01:17 IST against the paired phone (read-only: 459 requests, verbs ['GET']). Snippets: none (pass --samples N).

## 1. Capture quality

Routes: GET /api/frames (paged, limit 120), GET /api/frames/:id (memory text, sampled), GET /api/moments?day=, GET /api/scenes.
- frames fetched: 2277 (cap --max-frames 3000)
- days covered: 9; frames per day n=9 min=24 q1=73 median=199 q3=360 max=647
- frames per active hour: n=35 min=2 q1=17 median=46 q3=102 max=211
- inter-frame interval s (same day): n=2268 min=0.9 q1=8.9 median=14.6 q3=27.6 max=44350.7
  - < 2 s: 0.1 %; 2-30 s: 78.1 %; 30 s-10 min: 21.0 %; > 10 min: 0.7 %
- blur (frame meta): n=2277 min=60.046 q1=1950.730 median=3317.817 q3=4738.083 max=10444.459
- photo byte size: unavailable (no metadata route exposes it; photos are never downloaded by this tool)
- dwell frames: 1101 (48.4 %)
- frames with no live memory: 262 (11.5 %)
- extraction outcome (frame state): OK 2015 (88.5 %), pending 262 (11.5 %)
  - raw states: EXTRACTED=2015, CAPTION_QUEUED=262
  - needs-review: unavailable on frames (counted per run in the Canvas section); confidence: unavailable (not exposed)
- timestamps: future (> now+5 min) 0, very old (> 1 y or before 2020) 0; glasses-to-phone clock offset: unavailable (frames carry one ts)
- memories read: 0 from 0 frames (cap --max-detail 400)
- Moments (last 9 days, <= 120 per day): 852; types {'FRAME': 852}; photo-less 0 (0.0 %); with errorReason 0; without memory 186 (21.8 %)
- Scenes (first 120): 7; duration s n=7 min=1.0 q1=1.0 median=11.0 q3=104.0 max=118.0; Moments per Scene n=7 min=1 q1=1 median=2 q3=4 max=4

## 2. Retrieval self-test

Phone route: POST /api/retrieve/debug runs the phone's real retriever for each query per mode (no Answer model call, no write; body {query,mode,topK}; reply {mode,semanticUnavailable,fallback,hits:[{memoryId,score,rank}]}).  GET /api/asks/:id re-runs retrieval for a stored ask (real-ask diagnostics below); POST /api/asks/:id/replay calls the Answer model and is never sent.
- **unavailable**: no memory text could be read (GET /api/frames/:id)

## 3. Canvas quality

Routes: GET /api/pipelines, /api/pipelines/:id, /api/runs, /api/runs/:id, /api/runs/:id/proposal, /api/publish, /api/publish/history, /api/live, /api/plans/status, /api/signals, /api/traces, /api/replays, /api/sweeps, /api/devices.
- pipelines: 10 (built-in 8, with a live version 0, draft with validation problems 0)
- publish, publish history, live interpreter: skipped (their GET runs the phone's trace retention, which may delete old traces; pass --include-maintenance-reads to read them)
- capture plan status: keys active, rows
- signals: 9 catalogue (0 disabled); derived 0 (graph missing/unparsable 0); guard configs: unavailable (no route)
- traces: 1, status {'COMPLETE': 1}
- replays: 5, status {'DONE': 5}
- sweeps: 0, status {}
- devices: present (1 keys)

## 4. Output quality

Routes: GET /api/asks, GET /api/asks/:id (re-runs retrieval as of the ask; no model call). Nudges and proposal undo: no route.
- asks read: 19 (newest, cap --max-asks 50); redacted 9; views refused/failed 14
- question length words: n=10 min=1 q1=3 median=4 q3=6 max=13
- answer empty 0 (0.0 %); 'I don't know'-style 3 (30.0 %)
- ratings: 0 rated; {}
- citations: 0 over 5 views; not resolving to a memory/photo read by this tool or a retrieved hit 0 (n/a); cited but not among re-retrieved hits 0 (n/a) in 0 asks
  - note: the view re-retrieves as of the ask and is refused when any hit was forgotten, so 'outside' can also mean the index changed since
- ask latency: unavailable (not stored on asks; only replays report latency, and replays are never sent)
- Nudges (count, dedupe, text length, sourceIds, shown-on): **unavailable** (no nudge route on the canvas server)
- run proposals (Run-and-Save): 0 saveable/saved, 0 saved (n/a); undone: unavailable (no route)

## 5. Scores

Rules (each area starts at 100; shares are 0..1; clamped to 0..100; an area with no data is not scored):
- Capture = 100 - 40*failed - 20*no-memory - 20*duplicate - 10*refusal - 5*empty/short - 5*bad-timestamp
- Retrieval = 80*R@5 + 20*MRR - 20*negative-false-positive (mean over phone modes; the local reference only when no phone route exists)
- Canvas = 100 - 25*runs-failed - 20*node-errors - 15*pipelines-with-problems - 15*needs-review - 10*rolled-back - 10*live-batch-errors - 5*trace/replay-bad
- Output = 100 - 30*empty-answer - 25*unresolved-citation - 25*citation-outside-hits - 20*idk
- Issue priority = severity weight (1..3) x share; listed highest first.

| area | score |
|---|---|
| capture | 98 |
| retrieval | not scored |
| canvas | 100 |
| output | 94 |

### Prioritised issues

1. [output] 'I don't know'-style answers: 3 of 10 live asks (30.0 %) (priority 0.30)
2. [capture] frames with no live memory: 262 of 2277 frames (11.5 %) (priority 0.23)

## Unavailable routes

- /api/asks/:id: http=409 error=refused
- /api/frames/:id: http=400 error=bad_request
