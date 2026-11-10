# Proactive Context quality evaluation

Generated 2026-11-08 01:40 IST against the paired phone (read-only: 983 requests, verbs ['GET', 'POST']). Snippets: none (pass --samples N).

## 1. Capture quality

Routes: GET /api/frames (paged, limit 120), GET /api/frames/:id (memory text, sampled), GET /api/moments?day=, GET /api/scenes.
- frames fetched: 2277 (cap --max-frames 3000)
- days covered: 9; frames per day n=9 min=24 q1=73 median=199 q3=360 max=647
- frames per active hour: n=35 min=2 q1=17 median=46 q3=102 max=211
- inter-frame interval s (same day): n=2268 min=0.9 q1=8.9 median=14.6 q3=27.6 max=44350.7
  - < 2 s: 0.1 %; 2-30 s: 78.1 %; 30 s-10 min: 21.0 %; > 10 min: 0.7 %
- blur (frame meta): n=2277 min=60.046 q1=1950.730 median=3317.817 q3=4738.083 max=10444.459
  - blur below 300: 84 (3.7 %); below 1000: 264 (11.6 %) of 2277 frames with a blur value
- photo byte size: unavailable (no metadata route exposes it; photos are never downloaded by this tool)
- dwell frames: 1101 (48.4 %)
- frames with no live memory: 262 (11.5 %)
- extraction outcome (frame state): OK 2015 (88.5 %), pending 262 (11.5 %)
  - raw states: EXTRACTED=2015, CAPTION_QUEUED=262
  - needs-review: unavailable on frames (counted per run in the Canvas section); confidence: unavailable (not exposed)
- pending frames (CAPTION_QUEUED / PENDING / EXTRACTING): 262 (11.5 %); age in hours since capture n=262 min=315.1 q1=413.0 median=416.5 q3=419.2 max=539.2
  - age: < 1 h 0; 1-6 h 0; 6-24 h 0; 1-3 days 0; > 3 days 262; oldest 539.2 h
- timestamps: future (> now+5 min) 0, very old (> 1 y or before 2020) 0; glasses-to-phone clock offset: unavailable (frames carry one ts)
- memories read: 0 from 0 frames (cap --max-detail 400)
  - GET /api/frames/:id cannot take 400 of the sampled frame ids (the phone's id pattern ^[A-Za-z0-9:_-]{1,64}$ rejects them; they are not sent); memories were then harvested from POST /api/retrieve/debug with snippets=true: 1906 memories (<= 80 chars each) in 300 queries
- memory length chars: unavailable (only 80-char snippets could be read; length, empty and short stats below are over snippets)
- empty 0 (0.0 %), very short < 20 chars 0 (0.0 %), very long > 600 chars unavailable
- memory types: None=1906
- duplicate captions: exact (normalised) 10 (0.5 %); near-duplicate (content-word Jaccard >= 0.80) 64 memories (3.4 %)
- refusal/boilerplate phrases: 0 memories (0.0 %)
- Moments (last 9 days, <= 120 per day): 852; types {'FRAME': 852}; photo-less 0 (0.0 %); with errorReason 0; without memory 186 (21.8 %)
  - Moments holding pending frames (matched by time; Moments expose no frame ids): 186; of them without memory: 186; all Moments without memory: 186, of which photo-bearing 186; pending frames outside the 9-day Moment window: 0
- Scenes (first 120): 7; duration s n=7 min=1.0 q1=1.0 median=11.0 q3=104.0 max=118.0; Moments per Scene n=7 min=1 q1=1 median=2 q3=4 max=4

## 2. Retrieval self-test

Phone route: POST /api/retrieve/debug runs the phone's real retriever for each query per mode (no Answer model call, no write; body {query,mode,topK}; reply {mode,semanticUnavailable,fallback,hits:[{memoryId,score,rank}]}).  GET /api/asks/:id re-runs retrieval for a stored ask (real-ask diagnostics below); POST /api/asks/:id/replay calls the Answer model and is never sent.
- corpus note: 1906 of 1906 memories are 80-char snippets harvested through the retrieval route (the frame route cannot take the real ids), so the corpus is limited to what the phone's LEXICAL retriever surfaced for 300 seed/rare-word queries: recall figures are optimistic, and queries derive from the snippet only
- corpus: 1906 memories; sample: 100 (stratified by day x length tercile, seed 7); 2 queries each (tf-idf words, paraphrase-lite)
- **phone /api/retrieve/debug LEXICAL**
  - phone reported: mode used {'LEXICAL': 200}; semantic unavailable on 0 of 200 queries; fallback (no match, newest memories) on 0
  | query | n | R@1 | R@3 | R@5 | R@10 | MRR@10 |
  |---|---|---|---|---|---|---|
  | tfidf | 100 | 0.820 | 0.920 | 0.930 | 0.980 | 0.875 |
  | paraphrase | 100 | 0.690 | 0.880 | 0.900 | 0.950 | 0.785 |
  - failures (miss or rank > 5): 17 queries over 12 memories; ids: rokid_kf_02fccfa6-46d0-4a42-a077-ac132e325951_c2, rokid_kf_0d0c52c6-93d4-49fe-a817-72910169a775_c0, rokid_kf_2e935e89-c2b3-48f4-bffb-88033c9433e4_c0, rokid_kf_346ad58c-d382-4b1a-992e-0e68728a4ba3_c1, rokid_kf_66e2d3f5-7bc0-4184-8c79-83baf89fa3b2_c2, rokid_kf_7a103e89-9d89-4e96-84bc-4101b1c63d38_c1, rokid_kf_8ab191a0-a7cb-49dd-8bd8-4419c8d3a7e2_c0, rokid_kf_92be3d9e-000e-49da-8450-446cc562555c_c0, rokid_kf_a383f172-ff69-49c3-b131-72c40f90e004_c1, rokid_kf_b034ecf8-ceac-4ee7-8240-e1dbb7498477_c1, rokid_kf_da43e5ab-e2fe-4d7c-a481-79c2b5ed3937_c0, rokid_kf_ec8c1173-b8c1-4a0b-801b-a785f55c21a7_c0
  - failure rate by length: long 4.7 %, medium 16.2 %, short 4.4 %
  - failure rate by day: 2026-10-15 8.3 %, 2026-10-16 25.0 %, 2026-10-17 16.7 %, 2026-10-20 30.0 %, 2026-10-21 0.0 %, 2026-10-22 0.0 %, 2026-10-23 16.7 %, 2026-10-24 25.0 %, 2026-10-25 0.0 %, 2026-10-26 0.0 %, 2026-10-27 0.0 %, 2026-10-28 0.0 %, 2026-10-29 8.3 %, 2026-10-30 0.0 %, 2026-11-01 33.3 %, 2026-11-02 0.0 %, 2026-11-03 0.0 %, 2026-11-04 0.0 %, 2026-11-05 0.0 %, 2026-11-06 0.0 %
  - failure rate by type: None 8.5 %
  - failure rate by review/frame state: OK 8.5 %
  - negative control: 8 queries never captured (vocabulary-checked); returning any hit 0; top score n=8 min=0.000 q1=0.000 median=0.000 q3=0.000 max=0.000; scoring >= median true-hit score (13.561): 0 (0.0 %)
- **phone /api/retrieve/debug SEMANTIC**
  - phone reported: mode used {'LEXICAL': 200}; semantic unavailable on 200 of 200 queries; fallback (no match, newest memories) on 0
  | query | n | R@1 | R@3 | R@5 | R@10 | MRR@10 |
  |---|---|---|---|---|---|---|
  | tfidf | 100 | 0.820 | 0.920 | 0.930 | 0.980 | 0.875 |
  | paraphrase | 100 | 0.690 | 0.880 | 0.900 | 0.950 | 0.785 |
  - failures (miss or rank > 5): 17 queries over 12 memories; ids: rokid_kf_02fccfa6-46d0-4a42-a077-ac132e325951_c2, rokid_kf_0d0c52c6-93d4-49fe-a817-72910169a775_c0, rokid_kf_2e935e89-c2b3-48f4-bffb-88033c9433e4_c0, rokid_kf_346ad58c-d382-4b1a-992e-0e68728a4ba3_c1, rokid_kf_66e2d3f5-7bc0-4184-8c79-83baf89fa3b2_c2, rokid_kf_7a103e89-9d89-4e96-84bc-4101b1c63d38_c1, rokid_kf_8ab191a0-a7cb-49dd-8bd8-4419c8d3a7e2_c0, rokid_kf_92be3d9e-000e-49da-8450-446cc562555c_c0, rokid_kf_a383f172-ff69-49c3-b131-72c40f90e004_c1, rokid_kf_b034ecf8-ceac-4ee7-8240-e1dbb7498477_c1, rokid_kf_da43e5ab-e2fe-4d7c-a481-79c2b5ed3937_c0, rokid_kf_ec8c1173-b8c1-4a0b-801b-a785f55c21a7_c0
  - failure rate by length: long 4.7 %, medium 16.2 %, short 4.4 %
  - failure rate by day: 2026-10-15 8.3 %, 2026-10-16 25.0 %, 2026-10-17 16.7 %, 2026-10-20 30.0 %, 2026-10-21 0.0 %, 2026-10-22 0.0 %, 2026-10-23 16.7 %, 2026-10-24 25.0 %, 2026-10-25 0.0 %, 2026-10-26 0.0 %, 2026-10-27 0.0 %, 2026-10-28 0.0 %, 2026-10-29 8.3 %, 2026-10-30 0.0 %, 2026-11-01 33.3 %, 2026-11-02 0.0 %, 2026-11-03 0.0 %, 2026-11-04 0.0 %, 2026-11-05 0.0 %, 2026-11-06 0.0 %
  - failure rate by type: None 8.5 %
  - failure rate by review/frame state: OK 8.5 %
  - negative control: 8 queries never captured (vocabulary-checked); returning any hit 0; top score n=8 min=0.000 q1=0.000 median=0.000 q3=0.000 max=0.000; scoring >= median true-hit score (13.561): 0 (0.0 %)
- **phone /api/retrieve/debug HYBRID**
  - phone reported: mode used {'LEXICAL': 200}; semantic unavailable on 200 of 200 queries; fallback (no match, newest memories) on 0
  | query | n | R@1 | R@3 | R@5 | R@10 | MRR@10 |
  |---|---|---|---|---|---|---|
  | tfidf | 100 | 0.820 | 0.920 | 0.930 | 0.980 | 0.875 |
  | paraphrase | 100 | 0.690 | 0.880 | 0.900 | 0.950 | 0.785 |
  - failures (miss or rank > 5): 17 queries over 12 memories; ids: rokid_kf_02fccfa6-46d0-4a42-a077-ac132e325951_c2, rokid_kf_0d0c52c6-93d4-49fe-a817-72910169a775_c0, rokid_kf_2e935e89-c2b3-48f4-bffb-88033c9433e4_c0, rokid_kf_346ad58c-d382-4b1a-992e-0e68728a4ba3_c1, rokid_kf_66e2d3f5-7bc0-4184-8c79-83baf89fa3b2_c2, rokid_kf_7a103e89-9d89-4e96-84bc-4101b1c63d38_c1, rokid_kf_8ab191a0-a7cb-49dd-8bd8-4419c8d3a7e2_c0, rokid_kf_92be3d9e-000e-49da-8450-446cc562555c_c0, rokid_kf_a383f172-ff69-49c3-b131-72c40f90e004_c1, rokid_kf_b034ecf8-ceac-4ee7-8240-e1dbb7498477_c1, rokid_kf_da43e5ab-e2fe-4d7c-a481-79c2b5ed3937_c0, rokid_kf_ec8c1173-b8c1-4a0b-801b-a785f55c21a7_c0
  - failure rate by length: long 4.7 %, medium 16.2 %, short 4.4 %
  - failure rate by day: 2026-10-15 8.3 %, 2026-10-16 25.0 %, 2026-10-17 16.7 %, 2026-10-20 30.0 %, 2026-10-21 0.0 %, 2026-10-22 0.0 %, 2026-10-23 16.7 %, 2026-10-24 25.0 %, 2026-10-25 0.0 %, 2026-10-26 0.0 %, 2026-10-27 0.0 %, 2026-10-28 0.0 %, 2026-10-29 8.3 %, 2026-10-30 0.0 %, 2026-11-01 33.3 %, 2026-11-02 0.0 %, 2026-11-03 0.0 %, 2026-11-04 0.0 %, 2026-11-05 0.0 %, 2026-11-06 0.0 %
  - failure rate by type: None 8.5 %
  - failure rate by review/frame state: OK 8.5 %
  - negative control: 8 queries never captured (vocabulary-checked); returning any hit 0; top score n=8 min=0.000 q1=0.000 median=0.000 q3=0.000 max=0.000; scoring >= median true-hit score (13.561): 0 (0.0 %)
- **LOCAL-BM25 (reference baseline computed by this tool, NOT the phone's retriever)**
  | query | n | R@1 | R@3 | R@5 | R@10 | MRR@10 |
  |---|---|---|---|---|---|---|
  | tfidf | 100 | 0.990 | 1.000 | 1.000 | 1.000 | 0.995 |
  | paraphrase | 100 | 1.000 | 1.000 | 1.000 | 1.000 | 1.000 |
  - failures (miss or rank > 5): 0 queries over 0 memories; ids: 
  - failure rate by length: long 0.0 %, medium 0.0 %, short 0.0 %
  - failure rate by day: 2026-10-15 0.0 %, 2026-10-16 0.0 %, 2026-10-17 0.0 %, 2026-10-20 0.0 %, 2026-10-21 0.0 %, 2026-10-22 0.0 %, 2026-10-23 0.0 %, 2026-10-24 0.0 %, 2026-10-25 0.0 %, 2026-10-26 0.0 %, 2026-10-27 0.0 %, 2026-10-28 0.0 %, 2026-10-29 0.0 %, 2026-10-30 0.0 %, 2026-11-01 0.0 %, 2026-11-02 0.0 %, 2026-11-03 0.0 %, 2026-11-04 0.0 %, 2026-11-05 0.0 %, 2026-11-06 0.0 %
  - failure rate by type: None 0.0 %
  - failure rate by review/frame state: OK 0.0 %
  - negative control: 8 queries never captured (vocabulary-checked); returning any hit 0; top score n=8 min=0.000 q1=0.000 median=0.000 q3=0.000 max=0.000; scoring >= median true-hit score (23.524): 0 (0.0 %)
- **per-mode comparison (all sampled queries; BM25 baseline = the tool's own reference, not the phone)**
  | retriever | R@5 | MRR@10 | neg. false positives | R@5 vs BM25 | MRR vs BM25 |
  |---|---|---|---|---|---|
  | phone /api/retrieve/debug LEXICAL | 0.915 | 0.830 | 0.000 | -0.085 | -0.168 |
  | phone /api/retrieve/debug SEMANTIC | 0.915 | 0.830 | 0.000 | -0.085 | -0.168 |
  | phone /api/retrieve/debug HYBRID | 0.915 | 0.830 | 0.000 | -0.085 | -0.168 |
  | LOCAL-BM25 | 1.000 | 0.998 | 0.000 | +0.000 | +0.000 |
- real asks re-retrieved: 5 views; 14 refused by the forget gate (redacted, or 409 refused); retrieval modes {'LEXICAL': 5}; fallback 5 (100.0 %)
  - hit lexical score: n/a
  - hit semantic score: n/a
  - hit fused score: n/a
  - hit score score: n/a
  - hits per ask: n=5 min=0 q1=0 median=0 q3=0 max=0; in prompt per ask: n=5 min=0 q1=0 median=0 q3=0 max=0

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
- asks read: 19 (newest, cap --max-asks 50); redacted 9; views refused by the forget gate 14 (incl. redacted), failed 0
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
| capture | 97 |
| retrieval (phone) | 90 |
| canvas | 100 |
| output | 94 |

### Prioritised issues

1. [output] 'I don't know'-style answers: 3 of 10 live asks (30.0 %) (priority 0.30)
2. [capture] frames with no live memory: 262 of 2277 frames (11.5 %) (priority 0.23)
3. [capture] duplicate / near-duplicate memories: 64 of 1906 memories (3.4 %) (priority 0.07)

## Unavailable routes

- /api/frames/:id: frame ids rejected by the route's id pattern ^[A-Za-z0-9:_-]{1,64}$ (e.g. '.' in '...v2'): 400 of 400 sampled ids never sent
